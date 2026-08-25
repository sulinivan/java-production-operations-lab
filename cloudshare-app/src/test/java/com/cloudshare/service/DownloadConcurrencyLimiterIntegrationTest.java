package com.cloudshare.service;

import com.cloudshare.exception.DownloadCapacityExceededException;
import com.cloudshare.model.FileMetadata;
import com.cloudshare.model.ShareLink;
import com.cloudshare.repository.FileRepository;
import com.cloudshare.repository.ShareLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
    "storage.max-concurrent-decrypt-downloads=2",
    "storage.decrypt-acquire-timeout-seconds=1"
})
@ActiveProfiles("test")
public class DownloadConcurrencyLimiterIntegrationTest {

    @Autowired
    private FileService fileService;

    @Autowired
    private ShareService shareService;

    @Autowired
    private DownloadConcurrencyLimiter downloadConcurrencyLimiter;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        downloadConcurrencyLimiter.setMaxConcurrentDownloads(2);
    }

    @MockitoBean
    private FileRepository fileRepository;

    @MockitoBean
    private ShareLinkRepository shareLinkRepository;

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private EncryptionService encryptionService;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean(name = "redisTemplate")
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private com.cloudshare.repository.FileShareRepository fileShareRepository;

    @MockitoBean
    private com.cloudshare.repository.UserRepository userRepository;

    @MockitoBean
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Test
    void testSharedLimiter_boundsConcurrencyAcrossBothServices() throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ipAddress = "127.0.0.1";



        FileMetadata metadata = FileMetadata.builder()
                .id(fileId)
                .ownerId(userId)
                .storagePath("test-path")
                .originalFilename("test.txt")
                .fileSizeBytes(100L)
                .mimeType("text/plain")
                .encryptedFek("wrapped-fek")
                .ivGcm(java.util.Base64.getEncoder().encodeToString(new byte[12]))
                .deleted(false)
                .build();

        // Stubs for FileService
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.HashOperations<String, Object, Object> hashOperations = mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("cache:permissions:" + fileId, userId.toString())).thenReturn("OWNER");
        when(fileRepository.findAccessibleFile(fileId, userId)).thenReturn(Optional.of(metadata));

        // Stubs for ShareService
        ShareLink shareLink = ShareLink.builder()
                .id(UUID.randomUUID())
                .file(metadata)
                .shareCode("CODE123")
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .downloadLimit(10)
                .downloadCount(0)
                .build();
        when(shareLinkRepository.findByShareCode("CODE123")).thenReturn(Optional.of(shareLink));
        when(shareLinkRepository.incrementDownloadCountConditional(any())).thenReturn(1);

        // Stub FEK unwrapping
        SecretKey mockFek = new SecretKeySpec(new byte[32], "AES");
        when(encryptionService.unwrapFek(any(), anyInt())).thenReturn(mockFek);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch decryptEnterLatch = new CountDownLatch(2);
        CountDownLatch decryptReleaseLatch = new CountDownLatch(1);

        // Stub retrieve to simulate slow decryption
        when(storageService.retrieve(any())).thenAnswer((Answer<InputStream>) invocation -> {
            decryptEnterLatch.countDown();
            decryptReleaseLatch.await();
            return new java.io.ByteArrayInputStream("plaintext content".getBytes());
        });

        ExecutorService executor = Executors.newFixedThreadPool(3);

        Future<FileService.DecryptedFileStream> future1 = executor.submit(() -> {
            startLatch.await();
            return fileService.downloadFile(fileId, userId, ipAddress);
        });

        Future<FileService.DecryptedFileStream> future2 = executor.submit(() -> {
            startLatch.await();
            return shareService.downloadPublicLink("CODE123", null, ipAddress);
        });

        Future<FileService.DecryptedFileStream> future3 = executor.submit(() -> {
            startLatch.await();
            return fileService.downloadFile(fileId, userId, ipAddress);
        });

        startLatch.countDown();

        boolean entered = decryptEnterLatch.await(5, TimeUnit.SECONDS);
        assertTrue(entered, "First two concurrent requests must successfully enter decryption.");

        // Wait until the 3rd thread's 1-second timeout is guaranteed to have expired
        Thread.sleep(2000);

        // Release the blocking decryption step for the two successful threads
        decryptReleaseLatch.countDown();

        int successCount = 0;
        int failureCount = 0;
        Throwable failureCause = null;

        for (Future<FileService.DecryptedFileStream> future : java.util.List.of(future1, future2, future3)) {
            try {
                FileService.DecryptedFileStream stream = future.get(5, TimeUnit.SECONDS);
                assertNotNull(stream);
                successCount++;
            } catch (ExecutionException e) {
                failureCount++;
                failureCause = e.getCause();
            }
        }

        assertEquals(2, successCount, "Exactly 2 downloads must succeed");
        assertEquals(1, failureCount, "Exactly 1 download must fail due to concurrency limit");
        assertTrue(failureCause instanceof DownloadCapacityExceededException, 
                "Failure cause must be DownloadCapacityExceededException, but was " + failureCause);

        executor.shutdown();
    }
}
