CREATE TABLE seckill_activity (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id     BIGINT UNSIGNED NOT NULL,
    seckill_price  DECIMAL(10, 2)  NOT NULL,
    stock          INT             NOT NULL COMMENT '活动配额，实物同时受商品库存约束',
    sold_count     INT             NOT NULL DEFAULT 0,
    per_user_limit INT             NOT NULL DEFAULT 1 COMMENT '每人限购',
    start_at       DATETIME(3)     NOT NULL,
    end_at         DATETIME(3)     NOT NULL,
    status         VARCHAR(20)     NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/ACTIVE/ENDED/CANCELLED',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_seckill_product_time (product_id, start_at, end_at),
    KEY idx_seckill_status_time (status, start_at),
    CONSTRAINT fk_seckill_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '秒杀活动';
