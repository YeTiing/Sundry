package com.sundry.file.digital;

import java.time.Duration;
import java.util.List;

/**
 * 分片上传网关。生产用 S3 兼容实现，测试用内存替身。
 */
public interface DigitalUploadGateway {

    String createMultipart(String key, String contentType, long sizeBytes);

    List<String> presignParts(String key, String uploadId, int partCount, Duration ttl);

    List<UploadedPart> listParts(String key, String uploadId);

    void completeMultipart(String key, String uploadId, List<UploadedPart> parts);

    void abortMultipart(String key, String uploadId);

    StoredObject stat(String key);

    String presignGet(String key, Duration ttl);

    void deleteObject(String key);
}
