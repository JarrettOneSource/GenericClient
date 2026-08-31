import net from "node:net";
import { chmod, mkdir, lstat, unlink } from "node:fs/promises";
import path from "node:path";

const MAX_HANDOFF_BYTES = 256 * 1_024;

export class LauncherHandoffServer {
  constructor({ socketPath, broker, createServer = net.createServer }) {
    if (!socketPath || !broker) {
      throw new Error("LauncherHandoffServer requires socketPath and broker");
    }
    this.socketPath = path.resolve(socketPath);
    this.broker = broker;
    this.createServer = createServer;
    this.server = null;
    this.connections = new Set();
    this.ownsSocket = false;
  }

  async start() {
    if (this.server) {
      return this.status();
    }
    const directory = path.dirname(this.socketPath);
    const directoryExists = await exists(directory);
    await mkdir(directory, { recursive: true, mode: 0o700 });
    if (!directoryExists) {
      await chmod(directory, 0o700);
    }
    if (await exists(this.socketPath)) {
      if (await socketAcceptsConnections(this.socketPath)) {
        throw new Error(`Another GenericClient Harness owns ${this.socketPath}`);
      }
      await unlink(this.socketPath);
    }
    const server = this.createServer((socket) => this.#connection(socket));
    this.server = server;
    try {
      await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(this.socketPath, () => {
          server.off("error", reject);
          resolve();
        });
      });
      await chmod(this.socketPath, 0o600);
      this.ownsSocket = true;
    } catch (error) {
      const bound = server.listening;
      if (bound) {
        await new Promise((resolve) => server.close(resolve));
      }
      if (bound && await exists(this.socketPath)) {
        await unlink(this.socketPath);
      }
      this.server = null;
      throw error;
    }
    return this.status();
  }

  status() {
    return {
      running: Boolean(this.server?.listening),
      socket_path: this.socketPath,
    };
  }

  async close() {
    for (const socket of this.connections) {
      socket.destroy();
    }
    this.connections.clear();
    const server = this.server;
    this.server = null;
    const removeSocket = this.ownsSocket;
    this.ownsSocket = false;
    if (server) {
      await new Promise((resolve, reject) => {
        server.close((error) => error ? reject(error) : resolve());
      });
    }
    if (removeSocket && await exists(this.socketPath)) {
      await unlink(this.socketPath);
    }
  }

  #connection(socket) {
    this.connections.add(socket);
    socket.setEncoding("utf8");
    socket.setTimeout(10_000, () => socket.destroy());
    socket.once("close", () => this.connections.delete(socket));
    let source = "";
    let handled = false;
    socket.on("data", (chunk) => {
      if (handled) {
        return;
      }
      source += chunk;
      if (Buffer.byteLength(source) > MAX_HANDOFF_BYTES) {
        writeResponse(socket, { ok: false, error: "Jagex launcher handoff is too large" });
        return;
      }
      const boundary = source.indexOf("\n");
      if (boundary < 0) {
        return;
      }
      const line = source.slice(0, boundary);
      source = "";
      handled = true;
      void this.#accept(socket, line);
    });
  }

  async #accept(socket, line) {
    try {
      const handoff = JSON.parse(line);
      const result = await this.broker.accept(handoff);
      writeResponse(socket, { ok: true, result });
    } catch (error) {
      writeResponse(socket, { ok: false, error: error?.message || "Jagex handoff failed" });
    }
  }
}

async function exists(file) {
  try {
    await lstat(file);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") {
      return false;
    }
    throw error;
  }
}

function socketAcceptsConnections(socketPath) {
  return new Promise((resolve) => {
    const socket = net.createConnection(socketPath);
    socket.once("connect", () => {
      socket.destroy();
      resolve(true);
    });
    socket.once("error", () => resolve(false));
  });
}

function writeResponse(socket, value) {
  if (!socket.destroyed) {
    socket.end(`${JSON.stringify(value)}\n`);
  }
}
