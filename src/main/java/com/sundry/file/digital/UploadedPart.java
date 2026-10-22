package com.sundry.file.digital;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 前端上传完一个分片后回传的 ETag。
 */
public record UploadedPart(
        @Min(value = 1, message = "分片编号不合法") int partNumber,
        @NotBlank(message = "分片 ETag 不能为空") String etag) {
}
