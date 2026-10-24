from typing import Protocol

from openai import AsyncOpenAI

from app.config import Settings, get_settings


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
        return response.choices[0].message.content or ""


_client: LlmClient | None = None


def get_llm() -> LlmClient:
    """进程内复用连接池；测试通过 dependency_overrides 替换。"""
    global _client
    if _client is None:
        _client = DeepSeekClient(get_settings())
    return _client
