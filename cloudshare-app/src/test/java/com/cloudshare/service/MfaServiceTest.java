package com.cloudshare.service;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MfaServiceTest {

    private MfaService mfaService;
    private CodeGenerator codeGenerator;
    private TimeProvider timeProvider;

    @Mock
    private StringRedisTemplate securityRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(securityRedisTemplate.opsForValue()).thenReturn(valueOperations);
        mfaService = new MfaService(securityRedisTemplate);
        codeGenerator = new DefaultCodeGenerator();
        timeProvider = new SystemTimeProvider();
    }

    @Test
    void testGenerateSecret() {
        String secret = mfaService.generateSecret();
        assertNotNull(secret);
        assertEquals(32, secret.length());
    }

    @Test
    void testGenerateQrCodeUri() {
        String secret = mfaService.generateSecret();
        String qrUri = mfaService.generateQrCodeUri("testuser", secret);
        assertNotNull(qrUri);
        assertTrue(qrUri.startsWith("data:image/png;base64,"));
    }

    @Test
    void testVerifyCodeSuccess() throws Exception {
        String secret = mfaService.generateSecret();
        long time = timeProvider.getTime();
        long counter = Math.floorDiv(time, 30);
        String correctCode = codeGenerator.generate(secret, counter);

        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertTrue(mfaService.verifyCode("test-user-id", secret, correctCode));
    }

    @Test
    void testVerifyCodeFailure() {
        String secret = mfaService.generateSecret();
        assertFalse(mfaService.verifyCode("test-user-id", secret, "000000"));
        assertFalse(mfaService.verifyCode("test-user-id", secret, "invalid"));
        assertFalse(mfaService.verifyCode("test-user-id", null, "123456"));
    }

    @Test
    void verifyCode_validCodeFirstUse_returnsTrue() throws Exception {
        String secret = mfaService.generateSecret();
        long time = timeProvider.getTime();
        long counter = Math.floorDiv(time, 30);
        String correctCode = codeGenerator.generate(secret, counter);
        String userId = "test-user-id";

        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        assertTrue(mfaService.verifyCode(userId, secret, correctCode));
    }

    @Test
    void verifyCode_validCodeReplayedSameTimeStep_secondCallReturnsFalse() throws Exception {
        String secret = mfaService.generateSecret();
        long time = timeProvider.getTime();
        long counter = Math.floorDiv(time, 30);
        String correctCode = codeGenerator.generate(secret, counter);
        String userId = "test-user-id";

        // First call returns true, second returns false
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true)
                .thenReturn(false);

        // Verify first use returns true
        assertTrue(mfaService.verifyCode(userId, secret, correctCode));

        // Verify second use (replay) returns false
        assertFalse(mfaService.verifyCode(userId, secret, correctCode));

        // Verify setIfAbsent was called exactly twice
        verify(valueOperations, times(2)).setIfAbsent(anyString(), eq("1"), any(Duration.class));
    }

    @Test
    void verifyCode_invalidCodeReplay_neitherCallMarksUsedKey() {
        String secret = mfaService.generateSecret();
        String userId = "test-user-id";
        String incorrectCode = "000000";

        assertFalse(mfaService.verifyCode(userId, secret, incorrectCode));
        assertFalse(mfaService.verifyCode(userId, secret, incorrectCode));

        // Verify setIfAbsent was never called since validation failed first
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void verifyCode_redisUnavailable_failsAsConfigured() throws Exception {
        String secret = mfaService.generateSecret();
        long time = timeProvider.getTime();
        long counter = Math.floorDiv(time, 30);
        String correctCode = codeGenerator.generate(secret, counter);
        String userId = "test-user-id";

        // Mock Redis to throw an exception
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertFalse(mfaService.verifyCode(userId, secret, correctCode));
    }
}
