package com.sundry.file.digital;

import com.sundry.storage.StorageProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 S3 协议的分片上传：后端只创建/完成/签名，分片字节由浏览器直接写到私有桶。
 */
@Service
@Profile("!test")
public class S3DigitalUploadGateway implements DigitalUploadGateway {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public S3DigitalUploadGateway(S3Client s3Client, S3Presigner presigner, StorageProperties properties) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public String createMultipart(String key, String contentType, long sizeBytes) {
        CreateMultipartUploadResponse response = s3Client.createMultipartUpload(builder -> builder
                .bucket(properties.getPrivateBucket())
                .key(key)
                // 强制 octet-stream，下载时不会在浏览器里被当成网页执行
                .contentType(contentType));
        return response.uploadId();
    }

    @Override
    public List<String> presignParts(String key, String uploadId, int partCount, Duration ttl) {
        List<String> urls = new ArrayList<>(partCount);
        for (int partNumber = 1; partNumber <= partCount; partNumber++) {
            UploadPartRequest request = UploadPartRequest.builder()
                    .bucket(properties.getPrivateBucket())
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();
            PresignedUploadPartRequest presigned = presigner.presignUploadPart(builder ->
                    builder.signatureDuration(ttl).uploadPartRequest(request));
            urls.add(presigned.url().toString());
        }
        return urls;
    }

    @Override
    public List<UploadedPart> listParts(String key, String uploadId) {
        List<UploadedPart> parts = new ArrayList<>();
        Integer marker = null;
        while (true) {
            final Integer currentMarker = marker;
            ListPartsResponse response = s3Client.listParts(builder -> {
                builder.bucket(properties.getPrivateBucket()).key(key).uploadId(uploadId);
                if (currentMarker != null) {
                    builder.partNumberMarker(currentMarker);
                }
            });
            response.parts().forEach(part -> parts.add(new UploadedPart(part.partNumber(), part.eTag())));
            if (!Boolean.TRUE.equals(response.isTruncated())) {
                break;
            }
            marker = response.nextPartNumberMarker();
        }
        return parts;
    }

    @Override
    public void completeMultipart(String key, String uploadId, List<UploadedPart> parts) {
        CompletedMultipartUpload completed = CompletedMultipartUpload.builder()
                .parts(parts.stream()
                        .map(part -> CompletedPart.builder()
                                .partNumber(part.partNumber())
                                .eTag(part.etag())
                                .build())
                        .toList())
                .build();
        s3Client.completeMultipartUpload(builder -> builder
                .bucket(properties.getPrivateBucket())
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(completed));
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        s3Client.abortMultipartUpload(builder -> builder
                .bucket(properties.getPrivateBucket())
                .key(key)
                .uploadId(uploadId));
    }

    @Override
    public long objectSize(String key) {
        return s3Client.headObject(builder -> builder
                .bucket(properties.getPrivateBucket())
                .key(key)).contentLength();
    }

    @Override
    public void deleteObject(String key) {
        s3Client.deleteObject(builder -> builder
                .bucket(properties.getPrivateBucket())
                .key(key));
    }
}
