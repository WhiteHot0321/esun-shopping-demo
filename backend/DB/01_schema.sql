CREATE DATABASE IF NOT EXISTS esun_shop CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE esun_shop;

DROP TABLE IF EXISTS order_detail;
DROP TABLE IF EXISTS shop_order;
DROP TABLE IF EXISTS product;

CREATE TABLE product (
    product_id   VARCHAR(20) PRIMARY KEY,
    product_name VARCHAR(100) NOT NULL,
    price        DECIMAL(12,2) NOT NULL CHECK (price >= 0),
    quantity     INT NOT NULL CHECK (quantity >= 0),
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE shop_order (
    order_id     VARCHAR(30) PRIMARY KEY,
    member_id    VARCHAR(20) NOT NULL,
    price        DECIMAL(12,2) NOT NULL CHECK (price >= 0),
    pay_status   TINYINT NOT NULL DEFAULT 0,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_detail (
    order_item_sn BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id      VARCHAR(30) NOT NULL,
    product_id    VARCHAR(20) NOT NULL,
    quantity      INT NOT NULL CHECK (quantity > 0),
    stand_price   DECIMAL(12,2) NOT NULL CHECK (stand_price >= 0),
    item_price    DECIMAL(12,2) NOT NULL CHECK (item_price >= 0),
    CONSTRAINT fk_order_detail_order FOREIGN KEY (order_id) REFERENCES shop_order(order_id),
    CONSTRAINT fk_order_detail_product FOREIGN KEY (product_id) REFERENCES product(product_id)
);
