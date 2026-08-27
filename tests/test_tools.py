import tempfile
from pathlib import Path

from jarvis import storage, tools


def test_run_tool_add_and_list_tasks(monkeypatch):
    db = Path(tempfile.NamedTemporaryFile(suffix=".db", delete=False).name)
    monkeypatch.setattr(storage, "DB_PATH", db)
    monkeypatch.setattr(tools.storage, "DB_PATH", db)

    add_result = tools.run_tool("add_task", {"description": "walk the dog"})
    assert "walk the dog" in add_result

    list_result = tools.run_tool("list_tasks", {})
    assert "walk the dog" in list_result


def test_run_tool_unknown_tool_returns_message():
    assert "Unknown tool" in tools.run_tool("does_not_exist", {})


def test_run_tool_get_system_info_mentions_platform():
    result = tools.run_tool("get_system_info", {})
    assert "Running on" in result
