package com.cloudshare.service;

import com.cloudshare.config.CryptoProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceVersionedTest {

    @Test
    void testVersionedKeyOperations() throws Exception {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKek("fallback_key_for_v1_32_bytes_long");
        properties.getKek().setAllowRawPassphrase(true);

        Map<Integer, String> keks = new HashMap<>();
        keks.put(1, "original_kek_version_1_32_bytes");
        keks.put(2, "new_kek_version_2_32_bytes_long");
        properties.setKeks(keks);

        EncryptionService service = new EncryptionService(properties);

        SecretKey fek = service.generateFek();

        // Wrap and unwrap with version 1
        String wrappedV1 = service.wrapFek(fek, 1);
        assertNotNull(wrappedV1);
        SecretKey unwrappedV1 = service.unwrapFek(wrappedV1, 1);
        assertArrayEquals(fek.getEncoded(), unwrappedV1.getEncoded());

        // Wrap and unwrap with version 2
        String wrappedV2 = service.wrapFek(fek, 2);
        assertNotNull(wrappedV2);
        SecretKey unwrappedV2 = service.unwrapFek(wrappedV2, 2);
        assertArrayEquals(fek.getEncoded(), unwrappedV2.getEncoded());

        // Verify version 1 and version 2 produce different ciphertexts
        assertNotEquals(wrappedV1, wrappedV2);
    }

    @Test
    void testFallbackToMasterKekForVersion1() throws Exception {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKek("master_kek_fallback_32_bytes_xx");
        properties.getKek().setAllowRawPassphrase(true);
        // No keks map defined

        EncryptionService service = new EncryptionService(properties);
        SecretKey fek = service.generateFek();

        String wrapped = service.wrapFek(fek, 1);
        SecretKey unwrapped = service.unwrapFek(wrapped, 1);
        assertArrayEquals(fek.getEncoded(), unwrapped.getEncoded());
    }

    @Test
    void testUnconfiguredVersionThrowsException() {
        CryptoProperties properties = new CryptoProperties();
        properties.setMasterKek("master_kek_fallback_32_bytes_xx");
        properties.getKek().setAllowRawPassphrase(true);

        EncryptionService service = new EncryptionService(properties);
        SecretKey fek = service.generateFek();

        assertThrows(IllegalArgumentException.class, () -> service.wrapFek(fek, 2));
    }
}
