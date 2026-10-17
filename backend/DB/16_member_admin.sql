USE esun_shop;

-- Phase 4.#3 (M0): account status + token version for admin-driven revocation.
-- Idempotent like 06_member_profile.sql. Flyway/prod equivalent: db/migration/V2__member_admin.sql.
SET @add_status = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE member ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT ''ACTIVE'' AFTER role, ADD CONSTRAINT chk_member_status CHECK (status IN (''ACTIVE'', ''DISABLED''))',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'member' AND column_name = 'status'
);
PREPARE add_status_statement FROM @add_status;
EXECUTE add_status_statement;
DEALLOCATE PREPARE add_status_statement;

SET @add_token_version = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE member ADD COLUMN token_version INT NOT NULL DEFAULT 0 AFTER status',
        'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'member' AND column_name = 'token_version'
);
PREPARE add_token_version_statement FROM @add_token_version;
EXECUTE add_token_version_statement;
DEALLOCATE PREPARE add_token_version_statement;
