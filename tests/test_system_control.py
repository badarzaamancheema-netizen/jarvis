import json

from jarvis import system_control


def test_open_app_rejects_unknown_app(monkeypatch, tmp_path):
    apps_path = tmp_path / "apps.json"
    apps_path.write_text(json.dumps({"browser": "Google Chrome"}))
    monkeypatch.setattr(system_control, "APPS_CONFIG_PATH", apps_path)

    result = system_control.open_app("some-random-app")

    assert "browser" in result
    assert "don't have" in result


def test_open_app_missing_allowlist_file(monkeypatch, tmp_path):
    monkeypatch.setattr(system_control, "APPS_CONFIG_PATH", tmp_path / "missing.json")
    result = system_control.open_app("browser")
    assert "none configured" in result


def test_open_app_launches_allowed_app(monkeypatch, tmp_path):
    apps_path = tmp_path / "apps.json"
    apps_path.write_text(json.dumps({"browser": "Google Chrome"}))
    monkeypatch.setattr(system_control, "APPS_CONFIG_PATH", apps_path)

    calls = []
    monkeypatch.setattr(system_control.subprocess, "Popen", lambda args, **kw: calls.append(args))
    monkeypatch.setattr(system_control.platform, "system", lambda: "Linux")

    result = system_control.open_app("Browser")

    assert calls == [["Google Chrome"]]
    assert "Opening" in result


def test_set_volume_clamps_range(monkeypatch):
    monkeypatch.setattr(system_control.platform, "system", lambda: "Darwin")
    calls = []
    monkeypatch.setattr(system_control.subprocess, "run", lambda args, **kw: calls.append(args))

    system_control.set_volume(150)

    assert calls == [["osascript", "-e", "set volume output volume 100"]]


def test_open_url_adds_scheme(monkeypatch):
    opened = {}
    monkeypatch.setattr(system_control.webbrowser, "open", lambda url: opened.setdefault("url", url))
    system_control.open_url("example.com")
    assert opened["url"] == "https://example.com"
