"""Text-to-speech output (offline, via pyttsx3)."""
import threading

from jarvis.config import SETTINGS

_lock = threading.Lock()


class Speaker:
    def __init__(self):
        self._engine = None

    def _get_engine(self):
        if self._engine is None:
            import pyttsx3

            self._engine = pyttsx3.init()
            self._engine.setProperty("rate", SETTINGS.tts_rate)
        return self._engine

    def say(self, text: str) -> None:
        if not text:
            return
        with _lock:
            engine = self._get_engine()
            engine.say(text)
            engine.runAndWait()
