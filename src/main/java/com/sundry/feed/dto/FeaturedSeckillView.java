package com.sundry.feed.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 首页秒杀运营位。全局共享，不参与个性化排序。
 */
public record FeaturedSeckillView(Long activityId, Long productId, String name,
                                  String coverUrl, BigDecimal price, BigDecimal seckillPrice,
                                  int remaining, LocalDateTime endsAt) {
}
