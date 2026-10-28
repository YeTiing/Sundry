-- 分片上传会话的持久映射：uploadId -> fileId。
-- Redis 只做加速，完成态丢失后仍能按 uploadId 找回已经入库的文件。
CREATE TABLE upload_session (
    upload_id   VARCHAR(255)    NOT NULL,
    uploader_id BIGINT UNSIGNED NOT NULL,
    object_key  VARCHAR(255)    NOT NULL,
    file_id     BIGINT UNSIGNED NULL,
    status      VARCHAR(16)     NOT NULL COMMENT 'PENDING/COMPLETED',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (upload_id),
    KEY idx_upload_session_status (status, created_at),
    KEY idx_upload_session_file (file_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '分片上传会话';
