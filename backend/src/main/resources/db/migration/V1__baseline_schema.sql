-- Production baseline. This is intentionally a create-only snapshot: it contains no demo data,
-- DROP statements, or legacy initdb migrations. Existing databases are adopted only by the
-- explicit structural preflight in FlywayAdoptionConfiguration.

CREATE TABLE product (
    product_id VARCHAR(20) PRIMARY KEY, product_name VARCHAR(100) NOT NULL,
    price DECIMAL(12,2) NOT NULL CHECK (price >= 0.01), quantity INT NOT NULL CHECK (quantity >= 0),
    creator_id VARCHAR(255) NOT NULL DEFAULT 'legacy', deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_product_creator (creator_id)
);

CREATE TABLE member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL, display_name VARCHAR(100) NULL, phone VARCHAR(30) NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'BUYER', created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_member_role CHECK (role IN ('BUYER', 'SELLER', 'ADMIN'))
);

CREATE TABLE shop_order (
    order_id VARCHAR(30) PRIMARY KEY, member_id VARCHAR(100) NOT NULL,
    price DECIMAL(12,2) NOT NULL CHECK (price >= 0), pay_status TINYINT NOT NULL DEFAULT 0,
    order_status VARCHAR(20) NOT NULL DEFAULT 'CREATED', shipping_address_id BIGINT NULL,
    coupon_id BIGINT NULL, coupon_code VARCHAR(32) NULL, discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_shop_order_discount CHECK (discount_amount >= 0),
    INDEX idx_shop_order_member (member_id, created_at), INDEX idx_shop_order_status (order_status, created_at)
);

CREATE TABLE order_request (
    request_id VARCHAR(64) PRIMARY KEY, order_id VARCHAR(32) NOT NULL,
    member_id VARCHAR(100) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_detail (
    order_item_sn BIGINT PRIMARY KEY AUTO_INCREMENT, order_id VARCHAR(30) NOT NULL,
    product_id VARCHAR(20) NOT NULL, quantity INT NOT NULL CHECK (quantity > 0),
    unit_price DECIMAL(12,2) NOT NULL CHECK (unit_price >= 0), item_price DECIMAL(12,2) NOT NULL CHECK (item_price >= 0),
    CONSTRAINT fk_order_detail_order FOREIGN KEY (order_id) REFERENCES shop_order(order_id),
    CONSTRAINT fk_order_detail_product FOREIGN KEY (product_id) REFERENCES product(product_id),
    INDEX idx_order_detail_product_order (product_id, order_id)
);

CREATE TABLE product_image (
    image_id BIGINT PRIMARY KEY AUTO_INCREMENT, product_id VARCHAR(20) NOT NULL,
    image_url VARCHAR(500) NOT NULL, display_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product(product_id),
    INDEX idx_product_image_product (product_id, display_order, image_id)
);

CREATE TABLE shipping_address (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL, label VARCHAR(50) NOT NULL,
    receiver_name VARCHAR(100) NOT NULL, phone VARCHAR(30) NOT NULL, postal_code VARCHAR(10) NULL,
    address VARCHAR(255) NOT NULL, is_default BOOLEAN NOT NULL DEFAULT FALSE,
    default_member_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_default THEN member_id ELSE NULL END) STORED,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_shipping_address_member FOREIGN KEY (member_id) REFERENCES member(id),
    CONSTRAINT uk_shipping_address_default UNIQUE (default_member_id), INDEX idx_shipping_address_member (member_id, id)
);

ALTER TABLE shop_order ADD CONSTRAINT fk_shop_order_shipping_address
    FOREIGN KEY (shipping_address_id) REFERENCES shipping_address(id);

CREATE TABLE shopping_cart (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL, product_id VARCHAR(20) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0), added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_shopping_cart_member FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE CASCADE,
    CONSTRAINT fk_shopping_cart_product FOREIGN KEY (product_id) REFERENCES product(product_id),
    CONSTRAINT uk_shopping_cart_member_product UNIQUE (member_id, product_id), INDEX idx_shopping_cart_member (member_id, id)
);

CREATE TABLE product_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, product_id VARCHAR(20) NOT NULL, member_id BIGINT NOT NULL,
    rating TINYINT NOT NULL, content VARCHAR(1000) NOT NULL, visibility VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_product_review_member_product UNIQUE (member_id, product_id),
    CONSTRAINT chk_product_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_product_review_visibility CHECK (visibility IN ('VISIBLE', 'HIDDEN')),
    CONSTRAINT fk_product_review_product FOREIGN KEY (product_id) REFERENCES product(product_id),
    CONSTRAINT fk_product_review_member FOREIGN KEY (member_id) REFERENCES member(id),
    INDEX idx_product_review_public (product_id, visibility, created_at, id)
);

CREATE TABLE password_reset_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT NOT NULL, token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at DATETIME NOT NULL, used_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_password_reset_token_member FOREIGN KEY (member_id) REFERENCES member(id),
    INDEX idx_password_reset_token_member (member_id)
);

CREATE TABLE order_status_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id VARCHAR(30) NOT NULL, from_status VARCHAR(20) NULL,
    to_status VARCHAR(20) NOT NULL, actor VARCHAR(255) NOT NULL, actor_role VARCHAR(20) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES shop_order(order_id),
    INDEX idx_order_status_history_order (order_id, id)
);

CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, actor VARCHAR(255) NOT NULL, actor_role VARCHAR(20) NOT NULL,
    action VARCHAR(40) NOT NULL, target_type VARCHAR(30) NOT NULL, target_id VARCHAR(100) NOT NULL,
    before_state JSON NULL, after_state JSON NULL, created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_audit_log_created (created_at, id), INDEX idx_audit_log_actor (actor, id),
    INDEX idx_audit_log_target (target_type, target_id, id), INDEX idx_audit_log_action (action, id)
);

CREATE TABLE payment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id VARCHAR(30) NOT NULL, merchant_trade_no VARCHAR(40) NOT NULL,
    provider VARCHAR(20) NOT NULL, amount DECIMAL(12,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'INITIATED', provider_ref VARCHAR(64) NULL, failure_reason VARCHAR(100) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    paid_at DATETIME(3) NULL, active_order_id VARCHAR(30) GENERATED ALWAYS AS
        (CASE WHEN status IN ('INITIATED', 'SUCCEEDED') THEN order_id ELSE NULL END) STORED,
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES shop_order(order_id),
    CONSTRAINT uq_payment_trade_no UNIQUE (merchant_trade_no), CONSTRAINT uq_payment_active_order UNIQUE (active_order_id),
    INDEX idx_payment_order (order_id, id)
);

CREATE TABLE coupon (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(32) NOT NULL, discount_type VARCHAR(10) NOT NULL,
    discount_value DECIMAL(12,2) NOT NULL, max_discount DECIMAL(12,2) NULL,
    min_order_amount DECIMAL(12,2) NOT NULL DEFAULT 0, total_quota INT NULL, used_count INT NOT NULL DEFAULT 0,
    per_member_limit INT NOT NULL DEFAULT 1, starts_at DATETIME NOT NULL, expires_at DATETIME NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1, created_by VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_coupon_code UNIQUE (code), CONSTRAINT chk_coupon_type CHECK (discount_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT chk_coupon_value CHECK (discount_value > 0 AND (discount_type = 'FIXED' OR discount_value < 100)),
    CONSTRAINT chk_coupon_max_discount CHECK (max_discount IS NULL OR max_discount > 0),
    CONSTRAINT chk_coupon_min_order CHECK (min_order_amount >= 0),
    CONSTRAINT chk_coupon_quota CHECK (total_quota IS NULL OR (total_quota > 0 AND used_count <= total_quota)),
    CONSTRAINT chk_coupon_used CHECK (used_count >= 0), CONSTRAINT chk_coupon_member_limit CHECK (per_member_limit > 0),
    CONSTRAINT chk_coupon_window CHECK (expires_at > starts_at)
);

CREATE TABLE coupon_member_usage (
    coupon_id BIGINT NOT NULL, member_id VARCHAR(100) NOT NULL, used_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (coupon_id, member_id), CONSTRAINT chk_coupon_member_used CHECK (used_count >= 0),
    CONSTRAINT fk_coupon_usage_coupon FOREIGN KEY (coupon_id) REFERENCES coupon(id)
);

ALTER TABLE shop_order ADD CONSTRAINT fk_shop_order_coupon FOREIGN KEY (coupon_id) REFERENCES coupon(id);

CREATE TABLE faq (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, question VARCHAR(500) NOT NULL, answer TEXT NOT NULL,
    category VARCHAR(50), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE doc_embedding (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, source_type ENUM('product', 'faq') NOT NULL,
    source_id VARCHAR(20) NOT NULL, content TEXT NOT NULL, embedding JSON NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doc_embedding_source (source_type, source_id)
);

DELIMITER //
CREATE PROCEDURE sp_add_product(IN p_product_id VARCHAR(20), IN p_product_name VARCHAR(100),
                                IN p_price DECIMAL(12,2), IN p_quantity INT)
BEGIN
    INSERT INTO product(product_id, product_name, price, quantity)
    VALUES (p_product_id, p_product_name, p_price, p_quantity);
END //
CREATE PROCEDURE sp_get_available_products()
BEGIN
    SELECT product_id, product_name, price, quantity FROM product
    WHERE quantity > 0 AND deleted_at IS NULL ORDER BY product_id;
END //
CREATE PROCEDURE sp_decrease_stock(IN p_product_id VARCHAR(20), IN p_buy_quantity INT)
BEGIN
    UPDATE product SET quantity = quantity - p_buy_quantity
    WHERE product_id = p_product_id AND quantity >= p_buy_quantity AND deleted_at IS NULL;
    IF ROW_COUNT() = 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '庫存不足或商品不存在'; END IF;
END //
DELIMITER ;
