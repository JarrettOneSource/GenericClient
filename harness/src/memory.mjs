import { readFile } from "node:fs/promises";

const FIELDS = new Map([
  ["Rss", "rss_bytes"],
  ["Pss", "pss_bytes"],
  ["Pss_Anon", "pss_anon_bytes"],
  ["Pss_File", "pss_file_bytes"],
  ["Pss_Shmem", "pss_shmem_bytes"],
  ["Private_Clean", "private_clean_bytes"],
  ["Private_Dirty", "private_dirty_bytes"],
  ["Swap", "swap_bytes"],
]);

export function parseSmapsRollup(text) {
  const result = Object.fromEntries([...FIELDS.values()].map((name) => [name, 0]));
  const seen = new Set();
  for (const line of text.split(/\r?\n/)) {
    const match = /^([A-Za-z_]+):\s+(\d+)\s+kB$/.exec(line.trim());
    if (!match || !FIELDS.has(match[1])) {
      continue;
    }
    const field = FIELDS.get(match[1]);
    result[field] = Number(match[2]) * 1_024;
    seen.add(field);
  }
  for (const required of ["rss_bytes", "pss_bytes", "private_clean_bytes", "private_dirty_bytes"]) {
    if (!seen.has(required)) {
      throw new Error(`smaps_rollup is missing ${required}`);
    }
  }
  result.uss_bytes = result.private_clean_bytes + result.private_dirty_bytes;
  return result;
}

export async function readProcessMemory(pid, read = readFile) {
  if (!Number.isSafeInteger(pid) || pid <= 0) {
    throw new Error("pid must be a positive integer");
  }
  const text = await read(`/proc/${pid}/smaps_rollup`, "utf8");
  return { pid, ...parseSmapsRollup(text) };
}

export function summarizeFleet(samples) {
  const fields = [
    "rss_bytes",
    "pss_bytes",
    "pss_anon_bytes",
    "pss_file_bytes",
    "pss_shmem_bytes",
    "private_clean_bytes",
    "private_dirty_bytes",
    "uss_bytes",
    "swap_bytes",
  ];
  const totals = Object.fromEntries(fields.map((field) => [field, 0]));
  for (const sample of samples) {
    for (const field of fields) {
      totals[field] += sample[field] || 0;
    }
  }
  return {
    process_count: samples.length,
    totals,
    processes: [...samples].sort((left, right) => left.pid - right.pid),
  };
}

export function compareFleets(baseline, expanded) {
  if (!baseline?.totals || !expanded?.totals) {
    throw new Error("baseline and expanded fleet summaries are required");
  }
  return {
    baseline_process_count: baseline.process_count,
    expanded_process_count: expanded.process_count,
    added_processes: expanded.process_count - baseline.process_count,
    marginal_pss_bytes: expanded.totals.pss_bytes - baseline.totals.pss_bytes,
    marginal_uss_bytes: expanded.totals.uss_bytes - baseline.totals.uss_bytes,
    marginal_rss_bytes: expanded.totals.rss_bytes - baseline.totals.rss_bytes,
  };
}
