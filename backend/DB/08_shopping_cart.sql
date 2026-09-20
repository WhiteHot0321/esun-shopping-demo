USE esun_shop;

CREATE TABLE IF NOT EXISTS shopping_cart (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    member_id  BIGINT NOT NULL,
    product_id VARCHAR(20) NOT NULL,
    quantity   INT NOT NULL CHECK (quantity > 0),
    added_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_shopping_cart_member FOREIGN KEY (member_id) REFERENCES member(id) ON DELETE CASCADE,
    CONSTRAINT fk_shopping_cart_product FOREIGN KEY (product_id) REFERENCES product(product_id),
    CONSTRAINT uk_shopping_cart_member_product UNIQUE (member_id, product_id),
    INDEX idx_shopping_cart_member (member_id, id)
);
