import assert from "node:assert/strict";
import test from "node:test";

import { ScreenshotCache } from "../src/screenshot-cache.mjs";

test("caches screenshots and supports forced refresh and expiry", async () => {
  let now = 1_000;
  let calls = 0;
  const cache = cacheWith({
    now: () => now,
    callInstance: async () => screenshot(++calls),
    ttlMs: 100,
  });

  const first = await cache.get("alpha");
  assert.equal((await cache.get("alpha")).etag, first.etag);
  assert.equal(calls, 1);

  const refreshed = await cache.get("alpha", { refresh: true });
  assert.notEqual(refreshed.etag, first.etag);
  assert.equal(calls, 2);

  now += 101;
  await cache.get("alpha");
  assert.equal(calls, 3);
});

test("deduplicates in-flight capture per instance", async () => {
  let resolveCapture;
  let calls = 0;
  const pending = new Promise((resolve) => {
    resolveCapture = resolve;
  });
  const cache = cacheWith({
    callInstance: async () => {
      calls++;
      return pending;
    },
  });

  const first = cache.get("alpha", { refresh: true });
  const second = cache.get("alpha", { refresh: true });
  resolveCapture(screenshot(1));
  assert.strictEqual(await first, await second);
  assert.equal(calls, 1);
});

test("isolates instances and evicts the least recently used entry", async () => {
  const calls = new Map();
  const cache = cacheWith({
    maxEntries: 1,
    callInstance: async (instance) => {
      const count = (calls.get(instance.instance_id) || 0) + 1;
      calls.set(instance.instance_id, count);
      return screenshot(count + instance.instance_id.length);
    },
  });

  await cache.get("alpha");
  await cache.get("beta");
  await cache.get("alpha");
  assert.equal(calls.get("alpha"), 2);
  assert.equal(calls.get("beta"), 1);
  cache.clearMissing(["beta"]);
  assert.equal(cache.entries.has("alpha"), false);
});

test("does not cache failed or invalid captures", async () => {
  let calls = 0;
  const cache = cacheWith({
    callInstance: async () => {
      calls++;
      return { mime_type: "text/plain", image_base64: "eA==", width: 1, height: 1 };
    },
  });

  await assert.rejects(() => cache.get("alpha"), /MIME/);
  await assert.rejects(() => cache.get("alpha"), /MIME/);
  assert.equal(calls, 2);
});

function cacheWith(overrides = {}) {
  return new ScreenshotCache({
    registry: {
      resolve: async (instanceId) => ({ instance_id: instanceId, control_url: "unused" }),
    },
    callInstance: async () => screenshot(1),
    ttlMs: 10_000,
    maxEntries: 20,
    now: () => 1_000,
    ...overrides,
  });
}

function screenshot(seed) {
  const buffer = Buffer.from([0x89, 0x50, 0x4e, 0x47, seed]);
  return {
    mime_type: "image/png",
    image_base64: buffer.toString("base64"),
    width: 765,
    height: 503,
    captured_at_epoch_millis: 1_788_000_000_000 + seed,
  };
}
