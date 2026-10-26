# AI 推流构建额度设计（P0-P2）

> 状态：**已实现（2026-10-26）**
> 关联：`docs/FUTURE_DESIGN.md` 推荐 Feed、`DEVLOG.md` 2026-10-26 条目
> 目标：共享全局额度，同时保证防刷、公平、首访优先和窗口平滑

---

## 1. 背景

AI 重排是异步构建的，每次构建会调用两次模型（PREF / EXPLORE）。原先只有：

- 每用户 10 秒冷却
- 全局固定分钟计数 `20 次/分钟`

存在四个问题：

1. **拒绝也计数**：先 `INCR` 再判断上限，被拒绝的请求也会把全局额度打满
2. **固定窗口边界双倍**：跨分钟边界可以短时通过约 2 倍
3. **没有用户公平**：一个用户每 10 秒可触发一次，能吃掉大部分全局额度
4. **429 冷却只在单实例**：多实例部署时，一台遇到 429，其他实例仍会继续调用

---

## 2. 分层设计

| 层 | 实现 | 目的 |
|---|---|---|
| 防刷 | Redis `SETNX feed:ai:build:cooldown:{userId}`，10 秒 | 防连点 |
| 公平 | `feed:ai:build:hourly:{userId}`，默认 6 次/小时 | 防止单人吃光 |
| 全局额度 | Redis 令牌桶，默认 20 次/分钟 | 控制总成本 |
| 首访优先 | 独立保底令牌桶，默认 50% | 新用户/空池优先 |
| 窗口平滑 | 令牌桶按毫秒连续补充 | 消除固定分钟边界 |
| 429 冷却 | Redis `feed:ai:llm:cooldown` | 多实例共享 |
| 全局并发 | Redis ZSET 租约（可选） | 多实例时限制在途 LLM 调用 |

### 2.1 令牌桶

两个桶：

| 桶 | 容量 | 使用方 |
|---|---|---|
| `feed:ai:build:bucket:first` | `ceil(rate * reservePercent / 100)` | `COLD_START` |
| `feed:ai:build:bucket:shared` | `rate - first` | `COLD_START` 和 `REFILL` |

- `COLD_START`：先试首访桶，再试共享桶
- `REFILL`：只试共享桶
- 只有真正拿到令牌才扣减；被拒绝的请求不会写回 `tokens - 1`
- 令牌按 `容量 / 60000` 每毫秒补充，Redis `TIME` 作为统一时钟
- 桶 key 2 分钟无访问自动过期，重新回到满额

这是**硬预留**：`REFILL` 不能借用首访桶。低流量时预留容量会被浪费，但不会造成拒绝；高流量时能保证新用户不被补算流量挤掉。后续如需借用，可在 `REFILL` 分支增加“首访桶高于阈值才借用”的规则。

### 2.2 构建原因

新增 `BuildReason`：

| 原因 | 触发点 |
|---|---|
| `COLD_START` | 首访/池空，规则兜底变体先顶上 |
| `REFILL` | 切满一轮后补算下一批、轮询到规则兜底变体 |

调用链：`FeedService.doAiPage` / `finishSwitch` → `ensureBuild(userId, force, reason)` → `AiFeedStore.tryBuildPermit(userId, reason)`

### 2.3 用户每小时配额

- key：`feed:ai:build:hourly:{userId}`，TTL 3600 秒
- 只在**授予构建许可后** `INCR`
- 读取时达到上限直接拒绝，不消耗全局令牌
- 默认 6 次/小时；设 0 表示不限制

### 2.4 429 全局冷却

`AiLlmGate.coolDown()` 同时写：

- 本实例 `cooldownUntil`（Redis 故障时兜底）
- Redis `feed:ai:llm:cooldown`，TTL 等于 `feed-llm-cooldown`（默认 30 秒）

`ensureReady()` 两个都检查。多实例共享同一个冷却标记。

### 2.5 全局 LLM 并发（可选）

默认关闭（`feed-llm-global-concurrency=0`），单实例继续用本地 Semaphore。

开启后使用 Redis ZSET 租约：

- key：`feed:ai:llm:leases`
- 每个在途调用一个成员，score = 过期时间
- 获取时先清理过期成员，再判断 `ZCARD < limit`
- 释放时 `ZREM`
- 租约 TTL 默认 60 秒，大于 AI HTTP 超时 30 秒

这样 N 个实例共享同一个并发上限，而不是 N × `feed-llm-max-concurrent`。

### 2.6 指标

`AiFeedStore` 记录：

```
sundry.feed.ai.build.permit{
  result=granted|rejected,
  reason=ok|cooldown|hourly_limit|global_limit,
  buildReason=COLD_START|REFILL
}
```

用于观察拒绝原因分布，再决定是否调整 `rate-limit`、`reserve-percent`、`hourly`。

---

## 3. 流程

```
Feed 请求
  ├─ 有可用变体 → 切换，不新建
  └─ 需要构建
       ├─ SETNX 用户冷却
       ├─ 检查每小时配额
       ├─ COLD_START: 首访桶 → 共享桶
       ├─ REFILL: 共享桶
       ├─ 拿到令牌 → INCR 每小时计数 → 入队
       └─ 拒绝 → 缩短冷却到 2 秒，记录指标
```

---

## 4. 并发与故障

| 场景 | 结果 |
|---|---|
| 同一用户并发触发 | 冷却 key 的 `SETNX` 做用户级互斥，只有一个能进入 |
| 多个实例同时取令牌 | 令牌桶 Lua 原子执行，所有实例共享 |
| 被限流后重试 | 冷却缩短到 2 秒，且不消耗令牌/配额 |
| Redis 不可用 | `tryBuildPermit` 抛异常，`ensureBuild` 捕获并返回 false，Feed 继续用规则排序 |
| LLM 网关 Redis 不可用 | 本地冷却和本地 Semaphore 兜底；全局并发/冷却退化 |
| 实例崩溃留下租约 | 租约 TTL 到期后自动清理 |

---

## 5. 配置

| 配置 | 默认 | 说明 |
|---|---|---|
| `AI_FEED_BUILD_COOLDOWN` | `10s` | 每用户防连点 |
| `AI_FEED_BUILD_RATE_LIMIT` | `20` | 全局每分钟构建数 |
| `AI_FEED_BUILD_RESERVE_PERCENT` | `50` | 首访保底比例 |
| `AI_FEED_BUILD_PER_USER_HOURLY` | `6` | 每用户每小时构建数，0=不限 |
| `AI_FEED_BUILD_REJECT_COOLDOWN` | `2s` | 被拒后的短冷却 |
| `AI_FEED_LLM_GLOBAL_CONCURRENCY` | `0` | 全局在途 LLM 上限，0=关闭 |
| `AI_FEED_LLM_LEASE_TTL` | `60s` | 全局租约有效期 |
| `AI_FEED_LLM_COOLDOWN` | `30s` | 429 全局冷却 |

调参建议：

1. 先看 `sundry.feed.ai.build.permit` 的 `reason` 分布
2. 如果 `global_limit` 多、`COLD_START` 被拒多，先提高 `rate-limit`
3. 如果 `hourly_limit` 多，确认是异常用户还是正常“换一批”频率，再调 `per-user-hourly`
4. 单实例不需要开启 `feed-llm-global-concurrency`

---

## 6. 测试

- `AiFeedStorePoolTest`（14 个用例）
  - 冷却生效
  - 被拒绝不消耗令牌和每小时配额
  - `REFILL` 不能借用首访保底
  - 每小时配额只统计授予
- `AiLlmGateTest`（2 个）
  - 本地并发上限
  - 本地冷却
- `AiLlmGateIntegrationTest`（2 个）
  - Redis 全局租约跨实例互斥
  - 429 冷却跨实例可见
- `AiFeedIntegrationTest`（26 个）
- `FeedIntegrationTest`（8 个）
- 全量 Java：**375 个用例，0 失败 0 错误**
- 真实链路：4 个用户触发构建，全部 `SUCCESS`（`deepseek-chat`，1.7~3.1 秒），1 个 10% 对照组走 `RULE`；首访桶剩余约 6.15/10，每小时 key 正常
- 真实链路：每小时计数设为 6 时不再触发构建（`ai_feed_log` 3→3），清除计数和池后恢复（3→4）

---

## 7. 改动文件

- `src/main/java/com/sundry/ai/BuildReason.java`（新增）
- `src/main/java/com/sundry/ai/AiFeedStore.java`
- `src/main/java/com/sundry/ai/AiLlmGate.java`
- `src/main/java/com/sundry/ai/AiFeedClient.java`
- `src/main/java/com/sundry/ai/AiProperties.java`
- `src/main/java/com/sundry/feed/FeedService.java`
- `src/main/resources/application.yml`
- `src/test/java/com/sundry/ai/AiFeedStorePoolTest.java`
- `src/test/java/com/sundry/ai/AiLlmGateTest.java`
- `src/test/java/com/sundry/ai/AiLlmGateIntegrationTest.java`（新增）
- `DEVLOG.md`
