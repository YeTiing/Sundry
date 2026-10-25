CREATE TABLE product_embedding (
    product_id BIGINT UNSIGNED NOT NULL,
    model      VARCHAR(64)     NOT NULL,
    dim        INT             NOT NULL,
    vector     MEDIUMTEXT      NOT NULL COMMENT 'JSON 数组，1024 维',
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (product_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '商品向量';
