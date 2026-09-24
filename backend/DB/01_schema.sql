CREATE DATABASE IF NOT EXISTS esun_shop CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE esun_shop;

DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS order_status_history;
DROP TABLE IF EXISTS order_detail;
DROP TABLE IF EXISTS order_request;
DROP TABLE IF EXISTS shop_order;
DROP TABLE IF EXISTS product_review;
DROP TABLE IF EXISTS product_image;
DROP TABLE IF EXISTS product;

CREATE TABLE product (
    product_id   VARCHAR(20) PRIMARY KEY,
    product_name VARCHAR(100) NOT NULL,
    price        DECIMAL(12,2) NOT NULL CHECK (price >= 0.01),
    quantity     INT NOT NULL CHECK (quantity >= 0),
    creator_id   VARCHAR(255) NOT NULL DEFAULT 'legacy',
    deleted_at   DATETIME NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_product_creator (creator_id)
);

CREATE TABLE product_image (
    image_id     BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id   VARCHAR(20) NOT NULL,
    image_url    VARCHAR(500) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product(product_id),
    INDEX idx_product_image_product (product_id, display_order, image_id)
);

CREATE TABLE shop_order (
    order_id     VARCHAR(30) PRIMARY KEY,
    member_id    VARCHAR(100) NOT NULL,
    price        DECIMAL(12,2) NOT NULL CHECK (price >= 0),
    pay_status   TINYINT NOT NULL DEFAULT 0,
    order_status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_order_member (member_id, created_at),
    INDEX idx_shop_order_status (order_status, created_at)
);

CREATE TABLE order_request (
    request_id VARCHAR(64) PRIMARY KEY,
    order_id   VARCHAR(32) NOT NULL,
    member_id  VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_detail (
    order_item_sn BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id      VARCHAR(30) NOT NULL,
    product_id    VARCHAR(20) NOT NULL,
    quantity      INT NOT NULL CHECK (quantity > 0),
    unit_price    DECIMAL(12,2) NOT NULL CHECK (unit_price >= 0),
    item_price    DECIMAL(12,2) NOT NULL CHECK (item_price >= 0),
    CONSTRAINT fk_order_detail_order FOREIGN KEY (order_id) REFERENCES shop_order(order_id),
    CONSTRAINT fk_order_detail_product FOREIGN KEY (product_id) REFERENCES product(product_id)
);

-- Append-only audit trail of every order_status change; also the source of the buyer-facing timeline.
CREATE TABLE order_status_history (
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

-- Append-only operation audit log: who (actor/role) did what (action) to which record, with before/after snapshots.
-- No foreign key on target_*: targets span several tables and must stay traceable after the target is deleted.
CREATE TABLE audit_log (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor        VARCHAR(255) NOT NULL,
    actor_role   VARCHAR(20)  NOT NULL,
    action       VARCHAR(40)  NOT NULL,
    target_type  VARCHAR(30)  NOT NULL,
    target_id    VARCHAR(100) NOT NULL,
    before_state JSON NULL,
    after_state  JSON NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_audit_log_created (created_at, id),
    INDEX idx_audit_log_actor (actor, id),
    INDEX idx_audit_log_target (target_type, target_id, id),
    INDEX idx_audit_log_action (action, id)
);
