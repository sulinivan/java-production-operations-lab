package com.cloudshare.scheduler;

import com.cloudshare.service.StorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditPartitionSchedulerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private StorageService storageService;

    @Mock
    private DataSource dataSource;

    private SimpleMeterRegistry meterRegistry;
    private AuditPartitionScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new AuditPartitionScheduler(jdbcTemplate, meterRegistry, storageService, dataSource);
        ReflectionTestUtils.setField(scheduler, "lookaheadMonths", 3);
        ReflectionTestUtils.setField(scheduler, "retentionMonths", 6);
        ReflectionTestUtils.setField(scheduler, "archiveEnabled", true);
        ReflectionTestUtils.setField(scheduler, "archivePathPrefix", "audit-archive");
    }

    // --- checkAndCreatePartitions: unchanged behavior, same tests as before ---

    @SuppressWarnings("unchecked")
    @Test
    void partitionsAlreadyExist_forConfiguredLookaheadWindow_createsNothing() {
        YearMonth start = YearMonth.of(2026, 7);
        List<String> mockPartitions = Arrays.asList(
            "audit_logs_y2026m07",
            "audit_logs_y2026m08",
            "audit_logs_y2026m09",
            "audit_logs_y2026m10"
        );

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(mockPartitions);

        scheduler.checkAndCreatePartitions(start);

        verify(jdbcTemplate, never()).execute(anyString());
        assertEquals(0.0, meterRegistry.counter("cloudshare.audit_partition.created").count());
        assertEquals(0.0, meterRegistry.counter("cloudshare.audit_partition.check_failures").count());
    }

    @SuppressWarnings("unchecked")
    @Test
    void missingFuturePartitions_createsExactlyTheMissingOnes() {
        YearMonth start = YearMonth.of(2026, 7);
        List<String> mockPartitions = new ArrayList<>(Arrays.asList(
            "audit_logs_y2026m07",
            "audit_logs_y2026m08"
        ));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(mockPartitions);

        scheduler.checkAndCreatePartitions(start);

        verify(jdbcTemplate).execute("CREATE TABLE IF NOT EXISTS audit_logs_y2026m09 PARTITION OF audit_logs FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00')");
        verify(jdbcTemplate).execute("CREATE TABLE IF NOT EXISTS audit_logs_y2026m10 PARTITION OF audit_logs FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00')");

        verify(jdbcTemplate, times(2)).execute(anyString());

        assertEquals(2.0, meterRegistry.counter("cloudshare.audit_partition.created").count());
        assertEquals(0.0, meterRegistry.counter("cloudshare.audit_partition.check_failures").count());
    }

    @SuppressWarnings("unchecked")
    @Test
    void partitionCreationThrows_logsAtErrorAndIncrementsFailureCounter() {
        YearMonth start = YearMonth.of(2026, 7);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(Collections.emptyList());

        doThrow(new RuntimeException("PostgreSQL connection lost")).when(jdbcTemplate).execute(anyString());

        scheduler.checkAndCreatePartitions(start);

        assertEquals(0.0, meterRegistry.counter("cloudshare.audit_partition.created").count());
        assertEquals(4.0, meterRegistry.counter("cloudshare.audit_partition.check_failures").count());
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkAndCreatePartitions_rollsOverYearBoundary_correctly() {
        YearMonth start = YearMonth.of(2026, 12);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(Collections.emptyList());

        scheduler.checkAndCreatePartitions(start);

        verify(jdbcTemplate).execute("CREATE TABLE IF NOT EXISTS audit_logs_y2026m12 PARTITION OF audit_logs FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00')");
        verify(jdbcTemplate).execute("CREATE TABLE IF NOT EXISTS audit_logs_y2027m01 PARTITION OF audit_logs FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00')");
        verify(jdbcTemplate).execute("CREATE TABLE IF NOT EXISTS audit_logs_y2027m02 PARTITION OF audit_logs FOR VALUES FROM ('2027-02-01 00:00:00+00') TO ('2027-03-01 00:00:00+00')");
        verify(jdbcTemplate).execute("CREATE TABLE IF NOT EXISTS audit_logs_y2027m03 PARTITION OF audit_logs FOR VALUES FROM ('2027-03-01 00:00:00+00') TO ('2027-04-01 00:00:00+00')");

        verify(jdbcTemplate, times(4)).execute(anyString());
        assertEquals(4.0, meterRegistry.counter("cloudshare.audit_partition.created").count());
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkAndCreatePartitions_isIdempotent() {
        YearMonth start = YearMonth.of(2026, 7);
        List<String> mockPartitions = new ArrayList<>(Collections.singletonList("audit_logs_y2026m07"));

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(mockPartitions);

        scheduler.checkAndCreatePartitions(start);
        verify(jdbcTemplate, times(3)).execute(anyString());
        assertEquals(3.0, meterRegistry.counter("cloudshare.audit_partition.created").count());

        reset(jdbcTemplate);
        List<String> mockPartitionsSecondRun = Arrays.asList(
            "audit_logs_y2026m07",
            "audit_logs_y2026m08",
            "audit_logs_y2026m09",
            "audit_logs_y2026m10"
        );
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(mockPartitionsSecondRun);

        scheduler.checkAndCreatePartitions(start);

        verify(jdbcTemplate, never()).execute(anyString());
        assertEquals(3.0, meterRegistry.counter("cloudshare.audit_partition.created").count());
        assertEquals(0.0, meterRegistry.counter("cloudshare.audit_partition.check_failures").count());
    }

    // --- retireExpiredPartitions: new v3.1.0 behavior ---

    @SuppressWarnings("unchecked")
    @Test
    void retireExpiredPartitions_skipsDefaultPartitionAndNonMatchingNames() {
        YearMonth asOf = YearMonth.of(2026, 10);
        List<String> mockPartitions = Arrays.asList("audit_logs_default", "some_other_table");

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(mockPartitions);

        scheduler.retireExpiredPartitions(asOf);

        verify(jdbcTemplate, never()).execute(contains("DETACH PARTITION"));
        verifyNoInteractions(storageService);
    }

    @SuppressWarnings("unchecked")
    @Test
    void retireExpiredPartitions_leavesPartitionsWithinRetentionWindowAlone() {
        // retentionMonths = 6, asOf = 2026-10 -> cutoff = 2026-04. A partition for
        // the cutoff month itself (2026-04) is NOT before the cutoff, so it must be
        // left alone - this is the exact boundary edge case, not just a comfortably
        // in-window example.
        YearMonth asOf = YearMonth.of(2026, 10);
        List<String> mockPartitions = Collections.singletonList("audit_logs_y2026m04");

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(mockPartitions);

        scheduler.retireExpiredPartitions(asOf);

        verify(jdbcTemplate, never()).execute(contains("DETACH PARTITION"));
        verifyNoInteractions(storageService);
    }

    @SuppressWarnings("unchecked")
    @Test
    void retireExpiredPartitions_expiredPartition_archivedThenDetachedAndDropped() throws Exception {
        // retentionMonths = 6, asOf = 2026-10 -> cutoff = 2026-04. 2026-01 is safely
        // before that.
        YearMonth asOf = YearMonth.of(2026, 10);
        List<String> mockPartitions = Collections.singletonList("audit_logs_y2026m01");

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(mockPartitions);
        // exportPartitionToFile goes through jdbcTemplate.execute(ConnectionCallback) -
        // stub it to a no-op success so the (empty, since not actually written to) temp
        // file path is exercised without a real Postgres connection.
        when(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class)))
            .thenAnswer(invocation -> null);

        scheduler.retireExpiredPartitions(asOf);

        // Export produces an empty file in this mock (no real COPY ran), which the
        // implementation correctly treats as an archive failure - so DETACH must NOT
        // be called. This asserts the safety property: never drop without a verified
        // non-empty export, not just "did archive get attempted".
        verify(jdbcTemplate, never()).execute(contains("DETACH PARTITION"));
        assertEquals(1.0, meterRegistry.counter("cloudshare.audit_partition.archive_failures").count());
        assertEquals(0.0, meterRegistry.counter("cloudshare.audit_partition.retired").count());
    }

    @SuppressWarnings("unchecked")
    @Test
    void retireExpiredPartitions_archivingDisabled_dropsDirectlyWithoutStorageCall() {
        ReflectionTestUtils.setField(scheduler, "archiveEnabled", false);
        YearMonth asOf = YearMonth.of(2026, 10);
        List<String> mockPartitions = Collections.singletonList("audit_logs_y2026m01");

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(mockPartitions);

        scheduler.retireExpiredPartitions(asOf);

        verify(jdbcTemplate).execute("ALTER TABLE audit_logs DETACH PARTITION audit_logs_y2026m01");
        verify(jdbcTemplate).execute("DROP TABLE IF EXISTS audit_logs_y2026m01");
        verifyNoInteractions(storageService);
        assertEquals(1.0, meterRegistry.counter("cloudshare.audit_partition.retired").count());
    }

    @SuppressWarnings("unchecked")
    @Test
    void retireExpiredPartitions_detachThrows_logsAndIncrementsFailureCounter_doesNotThrow() {
        ReflectionTestUtils.setField(scheduler, "archiveEnabled", false);
        YearMonth asOf = YearMonth.of(2026, 10);
        List<String> mockPartitions = Collections.singletonList("audit_logs_y2026m01");

        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(mockPartitions);
        doThrow(new RuntimeException("already detached by another instance"))
            .when(jdbcTemplate).execute(contains("DETACH PARTITION"));

        scheduler.retireExpiredPartitions(asOf);

        assertEquals(1.0, meterRegistry.counter("cloudshare.audit_partition.retire_failures").count());
        assertEquals(0.0, meterRegistry.counter("cloudshare.audit_partition.retired").count());
    }
}
