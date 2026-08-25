package com.cloudshare.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "crypto")
@Getter
@Setter
public class CryptoProperties {
    private String masterKek;
    private int currentKekVersion = 1;
    private Map<Integer, String> keks = new HashMap<>();
    private KekProperties kek = new KekProperties();

    @Getter
    @Setter
    public static class KekProperties {
        private boolean allowRawPassphrase = false;
    }
}

