CREATE TABLE ai_feed_log (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id         BIGINT UNSIGNED NOT NULL,
    trace_id        VARCHAR(64)     NOT NULL,
    model           VARCHAR(64)     NULL,
    prompt_version  VARCHAR(32)     NULL,
    candidate_count INT             NOT NULL DEFAULT 0,
    returned_count  INT             NOT NULL DEFAULT 0,
    latency_ms      INT             NOT NULL DEFAULT 0,
    status          VARCHAR(20)     NOT NULL COMMENT 'SUCCESS/FALLBACK/STALE/ERROR',
    fallback_reason VARCHAR(200)    NULL,
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_feed_log_user_time (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT 'AI 推流审计';
