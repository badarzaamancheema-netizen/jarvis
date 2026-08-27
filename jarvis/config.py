"""Configuration loaded from environment variables (.env)."""
import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

DATA_DIR = Path(os.environ.get("JARVIS_DATA_DIR", "jarvis_data")).resolve()
DATA_DIR.mkdir(parents=True, exist_ok=True)

DB_PATH = DATA_DIR / "jarvis.db"
APPS_CONFIG_PATH = DATA_DIR / "apps.json"


@dataclass
class Settings:
    anthropic_api_key: str = field(default_factory=lambda: os.environ.get("ANTHROPIC_API_KEY", ""))
    model: str = field(default_factory=lambda: os.environ.get("ANTHROPIC_MODEL", "claude-sonnet-5"))
    user_name: str = field(default_factory=lambda: os.environ.get("USER_NAME", "sir"))
    wake_word: str = field(default_factory=lambda: os.environ.get("JARVIS_WAKE_WORD", "jarvis").lower())
    tts_rate: int = field(default_factory=lambda: int(os.environ.get("JARVIS_TTS_RATE", "185")))

    def require_api_key(self) -> str:
        if not self.anthropic_api_key:
            raise RuntimeError(
                "ANTHROPIC_API_KEY is not set. Copy .env.example to .env and fill it in."
            )
        return self.anthropic_api_key


SETTINGS = Settings()
