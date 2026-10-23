package com.sundry.file;

import com.sundry.cleanup.CleanupProperties;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.storage.ObjectStorage;
import com.sundry.storage.StorageProperties;
import com.sundry.user.Role;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封面图复用：新建商品时可以直接选最近上传过的图片，不必重新上传。
 * 只允许同一上传人或超级管理员使用。
 */
@Service
public class ImageReuseService {

    private final FileObjectMapper fileObjectMapper;
    private final ObjectStorage storage;
    private final StorageProperties properties;
    private final CleanupProperties cleanupProperties;

    public ImageReuseService(FileObjectMapper fileObjectMapper, ObjectStorage storage,
                             StorageProperties properties, CleanupProperties cleanupProperties) {
        this.fileObjectMapper = fileObjectMapper;
        this.storage = storage;
        this.properties = properties;
        this.cleanupProperties = cleanupProperties;
    }

    public List<ReusableImageView> reusable(Long uploaderId) {
        LocalDateTime cutoff = LocalDateTime.now().minus(cleanupProperties.getImageGrace());
        return fileObjectMapper.findReusableImagesForUploader(uploaderId, cutoff, 30).stream()
                .map(this::toView)
                .toList();
    }

    public ReusableImageView reuse(Long operatorId, Role operatorRole, Long fileId) {
        FileObject file = requireImage(fileId);
        if (!file.getUploaderId().equals(operatorId) && operatorRole != Role.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (file.getStatus() != null && file.getStatus() == 0) {
            fileObjectMapper.restore(fileId);
        } else {
            fileObjectMapper.touch(fileId);
        }
        return toView(fileObjectMapper.findById(fileId));
    }

    private FileObject requireImage(Long fileId) {
        FileObject file = fileObjectMapper.findById(fileId);
        if (file == null
                || file.getKind() != FileKind.IMAGE
                || !properties.getPublicBucket().equals(file.getBucket())) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        return file;
    }

    private ReusableImageView toView(FileObject file) {
        return new ReusableImageView(file.getId(), file.getOriginalName(), file.getSizeBytes(),
                file.getStatus(), file.getDeletedAt(),
                storage.publicUrl(file.getBucket(), file.getObjectKey()));
    }
}
