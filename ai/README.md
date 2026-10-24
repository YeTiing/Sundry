# Sundry AI 服务

只做 AI 编排：不连接业务库、不持有用户 JWT、不做写操作。

## 启动

```bash
cd ai
cp .env.example .env   # 填入 AI_INTERNAL_TOKEN 和 DEEPSEEK_API_KEY
uv sync
uv run --env-file .env uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Java 侧对应配置（环境变量）：

```
AI_BASE_URL=http://127.0.0.1:8000
AI_INTERNAL_TOKEN=<与 .env 中一致>
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查，不返回密钥 |
| POST | `/internal/feed/rerank` | 对 Java 传来的候选商品重排，需要 `X-Internal-Token` |

## 测试

```bash
cd ai
uv run pytest -q
```

测试使用假 LLM，不需要真实密钥，也不会调用 DeepSeek。
