package com.sundry.seckill;

import com.sundry.order.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 领取到期的秒杀订单并取消。取消仍是带前置状态的条件更新，重复处理不会重复回补。
 */
@Service
public class SeckillTimeoutProcessor {

    private static final Logger log = LoggerFactory.getLogger(SeckillTimeoutProcessor.class);

    private final SeckillTimeoutService timeoutService;
    private final SeckillProperties properties;
    private final OrderService orderService;

    public SeckillTimeoutProcessor(SeckillTimeoutService timeoutService,
                                   SeckillProperties properties,
                                   OrderService orderService) {
        this.timeoutService = timeoutService;
        this.properties = properties;
        this.orderService = orderService;
    }

    public void cancelExpired() {
        List<String> due = timeoutService.popDue(System.currentTimeMillis(),
                properties.getTimeoutBatchSize());
        for (String orderNo : due) {
            try {
                orderService.cancelTimeoutOrder(orderNo);
            } catch (RuntimeException ex) {
                // 单个订单失败不影响其他订单；数据库兜底任务还会再次扫描
                log.warn("秒杀订单超时取消失败 orderNo={}", orderNo, ex);
            }
        }
    }
}
