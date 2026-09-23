"""Tools for the phone client.

The server can't touch the phone directly, so phone-side tools (call someone,
open YouTube, navigate somewhere...) don't *do* anything here: they queue a
client action — a URL the phone opens (https:, tel:, sms:) — which the web app
runs once the reply comes back. Tasks, reminders, notes, weather and search
reuse the server-side implementations from `jarvis.tools`.

Like the desktop version there is no "run anything" tool: every action is a
URL built from a fixed template, and only http(s)/tel/sms schemes are allowed.
"""
import re
from urllib.parse import quote, quote_plus

from jarvis.tools import TOOLS, run_tool

# Desktop-only tools that make no sense when the user is on their phone
# (they'd act on the server machine), plus get_system_info, which would report
# the server's clock — the phone's local time is passed in as context instead.
_SERVER_ONLY = {"open_app", "open_url", "set_volume", "get_system_info"}

# Nickname -> web URL. On Android and iOS these are universal/app links, so
# they open the installed app when there is one and the website otherwise.
PHONE_APPS = {
    "youtube": "https://www.youtube.com/",
    "whatsapp": "https://wa.me/",
    "instagram": "https://www.instagram.com/",
    "facebook": "https://www.facebook.com/",
    "x": "https://x.com/",
    "twitter": "https://x.com/",
    "gmail": "https://mail.google.com/",
    "maps": "https://www.google.com/maps",
    "google maps": "https://www.google.com/maps",
    "spotify": "https://open.spotify.com/",
    "netflix": "https://www.netflix.com/",
    "google": "https://www.google.com/",
    "chatgpt": "https://chatgpt.com/",
    "linkedin": "https://www.linkedin.com/",
    "tiktok": "https://www.tiktok.com/",
    "calendar": "https://calendar.google.com/",
    "drive": "https://drive.google.com/",
}

_PHONE_TOOLS = [
    {
        "name": "open_url",
        "description": "Open a website on the user's phone.",
        "input_schema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    },
    {
        "name": "open_phone_app",
        "description": "Open a well-known app on the user's phone by name. Known apps: "
        + ", ".join(sorted(PHONE_APPS)) + ".",
        "input_schema": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
    },
    {
        "name": "phone_call",
        "description": "Start a phone call to a number (the user still presses call). Needs an actual phone number, not a contact name.",
        "input_schema": {
            "type": "object",
            "properties": {"number": {"type": "string"}},
            "required": ["number"],
        },
    },
    {
        "name": "send_sms",
        "description": "Open a prefilled text message to a number (the user still presses send).",
        "input_schema": {
            "type": "object",
            "properties": {"number": {"type": "string"}, "message": {"type": "string"}},
            "required": ["number", "message"],
        },
    },
    {
        "name": "send_whatsapp",
        "description": "Open WhatsApp with a prefilled message. Number (international format, e.g. 923001234567) is optional; without it the user picks the chat.",
        "input_schema": {
            "type": "object",
            "properties": {"message": {"type": "string"}, "number": {"type": "string"}},
            "required": ["message"],
        },
    },
    {
        "name": "navigate",
        "description": "Start Google Maps directions to a place or address.",
        "input_schema": {
            "type": "object",
            "properties": {"destination": {"type": "string"}},
            "required": ["destination"],
        },
    },
    {
        "name": "play_youtube",
        "description": "Search YouTube for a song, video or topic and open the results.",
        "input_schema": {
            "type": "object",
            "properties": {"query": {"type": "string"}},
            "required": ["query"],
        },
    },
]

MOBILE_TOOLS = [t for t in TOOLS if t["name"] not in _SERVER_ONLY] + _PHONE_TOOLS

MOBILE_SYSTEM_PROMPT_TEMPLATE = """You are Jarvis, a witty, imperturbably composed personal AI assistant \
in the mold of Tony Stark's J.A.R.V.I.S., running on the user's phone. You address the user as \
"{user_name}". Replies are read aloud, so keep them to a sentence or two unless asked for detail, \
and never use markdown. Use your tools whenever a question needs current information, or the user \
asks you to manage tasks, reminders or notes, or to do something on their phone (open an app or \
site, call, text, WhatsApp, navigate, play something) rather than guessing. Reminder times are in \
the user's local time. You cannot look up contacts: if the user names a person instead of giving \
a number, ask for the number. Never invent tool results."""

_ALLOWED_SCHEMES = ("https://", "http://", "tel:", "sms:")


def _digits(number: str) -> str:
    """Keep only what belongs in a dialable number (digits and a leading +)."""
    number = number.strip()
    cleaned = re.sub(r"[^\d]", "", number)
    return ("+" + cleaned) if number.startswith("+") else cleaned


class MobileToolRunner:
    """Runs tools for one request and collects the phone actions they produce."""

    def __init__(self):
        self.actions: list[dict] = []

    def _queue(self, url: str, label: str) -> str:
        if not url.startswith(_ALLOWED_SCHEMES):
            return f"Refusing to open '{url}': only web, call and text links are allowed."
        self.actions.append({"type": "open", "url": url, "label": label})
        return f"{label} — opening on the phone now."

    def __call__(self, name: str, tool_input: dict) -> str:
        if name == "open_url":
            url = tool_input["url"].strip()
            # "example.com" gets https:// added; anything with its own scheme
            # ("javascript:", "file://") is left as-is and rejected below.
            # A colon followed by a digit is a port ("localhost:8080"), not a scheme.
            if not re.match(r"^[a-z][a-z0-9+.-]*:(?!\d)", url, re.IGNORECASE):
                url = "https://" + url
            if not url.startswith(("https://", "http://")):
                return "I can only open web addresses."
            return self._queue(url, f"Opening {url}")
        if name == "open_phone_app":
            key = tool_input["name"].strip().lower()
            if key not in PHONE_APPS:
                return f"I don't know how to open '{tool_input['name']}'. Known apps: {', '.join(sorted(PHONE_APPS))}."
            return self._queue(PHONE_APPS[key], f"Opening {tool_input['name']}")
        if name == "phone_call":
            number = _digits(tool_input["number"])
            if len(number.lstrip("+")) < 3:
                return "That doesn't look like a phone number."
            return self._queue(f"tel:{number}", f"Calling {number}")
        if name == "send_sms":
            number = _digits(tool_input["number"])
            if len(number.lstrip("+")) < 3:
                return "That doesn't look like a phone number."
            return self._queue(f"sms:{number}?&body={quote(tool_input['message'])}", f"Texting {number}")
        if name == "send_whatsapp":
            number = _digits(tool_input.get("number", "")).lstrip("+")
            return self._queue(
                f"https://wa.me/{number}?text={quote(tool_input['message'])}",
                "Opening WhatsApp",
            )
        if name == "navigate":
            dest = tool_input["destination"]
            return self._queue(
                f"https://www.google.com/maps/dir/?api=1&destination={quote_plus(dest)}",
                f"Directions to {dest}",
            )
        if name == "play_youtube":
            query = tool_input["query"]
            return self._queue(
                f"https://www.youtube.com/results?search_query={quote_plus(query)}",
                f"YouTube: {query}",
            )
        return run_tool(name, tool_input)
