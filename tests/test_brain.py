from types import SimpleNamespace

from jarvis import brain


class _FakeMessages:
    def __init__(self, responses):
        self._responses = list(responses)

    def create(self, **kwargs):
        return self._responses.pop(0)


class _FakeAnthropic:
    def __init__(self, responses):
        self.messages = _FakeMessages(responses)


def _text_block(text):
    return SimpleNamespace(type="text", text=text)


def _tool_use_block(id_, name, tool_input):
    return SimpleNamespace(type="tool_use", id=id_, name=name, input=tool_input)


def _make_jarvis(monkeypatch, responses):
    monkeypatch.setattr(brain.SETTINGS, "anthropic_api_key", "test-key")
    j = brain.Jarvis.__new__(brain.Jarvis)
    j.client = _FakeAnthropic(responses)
    j.model = "claude-sonnet-5"
    j.system_prompt = "test system prompt"
    j.history = []
    j.tools = brain.TOOLS
    j.tool_runner = None
    return j


def test_ask_returns_plain_text_reply(monkeypatch):
    reply = SimpleNamespace(stop_reason="end_turn", content=[_text_block("Hello, sir.")])
    j = _make_jarvis(monkeypatch, [reply])

    result = j.ask("hi")

    assert result == "Hello, sir."
    assert j.history[0] == {"role": "user", "content": "hi"}


def test_ask_executes_tool_then_returns_text(monkeypatch):
    monkeypatch.setattr(
        brain,
        "run_tool",
        lambda name, tool_input: f"ran {name} with {tool_input}",
    )

    tool_call = SimpleNamespace(
        stop_reason="tool_use",
        content=[_tool_use_block("tool1", "get_system_info", {})],
    )
    final = SimpleNamespace(stop_reason="end_turn", content=[_text_block("It's noon.")])
    j = _make_jarvis(monkeypatch, [tool_call, final])

    result = j.ask("what time is it")

    assert result == "It's noon."
    tool_result_message = j.history[-2]
    assert tool_result_message["role"] == "user"
    assert tool_result_message["content"][0]["content"] == "ran get_system_info with {}"
