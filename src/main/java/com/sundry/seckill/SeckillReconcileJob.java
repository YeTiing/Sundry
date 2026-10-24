package com.sundry.seckill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天低峰对进行中的活动做一次对账，只报告不自动修复。
 */
@Component
@Profile("!test")
public class SeckillReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillReconcileJob.class);

    private final SeckillActivityMapper activityMapper;
    private final SeckillReconcileService reconcileService;

    public SeckillReconcileJob(SeckillActivityMapper activityMapper,
                               SeckillReconcileService reconcileService) {
        this.activityMapper = activityMapper;
        this.reconcileService = reconcileService;
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void scheduledRun() {
        for (SeckillActivity activity : activityMapper.findActiveForWarmUp()) {
            try {
                reconcileService.reconcile(activity.getId(), false);
            } catch (RuntimeException ex) {
                log.warn("秒杀对账失败 activityId={}", activity.getId(), ex);
            }
        }
    }
}
