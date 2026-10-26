import json
import logging
import secrets

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import ValidationError

from app.config import Settings, get_settings
from app.embeddings import EmbeddingClient, get_embedding_client
from app.llm import LlmClient, get_llm
from app.prompt import (
    ASSISTANT_PROMPT_VERSION,
    NL2SQL_PLAN_PROMPT_VERSION,
    PROMPT_VERSION,
    assistant_prompt,
    build_nl2sql_user_prompt,
    build_user_prompt,
    nl2sql_plan_prompt,
    system_prompt,
)
from app.schemas import (
    AssistantChatRequest,
    AssistantChatResponse,
    AssistantToolCall,
    EmbeddingRequest,
    EmbeddingResponse,
    LlmOutput,
    Nl2SqlPlan,
    Nl2SqlPlanRequest,
    Nl2SqlPlanResponse,
    RerankItem,
    RerankRequest,
    RerankResponse,
)

logger = logging.getLogger("sundry.ai")

app = FastAPI(title="Sundry AI", version="0.1.0")


def require_token(
    x_internal_token: str = Header(default=""),
    settings: Settings = Depends(get_settings),
) -> None:
    expected = settings.internal_token
    if not expected or not secrets.compare_digest(x_internal_token, expected):
        raise HTTPException(status_code=401, detail="invalid internal token")


@app.get("/health")
def health(settings: Settings = Depends(get_settings)) -> dict[str, str]:
    return {
        "status": "ok",
        "model": settings.deepseek_model,
        "promptVersion": PROMPT_VERSION,
        "embedModel": settings.embed_model,
        "embedDim": str(settings.embed_dim),
    }


@app.post(
    "/internal/embeddings",
    response_model=EmbeddingResponse,
    dependencies=[Depends(require_token)],
)
async def embeddings(
    request: EmbeddingRequest,
    client: EmbeddingClient = Depends(get_embedding_client),
    settings: Settings = Depends(get_settings),
) -> EmbeddingResponse:
    try:
        vectors = await client.embed(request.texts)
    except Exception as exc:
        logger.warning("embedding failed texts=%d", len(request.texts), exc_info=exc)
        raise HTTPException(status_code=503, detail="embedding failed") from exc
    return EmbeddingResponse(
        vectors=vectors,
        model=settings.embed_model,
        dim=settings.embed_dim,
    )


@app.post(
    "/internal/feed/rerank",
    response_model=RerankResponse,
    dependencies=[Depends(require_token)],
)
async def rerank(
    request: RerankRequest,
    llm: LlmClient = Depends(get_llm),
    settings: Settings = Depends(get_settings),
) -> RerankResponse:
    raw = await llm.complete(
        system_prompt(request.strategy),
        build_user_prompt(request.profile, request.candidates, request.limit),
        model=settings.feed_rerank_model,
    )

    if not raw.strip():
        raise HTTPException(status_code=503, detail="model returned empty output")

    try:
        parsed = LlmOutput.model_validate(json.loads(raw))
    except (json.JSONDecodeError, ValidationError, TypeError) as exc:
        logger.warning("invalid llm output: %s", raw[:1000])
        raise HTTPException(status_code=422, detail="model returned invalid json") from exc

    allowed = {candidate.id for candidate in request.candidates}
    seen: set[int] = set()
    cleaned: list[RerankItem] = []
    for item in parsed.items:
        if item.id not in allowed or item.id in seen:
            continue
        seen.add(item.id)
        cleaned.append(RerankItem(id=item.id, reason=_clean_reason(item.reason)))
        if len(cleaned) >= request.limit:
            break

    return RerankResponse(
        items=cleaned,
        model=settings.feed_rerank_model,
        prompt_version=PROMPT_VERSION,
    )


@app.post(
    "/internal/assistant/chat",
    response_model=AssistantChatResponse,
    dependencies=[Depends(require_token)],
)
async def assistant_chat(
    request: AssistantChatRequest,
    llm: LlmClient = Depends(get_llm),
) -> AssistantChatResponse:
    try:
        result = await llm.complete_with_tools(
            assistant_prompt(),
            _assistant_user_prompt(request),
            request.tools,
        )
    except Exception as exc:
        logger.warning("assistant llm failed", exc_info=exc)
        raise HTTPException(status_code=503, detail="assistant unavailable") from exc

    calls: list[AssistantToolCall] = []
    for item in result.get("tool_calls", []):
        try:
            calls.append(AssistantToolCall(**item))
        except (TypeError, ValidationError):
            continue

    return AssistantChatResponse(
        tool_calls=calls,
        content=result.get("content", "") or "",
        model=result.get("model", ""),
        prompt_version=ASSISTANT_PROMPT_VERSION,
    )


def _assistant_user_prompt(request: AssistantChatRequest) -> str:
    lines = [f"用户角色：{request.role}", f"用户问题：{request.message}"]
    if request.history:
        lines.append("最近对话：")
        for item in request.history[-6:]:
            role = str(item.get("role", "user"))[:16]
            content = str(item.get("content", ""))[:300]
            lines.append(f"- {role}: {content}")
    return "\n".join(lines)


@app.post(
    "/internal/nl2sql/plan",
    response_model=Nl2SqlPlanResponse,
    dependencies=[Depends(require_token)],
)
async def nl2sql_plan(
    request: Nl2SqlPlanRequest,
    llm: LlmClient = Depends(get_llm),
    settings: Settings = Depends(get_settings),
) -> Nl2SqlPlanResponse:
    try:
        raw = await llm.complete(
            nl2sql_plan_prompt(),
            build_nl2sql_user_prompt(
                request.vocabulary, request.question, request.role, request.history
            ),
        )
    except Exception as exc:
        logger.warning("nl2sql plan llm failed", exc_info=exc)
        raise HTTPException(status_code=503, detail="nl2sql planner unavailable") from exc

    if not raw.strip():
        raise HTTPException(status_code=503, detail="model returned empty output")

    try:
        payload = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return Nl2SqlPlanResponse(
            plan=None,
            unsupported=True,
            reason="模型输出不是 JSON",
            model=settings.deepseek_model,
            prompt_version=NL2SQL_PLAN_PROMPT_VERSION,
        )

    if isinstance(payload, dict) and payload.get("unsupported"):
        reason = str(payload.get("reason", ""))[:200]
        return Nl2SqlPlanResponse(
            plan=None,
            unsupported=True,
            reason=reason or "词汇表无法表达该问题",
            model=settings.deepseek_model,
            prompt_version=NL2SQL_PLAN_PROMPT_VERSION,
        )

    try:
        plan: Nl2SqlPlan = Nl2SqlPlan.model_validate(payload)
    except (ValidationError, TypeError):
        logger.warning("invalid nl2sql plan: %s", raw[:1000])
        return Nl2SqlPlanResponse(
            plan=None,
            unsupported=True,
            reason="模型输出不符合计划结构",
            model=settings.deepseek_model,
            prompt_version=NL2SQL_PLAN_PROMPT_VERSION,
        )

    return Nl2SqlPlanResponse(
        plan=plan,
        model=settings.deepseek_model,
        prompt_version=NL2SQL_PLAN_PROMPT_VERSION,
    )


def _clean_reason(reason: str) -> str:
    text = "".join(ch for ch in reason if ch.isprintable()).strip()
    return text[:40]
