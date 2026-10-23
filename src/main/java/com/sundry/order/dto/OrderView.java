package com.sundry.order.dto;

import com.sundry.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderView(String orderNo, OrderStatus status, BigDecimal totalAmount,
                        boolean hasPhysical, String receiverName, String receiverPhone,
                        String receiverAddress, String trackingNo,
                        LocalDateTime createdAt, LocalDateTime paidAt, LocalDateTime packedAt,
                        LocalDateTime shippedAt, LocalDateTime completedAt, LocalDateTime cancelledAt,
                        List<OrderItemView> items) {
}
