package com.sundry.file.digital;

import java.util.List;

/**
 * 初始化分片上传后的返回。前端按 partSize 切片，逐片 PUT 到对应 URL。
 */
public record DigitalUploadView(String uploadId, String key, long partSize, int partCount,
                                List<PresignedPart> parts) {
}
