USE esun_shop;

DROP TABLE IF EXISTS member;

CREATE TABLE member (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100) NULL,
    phone         VARCHAR(30) NULL,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
