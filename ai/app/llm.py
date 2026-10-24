import logging
from typing import Protocol

from openai import AsyncOpenAI

from app.config import Settings, get_settings

logger = logging.getLogger("sundry.ai")


class LlmClient(Protocol):
    async def complete(self, system: str, user: str) -> str: ...


class DeepSeekClient:
    """DeepSeek 走 OpenAI 兼容协议；密钥只在 Python 进程里。"""

    def __init__(self, settings: Settings) -> None:
        if not settings.deepseek_api_key:
            raise RuntimeError("DEEPSEEK_API_KEY is not configured")
        self._model = settings.deepseek_model
        self._timeout = settings.request_timeout
        self._client = AsyncOpenAI(
            api_key=settings.deepseek_api_key,
            base_url=settings.deepseek_base_url,
        )

    async def complete(self, system: str, user: str) -> str:
        last = ""
        # DeepSeek 偶发返回空 content；重试一次，仍为空则交给 Java 侧降级
        for attempt in range(2):
            response = await self._client.chat.completions.create(
                model=self._model,
                messages=[
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ],
                response_format={"type": "json_object"},
                temperature=0.2,
                max_tokens=2500,
                timeout=self._timeout,
            )
            choice = response.choices[0]
            content = choice.message.content or ""
            if content.strip():
                return content
            last = content
            logger.warning(
                "empty llm content attempt=%d finish_reason=%s message=%s",
                attempt + 1,
                getattr(choice, "finish_reason", None),
                str(choice.message)[:500],
            )
        return last


_client: LlmClient | None = None


def get_llm() -> LlmClient:
    """进程内复用连接池；测试通过 dependency_overrides 替换。"""
    global _client
    if _client is None:
        _client = DeepSeekClient(get_settings())
    return _client
