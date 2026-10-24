package com.sundry.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每秒消费一次 AI 推流队列。测试环境关闭，由测试直接调用 processor。
 */
@Component
@Profile("!test")
public class AiFeedBuildJob {

    private static final Logger log = LoggerFactory.getLogger(AiFeedBuildJob.class);

    private final AiFeedBuildProcessor processor;

    public AiFeedBuildJob(AiFeedBuildProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.ai.feed-build-interval:1s}")
    public void scheduledRun() {
        try {
            processor.buildPending();
        } catch (RuntimeException ex) {
            log.warn("AI 推流任务异常，等待下一轮", ex);
        }
    }
}
