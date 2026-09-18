-- Repeatable additive migration: existing/seed products are intentionally owned by the
-- non-loginable legacy principal so no real JWT user can silently administer them.
-- MySQL (unlike MariaDB) does not support ADD COLUMN IF NOT EXISTS, so each column is
-- guarded with an information_schema check plus dynamic SQL.
SET @creator_id_missing = (SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'creator_id');
SET @sql = IF(@creator_id_missing, 'ALTER TABLE product ADD COLUMN creator_id VARCHAR(255) NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @deleted_at_missing = (SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'deleted_at');
SET @sql = IF(@deleted_at_missing, 'ALTER TABLE product ADD COLUMN deleted_at DATETIME NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE product SET creator_id = 'legacy' WHERE creator_id IS NULL OR TRIM(creator_id) = '';
ALTER TABLE product MODIFY creator_id VARCHAR(255) NOT NULL;
