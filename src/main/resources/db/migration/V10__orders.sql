CREATE TABLE orders (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no         VARCHAR(32)     NOT NULL COMMENT '订单号',
    request_id       VARCHAR(64)     NULL COMMENT '下单幂等键',
    user_id          BIGINT UNSIGNED NOT NULL,
    status           VARCHAR(20)     NOT NULL COMMENT 'PENDING_PAYMENT/PAID/PACKING/SHIPPED/COMPLETED/CANCELLED',
    total_amount     DECIMAL(10, 2)  NOT NULL,
    has_physical     TINYINT         NOT NULL DEFAULT 0 COMMENT '1=含实物，需要物流',
    receiver_name    VARCHAR(50)     NULL,
    receiver_phone   VARCHAR(20)     NULL,
    receiver_address VARCHAR(300)    NULL,
    tracking_no      VARCHAR(64)     NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    paid_at          DATETIME(3)     NULL,
    packed_at        DATETIME(3)     NULL,
    shipped_at       DATETIME(3)     NULL,
    completed_at     DATETIME(3)     NULL,
    cancelled_at     DATETIME(3)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_no (order_no),
    UNIQUE KEY uk_orders_user_request (user_id, request_id),
    KEY idx_orders_user (user_id, id),
    KEY idx_orders_status (status, id),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '订单';

CREATE TABLE order_item (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id        BIGINT UNSIGNED NOT NULL,
    product_id      BIGINT UNSIGNED NOT NULL,
    product_name    VARCHAR(120)    NOT NULL COMMENT '下单时快照',
    product_type    VARCHAR(20)     NOT NULL,
    price           DECIMAL(10, 2)  NOT NULL COMMENT '下单时单价',
    quantity        INT             NOT NULL,
    cover_url       VARCHAR(500)    NULL COMMENT '下单时封面快照',
    digital_file_id BIGINT UNSIGNED NULL COMMENT '数字商品文件快照',
    PRIMARY KEY (id),
    KEY idx_order_item_order (order_id),
    KEY idx_order_item_product (product_id),
    KEY idx_order_item_digital (digital_file_id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '订单项';
