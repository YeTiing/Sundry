package com.sundry.feed;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每 2 秒把行为队列批量写入数据库；测试环境关闭，由测试直接调用 processor。
 */
@Component
@Profile("!test")
public class BehaviorFlushJob {

    private final BehaviorFlushProcessor processor;

    public BehaviorFlushJob(BehaviorFlushProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.feed.behavior-flush-interval:2s}")
    public void scheduledRun() {
        processor.flush();
    }
}
