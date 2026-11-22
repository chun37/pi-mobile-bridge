import { test } from "node:test";
import assert from "node:assert/strict";
import { captureBuildInfo } from "../extension/build-info.ts";

const moduleUrl = new URL("../extension/index.ts", import.meta.url).href;
const hash = "a".repeat(40);
function fixture(status = "") {
  const calls = [];
  return { calls, run: (cwd, args) => {
    calls.push(args);
    if (args.includes("--show-toplevel")) return cwd;
    if (args.includes("HEAD")) return hash;
    return status;
  } };
}
test("extension captures committed source revision", () => {
  const f = fixture();
  assert.deepEqual(captureBuildInfo(moduleUrl, f.run), { revision: hash, modified: false });
  assert.deepEqual(f.calls, [["rev-parse", "--show-toplevel"], ["rev-parse", "--verify", "HEAD"], ["status", "--porcelain"]]);
});
test("same release with uncommitted changes is distinguishable", () => {
  assert.deepEqual(captureBuildInfo(moduleUrl, fixture(" M extension/index.ts").run), { revision: hash, modified: true });
});
test("an unrelated enclosing repository is not reported as this build", () => {
  assert.deepEqual(captureBuildInfo(moduleUrl, () => "/unrelated"), {});
});
test("missing git, invalid revision and non-file installs stay unknown", () => {
  assert.deepEqual(captureBuildInfo(moduleUrl, () => { throw new Error("no git"); }), {});
  assert.deepEqual(captureBuildInfo(moduleUrl, (cwd,args) => args.includes("--show-toplevel") ? cwd : "invalid"), {});
  assert.deepEqual(captureBuildInfo("https://example.invalid/extension.ts"), {});
});
