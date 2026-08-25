package com.cloudshare.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsStartupValidatorIntegrationTest {

    @Configuration
    @Import(SecretsStartupValidator.class)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(CryptoProperties.class)
    static class TestConfig {
    }

    @Test
    void testStartupFailsWithInvalidKekAndAllowRawPassphraseFalse() {
        Exception exception = assertThrows(Exception.class, () -> {
            new SpringApplicationBuilder(TestConfig.class)
                    .web(org.springframework.boot.WebApplicationType.NONE)
                    .profiles("test")
                    .run("--crypto.master-kek=123456789012345678901234567890123", "--crypto.kek.allow-raw-passphrase=false");
        });

        // The root cause should be the IllegalStateException thrown by
        // SecretsStartupValidator
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }

        assertTrue(rootCause instanceof IllegalStateException);
        assertTrue(rootCause.getMessage().contains("is not exactly 32 bytes after Base64 decoding"));
    }
}
