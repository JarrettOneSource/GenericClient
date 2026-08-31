import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { DashboardServer } from "../src/dashboard-server.mjs";

test("serves only known dashboard assets with restrictive headers", async (context) => {
  const fixture = await startFixture(context);
  const index = await fetch(`${fixture.url}/`);
  assert.equal(index.status, 200);
  assert.equal(await index.text(), "<main>fleet</main>");
  assert.match(index.headers.get("content-security-policy"), /default-src 'self'/);
  assert.equal(index.headers.get("x-frame-options"), "DENY");
  assert.equal(index.headers.get("x-content-type-options"), "nosniff");
  assert.equal(index.headers.get("referrer-policy"), "no-referrer");

  const script = await fetch(`${fixture.url}/app.js`);
  assert.match(script.headers.get("content-type"), /text\/javascript/);
  assert.equal(await script.text(), "globalThis.dashboardLoaded = true;\n");
  assert.equal((await fetch(`${fixture.url}/unknown.js`)).status, 404);
  const wrongMethod = await fetch(`${fixture.url}/styles.css`, { method: "POST" });
  assert.equal(wrongMethod.status, 405);
  assert.equal(wrongMethod.headers.get("allow"), "GET");
});

test("exposes fleet health and explicitly routed instance lifecycle APIs", async (context) => {
  const fixture = await startFixture(context);
  const fleetResponse = await fetch(`${fixture.url}/api/fleet`);
  assert.deepEqual(await fleetResponse.json(), fixture.monitor.latest);
  const detail = await fetch(`${fixture.url}/api/instances/alpha`);
  assert.deepEqual(await detail.json(), { instance_id: "alpha", game_state: "LOGIN_SCREEN" });
  assert.deepEqual(fixture.controller.calls[0], { operation: "get", instanceId: "alpha" });

  const health = await fetch(`${fixture.url}/health`);
  const healthBody = await health.json();
  assert.equal(healthBody.ok, true);
  assert.equal(healthBody.schema, "genericclient_dashboard.v1");
  assert.equal(healthBody.monitor.running, true);

  const start = await postJson(`${fixture.url}/api/instances`, {
    instance_id: "replacement",
    heap: "384m",
  });
  assert.equal(start.status, 202);
  assert.equal((await start.json()).result.instance_id, "replacement");

  const command = await postJson(`${fixture.url}/api/instances/alpha/commands`, {
    command: "session.login",
  });
  assert.equal(command.status, 200);
  assert.equal((await command.json()).result.command, "session.login");

  const stop = await postJson(`${fixture.url}/api/instances/alpha/stop`, {});
  assert.equal(stop.status, 200);
  assert.equal((await stop.json()).result.stopped, true);
  assert.deepEqual(fixture.screenshotCache.invalidated, ["alpha"]);
  assert.deepEqual(
    fixture.controller.calls.map((call) => call.operation),
    ["get", "start", "command", "stop"],
  );
  assert.equal(fixture.monitor.refreshCalls, 2);
});

test("bounds and validates JSON mutations and maps domain failures", async (context) => {
  const fixture = await startFixture(context, { bodyLimitBytes: 32 });
  const missingType = await fetch(`${fixture.url}/api/instances`, {
    method: "POST",
    body: "{}",
  });
  assert.equal(missingType.status, 415);

  const malformed = await fetch(`${fixture.url}/api/instances`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{",
  });
  assert.equal(malformed.status, 400);

  const oversized = await postJson(`${fixture.url}/api/instances`, {
    instance_id: "x".repeat(40),
  });
  assert.equal(oversized.status, 413);

  assert.equal((await fetch(`${fixture.url}/api/instances/bad%20id`)).status, 400);
  assert.equal((await fetch(`${fixture.url}/api/instances/missing`)).status, 404);
  assert.equal((await postJson(`${fixture.url}/api/instances`, {
    instance_id: "duplicate",
  })).status, 409);
  assert.equal((await fetch(`${fixture.url}/api/instances/alpha/commands`)).status, 405);
  assert.equal((await fetch(`${fixture.url}/api/raw-rpc`)).status, 404);
});

test("serves isolated cached screenshots with validators and forced refresh", async (context) => {
  const fixture = await startFixture(context);
  const first = await fetch(`${fixture.url}/api/instances/alpha/screenshot`);
  assert.equal(first.status, 200);
  assert.equal(first.headers.get("content-type"), "image/png");
  assert.equal(first.headers.get("etag"), '"png-etag"');
  assert.deepEqual(Buffer.from(await first.arrayBuffer()), fixture.screenshotCache.buffer);

  const unchanged = await fetch(`${fixture.url}/api/instances/alpha/screenshot`, {
    headers: { "If-None-Match": '"png-etag"' },
  });
  assert.equal(unchanged.status, 304);
  const refreshed = await fetch(`${fixture.url}/api/instances/alpha/screenshot?refresh=1`);
  assert.equal(refreshed.status, 200);
  assert.deepEqual(fixture.screenshotCache.requests, [
    { instanceId: "alpha", options: { refresh: false } },
    { instanceId: "alpha", options: { refresh: false } },
    { instanceId: "alpha", options: { refresh: true } },
  ]);
});

test("streams an immediate fleet event and later monitor changes over SSE", async (context) => {
  const fixture = await startFixture(context);
  const controller = new AbortController();
  const response = await fetch(`${fixture.url}/api/events`, { signal: controller.signal });
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type"), /text\/event-stream/);
  const reader = response.body.getReader();
  let received = await readUntil(reader, "event: fleet");
  assert.match(received, /id: 1/);
  assert.match(received, /"instance_id":"alpha"/);

  fixture.monitor.publish({
    ...fixture.monitor.latest,
    sequence: 2,
    summary: { total_instances: 0 },
    instances: [],
  });
  received += await readUntil(reader, "id: 2");
  assert.match(received, /id: 2\nevent: fleet/);
  assert.deepEqual(fixture.screenshotCache.cleared.at(-1), []);
  controller.abort();
  await reader.cancel().catch(() => {});
});

async function startFixture(context, serverOptions = {}) {
  const webDirectory = await mkdtemp(path.join(os.tmpdir(), "genericclient-dashboard-test-"));
  await Promise.all([
    writeFile(path.join(webDirectory, "index.html"), "<main>fleet</main>"),
    writeFile(path.join(webDirectory, "styles.css"), "main { color: green; }\n"),
    writeFile(path.join(webDirectory, "app.js"), "globalThis.dashboardLoaded = true;\n"),
  ]);
  const controller = fakeController();
  const monitor = new FakeMonitor();
  const screenshotCache = new FakeScreenshotCache();
  const server = new DashboardServer({
    controller,
    monitor,
    screenshotCache,
    webDirectory,
    keepaliveMs: 60_000,
    ...serverOptions,
  });
  const address = await server.start();
  context.after(async () => {
    await server.close();
    await rm(webDirectory, { recursive: true, force: true });
  });
  return { ...address, controller, monitor, screenshotCache, server };
}

function fakeController() {
  return {
    calls: [],
    async get(instanceId) {
      this.calls.push({ operation: "get", instanceId });
      if (instanceId === "missing") {
        throw new Error("No healthy GenericClient instance 'missing'");
      }
      return { instance_id: instanceId, game_state: "LOGIN_SCREEN" };
    },
    async start(spec) {
      this.calls.push({ operation: "start", spec });
      if (spec.instance_id === "duplicate") {
        throw new Error("GenericClient instance 'duplicate' is already running");
      }
      return { instance_id: spec.instance_id, supervisor_pid: 700 };
    },
    async stop(instanceId) {
      this.calls.push({ operation: "stop", instanceId });
      return { instance_id: instanceId, stopped: true };
    },
    async command(instanceId, command) {
      this.calls.push({ operation: "command", instanceId, command });
      return { instance_id: instanceId, command: command.command, result: { accepted: true } };
    },
  };
}

class FakeMonitor {
  constructor() {
    this.running = false;
    this.refreshCalls = 0;
    this.listeners = new Set();
    this.latest = {
      schema: "genericclient_fleet.v1",
      sequence: 1,
      generated_at_epoch_millis: 100,
      summary: { total_instances: 1 },
      instances: [{ instance_id: "alpha" }],
      rejected: [],
    };
  }

  async start() {
    this.running = true;
    return this.latest;
  }

  async refresh() {
    this.refreshCalls++;
    return this.latest;
  }

  subscribe(listener) {
    this.listeners.add(listener);
    listener(this.latest);
    return () => this.listeners.delete(listener);
  }

  publish(snapshot) {
    this.latest = snapshot;
    for (const listener of this.listeners) {
      listener(snapshot);
    }
  }

  status() {
    return { running: this.running, has_snapshot: true, subscriber_count: this.listeners.size };
  }

  close() {
    this.running = false;
    this.listeners.clear();
  }
}

class FakeScreenshotCache {
  constructor() {
    this.buffer = Buffer.from([137, 80, 78, 71]);
    this.requests = [];
    this.invalidated = [];
    this.cleared = [];
  }

  async get(instanceId, options) {
    this.requests.push({ instanceId, options });
    return {
      instance_id: instanceId,
      buffer: this.buffer,
      mime_type: "image/png",
      width: 765,
      height: 503,
      captured_at_epoch_millis: 123,
      etag: '"png-etag"',
    };
  }

  invalidate(instanceId) {
    this.invalidated.push(instanceId);
  }

  clearMissing(instanceIds) {
    this.cleared.push(instanceIds);
  }
}

function postJson(url, body) {
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

async function readUntil(reader, marker) {
  const decoder = new TextDecoder();
  let value = "";
  while (!value.includes(marker)) {
    const chunk = await reader.read();
    if (chunk.done) {
      throw new Error(`SSE ended before '${marker}'`);
    }
    value += decoder.decode(chunk.value, { stream: true });
  }
  return value;
}
