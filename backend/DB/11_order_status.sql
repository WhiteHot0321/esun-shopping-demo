USE esun_shop;

-- Repeatable migration for the order status flow (CREATED -> CONFIRMED -> SHIPPED -> DELIVERED, or CANCELLED).
SET @add_order_status = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'shop_order' AND column_name = 'order_status') = 0,
    'ALTER TABLE shop_order ADD COLUMN order_status VARCHAR(20) NOT NULL DEFAULT ''CREATED'' AFTER pay_status',
    'SELECT 1'
);
PREPARE stmt FROM @add_order_status;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_member_idx = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'shop_order' AND index_name = 'idx_shop_order_member') = 0,
    'ALTER TABLE shop_order ADD INDEX idx_shop_order_member (member_id, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @add_member_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_status_idx = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'shop_order' AND index_name = 'idx_shop_order_status') = 0,
    'ALTER TABLE shop_order ADD INDEX idx_shop_order_status (order_status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @add_status_idx;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS order_status_history (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id    VARCHAR(30) NOT NULL,
    from_status VARCHAR(20) NULL,
    to_status   VARCHAR(20) NOT NULL,
    actor       VARCHAR(255) NOT NULL,
    actor_role  VARCHAR(20) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES shop_order(order_id),
    INDEX idx_order_status_history_order (order_id, id)
);

-- Give pre-existing orders an initial timeline entry; orders that already have history are left alone.
INSERT INTO order_status_history (order_id, from_status, to_status, actor, actor_role, created_at)
SELECT o.order_id, NULL, o.order_status, o.member_id, 'BUYER', o.created_at
FROM shop_order o
WHERE NOT EXISTS (SELECT 1 FROM order_status_history h WHERE h.order_id = o.order_id);
