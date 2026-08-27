"""Background thread that announces reminders when they come due."""
import threading
import time
from typing import Callable

from jarvis import storage
from jarvis.tools import now_iso


class ReminderWatcher(threading.Thread):
    def __init__(self, announce: Callable[[str], None], poll_seconds: int = 30):
        super().__init__(daemon=True)
        self.announce = announce
        self.poll_seconds = poll_seconds
        self._stop_event = threading.Event()

    def stop(self) -> None:
        self._stop_event.set()

    def run(self) -> None:
        while not self._stop_event.is_set():
            for reminder in storage.due_reminders(now_iso()):
                storage.mark_reminder_fired(reminder["id"])
                self.announce(f"Reminder: {reminder['text']}")
            self._stop_event.wait(self.poll_seconds)
