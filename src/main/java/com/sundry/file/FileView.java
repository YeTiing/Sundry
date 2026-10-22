package com.sundry.file;

/**
 * 上传成功后的返回，url 是可直接放进 img src 的地址。
 */
public record FileView(Long id, String key, String url, String originalName, long size, String contentType) {
}
