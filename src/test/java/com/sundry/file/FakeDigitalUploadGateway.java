package com.sundry.file;

import com.sundry.file.digital.DigitalUploadGateway;
import com.sundry.file.digital.StoredObject;
import com.sundry.file.digital.UploadedPart;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试替身：不连 S3，记录上传会话和对象大小。
 */
@Service
@Profile("test")
public class FakeDigitalUploadGateway implements DigitalUploadGateway {

    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    private final Map<String, Long> objectSizes = new ConcurrentHashMap<>();

    @Override
    public String createMultipart(String key, String contentType, long sizeBytes) {
        String uploadId = "fake-" + UUID.randomUUID();
        uploads.put(uploadId, new Upload(key, sizeBytes, 0));
        return uploadId;
    }

    @Override
    public List<String> presignParts(String key, String uploadId, int partCount, Duration ttl) {
        Upload upload = uploads.get(uploadId);
        if (upload != null) {
            uploads.put(uploadId, new Upload(upload.key(), upload.sizeBytes(), partCount));
        }
        List<String> urls = new ArrayList<>(partCount);
        for (int i = 1; i <= partCount; i++) {
            urls.add("http://test-minio/" + key + "?uploadId=" + uploadId + "&partNumber=" + i);
        }
        return urls;
    }

    @Override
    public List<UploadedPart> listParts(String key, String uploadId) {
        Upload upload = uploads.get(uploadId);
        if (upload == null || !upload.key().equals(key)) {
            throw new IllegalStateException("upload session not found");
        }
        List<UploadedPart> parts = new ArrayList<>(upload.partCount());
        for (int i = 1; i <= upload.partCount(); i++) {
            parts.add(new UploadedPart(i, "\"fake-etag-" + i + "\""));
        }
        return parts;
    }

    @Override
    public void completeMultipart(String key, String uploadId, List<UploadedPart> parts) {
        Upload upload = uploads.remove(uploadId);
        if (upload == null || !upload.key().equals(key)) {
            throw new IllegalStateException("upload session not found");
        }
        if (parts.size() != upload.partCount()) {
            throw new IllegalStateException("part count mismatch");
        }
        objectSizes.put(key, upload.sizeBytes());
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        uploads.remove(uploadId);
    }

    @Override
    public StoredObject stat(String key) {
        long size = objectSizes.getOrDefault(key, 0L);
        return new StoredObject(size, "\"fake-etag-" + size + "\"");
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        return "http://test-minio/download/" + key + "?ttl=" + ttl.toSeconds();
    }

    @Override
    public void deleteObject(String key) {
        objectSizes.remove(key);
    }

    public boolean exists(String key) {
        return objectSizes.containsKey(key);
    }

    public void clear() {
        uploads.clear();
        objectSizes.clear();
    }

    private record Upload(String key, long sizeBytes, int partCount) {
    }
}
