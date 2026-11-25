import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { StringDecoder } from "node:string_decoder";
import { randomUUID } from "node:crypto";
import type {
  ExtensionAPI,
  ExtensionContext,
} from "@earendil-works/pi-coding-agent";

import { commandCatalog, modelCatalog, RELOAD_COMMAND, runSlashCommand, THINKING_LEVELS } from "./commands.ts";

import { ExecutionState } from "./execution.ts";
import { cancelModelRefresh } from "./model-refresh.ts";
import { buildHistory, type OriginTag } from "./history.ts";
import { buildInfo } from "./session-info.ts";
import { prepareBridgeDir, removeStaleSocket } from "./socket-path.ts";
import { validateSend } from "./send-validation.ts";
import { FORWARDED_EVENTS, ToolTimings } from "./tool-timings.ts";

import { VERSION } from "./version.generated.ts";
import { captureBuildInfo } from "./build-info.ts";
export { VERSION };
const BUILD_INFO = Object.freeze(captureBuildInfo(import.meta.url));
export const MAX_FRAME = 24 * 1024 * 1024;

// LF only: U+2028/U+2029 are valid content, not wire separators.
export function parseLines(
  onLine: (value: any) => void,
  onError: (e: Error) => void,
) {
  const decoder = new StringDecoder("utf8");
  let buffer = "";
  return (chunk: Buffer) => {
    buffer += decoder.write(chunk);
    if (Buffer.byteLength(buffer) > MAX_FRAME) {
      onError(new Error("frame_too_large"));
      buffer = "";
      return;
    }
    let at: number;
    while ((at = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, at);
      buffer = buffer.slice(at + 1);
      if (!line.trim()) continue;
      try {
        onLine(JSON.parse(line));
      } catch (e) {
        onError(e as Error);
      }
    }
  };
}

export default function bridge(pi: ExtensionAPI) {
  pi.registerCommand(RELOAD_COMMAND, {
    description: "Reload this Pi runtime for the mobile bridge",
    handler: async (_args, commandContext) => { await commandContext.reload(); },
  });
  let ctx: ExtensionContext | undefined;
  let server: net.Server | undefined;
  let socketPath: string | undefined;
  let seq = 0;
  let execution = new ExecutionState();
  let enabled = false;
  let startReason = "startup";
  let instanceId = randomUUID();
  const clients = new Map<net.Socket, boolean>();
  const pending: { text: string; clientMsgId: string; timestamp: number }[] =
    [];
  const accepted = new Map<string, { fingerprint: string; result: any }>();
  const origins = new Map<number, OriginTag>();
  const inputOrigins: {
    text: string;
    hasImages: boolean;
    origin: string;
    clientMsgId?: string;
  }[] = [];
  let lastSyncedId: string | undefined;
  const tools = new ToolTimings();

  function write(s: net.Socket, v: any) {
    if (s.destroyed) return;
    if (s.writableLength > MAX_FRAME) {
      s.destroy();
      return;
    }
    s.write(JSON.stringify(v) + "\n");
  }
  function emit(event: string, payload: any) {
    if (!enabled) return;
    const packet = { event, seq: ++seq, sessionId: ctx!.sessionManager.getSessionId(), instanceId,
      payload, execution: execution.snapshot(seq, ctx!.isIdle()) };
    for (const [s, subscribed] of clients) if (subscribed) write(s, packet);
  }
  function info() {
    return buildInfo(pi, ctx!, { seq, execution, startReason, instanceId, bridgeVersion: VERSION, build: BUILD_INFO });
  }
  function history(since?: string) {
    const sm = ctx!.sessionManager;
    return buildHistory(sm.getBranch(), sm.getLeafId(), { since, seq, info: info(), origins, toolDurations: tools.durations });
  }
  async function request(s: net.Socket, req: any) {
    const id = typeof req?.id === "string" ? req.id : "";
    try {
      if (!ctx || !enabled) throw new Error("unavailable");
      const p = req.params ?? {};
      let result: any;
      switch (req.method) {
        case "info":
          result = info();
          break;
        case "commands": {
          const context = ctx;
          const sessionId = context.sessionManager.getSessionId();
          const generation = instanceId;
          if ((p.expectedSessionId != null && p.expectedSessionId !== sessionId) ||
              (p.expectedInstanceId != null && p.expectedInstanceId !== generation)) throw new Error("session_changed");
          const catalog = await modelCatalog(context);
          if (!enabled || ctx?.sessionManager.getSessionId() !== sessionId || instanceId !== generation)
            throw new Error("session_changed");
          result = { commands: commandCatalog(pi), ...catalog, thinkingLevels: THINKING_LEVELS };
          break;
        }
        case "history":
          result = history(p.since);
          break;
        case "subscribe":
          clients.set(s, true);
          result = { seq };
          break;
        case "abort":
          if (p.expectedSessionId != null && p.expectedSessionId !== ctx.sessionManager.getSessionId())
            throw new Error("session_changed");
          ctx.abort();
          result = { aborted: true };
          break;
        case "send": {
          if (p.expectedSessionId != null && p.expectedSessionId !== ctx.sessionManager.getSessionId())
            throw new Error("session_changed");
          const send = validateSend(p);
          const previous = accepted.get(send.clientMsgId);
          if (previous) {
            if (previous.fingerprint !== send.fingerprint)
              throw new Error("clientMsgId_conflict");
            result = previous.result;
            break;
          }
          const idle = ctx.isIdle();
          if (send.text.startsWith("/")) {
            if (send.images.length) throw new Error("コマンドに画像は添付できません");
            if (!idle) throw new Error("コマンドは実行が完了してから送信してください");
            // Reserve before any asynchronous model change or command dispatch.
            accepted.set(send.clientMsgId, { fingerprint: send.fingerprint, result: {
              status: "command", clientMsgId: send.clientMsgId,
              message: "前回のコマンド処理結果は未確認です。自動では再実行しません",
            } });
            if (accepted.size > 2048) accepted.delete(accepted.keys().next().value!);
            const sessionId = ctx.sessionManager.getSessionId();
            const generation = instanceId;
            result = { accepted: true, ...await runSlashCommand(pi, ctx, send.text,
              () => enabled && ctx?.sessionManager.getSessionId() === sessionId && instanceId === generation), clientMsgId: send.clientMsgId };
            accepted.set(send.clientMsgId, { fingerprint: send.fingerprint, result });
            break;
          }
          if (pending.length >= 2048) throw new Error("input_queue_full");
          const marker = {
            text: send.text,
            clientMsgId: send.clientMsgId,
            timestamp: Date.now(),
          };
          pending.push(marker);
          try {
            const content = [
              { type: "text" as const, text: send.text },
              ...send.images.map((im: any) => ({
                type: "image" as const,
                mimeType: im.mimeType,
                data: im.data,
              })),
            ];
            await pi.sendUserMessage(
              content,
              idle ? {} : { deliverAs: send.deliverAs ?? "followUp" },
            );
          } catch (e) {
            const at = pending.indexOf(marker);
            if (at >= 0) pending.splice(at, 1);
            throw e;
          }
          result = {
            status: idle ? "started" : "queued",
            clientMsgId: send.clientMsgId,
          };
          accepted.set(send.clientMsgId, { fingerprint: send.fingerprint, result });
          if (accepted.size > 2048)
            accepted.delete(accepted.keys().next().value!);
          break;
        }
        default:
          throw new Error("method_not_allowed");
      }
      write(s, { id, result });
    } catch (e) {
      write(s, {
        id,
        error: { code: "bridge_error", message: (e as Error).message },
      });
    }
  }
  async function cleanup() {
    if (ctx) cancelModelRefresh(ctx.modelRegistry);
    enabled = false;
    for (const s of clients.keys()) s.destroy();
    clients.clear();
    pending.length = 0;
    accepted.clear();
    origins.clear();
    inputOrigins.length = 0;
    if (server) {
      const old = server;
      server = undefined;
      await new Promise<void>((r) => old.close(() => r()));
    }
    if (socketPath) {
      try {
        fs.unlinkSync(socketPath);
      } catch {}
      socketPath = undefined;
    }
  }
  pi.on("session_start", async (event, context) => {
    await cleanup();
    ctx = context;
    if (ctx.mode !== "tui") return;
    startReason = event.reason;
    execution = new ExecutionState();
    instanceId = randomUUID();
    const dir = prepareBridgeDir();
    socketPath = path.join(dir, `${process.pid}.sock`);
    // A stale file is removed only at our own PID path, never another bridge's path.
    removeStaleSocket(socketPath);
    tools.clear();
    for (const e of ctx.sessionManager.getBranch() as any[]) {
      if (e.type === "custom" && e.customType === "pi-bridge-tool")
        tools.durations.set(e.data.toolCallId, e.data.durationMs);
      if (e.type === "custom" && e.customType === "pi-bridge-origin")
        origins.set(e.data.timestamp, e.data);
    }
    server = net.createServer((s) => {
      clients.set(s, false);
      s.on("error", () => s.destroy());
      s.on("close", () => clients.delete(s));
      s.on(
        "data",
        parseLines(
          (req) => {
            void request(s, req);
          },
          () => s.destroy(),
        ),
      );
    });
    await new Promise<void>((resolve, reject) => {
      server!.once("error", reject);
      server!.listen(socketPath!, () => resolve());
    });
    fs.chmodSync(socketPath, 0o600);
    enabled = true;
    lastSyncedId = ctx.sessionManager.getLeafId() ?? undefined;
    emit("session_start", { ...event, info: info() });
  });
  pi.on("session_shutdown", cleanup);
  pi.on("input", (event) => {
    if (!enabled) return;
    let marker: (typeof pending)[number] | undefined;
    if (event.source === "extension") {
      const at = pending.findIndex((p) => p.text === event.text);
      if (at >= 0) marker = pending.splice(at, 1)[0];
    }
    inputOrigins.push(
      marker
        ? {
            text: event.text,
            hasImages: !!event.images?.length,
            origin: "mobile",
            clientMsgId: marker.clientMsgId,
          }
        : { text: event.text, hasImages: !!event.images?.length, origin: "pc" },
    );
    if (inputOrigins.length > 4096) inputOrigins.shift();
  });
  pi.on("message_start", (event, context) => {
    if (!enabled) return;
    ctx = context;
    if (event.message.role === "user") {
      const content = event.message.content;
      const text =
        typeof content === "string"
          ? content
          : content
              .filter((p: any) => p.type === "text")
              .map((p: any) => p.text)
              .join("\n");
      let at = inputOrigins.findIndex((p) => p.text === text);
      if (at < 0)
        at = inputOrigins.findIndex(
          (p) => p.hasImages && text.startsWith(p.text),
        );
      const input = at >= 0 ? inputOrigins.splice(at, 1)[0] : undefined;
      const tag = input
        ? {
            origin: input.origin,
            ...(input.clientMsgId ? { clientMsgId: input.clientMsgId } : {}),
          }
        : { origin: "pc" };
      origins.set(event.message.timestamp, tag);
      pi.appendEntry("pi-bridge-origin", {
        timestamp: event.message.timestamp,
        ...tag,
      });
      emit("message_start", { ...event, ...tag });
    } else emit("message_start", event);
  });
  for (const name of FORWARDED_EVENTS)
    pi.on(name as any, (event: any, context: ExtensionContext) => {
      if (!enabled) return;
      ctx = context;
      execution.update(name, event);
      const tag =
        event.message?.role === "user"
          ? origins.get(event.message.timestamp)
          : undefined;
      if (name === "ui_prompt_start" || name === "ui_prompt_end") {
        pi.events?.emit("herdr:blocked", {
          active: name === "ui_prompt_start",
          label: event.title ?? "Mobile terminal response required",
        });
      }
      let timing: { durationMs?: number } = {};
      if (name === "tool_execution_start") tools.start(event.toolCallId);
      if (name === "tool_execution_end") {
        const durationMs = tools.end(event.toolCallId);
        if (durationMs !== undefined) {
          pi.appendEntry("pi-bridge-tool", {
            toolCallId: event.toolCallId,
            durationMs,
          });
          timing = { durationMs };
        }
      }
      emit(name, { ...event, ...tag, ...timing });
      if (name === "message_end") {
        const activeSession = context.sessionManager.getSessionId();
        // Pi persists message entries AFTER extension message_end handlers return.
        setImmediate(() => {
          if (!enabled || ctx?.sessionManager.getSessionId() !== activeSession)
            return;
          const snapshot = history(lastSyncedId);
          lastSyncedId = snapshot.leafId ?? undefined;
          emit("history_sync", snapshot);
        });
      }
    });
}
