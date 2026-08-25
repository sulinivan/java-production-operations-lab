# Disaster Recovery & Backup Blueprint

This document specifies the strategies, configurations, and recovery workflows to handle database corruption, physical server destruction, cloud region failures, or cyberattacks.

---

## 1. Backup Execution Strategy

To meet enterprise compliance, backups are split into metadata (PostgreSQL) and objects (files).

### 1.1 Metadata Backups (PostgreSQL)
1.  **Daily Full Backups:** Completed via standard SQL dump utility or physical backup manager (e.g., WAL-G/pgBackRest). Scheduled at 1:00 AM UTC daily.
2.  **Point-in-Time Recovery (PITR):** PostgreSQL Write-Ahead Logs (WAL) are shipped automatically to a secure, write-once secondary storage vault (S3 bucket with Object Lock or a separate file server) every 15 minutes.
3.  **Retention:** Full backups are retained for 30 days; monthly snapshots are retained for 1 year.

### 1.2 Object Storage Backups (Files)
*   **When using S3 / MinIO:**
    *   **Bucket Versioning:** Enabled to protect against accidental file deletion or ransomware encryption. Deleting a file simply inserts a delete marker; historical versions can be restored immediately.
    *   **Cross-Region Replication (CRR):** S3 asynchronously replicates uploads to a secondary geographic region.
*   **When using Local Filesystem Storage (Free/Single Server):**
    *   **Disk Redundancy:** Host servers must run **RAID-1** (Mirroring) or **RAID-10** arrays.
    *   **Offsite Sync:** A nightly cron job runs `rsync` over SSH to copy the sandbox upload directory to a separate physical backup server.

---

## 2. High Availability (HA) Cluster Topology

For production stability, all single-point-of-failures (SPOFs) are bypassed using replication:

```
                  [ Nginx Ingress / Load Balancer ]
                                 |
           +---------------------+---------------------+
           |                                           |
    [ Spring App 1 ]                            [ Spring App 2 ]
           |                                           |
  +--------+--------+                         +--------+--------+
  |                 |                         |                 |
  v                 v                         v                 v
[Redis Primary]  [Pg Primary]              [Redis Replica]  [Pg Replica]
  |                 |                         |                 |
  +-- (Replaces) -->[Redis Replica]           +-- (Replaces) -->[Pg Replica]
```

### HA Mechanics:
1.  **App Layer:** Multiple Spring Boot pods run concurrently. Ingress controller performs round-robin load-balancing and removes unhealthy nodes automatically.
2.  **Database Cluster:** Active-Passive topology utilizing `Patroni` or `pg_auto_failover`. Writes target the primary; reads can target read-replicas. If the primary fails, the replica is promoted.
3.  **Cache Cluster:** Redis Sentinel monitoring nodes. Sentinel detects a primary cache crash and promotes a replica, updating application connection pools via pub/sub notifications.

---

## 3. Disaster Recovery Metrics (RPO & RTO)

| Metric | Target Value | How it is Achieved |
| :--- | :--- | :--- |
| **RPO** (Recovery Point Objective) | **15 Minutes** | In the worst-case scenario, the system loses a maximum of 15 minutes of user metadata. Achieved through continuous WAL segment archiving. |
| **RTO** (Recovery Time Objective) | **2 Hours** | In the event of a full server wipe, the system is fully operational within 2 hours. Achieved through infrastructure-as-code scripts (Terraform/Kubernetes manifests) and automated failover pipelines. |

---

## 4. Restore Integrity Verification (The Backup Drill)

A common disaster recovery pitfall is failing to test backups. CloudShare specifies an automated **restore drill workflow**:

1.  **Weekly Restoration Trigger:** Every Sunday at 4:00 AM UTC, an automated job running on a staging Kubernetes namespace spins up a temporary PostgreSQL instance.
2.  **Snapshot Download:** The job pulls the latest full backup from the secure backup bucket.
3.  **Restore Execution:** It restores the database schema and imports the snapshot data.
4.  **Integrity Validation Script:** Runs a set of SQL tests:
    *   Verifies that the `users` and `files` row counts are non-zero.
    *   Ensures that table structure checks pass.
    *   Checks index validity.
5.  **Notification:** Upon successful completion, it logs a system status report: `BACKUP_VERIFICATION_SUCCESS`. If the restore fails, it triggers a high-severity PagerDuty/Slack alarm to notify engineers.
