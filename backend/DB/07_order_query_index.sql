-- Additive migration for existing databases. Supports GET /api/orders pagination
-- (WHERE member_id = ? ORDER BY created_at DESC) without a full table scan.
-- MySQL (unlike MariaDB) does not support ADD INDEX IF NOT EXISTS, so guard with an
-- information_schema check plus dynamic SQL.
SET @index_missing = (SELECT COUNT(*) = 0 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_order' AND INDEX_NAME = 'idx_shop_order_member_created');
SET @sql = IF(@index_missing,
    'ALTER TABLE shop_order ADD INDEX idx_shop_order_member_created (member_id, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
