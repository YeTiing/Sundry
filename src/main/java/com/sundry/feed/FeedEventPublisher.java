package com.sundry.feed;

import com.sundry.feed.dto.FeedEventRequest;

public interface FeedEventPublisher {

    void publish(Long userId, FeedEventRequest request);
}
