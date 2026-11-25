import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { VERSION as PI_VERSION } from "@earendil-works/pi-coding-agent";

import { contextUsage } from "./commands.ts";
import type { ExecutionState } from "./execution.ts";
import { lastResponse } from "./history.ts";

export type BuildInfo = { revision?: string; modified?: boolean };

/** Session metadata returned by "info" and attached to every history snapshot. */
export function buildInfo(
  pi: ExtensionAPI,
  ctx: ExtensionContext,
  opts: {
    seq: number;
    execution: ExecutionState;
    startReason: string;
    instanceId: string;
    bridgeVersion: string;
    build: BuildInfo;
  },
) {
  const sm = ctx.sessionManager;
  return {
    paneId: process.env.HERDR_PANE_ID,
    herdrSocket: process.env.HERDR_SOCKET_PATH,
    pid: process.pid,
    sessionId: sm.getSessionId(),
    sessionFile: sm.getSessionFile(),
    sessionName: sm.getSessionName(),
    cwd: ctx.cwd,
    model: ctx.model?.id,
    thinkingLevel: pi.getThinkingLevel(),
    contextUsage: contextUsage(ctx),
    capabilities: ["slashCommands", "contextUsage", "executionState"],
    idle: ctx.isIdle(),
    execution: opts.execution.snapshot(opts.seq, ctx.isIdle()),
    lastEntryId: sm.getLeafId(),
    lastResponse: lastResponse(sm.getBranch()),
    startReason: opts.startReason,
    instanceId: opts.instanceId,
    bridgeVersion: opts.bridgeVersion,
    build: opts.build,
    runtime: { piVersion: PI_VERSION, nodeVersion: process.version, platform: process.platform, arch: process.arch },
  };
}
