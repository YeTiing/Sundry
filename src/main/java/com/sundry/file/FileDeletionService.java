package com.sundry.file;

import com.sundry.catalog.ProductMapper;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.storage.StorageProperties;
import com.sundry.user.Role;
import org.springframework.stereotype.Service;

/**
 * 文件软删除：图片和数字文件共用。
 * 只要还有商品引用（包括已软删除的商品），就不允许删，避免破坏历史商品/下载权益。
 */
@Service
public class FileDeletionService {

    private final FileObjectMapper fileObjectMapper;
    private final ProductMapper productMapper;
    private final StorageProperties properties;

    public FileDeletionService(FileObjectMapper fileObjectMapper, ProductMapper productMapper,
                               StorageProperties properties) {
        this.fileObjectMapper = fileObjectMapper;
        this.productMapper = productMapper;
        this.properties = properties;
    }

    public void delete(Long operatorId, Role operatorRole, Long fileId) {
        FileObject file = fileObjectMapper.findById(fileId);
        if (file == null || !validBucket(file)) {
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

    private boolean validBucket(FileObject file) {
        if (file.getKind() == FileKind.IMAGE) {
            return properties.getPublicBucket().equals(file.getBucket());
        }
        if (file.getKind() == FileKind.DIGITAL) {
            return properties.getPrivateBucket().equals(file.getBucket());
        }
        return false;
    }
}
