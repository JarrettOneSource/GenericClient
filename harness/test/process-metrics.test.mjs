import assert from "node:assert/strict";
import test from "node:test";

import { parseProcStat, ProcessMetricsSampler } from "../src/process-metrics.mjs";

test("parses command names with spaces and Linux CPU fields", () => {
  const stat = parseProcStat(record({ pid: 321, command: "java dense client", user: 100, system: 25 }));

  assert.equal(stat.pid, 321);
  assert.equal(stat.command, "java dense client");
  assert.equal(stat.state, "S");
  assert.equal(stat.parent_pid, 1);
  assert.equal(stat.user_ticks, 100);
  assert.equal(stat.system_ticks, 25);
  assert.equal(stat.cpu_ticks, 125);
  assert.equal(stat.started_ticks, 123456);
});

test("samples CPU percentage from tick and monotonic-time deltas", async () => {
  const records = [
    record({ user: 100, system: 25 }),
    record({ user: 140, system: 35 }),
  ];
  const times = [1_000_000_000n, 2_000_000_000n];
  const sampler = new ProcessMetricsSampler({
    read: async () => records.shift(),
    now: () => times.shift(),
    clockTicks: 100,
  });

  assert.equal((await sampler.sample(321)).cpu_percent, null);
  assert.equal((await sampler.sample(321)).cpu_percent, 50);
});

test("resets sampling when a PID start time changes and forgets missing processes", async () => {
  const records = [
    record({ started: 10, user: 20 }),
    record({ started: 11, user: 500 }),
  ];
  let time = 0n;
  const sampler = new ProcessMetricsSampler({
    read: async () => records.shift(),
    now: () => (time += 1_000_000_000n),
    clockTicks: 100,
  });

  await sampler.sample(321);
  assert.equal((await sampler.sample(321)).cpu_percent, null);
  sampler.forgetMissing([]);
  assert.equal(sampler.previous.size, 0);
});

test("rejects malformed stat records", () => {
  assert.throws(() => parseProcStat("not a stat record"), /Invalid/);
  assert.throws(() => parseProcStat("1 (java) S 2"), /Incomplete/);
});

function record({
  pid = 321,
  command = "java",
  user = 100,
  system = 25,
  started = 123456,
} = {}) {
  return `${pid} (${command}) S 1 2 3 0 -1 4194560 10 0 2 0 ${user} ${system} 0 0 20 0 86 0 ${started} 999 888 777\n`;
}
