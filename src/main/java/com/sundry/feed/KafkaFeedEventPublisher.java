package com.sundry.feed;

import com.sundry.feed.dto.FeedEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 生产环境走 Kafka。投递失败（同步异常或异步失败）降级为直接写库，避免丢曝光。
 */
@Component
@Profile("!test")
public class KafkaFeedEventPublisher implements FeedEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaFeedEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final FeedEventService feedEventService;
    private final FeedProperties properties;

    public KafkaFeedEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   FeedEventService feedEventService,
                                   FeedProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.feedEventService = feedEventService;
        this.properties = properties;
    }

    @Override
    public void publish(Long userId, FeedEventRequest request) {
        String payload;
        try {
            FeedEventMessage message = new FeedEventMessage(
                    userId, request.traceId(), request.buildId(), request.algorithm(),
                    request.events().stream()
                            .map(item -> new FeedEventMessage.EventItem(
                                    item.type(), item.productId(), item.position()))
                            .toList());
            payload = objectMapper.writeValueAsString(message);
        } catch (RuntimeException ex) {
            log.warn("feed event 序列化失败，降级写库 userId={}", userId, ex);
            feedEventService.record(userId, request);
            return;
        }

        try {
            kafkaTemplate.send(properties.getEventTopic(), String.valueOf(userId), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("feed event 投递失败，降级写库 userId={}", userId, ex);
                            feedEventService.record(userId, request);
                        }
                    });
        } catch (RuntimeException ex) {
            log.warn("feed event 投递异常，降级写库 userId={}", userId, ex);
            feedEventService.record(userId, request);
        }
    }
}
