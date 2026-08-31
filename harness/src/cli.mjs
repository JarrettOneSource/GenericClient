#!/usr/bin/env node
import { execFile, spawn } from "node:child_process";
import { closeSync, mkdirSync, openSync } from "node:fs";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

import { callInstance } from "./client.mjs";
import { readProcessMemory, summarizeFleet } from "./memory.mjs";
import { InstanceRegistry } from "./registry.mjs";

const execute = promisify(execFile);
const harnessDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryDirectory = path.resolve(harnessDirectory, "..");

export async function run(argv, io = console) {
  const { command, options, positional } = parseArguments(argv);
  const runtimeDirectory = path.resolve(
    options.runtime || process.env.GENERICCLIENT_RUNTIME_DIR || path.join(harnessDirectory, "run"),
  );
  const instanceDirectory = path.resolve(
    options.directory || path.join(runtimeDirectory, "instances"),
  );
  const registry = new InstanceRegistry(instanceDirectory);

  let result;
  switch (command) {
    case "instances":
      result = await registry.scan();
      break;
    case "wait":
      result = await waitForInstance(registry, options);
      break;
    case "memory":
      result = await memoryReceipt(registry);
      break;
    case "probe-bridge":
      result = await bridgeProbe(Boolean(options.wine));
      break;
    case "launch-dense":
      result = launchDense(runtimeDirectory, instanceDirectory, options);
      break;
    case "stop":
      result = await stopInstance(registry, options.instance || positional[0]);
      break;
    case "status": {
      const instance = await registry.resolve(options.instance || positional[0]);
      result = await callInstance(instance, "status");
      break;
    }
    default:
      throw new Error(
        "Usage: cli.mjs <instances|wait|memory|probe-bridge|launch-dense|stop|status> [options]",
      );
  }
  io.log(JSON.stringify(result, null, 2));
  return result;
}

async function waitForInstance(registry, options) {
  const timeoutMs = positiveInteger(options.timeout || "30000", "timeout");
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const instance = await registry.resolve(options.instance);
      if (options.state) {
        const status = await callInstance(instance, "status", {}, 5_000);
        if (status.game_state !== options.state) {
          throw new Error(`game state is ${status.game_state}, expected ${options.state}`);
        }
        return { instance, status };
      }
      return { instance };
    } catch (error) {
      lastError = error;
      await sleep(100);
    }
  }
  throw new Error(`Timed out waiting for GenericClient: ${lastError?.message || "unavailable"}`);
}

async function memoryReceipt(registry) {
  const { instances, rejected } = await registry.scan();
  const samples = [];
  for (const instance of instances) {
    samples.push({
      instance_id: instance.instance_id,
      ...(await readProcessMemory(instance.pid)),
    });
  }
  return { ...summarizeFleet(samples), rejected };
}

function launchDense(runtimeDirectory, instanceDirectory, options) {
  const instanceId = options.instance || randomUUID();
  const jar = path.resolve(options.jar || path.join(repositoryDirectory, "build/libs/GenericClient.jar"));
  const logDirectory = path.join(runtimeDirectory, "logs");
  mkdirSync(logDirectory, { recursive: true });
  mkdirSync(instanceDirectory, { recursive: true });
  const logPath = path.join(logDirectory, `${instanceId}.log`);
  const logFd = openSync(logPath, "a", 0o600);
  const bootstrap = path.join(harnessDirectory, "bin", "genericclient-bootstrap");
  const env = {
    ...process.env,
    GENERICCLIENT_INSTANCE_ID: instanceId,
    GENERICCLIENT_RUNTIME_DIR: runtimeDirectory,
    GENERICCLIENT_INSTANCE_DIRECTORY: instanceDirectory,
    GENERICCLIENT_JAR: jar,
  };
  if (options.heap) {
    env.GENERICCLIENT_HEAP_SIZE = options.heap;
  }
  if (options.archive) {
    env.GENERICCLIENT_SHARED_ARCHIVE = path.resolve(options.archive);
  }
  if (options["archive-output"]) {
    env.GENERICCLIENT_ARCHIVE_AT_EXIT = path.resolve(options["archive-output"]);
  }
  const executable = process.env.DISPLAY ? bootstrap : "xvfb-run";
  const args = process.env.DISPLAY ? [] : ["-a", bootstrap];
  const child = spawn(executable, args, {
    detached: true,
    env,
    stdio: ["ignore", logFd, logFd],
  });
  closeSync(logFd);
  child.unref();
  return {
    instance_id: instanceId,
    supervisor_pid: child.pid,
    descriptor_directory: instanceDirectory,
    log_path: logPath,
    jar,
  };
}

async function stopInstance(registry, instanceId) {
  if (!instanceId) {
    throw new Error("stop requires --instance <id>");
  }
  const instance = await registry.resolve(instanceId);
  process.kill(instance.pid, "SIGTERM");
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    try {
      process.kill(instance.pid, 0);
      await sleep(100);
    } catch (error) {
      if (error?.code === "ESRCH") {
        return { instance_id: instanceId, pid: instance.pid, stopped: true };
      }
      throw error;
    }
  }
  throw new Error(`PID ${instance.pid} did not stop within 10 seconds`);
}

async function bridgeProbe(useWine) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "genericclient-cli-probe-"));
  const output = path.join(directory, "receipt.json");
  const bridge = path.join(harnessDirectory, "bin", "GenericClient-RuneLite.exe");
  const env = {
    ...process.env,
    GENERICCLIENT_BRIDGE_PROBE_OUTPUT: output,
    JX_SESSION_ID: "probe-session",
    JX_CHARACTER_ID: "probe-character",
    JX_DISPLAY_NAME: "Probe Character",
    JX_ACCESS_TOKEN: "",
    JX_REFRESH_TOKEN: "",
    WINEDEBUG: "-all",
  };
  try {
    if (useWine) {
      await execute("xvfb-run", [
        "-a",
        "bash",
        "-c",
        [
          'wine start /unix "$1" cli-proof >/dev/null 2>&1 || true',
          "for attempt in $(seq 1 200); do",
          '  test -f "$GENERICCLIENT_BRIDGE_PROBE_OUTPUT" && exit 0',
          "  sleep 0.1",
          "done",
          "exit 1",
        ].join("\n"),
        "genericclient-cli-probe",
        bridge,
      ], { env, timeout: 30_000 });
    } else {
      await execute(bridge, [], { env, timeout: 10_000 });
    }
    return JSON.parse(await readFile(output, "utf8"));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

export function parseArguments(argv) {
  const [command, ...rest] = argv;
  const options = {};
  const positional = [];
  for (let index = 0; index < rest.length; index++) {
    const argument = rest[index];
    if (!argument.startsWith("--")) {
      positional.push(argument);
      continue;
    }
    const equals = argument.indexOf("=");
    if (equals > 2) {
      options[argument.slice(2, equals)] = argument.slice(equals + 1);
      continue;
    }
    const name = argument.slice(2);
    if (rest[index + 1] && !rest[index + 1].startsWith("--")) {
      options[name] = rest[++index];
    } else {
      options[name] = true;
    }
  }
  return { command, options, positional };
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  run(process.argv.slice(2)).catch((error) => {
    console.error(JSON.stringify({ ok: false, error: error.message }));
    process.exitCode = 1;
  });
}
