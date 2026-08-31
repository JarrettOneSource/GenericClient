import assert from "node:assert/strict";
import test from "node:test";

import { FleetMonitor } from "../src/fleet-monitor.mjs";

test("starts with an immediate sample and delivers it to existing and new subscribers", async () => {
  const scheduled = [];
  const snapshots = [snapshot(1, "LOGIN_SCREEN")];
  const monitor = new FleetMonitor({
    controller: { snapshot: async () => snapshots.shift() },
    pollIntervalMs: 250,
    now: () => 1_000,
    setTimeoutImpl: (callback, delay) => {
      scheduled.push({ callback, delay });
      return 17;
    },
    clearTimeoutImpl() {},
  });
  const first = [];
  monitor.subscribe((value) => first.push(value));

  await monitor.start();
  assert.equal(first.length, 1);
  assert.equal(first[0].sequence, 1);
  assert.equal(scheduled.length, 1);
  assert.equal(scheduled[0].delay, 250);

  const late = [];
  monitor.subscribe((value) => late.push(value));
  assert.deepEqual(late, first);
  assert.deepEqual(monitor.status(), {
    running: true,
    poll_interval_millis: 250,
    subscriber_count: 2,
    has_snapshot: true,
    last_poll_epoch_millis: 1_000,
    last_event_epoch_millis: 1_000,
    last_error: null,
  });
  monitor.close();
});

test("suppresses timestamp-only changes and emits semantic state changes", async () => {
  const samples = [
    snapshot(1, "LOGIN_SCREEN", 100),
    snapshot(2, "LOGIN_SCREEN", 200),
    snapshot(3, "LOGGED_IN", 300),
  ];
  const monitor = new FleetMonitor({
    controller: { snapshot: async () => samples.shift() },
    now: () => 2_000,
  });
  const delivered = [];
  monitor.subscribe((value) => delivered.push(value.sequence));

  await monitor.refresh();
  await monitor.refresh();
  await monitor.refresh();

  assert.deepEqual(delivered, [1, 3]);
  assert.equal(monitor.latest.sequence, 3);
});

test("serial polling recovers after errors and never overlaps samples", async () => {
  const scheduled = [];
  let active = 0;
  let calls = 0;
  const monitor = new FleetMonitor({
    controller: {
      snapshot: async () => {
        calls++;
        active++;
        assert.equal(active, 1);
        try {
          if (calls === 1) {
            throw new Error("registry temporarily unavailable");
          }
          return snapshot(calls, "LOGIN_SCREEN");
        } finally {
          active--;
        }
      },
    },
    now: () => calls * 100,
    setTimeoutImpl: (callback) => {
      scheduled.push(callback);
      return { unref() {} };
    },
    clearTimeoutImpl() {},
  });

  assert.equal(await monitor.start(), null);
  assert.match(monitor.status().last_error.message, /temporarily unavailable/);
  assert.equal(scheduled.length, 1);
  scheduled.shift()();
  await waitFor(() => monitor.latest !== null);
  assert.equal(monitor.latest.sequence, 2);
  assert.equal(monitor.status().last_error, null);
  assert.equal(scheduled.length, 1);
  monitor.close();
});

test("unsubscribe and close release listeners and pending timers", async () => {
  const cleared = [];
  const monitor = new FleetMonitor({
    controller: { snapshot: async () => snapshot(1, "LOGIN_SCREEN") },
    setTimeoutImpl: () => 91,
    clearTimeoutImpl: (handle) => cleared.push(handle),
  });
  let calls = 0;
  const unsubscribe = monitor.subscribe(() => calls++);
  await monitor.start();
  assert.equal(calls, 1);
  assert.equal(unsubscribe(), true);
  await monitor.refresh();
  assert.equal(calls, 1);

  monitor.close();
  assert.deepEqual(cleared, [91]);
  assert.equal(monitor.status().running, false);
  assert.equal(monitor.status().subscriber_count, 0);
});

function snapshot(sequence, gameState, generatedAt = sequence * 10) {
  return {
    schema: "genericclient_fleet.v1",
    sequence,
    generated_at_epoch_millis: generatedAt,
    summary: { total_instances: 1 },
    instances: [{ instance_id: "one", game_state: gameState }],
    rejected: [],
  };
}

async function waitFor(predicate) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
  throw new Error("condition was not reached");
}
