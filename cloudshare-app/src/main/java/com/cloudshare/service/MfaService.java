package com.cloudshare.service;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
@Slf4j
public class MfaService {

    private final SecretGenerator secretGenerator;
    private final TimeProvider timeProvider;
    private final CodeGenerator codeGenerator;
    private final CodeVerifier codeVerifier;
    private final QrGenerator qrGenerator;
    private final StringRedisTemplate securityRedisTemplate;

    public MfaService(
            @Qualifier("securityRedisTemplate") StringRedisTemplate securityRedisTemplate) {
        this.securityRedisTemplate = securityRedisTemplate;
        this.secretGenerator = new DefaultSecretGenerator();
        this.timeProvider = new SystemTimeProvider();
        this.codeGenerator = new DefaultCodeGenerator();
        this.codeVerifier = new DefaultCodeVerifier(this.codeGenerator, this.timeProvider);
        this.qrGenerator = new ZxingPngQrGenerator();
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public String generateQrCodeUri(String username, String secret) {
        try {
            QrData data = new QrData.Builder()
                    .label(username)
                    .secret(secret)
                    .issuer("CloudShare")
                    .algorithm(HashingAlgorithm.SHA1)
                    .digits(6)
                    .period(30)
                    .build();

            byte[] qrBytes = qrGenerator.generate(data);
            return "data:" + qrGenerator.getImageMimeType() + ";base64," + Base64.getEncoder().encodeToString(qrBytes);
        } catch (Exception e) {
            log.error("Failed to generate QR code URI for user {}", username, e);
            throw new RuntimeException("Could not generate MFA QR Code", e);
        }
    }

    public boolean verifyCode(String userId, String secret, String code) {
        if (secret == null || code == null || userId == null) {
            return false;
        }
        try {
            String trimmedCode = code.trim();
            if (!codeVerifier.isValidCode(secret, trimmedCode)) {
                return false;
            }

            // Bind the single-use guard to the *code value itself* (hashed, not the
            // raw code, to avoid persisting live OTPs in Redis), not to "now / 30".
            // Keying on the current time-step is unsafe: DefaultCodeVerifier accepts
            // a +/-1 step discrepancy window by default, so a code valid for step T
            // can still validate during step T+1, at which point "now/30" computes a
            // *different* Redis key than the one claimed during step T — allowing the
            // same code to be replayed once per adjacent window. Keying on the code's
            // own value closes this regardless of which step in the discrepancy
            // window it was accepted under.
            String usedKey = "mfa:used:" + userId + ":" + sha256Hex(trimmedCode);

            // TTL must cover the verifier's full discrepancy window (default +/-1
            // step = 3 * 30s = 90s) so a code can't be replayed just after the key
            // expires but while the code is still otherwise valid.
            Boolean firstUse;
            try {
                firstUse = securityRedisTemplate.opsForValue()
                        .setIfAbsent(usedKey, "1", java.time.Duration.ofSeconds(90));
            } catch (Exception e) {
                log.error("MFA anti-replay check unavailable — failing closed for user {}", userId, e);
                return false;
            }

            if (!Boolean.TRUE.equals(firstUse)) {
                log.warn("Rejected replayed TOTP code for user {}", userId);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Error verifying MFA code", e);
            return false;
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
