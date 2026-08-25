package com.cloudshare.service;

import com.cloudshare.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Direct unit tests for the Redis mechanics extracted in v2.0.0 out of FileService and
 * ShareService: cache-aside read/write, eviction, and bypass-marker self-healing. These tests
 * replace the fine-grained Redis-level assertions that previously lived indirectly inside
 * FileServiceTest/ShareServiceTest (which now only verify that those services correctly
 * delegate to this class, mocked as a collaborator).
 */
@ExtendWith(MockitoExtension.class)
class PermissionCacheServiceTest {

    @Mock
    private StringRedisTemplate cacheRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PermissionCacheService permissionCacheService;

    @BeforeEach
    void setUp() {
        permissionCacheService = new PermissionCacheService(cacheRedisTemplate);
    }

    @Test
    void getCachedPermission_hit_returnsPermission() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String cacheKey = "cache:permissions:" + fileId;

        when(cacheRedisTemplate.hasKey("cache:permissions:bypass:" + fileId)).thenReturn(false);
        when(cacheRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(cacheKey, userId.toString())).thenReturn("OWNER");

        Optional<String> result = permissionCacheService.getCachedPermission(fileId, userId);

        assertTrue(result.isPresent());
        assertEquals("OWNER", result.get());
    }

    @Test
    void getCachedPermission_trueMiss_returnsEmpty() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String cacheKey = "cache:permissions:" + fileId;

        when(cacheRedisTemplate.hasKey("cache:permissions:bypass:" + fileId)).thenReturn(false);
        when(cacheRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(cacheKey, userId.toString())).thenReturn(null);
        when(cacheRedisTemplate.hasKey(cacheKey)).thenReturn(false);

        Optional<String> result = permissionCacheService.getCachedPermission(fileId, userId);

        assertTrue(result.isEmpty());
    }

    @Test
    void getCachedPermission_keyExistsButUserMissing_throwsResourceNotFound() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String cacheKey = "cache:permissions:" + fileId;

        when(cacheRedisTemplate.hasKey("cache:permissions:bypass:" + fileId)).thenReturn(false);
        when(cacheRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(cacheKey, userId.toString())).thenReturn(null);
        when(cacheRedisTemplate.hasKey(cacheKey)).thenReturn(true);

        assertThrows(ResourceNotFoundException.class,
                () -> permissionCacheService.getCachedPermission(fileId, userId));
    }

    @Test
    void getCachedPermission_redisErrorOnRead_returnsEmptyForDbFallback() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        when(cacheRedisTemplate.hasKey("cache:permissions:bypass:" + fileId)).thenReturn(false);
        when(cacheRedisTemplate.opsForHash()).thenThrow(new RuntimeException("Redis connection error"));

        Optional<String> result = permissionCacheService.getCachedPermission(fileId, userId);

        assertTrue(result.isEmpty());
    }

    @Test
    void getCachedPermission_bypassMarkerPresent_selfHealFails_returnsEmpty_noCacheReadAttempted() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String cacheKey = "cache:permissions:" + fileId;
        String bypassKey = "cache:permissions:bypass:" + fileId;

        when(cacheRedisTemplate.hasKey(bypassKey)).thenReturn(true);
        doThrow(new RuntimeException("Redis connection error")).when(cacheRedisTemplate).delete(cacheKey);

        Optional<String> result = permissionCacheService.getCachedPermission(fileId, userId);

        assertTrue(result.isEmpty());
        verify(cacheRedisTemplate).delete(cacheKey);
        // Bypass still active after a failed self-heal attempt: no hash read should be attempted.
        verify(cacheRedisTemplate, never()).opsForHash();
    }

    @Test
    void getCachedPermission_bypassMarkerPresent_selfHealSucceeds_resumesNormalCacheRead() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String cacheKey = "cache:permissions:" + fileId;
        String bypassKey = "cache:permissions:bypass:" + fileId;

        when(cacheRedisTemplate.hasKey(bypassKey)).thenReturn(true);
        when(cacheRedisTemplate.delete(cacheKey)).thenReturn(true);
        when(cacheRedisTemplate.delete(bypassKey)).thenReturn(true);
        when(cacheRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(cacheKey, userId.toString())).thenReturn(null);
        when(cacheRedisTemplate.hasKey(cacheKey)).thenReturn(false);

        Optional<String> result = permissionCacheService.getCachedPermission(fileId, userId);

        assertTrue(result.isEmpty());
        verify(cacheRedisTemplate).delete(cacheKey);
        verify(cacheRedisTemplate).delete(bypassKey);
        // Self-heal succeeded, so a normal (miss) cache read should still have been attempted.
        verify(cacheRedisTemplate).opsForHash();
    }

    @Test
    void cachePermissions_writesHashAndSetsExpiry() {
        UUID fileId = UUID.randomUUID();
        String cacheKey = "cache:permissions:" + fileId;
        Map<String, String> permissions = Map.of(UUID.randomUUID().toString(), "OWNER");

        when(cacheRedisTemplate.hasKey("cache:permissions:bypass:" + fileId)).thenReturn(false);
        when(cacheRedisTemplate.opsForHash()).thenReturn(hashOperations);

        permissionCacheService.cachePermissions(fileId, permissions);

        verify(hashOperations).putAll(cacheKey, permissions);
        verify(cacheRedisTemplate).expire(cacheKey, Duration.ofHours(1));
    }

    @Test
    void cachePermissions_bypassActive_skipsWrite() {
        UUID fileId = UUID.randomUUID();
        String bypassKey = "cache:permissions:bypass:" + fileId;
        Map<String, String> permissions = Map.of(UUID.randomUUID().toString(), "OWNER");

        when(cacheRedisTemplate.hasKey(bypassKey)).thenReturn(true);
        doThrow(new RuntimeException("Redis connection error")).when(cacheRedisTemplate)
                .delete("cache:permissions:" + fileId);

        permissionCacheService.cachePermissions(fileId, permissions);

        verify(cacheRedisTemplate, never()).opsForHash();
    }

    @Test
    void evict_success_deletesCacheKey() {
        UUID fileId = UUID.randomUUID();
        String cacheKey = "cache:permissions:" + fileId;

        permissionCacheService.evict(fileId);

        verify(cacheRedisTemplate).delete(cacheKey);
        verifyNoInteractions(valueOperations);
    }

    @Test
    void evict_deleteThrows_setsBypassMarker() {
        UUID fileId = UUID.randomUUID();
        String cacheKey = "cache:permissions:" + fileId;
        String bypassKey = "cache:permissions:bypass:" + fileId;

        doThrow(new RuntimeException("Redis error")).when(cacheRedisTemplate).delete(cacheKey);
        when(cacheRedisTemplate.opsForValue()).thenReturn(valueOperations);

        permissionCacheService.evict(fileId);

        verify(cacheRedisTemplate).delete(cacheKey);
        verify(valueOperations).set(eq(bypassKey), eq("true"), eq(Duration.ofMinutes(10)));
    }

    @Test
    void evict_deleteAndBypassSetBothThrow_doesNotPropagateException() {
        UUID fileId = UUID.randomUUID();
        String cacheKey = "cache:permissions:" + fileId;

        doThrow(new RuntimeException("Redis error")).when(cacheRedisTemplate).delete(cacheKey);
        when(cacheRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("Redis write error")).when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        // Should not throw — callers (FileService.deleteFile, ShareService.evictPermissionsCache)
        // must not have their own operation fail just because cache cleanup is degraded.
        assertDoesNotThrow(() -> permissionCacheService.evict(fileId));
    }
}
