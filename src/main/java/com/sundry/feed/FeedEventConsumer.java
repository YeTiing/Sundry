package com.sundry.feed;

import com.sundry.feed.dto.FeedEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 消费曝光/点击消息并批量落库。
 * 格式错误的消息直接丢弃；数据库异常向上抛，由 Kafka 容器重试。
 */
@Component
public class FeedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(FeedEventConsumer.class);

    private final FeedEventService feedEventService;
    private final ObjectMapper objectMapper;

    public FeedEventConsumer(FeedEventService feedEventService, ObjectMapper objectMapper) {
        this.feedEventService = feedEventService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.feed.event-topic:sundry.feed.event}",
            groupId = "${app.feed.event-group:sundry-feed-event}")
    public void consume(String payload) {
        handle(payload);
    }

    public void handle(String payload) {
        FeedEventMessage message;
        try {
            message = objectMapper.readValue(payload, FeedEventMessage.class);
        } catch (RuntimeException ex) {
            log.warn("丢弃格式错误的 feed event 消息", ex);
            return;
        }

        FeedEventRequest request;
        try {
            request = new FeedEventRequest(
                    message.traceId(),
                    message.buildId(),
                    message.algorithm(),
                    message.events().stream()
                            .map(item -> new FeedEventRequest.EventItem(
                                    item.type(), item.productId(), item.position()))
                            .toList());
        } catch (RuntimeException ex) {
            log.warn("丢弃字段不完整的 feed event 消息", ex);
            return;
        }

        // 数据库异常向上抛，交给 Kafka 容器重试
        feedEventService.record(message.userId(), request);
    }
}
