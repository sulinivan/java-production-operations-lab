package com.cloudshare.service;

import com.cloudshare.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the Redis mechanics behind the file-permission cache: the cache-aside read/write
 * pattern, the bypass-marker self-healing fallback used when eviction fails, and the two
 * key patterns ({@code cache:permissions:<file_id>}, {@code cache:permissions:bypass:<file_id>})
 * that back it.
 * <p>
 * This class deliberately does NOT know about {@code FileRepository}/{@code FileShareRepository}
 * or how to resolve a permission on a cache miss — that orchestration (query the DB, then call
 * back into this class to warm the cache) stays in {@link FileService#verifyFileAccess}. A cache
 * service reaching into repositories directly would blur its responsibility; keeping it strictly
 * about the Redis mechanics is what makes it safe to share between {@link FileService} and
 * {@link ShareService}.
 * <p>
 * Extracted in v2.0.0 to remove duplication: this exact eviction-with-bypass-marker pattern
 * previously existed independently in {@code FileService.deleteFile} and
 * {@code ShareService.evictPermissionsCache}, and the cache-read-with-bypass-self-heal pattern
 * existed only in {@code FileService.verifyFileAccess}. Any future change to TTLs, key naming,
 * or the self-healing behavior now only needs to happen in one place.
 */
@Service
@Slf4j
public class PermissionCacheService {

    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration BYPASS_MARKER_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate cacheRedisTemplate;

    public PermissionCacheService(@Qualifier("redisTemplate") StringRedisTemplate cacheRedisTemplate) {
        this.cacheRedisTemplate = cacheRedisTemplate;
    }

    private String cacheKey(UUID fileId) {
        return "cache:permissions:" + fileId;
    }

    private String bypassKey(UUID fileId) {
        return "cache:permissions:bypass:" + fileId;
    }

    /**
     * Attempts to resolve a user's permission for a file from cache.
     *
     * @return {@code Optional.of(permission)} if the cache has an explicit entry for this user
     *         (e.g. "OWNER"/"READ"/"WRITE"); {@code Optional.empty()} if the cache should be
     *         bypassed, was a true miss, or errored — the caller must fall back to the database
     *         and then call {@link #cachePermissions} to warm the cache.
     * @throws ResourceNotFoundException if the cache explicitly confirms no access exists for
     *         this user (the file's permission hash exists in cache, but this user isn't in it)
     *         — a fast-path deny that avoids an unnecessary DB round trip.
     */
    public Optional<String> getCachedPermission(UUID fileId, UUID userId) {
        if (isBypassActiveWithSelfHeal(fileId)) {
            return Optional.empty();
        }

        String cacheKey = cacheKey(fileId);
        try {
            String cachedPermission = (String) cacheRedisTemplate.opsForHash().get(cacheKey, userId.toString());
            if (cachedPermission != null) {
                return Optional.of(cachedPermission);
            }

            // Key exists but user not in hash: cache explicitly confirms no access, distinct
            // from "not cached at all" — avoid a DB round trip for a known-denied user.
            Boolean keyExists = cacheRedisTemplate.hasKey(cacheKey);
            if (Boolean.TRUE.equals(keyExists)) {
                throw new ResourceNotFoundException("File not found or access denied");
            }
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // Distinct from [PERMISSION_CACHE_EVICTION_FAILED]: this is a read-path miss that
            // safely falls back to the database as source of truth (no stale-permission risk),
            // whereas eviction failures risk serving an over-permissive cached entry.
            log.warn("[PERMISSION_CACHE_READ_FAILED] Redis error during permission check, falling back to database", e);
        }

        return Optional.empty();
    }

    /**
     * Warms the cache after a database fallback resolved a file's permission map. Re-checks the
     * bypass marker before writing (a second Redis round trip beyond what {@link #getCachedPermission}
     * already did) so a cache write can never race a delete-then-bypass sequence and leave a
     * stale, over-permissive entry behind — this only runs on the already-rare cache-miss path,
     * so the extra round trip is a deliberate correctness-over-latency tradeoff, not an oversight.
     */
    public void cachePermissions(UUID fileId, Map<String, String> permissionsByUserId) {
        if (isBypassActiveWithSelfHeal(fileId)) {
            return;
        }
        String cacheKey = cacheKey(fileId);
        try {
            cacheRedisTemplate.opsForHash().putAll(cacheKey, permissionsByUserId);
            cacheRedisTemplate.expire(cacheKey, CACHE_TTL);
        } catch (Exception e) {
            log.error("Failed to write permissions to Redis cache", e);
        }
    }

    /**
     * Evicts a file's cached permissions, e.g. after a share is created/modified/revoked or the
     * file is deleted. If eviction itself fails, sets a time-bounded bypass marker so subsequent
     * reads fall back to the database rather than risk serving a stale, over-permissive cached
     * entry until the marker is next self-healed or naturally expires.
     */
    public void evict(UUID fileId) {
        String cacheKey = cacheKey(fileId);
        try {
            cacheRedisTemplate.delete(cacheKey);
            log.debug("Evicted permissions cache for file: {}", fileId);
        } catch (Exception e) {
            log.error("[PERMISSION_CACHE_EVICTION_FAILED] Failed to evict permissions cache for file: {}. Setting bypass marker.",
                    fileId, e);
            try {
                cacheRedisTemplate.opsForValue().set(bypassKey(fileId), "true", BYPASS_MARKER_TTL);
            } catch (Exception ex) {
                log.error("[PERMISSION_CACHE_EVICTION_FAILED] Failed to set bypass marker for file: {}", fileId, ex);
            }
        }
    }

    /**
     * Checks whether a bypass marker is set for a file, attempting to self-heal (retry the
     * original eviction that failed) before reporting the bypass as still active. Returns
     * {@code true} (bypass active — caller must skip cache read/write and go to the database)
     * both when the marker is genuinely set and unhealable, and when Redis itself errors while
     * checking — fail-safe in the direction of the database being consulted, never in the
     * direction of trusting a cache that might be stale.
     */
    private boolean isBypassActiveWithSelfHeal(UUID fileId) {
        String cacheKey = cacheKey(fileId);
        String bypassKey = bypassKey(fileId);
        try {
            Boolean hasBypass = cacheRedisTemplate.hasKey(bypassKey);
            if (Boolean.TRUE.equals(hasBypass)) {
                try {
                    cacheRedisTemplate.delete(cacheKey);
                    cacheRedisTemplate.delete(bypassKey);
                    log.info("Self-healing retry of cache eviction succeeded for file: {}", fileId);
                    return false;
                } catch (Exception retryEx) {
                    log.error("[PERMISSION_CACHE_EVICTION_FAILED] Self-healing retry of cache eviction failed for file: {}", fileId, retryEx);
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("[PERMISSION_CACHE_EVICTION_FAILED] Redis error checking bypass marker for file: {}", fileId, e);
            return true;
        }
    }
}
