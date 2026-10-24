package com.sundry.feed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天凌晨清理过期浏览明细；测试环境关闭，由测试直接调用 processor。
 */
@Component
@Profile("!test")
public class BehaviorCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(BehaviorCleanupJob.class);

    private final BehaviorCleanupProcessor processor;

    public BehaviorCleanupJob(BehaviorCleanupProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void scheduledRun() {
        int deleted = processor.cleanup();
        if (deleted > 0) {
            log.info("清理过期浏览行为 {} 条", deleted);
        }
    }
}
