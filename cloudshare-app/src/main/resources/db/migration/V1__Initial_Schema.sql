-- Enable UUID extension if not already loaded
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. Users Table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(60) NOT NULL, -- BCrypt generates 60 char hashes
    mfa_secret VARCHAR(32),
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

-- 7. Audit Logs Table (System Activity) - Range Partitioned
CREATE TABLE audit_logs (
    id BIGSERIAL,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(50) NOT NULL,
    file_id UUID REFERENCES files(id) ON DELETE SET NULL,
    ip_address VARCHAR(45) NOT NULL,
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- 8. Indexes for Optimization
CREATE INDEX idx_files_owner ON files(owner_id) WHERE deleted = FALSE;
CREATE INDEX idx_files_deleted ON files(deleted) WHERE deleted = TRUE;
CREATE INDEX idx_user_roles_uid ON user_roles(user_id);
CREATE INDEX idx_file_shares_receiver ON file_shares(shared_with);
CREATE UNIQUE INDEX idx_share_links_code ON share_links(share_code);
CREATE INDEX idx_audit_logs_time ON audit_logs(created_at DESC);

-- 9. Trigger for Soft-Delete Integrity Cleanup
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

CREATE TRIGGER trg_files_soft_delete
    AFTER UPDATE OF deleted ON files
    FOR EACH ROW
    WHEN (NEW.deleted = TRUE AND OLD.deleted = FALSE)
    EXECUTE FUNCTION purge_shares_on_file_soft_delete();

-- 10. Audit Log Partitions
-- Active monthly partitions for 2026
CREATE TABLE audit_logs_y2026m06 PARTITION OF audit_logs
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');

CREATE TABLE audit_logs_y2026m07 PARTITION OF audit_logs
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

CREATE TABLE audit_logs_y2026m08 PARTITION OF audit_logs
    FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');

CREATE TABLE audit_logs_y2026m09 PARTITION OF audit_logs
    FOR VALUES FROM ('2026-09-01 00:00:00+00') TO ('2026-10-01 00:00:00+00');

-- Default Partition for Safety
CREATE TABLE audit_logs_default PARTITION OF audit_logs DEFAULT;
