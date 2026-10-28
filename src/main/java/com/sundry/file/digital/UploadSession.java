package com.sundry.file.digital;

import java.time.LocalDateTime;

/**
 * 分片上传会话。Redis 丢失后，complete 仍能按 uploadId 找回 fileId。
 */
public class UploadSession {

    private String uploadId;
    private Long uploaderId;
    private String objectKey;
    private Long fileId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UploadSession() {
    }

    public UploadSession(String uploadId, Long uploaderId, String objectKey,
                         Long fileId, String status) {
        this.uploadId = uploadId;
        this.uploaderId = uploaderId;
        this.objectKey = objectKey;
        this.fileId = fileId;
        this.status = status;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public Long getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(Long uploaderId) {
        this.uploaderId = uploaderId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
