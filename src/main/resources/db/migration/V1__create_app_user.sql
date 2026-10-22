CREATE TABLE app_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    phone         VARCHAR(20)     NOT NULL COMMENT '手机号',
    password_hash VARCHAR(100)    NOT NULL COMMENT 'BCrypt 哈希',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_phone (phone)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '用户';
