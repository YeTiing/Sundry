CREATE TABLE ai_chat_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    role          VARCHAR(16)     NOT NULL,
    session_id    VARCHAR(64)     NULL,
    message       VARCHAR(500)    NOT NULL,
    source        VARCHAR(16)     NOT NULL COMMENT 'RULE/LLM',
    action        VARCHAR(16)     NOT NULL COMMENT 'navigate/tool/answer/clarify',
    tool_name     VARCHAR(64)     NULL,
    arguments     JSON            NULL,
    route         VARCHAR(64)     NULL,
    status        VARCHAR(20)     NOT NULL COMMENT 'OK/REJECTED/DEGRADED/ERROR',
    reject_reason VARCHAR(200)    NULL,
    latency_ms    INT             NOT NULL DEFAULT 0,
    trace_id      VARCHAR(64)     NOT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ai_chat_user_time (user_id, created_at),
    KEY idx_ai_chat_status_time (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT 'AI 助手对话审计';
