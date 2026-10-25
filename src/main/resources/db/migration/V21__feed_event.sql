CREATE TABLE feed_event (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    trace_id      VARCHAR(64)     NOT NULL COMMENT '一次列表渲染',
    user_id       BIGINT UNSIGNED NOT NULL,
    event_type    VARCHAR(16)     NOT NULL COMMENT 'EXPOSURE/CLICK',
    algorithm     VARCHAR(16)     NOT NULL COMMENT 'AI/RULE',
    build_id      VARCHAR(64)     NULL COMMENT 'AI 快照 buildId',
    product_id    BIGINT UNSIGNED NOT NULL,
    category_id   BIGINT UNSIGNED NOT NULL,
    item_position INT             NOT NULL COMMENT '列表位置，从 1 开始',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_feed_event (trace_id, user_id, event_type, product_id, item_position),
    KEY idx_feed_event_user_time (user_id, created_at),
    KEY idx_feed_event_type_time (event_type, created_at),
    KEY idx_feed_event_product_time (product_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT 'Feed 曝光与点击';
