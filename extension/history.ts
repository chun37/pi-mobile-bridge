export type WireEntry = {
  entryId: string;
  timestamp: string;
  message?: unknown;
  origin?: string;
  clientMsgId?: string;
  note?: string;
  text?: string;
};

export type OriginTag = { origin: string; clientMsgId?: string };

export type HistorySnapshot = {
  entries: WireEntry[];
  leafId: string | null;
  reset: boolean;
  seq: number;
  info: unknown;
};

/** First line of the last assistant reply, capped for the session list. */
export function lastResponse(branch: any[]): string {
  const assistant = [...branch]
    .reverse()
    .find((e: any) => e.type === "message" && e.message.role === "assistant") as any;
  return (
    assistant?.message.content
      ?.filter((p: any) => p.type === "text")
      .map((p: any) => p.text)
      .join("\n")
      .split("\n")[0]
      ?.slice(0, 240) ?? ""
  );
}

/** Entries after [since] on the current branch. [reset] marks a leaf the bridge no longer has. */
export function buildHistory(
  branch: any[],
  leafId: string | null,
  opts: {
    since?: string;
    seq: number;
    info: unknown;
    origins: Map<number, OriginTag>;
    toolDurations: Map<string, number>;
  },
): HistorySnapshot {
  const index = opts.since ? branch.findIndex((e) => e.id === opts.since) : -1;
  const reset = !!opts.since && index < 0;
  const entries = branch
    .slice(index < 0 ? 0 : index + 1)
    .flatMap<WireEntry>((e: any) => {
      if (e.type === "message" && e.message.role !== "system") {
        const origin = opts.origins.get(e.message.timestamp) ?? { origin: "pc" };
        return [
          {
            entryId: e.id,
            timestamp: e.timestamp,
            message:
              e.message.role === "toolResult" && opts.toolDurations.has(e.message.toolCallId)
                ? {
                    ...e.message,
                    durationMs: opts.toolDurations.get(e.message.toolCallId),
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
  return { entries, leafId, reset, seq: opts.seq, info: opts.info };
}
