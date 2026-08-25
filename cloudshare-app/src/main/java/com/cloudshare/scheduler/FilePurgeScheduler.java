package com.cloudshare.scheduler;

import com.cloudshare.model.FileMetadata;
import com.cloudshare.repository.FileRepository;
import com.cloudshare.service.FileService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@Profile("!rekey-job")
public class FilePurgeScheduler {

    private final FileRepository fileRepository;
    private final FileService fileService;
    private final MeterRegistry meterRegistry;

    @Scheduled(cron = "${app.scheduler.file-purge.cron:0 0 2 * * ?}")
    public void purgeFiles() {
        log.info("Starting file purge scheduler job...");
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        List<FileMetadata> filesToPurge = fileRepository.findByDeletedTrueAndUpdatedAtBefore(cutoff);
        log.info("Found {} files to permanently purge", filesToPurge.size());

        int successCount = 0;
        int failureCount = 0;
        for (FileMetadata file : filesToPurge) {
            try {
                // Call via proxy to ensure @Transactional(propagation =
                // Propagation.REQUIRES_NEW) works
                fileService.purgeSoftDeletedFile(file);
                successCount++;
            } catch (Exception e) {
                log.error("Failed to purge soft-deleted file: id={}, filename={}", file.getId(),
                        file.getOriginalFilename(), e);
                failureCount++;
            }
        }
        meterRegistry.counter("cloudshare.purge.success").increment(successCount);
        meterRegistry.counter("cloudshare.purge.failures").increment(failureCount);
        log.info("Finished file purge scheduler job. success={} failures={}", successCount, failureCount);
    }
}
