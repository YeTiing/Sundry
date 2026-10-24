package com.sundry.seckill.dto;

public record SeckillReconcileView(Long activityId, int activityStock, int soldCount,
                                   int dbRemaining, int redisRemaining, long activeOrders,
                                   boolean stockConsistent, boolean soldConsistent,
                                   boolean repaired) {
}
