USE esun_shop;

-- Repeatable migration for payment integration (Phase 3.2 #13).
-- One row per payment attempt for an order. The order's pay_status is only ever advanced by a verified
-- provider callback (never by the client); this table is the ledger that proves why.
--
-- status: INITIATED -> SUCCEEDED | FAILED, and any late/duplicate charge is parked as REFUND_REQUIRED
-- (money was taken but the order cannot use it: cancelled, already paid, or the attempt was already closed).
--
-- active_order_id is NULL for closed attempts and equals order_id for INITIATED/SUCCEEDED ones; the UNIQUE index on it
-- is a partial unique constraint ("at most one live attempt per order") that MySQL cannot express directly, so two
-- concurrent "pay" clicks can never open two live attempts.
CREATE TABLE IF NOT EXISTS payment (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id          VARCHAR(30)   NOT NULL,
    merchant_trade_no VARCHAR(40)   NOT NULL,
    provider          VARCHAR(20)   NOT NULL,
    amount            DECIMAL(12,2) NOT NULL CHECK (amount > 0),
    status            VARCHAR(20)   NOT NULL DEFAULT 'INITIATED',
    provider_ref      VARCHAR(64)   NULL,
    failure_reason    VARCHAR(100)  NULL,
    created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    paid_at           DATETIME(3)   NULL,
    active_order_id   VARCHAR(30) GENERATED ALWAYS AS
        (CASE WHEN status IN ('INITIATED', 'SUCCEEDED') THEN order_id ELSE NULL END) STORED,
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES shop_order(order_id),
    CONSTRAINT uq_payment_trade_no UNIQUE (merchant_trade_no),
    CONSTRAINT uq_payment_active_order UNIQUE (active_order_id),
    INDEX idx_payment_order (order_id, id)
);
