package com.sundry.feed;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Redis 队列里的行为事件。用简单分隔符编码，避免在请求链路里引入 JSON 序列化。
 */
public record BehaviorEvent(String eventId, Long userId, Long productId, Long categoryId,
                            String eventType, long createdAtMillis) {

    public String encode() {
        return String.join("|", eventId, userId.toString(), productId.toString(),
                categoryId.toString(), eventType, Long.toString(createdAtMillis));
    }

    public static BehaviorEvent decode(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("行为事件格式错误: " + value);
        }
        return new BehaviorEvent(parts[0], Long.valueOf(parts[1]), Long.valueOf(parts[2]),
                Long.valueOf(parts[3]), parts[4], Long.parseLong(parts[5]));
    }

    public UserBehavior toEntity() {
        UserBehavior behavior = new UserBehavior();
        behavior.setEventId(eventId);
        behavior.setUserId(userId);
        behavior.setEventType(eventType);
        behavior.setProductId(productId);
        behavior.setCategoryId(categoryId);
        behavior.setCreatedAt(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(createdAtMillis), ZoneId.systemDefault()));
        return behavior;
    }
}
