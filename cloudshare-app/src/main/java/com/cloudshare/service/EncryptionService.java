package com.cloudshare.service;

import com.cloudshare.config.CryptoProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service performing cryptographic envelope encryption operations for stored
 * files.
 * <p>
 * <b>Cryptographic Design Rationale:</b>
 * <ul>
 * <li><b>Envelope Encryption:</b> Each file is encrypted with a unique,
 * randomly generated 256-bit AES File Encryption Key (FEK).
 * The FEK is then wrapped using an RFC 3394 AESWrap Key Encryption Key (KEK).
 * This ensures that a single FEK compromise affects only
 * one file, and allows KEK rotation (migrating files to a new master key
 * version) by re-wrapping FEKs without re-encrypting raw file contents.</li>
 * <li><b>Authenticated Streaming Encryption:</b> Stream encryption and
 * decryption use AES-256-GCM (Galois/Counter Mode) with 12-byte IVs
 * and 128-bit authentication tags. This guarantees both confidentiality and
 * ciphertext tamper-detection prior to releasing plaintext to clients.</li>
 * <li><b>KEK Key Shape Enforcement:</b> KEKs are expected to be 32
 * Base64-decoded bytes. If a raw non-32-byte passphrase is supplied,
 * it is digested via SHA-256 only when permitted by configuration.</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EncryptionService {

    private final CryptoProperties cryptoProperties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<Integer, SecretKey> resolvedKeks = new ConcurrentHashMap<>();
    private final Set<Integer> loggedDigestWarnings = ConcurrentHashMap.newKeySet();

    private SecretKey getMasterKek(int version) {
        return resolvedKeks.computeIfAbsent(version, v -> {
            String kekStr = cryptoProperties.getKeks().get(v);
            if (kekStr == null) {
                if (v == 1) {
                    kekStr = cryptoProperties.getMasterKek();
                }
                if (kekStr == null) {
                    throw new IllegalArgumentException("No KEK configured for version: " + v);
                }
            }

            byte[] keyBytes;
            try {
                // Attempt to decode KEK as Base64 first
                keyBytes = Base64.getDecoder().decode(kekStr.trim());
            } catch (IllegalArgumentException e) {
                // Fallback to raw UTF-8 bytes if not valid Base64
                keyBytes = kekStr.getBytes(StandardCharsets.UTF_8);
            }

            // If the key is not exactly 256 bits (32 bytes), digest it to enforce
            // correctness
            if (keyBytes.length != 32) {
                if (!cryptoProperties.getKek().isAllowRawPassphrase()) {
                    throw new IllegalStateException(
                            "KEK version " + v + " is not 32 bytes after Base64 decoding and " +
                                    "crypto.kek.allow-raw-passphrase is disabled. Refusing to derive a key via digest "
                                    +
                                    "fallback at runtime, to stay consistent with SecretsStartupValidator's fail-closed "
                                    +
                                    "startup contract.");
                }
                if (loggedDigestWarnings.add(v)) {
                    log.warn("KEK for version {} is not exactly 32 bytes after Base64 decoding. " +
                            "Digesting using SHA-256 fallback (crypto.kek.allow-raw-passphrase=true).", v);
                }
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    keyBytes = digest.digest(keyBytes);
                } catch (NoSuchAlgorithmException ex) {
                    throw new IllegalStateException("SHA-256 digest algorithm is unavailable", ex);
                }
            }
            return new SecretKeySpec(keyBytes, "AES");
        });
    }

    public int getCurrentKekVersion() {
        return cryptoProperties.getCurrentKekVersion();
    }

    public SecretKey generateFek() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256, secureRandom);
            return keyGen.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialize AES KeyGenerator", e);
        }
    }

    public byte[] generateIv() {
        byte[] iv = new byte[12]; // 12 bytes standard GCM IV
        secureRandom.nextBytes(iv);
        return iv;
    }

    public String wrapFek(SecretKey fek, int kekVersion) throws GeneralSecurityException {
        SecretKey masterKek = getMasterKek(kekVersion);
        Cipher cipher = Cipher.getInstance("AESWrap");
        cipher.init(Cipher.WRAP_MODE, masterKek);
        byte[] wrappedBytes = cipher.wrap(fek);
        return Base64.getEncoder().encodeToString(wrappedBytes);
    }

    public SecretKey unwrapFek(String wrappedFekBase64, int kekVersion) throws GeneralSecurityException {
        SecretKey masterKek = getMasterKek(kekVersion);
        byte[] wrappedBytes = Base64.getDecoder().decode(wrappedFekBase64);
        Cipher cipher = Cipher.getInstance("AESWrap");
        cipher.init(Cipher.UNWRAP_MODE, masterKek);
        return (SecretKey) cipher.unwrap(wrappedBytes, "AES", Cipher.SECRET_KEY);
    }

    public void encryptStream(InputStream plaintextIn, OutputStream encryptedOut, SecretKey fek, byte[] iv)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, fek, parameterSpec);

        try (CipherOutputStream cos = new CipherOutputStream(encryptedOut, cipher)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = plaintextIn.read(buffer)) != -1) {
                cos.write(buffer, 0, read);
            }
            cos.flush();
        }
    }

    public void decryptStreamFully(InputStream encryptedIn, OutputStream decryptedOut, SecretKey fek, byte[] iv)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, fek, parameterSpec);

        byte[] buffer = new byte[8192];
        int read;
        while ((read = encryptedIn.read(buffer)) != -1) {
            byte[] decryptedChunk = cipher.update(buffer, 0, read);
            if (decryptedChunk != null) {
                decryptedOut.write(decryptedChunk);
            }
        }
        byte[] finalChunk = cipher.doFinal();
        if (finalChunk != null) {
            decryptedOut.write(finalChunk);
        }
        decryptedOut.flush();
    }
}
