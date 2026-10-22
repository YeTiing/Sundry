package com.sundry.file.digital;

import jakarta.validation.constraints.NotBlank;

/**
 * 所有分片上传完成后通知后端；ETag 由后端 ListParts 获取，前端不用回传。
 */
public record CompleteDigitalUploadRequest(
        @NotBlank(message = "uploadId 不能为空") String uploadId,
        @NotBlank(message = "key 不能为空") String key) {
}
