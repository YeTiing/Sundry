package com.sundry.seckill.dto;

import com.sundry.catalog.ProductType;
import com.sundry.seckill.SeckillActivity;
import com.sundry.seckill.SeckillStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SeckillActivityView(Long id, Long productId, String productName,
                                  ProductType productType, BigDecimal productPrice,
                                  BigDecimal seckillPrice, int stock, int soldCount, int remaining,
                                  int perUserLimit, LocalDateTime startAt, LocalDateTime endAt,
                                  SeckillStatus status, boolean activeNow,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static SeckillActivityView from(SeckillActivity activity, LocalDateTime now) {
        int sold = activity.getSoldCount() == null ? 0 : activity.getSoldCount();
        int remaining = Math.max(0, activity.getStock() - sold);
        boolean activeNow = activity.getStatus() == SeckillStatus.ACTIVE
                && !now.isBefore(activity.getStartAt())
                && now.isBefore(activity.getEndAt());
        return new SeckillActivityView(activity.getId(), activity.getProductId(),
                activity.getProductName(), activity.getProductType(), activity.getProductPrice(),
                activity.getSeckillPrice(), activity.getStock(), sold, remaining,
                activity.getPerUserLimit(), activity.getStartAt(), activity.getEndAt(),
                activity.getStatus(), activeNow, activity.getCreatedAt(), activity.getUpdatedAt());
    }
}
