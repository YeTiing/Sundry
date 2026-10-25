import pytest
from fastapi.testclient import TestClient

from app.embeddings import get_embedding_client
from app.main import app


class FakeEmbeddingClient:
    def __init__(self, dim: int = 4, fail: bool = False) -> None:
        self.dim = dim
        self.fail = fail

    async def embed(self, texts: list[str]) -> list[list[float]]:
        if self.fail:
            raise RuntimeError("provider down")
        return [[0.1] * self.dim for _ in texts]


@pytest.fixture(autouse=True)
def env(monkeypatch):
    monkeypatch.setenv("AI_INTERNAL_TOKEN", "test-token")
    monkeypatch.setenv("EMBED_API_KEY", "test-key")
    monkeypatch.setenv("EMBED_MODEL", "test-embed")
    monkeypatch.setenv("EMBED_DIM", "4")
    yield
    app.dependency_overrides.clear()


def make_client(fake: FakeEmbeddingClient) -> TestClient:
    app.dependency_overrides[get_embedding_client] = lambda: fake
    return TestClient(app)


def test_embeddings_return_vectors():
    client = make_client(FakeEmbeddingClient())
    response = client.post(
        "/internal/embeddings",
        json={"texts": ["商品一", "商品二"]},
        headers={"X-Internal-Token": "test-token"},
    )
    assert response.status_code == 200
    data = response.json()
    assert len(data["vectors"]) == 2
    assert len(data["vectors"][0]) == 4
    assert data["model"] == "test-embed"
    assert data["dim"] == 4


def test_missing_token_rejected():
    client = make_client(FakeEmbeddingClient())
    response = client.post("/internal/embeddings", json={"texts": ["a"]})
    assert response.status_code == 401


def test_empty_texts_rejected():
    client = make_client(FakeEmbeddingClient())
    response = client.post(
        "/internal/embeddings",
        json={"texts": []},
        headers={"X-Internal-Token": "test-token"},
    )
    assert response.status_code == 422


def test_batch_over_25_rejected():
    client = make_client(FakeEmbeddingClient())
    response = client.post(
        "/internal/embeddings",
        json={"texts": [f"商品{i}" for i in range(26)]},
        headers={"X-Internal-Token": "test-token"},
    )
    assert response.status_code == 422


def test_provider_failure_returns_503():
    client = make_client(FakeEmbeddingClient(fail=True))
    response = client.post(
        "/internal/embeddings",
        json={"texts": ["a"]},
        headers={"X-Internal-Token": "test-token"},
    )
    assert response.status_code == 503
