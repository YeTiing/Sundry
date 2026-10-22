package com.sundry.file.digital;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.file.FileKind;
import com.sundry.file.FileObject;
import com.sundry.file.FileObjectMapper;
import com.sundry.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 数字文件分片上传编排。Redis 里保存上传会话，防止别人拿 uploadId 伪造完成请求。
 */
@Service
public class DigitalUploadService {

    private static final Logger log = LoggerFactory.getLogger(DigitalUploadService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final String STATE_KEY = "upload:digital:%s";
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final int MAX_PARTS = 10_000;

    private final DigitalUploadGateway gateway;
    private final StorageProperties properties;
    private final FileObjectMapper fileObjectMapper;
    private final StringRedisTemplate redis;

    public DigitalUploadService(DigitalUploadGateway gateway, StorageProperties properties,
                                FileObjectMapper fileObjectMapper, StringRedisTemplate redis) {
        this.gateway = gateway;
        this.properties = properties;
        this.fileObjectMapper = fileObjectMapper;
        this.redis = redis;
    }

    public DigitalUploadView init(Long uploaderId, InitDigitalUploadRequest request) {
        long size = request.sizeBytes();
        if (size > properties.getMaxDigitalBytes()) {
            throw new BusinessException(ErrorCode.DIGITAL_FILE_TOO_LARGE);
        }

        long partSize = properties.getPartSizeBytes();
        int partCount = (int) ((size + partSize - 1) / partSize);
        if (partCount < 1 || partCount > MAX_PARTS) {
            throw new BusinessException(ErrorCode.DIGITAL_FILE_TOO_LARGE);
        }

        String originalName = sanitizeName(request.fileName());
        String contentType = isBlank(request.contentType()) ? OCTET_STREAM : request.contentType().trim();
        String key = "digital/" + LocalDate.now(ZONE).format(DATE_PATH) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extensionOf(originalName);

        String uploadId;
        List<String> urls;
        try {
            uploadId = gateway.createMultipart(key, OCTET_STREAM, size);
            urls = gateway.presignParts(key, uploadId, partCount, properties.getUploadUrlTtl());
        } catch (RuntimeException ex) {
            log.error("failed to init digital upload, key={}", key, ex);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        saveState(uploadId, new UploadState(uploaderId, key, size, partCount, contentType, originalName));
        List<PresignedPart> parts = new java.util.ArrayList<>(partCount);
        for (int i = 0; i < urls.size(); i++) {
            parts.add(new PresignedPart(i + 1, urls.get(i)));
        }
        return new DigitalUploadView(uploadId, key, partSize, partCount, parts);
    }

    public DigitalFileView complete(Long uploaderId, CompleteDigitalUploadRequest request) {
        UploadState state = loadState(request.uploadId());
        if (state == null || !state.uploaderId().equals(uploaderId) || !state.key().equals(request.key())) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }

        List<UploadedPart> parts;
        try {
            parts = gateway.listParts(request.key(), request.uploadId()).stream()
                    .sorted(Comparator.comparingInt(UploadedPart::partNumber))
                    .toList();
        } catch (RuntimeException ex) {
            log.error("failed to list uploaded parts, key={}", request.key(), ex);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        if (parts.size() != state.partCount()) {
            throw new BusinessException(ErrorCode.UPLOAD_PART_INVALID);
        }

        try {
            gateway.completeMultipart(request.key(), request.uploadId(), parts);
        } catch (RuntimeException ex) {
            log.error("failed to complete multipart upload, key={}", request.key(), ex);
            abortQuietly(request.key(), request.uploadId());
            redis.delete(stateKey(request.uploadId()));
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        long actualSize;
        try {
            actualSize = gateway.objectSize(request.key());
        } catch (RuntimeException ex) {
            log.error("failed to stat uploaded object, key={}", request.key(), ex);
            deleteQuietly(request.key());
            redis.delete(stateKey(request.uploadId()));
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        if (actualSize <= 0 || actualSize > properties.getMaxDigitalBytes()) {
            deleteQuietly(request.key());
            redis.delete(stateKey(request.uploadId()));
            throw new BusinessException(ErrorCode.DIGITAL_FILE_TOO_LARGE);
        }
        if (actualSize != state.sizeBytes()) {
            deleteQuietly(request.key());
            redis.delete(stateKey(request.uploadId()));
            throw new BusinessException(ErrorCode.UPLOAD_PART_INVALID);
        }

        try {
            FileObject record = new FileObject();
            record.setBucket(properties.getPrivateBucket());
            record.setObjectKey(request.key());
            record.setOriginalName(state.originalName());
            record.setContentType(state.contentType());
            record.setSizeBytes(actualSize);
            record.setKind(FileKind.DIGITAL);
            record.setUploaderId(uploaderId);
            record.setStatus(1);
            fileObjectMapper.insert(record);
            redis.delete(stateKey(request.uploadId()));
            return new DigitalFileView(record.getId(), request.key(), state.originalName(),
                    actualSize, state.contentType());
        } catch (RuntimeException ex) {
            // 数据库没记上就把对象删掉，避免孤儿文件
            deleteQuietly(request.key());
            redis.delete(stateKey(request.uploadId()));
            throw ex;
        }
    }

    public void abort(Long uploaderId, AbortDigitalUploadRequest request) {
        UploadState state = loadState(request.uploadId());
        if (state == null || !state.uploaderId().equals(uploaderId) || !state.key().equals(request.key())) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        abortQuietly(request.key(), request.uploadId());
        redis.delete(stateKey(request.uploadId()));
    }

    private void saveState(String uploadId, UploadState state) {
        String encodedName = Base64.getUrlEncoder().encodeToString(
                state.originalName().getBytes(StandardCharsets.UTF_8));
        String value = state.uploaderId() + "|" + state.key() + "|" + state.sizeBytes() + "|"
                + state.partCount() + "|" + state.contentType() + "|" + encodedName;
        Duration ttl = properties.getUploadUrlTtl().plusHours(1);
        redis.opsForValue().set(stateKey(uploadId), value, ttl);
    }

    private UploadState loadState(String uploadId) {
        String value = redis.opsForValue().get(stateKey(uploadId));
        if (value == null) {
            return null;
        }
        String[] fields = value.split("\\|", 6);
        if (fields.length != 6) {
            return null;
        }
        try {
            String name = new String(Base64.getUrlDecoder().decode(fields[5]), StandardCharsets.UTF_8);
            return new UploadState(Long.valueOf(fields[0]), fields[1], Long.parseLong(fields[2]),
                    Integer.parseInt(fields[3]), fields[4], name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String stateKey(String uploadId) {
        return STATE_KEY.formatted(uploadId);
    }

    private String sanitizeName(String name) {
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[\\p{Cntrl}]", "_").trim();
        if (cleaned.isEmpty()) {
            cleaned = "file";
        }
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "bin";
        }
        String ext = name.substring(dot + 1).toLowerCase();
        return ext.matches("[a-z0-9]{1,10}") ? ext : "bin";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void abortQuietly(String key, String uploadId) {
        try {
            gateway.abortMultipart(key, uploadId);
        } catch (RuntimeException ex) {
            log.warn("failed to abort multipart upload, key={}", key, ex);
        }
    }

    private void deleteQuietly(String key) {
        try {
            gateway.deleteObject(key);
        } catch (RuntimeException ex) {
            log.warn("failed to delete object, key={}", key, ex);
        }
    }

    private record UploadState(Long uploaderId, String key, long sizeBytes, int partCount,
                               String contentType, String originalName) {
    }
}
