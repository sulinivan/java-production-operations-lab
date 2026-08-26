package com.cloudshare.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

@Component
@Slf4j
public class DownloadConcurrencyLimiter {

    private volatile Semaphore permits;

    public DownloadConcurrencyLimiter(
            @Value("${storage.max-concurrent-decrypt-downloads:20}") int maxConcurrent) {
        this.permits = new Semaphore(maxConcurrent, true);
    }

    public Semaphore getSemaphore() {
        return this.permits;
    }

    /** Upper sanity bound for admin-tunable concurrency; prevents resource exhaustion from misconfiguration. */
    private static final int MAX_ALLOWED_CONCURRENCY = 200;

    public synchronized void setMaxConcurrentDownloads(int limit) {
        if (limit < 1 || limit > MAX_ALLOWED_CONCURRENCY) {
            throw new IllegalArgumentException(
                    "Concurrency limit must be between 1 and " + MAX_ALLOWED_CONCURRENCY);
        }
        log.info("Updating download concurrency limit to {}", limit);
        this.permits = new Semaphore(limit, true);
    }
}
