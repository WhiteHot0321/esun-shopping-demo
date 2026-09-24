USE esun_shop;

-- Repeatable migration: append-only operation audit log (Phase 3.1 #12).
CREATE TABLE IF NOT EXISTS audit_log (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor        VARCHAR(255) NOT NULL,
    actor_role   VARCHAR(20)  NOT NULL,
    action       VARCHAR(40)  NOT NULL,
    target_type  VARCHAR(30)  NOT NULL,
    target_id    VARCHAR(100) NOT NULL,
    before_state JSON NULL,
    after_state  JSON NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_audit_log_created (created_at, id),
    INDEX idx_audit_log_actor (actor, id),
    INDEX idx_audit_log_target (target_type, target_id, id),
    INDEX idx_audit_log_action (action, id)
);
