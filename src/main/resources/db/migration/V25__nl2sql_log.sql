CREATE TABLE nl2sql_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    role          VARCHAR(16)     NOT NULL,
    session_id    VARCHAR(64)     NULL,
    question      VARCHAR(500)    NOT NULL,
    plan_json     JSON            NULL,
    sql_text      TEXT            NULL,
    row_count     INT             NOT NULL DEFAULT 0,
    latency_ms    INT             NOT NULL DEFAULT 0,
    status        VARCHAR(20)     NOT NULL,
    reject_reason VARCHAR(200)    NULL,
    trace_id      VARCHAR(64)     NOT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_nl2sql_user_time (user_id, created_at),
    KEY idx_nl2sql_status_time (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT 'NL2SQL 查询审计';
