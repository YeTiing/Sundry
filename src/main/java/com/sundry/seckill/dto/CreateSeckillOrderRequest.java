package com.sundry.seckill.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 秒杀下单：数量固定 1 件，实物订单需要地址。
 */
public record CreateSeckillOrderRequest(
        @NotNull(message = "活动不能为空")
        Long activityId,

        Long addressId) {
}
