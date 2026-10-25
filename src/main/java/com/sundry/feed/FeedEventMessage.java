package com.sundry.feed;

import java.util.List;

/**
 * Kafka 里的曝光/点击消息。userId 由 Controller 从 JWT 写入。
 */
public record FeedEventMessage(Long userId, String traceId, String buildId,
                               FeedAlgorithm algorithm, List<EventItem> events) {

    public record EventItem(FeedEventType type, Long productId, int position) {
    }
}
