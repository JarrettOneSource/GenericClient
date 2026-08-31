import os from "node:os";
import path from "node:path";

export function defaultLauncherSocket(environment = process.env) {
  if (environment.GENERICCLIENT_HARNESS_SOCKET) {
    return path.resolve(environment.GENERICCLIENT_HARNESS_SOCKET);
  }
  const owner = typeof process.getuid === "function" ? process.getuid() : "user";
  const runtimeRoot = environment.XDG_RUNTIME_DIR
    ? path.resolve(environment.XDG_RUNTIME_DIR)
    : os.tmpdir();
  return path.join(runtimeRoot, `genericclient-${owner}`, "launcher.sock");
}
