import json
import logging
import secrets

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import ValidationError

from app.config import Settings, get_settings
from app.embeddings import EmbeddingClient, get_embedding_client
from app.llm import LlmClient, get_llm
from app.prompt import PROMPT_VERSION, build_user_prompt, system_prompt
from app.schemas import (
    EmbeddingRequest,
    EmbeddingResponse,
    LlmOutput,
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
        model=settings.deepseek_model,
        prompt_version=PROMPT_VERSION,
    )


def _clean_reason(reason: str) -> str:
    text = "".join(ch for ch in reason if ch.isprintable()).strip()
    return text[:40]
