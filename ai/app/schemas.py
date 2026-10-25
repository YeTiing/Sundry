from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field, constr


class CategoryPreference(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    weight: float = Field(ge=0)


class PriceBand(BaseModel):
    min: Decimal | None = None
    max: Decimal | None = None


class Profile(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    categories: list[CategoryPreference] = Field(default_factory=list)
    price_band: PriceBand | None = Field(default=None, alias="priceBand")
    recent_views: list[str] = Field(default_factory=list, alias="recentViews")


class Candidate(BaseModel):
    id: int = Field(gt=0)
    name: str = Field(min_length=1, max_length=200)
    category: str = Field(min_length=1, max_length=40)
    price: Decimal


class RerankRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    user_id: int = Field(alias="userId", gt=0)
    profile: Profile
    candidates: list[Candidate] = Field(min_length=1, max_length=100)
    limit: int = Field(default=20, ge=1, le=50)
    # PREF / EXPLORE / PRICE；旧调用不传，按默认偏好排序
    strategy: str | None = Field(default=None, max_length=16)


class AssistantToolCall(BaseModel):
    id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=64)
    arguments: str = Field(default="{}", max_length=4000)


class AssistantChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=500)
    history: list[dict] = Field(default_factory=list, max_length=6)
    role: str = Field(max_length=16)
    # OpenAI tools 形状，由 Java 生成后原样下发
    tools: list[dict] = Field(min_length=1, max_length=20)


class AssistantChatResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    tool_calls: list[AssistantToolCall] = Field(
        default_factory=list, serialization_alias="toolCalls"
    )
    content: str = ""
    model: str
    prompt_version: str = Field(serialization_alias="promptVersion")


class RerankItem(BaseModel):
    id: int
    reason: str = ""


class LlmOutput(BaseModel):
    items: list[RerankItem] = Field(default_factory=list)


class RerankResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    items: list[RerankItem]
    model: str
    prompt_version: str = Field(serialization_alias="promptVersion")


EmbeddingText = constr(strip_whitespace=True, min_length=1, max_length=2000)


class EmbeddingRequest(BaseModel):
    # 百炼 embedding 单批最多 25 条
    texts: list[EmbeddingText] = Field(min_length=1, max_length=25)


class EmbeddingResponse(BaseModel):
    vectors: list[list[float]]
    model: str
    dim: int
