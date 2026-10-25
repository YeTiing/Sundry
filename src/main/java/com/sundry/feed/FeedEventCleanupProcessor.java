package com.sundry.feed;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 分批清理过期曝光/点击，避免一次删除锁表。
 * 曝光保留 14 天，点击保留 90 天，均可配置。
 */
@Component
public class FeedEventCleanupProcessor {

    private final FeedEventMapper feedEventMapper;
    private final FeedProperties properties;

    public FeedEventCleanupProcessor(FeedEventMapper feedEventMapper,
                                     FeedProperties properties) {
        this.feedEventMapper = feedEventMapper;
        this.properties = properties;
    }

    public CleanupResult cleanup() {
        LocalDateTime now = LocalDateTime.now();
        int exposures = deleteOld(FeedEventType.EXPOSURE.name(),
                now.minusDays(Math.max(1, properties.getEventExposureRetentionDays())));
        int clicks = deleteOld(FeedEventType.CLICK.name(),
                now.minusDays(Math.max(1, properties.getEventClickRetentionDays())));
        return new CleanupResult(exposures, clicks);
    }

    private int deleteOld(String eventType, LocalDateTime cutoff) {
        int batchSize = Math.max(1, properties.getEventCleanupBatchSize());
        int maxBatches = Math.max(1, properties.getEventCleanupMaxBatches());
        int deleted = 0;
        for (int i = 0; i < maxBatches; i++) {
            int rows = feedEventMapper.deleteOld(eventType, cutoff, batchSize);
            deleted += rows;
            if (rows < batchSize) {
                break;
            }
        }
        return deleted;
    }

    public record CleanupResult(int exposures, int clicks) {
    }
}
