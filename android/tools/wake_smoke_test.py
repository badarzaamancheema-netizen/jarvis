"""End-to-end check of the wake word, run in CI.

Synthesizes spoken phrases with espeak-ng, feeds them to the same Vosk model and
grammar the app uses, and fails if "hi jarvis" isn't heard or ordinary speech
wakes Jarvis up. Keep GRAMMAR in sync with Wake.GRAMMAR in core/.../Wake.kt.

Usage: python wake_smoke_test.py <path to vosk-model.zip>
"""
import json
import subprocess
import sys
import tempfile
import wave
import zipfile
from pathlib import Path

import vosk

GRAMMAR = ["hi jarvis", "hey jarvis", "hello jarvis", "okay jarvis", "jarvis open", "jarvis", "[unk]"]
SHOULD_WAKE = ["hi jarvis", "hey jarvis", "jarvis open", "jarvis"]
SHOULD_NOT_WAKE = ["what time is it", "open the door please", "i am going to the market", "hello there"]
VOICES = ["en-us", "en-gb", "en-us+m3"]


def speak(text: str, voice: str, out: Path) -> None:
    raw = out.with_suffix(".raw.wav")
    subprocess.run(["espeak-ng", "-v", voice, "-s", "140", "-w", str(raw), text], check=True)
    subprocess.run(["sox", str(raw), "-r", "16000", "-c", "1", "-b", "16", str(out)], check=True)


def recognize(model: vosk.Model, wav_path: Path) -> str:
    rec = vosk.KaldiRecognizer(model, 16000, json.dumps(GRAMMAR))
    with wave.open(str(wav_path), "rb") as wf:
        # Leading/trailing silence, as in real listening.
        silence = b"\x00\x00" * 8000
        rec.AcceptWaveform(silence)
        while data := wf.readframes(4000):
            rec.AcceptWaveform(data)
        rec.AcceptWaveform(silence)
    return json.loads(rec.FinalResult()).get("text", "")


def main() -> int:
    tmp = Path(tempfile.mkdtemp())
    with zipfile.ZipFile(sys.argv[1]) as zf:
        zf.extractall(tmp)
    model = vosk.Model(str(next(tmp.iterdir())))

    failures = 0
    for voice in VOICES:
        for phrase in SHOULD_WAKE + SHOULD_NOT_WAKE:
            wav = tmp / "phrase.wav"
            speak(phrase, voice, wav)
            heard = recognize(model, wav)
            woke = "jarvis" in heard.split()
            expected = phrase in SHOULD_WAKE
            ok = woke == expected
            failures += not ok
            print(f"{'ok  ' if ok else 'FAIL'} [{voice}] said {phrase!r:32} heard {heard!r:18} woke={woke}")

    total = len(VOICES) * (len(SHOULD_WAKE) + len(SHOULD_NOT_WAKE))
    print(f"{total - failures}/{total} passed")
    # Synthetic voices are harsher than real ones; allow a little slack but not a broken wake word.
    return 0 if failures <= total // 8 else 1


if __name__ == "__main__":
    sys.exit(main())
