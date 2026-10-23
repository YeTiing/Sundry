package com.sundry.order.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 从购物车选中项下单。addressId 仅含实物商品时必填。
 */
public record CreateOrderRequest(
        @NotEmpty(message = "请选择要购买的商品")
        List<Long> cartItemIds,

        Long addressId) {
}
