-- Additive migration for existing databases. member_id stores the JWT-authenticated
-- email (see OrderController.createOrder), which routinely exceeds 20 characters;
-- VARCHAR(20) silently truncated inserts with MysqlDataTruncation, surfacing as a
-- bare 500 DB_ERROR on order placement for any real email address. Widen to match
-- member.email's VARCHAR(255).
ALTER TABLE shop_order MODIFY member_id VARCHAR(255) NOT NULL;
ALTER TABLE order_request MODIFY member_id VARCHAR(255) NOT NULL;
