# 压测

使用 [k6](https://k6.io/) 对本机服务做冒烟压力测试。

## 1. 准备环境

```bash
docker start sundry-redis sundry-minio
docker compose -f docker-compose.kafka.yml up -d
# MySQL 本机 3306
```

后端使用 `local` profile 启动。

## 2. 准备秒杀活动

在后台 `/admin/seckill` 创建一个**数字商品**活动并启用，记下活动 ID。
数字商品不需要收货地址，压测脚本只传 `activityId`。

为了避免“一人限购”干扰压测，需要准备一批不同的用户 token：

1. 用不同手机号注册一批账号（开发环境验证码看后端日志 `[dev-sms] phone=... code=xxxxxx`）。
2. 从注册/登录接口响应里取出 `accessToken`。
3. 用逗号拼成 `TOKENS` 环境变量。

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
k6 run \
  -e BASE_URL=http://127.0.0.1:8080 \
  -e ACTIVITY_ID=1 \
  -e TOKENS="token1,token2,token3" \
  -e VUS=100 \
  -e ITERATIONS=1000 \
  loadtest/seckill.js
```

每个 VU 固定使用一个 token，避免同一用户被限购拦截。
默认限流是每用户每秒 5 次，压测时尽量让 `TOKENS` 数量接近 `VUS`。

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
