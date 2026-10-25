package com.sundry.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每 5 分钟补齐缺失/过期向量，并清理下架/删除商品的向量。
 * 商品保存后的即时更新由 ProductEmbeddingListener 负责。
 */
@Component
@Profile("!test")
public class ProductEmbeddingSyncJob {

    private static final Logger log = LoggerFactory.getLogger(ProductEmbeddingSyncJob.class);

    private final ProductEmbeddingService embeddingService;
    private final EmbeddingProperties properties;

    public ProductEmbeddingSyncJob(ProductEmbeddingService embeddingService,
                                   EmbeddingProperties properties) {
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.embedding.sync-interval:5m}")
    public void scheduledRun() {
        if (!properties.isSyncEnabled()) {
            return;
        }
        try {
            int orphans = embeddingService.removeOrphans(properties.getOrphanBatchSize());
            int stale = embeddingService.syncStale(
                    properties.getSyncBatchSize(), properties.getSyncMaxBatches());
            if (orphans > 0 || stale > 0) {
                log.info("product embedding sync finished: stale={}, removed={}", stale, orphans);
            }
        } catch (RuntimeException ex) {
            log.warn("product embedding sync failed", ex);
        }
    }
}
