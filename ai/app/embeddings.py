import logging

from openai import AsyncOpenAI

from app.config import Settings, get_settings

logger = logging.getLogger("sundry.ai")


class EmbeddingClient:
    """百炼 OpenAI 兼容 embeddings 接口；密钥只在 Python 进程里。"""

    def __init__(self, settings: Settings) -> None:
        if not settings.embed_api_key:
            raise RuntimeError("EMBED_API_KEY is not configured")
        if not settings.embed_model:
            raise RuntimeError("EMBED_MODEL is not configured")
        self._model = settings.embed_model
        self._dim = settings.embed_dim
        self._timeout = settings.embed_timeout
        self._client = AsyncOpenAI(
            api_key=settings.embed_api_key,
            base_url=settings.embed_base_url,
        )

    async def embed(self, texts: list[str]) -> list[list[float]]:
        response = await self._client.embeddings.create(
            model=self._model,
            input=texts,
            timeout=self._timeout,
        )
        vectors = [list(item.embedding) for item in response.data]
        if len(vectors) != len(texts):
            raise ValueError("embedding count mismatch")
        for vector in vectors:
            if len(vector) != self._dim:
                raise ValueError(
                    f"embedding dimension mismatch: expected {self._dim}, got {len(vector)}"
                )
        return vectors


_client: EmbeddingClient | None = None


def get_embedding_client() -> EmbeddingClient:
    """进程内复用连接池；测试通过 dependency_overrides 替换。"""
    global _client
    if _client is None:
        _client = EmbeddingClient(get_settings())
    return _client
