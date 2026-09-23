# Jarvis for Android

A native Android app: say **"Hi Jarvis"** (or "Hey Jarvis", "Jarvis open") at any
time, even with the screen off, and it answers out loud.

## Install it (no computer needed)

1. **Get an Anthropic API key.** Go to https://console.anthropic.com, sign in,
   add a few dollars of credit under *Billing*, then create a key under
   *API keys*. It starts with `sk-ant-`. This is separate from a Claude.ai
   subscription, and you pay per use.
2. **Download the app on your phone.** Open
   https://github.com/badarzaamancheema-netizen/jarvis/releases/tag/jarvis-latest
   and tap **Jarvis.apk**.
3. **Install it.** Open the downloaded file. Android will ask you to allow
   installing from your browser or Files app. Allow it, then tap *Install*. If
   Play Protect warns that the app is unknown, tap *More details → Install anyway*.
   It's unknown because you built it yourself, not because it's harmful.
4. **Set it up.** Open Jarvis, paste your API key, tap **Save**, then tap
   **Start listening for "Hi Jarvis"** and allow the microphone and notifications.
5. **Recommended.** Under ⚙ Settings, tap each of these:
   - *Let Jarvis open apps when the screen is off.* Without it, "open YouTube"
     while you're in another app shows a notification you have to tap.
   - *Stop Android killing Jarvis.* Samsung, Xiaomi, Oppo and others stop
     background apps aggressively.
   - *Let Jarvis find contacts*, so "call Mom" works.
   - *On-time reminders.*

A small "Jarvis: Listening for Hi Jarvis" notification stays up while it's
listening. That's Android's rule for any app using the mic in the background.

## What you can say

- "Hi Jarvis" … "what's the weather in Lahore?" / "latest cricket score?" (live web search)
- "Open WhatsApp" / "open the camera" / "open settings" (any installed app)
- "Call Mom" / "text Ali I'm running late" / "WhatsApp Sara saying on my way"
- "Navigate to Liberty Market" / "play Atif Aslam on YouTube"
- "Wake me up at 6:30" / "set a timer for 10 minutes"
- "Remind me to take my medicine at 9pm" / "add milk to my to-do list" / "note that the wifi password is …"
- "Turn on the flashlight" / "volume 40 percent" / "how's my battery?"

After Jarvis answers, it keeps listening for a few seconds, so you can follow
up without saying "Hi Jarvis" again.

## How it works

- **Wake word:** [Vosk](https://alphacephei.com/vosk/) runs fully on the phone,
  limited to a few phrases like "hi jarvis". No audio leaves the phone while
  it's waiting.
- **Your command:** Google's speech recognizer, or the on-device model if
  Google's isn't available (for example, when the phone is locked).
- **Brain:** Claude, through Anthropic's official Java SDK, with tools for the
  phone and server-side web search. Tasks, notes and reminders are stored on
  the phone.

## Honest limits

- It uses more battery than normal, because the mic is always on.
- Android may still stop it after a while on some brands, even with the
  battery setting. Opening Jarvis restarts it.
- The wake word is tuned for English and can mishear. A TV in the background
  can trigger it now and then.
- Calls and texts open ready to go, but **you** press call or send. That's
  deliberate, so a misheard command can't message the wrong person.

## Updating

Every change pushed to this repo rebuilds the app, and the same download link
gets the new version. Install it over the old one. If Android says the app
"conflicts with an existing package", the signing key changed: uninstall Jarvis
first, which erases its tasks and notes. To stop that from ever happening, give
the build a permanent key:

```bash
keytool -genkeypair -v -keystore jarvis.jks -alias jarvis -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 jarvis.jks   # copy the output
```

Then in GitHub, go to *Settings → Secrets and variables → Actions* and add
`JARVIS_KEYSTORE_BASE64` (the base64 text) and `JARVIS_KEYSTORE_PASSWORD`.

## Building it yourself (optional)

With Android Studio: open the `android/` folder, then Run. From a terminal:
`cd android && ./gradlew :app:assembleDebug`. The Claude/tool logic has unit
tests that don't need the Android SDK: `JARVIS_CORE_ONLY=1 ./gradlew :core:test`.
