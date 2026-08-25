package com.cloudshare.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;

@Service
@Slf4j
public class RateLimiterService {

    private final StringRedisTemplate rateLimitRedisTemplate;
    private final RedisScript<Long> rateLimitScript;

    public RateLimiterService(@Qualifier("rateLimitRedisTemplate") StringRedisTemplate rateLimitRedisTemplate) {
        this.rateLimitRedisTemplate = rateLimitRedisTemplate;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/rate_limit.lua"));
        script.setResultType(Long.class);
        this.rateLimitScript = script;
    }

    public boolean isAllowed(String key, int windowSeconds, int limit) {
        try {
            long now = Instant.now().getEpochSecond();
            Long result = rateLimitRedisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(windowSeconds),
                    String.valueOf(limit));
            return result != null && result == 1L;
        } catch (Exception e) {
            log.error("Failed to evaluate rate limit for key {}", key, e);
            // Fallback to true (allow request) to prevent rate limiting from taking down
            // service during Redis failure
            return true;
        }
    }
}
