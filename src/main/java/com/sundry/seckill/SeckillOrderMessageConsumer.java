package com.sundry.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 消费建单消息。处理失败不抛出，避免 Kafka 无限重投；失败结果写入 Redis 供前端查询。
 */
@Service
public class SeckillOrderMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillOrderMessageConsumer.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SeckillOrderService orderService;

    public SeckillOrderMessageConsumer(SeckillOrderService orderService) {
        this.orderService = orderService;
    }

    public void handle(String payload) {
        try {
            SeckillOrderMessage message = objectMapper.readValue(payload, SeckillOrderMessage.class);
            orderService.process(message);
        } catch (Exception ex) {
            log.error("秒杀建单消息处理失败 payload={}", payload, ex);
        }
    }
}
