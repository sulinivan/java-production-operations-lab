package com.cloudshare.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Configuration
@Slf4j
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String cacheHost;

    @Value("${spring.data.redis.port:6379}")
    private int cachePort;

    @Value("${spring.data.redis.password:}")
    private String cachePassword;

    @Value("${spring.data.redis.timeout-ms:2000}")
    private long cacheTimeoutMs;

    @Value("${spring.data.redis.pool.max-total:16}")
    private int cachePoolMaxTotal;

    @Value("${spring.data.redis.pool.max-idle:8}")
    private int cachePoolMaxIdle;

    @Value("${spring.data.redis.pool.min-idle:2}")
    private int cachePoolMinIdle;

    @Value("${security.redis.host:localhost}")
    private String securityHost;

    @Value("${security.redis.port:6380}")
    private int securityPort;

    @Value("${security.redis.password:}")
    private String securityPassword;

    @Value("${security.redis.timeout-ms:2000}")
    private long securityTimeoutMs;

    @Value("${security.redis.pool.max-total:16}")
    private int securityPoolMaxTotal;

    @Value("${security.redis.pool.max-idle:8}")
    private int securityPoolMaxIdle;

    @Value("${security.redis.pool.min-idle:2}")
    private int securityPoolMinIdle;

    @Value("${security.rate-limiting.redis.host:localhost}")
    private String rateLimitHost;

    @Value("${security.rate-limiting.redis.port:6381}")
    private int rateLimitPort;

    @Value("${security.rate-limiting.redis.password:}")
    private String rateLimitPassword;

    @Value("${security.rate-limiting.redis.timeout-ms:500}")
    private long rateLimitTimeoutMs;

    @Value("${security.rate-limiting.redis.pool.max-total:16}")
    private int rateLimitPoolMaxTotal;

    @Value("${security.rate-limiting.redis.pool.max-idle:8}")
    private int rateLimitPoolMaxIdle;

    @Value("${security.rate-limiting.redis.pool.min-idle:2}")
    private int rateLimitPoolMinIdle;

    @Primary
    @Bean(name = "cacheConnectionFactory")
    public LettuceConnectionFactory cacheConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(cacheHost, cachePort);
        applyPassword("cache", config, cachePassword);
        LettuceClientConfiguration clientConfig = buildPooledClientConfiguration(
                cacheTimeoutMs, cachePoolMaxTotal, cachePoolMaxIdle, cachePoolMinIdle);
        return new LettuceConnectionFactory(config, clientConfig);
    }

    @Bean(name = "securityConnectionFactory")
    public LettuceConnectionFactory securityConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(securityHost, securityPort);
        applyPassword("security", config, securityPassword);
        LettuceClientConfiguration clientConfig = buildPooledClientConfiguration(
                securityTimeoutMs, securityPoolMaxTotal, securityPoolMaxIdle, securityPoolMinIdle);
        return new LettuceConnectionFactory(config, clientConfig);
    }

    @Bean(name = "rateLimitConnectionFactory")
    public LettuceConnectionFactory rateLimitConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(rateLimitHost, rateLimitPort);
        applyPassword("rate-limit", config, rateLimitPassword);
        LettuceClientConfiguration clientConfig = buildPooledClientConfiguration(
                rateLimitTimeoutMs, rateLimitPoolMaxTotal, rateLimitPoolMaxIdle, rateLimitPoolMinIdle);
        return new LettuceConnectionFactory(config, clientConfig);
    }

    /**
     * Builds an explicit, pooled Lettuce client configuration instead of relying on
     * library defaults. Command timeout and pool sizing are per-instance and
     * environment-overridable (see application.yml) rather than hardcoded, matching
     * this codebase's existing convention for tunables (rate limits, concurrency
     * limits, JWT settings, etc. are all @Value-injected with defaults).
     */
    private LettuceClientConfiguration buildPooledClientConfiguration(
            long timeoutMs, int maxTotal, int maxIdle, int minIdle) {
        // NOTE: must be exactly GenericObjectPoolConfig<StatefulConnection<?, ?>>, not a
        // wildcard GenericObjectPoolConfig<?> — LettucePoolingClientConfigurationBuilder
        // .poolConfig(...) takes that exact invariant generic type (see Spring Data
        // Redis's LettucePoolingClientConfiguration source); a wildcard does not satisfy
        // it and fails to compile.
        GenericObjectPoolConfig<io.lettuce.core.api.StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(maxTotal);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMinIdle(minIdle);

        return LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeoutMs))
                .poolConfig(poolConfig)
                .build();
    }

    /**
     * Applies a password to a Redis connection if one is configured. Logs a loud
     * warning (rather than silently proceeding) if a Redis instance is left
     * unauthenticated, since these instances hold token blacklists, MFA replay
     * guards, and refresh-token families.
     */
    private void applyPassword(String label, RedisStandaloneConfiguration config, String password) {
        if (StringUtils.hasText(password)) {
            config.setPassword(password);
        } else {
            log.warn("Redis instance '{}' is configured WITHOUT a password (requirepass). " +
                    "This is only acceptable when network isolation is guaranteed. " +
                    "Set the corresponding *_REDIS_PASSWORD environment variable.", label);
        }
    }

    @Primary
    @Bean(name = "redisTemplate")
    public StringRedisTemplate cacheRedisTemplate(
            @Qualifier("cacheConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean(name = "securityRedisTemplate")
    public StringRedisTemplate securityRedisTemplate(
            @Qualifier("securityConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean(name = "rateLimitRedisTemplate")
    public StringRedisTemplate rateLimitRedisTemplate(
            @Qualifier("rateLimitConnectionFactory") LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
