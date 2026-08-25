package com.cloudshare.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DownloadConcurrencyLimiterTest {

    @Test
    void testInitializationAndGetter() {
        DownloadConcurrencyLimiter limiter = new DownloadConcurrencyLimiter(5);
        assertNotNull(limiter.getSemaphore());
        assertEquals(5, limiter.getSemaphore().availablePermits());
        assertTrue(limiter.getSemaphore().isFair());
    }

    @Test
    void testSetMaxConcurrentDownloads_resizesCorrectly() {
        DownloadConcurrencyLimiter limiter = new DownloadConcurrencyLimiter(5);
        limiter.setMaxConcurrentDownloads(10);
        assertEquals(10, limiter.getSemaphore().availablePermits());
        assertTrue(limiter.getSemaphore().isFair());
    }

    @Test
    void testSetMaxConcurrentDownloads_invalidLimit_throwsException() {
        DownloadConcurrencyLimiter limiter = new DownloadConcurrencyLimiter(5);
        assertThrows(IllegalArgumentException.class, () -> limiter.setMaxConcurrentDownloads(0));
        assertThrows(IllegalArgumentException.class, () -> limiter.setMaxConcurrentDownloads(-1));
    }
}
