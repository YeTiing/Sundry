package com.sundry.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天 4:30 清理过期曝光/点击；测试环境关闭，由测试直接调用 processor。
 */
@Component
@Profile("!test")
public class FeedEventCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(FeedEventCleanupJob.class);

    private final FeedEventCleanupProcessor processor;

    public FeedEventCleanupJob(FeedEventCleanupProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(cron = "${app.feed.event-cleanup-cron:0 30 4 * * *}")
    public void scheduledRun() {
        try {
            FeedEventCleanupProcessor.CleanupResult result = processor.cleanup();
            log.info("feed event cleanup finished: exposures={}, clicks={}",
                    result.exposures(), result.clicks());
        } catch (RuntimeException ex) {
            log.warn("feed event cleanup failed", ex);
        }
    }
}
