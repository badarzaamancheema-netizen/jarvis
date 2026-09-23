// Jarvis phone app: wake-word listening, talking to the server, speaking
// replies, running phone actions, and announcing reminders.
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const store = {
    get(key, fallback) {
      try { const v = localStorage.getItem("jarvis." + key); return v === null ? fallback : v; } catch { return fallback; }
    },
    set(key, value) { try { localStorage.setItem("jarvis." + key, value); } catch { /* private mode */ } },
  };

  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  const COMMAND_TIMEOUT_MS = 8000;
  const FOLLOW_UP_TIMEOUT_MS = 7000;
  const REMINDER_POLL_MS = 30000;
  const SAFE_URL = /^(https?:|tel:|sms:)/i;

  let token = store.get("token", "");
  let followUp = store.get("followUp", "1") === "1";
  let sessionId = store.get("session", "");
  if (!sessionId) { sessionId = newSessionId(); store.set("session", sessionId); }

  let mode = "off";          // off | wake | command | thinking | speaking
  let recognition = null;
  let commandTimer = null;
  let restartTimer = null;
  let wakeLock = null;
  let started = false;
  const wantsCommandOnLaunch = new URLSearchParams(location.search).get("wake") === "1";

  // ---------- UI helpers ----------
  function setMode(next, statusText) {
    mode = next;
    document.body.dataset.state = next;
    const labels = {
      off: "Tap the orb to start",
      wake: "Say “Hi Jarvis”",
      command: "Listening…",
      thinking: "Thinking…",
      speaking: "",
    };
    $("status").textContent = statusText !== undefined ? statusText : labels[next];
    if (next !== "command" && next !== "wake") $("heard").textContent = "";
  }

  function addMsg(kind, text) {
    const el = document.createElement("div");
    el.className = "msg " + kind;
    el.textContent = text;
    $("log").appendChild(el);
    $("log").scrollTop = $("log").scrollHeight;
    return el;
  }

  function newSessionId() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return String(Date.now()) + Math.random().toString(16).slice(2);
  }

  function localIsoNow() {
    const d = new Date();
    return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 19);
  }

  // ---------- Server ----------
  async function api(path, options) {
    const res = await fetch(path, {
      ...options,
      headers: { "Content-Type": "application/json", Authorization: "Bearer " + token, ...(options && options.headers) },
    });
    if (res.status === 401) {
      showSetup("That access token was rejected. Check JARVIS_ACCESS_TOKEN on your server.");
      throw new Error("unauthorized");
    }
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.detail || "Server error " + res.status);
    return body;
  }

  // ---------- Speech output ----------
  let voice = null;
  function pickVoice() {
    if (!window.speechSynthesis) return;
    const voices = speechSynthesis.getVoices();
    const prefs = [/UK English Male/i, /Daniel/i, /en-GB/i, /English.*Male/i, /^en/i];
    for (const re of prefs) {
      const v = voices.find((x) => re.test(x.name) || re.test(x.lang));
      if (v) { voice = v; return; }
    }
  }
  if (window.speechSynthesis) {
    pickVoice();
    speechSynthesis.onvoiceschanged = pickVoice;
  }

  function speak(text) {
    return new Promise((resolve) => {
      if (!window.speechSynthesis || !text) return resolve();
      speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text);
      if (voice) { u.voice = voice; u.lang = voice.lang; }
      u.rate = 1.05;
      // onend is unreliable on some phones; never wait forever.
      const fallback = setTimeout(done, 2500 + text.length * 90);
      function done() { clearTimeout(fallback); resolve(); }
      u.onend = done;
      u.onerror = (e) => {
        if (e.error === "not-allowed") needTap();
        done();
      };
      speechSynthesis.speak(u);
    });
  }

  // ---------- Speech input ----------
  function stopRecognition() {
    clearTimeout(restartTimer);
    if (recognition) {
      recognition.onend = null;
      recognition.onresult = null;
      recognition.onerror = null;
      try { recognition.abort(); } catch { /* already stopped */ }
      recognition = null;
    }
  }

  function listen() {
    stopRecognition();
    if (!SpeechRecognition || document.hidden) return;
    const rec = new SpeechRecognition();
    rec.lang = navigator.language || "en-US";
    rec.continuous = false;
    rec.interimResults = true;
    rec.maxAlternatives = 3;

    rec.onresult = (event) => {
      const result = event.results[event.results.length - 1];
      $("heard").textContent = result[0].transcript;
      if (!result.isFinal) return;
      const alternatives = Array.from(result, (alt) => alt.transcript);
      onHeard(alternatives);
    };
    rec.onerror = (event) => {
      if (event.error === "not-allowed" || event.error === "service-not-allowed") {
        stopRecognition();
        needTap();
      } else if (event.error === "network") {
        $("status").textContent = "Speech service unreachable — retrying…";
      }
    };
    rec.onend = () => {
      // Recognizers stop after each phrase or a stretch of silence; keep going.
      if (recognition === rec && (mode === "wake" || mode === "command")) {
        restartTimer = setTimeout(listen, 250);
      }
    };
    recognition = rec;
    try { rec.start(); } catch { restartTimer = setTimeout(listen, 1000); }
  }

  function listenForWake() {
    clearTimeout(commandTimer);
    setMode("wake");
    listen();
  }

  function listenForCommand(timeoutMs) {
    clearTimeout(commandTimer);
    setMode("command");
    listen();
    commandTimer = setTimeout(() => { if (mode === "command") listenForWake(); }, timeoutMs || COMMAND_TIMEOUT_MS);
  }

  function onHeard(alternatives) {
    if (mode === "command") {
      clearTimeout(commandTimer);
      const text = alternatives[0].trim();
      const wake = window.JarvisWake.parseWake(text);
      // "Hi Jarvis, open YouTube" during follow-up: drop the wake word.
      handleCommand(wake && wake.command ? wake.command : text);
      return;
    }
    if (mode !== "wake") return;
    for (const alt of alternatives) {
      const wake = window.JarvisWake.parseWake(alt);
      if (!wake) continue;
      if (wake.command) handleCommand(wake.command);
      else acknowledge();
      return;
    }
  }

  async function acknowledge() {
    stopRecognition();
    setMode("speaking", "");
    await speak("Yes, sir?");
    listenForCommand();
  }

  // ---------- Conversation ----------
  async function handleCommand(text) {
    text = text.trim();
    if (!text) return listenForWake();
    stopRecognition();
    clearTimeout(commandTimer);
    addMsg("you", text);
    setMode("thinking");
    let reply;
    try {
      reply = await api("/api/ask", {
        method: "POST",
        body: JSON.stringify({
          text,
          session_id: sessionId,
          local_time: localIsoNow(),
          timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "",
        }),
      });
    } catch (err) {
      if (err.message !== "unauthorized") addMsg("error", err.message);
      setMode("speaking", "");
      await speak("Sorry, I couldn't reach the server.");
      return started ? listenForWake() : setMode("off");
    }

    const bubble = addMsg("jarvis", reply.reply || "Done.");
    const actions = (reply.actions || []).filter((a) => SAFE_URL.test(a.url));
    for (const action of actions) {
      const link = document.createElement("a");
      link.className = "action";
      link.href = action.url;
      link.target = "_blank";
      link.rel = "noopener";
      link.textContent = action.label + " ↗";
      bubble.appendChild(document.createElement("br"));
      bubble.appendChild(link);
    }

    setMode("speaking", "");
    await speak(reply.reply);

    if (actions.length) {
      // Browsers may block opening another app without a tap; the button
      // above stays as the fallback.
      location.href = actions[0].url;
    }
    if (!started) return setMode("off");
    if (followUp) listenForCommand(FOLLOW_UP_TIMEOUT_MS);
    else listenForWake();
  }

  // ---------- Reminders ----------
  async function pollReminders() {
    if (!token) return;
    try {
      const { reminders } = await api("/api/reminders/due?now=" + encodeURIComponent(localIsoNow()));
      for (const r of reminders) {
        const text = "Reminder: " + r.text;
        addMsg("jarvis", text);
        notify(text);
        if (mode === "wake" || mode === "off") {
          stopRecognition();
          setMode("speaking", "");
          await speak(text);
          started ? listenForWake() : setMode("off");
        }
      }
    } catch { /* offline — try again next tick */ }
  }

  async function notify(text) {
    if (!("Notification" in window) || Notification.permission !== "granted") return;
    try {
      const reg = await navigator.serviceWorker.getRegistration();
      if (reg) reg.showNotification("Jarvis", { body: text, icon: "/static/icon-192.png", tag: "jarvis-" + Date.now() });
      else new Notification("Jarvis", { body: text });
    } catch { /* notifications unavailable */ }
  }

  // ---------- Screen wake lock (mobile browsers stop the mic when the screen sleeps) ----------
  async function keepAwake() {
    if (!("wakeLock" in navigator) || wakeLock || document.hidden) return;
    try {
      wakeLock = await navigator.wakeLock.request("screen");
      wakeLock.addEventListener("release", () => { wakeLock = null; });
    } catch { /* not allowed right now */ }
  }

  // ---------- Startup ----------
  function needTap() {
    started = false;
    setMode("off");
    $("tapToStart").hidden = false;
  }

  function start(fromGesture) {
    $("tapToStart").hidden = true;
    started = true;
    if (fromGesture) {
      // Unlock speech output on iOS/Android: it must first happen inside a tap.
      if (window.speechSynthesis) speechSynthesis.speak(new SpeechSynthesisUtterance(""));
      if ("Notification" in window && Notification.permission === "default") Notification.requestPermission();
    }
    keepAwake();
    if (!SpeechRecognition) {
      setMode("off", "Voice isn't supported in this browser — use Chrome on Android or Safari on iPhone. Typing still works.");
      return;
    }
    if (wantsCommandOnLaunch && !start.launched) {
      start.launched = true;
      acknowledge();
    } else {
      listenForWake();
    }
  }

  function showSetup(message) {
    if (message) addMsg("error", message);
    $("tokenInput").value = token;
    $("followUpInput").checked = followUp;
    $("setup").hidden = false;
  }

  $("setupForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    token = $("tokenInput").value.trim();
    followUp = $("followUpInput").checked;
    store.set("token", token);
    store.set("followUp", followUp ? "1" : "0");
    try {
      await api("/api/ping");
      $("setup").hidden = true;
      start(true);
    } catch (err) {
      if (err.message !== "unauthorized") addMsg("error", err.message);
    }
  });

  $("orb").addEventListener("click", () => {
    if (!token) return showSetup();
    if (!started) return start(true);
    if (mode === "speaking" && window.speechSynthesis) speechSynthesis.cancel();
    // Tapping the orb skips the wake word.
    if (mode === "wake" || mode === "speaking") { stopRecognition(); listenForCommand(); }
    else if (mode === "command") listenForWake();
  });

  $("tapToStartBtn").addEventListener("click", () => start(true));
  $("settingsBtn").addEventListener("click", () => showSetup());
  $("resetBtn").addEventListener("click", async () => {
    try { await api("/api/reset", { method: "POST", body: JSON.stringify({ session_id: sessionId }) }); } catch { /* ignore */ }
    sessionId = newSessionId();
    store.set("session", sessionId);
    $("log").textContent = "";
    addMsg("system", "New conversation.");
  });

  $("ask").addEventListener("submit", (e) => {
    e.preventDefault();
    const text = $("askInput").value.trim();
    if (!text || !token) return token ? undefined : showSetup();
    $("askInput").value = "";
    if (!started) start(true);
    handleCommand(text);
  });

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      stopRecognition();
    } else if (started) {
      keepAwake();
      pollReminders();
      if (mode === "wake" || mode === "command") listen();
    }
  });

  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});
  setInterval(pollReminders, REMINDER_POLL_MS);

  if (!token) {
    showSetup();
  } else {
    // Try to start hands-free. If the browser wants a tap first (mic or
    // speech permission), needTap() shows the Activate button instead.
    pollReminders();
    start(false);
  }
})();
