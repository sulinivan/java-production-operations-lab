package com.cloudshare.scheduler;

import com.cloudshare.repository.ShareLinkRepository;
import com.cloudshare.service.AuditLogService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
@Profile("!rekey-job")
public class LinkCleanupScheduler {

    private final ShareLinkRepository shareLinkRepository;
    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;

    @Scheduled(cron = "${app.scheduler.link-cleanup.cron:0 0 3 * * ?}")
    public void cleanupLinks() {
        log.info("Starting expired link cleanup scheduler job...");
        Instant now = Instant.now();
        int deletedCount = 0;
        try {
            // This execution runs in its own transaction & commits first.
            deletedCount = shareLinkRepository.deleteByExpiresAtBefore(now);
            meterRegistry.counter("cloudshare.link_cleanup.success").increment(deletedCount);

            // Audit logging runs in its own transaction afterward if delete was successful.
            if (deletedCount > 0) {
                auditLogService.log(
                        null,
                        "LINK_CLEANUP",
                        null,
                        "system",
                        "Bulk purged " + deletedCount + " expired share links");
                log.info("Successfully deleted {} expired public share links", deletedCount);
            }
        } catch (Exception e) {
            log.error("Failed to delete expired public share links", e);
            meterRegistry.counter("cloudshare.link_cleanup.failures").increment();
        }
        log.info("Finished expired link cleanup scheduler job. success={}", deletedCount);
    }
}
