package com.cloudshare.scheduler;

import com.cloudshare.repository.ShareLinkRepository;
import com.cloudshare.service.AuditLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LinkCleanupSchedulerTest {

    @Mock
    private ShareLinkRepository shareLinkRepository;

    @Mock
    private AuditLogService auditLogService;

    private SimpleMeterRegistry meterRegistry;
    private LinkCleanupScheduler linkCleanupScheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        linkCleanupScheduler = new LinkCleanupScheduler(shareLinkRepository, auditLogService, meterRegistry);
    }

    @Test
    void cleanupLinks_noExpiredLinks_doesNotLogAudit() {
        when(shareLinkRepository.deleteByExpiresAtBefore(any(Instant.class)))
                .thenReturn(0);

        linkCleanupScheduler.cleanupLinks();

        verify(shareLinkRepository).deleteByExpiresAtBefore(any(Instant.class));
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());

        assertEquals(0.0, meterRegistry.counter("cloudshare.link_cleanup.success").count());
        assertEquals(0.0, meterRegistry.counter("cloudshare.link_cleanup.failures").count());
    }

    @Test
    void cleanupLinks_expiredLinksDeleted_logsAudit() {
        when(shareLinkRepository.deleteByExpiresAtBefore(any(Instant.class)))
                .thenReturn(5);

        linkCleanupScheduler.cleanupLinks();

        verify(shareLinkRepository).deleteByExpiresAtBefore(any(Instant.class));
        verify(auditLogService).log(
                isNull(),
                eq("LINK_CLEANUP"),
                isNull(),
                eq("system"),
                eq("Bulk purged 5 expired share links")
        );

        assertEquals(5.0, meterRegistry.counter("cloudshare.link_cleanup.success").count());
        assertEquals(0.0, meterRegistry.counter("cloudshare.link_cleanup.failures").count());
    }

    @Test
    void cleanupLinks_repositoryThrowsException_incrementsFailuresMetric() {
        when(shareLinkRepository.deleteByExpiresAtBefore(any(Instant.class)))
                .thenThrow(new RuntimeException("Database timeout"));

        linkCleanupScheduler.cleanupLinks();

        verify(shareLinkRepository).deleteByExpiresAtBefore(any(Instant.class));
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());

        assertEquals(0.0, meterRegistry.counter("cloudshare.link_cleanup.success").count());
        assertEquals(1.0, meterRegistry.counter("cloudshare.link_cleanup.failures").count());
    }
}
