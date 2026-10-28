# 压测

使用 [k6](https://k6.io/) 对本机服务做冒烟压力测试。

## 1. 准备环境

```bash
docker start sundry-redis sundry-minio
docker compose -f docker-compose.kafka.yml up -d
# MySQL 本机 3306
```

后端使用 `local` profile 启动。

## 2. 一键准备活动和用户

```bash
USERS=200 STOCK=50 \
LOG_FILE="C:\\Users\\Lenovo\\AppData\\Local\\Temp\\sundry-backend.log" \
node loadtest/prepare.mjs
```

脚本会：

- 用超管账号 `13800138000/secret123` 登录
- 上传一个 1KB 数字文件，创建数字商品
- 创建并启用一个 50 库存的秒杀活动
- 注册 1 个用户，复制其 BCrypt 哈希，批量插入 199 个测试用户（手机号前缀 `1370000`）
- 逐个登录，把 token 写到 `loadtest/tokens.txt`（已 gitignore）
- 活动 ID 写到 `loadtest/activity.txt`

依赖：本机 `mysql` 命令、后端日志可读、开发短信仍在日志里输出。

## 3. 浏览压测（缓存）

```bash
k6 run \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e VUS=100 \
  -e DURATION=30s \
  loadtest/browse.js
```

重点看 `http_req_duration` 和 `http_req_failed`，以及 Redis 中 `cache:*` 的 key 数量。

## 4. 秒杀压测

```bash
ACTIVITY_ID=$(cat loadtest/activity.txt) \
k6 run \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e ACTIVITY_ID=$ACTIVITY_ID \
  -e VUS=200 \
  -e ITERATIONS=200 \
  loadtest/seckill.js
```

脚本自动读取 `loadtest/tokens.txt`，每个 VU 固定一个 token，避免同一用户被限购拦截。
409 售罄和 429 限流是**预期结果**，脚本用 `seckill_unexpected_status == 0` 作为通过条件，不把 409 计为失败。
默认限流是每用户每秒 5 次，压测时尽量让 token 数量接近 VUS。

## 4.1 助手压测

助手默认每用户 10 次/分钟；要压 Bulkhead 而不是限流，启动后端时把限流调高：

```bash
ASSISTANT_RATE_LIMIT=100000 \
AI_BASE_URL=http://127.0.0.1:8000 \
java -jar target/sundry-0.0.1-SNAPSHOT.jar
```

```bash
k6 run \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e VUS=100 \
  -e START_VUS=10 \
  -e HOLD=30s \
  -e MODEL_PERCENT=30 \
  loadtest/assistant.js
```

脚本从 `tokens.txt` 取 token，按 VU 轮换；70% 走规则快路径，30% 走模型路径。

关注：

- `http_req_duration` 的 p95 / p99
- `assistant_ok` / `assistant_busy` / `assistant_degraded` / `assistant_rate_limited` / `assistant_error`
- `sundry_assistant_bulkhead{result="ok|busy|error|cancelled"}`
- `sundry_assistant_bulkhead_active`、`sundry_assistant_bulkhead_queued`

通过标准：

- `assistant_error == 0`
- p95 < 4s，p99 < 4.5s（用户等待上限 4s）
- `busy` / `degraded` 有明确原因（队列满、模型不可用、429 冷却）
- 压测结束后 Tomcat 活跃线程回落到正常水平，说明异步返回没有占住线程

AI 服务未启动时，助手会快速降级，只能验证保护链路，不能验证真实模型吞吐。

### 本机实测（2026-10-28）

k6 v1.8.2，单机后端，助手 Bulkhead 默认 32 并发 / 200 队列 / 4s 等待上限：

| 场景 | 请求数 | p95 | p99 | 结果 |
|---|---:|---:|---:|---|
| 规则路径，100 VU | 19,751 | 7.04ms | 13.44ms | 0 error |
| 模型路径（2s 桩），20 VU | 131 | 2.02s | 2.16s | 0 error |
| 模型路径（2s 桩），100 VU | 595 | 4.81s | 4.87s | 498 busy、0 error |

100 VU + 2s 模型延迟时，32 并发只能提供约 16 req/s，而 100 VU 需求约 50 req/s，队列必然打满。
这是 Bulkhead 按预期保护系统，不是 5xx；要提高吞吐先调 `ASSISTANT_CONCURRENCY`，并确认上游模型容量。

## 5. 结果校验

活动库存为 `stock`，预期：

```sql
SELECT stock, sold_count FROM seckill_activity WHERE id = ?;
-- sold_count <= stock

SELECT COUNT(*) FROM orders
WHERE activity_id = ? AND status NOT IN ('CANCELLED', 'REFUNDED');
-- 有效订单数 = sold_count，且每个用户最多一单
```

Redis：

```bash
GET seckill:stock:{activityId}       # 应等于 stock - sold_count
HLEN seckill:buyer:{activityId}      # 购买用户数
```

指标：

```bash
curl -s http://127.0.0.1:8080/actuator/prometheus | grep sundry_seckill
```

关注：

- `sundry_seckill_submit_total{result="accepted"}`
- `sundry_seckill_submit_total{result="SECKILL_SOLD_OUT"}`
- `sundry_seckill_submit_total{result="RATE_LIMITED"}`
- `sundry_seckill_order_total{result="success"}`
- `sundry_seckill_compensation_total`
- `sundry_seckill_submit_duration_seconds`

## 6. 通过标准

- 没有超卖：`sold_count <= stock`
- 没有重复订单：同一 `activity_id + user_id` 只有一行
- 有效订单数、`sold_count`、Redis 剩余库存三者一致
- `sundry_seckill_compensation_total` 为 0，或每次补偿都能在日志里解释

## 7. 本机实测（2026-10-24）

环境：Windows 11、单机 MySQL 8.0 / Redis / MinIO / Kafka（Docker）、Spring Boot 本地进程。

场景：200 个用户并发抢 50 件数字商品，每人 1 次。

第二轮（Kafka producer 已预热）：

```text
200 requests, 50 accepted, 150 sold out
p99 = 139ms
unexpected status = 0
吞吐约 2000 req/s（本机短时突发）
```

一致性校验：

```text
seckill_activity: stock=50, sold_count=50
orders: 50 行，50 个不同 user_id
redis seckill:stock = 0
redis seckill:buyer HLEN = 50
sundry_seckill_compensation_total = 0
```

第一轮 p99 约 1.16s，原因是 Kafka producer 首次初始化；预热后恢复正常。
