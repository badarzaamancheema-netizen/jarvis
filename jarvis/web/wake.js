// Wake-phrase detection for the phone app. Plain function with no browser
// dependencies so it can be unit-tested with `node --test tests/web`.
(function (root) {
  // Speech recognizers often mis-hear "Jarvis"; these are the common variants.
  const NAMES = "jarvis|jarvi|jarves|jarviss|javis|jervis|jarbis|jaarvis";
  const GREETINGS = "hi|hey|hello|hay|ok|okay|yo|oi";
  const WAKE_RE = new RegExp(
    "(?:^|\\b)(?:(?:" + GREETINGS + ")[\\s,]+)?(?:" + NAMES + ")\\b[\\s,.!?:;-]*(.*)$",
    "i"
  );
  // Remainders that just mean "wake up", e.g. "Jarvis open", "hi Jarvis you there".
  const JUST_WAKE_RE = /^(?:open|opened|on|wake up|start|are you there|you there|please|now)?[\s.!?,]*$/i;

  /**
   * Returns null when the transcript doesn't contain the wake word.
   * Otherwise returns { command } — the words after the wake word, or "" when
   * the user only woke Jarvis up ("hi Jarvis", "Jarvis open").
   */
  function parseWake(transcript) {
    if (!transcript) return null;
    const m = String(transcript).trim().match(WAKE_RE);
    if (!m) return null;
    const rest = m[1].trim();
    if (JUST_WAKE_RE.test(rest)) return { command: "" };
    return { command: rest.replace(/[\s,]+$/, "") };
  }

  const api = { parseWake };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.JarvisWake = api;
})(this);
