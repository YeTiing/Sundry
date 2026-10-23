ALTER TABLE file_object
    ADD COLUMN etag VARCHAR(128) NULL COMMENT '对象 ETag，用于去重' AFTER size_bytes,
    ADD COLUMN deleted_at DATETIME(3) NULL COMMENT '软删除时间' AFTER status,
    ADD KEY idx_file_object_dedup (uploader_id, kind, size_bytes, etag),
    ADD KEY idx_file_object_cleanup (status, deleted_at, created_at);
