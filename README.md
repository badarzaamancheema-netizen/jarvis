# Jarvis

> **On Android?** Use the native app in [`android/`](android/README.md). It listens
> for "Hi Jarvis" even with the screen off, and you install it straight from
> this repo's Releases page with no computer needed. It runs free on Google Gemini's
> free tier, or on Claude if you add a paid key.

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

## On your phone ("Hi Jarvis" / "Jarvis open")

`python -m jarvis.server` serves Jarvis as an installable phone app (a PWA),
so you don't need an app store. While the app is open it listens for **"Hi Jarvis"**,
**"Hey Jarvis"** or **"Jarvis open"**. You can say the command in the same
breath ("Hi Jarvis, what's the weather in Lahore") or wait for "Yes, sir?". After
each answer it keeps listening for a few seconds so you can follow up without
saying the wake word again.

On the phone it can also: open apps and sites (YouTube, WhatsApp, Instagram,
Maps, Gmail, Spotify…), start a call, prefill an SMS or WhatsApp message,
start Google Maps directions, and search/play on YouTube. Reminders are spoken
and shown as notifications while the app is open, using your phone's clock.

### Limits to know first

- **Nothing can listen for "Hi Jarvis" while your phone is locked or the app is
  closed.** Android and iOS only let their own assistants (Google/Siri) do that.
  So you launch Jarvis *through* them, like this:
  - **iPhone:** Shortcuts app → **+** → *Add Action* → **Open URLs** → your
    Jarvis URL followed by `/?wake=1` → name the shortcut **"Jarvis open"**.
    Then say *"Hey Siri, Jarvis open"*.
  - **Android:** install the app (Chrome menu → *Add to Home screen / Install
    app*), then say *"Hey Google, open Jarvis"*. For a custom phrase like
    "Hi Jarvis", create a Google Assistant **Routine** with that starter phrase
    and the action *"open Jarvis"*.
  Opened that way, Jarvis goes straight to "Yes, sir?".
- While Jarvis is open it keeps the screen on so the mic keeps working. If you
  leave it open all day, keep the phone plugged in.
- The first launch needs one tap (**Activate**), because browsers only allow
  the mic and speech after you touch the screen.
- Your phone may block Jarvis from opening another app (call, YouTube…)
  without a tap. When it does, tap the button under Jarvis's reply.
- Voice needs Chrome on Android or Safari on iPhone. Typing works everywhere.
- It can't read your contacts: say or type the number.

### Setup

1. On a computer (or a cheap cloud server) that stays on:
   ```bash
   pip install -r requirements-server.txt
   cp .env.example .env   # add ANTHROPIC_API_KEY and JARVIS_ACCESS_TOKEN
   python -m jarvis.server  # listens on port 8000
   ```
   `JARVIS_ACCESS_TOKEN` is the password your phone uses. The server won't
   start without it, because anyone who can reach it could otherwise spend
   your Anthropic credit and read your notes.
2. **Give it an HTTPS address.** Phones only allow the microphone on `https://`
   pages, so `http://192.168.x.x:8000` won't work for voice. The easiest options:
   - [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/):
     `cloudflared tunnel --url http://localhost:8000` prints a public `https://…trycloudflare.com` URL.
   - [Tailscale](https://tailscale.com/kb/1223/funnel): `tailscale serve 8000` (private to your own devices).
3. Open that URL on your phone, enter the token, tap **Activate**, and allow the
   microphone. Then install it to your home screen and set up the Siri or
   Google shortcut above.

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
pip install pytest httpx -r requirements-server.txt
pytest
node --test tests/web/*.test.js   # wake-phrase parser
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
  server.py            # phone app server (FastAPI) + JSON API
  mobile_tools.py      # phone-side tools (call, SMS, WhatsApp, maps, YouTube…)
  web/                 # the installable phone app (HTML/JS, service worker)
  voice/
    listener.py        # mic input, wake-word + STT
    speaker.py          # TTS output
```
