package com.sundry.seckill;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每 5 秒领取一次到期的秒杀订单。测试环境关闭，由测试直接调用 processor。
 */
@Component
@Profile("!test")
public class SeckillTimeoutJob {

    private final SeckillTimeoutProcessor processor;

    public SeckillTimeoutJob(SeckillTimeoutProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.seckill.timeout-scan-interval:5s}")
    public void scheduledRun() {
        processor.cancelExpired();
    }
}
