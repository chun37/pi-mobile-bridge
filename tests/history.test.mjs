import test from "node:test";
import assert from "node:assert/strict";
import { buildHistory, lastResponse } from "../extension/history.ts";

const msg = (role, content, ts = 1) => ({ type: "message", id: `${role}-${ts}`, timestamp: ts, message: { role, content, timestamp: ts } });
const opts = (extra = {}) => ({ seq: 7, info: { marker: true }, origins: new Map(), toolDurations: new Map(), ...extra });

test("lastResponse is the first line of the latest assistant text, capped at 240", () => {
  const branch = [msg("assistant", [{ type: "text", text: "one\ntwo" }]), msg("assistant", [{ type: "text", text: "x".repeat(300) }])];
  assert.equal(lastResponse(branch), "x".repeat(240));
  assert.equal(lastResponse([]), "");
});

test("history after a leaf excludes it and reports the next entries with leaf and seq", () => {
  const branch = [msg("user", "hi", 1), msg("assistant", "hello", 2), msg("user", "again", 3)];
  const h = buildHistory(branch, "user-3", opts({ since: "assistant-2" }));
  assert.deepEqual(h.entries.map((e) => e.entryId), ["user-3"]);
  assert.equal(h.leafId, "user-3");
  assert.equal(h.seq, 7);
  assert.equal(h.reset, false);
  assert.deepEqual(h.info, { marker: true });
});

test("an unknown leaf resets and replays from the start; no since means everything", () => {
  const branch = [msg("user", "a", 1), msg("assistant", "b", 2)];
  const reset = buildHistory(branch, "assistant-2", opts({ since: "missing" }));
  assert.equal(reset.reset, true);
  assert.equal(reset.entries.length, 2);
  assert.equal(buildHistory(branch, "assistant-2", opts()).reset, false);
  assert.equal(buildHistory(branch, "assistant-2", opts()).entries.length, 2);
});

test("system messages are dropped, origins and tool durations are attached, custom and note entries pass through", () => {
  const toolResult = { type: "message", id: "r", timestamp: 5, message: { role: "toolResult", toolCallId: "c1", content: [] } };
  const branch = [
    msg("system", "hidden", 1),
    msg("user", "from phone", 2),
    { type: "custom_message", id: "c", timestamp: 3, content: [{ type: "text", text: "note" }] },
    { type: "custom_message", id: "h", timestamp: 4, display: false, content: [] },
    { type: "compaction", id: "k", timestamp: 6, summary: "sum" },
    { type: "other", id: "o", timestamp: 7 },
    toolResult,
  ];
  const origins = new Map([[2, { origin: "mobile", clientMsgId: "m1" }]]);
  const h = buildHistory(branch, "r", opts({ origins, toolDurations: new Map([["c1", 42]]) }));
  assert.deepEqual(h.entries.map((e) => e.entryId), ["user-2", "c", "k", "r"]);
  assert.deepEqual({ origin: h.entries[0].origin, clientMsgId: h.entries[0].clientMsgId }, { origin: "mobile", clientMsgId: "m1" });
  assert.equal(h.entries[1].message.role, "custom");
  assert.equal(h.entries[1].origin, "pc");
  assert.deepEqual({ note: h.entries[2].note, text: h.entries[2].text }, { note: "compaction", text: "sum" });
  assert.equal(h.entries[3].message.durationMs, 42);
  assert.equal(h.entries[0].message, branch[1].message);
});
