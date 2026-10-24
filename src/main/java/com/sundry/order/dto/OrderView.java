package com.sundry.order.dto;

import com.sundry.order.OrderSource;
import com.sundry.order.OrderStatus;
import com.sundry.order.RefundKind;
import com.sundry.order.RefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderView(String orderNo, OrderStatus status, OrderSource orderSource,
                        Long activityId, BigDecimal totalAmount,
                        boolean hasPhysical, String buyerPhone, String buyerNickname,
                        String receiverName, String receiverPhone,
                        String receiverAddress, String trackingNo,
                        LocalDateTime createdAt, LocalDateTime paidAt, LocalDateTime packedAt,
                        LocalDateTime shippedAt, LocalDateTime completedAt, LocalDateTime cancelledAt,
                        LocalDateTime refundedAt, BigDecimal refundAmount,
                        RefundKind refundKind, RefundStatus refundStatus, String refundReason,
                        LocalDateTime refundRequestedAt, LocalDateTime refundReviewedAt,
                        String refundReviewRemark,
                        String returnTrackingNo, LocalDateTime returnShippedAt,
                        LocalDateTime receivedAt,
                        List<OrderItemView> items) {
}
