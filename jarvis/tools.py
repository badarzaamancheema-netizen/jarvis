"""Tool schemas (Claude tool-use format) and the dispatcher that runs them."""
from datetime import datetime

from jarvis import live_info, storage, system_control

TOOLS = [
    {
        "name": "get_weather",
        "description": "Get the current weather for a city or location. Leave location blank for the user's approximate location.",
        "input_schema": {
            "type": "object",
            "properties": {"location": {"type": "string", "description": "City name, e.g. 'London' or 'Karachi'. Optional."}},
        },
    },
    {
        "name": "web_search",
        "description": "Search the web for current information (news, facts, prices, anything Claude's training data may not have).",
        "input_schema": {
            "type": "object",
            "properties": {"query": {"type": "string", "description": "The search query."}},
            "required": ["query"],
        },
    },
    {
        "name": "add_task",
        "description": "Add an item to the user's to-do list.",
        "input_schema": {
            "type": "object",
            "properties": {"description": {"type": "string"}},
            "required": ["description"],
        },
    },
    {
        "name": "list_tasks",
        "description": "List the user's open to-do items.",
        "input_schema": {"type": "object", "properties": {}},
    },
    {
        "name": "complete_task",
        "description": "Mark a to-do item as done, given its numeric id (from list_tasks).",
        "input_schema": {
            "type": "object",
            "properties": {"task_id": {"type": "integer"}},
            "required": ["task_id"],
        },
    },
    {
        "name": "add_reminder",
        "description": "Set a reminder for a specific future date/time.",
        "input_schema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "What to remind the user about."},
                "due_at": {
                    "type": "string",
                    "description": "ISO 8601 datetime for when the reminder is due, e.g. '2026-08-27T18:00:00'.",
                },
            },
            "required": ["text", "due_at"],
        },
    },
    {
        "name": "list_reminders",
        "description": "List the user's pending reminders.",
        "input_schema": {"type": "object", "properties": {}},
    },
    {
        "name": "add_note",
        "description": "Save a freeform note for the user to recall later.",
        "input_schema": {
            "type": "object",
            "properties": {"content": {"type": "string"}},
            "required": ["content"],
        },
    },
    {
        "name": "list_notes",
        "description": "List all saved notes.",
        "input_schema": {"type": "object", "properties": {}},
    },
    {
        "name": "open_app",
        "description": "Open an application the user has pre-configured by nickname (see jarvis_data/apps.json).",
        "input_schema": {
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"],
        },
    },
    {
        "name": "open_url",
        "description": "Open a URL in the user's default web browser.",
        "input_schema": {
            "type": "object",
            "properties": {"url": {"type": "string"}},
            "required": ["url"],
        },
    },
    {
        "name": "set_volume",
        "description": "Set the system output volume, 0-100.",
        "input_schema": {
            "type": "object",
            "properties": {"level": {"type": "integer", "minimum": 0, "maximum": 100}},
            "required": ["level"],
        },
    },
    {
        "name": "get_system_info",
        "description": "Get the current date/time and basic system info.",
        "input_schema": {"type": "object", "properties": {}},
    },
]


def run_tool(name: str, tool_input: dict) -> str:
    if name == "get_weather":
        return live_info.get_weather(tool_input.get("location", ""))
    if name == "web_search":
        return live_info.web_search(tool_input["query"])
    if name == "add_task":
        task_id = storage.add_task(tool_input["description"])
        return f"Added task #{task_id}: {tool_input['description']}"
    if name == "list_tasks":
        tasks = storage.list_tasks()
        if not tasks:
            return "No open tasks."
        return "\n".join(f"#{t['id']}: {t['description']}" for t in tasks)
    if name == "complete_task":
        ok = storage.complete_task(tool_input["task_id"])
        return "Done." if ok else f"No task with id {tool_input['task_id']}."
    if name == "add_reminder":
        reminder_id = storage.add_reminder(tool_input["text"], tool_input["due_at"])
        return f"Reminder #{reminder_id} set for {tool_input['due_at']}."
    if name == "list_reminders":
        reminders = storage.list_reminders()
        if not reminders:
            return "No pending reminders."
        return "\n".join(f"#{r['id']} at {r['due_at']}: {r['text']}" for r in reminders)
    if name == "add_note":
        note_id = storage.add_note(tool_input["content"])
        return f"Saved note #{note_id}."
    if name == "list_notes":
        notes = storage.list_notes()
        if not notes:
            return "No saved notes."
        return "\n".join(f"#{n['id']}: {n['content']}" for n in notes)
    if name == "open_app":
        return system_control.open_app(tool_input["name"])
    if name == "open_url":
        return system_control.open_url(tool_input["url"])
    if name == "set_volume":
        return system_control.set_volume(tool_input["level"])
    if name == "get_system_info":
        return system_control.get_system_info()
    return f"Unknown tool: {name}"


def now_iso() -> str:
    return datetime.now().isoformat(timespec="seconds")
