# Sundry AI 服务

只做 AI 编排：不连接业务库、不持有用户 JWT、不做写操作。

## 启动

```bash
cd ai
cp .env.example .env   # 填入 AI_INTERNAL_TOKEN 和 DEEPSEEK_API_KEY
uv sync
uv run --env-file .env uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 4
```

`--workers` 按机器核数调整，建议 2~4；单进程会把所有模型调用压在一个事件循环里。
助手超时通过 `AI_ASSISTANT_TIMEOUT_SECONDS` 单独控制，默认 3 秒，必须小于 Java 的 `app.assistant.wait-timeout`。

Java 侧对应配置（环境变量）：

```
AI_BASE_URL=http://127.0.0.1:8000
AI_INTERNAL_TOKEN=<与 .env 中一致>
```

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查，不返回密钥 |
| POST | `/internal/assistant/chat` | 助手选工具，返回 tool_calls，需要 `X-Internal-Token` |
| POST | `/internal/nl2sql/plan` | 自然语言转查询计划，需要 `X-Internal-Token` |
| POST | `/internal/feed/rerank` | 对 Java 传来的候选商品重排，需要 `X-Internal-Token` |

## 测试

```bash
cd ai
uv run pytest -q
```

测试使用假 LLM，不需要真实密钥，也不会调用 DeepSeek。
