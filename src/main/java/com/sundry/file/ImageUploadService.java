package com.sundry.file;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.storage.ObjectStorage;
import com.sundry.storage.StorageException;
import com.sundry.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 商品图上传：校验真实类型、生成随机 key、写公开桶、记录数据库。
 * 文件名只用于展示，不参与对象 key。
 */
@Service
public class ImageUploadService {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final ObjectStorage storage;
    private final StorageProperties properties;
    private final FileObjectMapper fileObjectMapper;

    public ImageUploadService(ObjectStorage storage, StorageProperties properties,
                              FileObjectMapper fileObjectMapper) {
        this.storage = storage;
        this.properties = properties;
        this.fileObjectMapper = fileObjectMapper;
    }

    public FileView upload(MultipartFile file, Long uploaderId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        if (file.getSize() > properties.getMaxImageBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        // 以文件头为准，不信任客户端传的 Content-Type
        String contentType = detectContentType(data);
        if (contentType == null) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        String extension = extensionOf(contentType);
        String key = "products/" + LocalDate.now(ZONE).format(DATE_PATH) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String bucket = properties.getPublicBucket();
        String originalName = sanitizeName(file.getOriginalFilename());

        try {
            storage.put(bucket, key, data, contentType);
        } catch (StorageException ex) {
            log.error("image upload failed, key={}", key, ex);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        try {
            FileObject record = new FileObject();
            record.setBucket(bucket);
            record.setObjectKey(key);
            record.setOriginalName(originalName);
            record.setContentType(contentType);
            record.setSizeBytes((long) data.length);
            record.setKind(FileKind.IMAGE);
            record.setUploaderId(uploaderId);
            record.setStatus(1);
            fileObjectMapper.insert(record);
            return new FileView(record.getId(), key, storage.publicUrl(bucket, key),
                    originalName, data.length, contentType);
        } catch (RuntimeException ex) {
            // 数据库写失败就删掉刚上传的对象，避免孤儿文件
            try {
                storage.delete(bucket, key);
            } catch (RuntimeException cleanup) {
                log.warn("failed to clean up object after db error, key={}", key, cleanup);
            }
            throw ex;
        }
    }

    private String detectContentType(byte[] data) {
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (data.length >= 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return "image/png";
        }
        if (data.length >= 12
                && asciiEquals(data, 0, "RIFF") && asciiEquals(data, 8, "WEBP")) {
            return "image/webp";
        }
        return null;
    }

    private boolean asciiEquals(byte[] data, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            if (data[offset + i] != value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        };
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "image";
        }
        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
