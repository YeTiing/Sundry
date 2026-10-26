# 秒杀取消后重新抢购（S1）设计文档

> 状态：**已实现（2026-10-26）**
> 关联：`docs/BACKLOG.md` T10、`DEVLOG.md` 2026-10-26 条目
> 范围：只有 `CANCELLED` 订单释放购买资格；已支付后的退款/退货仍保留资格

---

## 1. 规则变更

| 场景 | 旧规则 | 新规则 |
|---|---|---|
| 未支付，用户主动取消 | 保留购买标记，不可重抢 | 释放标记，可重新抢 |
| 未支付，30 分钟超时取消 | 保留购买标记，不可重抢 | 释放标记，可重新抢 |
| 已支付后退款 | 保留资格 | 保留资格（不变） |
| 已发货/确认收货后退货退款 | 保留资格 | 保留资格（不变） |
| 已有非取消订单时重复提交 | 返回已有订单（幂等） | 返回已有订单（不变） |

“释放资格”只影响新建订单，不影响用户继续浏览、切换已有 Feed/变体。

---

## 2. 旧实现为什么挡住重抢

三个独立闸门，缺一不可：

1. **应用层幂等前置检查**
   `OrderMapper.findSeckillOrder` 只按 `user_id + activity_id` 查询，不过滤状态；取消单也会被当成“已有订单”，直接返回成功。
2. **Redis Lua 购买标记**
   Lua 里 `HGET seckill:buyer:{activityId} {userId}` 判断限购；旧取消逻辑只归还库存，不删除 Hash 字段。
3. **数据库唯一索引**
   `uk_orders_activity_user (activity_id, user_id)` 会让第二单直接撞唯一键，消费端进入 `DuplicateKeyException` 分支。

---

## 3. 方案

### 3.1 数据库：生成列唯一索引

MySQL 不支持部分唯一索引，改用生成列：

```sql
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
```

- 非取消订单：`active_activity_user = "{activityId}:{userId}"`，唯一索引保证一人一单
- 取消订单：值为 `NULL`，MySQL 唯一索引允许多个 `NULL`，历史单保留
- 退款/退货：状态不是 `CANCELLED`，仍占用唯一键，避免“退款后再抢”绕过规则
- 迁移：`V27__seckill_rebuy.sql`

### 3.2 Redis：取消时原子归还并释放标记

新增 Lua，只有取消路径调用：

```lua
-- KEYS[1]=seckill:stock:{activityId}
-- KEYS[2]=seckill:buyer:{activityId}
-- ARGV[1]=1, ARGV[2]=userId
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCRBY', KEYS[1], ARGV[1])
end
redis.call('HDEL', KEYS[2], ARGV[2])
return 1
```

Java：`SeckillStockService.releaseCancelledReservation(activityId, userId)`

和原有 `restoreStock(activityId, 1)` 的区别：

| 方法 | 归还库存 | 删除购买标记 | 使用场景 |
|---|---|---|---|
| `restoreStock` | 是 | 否 | 建单失败补偿、退款/退货 |
| `releaseCancelledReservation` | 是 | 是 | 用户取消、超时取消 |

### 3.3 查询：排除取消单

`OrderMapper.xml`

```sql
-- findSeckillOrder
WHERE user_id = ? AND activity_id = ? AND status <> 'CANCELLED'

-- findSeckillBuyerIds（Redis 丢失后预热）
WHERE activity_id = ? AND order_source = 'SECKILL' AND status <> 'CANCELLED'
```

- 前者避免取消单在提交入口短路
- 后者避免 Redis 重建时把已取消用户重新写回购买者 Hash

### 3.4 OrderService：区分“取消”和“退款/退货”

`restoreStock(Order order, boolean releaseSeckillBuyer)`：

| 调用点 | 释放标记 |
|---|---|
| `cancel`（用户主动取消） | `true` |
| `cancelTimeoutOrders`（DB 扫描兜底超时） | `true` |
| `cancelTimeoutOrder`（ZSET 延迟队列超时） | `true` |
| `approveRefund`（商家通过退款） | `false` |
| `completeReturn`（确认收到退货退款） | `false` |

---

## 4. 流程

```
用户抢购
  ├─ findSeckillOrder：有非取消订单 → 返回已有订单（幂等）
  └─ 无 → Lua 扣库存/写购买标记 → Kafka → 建单

用户取消（或超时取消）
  ├─ 条件更新 status=PENDING_PAYMENT → CANCELLED
  ├─ 回补商品库存
  ├─ decrementSold(activityId)
  └─ Lua：INCR 活动库存 + HDEL 购买标记

用户重新抢购
  ├─ findSeckillOrder：只剩 CANCELLED → 查不到
  ├─ Lua：库存足够、购买标记已删 → 扣减成功
  ├─ 生成新的 orderNo
  └─ 唯一索引 active_activity_user：旧单为 NULL，新单通过
```

---

## 5. 并发与一致性

| 场景 | 保证 |
|---|---|
| 同一用户并发重复提交 | Lua 原子判重 + 购买者 Hash；只有一次扣减 |
| 取消和重抢并发 | Redis Lua 原子；DB 唯一索引兜底。极端交错下重抢可能返回已有活跃订单或需要重试，不会产生两条活跃订单 |
| Kafka 重复消费 | `findSeckillOrder` 返回活跃订单；`DuplicateKeyException` 归还多扣库存 |
| 取消重复调用 | `orderMapper.cancel` 条件更新，第二次返回当前订单 |
| Redis 丢失 | `ensureWarm` 按 DB 重建，取消单被过滤 |
| 多实例 | Redis 库存/标记和 DB 唯一索引都是全局的 |
| 退款/退货 | 不释放 Redis 标记；唯一索引仍占用，不能重新抢 |

### 已知窗口

`releaseCancelledReservation` 在取消事务内执行。如果 Redis 已释放、数据库事务随后回滚，用户可能短暂看到“可以重抢”，但：

- 旧订单仍是 `PENDING_PAYMENT`，`findSeckillOrder` 会先返回已有订单
- 数据库唯一索引仍占用，新单无法插入

最终不会产生两条活跃订单；极端情况下客户端需要重试。

---

## 6. API 表现

| 操作 | 返回 |
|---|---|
| 首次抢购 | `202 { requestId, status: PROCESSING }`，轮询结果成功 |
| 取消 | `200 { status: CANCELLED }` |
| 取消后重抢 | `202 { requestId, status: PROCESSING }`，轮询得到**新的 orderNo** |
| 已有活跃订单再提交 | `202 { requestId, status: SUCCESS }`，轮询得到已有 orderNo |

注意：取消后重抢返回 `PROCESSING`，不是 `SUCCESS`，因为需要重新走 Kafka 建单。

---

## 7. 迁移与回滚

### 上线

Flyway `V27__seckill_rebuy.sql` 自动执行。

### 回滚

旧唯一索引无法在“同一用户同时存在取消单和活跃单”时直接恢复。回滚前必须先处理数据：

1. 统计冲突：同一 `(activity_id, user_id)` 是否有多条非取消单；理论上新索引已阻止
2. 如需恢复旧规则：先删除/归档 `CANCELLED` 历史单，或把取消单迁到归档表
3. 删除 `uk_orders_activity_user_active` 和 `active_activity_user`
4. 重建 `uk_orders_activity_user (activity_id, user_id)`

回滚属于高风险操作，需单独评审。

---

## 8. 测试

### 自动化

`SeckillOrderIntegrationTest`（23 个用例）新增/调整：

- `cancelRestoresStockAndAllowsRebuy`：取消后 Redis 标记删除、新订单号、旧单保留、sold/remaining 正确
- `redisRebuildAfterCancelDoesNotBlockRebuy`：Redis 丢失后预热不把取消用户写回
- `expiredOrderIsCancelledAndStockRestored`：超时取消同样允许重抢
- `repeatPurchaseReturnsSameOrder`：活跃订单重复提交仍幂等

全量 Java：**370 个用例，0 失败 0 错误**

### 真实链路（本地）

```
抢到 373044383752130560
→ 取消（sold=0，Redis 剩余=2）
→ 重新抢到 373044388227452928（新单号）
→ 第三次提交返回已有订单 373044388227452928
→ 最终 sold=1、订单 2 条（1 CANCELLED + 1 PENDING_PAYMENT）、Redis 剩余=1
→ 对账 stockConsistent=true, soldConsistent=true
```

---

## 9. 不做 / 后续

- 不放开退款/退货后的重新抢购；如需放开，必须同时改 Lua 标记释放和生成列唯一索引
- 不删除取消单历史；后台订单列表会同时看到取消单和新单
- 不做“取消后保留优先排队”；重抢仍按 Redis 库存先到先得

---

## 10. 改动文件

- `src/main/resources/db/migration/V27__seckill_rebuy.sql`
- `src/main/resources/mapper/OrderMapper.xml`
- `src/main/java/com/sundry/seckill/SeckillStockService.java`
- `src/main/java/com/sundry/order/OrderService.java`
- `src/test/java/com/sundry/seckill/SeckillOrderIntegrationTest.java`
- `docs/BACKLOG.md`、`docs/INTERVIEW_SUMMARY.md`、`DEVLOG.md`
