package com.sundry.storage;

/**
 * 文件存储抽象。业务层只依赖这个接口，MinIO / 云 OSS 只换实现。
 */
public interface ObjectStorage {

    void put(String bucket, String key, byte[] data, String contentType);

    void delete(String bucket, String key);

    String publicUrl(String bucket, String key);
}
