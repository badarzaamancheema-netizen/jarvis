# Jarvis

A personal voice assistant in the style of Tony Stark's J.A.R.V.I.S.: wake word
+ voice conversation, powered by Claude for reasoning and tool use, with
built-in tasks, reminders, notes, live weather/web-search lookups, and a
small safe set of system controls.

## What it can do

- **Conversation** — general Q&A via the Claude API.
- **Tasks** — "add a task to call the plumber", "what's on my to-do list".
- **Reminders** — "remind me to take the bread out at 6pm" (announced out loud
  when due, checked every 30s while Jarvis is running).
- **Notes** — "note that the wifi password is ...", "what did I note about X".
- **Live info** — current weather (no API key needed, via wttr.in) and web
  search (no API key needed, via DuckDuckGo) for anything Claude's training
  data wouldn't know.
- **System control** — open a browser URL, open an app you've pre-approved by
  nickname, set system volume, get the date/time.

## Why no "run any command" tool

A voice/LLM-driven assistant that can execute arbitrary shell commands is a
real risk: misheard speech, or text pulled in from a web search result, could
trigger something you never intended. Instead, `open_app` only launches apps
you've explicitly listed by nickname in `jarvis_data/apps.json` — everything
else is a small number of specific, safe actions (open URL, set volume, get
system info).

## Setup

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # then add your ANTHROPIC_API_KEY
cp apps.example.json jarvis_data/apps.json   # then edit with your own app names
```

PyAudio (needed for microphone input) can be finicky to install:
- macOS: `brew install portaudio` first
- Debian/Ubuntu: `sudo apt install portaudio19-dev` first
- Windows: usually installs fine via pip directly

## Running

Text mode (no microphone or speakers required — good for a first test):

```bash
python -m jarvis.main --text
```

Voice mode (say "jarvis", wait for the chime/response, then speak your
command):

```bash
python -m jarvis.main
```

Configurable via `.env`: `USER_NAME` (what Jarvis calls you), `JARVIS_WAKE_WORD`
(default `jarvis`), `ANTHROPIC_MODEL`, `JARVIS_TTS_RATE`.

## Notes on the voice pipeline

There's no bundled dedicated wake-word engine (accurate ones like Porcupine
require their own API key) — instead Jarvis continuously transcribes short
utterances with the free Google Web Speech recognizer and looks for the wake
word in the text. That means it needs network access and won't be as snappy
or accurate as a purpose-built wake-word model, but it works with nothing
beyond your Anthropic API key. Text-to-speech is fully offline via `pyttsx3`.

This was built and unit-tested without physical audio hardware available, so
the storage/tool/brain logic is covered by `pytest`, but the microphone and
speaker path itself should get a real smoke test on your machine.

## Tests

```bash
pip install pytest
pytest
```

## Project layout

```
jarvis/
  main.py              # entry point (--text or voice mode)
  brain.py             # Claude conversation + tool-use loop
  tools.py             # tool schemas + dispatcher
  storage.py           # sqlite-backed tasks/reminders/notes
  live_info.py         # weather + web search (keyless)
  system_control.py    # safe app/URL/volume/system-info actions
  reminder_watcher.py  # background thread that fires due reminders
  config.py            # .env-driven settings
  voice/
    listener.py        # mic input, wake-word + STT
    speaker.py          # TTS output
```
