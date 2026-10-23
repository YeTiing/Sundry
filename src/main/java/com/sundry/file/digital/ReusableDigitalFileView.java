package com.sundry.file.digital;

import java.time.LocalDateTime;

/**
 * 后台“选择已上传文件”的列表项。status=0 表示软删除，仍在宽限期内可恢复。
 */
public record ReusableDigitalFileView(Long id, String originalName, long sizeBytes,
                                      String contentType, Integer status,
                                      LocalDateTime deletedAt, LocalDateTime createdAt) {
}
