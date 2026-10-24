package com.sundry.feed;

import com.sundry.catalog.Product;
import com.sundry.catalog.ProductMapper;
import com.sundry.catalog.ProductStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 浏览行为记录：Redis 去重后进入队列，由定时任务批量落库。
 * Redis 不可用时降级为直接写库。
 */
@Service
public class BehaviorService {

    private static final Logger log = LoggerFactory.getLogger(BehaviorService.class);
    private static final Duration DEDUPE_TTL = Duration.ofMinutes(5);
    private static final String QUEUE_KEY = "behavior:queue";

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

        BehaviorEvent event = new BehaviorEvent(UUID.randomUUID().toString(), userId, productId,
                product.getCategoryId(), "VIEW", System.currentTimeMillis());
        try {
            redis.opsForList().rightPush(QUEUE_KEY, event.encode());
        } catch (RuntimeException ex) {
            log.warn("行为入队失败，降级直接写库 userId={} productId={}", userId, productId, ex);
            try {
                behaviorMapper.insert(event.toEntity());
            } catch (RuntimeException insertError) {
                log.warn("行为直接写库失败 userId={} productId={}", userId, productId, insertError);
            }
        }
    }
}
