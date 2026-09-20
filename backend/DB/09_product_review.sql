USE esun_shop;

-- Existing installations predate RBAC and product ownership. Guard every additive
-- migration so this script is safe to execute again during a deployment drill.
SET @role_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'member' AND column_name = 'role'
);
SET @sql = IF(@role_exists = 0,
    "ALTER TABLE member ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'BUYER' AFTER phone",
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @role_check_exists = (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'member'
      AND constraint_name = 'chk_member_role'
);
SET @sql = IF(@role_check_exists = 0,
    "ALTER TABLE member ADD CONSTRAINT chk_member_role CHECK (role IN ('BUYER','SELLER','ADMIN'))",
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @creator_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'product' AND column_name = 'creator_id'
);
SET @sql = IF(@creator_exists = 0,
    'ALTER TABLE product ADD COLUMN creator_id VARCHAR(255) NULL AFTER quantity',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @creator_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'product' AND index_name = 'idx_product_creator'
);
SET @sql = IF(@creator_index_exists = 0,
    'CREATE INDEX idx_product_creator ON product(creator_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS product_review (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id  VARCHAR(20) NOT NULL,
    member_id   BIGINT NOT NULL,
    rating      TINYINT NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    visibility  VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_review_member_product UNIQUE (member_id, product_id),
    CONSTRAINT chk_product_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_product_review_visibility CHECK (visibility IN ('VISIBLE', 'HIDDEN')),
    CONSTRAINT fk_product_review_product FOREIGN KEY (product_id) REFERENCES product(product_id),
    CONSTRAINT fk_product_review_member FOREIGN KEY (member_id) REFERENCES member(id),
    INDEX idx_product_review_public (product_id, visibility, created_at, id)
);
