package com.sundry.order;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每分钟扫描未支付超时订单，取消并回补库存。
 */
@Component
public class OrderTimeoutJob {

    private final OrderService orderService;

    public OrderTimeoutJob(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(cron = "0 * * * * *")
    public void scheduledRun() {
        orderService.cancelTimeoutOrders();
    }
}
