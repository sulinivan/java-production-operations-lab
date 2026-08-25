package com.cloudshare.scheduler;

import com.cloudshare.service.StorageService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

@Component
@RequiredArgsConstructor
@Slf4j
@Profile("!rekey-job")
public class AuditPartitionScheduler {

    /**
     * Fixed key for the session-level Postgres advisory lock guarding this job.
     * Arbitrary but stable - must never change once deployed, or a rolling
     * deploy could briefly let two lock keys coexist. Chosen as a readable
     * hash-like constant with no other meaning.
     */
    private static final long ADVISORY_LOCK_KEY = 0x41554450415254L; // "AUDPART" in hex-ish, just a stable constant

    private static final Pattern PARTITION_NAME_PATTERN = Pattern.compile("^audit_logs_y(\\d{4})m(\\d{2})$");

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final StorageService storageService;
    private final DataSource dataSource;

    @Value("${app.scheduler.audit-partition.lookahead-months:3}")
    private int lookaheadMonths = 3;

    @Value("${app.scheduler.audit-partition.retention-months:6}")
    private int retentionMonths = 6;

    @Value("${app.scheduler.audit-partition.archive-enabled:true}")
    private boolean archiveEnabled = true;

    @Value("${app.scheduler.audit-partition.archive-path-prefix:audit-archive}")
    private String archivePathPrefix = "audit-archive";

    /**
     * Entry point. Guarded by a Postgres session-level advisory lock
     * (pg_try_advisory_lock) because this app scales horizontally
     * (docs/system-design/architecture.md) - without the guard, every replica
     * would run this on the same cron and race on partition creation/retirement.
     * Creation was already race-safe on its own (CREATE TABLE IF NOT EXISTS is
     * idempotent), but ALTER TABLE ... DETACH PARTITION is not: a second replica
     * detaching an already-detached partition fails outright. The lock means only
     * one replica performs maintenance per scheduled run; any others log and skip.
     * <p>
     * The lock is taken on its own dedicated connection, held open for the whole
     * job, and explicitly released in a finally block - it does not need to be the
     * same connection {@link #checkAndCreatePartitions} or
     * {@link #retireExpiredPartitions} use internally via {@code jdbcTemplate}
     * (which continue to borrow their own pooled connections per call, exactly as
     * before); a Postgres advisory lock is visible across sessions regardless of
     * which connection later performs the guarded work, so only the lock-holding
     * connection needs to stay open for the duration.
     */
    @Scheduled(cron = "${app.scheduler.audit-partition.cron:0 0 4 * * ?}")
    public void maintainPartitions() {
        log.info("Starting audit log partition maintenance scheduler job...");
        try {
            String dbProduct = jdbcTemplate.execute((Connection conn) -> conn.getMetaData().getDatabaseProductName());
            if (dbProduct == null || !dbProduct.toLowerCase(Locale.ROOT).contains("postgresql")) {
                log.info("Database is not PostgreSQL ({}); skipping native partition maintenance.", dbProduct);
                return;
            }
        } catch (Exception e) {
            log.error("Failed to determine database product name during partition check", e);
            meterRegistry.counter("cloudshare.audit_partition.check_failures").increment();
            return;
        }

        try (Connection lockConn = dataSource.getConnection()) {
            if (!tryAcquireLock(lockConn)) {
                log.info("Another instance already holds the audit-partition advisory lock; skipping this run.");
                return;
            }
            try {
                checkAndCreatePartitions(YearMonth.now());
                retireExpiredPartitions(YearMonth.now());
            } finally {
                releaseLock(lockConn);
            }
        } catch (SQLException e) {
            log.error("Failed to acquire advisory lock for audit partition maintenance", e);
            meterRegistry.counter("cloudshare.audit_partition.check_failures").increment();
        }
        log.info("Finished audit log partition maintenance scheduler job.");
    }

    private boolean tryAcquireLock(Connection lockConn) throws SQLException {
        try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void releaseLock(Connection lockConn) {
        try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            ps.execute();
        } catch (SQLException e) {
            // Non-fatal: session-level advisory locks are also released when the
            // holding connection closes/returns to the pool in a way Postgres treats
            // as session end. Logged so a persistent failure here is still visible.
            log.warn("Failed to explicitly release audit-partition advisory lock", e);
        }
    }

    public void checkAndCreatePartitions(YearMonth startMonth) {
        List<String> existingPartitions;
        try {
            existingPartitions = jdbcTemplate.query(
                "SELECT child.relname AS partition_name " +
                "FROM pg_inherits " +
                "JOIN pg_class parent ON pg_inherits.inhparent = parent.oid " +
                "JOIN pg_class child ON pg_inherits.inhrelid = child.oid " +
                "WHERE parent.relname = 'audit_logs'",
                (rs, rowNum) -> rs.getString("partition_name").toLowerCase(Locale.ROOT)
            );
        } catch (Exception e) {
            log.error("Failed to query existing partitions for audit_logs table", e);
            meterRegistry.counter("cloudshare.audit_partition.check_failures").increment();
            return;
        }

        for (int i = 0; i <= lookaheadMonths; i++) {
            YearMonth targetMonth = startMonth.plusMonths(i);
            String partitionName = String.format("audit_logs_y%04dm%02d", targetMonth.getYear(), targetMonth.getMonthValue());

            if (!existingPartitions.contains(partitionName)) {
                log.info("Partition {} is missing; executing creation DDL.", partitionName);
                String fromDate = String.format("%04d-%02d-01 00:00:00+00", targetMonth.getYear(), targetMonth.getMonthValue());

                YearMonth nextMonth = targetMonth.plusMonths(1);
                String toDate = String.format("%04d-%02d-01 00:00:00+00", nextMonth.getYear(), nextMonth.getMonthValue());

                String ddl = String.format(
                    "CREATE TABLE IF NOT EXISTS %s PARTITION OF audit_logs " +
                    "FOR VALUES FROM ('%s') TO ('%s')",
                    partitionName, fromDate, toDate
                );

                try {
                    jdbcTemplate.execute(ddl);
                    log.info("Successfully created partition: {}", partitionName);
                    meterRegistry.counter("cloudshare.audit_partition.created").increment();
                } catch (Exception e) {
                    log.error("Failed to create audit log partition: " + partitionName, e);
                    meterRegistry.counter("cloudshare.audit_partition.check_failures").increment();
                }
            } else {
                log.debug("Partition {} already exists.", partitionName);
            }
        }
    }

    /**
     * Retires audit_logs partitions whose entire covered month is older than the
     * configured retention window (retention-months). If archiving is enabled
     * (default true), each partition is exported to {@link StorageService} as a
     * gzip-compressed CSV BEFORE being detached and dropped - a failed export
     * always blocks the drop for that partition and is retried on the next
     * scheduled run; it never proceeds "best effort" with a possible data loss.
     * audit_logs_default (the catch-all safety partition) never matches the
     * expected naming pattern and is never touched by this method.
     */
    public void retireExpiredPartitions(YearMonth asOfMonth) {
        YearMonth cutoff = asOfMonth.minusMonths(retentionMonths);
        List<String> existingPartitions;
        try {
            existingPartitions = jdbcTemplate.query(
                "SELECT child.relname AS partition_name " +
                "FROM pg_inherits " +
                "JOIN pg_class parent ON pg_inherits.inhparent = parent.oid " +
                "JOIN pg_class child ON pg_inherits.inhrelid = child.oid " +
                "WHERE parent.relname = 'audit_logs'",
                (rs, rowNum) -> rs.getString("partition_name").toLowerCase(Locale.ROOT)
            );
        } catch (Exception e) {
            log.error("Failed to query existing partitions for audit_logs table during retirement check", e);
            meterRegistry.counter("cloudshare.audit_partition.check_failures").increment();
            return;
        }

        for (String partitionName : existingPartitions) {
            Matcher matcher = PARTITION_NAME_PATTERN.matcher(partitionName);
            if (!matcher.matches()) {
                // Includes audit_logs_default and anything not matching the expected
                // naming scheme - never touched by automated retirement.
                continue;
            }

            YearMonth partitionMonth = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            if (!partitionMonth.isBefore(cutoff)) {
                continue;
            }

            retirePartition(partitionName);
        }
    }

    private void retirePartition(String partitionName) {
        if (archiveEnabled && !archivePartition(partitionName)) {
            return; // Archive failed - never drop without a confirmed successful export.
        }

        try {
            jdbcTemplate.execute("ALTER TABLE audit_logs DETACH PARTITION " + partitionName);
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + partitionName);
            log.info("Retired partition: {}", partitionName);
            meterRegistry.counter("cloudshare.audit_partition.retired").increment();
        } catch (Exception e) {
            // Includes the expected race if another replica's advisory-lock window
            // somehow overlapped (e.g. lock held on a connection that then dropped) -
            // logged and counted, self-heals on the next scheduled run.
            log.error("Failed to detach/drop partition: " + partitionName, e);
            meterRegistry.counter("cloudshare.audit_partition.retire_failures").increment();
        }
    }

    /** @return true if the partition was successfully archived (or if archiving is disabled). */
    private boolean archivePartition(String partitionName) {
        Path archiveFile = null;
        try {
            archiveFile = Files.createTempFile("audit-archive-", ".csv.gz");
            exportPartitionToFile(partitionName, archiveFile);

            if (Files.size(archiveFile) == 0) {
                throw new IOException("Archive export produced an empty file for partition: " + partitionName);
            }

            String archivePath = archivePathPrefix + "/" + partitionName + ".csv.gz";
            try (InputStream in = Files.newInputStream(archiveFile)) {
                storageService.store(archivePath, in);
            }
            log.info("Archived partition {} to storage at {}", partitionName, archivePath);
            meterRegistry.counter("cloudshare.audit_partition.archived").increment();
            return true;
        } catch (Exception e) {
            log.error("Failed to archive partition {}; skipping drop for this run (will retry next scheduled run)", partitionName, e);
            meterRegistry.counter("cloudshare.audit_partition.archive_failures").increment();
            return false;
        } finally {
            if (archiveFile != null) {
                try {
                    Files.deleteIfExists(archiveFile);
                } catch (IOException cleanupEx) {
                    log.warn("Failed to delete temporary archive file: {}", archiveFile, cleanupEx);
                }
            }
        }
    }

    private void exportPartitionToFile(String partitionName, Path destination) throws IOException {
        try {
            jdbcTemplate.execute((Connection conn) -> {
                try (OutputStream fileOut = Files.newOutputStream(destination);
                     GZIPOutputStream gzipOut = new GZIPOutputStream(fileOut)) {
                    PGConnection pgConnection = conn.unwrap(PGConnection.class);
                    CopyManager copyManager = pgConnection.getCopyAPI();
                    copyManager.copyOut(
                        "COPY " + partitionName + " TO STDOUT WITH (FORMAT csv, HEADER true)",
                        gzipOut
                    );
                    return null;
                } catch (IOException | SQLException innerEx) {
                    throw new SQLException("Failed to export partition " + partitionName + " via COPY", innerEx);
                }
            });
        } catch (Exception e) {
            throw new IOException("Failed to export partition to archive file: " + partitionName, e);
        }
    }
}
