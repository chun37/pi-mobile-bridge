import net from "node:net";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { StringDecoder } from "node:string_decoder";
import { randomUUID } from "node:crypto";
import { VERSION as PI_VERSION } from "@earendil-works/pi-coding-agent";
import type {
  ExtensionAPI,
  ExtensionContext,
} from "@earendil-works/pi-coding-agent";

import { commandCatalog, contextUsage, modelCatalog, RELOAD_COMMAND, runSlashCommand, THINKING_LEVELS } from "./commands.ts";

import { ExecutionState } from "./execution.ts";
import { cancelModelRefresh } from "./model-refresh.ts";

import { VERSION } from "./version.generated.ts";
import { captureBuildInfo } from "./build-info.ts";
export { VERSION };
const BUILD_INFO = Object.freeze(captureBuildInfo(import.meta.url));
export const MAX_FRAME = 24 * 1024 * 1024;
type WireEntry = {
  entryId: string;
  timestamp: string;
  message?: unknown;
  origin?: string;
  clientMsgId?: string;
  note?: string;
  text?: string;
};

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
  const origins = new Map<number, { origin: string; clientMsgId?: string }>();
  const inputOrigins: {
    text: string;
    hasImages: boolean;
    origin: string;
    clientMsgId?: string;
  }[] = [];
  let lastSyncedId: string | undefined;
  const toolStarts = new Map<string, number>();
  const toolDurations = new Map<string, number>();

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
    const sm = ctx!.sessionManager;
    const assistant = [...sm.getBranch()]
      .reverse()
      .find(
        (e: any) => e.type === "message" && e.message.role === "assistant",
      ) as any;
    const lastResponse =
      assistant?.message.content
        ?.filter((p: any) => p.type === "text")
        .map((p: any) => p.text)
        .join("\n")
        .split("\n")[0]
        ?.slice(0, 240) ?? "";
    return {
      paneId: process.env.HERDR_PANE_ID,
      herdrSocket: process.env.HERDR_SOCKET_PATH,
      pid: process.pid,
      sessionId: sm.getSessionId(),
      sessionFile: sm.getSessionFile(),
      sessionName: sm.getSessionName(),
      cwd: ctx!.cwd,
      model: ctx!.model?.id,
      thinkingLevel: pi.getThinkingLevel(),
      contextUsage: contextUsage(ctx!),
      capabilities: ["slashCommands", "contextUsage", "executionState"],
      idle: ctx!.isIdle(),
      execution: execution.snapshot(seq, ctx!.isIdle()),
      lastEntryId: sm.getLeafId(),
      lastResponse,
      startReason,
      instanceId,
      bridgeVersion: VERSION,
      build: BUILD_INFO,
      runtime: { piVersion: PI_VERSION, nodeVersion: process.version, platform: process.platform, arch: process.arch },
    };
  }
  function history(since?: string) {
    const branch = ctx!.sessionManager.getBranch();
    const index = since ? branch.findIndex((e) => e.id === since) : -1;
    const reset = !!since && index < 0;
    const entries = branch
      .slice(index < 0 ? 0 : index + 1)
      .flatMap<WireEntry>((e: any) => {
        if (e.type === "message" && e.message.role !== "system") {
          const origin = origins.get(e.message.timestamp) ?? { origin: "pc" };
          return [
            {
              entryId: e.id,
              timestamp: e.timestamp,
              message:
                e.message.role === "toolResult" &&
                toolDurations.has(e.message.toolCallId)
                  ? {
                      ...e.message,
                      durationMs: toolDurations.get(e.message.toolCallId),
                    }
                  : e.message,
              ...origin,
            },
          ];
        }
        if (e.type === "custom_message" && e.display !== false)
          return [
            {
              entryId: e.id,
              timestamp: e.timestamp,
              message: {
                role: "custom",
                content: e.content,
                timestamp: Date.parse(e.timestamp),
              },
              origin: "pc",
            },
          ];
        if (["compaction", "branch_summary", "model_change"].includes(e.type))
          return [
            {
              entryId: e.id,
              timestamp: e.timestamp,
              note: e.type,
              text: e.summary ?? e.modelId,
            },
          ];
        return [];
      });
    return { entries, leafId: ctx!.sessionManager.getLeafId(), reset, seq, info: info() };
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
          if (typeof p.text !== "string" || p.text.length > 1000000)
            throw new Error("invalid_text");
          if (
            typeof p.clientMsgId !== "string" ||
            !p.clientMsgId ||
            p.clientMsgId.length > 128
          )
            throw new Error("invalid_clientMsgId");
          if (p.deliverAs && !["steer", "followUp"].includes(p.deliverAs))
            throw new Error("invalid_delivery");
          const images = p.images ?? [];
          if (!Array.isArray(images) || images.length > 4)
            throw new Error("invalid_images");
          for (const im of images) {
            if (
              !["image/png", "image/jpeg", "image/webp", "image/gif"].includes(
                im.mimeType,
              ) ||
              typeof im.data !== "string" ||
              im.data.length > 6 * 1024 * 1024 ||
              !/^[A-Za-z0-9+/]*={0,2}$/.test(im.data)
            )
              throw new Error("invalid_image");
          }
          if (!p.text.trim() && !images.length)
            throw new Error("empty_message");
          const fingerprint = JSON.stringify([p.text, images, p.deliverAs]);
          const previous = accepted.get(p.clientMsgId);
          if (previous) {
            if (previous.fingerprint !== fingerprint)
              throw new Error("clientMsgId_conflict");
            result = previous.result;
            break;
          }
          const idle = ctx.isIdle();
          if (p.text.startsWith("/")) {
            if (images.length) throw new Error("コマンドに画像は添付できません");
            if (!idle) throw new Error("コマンドは実行が完了してから送信してください");
            // Reserve before any asynchronous model change or command dispatch.
            accepted.set(p.clientMsgId, { fingerprint, result: {
              status: "command", clientMsgId: p.clientMsgId,
              message: "前回のコマンド処理結果は未確認です。自動では再実行しません",
            } });
            if (accepted.size > 2048) accepted.delete(accepted.keys().next().value!);
            const sessionId = ctx.sessionManager.getSessionId();
            const generation = instanceId;
            result = { accepted: true, ...await runSlashCommand(pi, ctx, p.text,
              () => enabled && ctx?.sessionManager.getSessionId() === sessionId && instanceId === generation), clientMsgId: p.clientMsgId };
            accepted.set(p.clientMsgId, { fingerprint, result });
            break;
          }
          if (pending.length >= 2048) throw new Error("input_queue_full");
          const marker = {
            text: p.text,
            clientMsgId: p.clientMsgId,
            timestamp: Date.now(),
          };
          pending.push(marker);
          try {
            const content = [
              { type: "text" as const, text: p.text },
              ...images.map((im: any) => ({
                type: "image" as const,
                mimeType: im.mimeType,
                data: im.data,
              })),
            ];
            await pi.sendUserMessage(
              content,
              idle ? {} : { deliverAs: p.deliverAs ?? "followUp" },
            );
          } catch (e) {
            const at = pending.indexOf(marker);
            if (at >= 0) pending.splice(at, 1);
            throw e;
          }
          result = {
            status: idle ? "started" : "queued",
            clientMsgId: p.clientMsgId,
          };
          accepted.set(p.clientMsgId, { fingerprint, result });
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
    const dir = path.join(os.homedir(), ".pi", "agent", "bridge");
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    const st = fs.lstatSync(dir);
    if (
      !st.isDirectory() ||
      st.isSymbolicLink() ||
      st.uid !== process.getuid?.()
    )
      throw new Error("unsafe_bridge_directory");
    fs.chmodSync(dir, 0o700);
    socketPath = path.join(dir, `${process.pid}.sock`);
    // A stale file is removed only at our own PID path, never another bridge's path.
    try {
      const st = fs.lstatSync(socketPath);
      if (!st.isSocket() || st.uid !== process.getuid?.())
        throw new Error("unsafe_socket");
      fs.unlinkSync(socketPath);
    } catch (e: any) {
      if (e.code !== "ENOENT") throw e;
    }
    toolStarts.clear();
    toolDurations.clear();
    for (const e of ctx.sessionManager.getBranch() as any[]) {
      if (e.type === "custom" && e.customType === "pi-bridge-tool")
        toolDurations.set(e.data.toolCallId, e.data.durationMs);
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
  const events = [
    "message_update",
    "message_end",
    "tool_execution_start",
    "tool_execution_update",
    "tool_execution_end",
    "agent_start",
    "agent_end",
    "agent_settled",
    "session_info_changed",
    "model_select",
    "thinking_level_select",
    "session_compact",
    "session_tree",
    "ui_prompt_start",
    "ui_prompt_end",
  ] as const;
  for (const name of events)
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
      if (name === "tool_execution_start")
        toolStarts.set(event.toolCallId, performance.now());
      if (name === "tool_execution_end") {
        const start = toolStarts.get(event.toolCallId);
        if (start !== undefined) {
          const durationMs = Math.max(0, Math.round(performance.now() - start));
          toolStarts.delete(event.toolCallId);
          toolDurations.set(event.toolCallId, durationMs);
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
