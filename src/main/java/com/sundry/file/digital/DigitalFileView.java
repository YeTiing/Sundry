package com.sundry.file.digital;

/**
 * 数字文件上传完成后的返回。私有桶不给 URL，购买后再签发下载地址。
 */
public record DigitalFileView(Long id, String key, String originalName, long sizeBytes, String contentType) {
}
