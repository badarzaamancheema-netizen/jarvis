const test = require("node:test");
const assert = require("node:assert");
const { parseWake } = require("../../jarvis/web/wake.js");

test("plain wake phrases wake without a command", () => {
  for (const phrase of ["hi Jarvis", "Hey Jarvis", "Jarvis open", "jarvis", "Hi, Jarvis.", "Jarvis, are you there?", "hello jervis"]) {
    assert.deepStrictEqual(parseWake(phrase), { command: "" }, phrase);
  }
});

test("wake phrase followed by a command returns the command", () => {
  assert.deepStrictEqual(parseWake("hi Jarvis what's the weather in Lahore"), { command: "what's the weather in Lahore" });
  assert.deepStrictEqual(parseWake("Jarvis open YouTube"), { command: "open YouTube" });
  assert.deepStrictEqual(parseWake("hey jarvis, remind me to call mom at 6"), { command: "remind me to call mom at 6" });
});

test("speech without the wake word is ignored", () => {
  for (const phrase of ["", "open youtube", "hi there", "the jarvisville mall", null]) {
    assert.strictEqual(parseWake(phrase), null, String(phrase));
  }
});

test("wake word mid-sentence still counts", () => {
  assert.deepStrictEqual(parseWake("um okay Jarvis set a timer"), { command: "set a timer" });
});
