// Test-only command. Load explicitly in the isolated, local-model Pi fixture.
import fs from "node:fs";
import path from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
export default function(pi: ExtensionAPI) {
  pi.registerCommand("bridge-test-slash", {
    description: "Test-only mobile slash command",
    handler: async (args, ctx) => {
      const root = process.env.BRIDGE_TEST_ROOT;
      if (!root) throw new Error("BRIDGE_TEST_ROOT is required");
      fs.appendFileSync(path.join(root, "slash-observations.jsonl"), JSON.stringify({ args, sessionId: ctx.sessionManager.getSessionId() }) + "\n");
      pi.setSessionName("bridge-slash-command-ok");
    },
  });
}
