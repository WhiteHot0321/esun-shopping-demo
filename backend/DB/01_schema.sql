CREATE DATABASE IF NOT EXISTS esun_shop CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE esun_shop;

DROP TABLE IF EXISTS order_detail;
DROP TABLE IF EXISTS payment_transaction;
DROP TABLE IF EXISTS order_request;
DROP TABLE IF EXISTS shop_order;
DROP TABLE IF EXISTS product;

CREATE TABLE product (
    product_id   VARCHAR(20) PRIMARY KEY,
    product_name VARCHAR(100) NOT NULL,
    price        DECIMAL(12,2) NOT NULL CHECK (price >= 0),
    quantity     INT NOT NULL CHECK (quantity >= 0),
    creator_id   VARCHAR(255) NOT NULL DEFAULT 'legacy',
    deleted_at   DATETIME NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE shop_order (
    order_id     VARCHAR(30) PRIMARY KEY,
    member_id    VARCHAR(255) NOT NULL,
    price        DECIMAL(12,2) NOT NULL CHECK (price >= 0),
    pay_status   TINYINT NOT NULL DEFAULT 0,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_order_member_created (member_id, created_at)
);

CREATE TABLE payment_transaction (
    order_id                VARCHAR(30) PRIMARY KEY,
    merchant_trade_no       VARCHAR(20) NOT NULL UNIQUE,
    provider_transaction_id VARCHAR(64) NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_transaction_order FOREIGN KEY (order_id) REFERENCES shop_order(order_id)
);

CREATE TABLE order_request (
    request_id VARCHAR(64) PRIMARY KEY,
    order_id   VARCHAR(32) NOT NULL,
    member_id  VARCHAR(255) NOT NULL,
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
