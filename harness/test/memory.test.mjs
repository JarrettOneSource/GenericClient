import assert from "node:assert/strict";
import test from "node:test";

import {
  compareFleets,
  parseSmapsRollup,
  readProcessMemory,
  summarizeFleet,
} from "../src/memory.mjs";

const SAMPLE = `00400000-7fffffffffff ---p 00000000 00:00 0 [rollup]
Rss:              120000 kB
Pss:               80000 kB
Pss_Dirty:         60000 kB
Pss_Anon:          65000 kB
Pss_File:          14000 kB
Pss_Shmem:          1000 kB
Shared_Clean:      30000 kB
Shared_Dirty:          0 kB
Private_Clean:      5000 kB
Private_Dirty:     55000 kB
Swap:               2000 kB
`;

test("parses Linux smaps_rollup into byte metrics", () => {
  const memory = parseSmapsRollup(SAMPLE);
  assert.equal(memory.rss_bytes, 120_000 * 1_024);
  assert.equal(memory.pss_bytes, 80_000 * 1_024);
  assert.equal(memory.pss_anon_bytes, 65_000 * 1_024);
  assert.equal(memory.pss_file_bytes, 14_000 * 1_024);
  assert.equal(memory.pss_shmem_bytes, 1_000 * 1_024);
  assert.equal(memory.uss_bytes, 60_000 * 1_024);
  assert.equal(memory.swap_bytes, 2_000 * 1_024);
});

test("reads a process rollup from the expected proc path", async () => {
  let requested;
  const memory = await readProcessMemory(321, async (file, encoding) => {
    requested = { file, encoding };
    return SAMPLE;
  });
  assert.deepEqual(requested, { file: "/proc/321/smaps_rollup", encoding: "utf8" });
  assert.equal(memory.pid, 321);
});

test("aggregates fleet memory and reports marginal cost", () => {
  const one = { pid: 1, ...parseSmapsRollup(SAMPLE) };
  const two = {
    pid: 2,
    ...parseSmapsRollup(
      SAMPLE.replace("120000 kB", "100000 kB").replace("80000 kB", "70000 kB"),
    ),
  };
  const baseline = summarizeFleet([one]);
  const expanded = summarizeFleet([one, two]);
  const comparison = compareFleets(baseline, expanded);

  assert.equal(expanded.process_count, 2);
  assert.equal(expanded.totals.pss_bytes, 150_000 * 1_024);
  assert.equal(comparison.added_processes, 1);
  assert.equal(comparison.marginal_pss_bytes, 70_000 * 1_024);
  assert.equal(comparison.marginal_uss_bytes, 60_000 * 1_024);
});

test("rejects incomplete rollup input", () => {
  assert.throws(() => parseSmapsRollup("Rss: 10 kB\n"), /missing pss_bytes/);
});
