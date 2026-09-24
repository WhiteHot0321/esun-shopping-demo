-- Phase 3.2 #15 recommendations: read-only co-purchase queries scan order_detail by product, then jump to the sibling
-- lines of the same order. (product_id, order_id) covers that first hop without touching the clustered index.
-- Repeatable: only adds the index when it is missing.
USE esun_shop;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'order_detail'
                     AND index_name = 'idx_order_detail_product_order');
SET @ddl = IF(@idx_exists = 0,
              'ALTER TABLE order_detail ADD INDEX idx_order_detail_product_order (product_id, order_id)',
              'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
