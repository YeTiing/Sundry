# AI 推流变体池（方案 B）设计文档

> 状态：待评审
> 日期：2026-10-25
> 关联：`docs/BACKLOG.md` A3、`DEVLOG.md` 第 1792 行「下一步」

---

## 1. 要解决的问题

当前「换一批」是同步等模型：

1. 用户点击 → 后端入队 → 立即返回**旧快照**或规则页（`PENDING`）
2. 前端轮询 10 次 × 1.5 秒
3. 后台调 DeepSeek 重排，实测 **4~11 秒**
4. 算完写回同一份快照，前端下一次轮询才替换列表

用户体感：点完先没反应，几秒后列表才「自己跳一下」。

**方案 B 的目标：点击到列表变化，0 等待。**

---

## 2. 目标 / 非目标

### 目标

- 「换一批」同步返回，不依赖 LLM 实时响应
- 连续快速点击不阻塞、不重复入队、不返回同一批商品
- 任何异常（LLM 挂、Redis 挂、池子空）都降级到规则页，**永不阻塞用户**
- 埋点能区分不同变体，为后续 A/B 评估留数据

### 非目标

- 不做在线学习 / 排序模型（资源不够）
- 不改召回算法（多路召回 + Milvus 语义召回保持不变）
- 不做实验分流平台（不做用户级 A/B 分组，只做变体轮转）

---

## 3. 现状（改动前）

| 组件 | 位置 | 行为 |
|---|---|---|
| 读取入口 | `FeedService.feed` → `aiPage` | refresh 时入队，读单份快照 |
| 状态存储 | `AiFeedStore` | 单快照 `feed:ai:snapshot:{uid}` |
| 后台任务 | `AiFeedBuildJob` | 每 1 秒消费队列一批 |
| 构建 | `AiFeedBuildProcessor` | 召回 → 规则排序 → 取 60 候选 → 调 LLM → 合并 → 覆盖快照 |
| 前端 | `FeedView.refreshWithAi` | 轮询 10 × 1.5 秒 |

Redis 键：

```
feed:ai:queue            LIST   待处理用户
feed:ai:processing       LIST   处理中
feed:ai:pending:{uid}    STR    buildId，标识"正在构建"
feed:ai:queued:{uid}     STR    SETNX，防重复入队
feed:ai:snapshot:{uid}   STR    单份榜单（TTL 10 分钟）
feed:ai:lock:{uid}       STR    构建锁（60 秒）
feed:ai:attempt:{uid}    STR    失败计数（5 分钟）
```

---

## 4. 设计

### 4.1 总体流程

```
用户浏览时（后台静默）
   召回一次 → 规则排序 → 候选 60
   → 并发调 LLM 生成 N 份变体（N=2）
   → 变体间去重 → 写入环形池

用户点「换一批」
   Lua 原子切换：LPOP 池头 + RPUSH 回池尾
   → 同步返回该变体（毫秒级）
   → 后台检查水位，不足则补算
```

核心思想：**LLM 提前回答「用户下一步可能想看什么」，而不是响应用户刚点的那一下。**

### 4.2 Redis 数据结构

```
feed:ai:pool:{uid}       LIST  变体池（当前生效），每个元素是一份变体 JSON，环形使用
feed:ai:pool:next:{uid}  LIST  待上架区：新构建结果先放这里，下次点击时提升为正式池
feed:ai:pool:current:{uid} STR 当前展示的变体 buildId，无 buildId 的请求按它返回同一份
feed:ai:variant:view:{uid}:{buildId} STR 曝光下沉后的变体快照（保证翻页顺序一致）
feed:ai:pool:seen:{uid}  ZSET  已曝光商品 → 时间戳（去重降权用，TTL 1h）
feed:ai:pending:{uid}    STR   buildId，语义保留（首次构建中）
feed:ai:queued:{uid}     STR   保留，防重复入队
feed:ai:lock:{uid}       STR   保留，构建锁
feed:ai:attempt:{uid}    STR   保留，失败计数
```

**为什么需要 `pool:next`：** 如果构建完成直接覆盖正式池，用户正在阅读的变体会消失，下一次翻页会因 `buildId` 对不上而跳回第一页——用户没点任何东西，列表却自己变了。因此构建结果只写待上架区，只有用户下一次点击时才原子替换。

变体 JSON：

```json
{
  "buildId": "PREF:550e8400-e29b-41d4-a716-446655440000",
  "strategy": "PREF",
  "builtAt": "2026-10-25T16:30:00",
  "model": "deepseek-v4-flash",
  "promptVersion": "feed_rerank_v2",
  "items": [
    { "id": 101, "reason": "你常看的户外类目" },
    { "id": 205, "reason": null }
  ]
}
```

**buildId 格式：`{STRATEGY}:{UUID}`**。`strategy` 直接编码进去，后续评估按前缀分组，**不需要给 `feed_event` 加列**（现有 `build_id varchar(64)` 够用）。

### 4.3 变体生成

| 策略码 | 排序偏好 |
|---|---|
| `PREF` | 标准偏好：分类 + 标签 + 最近浏览 |
| `EXPLORE` | 探索：压低头部类目，抬高冷门分类与新品 |

**执行方式：**

1. 召回只跑一次（五路 + Milvus 是最贵的步骤）
2. 规则打分得到基准顺序 `ranked`
3. N 个变体**并发**调 LLM（串行会变成 12~33 秒）
4. 每个变体独立走现有 `merge` 校验：id 必须在候选内、去重、截断到 `feed-rerank-limit`
5. **变体间去重**：第 k 份变体的前 6 个商品，不能与第 1..k-1 份的前 6 个重复；冲突项与规则顺序中后续候选交换
6. 单个变体超时（`feed-variant-deadline`，默认 12 秒）或失败 → 该份用规则顺序补齐，标记 `model=rule-fallback`，整批仍可写入

Prompt 侧只加一段「本轮排序偏好」，仍禁止编造 id、仍只输出 JSON。

### 4.4 切换（原子）

点击时先检查待上架区：有内容则原子提升为正式池，再切换。保证「用户不点，列表就不变」。

```lua
-- 提升：KEYS[1] = pool:next, KEYS[2] = pool
if redis.call('LLEN', KEYS[1]) > 0 then
  redis.call('DEL', KEYS[2])
  redis.call('RENAME', KEYS[1], KEYS[2])
  return 1
end
return 0
```

随后原子切换：

```lua
-- KEYS[1] = feed:ai:pool:{uid}
local v = redis.call('LPOP', KEYS[1])
if v then
  redis.call('RPUSH', KEYS[1], v)
end
return v
```

- 拿到 `nil` → 池空 → 走 4.6 降级
- 拿到变体后，把该变体的商品 id 写入 `pool:seen`（`ZADD`，score 为当前毫秒）
- 变体 `builtAt` 超过 `feed-variant-ttl`（默认 20 分钟）→ 视为不可用，直接从池中移除并继续取下一份

### 4.5 补算触发点

环形池长度不会随切换下降，因此不能用 `LLEN(pool)` 判断水位。实际触发点：

| 触发点 | 条件 |
|---|---|
| 首次访问 | 正式池为空且待上架区为空 |
| 切满一轮 | 本轮切换次数 ≥ 当前变体数 |
| 可用变体不足 | 正式池中未过期变体数 < `feed-variant-count` |
| 变体全部过期 | 切换时全部被剔除 |
| 失败熔断 | 连续失败达 `max-attempts` 后停止，等下一次触发 |

所有补算都走现有 `feed:ai:queued:{uid}` SETNX，同一用户同时只有一个在途构建。构建结果写入 `pool:next`，不碰正式池。

### 4.6 池空 / 过期降级

| 情况 | 返回 | aiStatus |
|---|---|---|
| 池中有可用变体 | 该变体 | `READY` |
| 首次访问，池为空 | 规则排序页 | `PENDING` |
| 变体全部过期且补算未完成 | 规则排序页 | `PENDING` |
| 构建失败达上限 | 规则排序页 | `FALLBACK` |
| AI 关闭 / 未登录 | 规则排序页 | `DISABLED` |

前端**只在 `PENDING` 时轮询**（首次构建仍需要），`READY`/`FALLBACK`/`DISABLED` 不轮询。

### 4.7 翻页一致性

游标基于变体的 `buildId`。若翻页请求带 `buildId` 与当前游标所属变体不一致，**丢弃游标、从第一页重新加载**（现有 `FeedView.loadMore` 已实现该逻辑，判断依据沿用 `aiBuildId`）。

服务端同理：`FeedService` 收到的 `buildId` 参数若与池头变体不一致，按新变体重算游标。

首屏请求（`offset=0` 且不带 `buildId`，即重新打开网站或 F5）视为一次「换一批」：有待上架新鲜批次先提升，否则切到池中下一份，0 等待。

不带 `buildId` 但带游标的翻页请求读取 `pool:current`，保持当前变体，避免翻页中途换榜。

### 4.8 已曝光降权

环形池意味着用户第 3 次点击会看到第 1 份变体。为避免「完全一样」，切换后做一次轻量后处理：

- 读取 `pool:seen` 中最近 10 分钟内曝光过的商品
- 位于变体前 8 名且已曝光的，下沉到该变体的第 12~20 位
- 不删除（保证列表长度不变），只调整
- 零 DB 开销，纯 Redis
- 下沉后的顺序写入 `feed:ai:variant:view:{uid}:{buildId}`（TTL 与变体一致）；翻页读同一份，避免“第一页下沉、第二页没下沉”

### 4.9 多实例并发

- 切换：Lua 原子操作
- 构建：现有 `feed:ai:lock:{uid}` 保证同一用户单线程构建
- 补算入队：现有 `feed:ai:queued:{uid}` SETNX 保证只有一个在途任务

---

## 5. 接口契约

**`GET /api/feed` 响应结构不变**，仅语义变化：

| 字段 | 改动前 | 改动后 |
|---|---|---|
| `aiStatus` | DISABLED/PENDING/READY/FALLBACK | 不变 |
| `aiBuildId` | 轮询目标 buildId | `READY` 时为当前变体 buildId；`PENDING` 时为 null |
| `feedBuildId` | 当前快照 buildId | 当前变体 buildId |

前端 `FeedEventPayload.buildId` 直接复用为 `variantId`，**事件接口零改动**。

前端唯一改动：`refreshWithAi` 只在 `aiStatus === 'PENDING'` 时轮询。

---

## 6. 新增配置

```yaml
app:
  ai:
    feed-variant-count: ${AI_FEED_VARIANT_COUNT:2}          # 2~3
    feed-variant-ttl: ${AI_FEED_VARIANT_TTL:20m}            # 变体最大年龄
    feed-variant-dedup-head: ${AI_FEED_VARIANT_DEDUP_HEAD:6} # 变体间头部去重数量
    feed-variant-deadline: ${AI_FEED_VARIANT_DEADLINE:12s}  # 单次构建等待模型的上限
    feed-build-cooldown: ${AI_FEED_BUILD_COOLDOWN:10s}      # 每用户构建冷却
    feed-build-rate-limit: ${AI_FEED_BUILD_RATE_LIMIT:100}  # 全站每分钟构建上限
```

`feed-variant-count=1` 时等价于现状逻辑（便于灰度回滚）。

---

## 7. 失败与边界矩阵

| 场景 | 行为 | 用户感受 |
|---|---|---|
| LLM 超时 | 该批变体不写入，重试至上限后 `FALLBACK` | 列表停在规则排序 |
| 部分变体成功 | 成功的写入池子（≥1 份即可用） | 正常 |
| Redis 读失败 | 规则页 | 正常浏览 |
| 池中变体全部过期 | 规则页 + 入队补算 | 短暂回到规则排序 |
| 用户连点 5 次 | 指针切 5 次（环形复用），只有 1 个在途构建 | 每次都变，后几次是复用 |
| 构建期间再次点击 | 切换旧变体，不取消在途构建 | 正常 |
| 商品下架 | `hydrate` 返回 null，列表中跳过（现有逻辑） | 正常 |

---

## 8. 测试计划

### 单元 / 集成（Java）

1. `AiFeedStore` 环形切换：连续 N+1 次拿到不同变体，第 N+1 次回到第 1 份
2. 过期变体被跳过并移除
3. 水位低于阈值时只入队一次（并发点击不重复）
4. 变体间头部去重：前 6 名无交集
5. 池空 → 规则页 + `PENDING`
6. 构建失败 → `FALLBACK`，池子状态不变
7. 多线程并发切换不重复消费同一份（Lua 原子性）

### 前端

8. `READY` 时不发轮询请求
9. 首次 `PENDING` 仍轮询并正常替换

### Python

10. 新 prompt 段落下模型仍只返回合法 id（现有校验测试复用）

---

## 9. 实施切片

| # | 内容 | 验收 |
|---|---|---|
| 1 | `AiFeedStore` 池结构 + Lua 切换 + 单元测试 | 不改行为，测试全绿 |
| 2 | 构建端产出 N 个变体 + 并发调用 + 头部去重 | 池中 N 份，前 6 名无交集 |
| 3 | `FeedService` 读取路径切到池子 + 池空降级 | 点击 0 等待 |
| 4 | 曝光降权 + 前端删轮询 + 埋点对接 | 连点 5 次每次都有变化 |
| 5 | 指标按 `build_id` 前缀分组 | 管理端可看各策略 CTR |

每个切片独立提交，跑全量测试后更新 `DEVLOG.md`。

---

## 10. 成本与风险

| 项 | 影响 | 应对 |
|---|---|---|
| LLM 调用 ×N | token 成本 ×2~3 | 默认 2 份；`feed-variant-count=1` 可回滚 |
| 召回不变 | 无额外 DB 压力 | 召回只跑一次 |
| Redis 内存 | 每活跃用户 N 份 × 约 2KB | 可忽略；TTL 20 分钟自动回收 |
| 变体内容重复 | 用户觉得「没换」 | 构建期头部去重 + 运行期曝光降权 |
| 榜单滞后 | 新品上架不立刻体现 | TTL 20 分钟可接受；商品变更事件已触发向量重算 |
| 首次访问仍要等 | 首屏无法避免 | 保持规则页 + 轮询，体验与现在一致 |

---

## 11. 待确认

1. **变体数默认 2 还是 3？** —— 建议 2，靠快速补算兜底
2. **三套策略是否合适？** —— `PREF` / `EXPLORE` / `PRICE`
3. **变体 TTL 20 分钟**是否可接受（现在快照是 10 分钟）
4. **复用旧变体时是否需要更强过滤**（直接剔除已曝光商品，而不是下沉）
5. **是否保留首次访问的轮询**（建议保留）

---

## 12. 已确认决策（2026-10-25）

- 变体数 2；策略 `PREF` / `EXPLORE`（`PRICE` 已去掉）；变体 TTL 20 分钟
- 防刷：每用户构建冷却 10 秒 + 全站每分钟 100 次；只拦构建，不拦切换
- 模型超时/失败：单份用规则顺序补齐，标记 `rule-fallback`，页面状态 `FALLBACK`
- 复用旧变体时已曝光商品下沉到 12~20 位，不删除
- 保留首次访问轮询（仅 `PENDING` 时）
- 补算触发点：首次访问、切满一轮、可用变体不足、全部过期、失败熔断
- 新构建结果先写 `pool:next`，用户下一次点击时原子提升为正式池；用户不点击则页面不变
- 重新打开网站 / F5（无 buildId 的首屏请求）视为一次换一批：优先使用待上架新批次，否则切池中下一份；站内返回首页不触发
