package com.sundry.seckill;

/**
 * 活动状态：草稿、启用、已结束、已取消。
 * “是否正在进行”还要结合 start_at / end_at 判断。
 */
public enum SeckillStatus {
    DRAFT,
    ACTIVE,
    ENDED,
    CANCELLED
}
