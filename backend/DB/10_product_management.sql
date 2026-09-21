-- Repeatable migration for seller-owned product management.
SET @deleted_at_missing = (SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'deleted_at');
SET @sql = IF(@deleted_at_missing,
    'ALTER TABLE product ADD COLUMN deleted_at DATETIME NULL AFTER creator_id', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE product SET creator_id = 'legacy' WHERE creator_id IS NULL OR TRIM(creator_id) = '';
ALTER TABLE product MODIFY creator_id VARCHAR(255) NOT NULL DEFAULT 'legacy';

CREATE TABLE IF NOT EXISTS product_image (
    image_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id VARCHAR(20) NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product(product_id),
    INDEX idx_product_image_product (product_id, display_order, image_id)
);
