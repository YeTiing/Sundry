package com.sundry.feed;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sundry.catalog.ProductService;
import com.sundry.catalog.dto.ProductView;
import com.sundry.feed.dto.FeaturedSeckillView;
import com.sundry.seckill.SeckillActivity;
import com.sundry.seckill.SeckillActivityMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 首页秒杀运营位：全局共享，短缓存，不参与个性化排序，也不触发用户变体重建。
 */
@Service
public class FeedFeaturedService {

    private final SeckillActivityMapper activityMapper;
    private final ProductService productService;
    private final FeedProperties properties;
    private final Cache<String, List<FeaturedSeckillView>> cache;

    public FeedFeaturedService(SeckillActivityMapper activityMapper,
                               ProductService productService,
                               FeedProperties properties) {
        this.activityMapper = activityMapper;
        this.productService = productService;
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(properties.getFeaturedCacheTtl())
                .build();
    }

    public List<FeaturedSeckillView> featured() {
        return cache.get("active", ignored -> load());
    }

    private List<FeaturedSeckillView> load() {
        int limit = Math.min(Math.max(1, properties.getFeaturedLimit()), 10);
        List<SeckillActivity> activities = activityMapper.findFeatured(limit);
        if (activities == null || activities.isEmpty()) {
            return List.of();
        }
        List<Long> productIds = activities.stream()
                .map(SeckillActivity::getProductId)
                .toList();
        Map<Long, ProductView> products = productService.findPublicBatch(productIds);

        List<FeaturedSeckillView> result = new ArrayList<>();
        for (SeckillActivity activity : activities) {
            ProductView product = products.get(activity.getProductId());
            if (product == null || activity.getSeckillPrice() == null) {
                continue;
            }
            int stock = activity.getStock() == null ? 0 : activity.getStock();
            int sold = activity.getSoldCount() == null ? 0 : activity.getSoldCount();
            int remaining = Math.max(0, stock - sold);
            if (remaining <= 0) {
                continue;
            }
            result.add(new FeaturedSeckillView(
                    activity.getId(), activity.getProductId(), product.name(),
                    product.coverUrl(), product.price(), activity.getSeckillPrice(),
                    remaining, activity.getEndAt()));
        }
        return List.copyOf(result);
    }
}
