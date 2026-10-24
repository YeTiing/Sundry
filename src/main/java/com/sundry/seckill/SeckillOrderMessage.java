package com.sundry.seckill;

/**
 * 抢购成功后的建单消息。requestId 同时是结果查询的凭据。
 */
public record SeckillOrderMessage(String requestId, Long activityId, Long userId, Long addressId) {
}
