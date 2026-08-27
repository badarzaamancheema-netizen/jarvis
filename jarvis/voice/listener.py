"""Speech-to-text and wake-word detection, built on SpeechRecognition.

There's no bundled wake-word model (Porcupine etc. need a paid access key),
so instead we continuously transcribe short utterances with the free Google
Web Speech recognizer and look for the wake word in the text. This is less
robust than a dedicated wake-word engine and needs network access, but
requires no extra API keys beyond the ANTHROPIC_API_KEY already in use.
"""
from dataclasses import dataclass
from typing import Callable, Optional

from jarvis.config import SETTINGS


@dataclass
class HeardUtterance:
    text: str
    contains_wake_word: bool
    command: str


def _strip_wake_word(text: str) -> str:
    lowered = text.lower()
    wake = SETTINGS.wake_word
    idx = lowered.find(wake)
    if idx == -1:
        return text
    return text[idx + len(wake):].strip(" ,.:;")


class Listener:
    """Wraps SpeechRecognition's microphone + recognizer."""

    def __init__(self):
        import speech_recognition as sr

        self._sr = sr
        self.recognizer = sr.Recognizer()
        self.microphone = sr.Microphone()
        with self.microphone as source:
            self.recognizer.adjust_for_ambient_noise(source, duration=1)

    def listen_once(self, timeout: Optional[float] = None, phrase_time_limit: Optional[float] = 8) -> Optional[str]:
        """Block until one utterance is captured and transcribed, or timeout/silence."""
        with self.microphone as source:
            try:
                audio = self.recognizer.listen(source, timeout=timeout, phrase_time_limit=phrase_time_limit)
            except self._sr.WaitTimeoutError:
                return None
        try:
            return self.recognizer.recognize_google(audio)
        except (self._sr.UnknownValueError, self._sr.RequestError):
            return None

    def wait_for_wake_word(self) -> None:
        """Block until an utterance containing the wake word is heard."""
        while True:
            text = self.listen_once(timeout=None, phrase_time_limit=4)
            if text and SETTINGS.wake_word in text.lower():
                return

    def listen_for_command(self) -> Optional[str]:
        """Listen for the command that follows the wake word."""
        text = self.listen_once(timeout=5, phrase_time_limit=10)
        return text
