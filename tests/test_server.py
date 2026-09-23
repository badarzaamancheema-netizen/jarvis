import tempfile
from pathlib import Path

import pytest

pytest.importorskip("fastapi")
from fastapi.testclient import TestClient  # noqa: E402

from jarvis import server, storage  # noqa: E402

TOKEN = "test-token-123456"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


class _FakeJarvis:
    """Stands in for the Claude-backed brain; records what it was asked."""

    def __init__(self, tools=None, tool_runner=None, system_prompt=None):
        self.tool_runner = tool_runner
        self.calls = []

    def ask(self, text, context=""):
        self.calls.append((text, context))
        if text == "boom":
            raise RuntimeError("api down")
        if text == "call":
            self.tool_runner("phone_call", {"number": "12345"})
        return f"echo: {text}"

    def reset(self):
        self.calls = []


@pytest.fixture
def client(monkeypatch):
    db = Path(tempfile.NamedTemporaryFile(suffix=".db", delete=False).name)
    monkeypatch.setattr(storage, "DB_PATH", db)
    monkeypatch.setattr(server.SETTINGS, "access_token", TOKEN)
    monkeypatch.setattr(server, "Jarvis", _FakeJarvis)
    server._sessions.clear()
    return TestClient(server.app)


def test_api_requires_token(client):
    assert client.get("/api/ping").status_code == 401
    assert client.get("/api/ping", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.get("/api/ping", headers=AUTH).json()["ok"] is True


def test_api_rejects_everything_when_no_token_configured(client, monkeypatch):
    monkeypatch.setattr(server.SETTINGS, "access_token", "")
    assert client.get("/api/ping", headers={"Authorization": "Bearer "}).status_code == 401


def test_ask_passes_phone_time_and_returns_actions(client):
    res = client.post(
        "/api/ask",
        headers=AUTH,
        json={"text": "call", "session_id": "s1", "local_time": "2026-09-23T18:00:00", "timezone": "Asia/Karachi"},
    )
    body = res.json()
    assert res.status_code == 200
    assert body["reply"] == "echo: call"
    assert body["actions"][0]["url"] == "tel:12345"
    _, context = server._sessions["s1"].jarvis.calls[0]
    assert "2026-09-23T18:00:00" in context and "Asia/Karachi" in context


def test_actions_do_not_leak_between_requests(client):
    client.post("/api/ask", headers=AUTH, json={"text": "call", "session_id": "s1"})
    res = client.post("/api/ask", headers=AUTH, json={"text": "hello", "session_id": "s1"})
    assert res.json()["actions"] == []


def test_ask_failure_returns_502_and_resets_history(client):
    client.post("/api/ask", headers=AUTH, json={"text": "hello", "session_id": "s1"})
    res = client.post("/api/ask", headers=AUTH, json={"text": "boom", "session_id": "s1"})
    assert res.status_code == 502
    assert server._sessions["s1"].jarvis.calls == []


def test_due_reminders_use_phone_time_and_fire_once(client):
    storage.add_reminder("stretch", "2026-09-23T18:00:00")
    storage.add_reminder("later", "2026-09-23T20:00:00")
    first = client.get("/api/reminders/due", params={"now": "2026-09-23T18:30:00"}, headers=AUTH).json()
    again = client.get("/api/reminders/due", params={"now": "2026-09-23T18:31:00"}, headers=AUTH).json()
    assert [r["text"] for r in first["reminders"]] == ["stretch"]
    assert again["reminders"] == []


def test_app_shell_is_served_without_token(client):
    assert "J.A.R.V.I.S." in client.get("/").text
    assert client.get("/sw.js").status_code == 200
    manifest = client.get("/static/manifest.webmanifest")
    assert manifest.json()["start_url"] == "/?wake=1"
