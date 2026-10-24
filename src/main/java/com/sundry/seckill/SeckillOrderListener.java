package com.sundry.seckill;

import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 消费入口，具体逻辑在 consumer 里，便于测试直接调用。
 */
@Component
@Profile("!test")
public class SeckillOrderListener {

    private final SeckillOrderMessageConsumer consumer;

    public SeckillOrderListener(SeckillOrderMessageConsumer consumer) {
        this.consumer = consumer;
    }

    @KafkaListener(topics = "${app.seckill.topic:sundry.seckill.order.create}")
    public void onMessage(String payload) {
        consumer.handle(payload);
    }
}
