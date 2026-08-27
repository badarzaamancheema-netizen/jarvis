"""Safe, narrow system-control actions.

Deliberately does NOT expose arbitrary shell execution to the assistant:
a voice/LLM-driven "run this command" tool is a command-injection and
prompt-injection risk (misheard speech or a malicious web-search result
could trigger it). Instead we expose a small set of specific actions,
plus an allowlist of apps the user configures themselves.
"""
import json
import platform
import shutil
import subprocess
import webbrowser
from datetime import datetime

from jarvis.config import APPS_CONFIG_PATH


def _load_app_allowlist() -> dict:
    if not APPS_CONFIG_PATH.exists():
        return {}
    try:
        return json.loads(APPS_CONFIG_PATH.read_text())
    except (json.JSONDecodeError, OSError):
        return {}


def open_app(name: str) -> str:
    """Launch an application by its configured nickname.

    Only apps listed in jarvis_data/apps.json (nickname -> executable path
    or macOS app name) can be launched. This avoids running whatever
    string a voice command happened to contain.
    """
    allowlist = _load_app_allowlist()
    key = name.strip().lower()
    if key not in allowlist:
        known = ", ".join(sorted(allowlist)) or "(none configured)"
        return (
            f"I don't have '{name}' in the allowed app list. "
            f"Known apps: {known}. Add more in jarvis_data/apps.json."
        )

    target = allowlist[key]
    system = platform.system()
    try:
        if system == "Darwin":
            subprocess.Popen(["open", "-a", target])
        elif system == "Windows":
            subprocess.Popen(["cmd", "/c", "start", "", target], shell=False)
        else:
            subprocess.Popen([target])
        return f"Opening {name}."
    except (OSError, subprocess.SubprocessError) as exc:
        return f"I couldn't open {name}: {exc}"


def open_url(url: str) -> str:
    if not (url.startswith("http://") or url.startswith("https://")):
        url = "https://" + url
    webbrowser.open(url)
    return f"Opening {url} in your browser."


def set_volume(level: int) -> str:
    level = max(0, min(100, int(level)))
    system = platform.system()
    try:
        if system == "Darwin":
            subprocess.run(["osascript", "-e", f"set volume output volume {level}"], check=True)
        elif system == "Linux" and shutil.which("pactl"):
            subprocess.run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{level}%"], check=True)
        elif system == "Windows":
            return "Volume control on Windows isn't wired up yet — use the system tray for now."
        else:
            return "I don't know how to control volume on this system."
        return f"Volume set to {level} percent."
    except (OSError, subprocess.SubprocessError) as exc:
        return f"I couldn't change the volume: {exc}"


def get_system_info() -> str:
    return (
        f"It's {datetime.now().strftime('%A, %B %d, %Y at %I:%M %p')}. "
        f"Running on {platform.system()} {platform.release()}."
    )
