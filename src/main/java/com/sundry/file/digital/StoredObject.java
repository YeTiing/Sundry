package com.sundry.file.digital;

/**
 * 上传完成后从对象存储读到的元信息。ETag 用于去重。
 */
public record StoredObject(long size, String etag) {
}
