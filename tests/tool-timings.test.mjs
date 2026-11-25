import test from "node:test";
import assert from "node:assert/strict";
import { FORWARDED_EVENTS, ToolTimings } from "../extension/tool-timings.ts";

test("tool durations are rounded from start to end and kept per call", () => {
  const t = new ToolTimings();
  t.start("a", 100);
  t.start("b", 300);
  assert.equal(t.end("a", 160.6), 61);
  assert.equal(t.end("b", 299), 0);
  assert.deepEqual([...t.durations], [["a", 61], ["b", 0]]);
});

test("an end without a start is ignored and a second end does not overwrite", () => {
  const t = new ToolTimings();
  assert.equal(t.end("x", 5), undefined);
  t.start("y", 0);
  assert.equal(t.end("y", 10), 10);
  assert.equal(t.end("y", 99), undefined);
  assert.equal(t.durations.get("y"), 10);
});

test("clear forgets both running starts and recorded durations", () => {
  const t = new ToolTimings();
  t.start("a", 0);
  t.end("a", 1);
  t.clear();
  assert.equal(t.durations.size, 0);
  assert.equal(t.end("a", 5), undefined);
});

test("every forwarded event name is unique and the relay relies on is present", () => {
  assert.equal(new Set(FORWARDED_EVENTS).size, FORWARDED_EVENTS.length);
  for (const name of ["message_end", "tool_execution_start", "tool_execution_end", "agent_settled", "ui_prompt_start", "ui_prompt_end"])
    assert.ok(FORWARDED_EVENTS.includes(name), name);
});
