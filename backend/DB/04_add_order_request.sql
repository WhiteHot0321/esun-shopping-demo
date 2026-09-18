-- Additive migration for existing databases. Run once; it does not reset order data.
CREATE TABLE IF NOT EXISTS order_request (
    request_id VARCHAR(64) PRIMARY KEY,
    order_id   VARCHAR(32) NOT NULL,
    member_id  VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
