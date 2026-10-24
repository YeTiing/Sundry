package com.sundry.feed;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 浏览行为记录。同一用户同一商品 5 分钟内只记一次，Redis 不可用时降级为直接写库。
 */
@Service
public class BehaviorService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorService.class);
    private static final Duration DEDUPE_TTL = Duration.ofMinutes(5);

    private final UserBehaviorMapper behaviorMapper;
    private final ProductMapper productMapper;
    private final StringRedisTemplate redis;

    public BehaviorService(UserBehaviorMapper behaviorMapper, ProductMapper productMapper,
                           StringRedisTemplate redis) {
        this.behaviorMapper = behaviorMapper;
        this.productMapper = productMapper;
        this.redis = redis;
    }

    public void recordView(Long userId, Long productId) {
        Product product = productMapper.findById(productId);
        if (product == null || product.getDeletedAt() != null
                || product.getStatus() != ProductStatus.ON_SALE) {
            return;
        }

        String dedupeKey = "behavior:view:%d:%d".formatted(userId, productId);
        try {
            Boolean first = redis.opsForValue().setIfAbsent(dedupeKey, "1", DEDUPE_TTL);
            if (!Boolean.TRUE.equals(first)) {
                return;
            }
        } catch (RuntimeException ex) {
            log.warn("浏览行为去重失败，按未重复处理 userId={} productId={}", userId, productId, ex);
        }

        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setEventType("VIEW");
        behavior.setProductId(productId);
        behavior.setCategoryId(product.getCategoryId());
        try {
            behaviorMapper.insert(behavior);
            // 行为变化后让该用户的 Feed 快照失效，下次进入首页重新算
            redis.delete("feed:home:" + userId);
        } catch (RuntimeException ex) {
            log.warn("浏览行为写入失败 userId={} productId={}", userId, productId, ex);
            try {
                redis.delete(dedupeKey);
            } catch (RuntimeException ignored) {
                // 清理失败不影响主流程
            }
        }
    }
}
