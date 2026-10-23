ALTER TABLE product
    ADD COLUMN stock INT NOT NULL DEFAULT 0 COMMENT '实物库存；数字商品忽略' AFTER price;

CREATE TABLE cart_item (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id    BIGINT UNSIGNED NOT NULL,
    product_id BIGINT UNSIGNED NOT NULL,
    quantity   INT             NOT NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_cart_user_product (user_id, product_id),
    KEY idx_cart_user (user_id),
    CONSTRAINT fk_cart_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '购物车';

CREATE TABLE user_address (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL,
    receiver_name  VARCHAR(50)     NOT NULL COMMENT '收货人',
    receiver_phone VARCHAR(20)     NOT NULL COMMENT '收货电话',
    province       VARCHAR(50)     NOT NULL,
    city           VARCHAR(50)     NOT NULL,
    district       VARCHAR(50)     NOT NULL,
    detail         VARCHAR(200)    NOT NULL COMMENT '详细地址',
    is_default     TINYINT         NOT NULL DEFAULT 0 COMMENT '1=默认',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_address_user (user_id),
    CONSTRAINT fk_address_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '收货地址';
