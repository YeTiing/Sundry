package com.sundry.order;

import java.time.LocalDateTime;

/**
 * 用户订单查询条件。时间由服务端换算，SQL 里只做参数绑定。
 */
public record OrderQueryFilters(OrderStatus status, LocalDateTime start, LocalDateTime end,
                                String keyword, OrderSort sort) {
}
