package com.sundry.order.dto;

/**
 * 短时效下载地址；前端直接用。
 */
public record DownloadView(String url, long expiresInSeconds) {
}
