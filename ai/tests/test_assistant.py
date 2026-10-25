import pytest
from fastapi.testclient import TestClient

from app.llm import get_llm
from app.main import app
from app.prompt import ASSISTANT_PROMPT_VERSION


class FakeLlm:
    def __init__(self, result: dict) -> None:
        self.result = result

    async def complete(self, system: str, user: str) -> str:
        return ""

    async def complete_with_tools(self, system: str, user: str, tools: list[dict]) -> dict:
        return self.result


@pytest.fixture(autouse=True)
def env(monkeypatch):
    monkeypatch.setenv("AI_INTERNAL_TOKEN", "test-token")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "test-key")
    yield
    app.dependency_overrides.clear()


def client(result: dict) -> TestClient:
    app.dependency_overrides[get_llm] = lambda: FakeLlm(result)
    return TestClient(app)


def body() -> dict:
    return {
        "message": "我的包裹到哪了",
        "history": [],
        "role": "USER",
        "tools": [
            {
                "type": "function",
                "function": {
                    "name": "navigate",
                    "description": "打开页面",
                    "parameters": {
                        "type": "object",
                        "properties": {"route": {"type": "string", "enum": ["orders"]}},
                        "required": ["route"],
                    },
                },
            }
        ],
    }


def test_returns_tool_calls():
    response = client(
        {
            "tool_calls": [
                {"id": "call_1", "name": "navigate", "arguments": '{"route":"orders"}'}
            ],
            "content": "",
            "model": "deepseek-v4-flash",
        }
    ).post("/internal/assistant/chat", json=body(), headers={"X-Internal-Token": "test-token"})

    assert response.status_code == 200
    data = response.json()
    assert data["toolCalls"][0]["name"] == "navigate"
    assert data["toolCalls"][0]["arguments"] == '{"route":"orders"}'
    assert data["promptVersion"] == ASSISTANT_PROMPT_VERSION


def test_text_answer_is_passed_through():
    response = client(
        {
            "tool_calls": [],
            "content": "可以在订单页查看物流。",
            "model": "deepseek-v4-flash",
        }
    ).post("/internal/assistant/chat", json=body(), headers={"X-Internal-Token": "test-token"})

    assert response.status_code == 200
    data = response.json()
    assert data["toolCalls"] == []
    assert data["content"] == "可以在订单页查看物流。"


def test_requires_internal_token():
    response = client({"tool_calls": [], "content": "", "model": "x"}).post(
        "/internal/assistant/chat", json=body()
    )
    assert response.status_code == 401


def test_rejects_empty_tools():
    response = client({"tool_calls": [], "content": "", "model": "x"}).post(
        "/internal/assistant/chat",
        json={**body(), "tools": []},
        headers={"X-Internal-Token": "test-token"},
    )
    assert response.status_code == 422
