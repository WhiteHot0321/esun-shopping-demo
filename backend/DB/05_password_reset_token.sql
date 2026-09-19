USE esun_shop;

DROP TABLE IF EXISTS password_reset_token;

CREATE TABLE password_reset_token (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    member_id  BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at DATETIME NOT NULL,
    used_at    DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_password_reset_token_member FOREIGN KEY (member_id) REFERENCES member (id),
    INDEX idx_password_reset_token_member (member_id)
);
