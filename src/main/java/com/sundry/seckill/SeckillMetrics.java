package com.sundry.seckill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 秒杀关键指标，Prometheus 通过 /actuator/prometheus 抓取。
 */
@Component
public class SeckillMetrics {

    private static final String SUBMIT = "sundry.seckill.submit";
    private static final String ORDER = "sundry.seckill.order";
    private static final String COMPENSATION = "sundry.seckill.compensation";
    private static final String SUBMIT_DURATION = "sundry.seckill.submit.duration";

    private final MeterRegistry registry;

    public SeckillMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void submitResult(String result) {
        Counter.builder(SUBMIT).tag("result", result).register(registry).increment();
    }

    public void orderSuccess() {
        Counter.builder(ORDER).tag("result", "success").register(registry).increment();
    }

    public void orderFailed(String reason) {
        Counter.builder(ORDER).tag("result", "failed").tag("reason", reason).register(registry).increment();
    }

    public void compensation() {
        Counter.builder(COMPENSATION).register(registry).increment();
    }

    public void submitDuration(long nanos) {
        Timer.builder(SUBMIT_DURATION).register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }
}
