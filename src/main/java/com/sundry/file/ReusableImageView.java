package com.sundry.file;

import java.time.LocalDateTime;

/**
 * 封面图复用列表项。status=0 表示软删除，仍在宽限期内可恢复。
 */
public record ReusableImageView(Long id, String originalName, long sizeBytes,
                                Integer status, LocalDateTime deletedAt, String url) {
}
