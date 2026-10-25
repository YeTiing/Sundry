package com.sundry.catalog;

import com.sundry.catalog.dto.CategoryView;
import com.sundry.catalog.dto.ProductView;
import com.sundry.catalog.dto.SaveProductRequest;
import com.sundry.common.api.PageResponse;
import com.sundry.common.api.ScrollPage;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.file.FileKind;
import com.sundry.file.FileObject;
import com.sundry.file.FileObjectMapper;
import com.sundry.file.digital.DigitalUploadGateway;
import com.sundry.order.dto.DownloadView;
import com.sundry.storage.ObjectStorage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 商品读写。前台只读已上架；管理端可以读写全部状态。
 */
@Service
public class ProductService {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final FileObjectMapper fileObjectMapper;
    private final ObjectStorage storage;
    private final DigitalUploadGateway digitalGateway;
    private final ProductCacheService cache;
    private final ApplicationEventPublisher eventPublisher;

    public ProductService(ProductMapper productMapper, CategoryMapper categoryMapper,
                          FileObjectMapper fileObjectMapper, ObjectStorage storage,
                          DigitalUploadGateway digitalGateway, ProductCacheService cache,
                          ApplicationEventPublisher eventPublisher) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.fileObjectMapper = fileObjectMapper;
        this.storage = storage;
        this.digitalGateway = digitalGateway;
        this.cache = cache;
        this.eventPublisher = eventPublisher;
    }

    public List<CategoryView> categories() {
        return cache.categories(() -> categoryMapper.findActive().stream()
                .map(CategoryView::from).toList());
    }

    public PageResponse<ProductView> findPublicPage(Long categoryId, int page, int size) {
        ProductIdPage idPage = cache.idPage(categoryId, page, size, () -> {
            long offset = (long) (page - 1) * size;
            List<Long> ids = productMapper.findPublicIds(categoryId, offset, size);
            return new ProductIdPage(ids, productMapper.countPublic(categoryId), page, size);
        });
        List<ProductView> items = idPage.ids().stream()
                .map(id -> cache.detail(id, () -> {
                    Product product = productMapper.findPublicById(id);
                    return product == null ? null : toView(product);
                }))
                .filter(Objects::nonNull)
                .toList();
        return new PageResponse<>(items, idPage.total(), idPage.page(), idPage.size());
    }

    /**
     * 游标分页：按 id 倒序，多取一条判断 hasMore。
     */
    public ScrollPage<ProductView> findPublicScroll(Long categoryId, Long cursor, int size) {
        List<Long> ids = productMapper.findPublicIdsByCursor(categoryId, cursor, size + 1);
        boolean hasMore = ids.size() > size;
        List<Long> pageIds = hasMore ? ids.subList(0, size) : ids;
        List<ProductView> items = pageIds.stream()
                .map(id -> cache.detail(id, () -> {
                    Product product = productMapper.findPublicById(id);
                    return product == null ? null : toView(product);
                }))
                .filter(Objects::nonNull)
                .toList();
        Long nextCursor = hasMore && !pageIds.isEmpty()
                ? pageIds.get(pageIds.size() - 1)
                : null;
        return new ScrollPage<>(items, nextCursor, hasMore);
    }

    public PageResponse<ProductView> findAdminPage(ProductStatus status, int page, int size) {
        long offset = (long) (page - 1) * size;
        List<ProductView> items = productMapper.findPageAdmin(status, offset, size)
                .stream().map(this::toView).toList();
        return new PageResponse<>(items, productMapper.countAdmin(status), page, size);
    }

    public ProductView findPublic(Long id) {
        ProductView view = cache.detail(id, () -> {
            Product product = productMapper.findPublicById(id);
            return product == null ? null : toView(product);
        });
        if (view == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return view;
    }

    public ProductView findAdmin(Long id) {
        Product product = productMapper.findById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return toView(product);
    }

    /**
     * 管理员直接下载数字商品文件，不需要下单。
     */
    public DownloadView adminDownload(Long id) {
        Product product = productMapper.findById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (product.getType() != ProductType.DIGITAL || product.getDigitalFileId() == null) {
            throw new BusinessException(ErrorCode.PRODUCT_DOWNLOAD_NOT_SUPPORTED);
        }
        FileObject file = fileObjectMapper.findById(product.getDigitalFileId());
        if (file == null || file.getKind() != FileKind.DIGITAL) {
            throw new BusinessException(ErrorCode.DIGITAL_ITEM_NOT_FOUND);
        }
        Duration ttl = Duration.ofMinutes(10);
        return new DownloadView(digitalGateway.presignGet(file.getObjectKey(), ttl), ttl.toSeconds());
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
        cache.evictDetail(product.getId());
        cache.evictLists();
        eventPublisher.publishEvent(new ProductChangedEvent(product.getId()));
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
        Product existing = productMapper.findById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        requireCategory(request.categoryId());
        requireCover(request.coverFileId());
        requireDigitalFile(request.type(), request.digitalFileId());

        Product product = new Product();
        product.setId(id);
        apply(product, request);
        productMapper.update(product);

        // 卡片按商品独立缓存，普通编辑只失效这张卡片
        cache.evictDetail(id);
        // 换分类会改变列表归属，需要升级列表版本
        if (!Objects.equals(existing.getCategoryId(), request.categoryId())) {
            cache.evictLists();
        }
        eventPublisher.publishEvent(new ProductChangedEvent(id));
        return toView(productMapper.findById(id));
    }

    public ProductView changeStatus(Long id, ProductStatus status) {
        if (productMapper.findById(id) == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        productMapper.updateStatus(id, status);
        evict(id);
        eventPublisher.publishEvent(new ProductChangedEvent(id));
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
        evict(id);
        eventPublisher.publishEvent(new ProductChangedEvent(id));
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
        evict(id);
        eventPublisher.publishEvent(new ProductChangedEvent(id));
        return toView(productMapper.findById(id));
    }

    private void evict(Long id) {
        cache.evictDetail(id);
        cache.evictLists();
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
                product.getStatus(), product.getDeletedAt(), product.getCreatedAt(), null);
    }
}
