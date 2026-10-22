package com.sundry.file.digital;

/**
 * 一个分片的预签名 PUT 地址。
 */
public record PresignedPart(int partNumber, String url) {
}
