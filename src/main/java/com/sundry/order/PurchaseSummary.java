package com.sundry.order;

import java.math.BigDecimal;

/**
 * 个人购买汇总：订单数、商品件数、累计支付金额。
 */
public record PurchaseSummary(long orderCount, long itemCount, BigDecimal totalAmount) {
}
