package com.sundry.seckill;

import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.order.OrderMapper;
import com.sundry.seckill.dto.SeckillReconcileView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 活动库存对账：DB 的 sold_count 与有效订单数、Redis 剩余库存与 DB 剩余库存。
 * 默认只报告；显式 repair 且 sold_count 一致时，按 DB 重建 Redis 库存。
 */
@Service
public class SeckillReconcileService {

    private static final Logger log = LoggerFactory.getLogger(SeckillReconcileService.class);

    private final SeckillActivityMapper activityMapper;
    private final OrderMapper orderMapper;
    private final SeckillStockService stockService;
    private final MeterRegistry meterRegistry;

    public SeckillReconcileService(SeckillActivityMapper activityMapper,
                                   OrderMapper orderMapper,
                                   SeckillStockService stockService,
                                   MeterRegistry meterRegistry) {
        this.activityMapper = activityMapper;
        this.orderMapper = orderMapper;
        this.stockService = stockService;
        this.meterRegistry = meterRegistry;
    }

    public SeckillReconcileView reconcile(Long activityId, boolean repair) {
        SeckillActivity activity = activityMapper.findById(activityId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
        }

        int sold = activity.getSoldCount() == null ? 0 : activity.getSoldCount();
        int dbRemaining = Math.max(0, activity.getStock() - sold);
        int redisRemaining = stockService.remaining(activityId);
        long activeOrders = orderMapper.countActiveSeckillOrders(activityId);

        boolean stockConsistent = redisRemaining == dbRemaining;
        boolean soldConsistent = activeOrders == sold;
        boolean repaired = false;

        if (!stockConsistent || !soldConsistent) {
            log.warn("秒杀活动对账不一致 activityId={} stock={} sold={} dbRemaining={} "
                            + "redisRemaining={} activeOrders={}",
                    activityId, activity.getStock(), sold, dbRemaining,
                    redisRemaining, activeOrders);
            Counter.builder("sundry.seckill.reconcile.mismatch")
                    .tag("activityId", String.valueOf(activityId))
                    .register(meterRegistry)
                    .increment();
        }

        // sold_count 不一致时不做自动修复，避免把错误的已售数当成真相
        if (repair && !stockConsistent && soldConsistent) {
            stockService.clear(activityId);
            stockService.ensureWarm(activity);
            repaired = true;
            redisRemaining = stockService.remaining(activityId);
            stockConsistent = redisRemaining == dbRemaining;
        }

        return new SeckillReconcileView(activityId, activity.getStock(), sold, dbRemaining,
                redisRemaining, activeOrders, stockConsistent, soldConsistent, repaired);
    }
}
