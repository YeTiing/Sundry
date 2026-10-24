package com.sundry.feed;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 清理超过保留期的浏览明细。分批删除，避免长事务。
 */
@Component
public class BehaviorCleanupProcessor {

    private final UserBehaviorMapper behaviorMapper;
    private final FeedProperties properties;

    public BehaviorCleanupProcessor(UserBehaviorMapper behaviorMapper, FeedProperties properties) {
        this.behaviorMapper = behaviorMapper;
        this.properties = properties;
    }

    public int cleanup() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusDays(Math.max(1, properties.getBehaviorRetentionDays()));
        int batchSize = Math.max(1, properties.getBehaviorCleanupBatchSize());
        int maxBatches = Math.max(1, properties.getBehaviorCleanupMaxBatches());

        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = behaviorMapper.deleteOldViews(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
