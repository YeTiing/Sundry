-- 秒杀取消后允许重新抢购：
-- 原来的 (activity_id, user_id) 唯一索引会让取消后的第二次抢购直接撞唯一键。
-- 改为只约束“非取消”订单，取消单的历史仍然保留。
ALTER TABLE orders
    DROP INDEX uk_orders_activity_user,
    ADD COLUMN active_activity_user VARCHAR(80)
        GENERATED ALWAYS AS (
            CASE
                WHEN activity_id IS NOT NULL AND status <> 'CANCELLED'
                    THEN CONCAT(activity_id, ':', user_id)
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_orders_activity_user_active (active_activity_user);
