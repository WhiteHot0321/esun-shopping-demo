USE esun_shop;

CREATE TABLE IF NOT EXISTS shipping_address (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    member_id         BIGINT NOT NULL,
    label             VARCHAR(50) NOT NULL,
    receiver_name     VARCHAR(100) NOT NULL,
    phone             VARCHAR(30) NOT NULL,
    postal_code       VARCHAR(10) NULL,
    address           VARCHAR(255) NOT NULL,
    is_default        BOOLEAN NOT NULL DEFAULT FALSE,
    default_member_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_default THEN member_id ELSE NULL END
    ) STORED,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_shipping_address_member FOREIGN KEY (member_id) REFERENCES member(id),
    CONSTRAINT uk_shipping_address_default UNIQUE (default_member_id),
    INDEX idx_shipping_address_member (member_id, id)
);

-- MySQL 8.0 does not consistently support ADD COLUMN IF NOT EXISTS across supported patch
-- releases, so keep the migration repeatable through information_schema guards.
SET @add_shipping_address_id = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'shop_order'
       AND column_name = 'shipping_address_id') = 0,
    'ALTER TABLE shop_order ADD COLUMN shipping_address_id BIGINT NULL AFTER member_id',
    'SELECT 1'
);
PREPARE stmt FROM @add_shipping_address_id;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_shipping_address_fk = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE() AND table_name = 'shop_order'
       AND constraint_name = 'fk_shop_order_shipping_address') = 0,
    'ALTER TABLE shop_order ADD CONSTRAINT fk_shop_order_shipping_address FOREIGN KEY (shipping_address_id) REFERENCES shipping_address(id)',
    'SELECT 1'
);
PREPARE stmt FROM @add_shipping_address_fk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
