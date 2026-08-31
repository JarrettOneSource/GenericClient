#!/usr/bin/env node
import net from "node:net";
import { fileURLToPath } from "node:url";

import { JAGEX_HANDOFF_SCHEMA } from "./launcher-broker.mjs";
import { defaultLauncherSocket } from "./launcher-handoff-path.mjs";

const ENVIRONMENT_KEYS = Object.freeze([
  "JX_SESSION_ID",
  "JX_CHARACTER_ID",
  "JX_DISPLAY_NAME",
  "JX_ACCESS_TOKEN",
  "JX_REFRESH_TOKEN",
  "DISPLAY",
  "WAYLAND_DISPLAY",
  "XAUTHORITY",
  "DBUS_SESSION_BUS_ADDRESS",
]);

export class HandoffUnavailableError extends Error {}

export function forwardLauncherHandoff(
  {
    socketPath = defaultLauncherSocket(),
    environment = process.env,
    arguments: clientArguments = [],
    timeoutMs = 10_000,
  } = {},
) {
  const payload = {
    schema: JAGEX_HANDOFF_SCHEMA,
    arguments: [...clientArguments],
    environment: Object.fromEntries(
      ENVIRONMENT_KEYS
        .filter((key) => typeof environment[key] === "string" && environment[key].length > 0)
        .map((key) => [key, environment[key]]),
    ),
  };
  return new Promise((resolve, reject) => {
    const socket = net.createConnection(socketPath);
    let connected = false;
    let source = "";
    const timeout = setTimeout(() => {
      socket.destroy();
      reject(new Error("GenericClient Harness did not answer the launcher handoff"));
    }, timeoutMs);
    const finish = (action, value) => {
      clearTimeout(timeout);
      socket.destroy();
      action(value);
    };
    socket.setEncoding("utf8");
    socket.once("connect", () => {
      connected = true;
      socket.write(`${JSON.stringify(payload)}\n`);
    });
    socket.on("data", (chunk) => {
      source += chunk;
      if (source.length > 256 * 1_024) {
        finish(reject, new Error("GenericClient Harness returned an oversized handoff receipt"));
        return;
      }
      const boundary = source.indexOf("\n");
      if (boundary < 0) {
        return;
      }
      try {
        const response = JSON.parse(source.slice(0, boundary));
        if (!response?.ok) {
          throw new Error(response?.error || "GenericClient Harness rejected the launcher handoff");
        }
        finish(resolve, response.result);
      } catch (error) {
        finish(reject, error);
      }
    });
    socket.once("error", (error) => {
      if (!connected && ["ENOENT", "ECONNREFUSED"].includes(error?.code)) {
        finish(reject, new HandoffUnavailableError("GenericClient Harness is not running"));
      } else {
        finish(reject, error);
      }
    });
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  forwardLauncherHandoff({ arguments: process.argv.slice(2) }).catch((error) => {
    if (error instanceof HandoffUnavailableError) {
      process.exitCode = 75;
      return;
    }
    console.error(error.message);
    process.exitCode = 1;
  });
}
