package com.sundry.file.digital;

import jakarta.validation.constraints.NotBlank;

/**
 * 取消分片上传。
 */
public record AbortDigitalUploadRequest(
        @NotBlank(message = "uploadId 不能为空") String uploadId,
        @NotBlank(message = "key 不能为空") String key) {
}
