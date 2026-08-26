package com.cloudshare.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

@Service
@Slf4j
public class BreachedPasswordService {

    @Value("${security.password.breach-check.enabled:true}")
    private boolean enabled;

    @Value("${security.password.breach-check.timeout-ms:3000}")
    private int timeoutMs;

    private HttpClient httpClient;

    // Package-private setter for testing mocks
    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @PostConstruct
    public void init() {
        if (this.httpClient == null) {
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
        }
    }

    /**
     * Checks if the given password has been exposed in a data breach.
     * Uses HaveIBeenPwned range API (k-anonymity protocol).
     *
     * @param password the password to check
     * @return true if the password is breached, false otherwise (fails open on
     *         error)
     */
    public boolean isBreached(String password) {
        if (!enabled) {
            log.debug("Breached password check is disabled.");
            return false;
        }

        if (password == null || password.isBlank()) {
            return false;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashBytes = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02X", b));
            }
            String sha1 = sb.toString();

            if (sha1.length() < 5) {
                return false;
            }

            String prefix = sha1.substring(0, 5);
            String suffix = sha1.substring(5);

            String url = "https://api.pwnedpasswords.com/range/" + prefix;
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "CloudShare-Breached-Password-Check")
                    .GET()
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() != 200) {
                log.warn("HIBP range API returned non-200 status code: {}. Failing open.", httpResponse.statusCode());
                return false;
            }

            String body = httpResponse.body();
            if (body == null) {
                return false;
            }

            return body.lines()
                    .map(line -> line.split(":"))
                    .filter(parts -> parts.length > 0)
                    .anyMatch(parts -> parts[0].equalsIgnoreCase(suffix));

        } catch (Exception e) {
            log.error("Error occurred during breached password check. Failing open.", e);
            return false;
        }
    }
}
