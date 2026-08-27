"""Live info lookups: weather (wttr.in) and web search (DuckDuckGo).

Both are keyless so the assistant works with only an ANTHROPIC_API_KEY set.
"""
import requests


def get_weather(location: str = "") -> str:
    """Fetch a one-line weather summary for a location (blank = caller's IP-based location)."""
    try:
        resp = requests.get(
            f"https://wttr.in/{requests.utils.quote(location)}",
            params={"format": "j1"},
            timeout=8,
        )
        resp.raise_for_status()
        data = resp.json()
        current = data["current_condition"][0]
        area = data.get("nearest_area", [{}])[0]
        place = ", ".join(
            filter(None, [
                area.get("areaName", [{}])[0].get("value"),
                area.get("country", [{}])[0].get("value"),
            ])
        ) or (location or "your location")
        return (
            f"In {place}: {current['weatherDesc'][0]['value']}, "
            f"{current['temp_C']}°C ({current['temp_F']}°F), "
            f"feels like {current['FeelsLikeC']}°C, "
            f"humidity {current['humidity']}%."
        )
    except (requests.RequestException, KeyError, IndexError, ValueError) as exc:
        return f"I couldn't fetch the weather right now ({exc})."


def web_search(query: str, max_results: int = 4) -> str:
    """Run a keyless web search and return a short summary of top results."""
    try:
        from ddgs import DDGS

        with DDGS() as ddgs:
            results = list(ddgs.text(query, max_results=max_results))
        if not results:
            return f"I couldn't find anything for '{query}'."
        lines = [f"Top results for '{query}':"]
        for r in results:
            title = r.get("title", "").strip()
            body = r.get("body", "").strip()
            href = r.get("href", "").strip()
            lines.append(f"- {title}: {body} ({href})")
        return "\n".join(lines)
    except Exception as exc:  # noqa: BLE001 - surface any search backend failure as text
        return f"The web search failed ({exc})."
