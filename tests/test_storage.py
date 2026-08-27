import tempfile
from pathlib import Path

from jarvis import storage


def _tmp_db():
    tmp = tempfile.NamedTemporaryFile(suffix=".db", delete=False)
    tmp.close()
    return Path(tmp.name)


def test_add_and_list_tasks():
    db = _tmp_db()
    task_id = storage.add_task("buy milk", db_path=db)
    tasks = storage.list_tasks(db_path=db)
    assert len(tasks) == 1
    assert tasks[0]["id"] == task_id
    assert tasks[0]["description"] == "buy milk"
    assert tasks[0]["done"] == 0


def test_complete_task_hides_it_from_default_listing():
    db = _tmp_db()
    task_id = storage.add_task("buy milk", db_path=db)
    assert storage.complete_task(task_id, db_path=db) is True
    assert storage.list_tasks(db_path=db) == []
    assert len(storage.list_tasks(include_done=True, db_path=db)) == 1


def test_complete_task_unknown_id_returns_false():
    db = _tmp_db()
    assert storage.complete_task(999, db_path=db) is False


def test_reminders_due_and_fire():
    db = _tmp_db()
    storage.add_reminder("call mom", "2020-01-01T00:00:00", db_path=db)
    due = storage.due_reminders("2025-01-01T00:00:00", db_path=db)
    assert len(due) == 1
    storage.mark_reminder_fired(due[0]["id"], db_path=db)
    assert storage.due_reminders("2025-01-01T00:00:00", db_path=db) == []


def test_reminders_not_yet_due_are_excluded():
    db = _tmp_db()
    storage.add_reminder("future", "2099-01-01T00:00:00", db_path=db)
    assert storage.due_reminders("2025-01-01T00:00:00", db_path=db) == []


def test_notes_roundtrip():
    db = _tmp_db()
    storage.add_note("remember the milk", db_path=db)
    notes = storage.list_notes(db_path=db)
    assert notes[0]["content"] == "remember the milk"
