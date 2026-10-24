package com.sundry.seckill;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductStatus;
import com.sundry.catalog.ProductType;
import com.sundry.common.api.PageResponse;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.seckill.dto.SaveSeckillActivityRequest;
import com.sundry.seckill.dto.SeckillActivityView;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动管理。活动库存是销售配额；实物下单时还要受商品库存约束。
 */
@Service
public class SeckillActivityService {

    private static final int DEFAULT_PER_USER_LIMIT = 1;

    private final SeckillActivityMapper activityMapper;
    private final ProductMapper productMapper;

    public SeckillActivityService(SeckillActivityMapper activityMapper, ProductMapper productMapper) {
        this.activityMapper = activityMapper;
        this.productMapper = productMapper;
    }

    public SeckillActivityView create(SaveSeckillActivityRequest request) {
        Product product = requireSaleableProduct(request.productId());
        validate(request, product, null);
        SeckillActivity activity = new SeckillActivity();
        applyRequest(activity, request);
        activity.setSoldCount(0);
        activity.setStatus(SeckillStatus.DRAFT);
        activityMapper.insert(activity);
        return view(requireActivity(activity.getId()));
    }

    public SeckillActivityView update(Long id, SaveSeckillActivityRequest request) {
        SeckillActivity existing = requireActivity(id);
        if (existing.getStatus() != SeckillStatus.DRAFT) {
            throw new BusinessException(ErrorCode.SECKILL_STATUS_INVALID);
        }
        Product product = requireSaleableProduct(request.productId());
        validate(request, product, id);
        SeckillActivity activity = new SeckillActivity();
        activity.setId(id);
        applyRequest(activity, request);
        if (activityMapper.updateDraft(activity) == 0) {
            throw new BusinessException(ErrorCode.SECKILL_STATUS_INVALID);
        }
        return view(requireActivity(id));
    }

    public SeckillActivityView activate(Long id) {
        SeckillActivity existing = requireActivity(id);
        if (existing.getStatus() == SeckillStatus.ACTIVE) {
            return view(existing);
        }
        if (existing.getStatus() != SeckillStatus.DRAFT) {
            throw new BusinessException(ErrorCode.SECKILL_STATUS_INVALID);
        }
        if (!existing.getEndAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.SECKILL_TIME_INVALID);
        }
        requireNoActiveOverlap(existing.getProductId(), existing.getStartAt(),
                existing.getEndAt(), id);
        if (activityMapper.activate(id) == 0) {
            SeckillActivity refreshed = requireActivity(id);
            if (refreshed.getStatus() == SeckillStatus.ACTIVE) {
                return view(refreshed);
            }
            throw new BusinessException(ErrorCode.SECKILL_STATUS_INVALID);
        }
        return view(requireActivity(id));
    }

    public SeckillActivityView cancel(Long id) {
        SeckillActivity existing = requireActivity(id);
        if (existing.getStatus() == SeckillStatus.CANCELLED) {
            return view(existing);
        }
        if (existing.getStatus() != SeckillStatus.DRAFT
                && existing.getStatus() != SeckillStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SECKILL_STATUS_INVALID);
        }
        if (activityMapper.cancel(id) == 0) {
            SeckillActivity refreshed = requireActivity(id);
            if (refreshed.getStatus() == SeckillStatus.CANCELLED) {
                return view(refreshed);
            }
            throw new BusinessException(ErrorCode.SECKILL_STATUS_INVALID);
        }
        return view(requireActivity(id));
    }

    public SeckillActivityView end(Long id) {
        SeckillActivity existing = requireActivity(id);
        if (existing.getStatus() == SeckillStatus.ENDED) {
            return view(existing);
        }
        if (existing.getStatus() != SeckillStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SECKILL_STATUS_INVALID);
        }
        if (activityMapper.end(id) == 0) {
            SeckillActivity refreshed = requireActivity(id);
            if (refreshed.getStatus() == SeckillStatus.ENDED) {
                return view(refreshed);
            }
            throw new BusinessException(ErrorCode.SECKILL_STATUS_INVALID);
        }
        return view(requireActivity(id));
    }

    public PageResponse<SeckillActivityView> adminPage(SeckillStatus status, int page, int size) {
        long offset = (long) (page - 1) * size;
        List<SeckillActivityView> items = activityMapper.findPageAdmin(status, offset, size)
                .stream().map(this::view).toList();
        return new PageResponse<>(items, activityMapper.countAdmin(status), page, size);
    }

    public SeckillActivityView adminDetail(Long id) {
        return view(requireActivity(id));
    }

    /**
     * 商品详情用：只返回当前正在进行的活动。
     */
    public SeckillActivityView activeForProduct(Long productId) {
        SeckillActivity activity = activityMapper.findActiveByProduct(productId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
        }
        return view(activity);
    }

    private void applyRequest(SeckillActivity activity, SaveSeckillActivityRequest request) {
        activity.setProductId(request.productId());
        activity.setSeckillPrice(request.seckillPrice());
        activity.setStock(request.stock());
        activity.setPerUserLimit(request.perUserLimit() == null
                ? DEFAULT_PER_USER_LIMIT : request.perUserLimit());
        activity.setStartAt(request.startAt());
        activity.setEndAt(request.endAt());
    }

    private void validate(SaveSeckillActivityRequest request, Product product, Long excludeId) {
        LocalDateTime now = LocalDateTime.now();
        if (!request.startAt().isBefore(request.endAt())) {
            throw new BusinessException(ErrorCode.SECKILL_TIME_INVALID);
        }
        if (!request.endAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.SECKILL_TIME_INVALID);
        }
        if (request.seckillPrice().signum() <= 0
                || request.seckillPrice().compareTo(product.getPrice()) > 0) {
            throw new BusinessException(ErrorCode.SECKILL_PRICE_INVALID);
        }
        if (product.getType() == ProductType.PHYSICAL) {
            int productStock = product.getStock() == null ? 0 : product.getStock();
            if (request.stock() > productStock) {
                throw new BusinessException(ErrorCode.SECKILL_STOCK_INVALID);
            }
        }
        requireNoActiveOverlap(request.productId(), request.startAt(), request.endAt(), excludeId);
    }

    private void requireNoActiveOverlap(Long productId, LocalDateTime startAt,
                                        LocalDateTime endAt, Long excludeId) {
        long overlap = activityMapper.countOverlapping(productId, startAt, endAt, excludeId,
                List.of(SeckillStatus.ACTIVE));
        if (overlap > 0) {
            throw new BusinessException(ErrorCode.SECKILL_CONFLICT);
        }
    }

    private Product requireSaleableProduct(Long productId) {
        Product product = productMapper.findById(productId);
        if (product == null || product.getDeletedAt() != null
                || product.getStatus() != ProductStatus.ON_SALE) {
            throw new BusinessException(ErrorCode.SECKILL_PRODUCT_NOT_SALEABLE);
        }
        return product;
    }

    private SeckillActivity requireActivity(Long id) {
        SeckillActivity activity = activityMapper.findById(id);
        if (activity == null) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
        }
        return activity;
    }

    private SeckillActivityView view(SeckillActivity activity) {
        return SeckillActivityView.from(activity, LocalDateTime.now());
    }
}
