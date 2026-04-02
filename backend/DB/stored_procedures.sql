USE esun_shop;

DROP PROCEDURE IF EXISTS sp_add_product;
DELIMITER //
CREATE PROCEDURE sp_add_product(
    IN p_product_id VARCHAR(20),
    IN p_product_name VARCHAR(100),
    IN p_price DECIMAL(12,2),
    IN p_quantity INT
)
BEGIN
    INSERT INTO product(product_id, product_name, price, quantity)
    VALUES (p_product_id, p_product_name, p_price, p_quantity);
END //
DELIMITER ;

DROP PROCEDURE IF EXISTS sp_get_available_products;
DELIMITER //
CREATE PROCEDURE sp_get_available_products()
BEGIN
    SELECT product_id, product_name, price, quantity
    FROM product
    WHERE quantity > 0
    ORDER BY product_id;
END //
DELIMITER ;

DROP PROCEDURE IF EXISTS sp_decrease_stock;
DELIMITER //
CREATE PROCEDURE sp_decrease_stock(
    IN p_product_id VARCHAR(20),
    IN p_buy_quantity INT
)
BEGIN
    UPDATE product
    SET quantity = quantity - p_buy_quantity
    WHERE product_id = p_product_id
      AND quantity >= p_buy_quantity;

    IF ROW_COUNT() = 0 THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = '庫存不足或商品不存在';
    END IF;
END //
DELIMITER ;
