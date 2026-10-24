CREATE TABLE user_behavior (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    event_type  VARCHAR(20)     NOT NULL COMMENT 'VIEW/CART/ORDER/SEARCH',
    product_id  BIGINT UNSIGNED NOT NULL,
    category_id BIGINT UNSIGNED NOT NULL,
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_behavior_user_time (user_id, created_at),
    KEY idx_behavior_product (product_id),
    KEY idx_behavior_category (category_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '用户行为';
