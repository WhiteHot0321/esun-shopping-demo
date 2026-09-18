-- Additive migration for existing databases. No ECPay credentials are stored in the database.
CREATE TABLE IF NOT EXISTS payment_transaction (
    order_id                VARCHAR(30) PRIMARY KEY,
    merchant_trade_no       VARCHAR(20) NOT NULL UNIQUE,
    provider_transaction_id VARCHAR(64) NULL,
    created_at              DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_transaction_order FOREIGN KEY (order_id) REFERENCES shop_order(order_id)
);
