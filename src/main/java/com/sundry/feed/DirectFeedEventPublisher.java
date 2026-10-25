package com.sundry.feed;

import com.sundry.feed.dto.FeedEventRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 测试环境直接写库，避免测试依赖 Kafka。
 */
@Component
@Profile("test")
public class DirectFeedEventPublisher implements FeedEventPublisher {

    private final FeedEventService feedEventService;

    public DirectFeedEventPublisher(FeedEventService feedEventService) {
        this.feedEventService = feedEventService;
    }

    @Override
    public void publish(Long userId, FeedEventRequest request) {
        feedEventService.record(userId, request);
    }
}
