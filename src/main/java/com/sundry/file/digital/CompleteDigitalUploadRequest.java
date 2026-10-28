package com.sundry.file.digital;

import jakarta.validation.constraints.NotBlank;

/**
 * 所有分片上传完成后通知后端；ETag 由后端 ListParts 获取，前端不用回传。
 * key 可选：upload_session 存在时由服务端补全，客户端只保留 uploadId 也能恢复。
 */
public record CompleteDigitalUploadRequest(
        @NotBlank(message = "uploadId 不能为空") String uploadId,
        String key) {
}
