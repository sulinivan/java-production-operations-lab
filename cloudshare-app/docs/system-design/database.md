# Database Schema & ERD

This document specifies the database design for the CloudShare application. We use **PostgreSQL** as our primary relational database to manage user accounts, roles, file metadata, sharing configurations, and system audit logs.

---

## 1. Entity-Relationship Diagram (ERD)

The diagram below represents the core data model. Relationships are enforced via strict foreign key constraints at the database level.

```mermaid
erDiagram
    users {
        uuid id PK
        varchar username UK
        varchar email UK
        varchar password_hash
        varchar mfa_secret
        boolean mfa_enabled
        timestamp created_at
        timestamp updated_at
    }

    roles {
        bigint id PK
        varchar name UK
    }

    user_roles {
        uuid user_id PK, FK
        bigint role_id PK, FK
    }

    files {
        uuid id PK
        uuid owner_id FK
        varchar storage_path UK
        varchar original_filename
        bigint file_size_bytes
        varchar mime_type
        varchar checksum_sha256
        varchar encrypted_fek
        varchar iv_gcm
        integer kek_version
        boolean deleted
        timestamp created_at
        timestamp updated_at
    }

    file_shares {
        uuid id PK
        uuid file_id FK
        uuid shared_by FK
        uuid shared_with FK
        varchar permission_type
        timestamp created_at
    }

    share_links {
        uuid id PK
        uuid file_id FK
        varchar share_code UK
        timestamp expires_at
        varchar password_hash
        integer download_limit
        integer download_count
        timestamp created_at
    }

    audit_logs {
        bigint id PK
        uuid user_id FK
        varchar action
        uuid file_id FK
        varchar ip_address
        text details
        timestamp created_at
    }

    users ||--o{ user_roles : "has"
    roles ||--o{ user_roles : "assigned_to"
    users ||--o{ files : "owns"
    files ||--o{ file_shares : "shared_in"
    users ||--o{ file_shares : "shares_via_by"
    users ||--o{ file_shares : "receives_via_with"
    files ||--o{ share_links : "has_public"
    users ||--o{ audit_logs : "triggers"
    files ||--o{ audit_logs : "referenced_in"
```

---

## 2. PostgreSQL DDL Schema

Below are the production-ready DDL statements, including indexes, auto-increment sequences, and constraints.

```sql
-- Enable UUID extension if not already loaded
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Users Table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(60) NOT NULL, -- BCrypt generates 60 char hashes
    mfa_secret VARCHAR(64), -- Altered to VARCHAR(64) to prevent padding issues
    mfa_enabled BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 2. Roles Table
CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE
);

-- 3. User Roles Mapping Table
CREATE TABLE user_roles (
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- Seed Initial Roles
INSERT INTO roles (name) VALUES ('ROLE_USER'), ('ROLE_ADMIN') ON CONFLICT DO NOTHING;

-- 4. Files Table (Stores Metadata, Encrypted Keys, and IV)
CREATE TABLE files (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    storage_path VARCHAR(255) NOT NULL UNIQUE, -- UUID filename on filesystem/S3 bucket
    original_filename VARCHAR(255) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    encrypted_fek VARCHAR(128) NOT NULL, -- AES FEK encrypted by KEK (Base64)
    iv_gcm VARCHAR(24) NOT NULL, -- 12 byte IV (Base64)
    kek_version INTEGER NOT NULL DEFAULT 1, -- KEK key version reference
    deleted BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 5. Direct User-to-User Shares Table
CREATE TABLE file_shares (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    shared_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shared_with UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission_type VARCHAR(10) NOT NULL CHECK (permission_type IN ('READ', 'WRITE')),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT unique_file_user_share UNIQUE (file_id, shared_with)
);

-- 6. Public Sharing Links Table
CREATE TABLE share_links (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    file_id UUID NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    share_code VARCHAR(16) NOT NULL UNIQUE, -- Random alphanumeric string
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    password_hash VARCHAR(60), -- BCrypt hash if protected, nullable
    download_limit INTEGER, -- Self-destruct if limit reached, nullable
    download_count INTEGER DEFAULT 0 NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 7. Audit Logs Table (System Activity)
CREATE TABLE audit_logs (
    id BIGSERIAL,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(50) NOT NULL,
    file_id UUID REFERENCES files(id) ON DELETE SET NULL,
    ip_address VARCHAR(45) NOT NULL, -- Accommodates IPv4 and IPv6
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (id, created_at) -- Multi-column key required for range partitioning
) PARTITION BY RANGE (created_at);
```

---

## 3. Indexing Strategy

To keep query performance under 10ms for common search queries under high load, the following indices are created:

```sql
-- Files Index: Speeds up dashboard loading for users listing their files.
CREATE INDEX idx_files_owner ON files(owner_id) WHERE deleted = FALSE;

-- Files Soft Delete Index: Speeds up background tasks checking for files to purge.
CREATE INDEX idx_files_deleted ON files(deleted) WHERE deleted = TRUE;

-- User Roles Index: Speeds up authorization queries during Spring Security filter evaluation.
CREATE INDEX idx_user_roles_uid ON user_roles(user_id);

-- Sharing Index: Optimizes fetching files shared with the current logged-in user.
CREATE INDEX idx_file_shares_receiver ON file_shares(shared_with);

-- Public Share Codes Index: Optimizes lookups for public link access.
CREATE UNIQUE INDEX idx_share_links_code ON share_links(share_code);

-- Audit Log index for time-based analytical lookups.
CREATE INDEX idx_audit_logs_time ON audit_logs(created_at DESC);
```

---

## 4. Partitioning & Archiving Strategy

The `audit_logs` table grows rapidly in active environments. Writing millions of rows will slow down index structures and disk reads.

*   **Partition Strategy:** We use **PostgreSQL Declarative Partitioning** to partition the `audit_logs` table by range on the `created_at` column.
*   **Interval:** Partitioning is configured monthly.
*   **Partition Creation DDL Example:**
    ```sql
    -- Example partitions for Q2 2026
    CREATE TABLE audit_logs_y2026m06 PARTITION OF audit_logs
        FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
    
    CREATE TABLE audit_logs_y2026m07 PARTITION OF audit_logs
        FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');
    ```
*   **Automated Partition Management:** To prevent write outages under the fail-closed audit logging model, CloudShare automates partition pre-creation and retirement via the Java-based `AuditPartitionScheduler`. 
    *   It executes daily at 4:00 AM UTC (`app.scheduler.audit-partition.cron`).
    *   It queries `pg_inherits` to check partition availability and pre-creates tables up to 3 months in advance (`app.scheduler.audit-partition.lookahead-months`).
    *   It identifies and retires partitions older than the configured retention period (`app.scheduler.audit-partition.retention-months`, default `6`).
    *   Manual maintenance can be triggered by administrators via `POST /api/v1/admin/audit-logs/partitions`.
*   **Archiving:** When retiring a partition, the scheduler automatically exports it as a gzip-compressed CSV file using PostgreSQL `CopyManager` and uploads it to object storage (`StorageService`) before executing the `DETACH` and `DROP` commands. If archiving fails, the drop is blocked to prevent data loss. This can be toggled using `app.scheduler.audit-partition.archive-enabled` (default `true`).

---

## 5. Database Connection Pool Settings (HikariCP)

For production loads, the Spring Boot configuration (`application.yml`) uses HikariCP tuned for resource efficiency:

```yaml
spring:
  datasource:
    hikari:
      pool-name: CloudShareHikariPool
      maximum-pool-size: 25 # Core count * 2 + spindle count rule
      minimum-idle: 10
      idle-timeout: 600000 # 10 minutes
      max-lifetime: 1800000 # 30 minutes
      connection-timeout: 30000 # 30 seconds
      leak-detection-threshold: 2000 # 2 seconds to trace unclosed connections
```

---

## 6. Soft-Delete Referential Integrity Rules

When a file's state changes to `deleted = TRUE` (moved to the Recycle Bin), all active share records and public links pointing to it must be invalidated immediately.

### 6.1 Database Trigger Cleanup
To prevent index bloat and ensure security at the database layer, an asynchronous trigger automatically deletes associated permissions upon file soft-deletion:

```sql
-- Trigger Function to clean up shares when a file is soft-deleted
CREATE OR REPLACE FUNCTION purge_shares_on_file_soft_delete()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.deleted = TRUE AND OLD.deleted = FALSE THEN
        -- Revoke all internal user shares
        DELETE FROM file_shares WHERE file_id = NEW.id;
        
        -- Delete all public sharing links
        DELETE FROM share_links WHERE file_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Bind Trigger to Files Table
CREATE TRIGGER trg_files_soft_delete
    AFTER UPDATE OF deleted ON files
    FOR EACH ROW
    WHEN (NEW.deleted = TRUE AND OLD.deleted = FALSE)
    EXECUTE FUNCTION purge_shares_on_file_soft_delete();
```

### 6.2 Application Query Resolution (Defense-in-Depth)
Even before triggers execute, all query filters resolving permissions must join the `files` table and verify that `deleted = FALSE` to prevent race conditions:

```sql
-- Query to resolve active internal shares for a specific user
SELECT fs.* FROM file_shares fs
JOIN files f ON fs.file_id = f.id
WHERE fs.shared_with = :userId 
  AND fs.file_id = :fileId 
  AND f.deleted = FALSE;
```

