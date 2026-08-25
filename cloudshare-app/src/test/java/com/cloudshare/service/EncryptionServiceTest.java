package com.cloudshare.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cloudshare.config.CryptoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

    private EncryptionService encryptionService;
    private static final String TEST_KEK = "32ByteSecretKeyForHMACSHA256SignatureAuthenticationOfCloudShareTokens";

    @BeforeEach
    void setUp() {
        CryptoProperties cryptoProperties = new CryptoProperties();
        cryptoProperties.setMasterKek(TEST_KEK);
        cryptoProperties.getKek().setAllowRawPassphrase(true);
        encryptionService = new EncryptionService(cryptoProperties);
    }

    @Test
    void fekGeneration_andWrapping_success() throws Exception {
        // Generate FEK
        SecretKey originalFek = encryptionService.generateFek();
        assertNotNull(originalFek);
        assertEquals("AES", originalFek.getAlgorithm());
        assertEquals(32, originalFek.getEncoded().length); // 256 bits

        // Wrap the FEK using Master KEK v1
        String wrappedFek = encryptionService.wrapFek(originalFek, 1);
        assertNotNull(wrappedFek);
        assertNotEquals(Base64.getEncoder().encodeToString(originalFek.getEncoded()), wrappedFek);

        // Unwrap the FEK using Master KEK v1
        SecretKey unwrappedFek = encryptionService.unwrapFek(wrappedFek, 1);
        assertNotNull(unwrappedFek);
        assertArrayEquals(originalFek.getEncoded(), unwrappedFek.getEncoded());
    }

    @Test
    void encryptAndDecryptStream_success() throws Exception {
        String originalText = "This is a very sensitive message containing corporate secrets.";
        byte[] originalBytes = originalText.getBytes(StandardCharsets.UTF_8);

        SecretKey fek = encryptionService.generateFek();
        byte[] iv = encryptionService.generateIv();

        // Encrypt stream
        ByteArrayInputStream plaintextIn = new ByteArrayInputStream(originalBytes);
        ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream();
        encryptionService.encryptStream(plaintextIn, encryptedOut, fek, iv);

        byte[] encryptedBytes = encryptedOut.toByteArray();
        assertNotNull(encryptedBytes);
        assertTrue(encryptedBytes.length > 0);
        assertNotEquals(originalText, new String(encryptedBytes, StandardCharsets.UTF_8));

        // Decrypt stream
        ByteArrayInputStream encryptedIn = new ByteArrayInputStream(encryptedBytes);
        ByteArrayOutputStream decryptedOut = new ByteArrayOutputStream();
        encryptionService.decryptStreamFully(encryptedIn, decryptedOut, fek, iv);

        String decryptedText = decryptedOut.toString(StandardCharsets.UTF_8);
        assertEquals(originalText, decryptedText);
    }

    @Test
    void testNoWarningFor32ByteKek() throws Exception {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKek(Base64.getEncoder().encodeToString(new byte[32]));

        EncryptionService service = new EncryptionService(properties);

        Logger logger = (Logger) LoggerFactory.getLogger(EncryptionService.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        try {
            SecretKey fek = service.generateFek();
            String wrapped = service.wrapFek(fek, 1);
            assertNotNull(wrapped);
        } finally {
            logger.detachAppender(listAppender);
        }

        List<ILoggingEvent> logs = listAppender.list;
        boolean hasWarn = logs.stream().anyMatch(event -> event.getLevel().toString().equals("WARN"));
        assertFalse(hasWarn, "Should not log a warning for a valid 32-byte KEK");
    }

    @Test
    void testWarningLoggedExactlyOnceForNon32ByteKek() throws Exception {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKek("short_key");
        properties.getKek().setAllowRawPassphrase(true);

        EncryptionService service = new EncryptionService(properties);

        Logger logger = (Logger) LoggerFactory.getLogger(EncryptionService.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);

        try {
            SecretKey fek = service.generateFek();
            String wrapped = service.wrapFek(fek, 1);
            assertNotNull(wrapped);

            SecretKey unwrapped = service.unwrapFek(wrapped, 1);
            assertNotNull(unwrapped);
        } finally {
            logger.detachAppender(listAppender);
        }

        List<ILoggingEvent> logs = listAppender.list;
        long warnCount = logs.stream()
                .filter(event -> event.getLevel().toString().equals("WARN"))
                .filter(event -> event.getFormattedMessage().contains("not exactly 32 bytes"))
                .count();

        assertEquals(1, warnCount, "Should log a warning exactly once for the non-32-byte KEK");
    }

    @Test
    void getMasterKek_nonStandardKeyLength_allowRawPassphraseFalse_throwsIllegalStateException() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKek("short_key");
        properties.getKek().setAllowRawPassphrase(false);
        EncryptionService service = new EncryptionService(properties);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "getMasterKek", 1));
        assertTrue(ex.getMessage().contains(
                "is not 32 bytes after Base64 decoding and crypto.kek.allow-raw-passphrase is disabled. Refusing to derive a key via digest fallback at runtime"));
    }

    @Test
    void getMasterKek_nonStandardKeyLength_allowRawPassphraseTrue_digestsSuccessfully() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKek("short_key");
        properties.getKek().setAllowRawPassphrase(true);
        EncryptionService service = new EncryptionService(properties);

        SecretKey key = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "getMasterKek", 1);
        assertNotNull(key);
        assertEquals(32, key.getEncoded().length);
    }

    @Test
    void getMasterKek_standardKeyLength_allowRawPassphraseFalse_noDigestApplied() {
        CryptoProperties properties = new CryptoProperties();
        byte[] rawKey = new byte[32];
        for (int i = 0; i < 32; i++) {
            rawKey[i] = (byte) i;
        }
        String validKek = Base64.getEncoder().encodeToString(rawKey);
        properties.setMasterKek(validKek);
        properties.getKek().setAllowRawPassphrase(false);
        EncryptionService service = new EncryptionService(properties);

        SecretKey key = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "getMasterKek", 1);
        assertNotNull(key);
        assertEquals(32, key.getEncoded().length);
        assertArrayEquals(rawKey, key.getEncoded());
    }
}
