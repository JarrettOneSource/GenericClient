import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { once } from "node:events";
import test from "node:test";

import { parseArguments, run } from "../src/cli.mjs";
import { INSTANCE_SCHEMA } from "../src/registry.mjs";

test("parses positional flags values and equals options", () => {
  assert.deepEqual(
    parseArguments(["wait", "fallback", "--instance=alpha", "--timeout", "5000", "--flag"]),
    {
      command: "wait",
      positional: ["fallback"],
      options: { instance: "alpha", timeout: "5000", flag: true },
    },
  );
});

test("instances emits a stable empty JSON result", async (context) => {
  const runtime = await temporaryDirectory(context, "genericclient-cli-empty-");
  const lines = [];
  const result = await run(["instances", "--runtime", runtime], { log: (line) => lines.push(line) });

  assert.deepEqual(result, { instances: [], rejected: [] });
  assert.deepEqual(JSON.parse(lines[0]), result);
});

test("probe-bridge returns only inheritance presence", async () => {
  const lines = [];
  const result = await run(["probe-bridge"], { log: (line) => lines.push(line) });

  assert.equal(result.schema, "genericclient_bridge_probe.v1");
  assert.equal(result.native_platform, "linux");
  assert.equal(result.inherited.JX_SESSION_ID, true);
  assert.equal(JSON.stringify(result).includes("probe-session"), false);
});

test("stop terminates the explicitly selected healthy instance", async (context) => {
  const runtime = await temporaryDirectory(context, "genericclient-cli-stop-");
  const descriptors = path.join(runtime, "instances");
  const child = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], {
    stdio: "ignore",
  });
  const childExited = once(child, "exit");
  const server = http.createServer((request, response) => {
    response.setHeader("Content-Type", "application/json");
    response.end(JSON.stringify({
      ok: true,
      schema: INSTANCE_SCHEMA,
      instance_id: "stop-me",
      pid: child.pid,
      control_url: controlUrl(server),
    }));
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => server.close());
  context.after(() => {
    try {
      process.kill(child.pid, "SIGKILL");
    } catch {
      // Already stopped.
    }
  });
  await writeDescriptor(descriptors, {
    schema: INSTANCE_SCHEMA,
    instance_id: "stop-me",
    pid: child.pid,
    started_epoch_millis: Date.now(),
    control_url: controlUrl(server),
    lifecycle: "login_screen",
    dense: true,
  });

  const result = await run(
    ["stop", "--directory", descriptors, "--instance", "stop-me"],
    { log: () => {} },
  );
  await childExited;
  assert.deepEqual(result, { instance_id: "stop-me", pid: child.pid, stopped: true });
});

function controlUrl(server) {
  return `http://127.0.0.1:${server.address().port}`;
}

async function writeDescriptor(directory, descriptor) {
  await mkdir(directory, { recursive: true });
  await writeFile(
    path.join(directory, `${descriptor.instance_id}.json`),
    `${JSON.stringify(descriptor)}\n`,
  );
}

async function temporaryDirectory(context, prefix) {
  const directory = await mkdtemp(path.join(os.tmpdir(), prefix));
  context.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}
