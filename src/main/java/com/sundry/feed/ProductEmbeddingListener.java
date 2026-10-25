package com.sundry.feed;

import com.sundry.catalog.ProductChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 商品变更后异步刷新单个商品向量，不阻塞保存请求。
 * 定时扫描作为兜底，见 ProductEmbeddingSyncJob。
 */
@Component
@Profile("!test")
public class ProductEmbeddingListener {

    private static final Logger log = LoggerFactory.getLogger(ProductEmbeddingListener.class);

    private final ProductEmbeddingService embeddingService;

    public ProductEmbeddingListener(ProductEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Async
    @EventListener
    public void onProductChanged(ProductChangedEvent event) {
        try {
            embeddingService.refresh(event.productId());
        } catch (RuntimeException ex) {
            log.warn("商品向量增量更新失败 productId={}，等待定时扫描兜底",
                    event.productId(), ex);
        }
    }
}
