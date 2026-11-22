// Execution state is independent of TUI focus/seen status and conversation contents.
export class ExecutionState {
  private state = "idle";
  private dialogs = 0;
  private failed = false;

  update(kind: string, payload: any) {
    switch (kind) {
      case "agent_start": this.state = "working"; this.failed = false; break;
      case "message_end":
        if (payload.message?.role === "assistant") this.failed = payload.message.stopReason === "error";
        break;
      case "agent_settled":
        this.state = payload.aborted ? "aborted" : this.failed ? "error" : "done";
        break;
      case "ui_prompt_start": this.dialogs++; break;
      case "ui_prompt_end": this.dialogs = Math.max(0, this.dialogs - 1); break;
    }
  }

  snapshot(seq: number, idle: boolean) {
    return { state: this.dialogs > 0 ? "blocked" : this.state === "idle" && !idle ? "working" : this.state, seq };
  }
}
