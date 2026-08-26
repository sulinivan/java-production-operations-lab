# Runbook: Audit Log Partition Maintenance

## Overview
The `audit_logs` table in CloudShare is range-partitioned monthly (e.g. `audit_logs_y2026m10`, `audit_logs_y2026m11`) to prevent database index bloat and tablespace performance degradation. 

Because CloudShare's security posture is **fail-secure** (audit log write failures abort the parent operation), any query that fails to write an audit log entry will cause the corresponding upload, download, share, or delete operation to roll back and throw an error. Consequently, if the database partitions are exhausted and a new month begins without a matching partition table, **all application write paths will go down**.

To automate this upkeep, the system includes `AuditPartitionScheduler`, which dynamically pre-creates future partition tables.

---

## Observability & Alerting
The scheduler registers the following Micrometer counters exposed via `/actuator/prometheus`:
- `cloudshare.audit_partition.created`: Incremented on successful creation of a new partition.
- `cloudshare.audit_partition.check_failures`: Incremented on check or creation failures (e.g., query database error, connection pool exhaustion, SQL syntax error).
- `cloudshare.audit_partition.retired`: Incremented when an old partition is successfully retired (archived and dropped, or just dropped if archiving is disabled).
- `cloudshare.audit_partition.retire_failures`: Incremented on failures during the partition retirement process.
- `cloudshare.audit_partition.archive_failures`: Incremented specifically if export/archiving to storage fails.

> [!WARNING]
> If a partition check, creation, or retirement fails, the scheduler logs high-severity **`ERROR`** or **`WARN`** messages in log files:
> - `"Failed to create audit log partition: audit_logs_y[...]"`
> - `"Failed to export partition to archive file: [...]"`
> - `"Failed to retire partition: [...]"`
> Set up alerts on your log aggregator (ELK/Datadog) to trigger on these patterns or when the `check_failures`, `retire_failures`, or `archive_failures` metrics increment.

---

## Concurrency Control
The maintenance job is protected by a session-level Postgres advisory lock (`pg_try_advisory_lock` using a dedicated key). This prevents race conditions in multi-replica deployments where multiple instances might concurrently attempt the non-idempotent `ALTER TABLE ... DETACH PARTITION` retirement step. Only one replica performs partition maintenance per cron execution; others log and exit cleanly.

---

## Configuration Properties
The scheduler behavior is defined by the following variables in `application.yml` or via container environment properties:
- `app.scheduler.audit-partition.cron`: Cron expression controlling how frequently the scheduler runs. Default is `0 0 4 * * ?` (daily at 4:00 AM UTC).
- `app.scheduler.audit-partition.lookahead-months`: Number of months in the future to keep pre-created. Default is `3`.
- `app.scheduler.audit-partition.retention-months`: Number of months of hot partitions to keep before retirement. Default is `6`.
- `app.scheduler.audit-partition.archive-enabled`: Boolean controlling whether retired partitions are archived before drop. Default is `true`.
- `app.scheduler.audit-partition.archive-path-prefix`: Path prefix in object storage for retired partition archives. Default is `"audit-archive"`.

---

## Recovery Steps

If you receive an alert indicating that a partition failed to create or is missing, execute the following steps in order:

### Step 1: Trigger Automated Maintenance via Admin REST API (First Resort)
CloudShare exposes a secure endpoint specifically for triggering partition maintenance on-demand. This endpoint executes the same Java-based logic as the scheduler, dynamically pre-creating missing partitions and retiring/archiving partitions that have fallen outside the retention window.

1. Authenticate to the application as a user with `ROLE_ADMIN`.
2. Generate an MFA step-up token by calling:
   ```http
   POST /api/v1/auth/mfa/step-up
   Content-Type: application/json
   
   {
     "code": "<6-digit-TOTP-code>"
   }
   ```
3. Copy the returned step-up token from the `stepUpToken` field or the response headers.
4. Execute a `POST` request to the partition endpoint with the step-up token attached:
   ```http
   POST /api/v1/admin/audit-logs/partitions
   Authorization: Bearer <your-jwt-access-token>
   X-StepUp-Token: <your-step-up-token>
   ```
5. Confirm that the API returns `200 OK` with a success response. Check application logs to verify that the missing partitions have been created successfully.

---

### Step 2: Execute Manual DDL Fallback (Emergency Fallback)
If the application server is completely unresponsive, or the REST API is inaccessible, you must connect directly to the PostgreSQL database cluster and execute the declarative partition DDL manually.

1. Connect to the Postgres database using a client like `psql` or `pgAdmin` using `cloudshare_user`.
2. Identify the missing partition month. For example, if the current or upcoming month is October 2026, the partition name will be `audit_logs_y2026m10`.
3. Execute the SQL command to create the partition. 
   - **Start date**: The first day of the partition month (`YYYY-MM-01 00:00:00+00`).
   - **End date**: The first day of the *next* month (`YYYY-NextMonth-01 00:00:00+00`).

#### SQL Template:
```sql
-- Replace the table suffix and dates with your target values
CREATE TABLE IF NOT EXISTS audit_logs_y2026m10 PARTITION OF audit_logs
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');
```
4. Verify that the table is registered as a partition:
```sql
SELECT child.relname AS partition_name
FROM pg_inherits
JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
JOIN pg_class child ON pg_inherits.inhrelid = child.oid
WHERE parent.relname = 'audit_logs';
```
5. Confirm that write operations (like uploading or deleting a small test file) are restored.
