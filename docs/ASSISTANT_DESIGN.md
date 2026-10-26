# AI 助手框架（A4）设计文档

> 状态：**设计定稿；L0/L1/L2/L3 已完成，L4 待开发**
> 日期：2026-10-25
> 关联：`docs/NL2SQL_DESIGN.md`（A5）、`docs/BACKLOG.md` A4/A7、`docs/FUTURE_DESIGN.md` 第 4 节
> 用途：开发时按本文执行，避免重新推导

---

## 0. 一句话定位

站内助手：理解用户一句话，决定**跳哪个页面**、**调哪个工具**、还是**直接回答**，然后由 Java 校验并执行。

**模型只做「选择 + 填参」，不做「执行」。**

---

## 1. 核心原则

| # | 原则 | 反面做法 |
|---|---|---|
| 1 | 身份只来自 JWT，不信任模型传入的 userId | 用模型给的 userId 查数据 |
| 2 | 模型输出工具名 + 参数，不输出 URL / SQL / 表名 | 让模型拼路径或 SQL |
| 3 | 校验在 Java，按固定顺序，先校验后执行 | "先跑一下看看" |
| 4 | 工具清单按角色裁剪，不是全局下发 | 所有人拿到全部工具 |
| 5 | 规则优先，AI 兜底；AI 挂了导航仍可用 | 所有请求都走 LLM |
| 6 | 单次最多 1 个工具调用 | 允许模型串行调多个 |
| 7 | 全量审计，含拒绝原因 | 只记成功 |

---

## 2. 架构

```
浏览器
  │ ① POST /api/assistant/chat {sessionId, message}
  ▼
┌──────────────────── Java 8080 ────────────────────────────┐
│ ② JWT → userId / role                                     │
│ ③ 限流 + 长度校验 + 会话加载                               │
│ ④ 快路径：规则匹配路由目录（0ms，不依赖 AI）                │
│ ⑤ 未命中 → 调 Python（内部令牌 + traceId，超时 3s）         │
│ ⑥ 校验 tool_calls（顺序固定，见第 8 节）                    │
│ ⑦ 执行：navigate 无副作用 / 工具调业务服务 / NL2SQL 走 A5    │
│ ⑧ 审计 ai_chat_log                                        │
│ ⑨ 返回前端                                                 │
└──────────────────────────┬────────────────────────────────┘
                           │
                    ┌──────▼────────────┐
                    │ Python 8000        │
                    │ 意图 + 工具选择     │
                    │ 不碰 DB、不执行     │
                    └──────┬────────────┘
                           │
                    ┌──────▼────────────┐
                    │ DeepSeek API       │
                    └────────────────────┘
```

Java 与 Python 的边界沿用现有 `AiFeedClient` 的模式：内部令牌、traceId、超时、失败降级。

---

## 3. 三层能力

| 能力 | 触发 | 模型输出 | 风险 |
|---|---|---|---|
| **navigate** | "我的订单在哪" | `{route, query}` | 低，无副作用 |
| **固定工具** | "我最近买了什么" | `{tool, args}` | 低，只返回本人数据 |
| **NL2SQL** | "上个月卖了多少" | `{tool: query_sales, args:{question}}` | 中，走 A5 全链路 |

NL2SQL **不是独立系统**，它是工具注册表里的 `query_sales`，执行时进入 `NL2SQL_DESIGN.md` 定义的流程。

---

## 4. 路由目录（Route Catalog）

只登记**可导航子集**，不是全部路由。

```yaml
# src/main/resources/assistant/routes.yml
routes:
  feed:
    label: 首页推荐
    synonyms: [首页, 推荐, 猜你喜欢]
    name: feed
    requiresAuth: false
    requiresAdmin: false

  products:
    label: 商品列表
    synonyms: [所有商品, 商品, 全部商品, 逛逛]
    name: products
    requiresAuth: false

  cart:
    label: 购物车
    synonyms: [购物车, 我加的, 待付款]
    name: cart
    requiresAuth: true

  addresses:
    label: 收货地址
    synonyms: [地址, 我的地址, 收货信息]
    name: addresses
    requiresAuth: true

  orders:
    label: 我的订单
    synonyms: [订单, 我的订单, 购买记录, 我买的东西, 物流]
    name: orders
    requiresAuth: true
    # v1 不带状态筛选；等 OrdersView 支持 route.query.status 后再加参数

  account:
    label: 个人中心
    synonyms: [我的, 账号, 个人信息, 资料]
    name: account
    requiresAuth: true

  admin-orders:
    label: 订单管理
    synonyms: [后台订单, 全部订单, 管理订单]
    name: admin-orders
    requiresAuth: true
    requiresAdmin: true

  admin-products:
    label: 商品管理
    synonyms: [管理商品, 上架商品, 商品后台]
    name: admin-products
    requiresAuth: true
    requiresAdmin: true

  admin-seckill:
    label: 秒杀管理
    synonyms: [管理秒杀, 秒杀活动后台]
    name: admin-seckill
    requiresAuth: true
    requiresAdmin: true

  admin-feed:
    label: 推荐指标
    synonyms: [推荐数据, 推荐效果, CTR]
    name: admin-feed
    requiresAuth: true
    requiresAdmin: true
```

### 不登记的路由及原因

| 路由 | 原因 |
|---|---|
| `product` (`/products/:id`) | 需要真实商品 ID |
| `seckill-buy` (`/seckill/:activityId`) | 需要有效活动 ID 与资格 |
| `checkout` | 中途流程，直接进入缺上下文 |
| `login` / `register` | 无导航意义 |

### 使用 route name 而不是 path

前端跳转一律 `router.push({ name, query })`：

- name 不存在会报错，path 写错会静默 404
- 路由改名时只改 YAML 里的 `name`

---

## 5. 工具注册表（Tool Registry）

```java
public interface AssistantTool {
    String name();
    String description();
    Set<Role> roles();
    JsonNode parameters();                    // JSON Schema，下发给模型
    Object execute(Long userId, JsonNode args);
}
```

### 内置工具

| name | 角色 | 参数 | 副作用 |
|---|---|---|---|
| `navigate` | 全部 | `route`(enum)、`query`(受控 map) | 无 |
| `list_my_orders` | 全部 | `status`(enum，可选) | 读，本人数据 |
| `get_product_status` | ADMIN+ | `productId`(long) | 读 |
| `query_sales` | ADMIN+ | `question`(≤200 字) | 读，走 A5 |

### 新增工具的步骤

1. 实现 `AssistantTool`
2. 标注 `@Component`，自动进入注册表
3. 写参数 schema
4. 权限测试 + 参数校验测试

**入口代码一行不改。**

---

## 6. Tool Calling 协议

### 6.1 使用原生 tools 参数

采用 OpenAI 兼容的 `tools` 协议（DeepSeek 支持）。相比"prompt 里求它返回 JSON"，模型遵守率更高、解析成本更低。

工具定义**由 Java 生成并随请求下发**，Python 不硬编码：

```
Java 工具注册表（唯一事实来源）
   → 序列化 tools[]（不含 roles）
   → Python 原样传给 LLM
   → LLM 返回 tool_calls
   → Java 按注册表校验并执行
```

### 6.2 下发给模型的形状

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "navigate",
        "description": "帮用户打开本项目内的某个页面。只在用户问『在哪』『怎么去』『打开』时使用。",
        "parameters": {
          "type": "object",
          "properties": {
            "route": {
              "type": "string",
              "enum": ["feed", "products", "cart", "addresses", "orders", "account"]
            },
            "query": { "type": "object" }
          },
          "required": ["route"]
        }
      }
    },
    {
      "type": "function",
      "function": {
        "name": "list_my_orders",
        "description": "查询当前登录用户自己的订单。",
        "parameters": {
          "type": "object",
          "properties": {
            "status": {
              "type": "string",
              "enum": ["PENDING_PAYMENT", "PAID", "SHIPPED", "COMPLETED"]
            }
          }
        }
      }
    }
  ],
  "tool_choice": "auto",
  "temperature": 0
}
```

**`roles` 不下发。** 模型不需要知道权限，权限只在 Java 校验。

**route 枚举按角色动态生成**：USER 只看到 `feed/products/cart/addresses/orders/account`，管理员才会看到 `admin-*`。L1 只下发 `navigate` 一个工具；L2 再追加业务工具。

### 6.3 模型返回

```json
{
  "tool_calls": [{
    "id": "call_1",
    "function": {
      "name": "navigate",
      "arguments": "{\"route\":\"orders\"}"
    }
  }]
}
```

无工具调用时返回文本 → 视为 `answer`。

### 6.4 描述写法要求

「什么时候用」比「是什么」更重要：

```
❌ 打开页面
✅ 帮用户打开本项目内的某个页面。只在用户问『在哪』『怎么去』『打开』时使用。
```

能显著减少误选（例如"订单状态怎么改"不应触发跳转）。

---

## 7. 完整链路

| # | 谁 | 做什么 | 失败处理 |
|---|---|---|---|
| ① | 前端 | 发消息 `{sessionId, message}` | 网络错 → 提示重试 |
| ② | Java | 解析 JWT → userId/role | 401 |
| ③ | Java | 限流 10 次/分、message ≤ 500 字、加载会话 | 429 / 400 |
| ④ | Java | **快路径**：同义词匹配路由 | 未命中 → ⑤ |
| ⑤ | Java→Python | 下发 tools + 历史 + role | 超时 8s → 降级 |
| ⑥ | Java | 校验 tool_calls（第 8 节） | 拒绝并审计 |
| ⑦ | Java | 执行工具 | 业务异常 → 友好提示 |
| ⑧ | Java | 写 `ai_chat_log` | 审计失败只告警 |
| ⑨ | 前端 | 渲染回复；navigate 时跳转 | — |

### 快路径为什么在 Java

- 5ms vs 1000ms
- AI 服务挂掉，导航仍可用
- 覆盖大部分实际问法

```java
// 按同义词长度降序，避免"订单"抢先匹配"后台订单"
for (RouteDef r : catalog.visibleTo(role)) {
    if (r.synonyms().stream().anyMatch(message::contains)) {
        return AssistantResult.navigate(r);
    }
}
```

匹配规则（L0 必须实现并写负例测试）：

- 同义词按长度降序匹配，长词优先
- 只看当前角色可见的路由（USER 不会匹配 `后台订单`）
- 否定标记：问题里出现「怎么改 / 如何 / 为什么 / 能不能」等解释型意图，且没有「在哪 / 打开 / 去 / 进入」等导航动词时，不跳转
- 命中后返回 `navigate`，**不调用模型**；未命中才进入 AI 路径

---

## 8. 校验顺序（不可调整）

```
① tool_calls 数量 ≤ 1                       否 → MULTI_TOOL
② name 在注册表里                            否 → UNKNOWN_TOOL
③ 当前角色拥有该工具                         否 → FORBIDDEN_TOOL
④ arguments 是合法 JSON                      否 → INVALID_ARGS
⑤ 参数 key 全部在 schema 中（多余字段丢弃）    否 → 丢弃
⑥ 枚举值 / 范围合法                          否 → INVALID_ARGS
⑦ navigate：route 在可导航子集且角色可见      否 → FORBIDDEN_ROUTE
⑧ 执行
⑨ 审计（含拒绝原因）
```

**先校验，后执行。** 不存在"先跑一下看看"。

---

## 9. 角色裁剪

| 角色 | tools |
|---|---|
| 未登录 | 不开放助手 |
| USER | `navigate`(公开路由) + `list_my_orders` |
| ADMIN | 上述 + `get_product_status` + `query_sales` + 后台路由 |
| SUPER_ADMIN | 同 ADMIN |

裁剪是**纵深防御的第一层**：模型根本不知道存在 `query_sales`，无法误选；提示注入也只能在已有工具里选。

---

## 10. 失败与降级矩阵

| 故障 | 表现 | 兜底 |
|---|---|---|
| Python 不可用 | 规则能答的照答 | 其余「助手暂不可用」 |
| LLM 超时（8s） | 同上 | 不无限重试 |
| 模型返回不存在的工具 | 拒绝 | 「我暂时无法完成这个操作」 |
| 返回越权工具 | 403 | 审计 `FORBIDDEN_TOOL` |
| 返回不存在路由 | 拒绝 | 「我打不开这个页面」 |
| 工具执行异常 | 友好提示 | 审计 `TOOL_ERROR` |
| Redis 不可用 | 会话丢失 | 新会话，导航不受影响 |
| 模型答非所问 | 正常返回 | `answer` 不执行任何操作 |

---

## 11. 前端行为

```js
if (res.action === 'navigate') {
  showReply(res.reply)                      // 先显示回复
  showNavigateButton(res.route, res.query)  // 用户点击后再跳转
}
```

- **v1 不做自动跳转**：回复里带一个「去订单页」按钮，用户确认后 `router.push({ name, query })`
- 跳转只接受 `name`，不接受路径字符串
- `query` 只接受路由目录里声明的 key（v1 为空）
- 目标页加载失败时显示「页面不存在」而不是空白

---

## 12. 审计表

```sql
CREATE TABLE ai_chat_log (
  id             BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id        BIGINT UNSIGNED NOT NULL,
  role           VARCHAR(16)     NOT NULL,
  session_id     VARCHAR(64)     NULL,
  message        VARCHAR(500)    NOT NULL,
  source         VARCHAR(16)     NOT NULL,   -- RULE / LLM
  action         VARCHAR(16)     NOT NULL,   -- navigate / tool / answer / clarify
  tool_name      VARCHAR(64)     NULL,
  arguments      JSON            NULL,       -- 模型原始参数，用于调 prompt
  route          VARCHAR(64)     NULL,
  status         VARCHAR(20)     NOT NULL,   -- OK / REJECTED / DEGRADED / ERROR
  reject_reason  VARCHAR(200)    NULL,
  latency_ms     INT             NOT NULL DEFAULT 0,
  trace_id       VARCHAR(64)     NOT NULL,
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_user_time (user_id, created_at),
  KEY idx_status_time (status, created_at)
);
```

记录 `arguments` 原文是调 prompt 的唯一依据；不记录工具返回的业务数据。

**脱敏**：`message` 和 `arguments` 入库前把手机号、收货地址、订单号替换为掩码，避免审计表变成 PII 仓库。

---

## 13. Python 端职责

| 接口 | 输入 | 输出 |
|---|---|---|
| `POST /internal/assistant/chat` | message、history(3 轮)、tools[]、role | `tool_calls` 或文本 |
| `POST /internal/nl2sql/plan` | 见 `NL2SQL_DESIGN.md` | 查询计划 |

- 不持有 DB 凭据，不执行任何业务操作
- 不硬编码工具清单（由 Java 下发）
- 工具调用失败时不自行重试

---

## 14. 技术栈

| 层 | 复用 | 新增 |
|---|---|---|
| Java | Spring Boot / Security / Redis / MyBatis / Jackson | 无 |
| Python | FastAPI / Pydantic / openai SDK / pytest | 无 |
| 前端 | Vue3 + TS + Pinia + vue-router | 无 |

---

## 15. 实施切片

| # | 内容 | 验收 |
|---|---|---|
| L0 | 路由目录 + 规则匹配 + 前端跳转 + 审计 | 不依赖 AI 也能跳转；测试覆盖未登录/越权 |
| L1 | `/internal/assistant/chat` + tool 校验 + 降级 | AI 挂掉导航仍可用 |
| L2 | `list_my_orders` + `get_product_status` 两个固定工具 | 工具调用闭环，参数校验生效 |
| L3 | `query_sales` 接入 A5 | 管理员可做经营分析 |
| L4 | 多轮会话 + 参数补全（"已发货的那些"） | 指代消解正确 |

**L0 独立可演示，且是后续所有切片的地基。**

---

## 16. 明确不做

- 不做流式输出（先整段返回，SSE 留到需要时）
- 不做写操作（改地址、退款等需要二次确认，另立切片）
- 不让模型生成 URL / SQL
- 不做多工具串行编排
- 不接入 LangChain 等编排框架

---

## 17. 已确认决策（2026-10-25）

1. **入口**：全局右下角悬浮按钮，所有页面可用
2. **未登录**：不开放助手
3. **订单状态筛选**：v1 不传 `status`；等 `OrdersView` 支持 `route.query.status` 后再加
4. **跳转方式**：回复 + 用户点击按钮跳转，不做自动跳转
5. **会话**：L0~L3 单轮；L4 再接 Redis 多轮
6. **模型超时**：8 秒；规则快路径 0ms
7. **navigate 定位**：作为特殊 action，不实现成可执行工具，避免和路由目录两套事实源
8. **审计**：`message` / `arguments` 入库前脱敏手机号、地址、订单号
