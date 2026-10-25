from pathlib import Path

from app.schemas import Candidate, Profile

PROMPT_VERSION = "feed_rerank_v2"
_PROMPT_PATH = Path(__file__).resolve().parent.parent / "prompts" / "feed_rerank_v2.txt"
ASSISTANT_PROMPT_VERSION = "assistant_v1"
_ASSISTANT_PROMPT_PATH = (
    Path(__file__).resolve().parent.parent / "prompts" / "assistant_v1.txt"
)

STRATEGY_INSTRUCTIONS = {
    "PREF": "附加偏好（不改变以上规则）：本轮按标准偏好排序，优先常看分类、历史价位和最近浏览。",
    "EXPLORE": "附加偏好（不改变以上规则）：本轮偏向探索，压低头部类目，提高冷门分类和较新商品的位置。",
}


def system_prompt(strategy: str | None = None) -> str:
    base = _PROMPT_PATH.read_text(encoding="utf-8").strip()
    instruction = STRATEGY_INSTRUCTIONS.get((strategy or "").upper())
    if not instruction:
        return base
    return f"{base}\n\n{instruction}"


def assistant_prompt() -> str:
    return _ASSISTANT_PROMPT_PATH.read_text(encoding="utf-8").strip()


def build_user_prompt(profile: Profile, candidates: list[Candidate], limit: int) -> str:
    lines: list[str] = ["用户偏好："]

    if profile.categories:
        readable = "、".join(
            f"{item.name}({item.weight:g})" for item in profile.categories
        )
        lines.append(f"- 常看分类：{readable}")
    else:
        lines.append("- 常看分类：暂无")

    if profile.price_band and profile.price_band.min is not None:
        lines.append(
            f"- 价位带：{profile.price_band.min} - {profile.price_band.max} 元"
        )
    else:
        lines.append("- 价位带：暂无")

    if profile.recent_views:
        lines.append("- 最近浏览：" + "、".join(profile.recent_views))
    else:
        lines.append("- 最近浏览：暂无")

    lines.append("")
    lines.append(
        f"候选商品（只能从这里选，请按优先级返回 {limit} 个；候选不足时返回全部）："
    )
    for item in candidates:
        lines.append(
            f"{item.id} | {item.name} | {item.category} | {item.price}"
        )
    return "\n".join(lines)
