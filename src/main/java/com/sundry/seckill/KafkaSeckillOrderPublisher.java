package com.sundry.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 生产环境使用 Kafka 投递建单消息；测试环境用内存实现。
 * 发送失败直接抛出，调用方会回补 Redis 预占。
 */
@Component
@Profile("!test")
public class KafkaSeckillOrderPublisher implements SeckillOrderPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaSeckillOrderPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 3;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SeckillProperties properties;

    public KafkaSeckillOrderPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                      SeckillProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(SeckillOrderMessage message) {
        try {
            kafkaTemplate.send(properties.getTopic(),
                            String.valueOf(message.activityId()),
                            objectMapper.writeValueAsString(message))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_READY);
        } catch (Exception ex) {
            log.error("秒杀建单消息投递失败 requestId={}", message.requestId(), ex);
            throw new BusinessException(ErrorCode.SECKILL_NOT_READY);
        }
    }
}
