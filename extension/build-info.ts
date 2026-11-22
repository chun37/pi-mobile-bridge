import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

type RunGit = (cwd: string, args: string[]) => string;
const runGit: RunGit = (cwd, args) => execFileSync("git", ["--no-optional-locks", "-C", cwd, ...args], {
  encoding: "utf8", timeout: 1500, maxBuffer: 1024 * 1024,
  stdio: ["ignore", "pipe", "ignore"],
}).trim();

// Capture once at module load, never on a debug request. Git is read-only and
// argv is fixed, not supplied by clients. Standalone/non-Git installs are unknown.
export function captureBuildInfo(moduleUrl: string, run: RunGit = runGit): { revision?: string; modified?: boolean } {
  try {
    const root = path.dirname(path.dirname(fs.realpathSync(fileURLToPath(moduleUrl))));
    if (run(root, ["rev-parse", "--show-toplevel"]) !== root) return {};
    const revision = run(root, ["rev-parse", "--verify", "HEAD"]);
    if (!/^[a-f0-9]{40,64}$/.test(revision)) return {};
    const modified = run(root, ["status", "--porcelain"]) !== "";
    return { revision, modified };
  } catch {
    return {};
  }
}
