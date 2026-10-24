# Sundry 后续功能设计（讨论稿）

> 状态：已讨论，未实现。
> 日期：2026-10-24
> 相关：`docs/BACKLOG.md`、`docs/INTERVIEW_SUMMARY.md`、`DEVLOG.md`

本文记录“怎么做”的结论，避免后续开发跑偏。实现时按切片推进，先方案后代码。

---

## 0. 总原则

- 一次一个切片，先方案、后代码、再测试和提交。
- Java 单体负责业务、认证、权限、工具执行、SQL 执行、审计。
- Python 只做 AI 编排，不是微服务，不直连业务库、不做写操作。
- 安全校验在服务端工具层，不依赖 prompt。
- 缓存是优化，不是正确性前提；交易判断永远以 DB/Redis 条件更新为准。

---

## 1. 滑动分页 / keyset

### 1.1 为什么不用 offset

`LIMIT offset, size` 在深页要扫过前面所有行，越翻越慢。
商品插入/删除还会导致页面整体错位、重复或漏项。

### 1.2 接口

```
GET /api/products?categoryId=&cursor=&size=20
```

响应：

```json
{
  "items": [...],
  "nextCursor": 123,
  "hasMore": true
}
```

- 不传 `cursor` 取第一页。
- `cursor` 用上一页最后一条的 `id`。
- 多查一条判断 `hasMore`，不额外 count。
- 保留 page/size 模式兼容旧调用；有 cursor 走 keyset。
- 后台商品列表继续用 page/size。

### 1.3 SQL 与索引

```sql
WHERE status = 'ON_SALE'
  AND deleted_at IS NULL
  AND id < :lastId
  AND (:categoryId IS NULL OR category_id = :categoryId)
ORDER BY id DESC
LIMIT :size + 1
```

索引：

```sql
(status, deleted_at, id)
(status, deleted_at, category_id, id)
```

按销量/价格排序时使用复合游标 `(sales, id)` 或 `(price, id)`，并配对应索引。

### 1.4 缓存

- 第一版：DB keyset + 现有商品卡片缓存，不改 Redis 结构。
- 有压力后：缓存前 3~5 屏的 ID 页
  ```
  cache:products:{version}:{categoryId}:cursor:{lastId}:{size}
  ```
- 缓存内容：`ids + nextCursor + hasMore`。
- 深层游标命中率低，直接查 DB 或极短 TTL，避免 Redis 被无限游标撑大。
- 商品新增/删除/上下架/换分类时版本号 +1。

### 1.5 前端

- `IntersectionObserver` 监听底部，提前预加载。
- 按商品 id 去重。
- 骨架屏、加载中、没有更多、失败重试。
- 返回页面时恢复滚动位置。

### 1.6 测试

- 第一页/第二页无重复、无遗漏。
- 分类筛选正确。
- `hasMore` 边界。
- 空结果。
- 后台分页不受影响。

---

## 2. 推荐 Feed（智能推流）

### 2.1 定义

商城首页的个性化商品流，不是分类列表。
滑动只是前端交互，核心是“推荐什么、按什么顺序”。

### 2.2 推 vs 拉

| | 推（写扩散） | 拉（读扩散） |
|---|---|---|
| 时机 | 发布时写入每个用户列表 | 读时现算 |
| 读 | 快 | 慢一些 |
| 写 | 贵 | 便宜 |
| 适合 | 粉丝少 | 关注多 |

单商户没有关注关系，采用**混合**：

```
离线/定时：候选池 + 规则/模型打分 → Redis
在线：读候选 → 过滤 → 重排 → 游标分页
```

### 2.3 数据来源

共享池（所有人可用）：

- 热销榜
- 新品榜
- 分类榜

个人池：

- 最近浏览
- 最近加购/收藏
- 购买过的同类
- 偏好分类
- 相似商品

### 2.4 用户偏好信号

| 信号 | 权重 |
|---|---|
| 购买 | 最高 |
| 加购 | 高 |
| 收藏 | 高 |
| 搜索词 | 中高 |
| 点击/停留 | 中 |
| 浏览分类/价格带 | 中 |
| 时间衰减 | 最近 7 天优先 |

### 2.5 评分（第一版规则）

```
score =
    0.35 × 分类偏好
  + 0.20 × 价格带匹配
  + 0.15 × 与最近浏览相似度
  + 0.15 × 热度
  + 0.10 × 新鲜度
  + 0.05 × 随机探索
  - 已购买 / 下架 / 缺货 / 黑名单
```

### 2.6 多样性与探索

- 一页 20 个，同一分类最多 6~8 个。
- 同一分类最多连续 2 个。
- 每 10 个放 1~2 个探索商品（新分类/新品）。
- 冷启动：热销 + 新品 + 分类混合。

### 2.7 Redis 结构

```
feed:hot                 ZSET  热销
feed:new                 ZSET  新品
feed:category:{id}       ZSET  分类榜
feed:user:{userId}       ZSET  个人候选，score=推荐分
```

- Feed 分页用 `score + id` 游标，不用 offset。
- 商品卡片继续复用详情缓存。

### 2.8 行为埋点

```
user_behavior(id, event_id, user_id, event_type, product_id, category_id, created_at)
event_type: VIEW / CLICK / CART / FAVORITE / ORDER / SEARCH
```

已实现的 F1 写入链路：

```
浏览请求
  → Redis 5 分钟去重
  → RPUSH behavior:queue
  → 每 2 秒批量落库（默认 500/批）
  → behavior:processing 失败重试
  → event_id + INSERT IGNORE 幂等
```

- 请求链路不直接写 MySQL，峰值只写 Redis。
- Redis 不可用时降级为直接写库。
- Feed 快照不因浏览立即失效；整页刷新时带 `refresh=true` 重算。
- 偏好计算窗口：
  - 最近 14 天、最近 100 次浏览
  - 最近 10 笔订单，购买权重 ×3
- 浏览明细保留 30 天，每天 4 点分批清理。

后续可扩展：

- CLICK / CART / FAVORITE / SEARCH 事件
- 停留时长、曝光、点击位置
- Kafka 批量消费替代定时 flush
- 离线评估和模型训练

### 2.9 评估

- 离线：命中率、NDCG、覆盖率、多样性。
- 在线：曝光、点击率、加购率（有流量再做 A/B）。
- 没有数据回流就不要声称“智能”。

### 2.10 切片

- F1：行为埋点 + 单路候选 + 规则打分 + 过滤/去重/多样性 + Redis 快照 + Feed 接口 + 首页无限滑动
- F2：个人候选（最近浏览/同分类/已购排除）
- F3：曝光点击回流 + 离线评估
- F4：Python 模型重排（可选）

### 2.11 召回层：多路召回与 RAG 的区别

推荐系统的标准四层：

```
1. 召回：候选商品
2. 过滤：下架、缺货、已购、重复、黑名单
3. 排序：规则/模型打分
4. 重排：多样性、探索、业务规则
```

RAG 也有类似结构（检索 → 过滤去重 → 重排 → 生成），但目标不同：

| | RAG | Feed |
|---|---|---|
| 目标 | 回答一个问题，准确、可引用 | 让用户继续看/点/买 |
| 召回对象 | 文档片段 | 商品 |
| 精准要求 | 高，要求相关性和忠实度 | 列表整体排序质量、覆盖率、多样性 |
| 输出 | 带引用的自然语言答案 | 商品列表 |
| LLM 角色 | 核心生成 | 通常不参与；最多离线重排/解释 |

**F1 不做多路召回。**

- 当前商品量小，一次取出已上架商品（上限 500）全量打分即可。
- 单路候选 + 过滤 + 打分 + 多样性重排，已经覆盖 F1 需求。
- 不为“显得高级”在几十个商品上硬造五路召回。

**什么时候再上多路召回：**

- 商品上万，不能全量打分
- 有行为数据，能做相似商品、协同过滤
- 延迟敏感，需要每路先取 TopN

届时再拆：

```
热销路 Top 50
新品路 Top 30
偏好分类路 Top 50
相似商品路 Top 30
协同过滤路 Top 30
    ↓ 合并去重
    ↓ 统一打分
    ↓ 多样性重排
```

简历上也应如实写：F1 是“单路候选 + 规则排序 + 多样性重排”；上了多路召回再写多路召回。

---

## 3. 公告与已读

### 3.1 模型

```sql
announcement(
  id, title, content, status, published_at, created_by,
  created_at, updated_at
)

announcement_read(
  announcement_id, user_id, read_at,
  UNIQUE(announcement_id, user_id)
)
```

### 3.2 读扩散，不做写扩散

- 管理员发布只写 1 行公告。
- 不给每个用户写一条通知。
- 未读数 = 已发布公告数 − 用户已读记录数。
- 已读回执：用户打开公告时插入一条 read 记录，`INSERT IGNORE` 幂等。

### 3.3 权限

| 角色 | 能力 |
|---|---|
| USER | 看已发布公告、标记自己的已读、看自己的未读数 |
| ADMIN | 发布/下架、看阅读统计（总数/已读人数） |
| SUPER_ADMIN | 同上 + 查看具体已读用户 |

普通用户不能查看“谁读了”。

### 3.4 实时性

- 默认轮询未读数。
- 需要实时红点再上 SSE/WebSocket。

### 3.5 接口

```
GET  /api/announcements
GET  /api/announcements/{id}
POST /api/announcements/{id}/read
GET  /api/announcements/unread-count

GET/POST/PUT/DELETE /api/admin/announcements
GET  /api/admin/announcements/{id}/readers
```

### 3.6 测试

- 未读数正确。
- 重复已读幂等。
- 普通用户不能看阅读明细。
- 下架公告不出现在用户列表。
- 并发已读不重复。

---

## 4. AI 助手

### 4.1 架构

```
Vue → Java（认证、权限、工具执行、审计）
          │ 内部 HTTP + 内部令牌 + traceId
          ▼
     Python AI 服务（意图识别、LLM 编排、查询计划）
          │
          ▼
        LLM API
```

- 浏览器只访问 Java。
- Python 不直连业务库、不持有 DB 凭据、不做写操作。
- LLM key 只放在 Python。
- 部署是两个进程，不是微服务：无注册中心、无网关、无 Spring Cloud。

### 4.2 职责边界

| Java | Python |
|---|---|
| JWT/RBAC | 意图识别 |
| 工具白名单与执行 | LLM 编排 |
| 数据查询与行级过滤 | NL2SQL 查询计划 |
| 结果校验 | 推荐离线计算 |
| 审计与限流 | 红队评测 |

### 4.3 调用流程

1. 用户发消息。
2. Java 校验 JWT、限流，组装脱敏上下文。
3. 调 Python `/internal/assistant/chat`。
4. Python 返回结构化结果：`intent`、`toolCalls`、`answer`。
5. Java 校验工具/参数/权限，执行工具。
6. 需要自然语言总结时，把工具结果脱敏后交 Python。
7. Java 返回前端。

### 4.4 内部接口

```
POST /internal/assistant/chat
POST /internal/assistant/intent
POST /internal/nl2sql/plan
POST /internal/recommend/rerank
GET  /health
```

请求头：

```
X-Internal-Token: <shared secret>
X-Trace-Id: <Java 生成>
X-User-Id / X-User-Role: Java 校验后传入
```

### 4.5 页面跳转

只返回结构化结果：

```json
{ "action": "navigate", "route": "orders", "params": { "status": "PAID" } }
```

- 服务端维护路由白名单。
- 前端只接受白名单路由。
- 绝不直接使用模型返回的 URL。
- 用户无权限的页面直接拒绝。

### 4.6 失败处理

- Java 侧 3 秒超时 + 熔断。
- Python 挂了：助手降级，商城业务不受影响。
- 不做无限重试。

### 4.7 审计

记录：用户、问题、意图、工具、参数摘要、返回行数、耗时、拒绝原因。
不记录敏感原文，或脱敏后记录。

---

## 5. NL2SQL 安全

### 5.1 范围

只对 ADMIN / SUPER_ADMIN 开放。普通用户走固定工具，不开放裸 SQL。

### 5.2 不是让模型自由写 SQL

1. 建语义层：指标、维度、受限视图。
2. 模型只输出“查什么指标/维度/时间范围”。
3. Java 用受限查询构建器生成 SQL。
4. 必须校验后再执行。

### 5.3 防护管线

```
只读数据库账号
  → 只允许单条 SELECT
  → 表白名单 / 列白名单
  → 强制追加数据范围条件（角色/用户）
  → 禁止 information_schema、系统函数、SLEEP、文件读写
  → 最大返回行数 + 查询超时
  → SQL 解析器校验（JSqlParser/Calcite）
  → 审计：谁、问什么、生成什么 SQL、返回多少行
```

### 5.4 权限矩阵

| 能力 | USER | ADMIN | SUPER_ADMIN |
|---|---|---|---|
| 查自己的订单/地址 | ✅ | ✅ | ✅ |
| 查全站订单 | ❌ | ✅ | ✅ |
| 看公告阅读人数 | ❌ | ✅ | ✅ |
| 看具体谁已读 | ❌ | ❌ | ✅ |
| NL2SQL 经营分析 | ❌ | 受限视图 | ✅ |
| 修改商品/活动 | ❌ | ✅ | ✅ |

### 5.5 安全风险

| 风险 | 防护 |
|---|---|
| 越权查询 | 工具/查询层强制 userId/角色条件 |
| 提示注入 | 用户内容只作为数据；工具白名单；输出结构化 |
| SQL 注入 | 只读账号 + 解析器 + 白名单 + 参数化 |
| 数据外泄 | 发送模型前脱敏；最小上下文 |
| 任意跳转 | route 白名单 |
| 成本滥用 | 用户限流、每日配额、超时 |

### 5.6 红队评测

覆盖：

- 跨用户越权查询
- 提示注入（“忽略之前指令”）
- SQL 注入 / UNION / 系统表
- 任意 URL 跳转
- 角色伪造
- 聚合推断

要求：拦截率 100%，形成测试报告。

---

## 6. 卡密与软件授权

已设计，暂不排期，详见 `docs/BACKLOG.md` 第九节。

摘要：

- 平台发下载链接 + 卡密，软件内输入卡密激活。
- 一单一码，卡密不写进下载文件。
- 激活 / 心跳 / 解绑接口。
- 设备绑定、席位限制、退款吊销。
- 数据表：`license_key`、`license_activation`、`license_event`。
- 彻底防破解做不到，目标是提高成本、可追溯、可吊销。

---

## 7. 开发顺序

1. **滑动分页 / keyset**（先做，不依赖 AI）
2. A1 公告 + 已读 + 未读数
3. A4 助手框架 + 页面跳转白名单
4. A5 NL2SQL 安全
5. A7 AI 安全评测集
6. A3 推荐 Feed（F1 → F2 → F3）
7. P11/P12 E2E 与普通链路压测
8. 上线前：真实短信/支付、HTTPS、静态托管、CI、备份

---

## 8. 验收口径

- 接口 + 前端 + 权限 + 校验
- 正向、越权、并发/边界测试
- 缓存变更能失效
- DEVLOG 有记录
- 测试全绿、前端构建通过
- 提交并同步远端

---

## 9. 明确不做

- 多商户 / 多租户
- 微服务、注册中心、网关、Spring Cloud
- 完整 SKU（除非开始卖多规格实物）
- 数字商品多版本（先一版本一商品）
- 秒杀库存分桶（除非压测出现瓶颈）
- 优惠券/积分体系（除非运营明确需要）
