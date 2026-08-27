"""SQLite-backed storage for tasks, reminders, and notes."""
import sqlite3
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Optional

from jarvis.config import DB_PATH

SCHEMA = """
CREATE TABLE IF NOT EXISTS tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    description TEXT NOT NULL,
    done INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS reminders (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    text TEXT NOT NULL,
    due_at TEXT NOT NULL,
    fired INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS notes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL
);
"""


@contextmanager
def connect(db_path: Optional[Path] = None):
    conn = sqlite3.connect(str(db_path or DB_PATH))
    conn.row_factory = sqlite3.Row
    try:
        conn.executescript(SCHEMA)
        yield conn
        conn.commit()
    finally:
        conn.close()


def add_task(description: str, db_path: Optional[Path] = None) -> int:
    with connect(db_path) as conn:
        cur = conn.execute(
            "INSERT INTO tasks (description, done, created_at) VALUES (?, 0, ?)",
            (description, datetime.now().isoformat()),
        )
        return cur.lastrowid


def list_tasks(include_done: bool = False, db_path: Optional[Path] = None) -> list[dict]:
    with connect(db_path) as conn:
        query = "SELECT * FROM tasks"
        if not include_done:
            query += " WHERE done = 0"
        query += " ORDER BY id"
        return [dict(row) for row in conn.execute(query)]


def complete_task(task_id: int, db_path: Optional[Path] = None) -> bool:
    with connect(db_path) as conn:
        cur = conn.execute("UPDATE tasks SET done = 1 WHERE id = ?", (task_id,))
        return cur.rowcount > 0


def add_reminder(text: str, due_at: str, db_path: Optional[Path] = None) -> int:
    with connect(db_path) as conn:
        cur = conn.execute(
            "INSERT INTO reminders (text, due_at, fired, created_at) VALUES (?, ?, 0, ?)",
            (text, due_at, datetime.now().isoformat()),
        )
        return cur.lastrowid


def list_reminders(include_fired: bool = False, db_path: Optional[Path] = None) -> list[dict]:
    with connect(db_path) as conn:
        query = "SELECT * FROM reminders"
        if not include_fired:
            query += " WHERE fired = 0"
        query += " ORDER BY due_at"
        return [dict(row) for row in conn.execute(query)]


def due_reminders(now_iso: str, db_path: Optional[Path] = None) -> list[dict]:
    with connect(db_path) as conn:
        rows = conn.execute(
            "SELECT * FROM reminders WHERE fired = 0 AND due_at <= ?", (now_iso,)
        )
        return [dict(row) for row in rows]


def mark_reminder_fired(reminder_id: int, db_path: Optional[Path] = None) -> None:
    with connect(db_path) as conn:
        conn.execute("UPDATE reminders SET fired = 1 WHERE id = ?", (reminder_id,))


def add_note(content: str, db_path: Optional[Path] = None) -> int:
    with connect(db_path) as conn:
        cur = conn.execute(
            "INSERT INTO notes (content, created_at) VALUES (?, ?)",
            (content, datetime.now().isoformat()),
        )
        return cur.lastrowid


def list_notes(db_path: Optional[Path] = None) -> list[dict]:
    with connect(db_path) as conn:
        return [dict(row) for row in conn.execute("SELECT * FROM notes ORDER BY id DESC")]
