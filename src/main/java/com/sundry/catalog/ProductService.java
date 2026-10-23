package com.sundry.catalog;

import com.sundry.catalog.dto.CategoryView;
import com.sundry.catalog.dto.ProductView;
import com.sundry.catalog.dto.SaveProductRequest;
import com.sundry.common.api.PageResponse;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.file.FileKind;
import com.sundry.file.FileObject;
import com.sundry.file.FileObjectMapper;
import com.sundry.storage.ObjectStorage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.util.List;

/**
 * 商品读写。前台只读已上架；管理端可以读写全部状态。
 */
@Service
public class ProductService {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final FileObjectMapper fileObjectMapper;
    private final ObjectStorage storage;

    public ProductService(ProductMapper productMapper, CategoryMapper categoryMapper,
                          FileObjectMapper fileObjectMapper, ObjectStorage storage) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.fileObjectMapper = fileObjectMapper;
        this.storage = storage;
    }

    public List<CategoryView> categories() {
        return categoryMapper.findActive().stream().map(CategoryView::from).toList();
    }

    public PageResponse<ProductView> findPublicPage(Long categoryId, int page, int size) {
        long offset = (long) (page - 1) * size;
        List<ProductView> items = productMapper.findPagePublic(categoryId, offset, size)
                .stream().map(this::toView).toList();
        return new PageResponse<>(items, productMapper.countPublic(categoryId), page, size);
    }

    public PageResponse<ProductView> findAdminPage(ProductStatus status, int page, int size) {
        long offset = (long) (page - 1) * size;
        List<ProductView> items = productMapper.findPageAdmin(status, offset, size)
                .stream().map(this::toView).toList();
        return new PageResponse<>(items, productMapper.countAdmin(status), page, size);
    }

    public ProductView findPublic(Long id) {
        Product product = productMapper.findPublicById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return toView(product);
    }

    public ProductView findAdmin(Long id) {
        Product product = productMapper.findById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return toView(product);
    }

    /**
     * 创建商品。Idempotency-Key 相同且该商品已创建时，直接返回首次结果；
     * 数据库唯一索引兜住并发重试。
     */
    public ProductView create(SaveProductRequest request, String requestId) {
        String idempotencyKey = normalizeRequestId(requestId);
        if (idempotencyKey != null) {
            Product existing = productMapper.findByRequestId(idempotencyKey);
            if (existing != null) {
                return toView(existing);
            }
        }

        requireCategory(request.categoryId());
        requireCover(request.coverFileId());
        requireDigitalFile(request.type(), request.digitalFileId());

        Product product = new Product();
        product.setRequestId(idempotencyKey);
        apply(product, request);
        product.setStatus(request.status() == null ? ProductStatus.DRAFT : request.status());
        try {
            productMapper.insert(product);
        } catch (DuplicateKeyException ex) {
            if (idempotencyKey != null) {
                Product existing = productMapper.findByRequestId(idempotencyKey);
                if (existing != null) {
                    return toView(existing);
                }
            }
            throw ex;
        }
        return toView(productMapper.findById(product.getId()));
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        String trimmed = requestId.trim();
        return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
    }

    public ProductView update(Long id, SaveProductRequest request) {
        if (productMapper.findById(id) == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        requireCategory(request.categoryId());
        requireCover(request.coverFileId());
        requireDigitalFile(request.type(), request.digitalFileId());

        Product product = new Product();
        product.setId(id);
        apply(product, request);
        productMapper.update(product);
        return toView(productMapper.findById(id));
    }

    public ProductView changeStatus(Long id, ProductStatus status) {
        if (productMapper.findById(id) == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        productMapper.updateStatus(id, status);
        return toView(productMapper.findById(id));
    }

    /**
     * 软删除商品，和“下架”不是一回事：
     * 下架只是 status=OFF_SHELF，仍然存在于后台；删除会记 deleted_at，前后台都不再展示。
     * 文件引用保留，已售数字商品的下载权益不受影响。
     */
    public void delete(Long id) {
        if (productMapper.findById(id) == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        productMapper.softDelete(id);
    }

    public PageResponse<ProductView> findDeletedPage(int page, int size) {
        long offset = (long) (page - 1) * size;
        List<ProductView> items = productMapper.findPageDeleted(offset, size)
                .stream().map(this::toView).toList();
        return new PageResponse<>(items, productMapper.countDeleted(), page, size);
    }

    /**
     * 从回收站恢复，保持 OFF_SHELF，商家确认后再上架。
     */
    public ProductView restore(Long id) {
        if (productMapper.restore(id) == 0) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return toView(productMapper.findById(id));
    }

    private void apply(Product product, SaveProductRequest request) {
        product.setCategoryId(request.categoryId());
        product.setName(request.name().trim());
        product.setDescription(request.description().trim());
        ProductType type = request.type() == null ? ProductType.PHYSICAL : request.type();
        product.setType(type);
        product.setPrice(request.price().setScale(2, RoundingMode.HALF_UP));
        // 数字商品不参与库存，统一记 0
        product.setStock(type == ProductType.DIGITAL ? 0
                : (request.stock() == null ? 0 : request.stock()));
        product.setCoverFileId(request.coverFileId());
        product.setDigitalFileId(request.digitalFileId());
    }

    private void requireDigitalFile(ProductType type, Long digitalFileId) {
        ProductType resolved = type == null ? ProductType.PHYSICAL : type;
        if (resolved == ProductType.DIGITAL) {
            if (digitalFileId == null) {
                throw new BusinessException(ErrorCode.DIGITAL_FILE_REQUIRED);
            }
            FileObject file = fileObjectMapper.findById(digitalFileId);
            if (file == null || file.getKind() != FileKind.DIGITAL) {
                throw new BusinessException(ErrorCode.DIGITAL_FILE_INVALID);
            }
        } else if (digitalFileId != null) {
            throw new BusinessException(ErrorCode.DIGITAL_FILE_INVALID);
        }
    }

    private void requireCategory(Long categoryId) {
        Category category = categoryMapper.findById(categoryId);
        if (category == null || category.getStatus() == null || category.getStatus() != 1) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
    }

    private void requireCover(Long coverFileId) {
        if (coverFileId == null) {
            return;
        }
        FileObject file = fileObjectMapper.findById(coverFileId);
        if (file == null || file.getKind() != FileKind.IMAGE) {
            throw new BusinessException(ErrorCode.COVER_NOT_FOUND);
        }
    }

    private ProductView toView(Product product) {
        String coverUrl = null;
        if (product.getCoverBucket() != null && product.getCoverObjectKey() != null) {
            coverUrl = storage.publicUrl(product.getCoverBucket(), product.getCoverObjectKey());
        }
        return new ProductView(product.getId(), product.getName(), product.getDescription(),
                product.getType(), product.getPrice(), product.getStock(),
                product.getCategoryId(), product.getCategoryName(),
                product.getCoverFileId(), coverUrl, product.getDigitalFileId(),
                product.getStatus(), product.getDeletedAt(), product.getCreatedAt());
    }
}
