import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";

import { refreshModels } from "./model-refresh.ts";

export const RELOAD_COMMAND = "pibridge-reload";
export const THINKING_LEVELS = ["off", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
const builtins = [
  { name: "model", description: "モデルを切り替える（続けて候補を選択）" },
  { name: "thinking", description: "思考レベルを変更する" },
  { name: "compact", description: "コンテキストを圧縮する（任意で指示を追記）" },
  { name: "name", description: "セッション名を変更する" },
  { name: "session", description: "モデルとコンテキスト使用量を表示する" },
  { name: "reload", description: "このPiの拡張と設定を再読み込みする" },
];

export function contextUsage(ctx: ExtensionContext) {
  try {
    const usage = ctx.getContextUsage?.();
    if (!usage || !Number.isFinite(usage.contextWindow) || usage.contextWindow <= 0) return null;
    return {
      tokens: typeof usage.tokens === "number" && Number.isFinite(usage.tokens) && usage.tokens >= 0 ? usage.tokens : null,
      contextWindow: usage.contextWindow,
      percent: typeof usage.percent === "number" && Number.isFinite(usage.percent) && usage.percent >= 0 ? usage.percent : null,
    };
  } catch { return null; }
}

export function commandCatalog(pi: ExtensionAPI) {
  const commands = pi.getCommands().filter(c => c.name !== RELOAD_COMMAND && c.name !== "new").map(c => ({
    name: c.name, description: (c.description ?? "").slice(0, 500), source: c.source,
  }));
  const names = new Set(commands.map(c => c.name));
  return [...commands, ...builtins.filter(c => !names.has(c.name)).map(c => ({ ...c, source: "builtin" }))];
}

export async function modelCatalog(ctx: ExtensionContext) {
  const partial = await refreshModels(ctx.modelRegistry);
  return {
    models: ctx.modelRegistry.getAvailable().map(model => ({
      value: `${model.provider}/${model.id}`, name: model.name, contextWindow: model.contextWindow,
    })),
    modelRefreshWarning: partial ? "一部のプロバイダーを更新できませんでした。取得できた候補を表示しています" : "",
  };
}

export async function runSlashCommand(pi: ExtensionAPI, ctx: ExtensionContext, text: string, isCurrent: () => boolean = () => true) {
  if (!ctx.isIdle()) throw new Error("コマンドは実行が完了してから送信してください");
  const match = /^\/([^\s/]+)(?:\s+([\s\S]*))?$/.exec(text);
  if (!match) throw new Error("コマンド名を選択してください");
  const [, name, argument = ""] = match;
  if (name === "new") throw new Error("新しい会話は一覧の＋から別タブで作成してください");
  const command = commandCatalog(pi).find(c => c.name === name);
  if (!command) throw new Error(`未対応のコマンドです: /${name}（通常メッセージとしては送信しません）`);
  if (command.source !== "builtin") {
    // This opts into actual command dispatch, not a slash-looking prompt to the model.
    pi.sendUserMessage(text, { expandPromptTemplates: true });
    return { status: "command", message: `/${name} を受け付けました` };
  }
  const args = argument.trim();
  let message: string;
  switch (name) {
    case "model": {
      const sessionId = ctx.sessionManager.getSessionId();
      await refreshModels(ctx.modelRegistry);
      if (!isCurrent() || ctx.sessionManager.getSessionId() !== sessionId) throw new Error("session_changed");
      if (!ctx.isIdle()) throw new Error("コマンドは実行が完了してから送信してください");
      const matches = ctx.modelRegistry.getAvailable().filter(m => `${m.provider}/${m.id}` === args || m.id === args);
      if (matches.length !== 1) throw new Error("/model の後に候補から provider/model を選択してください");
      if (!await pi.setModel(matches[0])) throw new Error("このモデルの認証が設定されていません");
      message = `モデルを ${matches[0].provider}/${matches[0].id} に変更しました`;
      break;
    }
    case "thinking":
      if (!(THINKING_LEVELS as readonly string[]).includes(args)) throw new Error("/thinking の後にレベルを選択してください");
      pi.setThinkingLevel(args as typeof THINKING_LEVELS[number]);
      message = `思考レベル: ${pi.getThinkingLevel()}`;
      break;
    case "name":
      if (!args || args.length > 200 || /[\r\n\x00-\x1f]/.test(args)) throw new Error("/name の後に200文字以内の名前を指定してください");
      pi.setSessionName(args);
      message = `セッション名を ${args} に変更しました`;
      break;
    case "compact":
      ctx.compact({ customInstructions: args || undefined });
      message = "コンテキストの圧縮を依頼しました";
      break;
    case "session": {
      const usage = contextUsage(ctx);
      message = `${ctx.sessionManager.getSessionName() ?? "Pi"} / ${ctx.model?.id ?? "不明"} / ${usage?.percent == null ? "使用率不明" : `${usage.percent.toFixed(1)}%`}（${usage?.tokens ?? "不明"} / ${usage?.contextWindow ?? "不明"} tokens）`;
      break;
    }
    case "reload":
      if (args) throw new Error("/reload に引数は指定できません");
      // Send the acknowledgement before the reload closes this bridge's socket.
      setImmediate(() => pi.sendUserMessage(`/${RELOAD_COMMAND}`, { expandPromptTemplates: true }));
      message = "このPiの再読み込みを依頼しました";
      break;
    default: throw new Error("未対応のコマンドです");
  }
  return { status: "command", message };
}
