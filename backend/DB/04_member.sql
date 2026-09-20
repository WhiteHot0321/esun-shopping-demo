USE esun_shop;

DROP TABLE IF EXISTS member;

CREATE TABLE member (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100) NULL,
    phone         VARCHAR(30) NULL,
    role          VARCHAR(20) NOT NULL DEFAULT 'BUYER',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_member_role CHECK (role IN ('BUYER', 'SELLER', 'ADMIN'))
);
