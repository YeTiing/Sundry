import json

import pytest
from fastapi.testclient import TestClient

from app.llm import get_llm
from app.main import app
from app.prompt import NL2SQL_PLAN_PROMPT_VERSION


class FakeLlm:
    def __init__(self, payload: str) -> None:
        self.payload = payload

    async def complete(self, system: str, user: str) -> str:
        return self.payload

    async def complete_with_tools(self, system: str, user: str, tools: list[dict]) -> dict:
        return {"tool_calls": [], "content": "", "model": "fake"}


@pytest.fixture(autouse=True)
def env(monkeypatch):
    monkeypatch.setenv("AI_INTERNAL_TOKEN", "test-token")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "test-key")
    yield
    app.dependency_overrides.clear()


def client(payload: str) -> TestClient:
    app.dependency_overrides[get_llm] = lambda: FakeLlm(payload)
    return TestClient(app)


def body() -> dict:
    return {
        "question": "上个月各分类的销售额",
        "role": "ADMIN",
        "history": [],
        "vocabulary": {
            "metrics": [{"key": "gmv", "label": "销售额", "synonyms": [], "unit": "元"}],
            "dimensions": [{"key": "category", "label": "商品分类", "synonyms": []}],
            "filters": [],
            "timePresets": ["LAST_MONTH"],
            "limits": {"maxMetrics": 3, "maxDimensions": 3, "maxRangeDays": 365, "maxRows": 1000},
        },
    }


def test_valid_plan_is_returned():
    plan = {
        "metrics": ["gmv"],
        "dimensions": ["category"],
        "filters": [],
        "time": {"preset": "LAST_MONTH"},
        "granularity": "day",
        "sort": [{"metric": "gmv", "order": "desc"}],
        "limit": 10,
    }
    response = client(json.dumps(plan)).post(
        "/internal/nl2sql/plan", json=body(), headers={"X-Internal-Token": "test-token"}
    )

    assert response.status_code == 200
    data = response.json()
    assert data["plan"]["metrics"] == ["gmv"]
    assert data["plan"]["time"]["preset"] == "LAST_MONTH"
    assert data["unsupported"] is False
    assert data["promptVersion"] == NL2SQL_PLAN_PROMPT_VERSION


class CapturingLlm(FakeLlm):
    def __init__(self, payload: str) -> None:
        super().__init__(payload)
        self.calls: list[tuple[str, str]] = []

    async def complete(self, system: str, user: str) -> str:
        self.calls.append((system, user))
        return await super().complete(system, user)


def test_history_is_rendered_into_prompt():
    plan = {
        "metrics": ["gmv"],
        "dimensions": ["category"],
        "filters": [],
        "time": {"preset": "LAST_MONTH"},
        "granularity": "day",
        "sort": [],
        "limit": 10,
    }
    llm = CapturingLlm(json.dumps(plan))
    app.dependency_overrides[get_llm] = lambda: llm
    request_body = {
        **body(),
        "history": [
            {
                "question": "上个月各分类销售额",
                "plan": '{"metrics":["gmv"]}',
                "rowCount": "2",
            }
        ],
    }

    response = TestClient(app).post(
        "/internal/nl2sql/plan",
        json=request_body,
        headers={"X-Internal-Token": "test-token"},
    )

    assert response.status_code == 200
    user_prompt = llm.calls[0][1]
    assert "上个月各分类销售额" in user_prompt
    assert '{"metrics":["gmv"]}' in user_prompt
    assert "结果行数：2" in user_prompt


def test_unsupported_passthrough():
    response = client(
        json.dumps({"unsupported": True, "reason": "词汇表没有这个维度"})
    ).post("/internal/nl2sql/plan", json=body(), headers={"X-Internal-Token": "test-token"})

    assert response.status_code == 200
    data = response.json()
    assert data["unsupported"] is True
    assert data["plan"] is None
    assert data["reason"] == "词汇表没有这个维度"


def test_invalid_json_is_reported_as_unsupported():
    response = client("not json").post(
        "/internal/nl2sql/plan", json=body(), headers={"X-Internal-Token": "test-token"}
    )

    assert response.status_code == 200
    assert response.json()["unsupported"] is True


def test_requires_internal_token():
    response = client("{}").post("/internal/nl2sql/plan", json=body())
    assert response.status_code == 401
