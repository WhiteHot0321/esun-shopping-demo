-- SQLPERF-01 synthetic dataset for a DISPOSABLE MySQL only (never run against a real database).
-- Applied after backend/src/main/resources/db/migration/V1__baseline_schema.sql. Skewed on purpose: member 1 and
-- product P00001 are "hot" (RAND()^2 concentrates rows at low ids), so list/aggregate queries see both a heavy and a
-- typical case. RAND() is unseeded, so counts vary slightly between runs; cardinalities are fixed by the WHERE bounds.
SET SESSION cte_max_recursion_depth = 300000;
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;

CREATE TABLE nums (n INT PRIMARY KEY);
INSERT INTO nums WITH RECURSIVE s AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM s WHERE n < 250000) SELECT n FROM s;

-- 5,000 members; ids 1..50 are sellers.
INSERT INTO member(email, password_hash, display_name, role, created_at)
SELECT CONCAT('u', n, '@example.com'), 'x', CONCAT('User ', n), IF(n <= 50, 'SELLER', 'BUYER'), NOW() - INTERVAL (n % 365) DAY
FROM nums WHERE n <= 5000;

-- 2,000 products (10% soft-deleted, 10% sold out), owned by the 50 sellers.
INSERT INTO product(product_id, product_name, price, quantity, creator_id, deleted_at, created_at)
SELECT CONCAT('P', LPAD(n, 5, '0')), CONCAT('Product ', n, ' ', ELT(1 + n % 5, 'chair', 'table', 'lamp', 'sofa', 'desk')),
       10 + (n % 990), IF(n % 10 = 0, 0, 10 + (n % 500)), CONCAT('u', 1 + (n % 50), '@example.com'),
       IF(n % 10 = 7, NOW(), NULL), NOW() - INTERVAL (n % 365) DAY
FROM nums WHERE n <= 2000;

INSERT INTO shipping_address(member_id, label, receiver_name, phone, address, is_default)
SELECT n, 'home', CONCAT('User ', n), '0900000000', 'Taipei', TRUE FROM nums WHERE n <= 5000;

-- 100,000 orders over the last year; buyer skew toward low member ids.
INSERT INTO shop_order(order_id, member_id, price, pay_status, order_status, shipping_address_id, created_at)
SELECT CONCAT('O', LPAD(n, 8, '0')), CONCAT('u', m, '@example.com'), 50 + (n % 2000), 1,
       CASE WHEN n % 20 = 0 THEN 'CANCELLED' WHEN n % 5 = 0 THEN 'CREATED' WHEN n % 5 = 1 THEN 'CONFIRMED'
            WHEN n % 5 = 2 THEN 'SHIPPED' ELSE 'DELIVERED' END,
       m, NOW() - INTERVAL FLOOR(RAND() * 365 * 24 * 60) MINUTE
FROM (SELECT n, 1 + FLOOR(5000 * POW(RAND(), 2)) AS m FROM nums WHERE n <= 100000) t;

-- 250,000 order lines; product skew toward low product ids.
INSERT INTO order_detail(order_id, product_id, quantity, unit_price, item_price)
SELECT CONCAT('O', LPAD(1 + (n % 100000), 8, '0')), CONCAT('P', LPAD(pid, 5, '0')), q, 20 + (pid % 500), q * (20 + (pid % 500))
FROM (SELECT n, 1 + FLOOR(2000 * POW(RAND(), 2)) AS pid, 1 + FLOOR(RAND() * 3) AS q FROM nums WHERE n <= 250000) t;

INSERT INTO order_request(request_id, order_id, member_id)
SELECT CONCAT('R', LPAD(n, 8, '0')), CONCAT('O', LPAD(n, 8, '0')), 'u1@example.com' FROM nums WHERE n <= 100000;

INSERT INTO order_status_history(order_id, from_status, to_status, actor, actor_role)
SELECT CONCAT('O', LPAD(1 + (n % 100000), 8, '0')), IF(n <= 100000, NULL, 'CREATED'), IF(n <= 100000, 'CREATED', 'CONFIRMED'), 'system', 'BUYER'
FROM nums WHERE n <= 200000;

INSERT INTO payment(order_id, merchant_trade_no, provider, amount, status, created_at, updated_at)
SELECT CONCAT('O', LPAD(n, 8, '0')), CONCAT('T', n), 'ecpay', 50 + (n % 2000), IF(n % 20 = 0, 'FAILED', 'SUCCEEDED'),
       NOW(3) - INTERVAL (n % 365) DAY, NOW(3)
FROM nums WHERE n <= 90000;

-- 20,000 reviews, unique per (member, product).
INSERT INTO product_review(product_id, member_id, rating, content, visibility, created_at)
SELECT CONCAT('P', LPAD(((n % 5000) * 3 + FLOOR(n / 5000) * 500) % 2000 + 1, 5, '0')), 1 + (n % 5000), 1 + (n % 5), 'sample review',
       IF(n % 10 = 0, 'HIDDEN', 'VISIBLE'), NOW() - INTERVAL (n % 365) DAY
FROM (SELECT n - 1 AS n FROM nums WHERE n <= 20000) t;

-- 10,000 cart lines, unique per (member, product).
INSERT INTO shopping_cart(member_id, product_id, quantity)
SELECT 1 + (n % 2500), CONCAT('P', LPAD(((n % 2500) * 7 + FLOOR(n / 2500) * 500) % 2000 + 1, 5, '0')), 1 + (n % 3)
FROM (SELECT n - 1 AS n FROM nums WHERE n <= 10000) t;

INSERT INTO product_image(product_id, image_url, display_order)
SELECT CONCAT('P', LPAD(1 + (n % 2000), 5, '0')), CONCAT('/uploads/products/', n, '.jpg'), FLOOR(n / 2000)
FROM (SELECT n - 1 AS n FROM nums WHERE n <= 4000) t;

INSERT INTO audit_log(actor, actor_role, action, target_type, target_id, after_state, created_at)
SELECT CONCAT('u', 1 + (n % 50), '@example.com'), 'SELLER',
       ELT(1 + n % 6, 'PRODUCT_CREATE', 'PRODUCT_UPDATE', 'PRODUCT_DELETE', 'PRODUCT_RESTOCK', 'ORDER_STATUS', 'REVIEW_HIDE'),
       IF(n % 6 = 4, 'ORDER', 'PRODUCT'), CONCAT('P', LPAD(1 + (n % 2000), 5, '0')), JSON_OBJECT('n', n),
       NOW(3) - INTERVAL FLOOR(RAND() * 365 * 24 * 60) MINUTE
FROM nums WHERE n <= 100000;

INSERT INTO coupon(code, discount_type, discount_value, min_order_amount, total_quota, per_member_limit, starts_at, expires_at, created_by)
SELECT CONCAT('SAVE', n), 'FIXED', 50, 500, 1000, 1, NOW() - INTERVAL 30 DAY, NOW() + INTERVAL 30 DAY, 'u1@example.com'
FROM nums WHERE n <= 20;

DROP TABLE nums;
SET SESSION foreign_key_checks = 1;
SET SESSION unique_checks = 1;

ANALYZE TABLE member, product, shop_order, order_detail, order_status_history, payment, product_review,
              shopping_cart, product_image, audit_log, shipping_address, coupon;
