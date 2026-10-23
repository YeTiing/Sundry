package com.sundry.file.digital;

import com.sundry.catalog.ProductMapper;
import com.sundry.cleanup.CleanupProperties;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.file.FileKind;
import com.sundry.file.FileObject;
import com.sundry.file.FileObjectMapper;
import com.sundry.storage.StorageProperties;
import com.sundry.user.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private static final String DONE_KEY = "upload:digital:done:%s";
    private static final Duration DONE_TTL = Duration.ofHours(1);
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final int MAX_PARTS = 10_000;

    private final DigitalUploadGateway gateway;
    private final StorageProperties properties;
    private final FileObjectMapper fileObjectMapper;
    private final ProductMapper productMapper;
    private final CleanupProperties cleanupProperties;
    private final StringRedisTemplate redis;

    public DigitalUploadService(DigitalUploadGateway gateway, StorageProperties properties,
                                FileObjectMapper fileObjectMapper, ProductMapper productMapper,
                                CleanupProperties cleanupProperties, StringRedisTemplate redis) {
        this.gateway = gateway;
        this.properties = properties;
        this.fileObjectMapper = fileObjectMapper;
        this.productMapper = productMapper;
        this.cleanupProperties = cleanupProperties;
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
        // 网络超时后客户端重试：直接返回上次成功的结果
        DigitalFileView cached = loadDone(request.uploadId(), request.key());
        if (cached != null) {
            return cached;
        }

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

        StoredObject stored;
        try {
            stored = gateway.stat(request.key());
        } catch (RuntimeException ex) {
            log.error("failed to stat uploaded object, key={}", request.key(), ex);
            deleteQuietly(request.key());
            redis.delete(stateKey(request.uploadId()));
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        long actualSize = stored.size();
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

        // 同一上传人、同大小、同 ETag：删掉新对象，复用旧记录；软删除记录顺便恢复
        FileObject reusable = fileObjectMapper.findReusable(
                uploaderId, FileKind.DIGITAL, actualSize, stored.etag());
        if (reusable != null) {
            deleteQuietly(request.key());
            if (reusable.getStatus() != null && reusable.getStatus() == 0) {
                fileObjectMapper.restore(reusable.getId());
            } else {
                fileObjectMapper.touch(reusable.getId());
            }
            redis.delete(stateKey(request.uploadId()));
            cacheDone(request.uploadId(), request.key(), reusable.getId());
            return new DigitalFileView(reusable.getId(), reusable.getObjectKey(),
                    reusable.getOriginalName(), reusable.getSizeBytes(), reusable.getContentType());
        }

        try {
            FileObject record = new FileObject();
            record.setBucket(properties.getPrivateBucket());
            record.setObjectKey(request.key());
            record.setOriginalName(state.originalName());
            record.setContentType(state.contentType());
            record.setSizeBytes(actualSize);
            record.setEtag(stored.etag());
            record.setKind(FileKind.DIGITAL);
            record.setUploaderId(uploaderId);
            record.setStatus(1);
            fileObjectMapper.insert(record);
            redis.delete(stateKey(request.uploadId()));
            cacheDone(request.uploadId(), request.key(), record.getId());
            return new DigitalFileView(record.getId(), request.key(), state.originalName(),
                    actualSize, state.contentType());
        } catch (RuntimeException ex) {
            // 数据库没记上就把对象删掉，避免孤儿文件
            deleteQuietly(request.key());
            redis.delete(stateKey(request.uploadId()));
            throw ex;
        }
    }

    /**
     * 后台可直接选择最近上传过的文件，不用重新上传。
     * 包含有效文件和宽限期内软删除的文件。
     */
    public List<ReusableDigitalFileView> reusable(Long uploaderId) {
        LocalDateTime cutoff = LocalDateTime.now().minus(cleanupProperties.getDigitalGrace());
        return fileObjectMapper.findReusableForUploader(uploaderId, cutoff, 20).stream()
                .map(file -> new ReusableDigitalFileView(file.getId(), file.getOriginalName(),
                        file.getSizeBytes(), file.getContentType(), file.getStatus(),
                        file.getDeletedAt(), file.getCreatedAt()))
                .toList();
    }

    /**
     * 直接复用已上传文件：软删除的恢复，有效文件刷新时间，不产生新的上传。
     */
    public DigitalFileView reuse(Long operatorId, Role operatorRole, Long fileId) {
        FileObject file = fileObjectMapper.findById(fileId);
        if (file == null
                || file.getKind() != FileKind.DIGITAL
                || !properties.getPrivateBucket().equals(file.getBucket())) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        if (!file.getUploaderId().equals(operatorId) && operatorRole != Role.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        try {
            StoredObject stored = gateway.stat(file.getObjectKey());
            if (stored.size() <= 0) {
                throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("reusable object missing, id={}, key={}", fileId, file.getObjectKey(), ex);
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }

        if (file.getStatus() != null && file.getStatus() == 0) {
            fileObjectMapper.restore(fileId);
        } else {
            fileObjectMapper.touch(fileId);
        }

        FileObject refreshed = fileObjectMapper.findById(fileId);
        if (refreshed == null) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return new DigitalFileView(refreshed.getId(), refreshed.getObjectKey(),
                refreshed.getOriginalName(), refreshed.getSizeBytes(), refreshed.getContentType());
    }

    /**
     * 软删除：保留对象和记录，7 天内重复上传可复用，到期由清理任务硬删除。
     */
    public void delete(Long operatorId, Role operatorRole, Long fileId) {
        FileObject file = fileObjectMapper.findById(fileId);
        if (file == null
                || file.getKind() != FileKind.DIGITAL
                || !properties.getPrivateBucket().equals(file.getBucket())) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        if (!file.getUploaderId().equals(operatorId) && operatorRole != Role.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (productMapper.countByFileId(fileId) > 0) {
            throw new BusinessException(ErrorCode.FILE_IN_USE);
        }
        fileObjectMapper.softDelete(fileId);
    }

    public void abort(Long uploaderId, AbortDigitalUploadRequest request) {
        UploadState state = loadState(request.uploadId());
        if (state == null || !state.uploaderId().equals(uploaderId) || !state.key().equals(request.key())) {
            throw new BusinessException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        abortQuietly(request.key(), request.uploadId());
        redis.delete(stateKey(request.uploadId()));
    }

    private DigitalFileView loadDone(String uploadId, String key) {
        String value = redis.opsForValue().get(DONE_KEY.formatted(uploadId));
        if (value == null) {
            return null;
        }
        String[] fields = value.split("\\|", 2);
        if (fields.length != 2 || !fields[0].equals(key)) {
            return null;
        }
        try {
            FileObject file = fileObjectMapper.findById(Long.valueOf(fields[1]));
            if (file == null || file.getStatus() == null || file.getStatus() != 1) {
                return null;
            }
            return new DigitalFileView(file.getId(), file.getObjectKey(), file.getOriginalName(),
                    file.getSizeBytes(), file.getContentType());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void cacheDone(String uploadId, String key, Long fileId) {
        redis.opsForValue().set(DONE_KEY.formatted(uploadId), key + "|" + fileId, DONE_TTL);
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
