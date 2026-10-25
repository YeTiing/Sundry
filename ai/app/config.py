import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    internal_token: str
    deepseek_api_key: str
    deepseek_base_url: str
    deepseek_model: str
    request_timeout: float
    embed_api_key: str
    embed_base_url: str
    embed_model: str
    embed_dim: int
    embed_timeout: float


def get_settings() -> Settings:
    """每次读取环境变量，测试可以按用例覆盖，不在导入时固化密钥。"""
    return Settings(
        internal_token=os.getenv("AI_INTERNAL_TOKEN", ""),
        deepseek_api_key=os.getenv("DEEPSEEK_API_KEY", ""),
        deepseek_base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
        deepseek_model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
        request_timeout=float(os.getenv("AI_REQUEST_TIMEOUT_SECONDS", "5")),
        embed_api_key=os.getenv("EMBED_API_KEY", ""),
        embed_base_url=os.getenv(
            "EMBED_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"
        ),
        embed_model=os.getenv("EMBED_MODEL", ""),
        embed_dim=int(os.getenv("EMBED_DIM", "1024")),
        embed_timeout=float(os.getenv("EMBED_TIMEOUT_SECONDS", "30")),
    )
