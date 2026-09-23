from jarvis.mobile_tools import MOBILE_TOOLS, MobileToolRunner


def _names():
    return {t["name"] for t in MOBILE_TOOLS}


def test_server_only_tools_are_not_offered_on_phone():
    names = _names()
    assert not names & {"open_app", "set_volume", "get_system_info"}
    assert {"add_task", "add_reminder", "get_weather", "phone_call", "navigate"} <= names


def test_tool_names_are_unique():
    names = [t["name"] for t in MOBILE_TOOLS]
    assert len(names) == len(set(names))


def test_phone_call_queues_clean_tel_link():
    runner = MobileToolRunner()
    result = runner("phone_call", {"number": "+92 300-123 4567"})
    assert "opening on the phone" in result
    assert runner.actions == [{"type": "open", "url": "tel:+923001234567", "label": "Calling +923001234567"}]


def test_phone_call_rejects_non_numbers():
    runner = MobileToolRunner()
    assert "doesn't look like" in runner("phone_call", {"number": "mom"})
    assert runner.actions == []


def test_sms_and_whatsapp_encode_message():
    runner = MobileToolRunner()
    runner("send_sms", {"number": "123456", "message": "on my way & late"})
    runner("send_whatsapp", {"message": "hi there"})
    assert runner.actions[0]["url"] == "sms:123456?&body=on%20my%20way%20%26%20late"
    assert runner.actions[1]["url"] == "https://wa.me/?text=hi%20there"


def test_open_url_rejects_other_schemes():
    runner = MobileToolRunner()
    assert "only open web" in runner("open_url", {"url": "javascript:alert(1)"})
    assert "only open web" in runner("open_url", {"url": "file:///etc/passwd"})
    runner("open_url", {"url": "example.com"})
    assert runner.actions == [{"type": "open", "url": "https://example.com", "label": "Opening https://example.com"}]


def test_open_phone_app_uses_allowlist():
    runner = MobileToolRunner()
    runner("open_phone_app", {"name": "YouTube"})
    assert runner.actions[0]["url"] == "https://www.youtube.com/"
    assert "don't know" in runner("open_phone_app", {"name": "settings"})


def test_navigate_and_youtube_build_search_urls():
    runner = MobileToolRunner()
    runner("navigate", {"destination": "Liberty Market, Lahore"})
    runner("play_youtube", {"query": "lofi beats"})
    assert runner.actions[0]["url"] == "https://www.google.com/maps/dir/?api=1&destination=Liberty+Market%2C+Lahore"
    assert runner.actions[1]["url"] == "https://www.youtube.com/results?search_query=lofi+beats"


def test_shared_tools_fall_through_to_server_implementation(monkeypatch):
    from jarvis import mobile_tools

    monkeypatch.setattr(mobile_tools, "run_tool", lambda name, tool_input: f"server ran {name}")
    runner = MobileToolRunner()
    assert runner("add_task", {"description": "x"}) == "server ran add_task"
    assert runner.actions == []
