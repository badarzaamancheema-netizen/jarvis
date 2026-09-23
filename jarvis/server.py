"""HTTP server for the phone app: `python -m jarvis.server`

Serves the installable web app (jarvis/web) and a small JSON API the app talks
to. Everything is behind JARVIS_ACCESS_TOKEN: this server spends your Anthropic
credit and holds your notes, so it refuses to start without one.
"""
import hmac
import mimetypes
import threading
import time
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from jarvis import storage
from jarvis.brain import Jarvis
from jarvis.config import SETTINGS
from jarvis.mobile_tools import MOBILE_SYSTEM_PROMPT_TEMPLATE, MOBILE_TOOLS, MobileToolRunner

mimetypes.add_type("application/manifest+json", ".webmanifest")

WEB_DIR = Path(__file__).parent / "web"
SESSION_IDLE_SECONDS = 30 * 60


class AskRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    session_id: str = Field(min_length=1, max_length=64)
    # The phone's clock, e.g. "2026-09-23T18:04:00" + "Asia/Karachi". Reminders
    # are stored and checked in the phone's local time, not the server's.
    local_time: str = Field(default="", max_length=40)
    timezone: str = Field(default="", max_length=64)


class ResetRequest(BaseModel):
    session_id: str = Field(min_length=1, max_length=64)


class _Session:
    def __init__(self, runner: MobileToolRunner):
        self.runner = runner
        self.jarvis = Jarvis(
            tools=MOBILE_TOOLS,
            tool_runner=runner,
            system_prompt=MOBILE_SYSTEM_PROMPT_TEMPLATE.format(user_name=SETTINGS.user_name),
        )
        self.lock = threading.Lock()
        self.last_used = time.monotonic()


_sessions: dict[str, _Session] = {}
_sessions_lock = threading.Lock()


def _get_session(session_id: str) -> _Session:
    now = time.monotonic()
    with _sessions_lock:
        for sid in [s for s, sess in _sessions.items() if now - sess.last_used > SESSION_IDLE_SECONDS]:
            del _sessions[sid]
        session = _sessions.get(session_id)
        if session is None:
            session = _sessions[session_id] = _Session(MobileToolRunner())
        session.last_used = now
        return session


def require_token(authorization: str = Header(default="")) -> None:
    expected = f"Bearer {SETTINGS.access_token}"
    if not SETTINGS.access_token or not hmac.compare_digest(authorization.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="Bad or missing access token.")


app = FastAPI(title="Jarvis", docs_url=None, redoc_url=None)


@app.get("/api/ping", dependencies=[Depends(require_token)])
def ping() -> dict:
    return {"ok": True, "user_name": SETTINGS.user_name}


@app.post("/api/ask", dependencies=[Depends(require_token)])
def ask(req: AskRequest) -> dict:
    session = _get_session(req.session_id)
    context = ""
    if req.local_time:
        context = f"The user's current local time is {req.local_time}"
        context += f" ({req.timezone})." if req.timezone else "."
    with session.lock:
        session.runner.actions = []
        try:
            reply = session.jarvis.ask(req.text, context=context)
        except Exception as exc:  # noqa: BLE001 - any API failure leaves history half-written
            session.jarvis.reset()
            raise HTTPException(status_code=502, detail=f"Jarvis had a problem: {exc}") from exc
        return {"reply": reply, "actions": list(session.runner.actions)}


@app.post("/api/reset", dependencies=[Depends(require_token)])
def reset(req: ResetRequest) -> dict:
    with _sessions_lock:
        _sessions.pop(req.session_id, None)
    return {"ok": True}


@app.get("/api/reminders/due", dependencies=[Depends(require_token)])
def reminders_due(now: str) -> dict:
    """Return (and mark fired) reminders due at or before the phone's local `now`."""
    due = storage.due_reminders(now)
    for reminder in due:
        storage.mark_reminder_fired(reminder["id"])
    return {"reminders": [{"id": r["id"], "text": r["text"], "due_at": r["due_at"]} for r in due]}


@app.get("/api/overview", dependencies=[Depends(require_token)])
def overview() -> dict:
    return {
        "tasks": storage.list_tasks(),
        "reminders": storage.list_reminders(),
        "notes": storage.list_notes()[:20],
    }


@app.get("/")
def index() -> FileResponse:
    return FileResponse(WEB_DIR / "index.html", headers={"Cache-Control": "no-cache"})


@app.get("/sw.js")
def service_worker() -> FileResponse:
    # Served from the root so its scope covers the whole app.
    return FileResponse(WEB_DIR / "sw.js", media_type="text/javascript", headers={"Cache-Control": "no-cache"})


app.mount("/static", StaticFiles(directory=WEB_DIR), name="static")


def main() -> None:
    import argparse

    import uvicorn

    parser = argparse.ArgumentParser(description="Serve the Jarvis phone app")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()

    SETTINGS.require_api_key()
    if len(SETTINGS.access_token) < 12:
        raise SystemExit(
            "Set JARVIS_ACCESS_TOKEN in .env to a long random string (12+ characters) before "
            "starting the server — it's the password your phone uses. Generate one with:\n"
            "  python -c \"import secrets; print(secrets.token_urlsafe(24))\""
        )
    uvicorn.run(app, host=args.host, port=args.port)


if __name__ == "__main__":
    main()
