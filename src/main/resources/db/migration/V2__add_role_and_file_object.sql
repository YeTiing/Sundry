ALTER TABLE app_user
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT 'SUPER_ADMIN/ADMIN/USER' AFTER status;

CREATE TABLE file_object (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    bucket        VARCHAR(64)     NOT NULL COMMENT '对象存储桶',
    object_key    VARCHAR(255)    NOT NULL COMMENT '对象 key，不含桶名',
    original_name VARCHAR(255)    NOT NULL COMMENT '上传时的文件名，仅展示用',
    content_type  VARCHAR(100)    NOT NULL COMMENT '校验后的 MIME',
    size_bytes    BIGINT UNSIGNED NOT NULL,
    kind          VARCHAR(20)     NOT NULL COMMENT 'IMAGE/DIGITAL',
    uploader_id   BIGINT UNSIGNED NOT NULL,
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '1=正常 0=已删除',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_file_object_bucket_key (bucket, object_key),
    KEY idx_file_object_uploader (uploader_id, created_at),
    CONSTRAINT fk_file_object_uploader FOREIGN KEY (uploader_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT '上传文件';
