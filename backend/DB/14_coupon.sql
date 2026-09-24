USE esun_shop;

-- Repeatable migration for the coupon system (Phase 3.2 #14).
--
-- coupon: platform-wide discount codes managed by ADMIN. Discount rule fields (type/value/cap/minimum) are immutable
-- after creation so an issued code can never silently change meaning; only active / expires_at / total_quota move.
-- used_count is a denormalised redemption counter that is only changed while the coupon row is locked (checkout
-- redeems, cancel releases); the CHECK is a last-resort backstop so a code path bug cannot over-issue the quota.
--
-- coupon_member_usage: per-member redemption counter, same lifecycle as used_count (one row per coupon+member).
--
-- shop_order gains a snapshot of what was applied. price stays the amount payable (payment always charges price), so
-- price + discount_amount is the pre-discount subtotal.
CREATE TABLE IF NOT EXISTS coupon (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    code             VARCHAR(32)   NOT NULL,
    discount_type    VARCHAR(10)   NOT NULL,
    discount_value   DECIMAL(12,2) NOT NULL,
    max_discount     DECIMAL(12,2) NULL,
    min_order_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    total_quota      INT           NULL,
    used_count       INT           NOT NULL DEFAULT 0,
    per_member_limit INT           NOT NULL DEFAULT 1,
    starts_at        DATETIME      NOT NULL,
    expires_at       DATETIME      NOT NULL,
    active           TINYINT(1)    NOT NULL DEFAULT 1,
    created_by       VARCHAR(255)  NOT NULL,
    created_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_coupon_code UNIQUE (code),
    CONSTRAINT chk_coupon_type CHECK (discount_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT chk_coupon_value CHECK (discount_value > 0
        AND (discount_type = 'FIXED' OR discount_value < 100)),
    CONSTRAINT chk_coupon_max_discount CHECK (max_discount IS NULL OR max_discount > 0),
    CONSTRAINT chk_coupon_min_order CHECK (min_order_amount >= 0),
    CONSTRAINT chk_coupon_quota CHECK (total_quota IS NULL OR (total_quota > 0 AND used_count <= total_quota)),
    CONSTRAINT chk_coupon_used CHECK (used_count >= 0),
    CONSTRAINT chk_coupon_member_limit CHECK (per_member_limit > 0),
    CONSTRAINT chk_coupon_window CHECK (expires_at > starts_at)
);

CREATE TABLE IF NOT EXISTS coupon_member_usage (
    coupon_id  BIGINT       NOT NULL,
    member_id  VARCHAR(100) NOT NULL,
    used_count INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (coupon_id, member_id),
    CONSTRAINT chk_coupon_member_used CHECK (used_count >= 0),
    CONSTRAINT fk_coupon_usage_coupon FOREIGN KEY (coupon_id) REFERENCES coupon(id)
);

SET @add_coupon_id = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'shop_order' AND column_name = 'coupon_id') = 0,
    'ALTER TABLE shop_order ADD COLUMN coupon_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @add_coupon_id;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_coupon_code = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'shop_order' AND column_name = 'coupon_code') = 0,
    'ALTER TABLE shop_order ADD COLUMN coupon_code VARCHAR(32) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @add_coupon_code;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_discount_amount = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'shop_order' AND column_name = 'discount_amount') = 0,
    'ALTER TABLE shop_order ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE stmt FROM @add_discount_amount;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_coupon_fk = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'shop_order' AND constraint_name = 'fk_shop_order_coupon') = 0,
    'ALTER TABLE shop_order ADD CONSTRAINT fk_shop_order_coupon FOREIGN KEY (coupon_id) REFERENCES coupon(id)',
    'SELECT 1'
);
PREPARE stmt FROM @add_coupon_fk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_discount_chk = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'shop_order' AND constraint_name = 'chk_shop_order_discount') = 0,
    'ALTER TABLE shop_order ADD CONSTRAINT chk_shop_order_discount CHECK (discount_amount >= 0)',
    'SELECT 1'
);
PREPARE stmt FROM @add_discount_chk;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
