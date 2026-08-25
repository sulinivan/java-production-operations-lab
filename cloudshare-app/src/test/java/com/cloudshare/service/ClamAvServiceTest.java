package com.cloudshare.service;

import com.cloudshare.exception.ScanCapacityExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

public class ClamAvServiceTest {

    private ClamAvService clamAvService;
    private ExecutorService executorService;

    @BeforeEach
    public void setUp() {
        // Create service with a concurrency limit of 2, and timeout of 1 second for faster testing
        clamAvService = Mockito.spy(new ClamAvService("localhost", 3310, 10000, 2, 1));
        executorService = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    public void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    public void scan_withinConcurrencyLimit_succeedsNormally() throws Exception {
        doAnswer(invocation -> true).when(clamAvService).performSocketScan(any(InputStream.class));

        assertTrue(clamAvService.scan(new ByteArrayInputStream("test".getBytes())));
        assertTrue(clamAvService.scan(new ByteArrayInputStream("test".getBytes())));
    }

    @Test
    public void scan_concurrentCallsExceedingLimit_excessCallsRejectedOrQueued() throws Exception {
        AtomicInteger activeScans = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrent = new AtomicInteger(0);

        doAnswer(invocation -> {
            int current = activeScans.incrementAndGet();
            maxObservedConcurrent.accumulateAndGet(current, Math::max);
            Thread.sleep(500); // Simulate slow scan
            activeScans.decrementAndGet();
            return true;
        }).when(clamAvService).performSocketScan(any(InputStream.class));

        // Submit 3 parallel uploads.
        // Since limit is 2, the third request will queue/wait. 
        // With a timeout of 1 second, it should wait until one of the first two finishes (500ms) and then succeed.
        Future<Boolean> f1 = executorService.submit(() -> clamAvService.scan(new ByteArrayInputStream("f1".getBytes())));
        Future<Boolean> f2 = executorService.submit(() -> clamAvService.scan(new ByteArrayInputStream("f2".getBytes())));
        Thread.sleep(50); // Ensure first two are running before submitting the third
        Future<Boolean> f3 = executorService.submit(() -> clamAvService.scan(new ByteArrayInputStream("f3".getBytes())));

        assertTrue(f1.get(2, TimeUnit.SECONDS));
        assertTrue(f2.get(2, TimeUnit.SECONDS));
        assertTrue(f3.get(2, TimeUnit.SECONDS));

        // Verify we never exceeded the concurrency limit of 2 in performSocketScan
        assertTrue(maxObservedConcurrent.get() <= 2, "Observed concurrency was " + maxObservedConcurrent.get());
    }

    @Test
    public void scan_timeoutWaitingForCapacity_throwsScanCapacityExceededException() throws Exception {
        doAnswer(invocation -> {
            Thread.sleep(2000);
            return true;
        }).when(clamAvService).performSocketScan(any(InputStream.class));

        // Start 2 concurrent scans to exhaust all 2 permits
        executorService.submit(() -> clamAvService.scan(new ByteArrayInputStream("b1".getBytes())));
        executorService.submit(() -> clamAvService.scan(new ByteArrayInputStream("b2".getBytes())));
        Thread.sleep(100); // Ensure they have fully acquired the permits

        // Try to scan a third file, it should block and then timeout throwing ScanCapacityExceededException
        InputStream testInput = new ByteArrayInputStream("b3".getBytes());
        ExecutionException exception = assertThrows(ExecutionException.class, () -> {
            Future<Boolean> f = executorService.submit(() -> clamAvService.scan(testInput));
            f.get();
        });

        assertInstanceOf(ScanCapacityExceededException.class, exception.getCause());
        assertEquals("Virus scanning is temporarily at capacity. Please try again shortly.", exception.getCause().getMessage());
    }
}
