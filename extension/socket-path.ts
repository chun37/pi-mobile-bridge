import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/** Create ~/.pi/agent/bridge owned by this user, refusing symlinks and foreign owners. */
export function prepareBridgeDir(home: string = os.homedir()): string {
  const dir = path.join(home, ".pi", "agent", "bridge");
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const st = fs.lstatSync(dir);
  if (!st.isDirectory() || st.isSymbolicLink() || st.uid !== process.getuid?.())
    throw new Error("unsafe_bridge_directory");
  fs.chmodSync(dir, 0o700);
  return dir;
}

/** Remove a stale socket left by an earlier runtime at exactly this PID's path. */
export function removeStaleSocket(socketPath: string): void {
  try {
    const st = fs.lstatSync(socketPath);
    if (!st.isSocket() || st.uid !== process.getuid?.()) throw new Error("unsafe_socket");
    fs.unlinkSync(socketPath);
  } catch (e: any) {
    if (e.code !== "ENOENT") throw e;
  }
}
