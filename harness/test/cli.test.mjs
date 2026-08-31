import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { once } from "node:events";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { parseArguments, run } from "../src/cli.mjs";
import { INSTANCE_SCHEMA } from "../src/registry.mjs";

const harnessDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryDirectory = path.resolve(harnessDirectory, "..");

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

test("serve starts the loopback dashboard and shuts down cleanly", async (context) => {
  const runtime = await temporaryDirectory(context, "genericclient-cli-dashboard-");
  const child = spawn(process.execPath, [
    path.join(harnessDirectory, "src/cli.mjs"),
    "serve",
    "--runtime",
    runtime,
    "--port",
    "0",
    "--poll",
    "50",
    "--screenshot-ttl",
    "250",
  ], {
    cwd: repositoryDirectory,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let cleanedUp = false;
  context.after(() => {
    if (!cleanedUp) {
      child.kill("SIGKILL");
    }
  });
  const receipt = await readJsonOutput(child);
  assert.equal(receipt.schema, "genericclient_dashboard_server.v1");
  assert.equal(receipt.host, "127.0.0.1");
  assert.ok(receipt.port > 0);
  assert.equal(receipt.poll_interval_millis, 50);
  assert.equal(receipt.screenshot_ttl_millis, 250);

  const health = await fetch(`${receipt.url}/health`);
  assert.equal(health.status, 200);
  assert.equal((await health.json()).ok, true);
  const page = await fetch(receipt.url);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /Instance fleet/);

  child.kill("SIGTERM");
  const [code, signal] = await once(child, "exit");
  cleanedUp = true;
  assert.equal(code, 0);
  assert.equal(signal, null);
});

test("serve rejects non-loopback and invalid cadence configuration", async () => {
  await assert.rejects(
    () => run(["serve", "--host", "0.0.0.0"], { log() {} }),
    /host must be/,
  );
  await assert.rejects(
    () => run(["serve", "--port", "65536"], { log() {} }),
    /port must be/,
  );
  await assert.rejects(
    () => run(["serve", "--poll", "0"], { log() {} }),
    /poll must be a positive integer/,
  );
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

function readJsonOutput(child) {
  return new Promise((resolve, reject) => {
    let stdout = "";
    let stderr = "";
    const timeout = setTimeout(() => reject(new Error(`Dashboard did not start: ${stderr}`)), 10_000);
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
      try {
        const value = JSON.parse(stdout);
        clearTimeout(timeout);
        resolve(value);
      } catch {
        // Pretty-printed JSON is incomplete until the final chunk arrives.
      }
    });
    child.once("exit", (code, signal) => {
      clearTimeout(timeout);
      reject(new Error(`Dashboard exited before its receipt: code=${code} signal=${signal} ${stderr}`));
    });
  });
}
