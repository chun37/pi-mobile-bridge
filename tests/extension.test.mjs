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
let thinkingLevel = "high";
let aborts = 0;
let sent = [];
const dir = path.join(os.homedir(), ".pi/agent/bridge");
const socket = path.join(dir, `${process.pid}.sock`);
const pi = {
  registerCommand: () => {},
  getThinkingLevel: () => thinkingLevel,
  setThinkingLevel: level => { thinkingLevel = level; },
  getCommands: () => [{ name: "fixture", description: "Local extension", source: "extension" }],
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
  getContextUsage: () => ({ tokens: 12345, contextWindow: 100000, percent: 12.345 }),
  modelRegistry: { getAvailable: () => [{ id: "test-model", provider: "fixture", name: "Test", contextWindow: 100000 }] },
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
  assert.match(p.result.runtime.piVersion, /^\d+\.\d+\.\d+/);
  assert.equal(p.result.runtime.nodeVersion, process.version);
  assert.equal(p.result.runtime.platform, process.platform);
  assert.equal(p.result.runtime.arch, process.arch);
  assert.equal(p.result.bridgeVersion, "0.3.2");
  assert.equal(p.result.contextUsage.percent, 12.345);
  assert.ok(p.result.capabilities.includes("slashCommands"));
});
test("info and history expose the current thinking level, including off", async () => {
  try {
    for (const level of ["off", "minimal", "low", "medium", "high", "xhigh", "max"]) {
      thinkingLevel = level;
      assert.equal((await req("info")).result.thinkingLevel, level);
      assert.equal((await req("history")).result.info.thinkingLevel, level);
    }
    const response = await req("send", { text: "/thinking low", clientMsgId: "thinking-label" });
    assert.equal(response.result.status, "command");
    assert.equal((await req("info")).result.thinkingLevel, "low");
    await req("subscribe");
    await emit("thinking_level_select", { level: "low", previousLevel: "high" });
    await req("info");
    assert.equal(events.at(-1).event, "thinking_level_select");
  } finally { thinkingLevel = "high"; }
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
test("command catalog and slash dispatch never turn unknown commands into prompts", async () => {
  const catalog = (await req("commands")).result;
  assert.ok(catalog.commands.some(c => c.name === "fixture"));
  assert.ok(catalog.commands.some(c => c.name === "compact"));
  assert.ok(!catalog.commands.some(c => c.name === "new"));
  assert.equal(catalog.models[0].value, "fixture/test-model");
  const before = sent.length;
  let p = await req("send", { text: "/fixture 日本語", clientMsgId: "command-one" });
  assert.equal(p.result.status, "command");
  assert.equal(sent.length, before + 1);
  assert.deepEqual(sent.at(-1), { content: "/fixture 日本語", opts: { expandPromptTemplates: true } });
  p = await req("send", { text: "/fixture 日本語", clientMsgId: "command-one" });
  assert.equal(p.result.status, "command");
  assert.equal(sent.length, before + 1);
  p = await req("send", { text: "/does-not-exist", clientMsgId: "command-unknown" });
  assert.ok(p.error);
  p = await req("send", { text: "/new", clientMsgId: "command-new" });
  assert.match(p.error.message, /別タブ/);
  p = await req("send", { text: "/fixture", images: [{ mimeType: "image/png", data: "AA==" }], clientMsgId: "command-image" });
  assert.ok(p.error);
  idle = false;
  p = await req("send", { text: "/fixture", clientMsgId: "command-busy" });
  assert.ok(p.error);
  idle = true;
  p = await req("send", { text: "/fixture", expectedSessionId: "other-session", clientMsgId: "command-other" });
  assert.match(p.error.message, /session_changed/);
  assert.equal(sent.length, before + 1);
});

test("atomic snapshots and streamed packets share identity, execution and watermark", async () => {
  await req("subscribe");
  const before = events.length;
  idle = false;
  await emit("agent_start");
  const running = (await req("history")).result;
  assert.equal(running.info.execution.state, "working");
  assert.equal(running.info.execution.seq, running.seq);
  assert.equal(running.info.sessionId, "test-session");
  assert.ok(running.info.capabilities.includes("executionState"));
  await emit("agent_end");
  assert.equal((await req("info")).result.execution.state, "working");
  idle = true;
  await emit("agent_settled", { aborted: false });
  const done = (await req("history")).result;
  assert.equal(done.info.execution.state, "done");
  assert.equal(done.info.execution.seq, done.seq);
  assert.equal(done.info.instanceId, running.info.instanceId);
  const live = events.slice(before).filter(e => e.event === "agent_settled").at(-1);
  assert.equal(live.execution.state, "done");
  assert.equal(live.execution.seq, live.seq);
  assert.equal(live.sessionId, done.info.sessionId);
  assert.equal(live.instanceId, done.info.instanceId);
});

test("abort rejects changed conversations before any side effect", async () => {
  const before = aborts;
  const rejected = await req("abort", { expectedSessionId: "old-conversation" });
  assert.match(rejected.error.message, /session_changed/);
  assert.equal(aborts, before);
  const accepted = await req("abort", { expectedSessionId: "test-session" });
  assert.equal(accepted.result.aborted, true);
  assert.equal(aborts, before + 1);
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
