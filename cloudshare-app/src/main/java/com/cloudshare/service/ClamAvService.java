package com.cloudshare.service;

import com.cloudshare.exception.ScanCapacityExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ClamAvService {

    private final String host;
    private final int port;
    private final int timeout;
    private final int concurrencyTimeoutSeconds;
    private volatile Semaphore scanConcurrencyLimiter;

    public ClamAvService(
            @Value("${clamav.host:localhost}") String host,
            @Value("${clamav.port:3310}") int port,
            @Value("${clamav.timeout-ms:10000}") int timeout,
            @Value("${clamav.max-concurrent-scans:8}") int maxConcurrentScans,
            @Value("${clamav.concurrency-timeout-seconds:30}") int concurrencyTimeoutSeconds) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
        this.concurrencyTimeoutSeconds = concurrencyTimeoutSeconds;
        this.scanConcurrencyLimiter = new Semaphore(maxConcurrentScans, true);
    }

    /** Upper sanity bound for admin-tunable concurrency; prevents resource exhaustion from misconfiguration. */
    private static final int MAX_ALLOWED_CONCURRENCY = 200;

    public synchronized void setMaxConcurrentScans(int limit) {
        if (limit < 1 || limit > MAX_ALLOWED_CONCURRENCY) {
            throw new IllegalArgumentException(
                    "Concurrency limit must be between 1 and " + MAX_ALLOWED_CONCURRENCY);
        }
        log.info("Updating ClamAV scan concurrency limit to {}", limit);
        this.scanConcurrencyLimiter = new Semaphore(limit, true);
    }

    public boolean scan(InputStream inputStream) throws IOException {
        Semaphore limiter = this.scanConcurrencyLimiter;
        boolean acquired = false;
        try {
            acquired = limiter.tryAcquire(concurrencyTimeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("ClamAV scan concurrency limit reached; rejecting upload to protect scan throughput");
                throw new ScanCapacityExceededException("Virus scanning is temporarily at capacity. Please try again shortly.");
            }
            return performSocketScan(inputStream);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread was interrupted while waiting for ClamAV scan permit", e);
            throw new IOException("Virus scanning wait was interrupted", e);
        } finally {
            if (acquired) {
                limiter.release();
            }
        }
    }

    boolean performSocketScan(InputStream inputStream) throws IOException {
        log.debug("Connecting to ClamAV daemon at {}:{}", host, port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);

            try (OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                 InputStream in = new BufferedInputStream(socket.getInputStream())) {

                // Send INSTREAM command (zINSTREAM\0 or nINSTREAM\n)
                // Using zINSTREAM\0 allows us to use null-byte delimiters
                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    if (read > 0) {
                        // Header: 4-byte chunk size (big-endian)
                        byte[] lengthHeader = ByteBuffer.allocate(4).putInt(read).array();
                        out.write(lengthHeader);
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }

                // Terminate stream with a zero-length chunk
                out.write(new byte[]{0, 0, 0, 0});
                out.flush();
                socket.shutdownOutput(); // Signal end of data, prompts ClamAV to respond and close

                // Read ClamAV response
                ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
                byte[] respReadBuffer = new byte[1024];
                int respRead;
                while ((respRead = in.read(respReadBuffer)) != -1) {
                    responseBuffer.write(respReadBuffer, 0, respRead);
                }

                String response = responseBuffer.toString(StandardCharsets.US_ASCII).trim();
                log.info("ClamAV response: {}", response);

                if (response.contains("FOUND")) {
                    log.warn("ClamAV detected malware: {}", response);
                    return false;
                }
                return true;
            }
        } catch (IOException e) {
            log.error("ClamAV scanning failed due to connection/IO error", e);
            throw new IOException("Failed to communicate with ClamAV service", e);
        }
    }
}
