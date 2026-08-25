-- Alter user table mfa_secret column length to 64 characters to prevent padding issues
ALTER TABLE users ALTER COLUMN mfa_secret TYPE VARCHAR(64);
