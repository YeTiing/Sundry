package com.sundry.feed;

import com.sundry.feed.dto.FeedEventRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka 投递成功、同步失败降级、异步失败降级。
 */
class KafkaFeedEventPublisherTest {

    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final FeedEventService feedEventService = mock(FeedEventService.class);
    private final FeedProperties properties = new FeedProperties();

    private final KafkaFeedEventPublisher publisher = new KafkaFeedEventPublisher(
            kafkaTemplate, new ObjectMapper(), feedEventService, properties);

    @Test
    void sendsMessageToKafka() {
        properties.setEventTopic("feed-topic");
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());

        publisher.publish(2L, request());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("feed-topic"), eq("2"), payload.capture());
        assertThat(payload.getValue())
                .contains("\"userId\":2")
                .contains("\"traceId\":\"trace-00000010\"")
                .contains("\"algorithm\":\"AI\"");
        verify(feedEventService, never()).record(any(), any());
    }

    @Test
    void fallsBackWhenSendThrows() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("kafka down"));

        publisher.publish(2L, request());

        verify(feedEventService).record(eq(2L), any(FeedEventRequest.class));
    }

    @Test
    void fallsBackWhenSendFailsAsynchronously() {
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> failed
                = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        publisher.publish(2L, request());

        verify(feedEventService, timeout(1000)).record(eq(2L), any(FeedEventRequest.class));
    }

    private FeedEventRequest request() {
        return new FeedEventRequest(
                "trace-00000010",
                "build-10",
                FeedAlgorithm.AI,
                List.of(new FeedEventRequest.EventItem(FeedEventType.EXPOSURE, 74L, 1)));
    }
}
