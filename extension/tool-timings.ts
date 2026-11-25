/** Start times of running tool calls and the durations persisted as custom entries. */
export class ToolTimings {
  readonly durations = new Map<string, number>();
  private readonly starts = new Map<string, number>();

  start(toolCallId: string, now: number = performance.now()): void {
    this.starts.set(toolCallId, now);
  }

  /** Returns the duration to attach to the end event, or undefined when the start was missed. */
  end(toolCallId: string, now: number = performance.now()): number | undefined {
    const start = this.starts.get(toolCallId);
    if (start === undefined) return undefined;
    const durationMs = Math.max(0, Math.round(now - start));
    this.starts.delete(toolCallId);
    this.durations.set(toolCallId, durationMs);
    return durationMs;
  }

  clear(): void {
    this.starts.clear();
    this.durations.clear();
  }
}

/** Pi events forwarded to subscribers after execution state and tool timing updates. */
export const FORWARDED_EVENTS = [
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
