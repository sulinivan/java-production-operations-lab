package com.cloudshare.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    private StringRedisTemplate rateLimitRedisTemplate;

    @Mock
    private StringRedisTemplate securityRedisTemplate;

    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        rateLimiterService = new RateLimiterService(rateLimitRedisTemplate);
    }

    @SuppressWarnings("unchecked")
    @Test
    void isAllowed_success_usesRateLimitTemplateOnly() {
        // Mock the execute command to return 1 (allowed)
        when(rateLimitRedisTemplate.execute(
                any(RedisScript.class),
                any(List.class),
                anyString(), anyString(), anyString())).thenReturn(1L);

        boolean allowed = rateLimiterService.isAllowed("test-key", 60, 10);

        assertTrue(allowed);

        // Verify that rateLimitRedisTemplate is called
        verify(rateLimitRedisTemplate).execute(
                any(RedisScript.class),
                eq(Collections.singletonList("test-key")),
                anyString(), eq("60"), eq("10"));

        // Verify that securityRedisTemplate is never touched at all
        verifyNoInteractions(securityRedisTemplate);
    }
}
