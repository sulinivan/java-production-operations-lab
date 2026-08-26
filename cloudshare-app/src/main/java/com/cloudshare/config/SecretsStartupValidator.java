package com.cloudshare.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * Startup component that validates database passwords, JWT secrets, MinIO credentials, and Master KEK shapes.
 * <p>
 * <b>Why validation happens at startup (Fail-Closed stance):</b> Cryptographic key shapes and operational secrets
 * are evaluated during application initialization ({@link PostConstruct}). If an insecure default secret, an undersized password,
 * or a malformed KEK (not exactly 32 bytes / 256 bits after Base64 decoding) is detected, startup fails immediately with an
 * {@link IllegalStateException}. This prevents serving traffic with insecure configurations or corrupting encrypted data.
 * </p>
 * <p>
 * <b>Opt-In Raw Passphrase Fallback:</b> If an administrator intentionally configures a raw passphrase KEK instead of a 32-byte
 * Base64-encoded key, they must explicitly set {@code crypto.kek.allow-raw-passphrase=true}. Otherwise, non-32-byte KEKs trigger
 * a hard startup abort.
 * </p>
 */
@Component
@Slf4j
public class SecretsStartupValidator {

    @Autowired
    private CryptoProperties cryptoProperties;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${security.jwt.secret:}")
    private String jwtSecret;

    @Value("${storage.minio.access-key:}")
    private String minioAccessKey;

    @Value("${storage.minio.secret-key:}")
    private String minioSecretKey;

    @Value("${crypto.master-kek:}")
    private String masterKek;

    @Value("${spring.data.redis.password:}")
    private String cacheRedisPassword;

    @Value("${security.redis.password:}")
    private String securityRedisPassword;

    @Value("${security.rate-limiting.redis.password:}")
    private String rateLimitRedisPassword;

    @Value("${security.redis.require-password:true}")
    private boolean requireRedisPassword;

    private static final Set<String> FORMER_DEFAULTS = Set.of(
            "StrongDBPassword123!",
            "32ByteSecretKeyForHMACSHA256SignatureAuthenticationOfCloudShareTokens",
            "minioadmin",
            "your_crypto_master_kek_32_bytes"
    );

    @PostConstruct
    public void validateSecrets() {
        log.info("Validating application secrets on startup...");

        validateSecret("spring.datasource.password", dbPassword, 8);
        validateSecret("security.jwt.secret", jwtSecret, 64);
        validateSecret("storage.minio.access-key", minioAccessKey, 5);
        validateSecret("storage.minio.secret-key", minioSecretKey, 8);
        validateSecret("crypto.master-kek", masterKek, 32);

        validateKekShape();
        validateRedisPasswords();

        log.info("All application secrets validated successfully.");
    }

    /**
     * Fails startup if any Redis instance is unauthenticated, unless explicitly
     * overridden via {@code security.redis.require-password=false} (e.g. for a
     * local dev profile relying solely on Docker network isolation). All three
     * Redis instances hold security-sensitive state (token blacklists, MFA
     * replay guards, refresh-token families, rate-limit counters).
     */
    private void validateRedisPasswords() {
        if (!requireRedisPassword) {
            log.warn("security.redis.require-password=false — starting with potentially unauthenticated " +
                    "Redis connections. This should only be used in local development.");
            return;
        }
        requireNonBlank("spring.data.redis.password", cacheRedisPassword);
        requireNonBlank("security.redis.password", securityRedisPassword);
        requireNonBlank("security.rate-limiting.redis.password", rateLimitRedisPassword);
    }

    private void requireNonBlank(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(String.format(
                    "Secret '%s' is required but is blank. Set security.redis.require-password=false only for " +
                            "local development with network-isolated Redis instances.",
                    name));
        }
    }

    private void validateKekShape() {
        if (cryptoProperties == null) {
            return;
        }

        boolean allowRaw = cryptoProperties.getKek() != null && cryptoProperties.getKek().isAllowRawPassphrase();

        // Validate master KEK if it is configured and not already overridden by version 1 in keks map
        String masterKekVal = cryptoProperties.getMasterKek();
        if (masterKekVal != null && !masterKekVal.trim().isEmpty() && !cryptoProperties.getKeks().containsKey(1)) {
            checkKekShape("master KEK", masterKekVal, allowRaw);
        }

        // Validate all versioned KEKs in the map
        cryptoProperties.getKeks().forEach((version, kekVal) -> {
            if (kekVal != null && !kekVal.trim().isEmpty()) {
                checkKekShape("version " + version, kekVal, allowRaw);
            }
        });
    }

    private void checkKekShape(String versionLabel, String kekStr, boolean allowRaw) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(kekStr.trim());
        } catch (IllegalArgumentException e) {
            keyBytes = kekStr.getBytes(StandardCharsets.UTF_8);
        }

        if (keyBytes.length != 32) {
            if (!allowRaw) {
                throw new IllegalStateException(String.format(
                        "KEK for %s is not exactly 32 bytes after Base64 decoding. " +
                        "If you intend to use a raw passphrase and digest it, set crypto.kek.allow-raw-passphrase=true.",
                        versionLabel));
            } else {
                log.warn("KEK for {} is not exactly 32 bytes after Base64 decoding. " +
                        "It will be digested using SHA-256 fallback because crypto.kek.allow-raw-passphrase=true.",
                        versionLabel);
            }
        }
    }

    private void validateSecret(String name, String value, int minLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(String.format("Secret '%s' is required but is blank.", name));
        }

        String trimmed = value.trim();

        if (FORMER_DEFAULTS.contains(trimmed)) {
            throw new IllegalStateException(String.format(
                    "Secret '%s' is using a known insecure former default value. Please change this value.", name));
        }

        if (trimmed.length() < minLength) {
            throw new IllegalStateException(String.format(
                    "Secret '%s' is too short (length: %d, required: at least %d).",
                    name, trimmed.length(), minLength));
        }
    }
}
