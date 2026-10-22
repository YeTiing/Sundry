package com.sundry.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 启动时确保公开桶存在，并设置只读公开策略（商品图需要直链访问）。
 */
@Component
@Profile("!test")
public class StorageInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageInitializer.class);

    private final MinioClient client;
    private final StorageProperties properties;

    public StorageInitializer(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String bucket = properties.getPublicBucket();
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 MinIO 公开桶: {}", bucket);
            }
            String policy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Principal": {"AWS": ["*"]},
                          "Action": ["s3:GetObject"],
                          "Resource": ["arn:aws:s3:::%s/*"]
                        }
                      ]
                    }
                    """.formatted(bucket);
            client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
            log.info("MinIO 公开读策略已就绪: {}", bucket);
        } catch (Exception ex) {
            throw new IllegalStateException("MinIO 初始化失败，请检查 app.storage.* 配置和容器状态", ex);
        }
    }
}
