import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import net from "node:net";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import bridge, { parseLines, MAX_FRAME } from "../extension/index.ts";

const originalHome = process.env.HOME;
const testHome = fs.mkdtempSync(path.join(os.tmpdir(), "pi-bridge-test-"));
process.env.HOME = testHome;
const handlers = new Map();
const entries = [];
let idle = true;
let aborts = 0;
let sent = [];
const dir = path.join(os.homedir(), ".pi/agent/bridge");
const socket = path.join(dir, `${process.pid}.sock`);
const pi = {
  on: (n, f) => {
    if (!handlers.has(n)) handlers.set(n, []);
    handlers.get(n).push(f);
  },
  appendEntry: (t, d) =>
    entries.push({
      type: "custom",
      customType: t,
      data: d,
      id: "tag" + entries.length,
    }),
  sendUserMessage: (content, opts) => {
    sent.push({ content, opts });
  },
};
const ctx = {
  mode: "tui",
  cwd: "/test",
  model: { id: "test-model" },
  isIdle: () => idle,
  abort: () => {
    aborts++;
  },
  sessionManager: {
    getSessionId: () => "test-session",
    getSessionFile: () => "/test/session.jsonl",
    getSessionName: () => "Test",
    getLeafId: () => entries.at(-1)?.id,
    getBranch: () => entries,
  },
};
async function emit(n, e = {}) {
  for (const fn of handlers.get(n) ?? []) await fn(e, ctx);
}
let connection;
let id = 0;
const pending = new Map();
const events = [];
async function req(method, params = {}) {
  const key = String(++id);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("timeout")), 2000);
    pending.set(key, (v) => {
      clearTimeout(timer);
      resolve(v);
    });
    connection.write(JSON.stringify({ id: key, method, params }) + "\n");
  });
}
before(async () => {
  bridge(pi);
  await emit("session_start", { reason: "startup" });
  connection = net.createConnection(socket);
  await new Promise((r) => connection.once("connect", r));
  connection.on(
    "data",
    parseLines(
      (p) => {
        if (p.id) pending.get(p.id)?.(p);
        else events.push(p);
      },
      (e) => {
        throw e;
      },
    ),
  );
});
after(async () => {
  connection?.destroy();
  await emit("session_shutdown");
  process.env.HOME = originalHome;
  fs.rmSync(testHome, { recursive: true, force: true });
});

test("secure socket and metadata", async () => {
  assert.equal(fs.statSync(dir).mode & 511, 448);
  assert.equal(fs.statSync(socket).mode & 511, 384);
  const p = await req("info");
  assert.equal(p.result.sessionId, "test-session");
  assert.equal(p.result.model, "test-model");
});
test("history excludes system and handles unknown cursor", async () => {
  entries.push(
    {
      type: "message",
      id: "s",
      message: { role: "system", content: "private" },
    },
    {
      type: "message",
      id: "u",
      timestamp: "now",
      message: { role: "user", content: "hello", timestamp: 1 },
    },
  );
  let p = await req("history");
  assert.equal(p.result.entries.length, 1);
  p = await req("history", { since: "missing" });
  assert.equal(p.result.reset, true);
  p = await req("history", { since: "u" });
  assert.equal(p.result.entries.length, 0);
});
test("idle injection + dedup + collision rejection", async () => {
  let p = await req("send", { text: "mobile", clientMsgId: "one" });
  assert.equal(p.result.status, "started");
  assert.equal(sent.length, 1);
  p = await req("send", { text: "mobile", clientMsgId: "one" });
  assert.equal(sent.length, 1);
  p = await req("send", { text: "other", clientMsgId: "one" });
  assert.match(p.error.message, /conflict/);
});
test("busy steering and follow-up are explicit", async () => {
  idle = false;
  await req("send", { text: "steer", clientMsgId: "two", deliverAs: "steer" });
  await req("send", { text: "queue", clientMsgId: "three" });
  assert.equal(sent[1].opts.deliverAs, "steer");
  assert.equal(sent[2].opts.deliverAs, "followUp");
  idle = true;
});
test("reject invalid requests and images", async () => {
  for (const params of [
    { text: "", clientMsgId: "empty" },
    { text: "x", clientMsgId: "bad", deliverAs: "wrong" },
    {
      text: "x",
      clientMsgId: "img",
      images: [{ mimeType: "text/html", data: "abc" }],
    },
  ])
    assert.ok((await req("send", params)).error);
  assert.ok((await req("server.stop")).error);
});
test("abort uses context abort", async () => {
  assert.equal((await req("abort")).result.aborted, true);
  assert.equal(aborts, 1);
});
test("subscribed mobile and pc messages have monotonic sequences and origin", async () => {
  await req("subscribe");
  await emit("input", { text: "mobile", source: "extension" });
  await emit("message_start", {
    message: { role: "user", content: "mobile", timestamp: 2 },
  });
  await emit("input", { text: "PC text", source: "interactive" });
  await emit("message_start", {
    message: { role: "user", content: "PC text", timestamp: 3 },
  });
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(events.at(-2).payload.origin, "mobile");
  assert.equal(events.at(-2).payload.clientMsgId, "one");
  assert.equal(events.at(-1).payload.origin, "pc");
  assert.ok(events.at(-1).seq > events.at(-2).seq);
});
test("tool duration is measured and retained in normalized history", async () => {
  await emit("tool_execution_start", {
    toolCallId: "duration-tool",
    toolName: "bash",
    args: { command: "true" },
  });
  await emit("tool_execution_end", {
    toolCallId: "duration-tool",
    toolName: "bash",
    result: { content: [{ type: "text", text: "ok" }] },
    isError: false,
  });
  entries.push({
    type: "message",
    id: "tool-result",
    timestamp: "now",
    message: {
      role: "toolResult",
      toolCallId: "duration-tool",
      timestamp: 8,
      content: [{ type: "text", text: "ok" }],
    },
  });
  const h = await req("history");
  const m = h.result.entries.find((e) => e.entryId === "tool-result").message;
  assert.equal(typeof m.durationMs, "number");
  assert.ok(m.durationMs >= 0);
  assert.ok(entries.some((e) => e.customType === "pi-bridge-tool"));
});

test("LF parser keeps unicode separators and fragmented UTF8", () => {
  const got = [];
  let errors = 0;
  const parser = parseLines(
    (x) => got.push(x),
    () => errors++,
  );
  const b = Buffer.from(
    JSON.stringify({ text: "日本語\u2028next\u2029end" }) + "\n",
  );
  for (const byte of b) parser(Buffer.from([byte]));
  assert.deepEqual(got, [{ text: "日本語\u2028next\u2029end" }]);
  parser(Buffer.from("{bad}\n"));
  assert.equal(errors, 1);
});
test("shutdown and reload are idempotent; non-TUI has no socket", async () => {
  await emit("session_shutdown");
  await emit("session_shutdown");
  assert.equal(fs.existsSync(socket), false);
  ctx.mode = "rpc";
  await emit("session_start");
  assert.equal(fs.existsSync(socket), false);
  ctx.mode = "tui";
  await emit("session_start", { reason: "reload" });
  assert.equal(fs.existsSync(socket), true);
});
