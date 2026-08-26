package com.cloudshare.repository;

import com.cloudshare.model.FileMetadata;
import com.cloudshare.model.ShareLink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShareLinkRepositoryTest {

    @Autowired
    private ShareLinkRepository shareLinkRepository;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void testIncrementDownloadCountConditional_concurrencyLimitEnforced() throws Exception {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // 1. Create and persist FileMetadata & ShareLink in a committed setup transaction
        final UUID ownerId = UUID.randomUUID();
        final UUID[] ids = new UUID[1];

        transactionTemplate.executeWithoutResult(status -> {
            FileMetadata file = FileMetadata.builder()
                    .ownerId(ownerId)
                    .originalFilename("concurrency-test.txt")
                    .mimeType("text/plain")
                    .fileSizeBytes(10L)
                    .checksumSha256("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                    .storagePath("store/test")
                    .encryptedFek("fek")
                    .ivGcm("iv")
                    .kekVersion(1)
                    .deleted(false)
                    .build();
            file = fileRepository.save(file);

            ShareLink link = ShareLink.builder()
                    .file(file)
                    .shareCode("RACE1234")
                    .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                    .downloadLimit(1)
                    .downloadCount(0)
                    .build();
            link = shareLinkRepository.save(link);
            ids[0] = link.getId();
        });

        final UUID shareLinkId = ids[0];

        // 2. Fire 10 parallel threads executing incrementDownloadCountConditional concurrently
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Integer updatedRows = transactionTemplate.execute(status ->
                            shareLinkRepository.incrementDownloadCountConditional(shareLinkId)
                    );
                    if (updatedRows != null && updatedRows == 1) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        // 3. Assert exactly 1 thread succeeded and 9 threads failed
        assertEquals(1, successCount.get(), "Exactly 1 concurrent update must succeed when downloadLimit = 1");
        assertEquals(9, failureCount.get(), "Remaining 9 concurrent updates must return 0 updated rows");

        // 4. Verify DB state
        transactionTemplate.executeWithoutResult(status -> {
            ShareLink updatedLink = shareLinkRepository.findById(shareLinkId).orElseThrow();
            assertEquals(1, updatedLink.getDownloadCount(), "Final download count in DB must equal limit of 1");
            shareLinkRepository.delete(updatedLink);
            fileRepository.deleteById(updatedLink.getFile().getId());
        });
    }
}
