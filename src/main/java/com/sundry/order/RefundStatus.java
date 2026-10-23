package com.sundry.order;

/**
 * 退款申请状态；订单主状态仍以 {@link OrderStatus} 为准。
 */
public enum RefundStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
