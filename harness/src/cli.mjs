#!/usr/bin/env node
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

import { callInstance } from "./client.mjs";
import { createDashboardRuntime } from "./dashboard-runtime.mjs";
import { readProcessMemory, summarizeFleet } from "./memory.mjs";
import { ProcessSupervisor } from "./process-supervisor.mjs";
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
  const supervisor = new ProcessSupervisor({
    runtimeDirectory,
    instanceDirectory,
    repositoryDirectory,
    harnessDirectory,
  });

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
      result = supervisor.start({
        instance_id: options.instance,
        jar: options.jar,
        heap: options.heap,
        archive: options.archive,
        archive_output: options["archive-output"],
        runelite_profile: options.profile,
      });
      break;
    case "stop":
      result = await stopInstance(registry, supervisor, options.instance || positional[0]);
      break;
    case "status": {
      const instance = await registry.resolve(options.instance || positional[0]);
      result = await callInstance(instance, "status");
      break;
    }
    case "serve": {
      const host = loopbackHost(options.host || "127.0.0.1");
      const port = portInteger(options.port || "3765");
      const pollIntervalMs = positiveInteger(options.poll || "1000", "poll");
      const screenshotTtlMs = positiveInteger(
        options["screenshot-ttl"] || "10000",
        "screenshot-ttl",
      );
      const dashboard = createDashboardRuntime({
        runtimeDirectory,
        instanceDirectory,
        repositoryDirectory,
        harnessDirectory,
        host,
        port,
        pollIntervalMs,
        screenshotTtlMs,
      });
      const address = await dashboard.start();
      installShutdownHandlers(dashboard);
      result = {
        schema: "genericclient_dashboard_server.v1",
        ...address,
        runtime_directory: runtimeDirectory,
        instance_directory: instanceDirectory,
        poll_interval_millis: pollIntervalMs,
        screenshot_ttl_millis: screenshotTtlMs,
      };
      break;
    }
    default:
      throw new Error(
        "Usage: cli.mjs <instances|wait|memory|probe-bridge|launch-dense|stop|status|serve> [options]",
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

async function stopInstance(registry, supervisor, instanceId) {
  if (!instanceId) {
    throw new Error("stop requires --instance <id>");
  }
  const instance = await registry.resolve(instanceId);
  return supervisor.stop(instance);
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

function portInteger(value) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 0 || parsed > 65_535) {
    throw new Error("port must be an integer from 0 through 65535");
  }
  return parsed;
}

function loopbackHost(value) {
  if (!["127.0.0.1", "::1", "localhost"].includes(value)) {
    throw new Error("host must be 127.0.0.1, ::1, or localhost");
  }
  return value;
}

function installShutdownHandlers(dashboard) {
  let closing = false;
  const close = async () => {
    if (closing) {
      return;
    }
    closing = true;
    try {
      await dashboard.close();
    } catch (error) {
      console.error(JSON.stringify({ ok: false, error: error.message }));
      process.exitCode = 1;
    }
  };
  process.once("SIGINT", close);
  process.once("SIGTERM", close);
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
