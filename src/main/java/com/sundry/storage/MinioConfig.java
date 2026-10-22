package com.sundry.storage;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * MinIO 客户端。测试环境不加载，改用内存实现。
 */
@Configuration
@Profile("!test")
public class MinioConfig {

    @Bean
    public MinioClient minioClient(StorageProperties properties) {
        if (isBlank(properties.getAccessKey()) || isBlank(properties.getSecretKey())) {
            throw new IllegalStateException("app.storage.access-key / secret-key 未配置");
        }
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
