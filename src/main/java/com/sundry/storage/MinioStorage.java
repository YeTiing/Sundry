package com.sundry.storage;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * MinIO 实现。公开桶里的对象按 publicBaseUrl 拼直链。
 */
@Service
@Profile("!test")
public class MinioStorage implements ObjectStorage {

    private final MinioClient client;
    private final StorageProperties properties;

    public MinioStorage(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void put(String bucket, String key, byte[] data, String contentType) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(in, (long) data.length, -1L)
                    .contentType(contentType)
                    .build());
        } catch (Exception ex) {
            throw new StorageException("上传到 MinIO 失败: " + key, ex);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception ex) {
            throw new StorageException("删除 MinIO 对象失败: " + key, ex);
        }
    }

    @Override
    public String publicUrl(String bucket, String key) {
        return properties.getPublicBaseUrl() + "/" + bucket + "/" + key;
    }
}
