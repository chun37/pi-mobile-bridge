import test from "node:test";
import assert from "node:assert/strict";
import { ExecutionState } from "../extension/execution.ts";

const snapshot = (e, idle = true, seq = 5) => e.snapshot(seq, idle);
for (const idle of [true, false]) test(`initial context idle=${idle}`, () => {
  assert.deepEqual(snapshot(new ExecutionState(), idle), { state: idle ? "idle" : "working", seq: 5 });
});
test("agent_end leaves automatic continuation running", () => {
  const e = new ExecutionState(); e.update("agent_start", {}); e.update("agent_end", {});
  assert.equal(snapshot(e).state, "working");
  e.update("agent_settled", { aborted: false }); assert.equal(snapshot(e).state, "done");
});
for (const phase of ["idle", "working", "done", "aborted", "error"]) test(`dialog restores ${phase}`, () => {
  const e = new ExecutionState();
  if (phase === "working") e.update("agent_start", {});
  if (["done", "aborted", "error"].includes(phase)) {
    e.update("message_end", { message: { role: "assistant", stopReason: phase === "error" ? "error" : "stop" } });
    e.update("agent_settled", { aborted: phase === "aborted" });
  }
  e.update("ui_prompt_start", {}); assert.equal(snapshot(e).state, "blocked");
  e.update("ui_prompt_end", {}); assert.equal(snapshot(e).state, phase);
});
test("nested UI prompts remain blocked until outer closes", () => {
  const e = new ExecutionState(); e.update("agent_start", {});
  e.update("ui_prompt_start", {}); e.update("ui_prompt_start", {});
  e.update("ui_prompt_end", {}); assert.equal(snapshot(e).state, "blocked");
  e.update("ui_prompt_end", {}); assert.equal(snapshot(e).state, "working");
});
test("settlement survives closing a remaining dialog without resurrecting work", () => {
  const e = new ExecutionState(); e.update("agent_start", {}); e.update("ui_prompt_start", {});
  e.update("agent_settled", { aborted: true }); e.update("ui_prompt_end", {});
  assert.equal(snapshot(e).state, "aborted");
});
test("tool error alone is not a failed run", () => {
  const e = new ExecutionState(); e.update("message_end", { message: { role: "toolResult", isError: true } });
  e.update("agent_settled", {}); assert.equal(snapshot(e).state, "done");
});
test("successful retry replaces earlier assistant error", () => {
  const e = new ExecutionState();
  e.update("message_end", { message: { role: "assistant", stopReason: "error" } });
  e.update("message_end", { message: { role: "assistant", stopReason: "stop" } });
  e.update("agent_settled", {}); assert.equal(snapshot(e).state, "done");
});
test("new run resets earlier failure", () => {
  const e = new ExecutionState();
  e.update("message_end", { message: { role: "assistant", stopReason: "error" } });
  e.update("agent_settled", {}); assert.equal(snapshot(e).state, "error");
  e.update("agent_start", {}); e.update("agent_settled", {});
  assert.equal(snapshot(e).state, "done");
});
test("abort takes precedence over error", () => {
  const e = new ExecutionState();
  e.update("message_end", { message: { role: "assistant", stopReason: "error" } });
  e.update("agent_settled", { aborted: true }); assert.equal(snapshot(e).state, "aborted");
});
test("run starting behind a modal remains blocked until the modal closes", () => {
  const e = new ExecutionState(); e.update("ui_prompt_start", {}); e.update("agent_start", {});
  assert.equal(snapshot(e, false).state, "blocked");
  e.update("ui_prompt_end", {}); assert.equal(snapshot(e, false).state, "working");
});
test("settlement behind a modal preserves its outcome without hiding the modal", () => {
  const e = new ExecutionState(); e.update("agent_start", {}); e.update("ui_prompt_start", {});
  e.update("agent_settled", { aborted: true }); assert.equal(snapshot(e).state, "blocked");
  e.update("ui_prompt_end", {}); assert.equal(snapshot(e).state, "aborted");
});
test("unmatched prompt-end cannot underflow or create work", () => {
  const e = new ExecutionState(); e.update("ui_prompt_end", {}); e.update("ui_prompt_start", {});
  assert.equal(snapshot(e).state, "blocked");
  e.update("ui_prompt_end", {}); assert.equal(snapshot(e).state, "idle");
});
test("snapshots are immutable values with caller watermark", () => {
  const e = new ExecutionState(); const old = snapshot(e);
  e.update("agent_start", {}); assert.equal(old.state, "idle");
  assert.deepEqual(snapshot(e, false, 8), { state: "working", seq: 8 });
});
