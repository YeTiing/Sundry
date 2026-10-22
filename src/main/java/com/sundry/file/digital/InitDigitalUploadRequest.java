package com.sundry.file.digital;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 初始化数字文件分片上传。
 */
public record InitDigitalUploadRequest(
        @NotBlank(message = "文件名不能为空")
        @Size(max = 255, message = "文件名过长")
        String fileName,

        @Size(max = 100, message = "文件类型过长")
        String contentType,

        @Min(value = 1, message = "文件大小不合法")
        long sizeBytes) {
}
