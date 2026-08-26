package com.cloudshare.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private StringRedisTemplate securityRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private RefreshTokenService refreshTokenService;
    private final long expirationSeconds = 604800; // 7 days

    @BeforeEach
    void setUp() {
        lenient().when(securityRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(securityRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        refreshTokenService = new RefreshTokenService(securityRedisTemplate, expirationSeconds);
    }

    @Test
    void createRefreshToken_success() {
        UUID userId = UUID.randomUUID();

        String token = refreshTokenService.createRefreshToken(userId);

        assertNotNull(token);
        verify(valueOperations).set(eq("refresh:active:" + token), eq(userId.toString()), any(Duration.class));
        verify(valueOperations).set(eq("refresh:metadata:" + token), eq(userId.toString()), any(Duration.class));
        verify(zSetOperations).add(eq("refresh:family:" + userId), eq(token), anyDouble());
        verify(securityRedisTemplate).expire(eq("refresh:family:" + userId), any(Duration.class));
        verify(zSetOperations).removeRangeByScore(eq("refresh:family:" + userId), eq(Double.NEGATIVE_INFINITY),
                anyDouble());
    }

    @Test
    void rotateRefreshToken_success() {
        String oldToken = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();

        // Mock old token is active
        when(valueOperations.getAndDelete("refresh:active:" + oldToken)).thenReturn(userId.toString());

        RefreshTokenService.TokenRotationResult result = refreshTokenService.rotateRefreshToken(oldToken);

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
        assertNotNull(result.getNewTokenId());
        assertNotEquals(oldToken, result.getNewTokenId());

        // Verify new token is created and stored
        verify(valueOperations).set(eq("refresh:active:" + result.getNewTokenId()), eq(userId.toString()),
                any(Duration.class));
    }

    @Test
    void rotateRefreshToken_reuseAttack_revokesAll() {
        String leakedOldToken = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        String otherToken = UUID.randomUUID().toString();

        // 1. Mock old token is NOT active
        when(valueOperations.getAndDelete("refresh:active:" + leakedOldToken)).thenReturn(null);
        // 2. Mock historic owner is found in metadata
        when(valueOperations.get("refresh:metadata:" + leakedOldToken)).thenReturn(userId.toString());
        // 3. Mock token is member of family (returns score)
        when(zSetOperations.score("refresh:family:" + userId, leakedOldToken)).thenReturn(12345.0);
        // 4. Mock set members for family revocation
        when(zSetOperations.range("refresh:family:" + userId, 0, -1)).thenReturn(Set.of(leakedOldToken, otherToken));

        // When/Then
        assertThrows(SecurityException.class, () -> refreshTokenService.rotateRefreshToken(leakedOldToken));

        // Verify entire family is revoked
        verify(securityRedisTemplate).delete("refresh:active:" + leakedOldToken);
        verify(securityRedisTemplate).delete("refresh:metadata:" + leakedOldToken);
        verify(securityRedisTemplate).delete("refresh:active:" + otherToken);
        verify(securityRedisTemplate).delete("refresh:metadata:" + otherToken);
        verify(securityRedisTemplate).delete("refresh:family:" + userId);
    }

    @Test
    void rotateRefreshToken_invalidToken_throwsException() {
        String invalidToken = "invalid_token";

        // Mock both active and metadata are null
        when(valueOperations.getAndDelete("refresh:active:" + invalidToken)).thenReturn(null);
        when(valueOperations.get("refresh:metadata:" + invalidToken)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> refreshTokenService.rotateRefreshToken(invalidToken));
    }

    @Test
    void revokeToken_success() {
        String token = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();

        when(valueOperations.get("refresh:active:" + token)).thenReturn(userId.toString());

        refreshTokenService.revokeToken(token);

        verify(securityRedisTemplate).delete("refresh:active:" + token);
        verify(securityRedisTemplate).delete("refresh:metadata:" + token);
        verify(zSetOperations).remove("refresh:family:" + userId, token);
    }

    @Test
    void testPruning_boundaryConditions() {
        UUID userId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        refreshTokenService.createRefreshToken(userId);

        double expectedCutoff = (double) (now - (expirationSeconds * 1000L));
        verify(zSetOperations).removeRangeByScore(
                eq("refresh:family:" + userId),
                eq(Double.NEGATIVE_INFINITY),
                doubleThat(score -> score != null && Math.abs(score - expectedCutoff) < 5000.0));
    }
}
