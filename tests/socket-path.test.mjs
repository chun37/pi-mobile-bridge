import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { prepareBridgeDir, removeStaleSocket } from "../extension/socket-path.ts";

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "pibridge-socket-"));
const listen = (p) => new Promise((ok, no) => { const s = net.createServer(); s.once("error", no); s.listen(p, () => ok(s)); });

test("the bridge directory is created private to this user under the given home", () => {
  const home = tmp();
  const dir = prepareBridgeDir(home);
  assert.equal(dir, path.join(home, ".pi", "agent", "bridge"));
  const st = fs.statSync(dir);
  assert.ok(st.isDirectory());
  assert.equal(st.mode & 0o777, 0o700);
  assert.equal(dir, prepareBridgeDir(home));
});

test("a symlinked bridge directory is refused", () => {
  const home = tmp();
  const real = tmp();
  fs.mkdirSync(path.join(home, ".pi", "agent"), { recursive: true });
  fs.symlinkSync(real, path.join(home, ".pi", "agent", "bridge"));
  assert.throws(() => prepareBridgeDir(home), /unsafe_bridge_directory/);
});

test("a missing socket path is fine, a regular file is refused and a stale socket is removed", async () => {
  const dir = tmp();
  const missing = path.join(dir, "missing.sock");
  removeStaleSocket(missing);
  const file = path.join(dir, "file.sock");
  fs.writeFileSync(file, "x");
  assert.throws(() => removeStaleSocket(file), /unsafe_socket/);
  assert.ok(fs.existsSync(file));
  const stale = path.join(dir, "stale.sock");
  const server = await listen(stale);
  try {
    assert.ok(fs.lstatSync(stale).isSocket());
    removeStaleSocket(stale);
    assert.ok(!fs.existsSync(stale));
  } finally {
    await new Promise((ok) => server.close(ok));
  }
});
