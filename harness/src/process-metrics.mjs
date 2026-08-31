import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";

export function parseProcStat(text) {
  const match = /^(\d+) \((.*)\) (.+)$/.exec(text.trim());
  if (!match) {
    throw new Error("Invalid /proc PID stat record");
  }
  const fields = match[3].split(/\s+/);
  if (fields.length < 20) {
    throw new Error("Incomplete /proc PID stat record");
  }
  const utimeTicks = integer(fields[11], "utime");
  const stimeTicks = integer(fields[12], "stime");
  return {
    pid: integer(match[1], "pid"),
    command: match[2],
    state: fields[0],
    parent_pid: integer(fields[1], "ppid"),
    cpu_ticks: utimeTicks + stimeTicks,
    user_ticks: utimeTicks,
    system_ticks: stimeTicks,
    started_ticks: integer(fields[19], "starttime"),
  };
}

export class ProcessMetricsSampler {
  constructor(
    {
      read = readFile,
      now = () => process.hrtime.bigint(),
      clockTicks = systemClockTicks(),
    } = {},
  ) {
    this.read = read;
    this.now = now;
    this.clockTicks = clockTicks;
    this.previous = new Map();
  }

  async sample(pid) {
    if (!Number.isSafeInteger(pid) || pid <= 0) {
      throw new Error("pid must be a positive integer");
    }
    const stat = parseProcStat(await this.read(`/proc/${pid}/stat`, "utf8"));
    const sampledNanos = this.now();
    const previous = this.previous.get(pid);
    let cpuPercent = null;
    if (previous && previous.started_ticks === stat.started_ticks) {
      const elapsedSeconds = Number(sampledNanos - previous.sampled_nanos) / 1_000_000_000;
      const usedSeconds = (stat.cpu_ticks - previous.cpu_ticks) / this.clockTicks;
      if (elapsedSeconds > 0 && usedSeconds >= 0) {
        cpuPercent = (usedSeconds / elapsedSeconds) * 100;
      }
    }
    this.previous.set(pid, {
      cpu_ticks: stat.cpu_ticks,
      started_ticks: stat.started_ticks,
      sampled_nanos: sampledNanos,
    });
    return {
      ...stat,
      cpu_percent: cpuPercent,
      sampled_nanos: sampledNanos.toString(),
    };
  }

  forgetMissing(activePids) {
    const active = new Set(activePids);
    for (const pid of this.previous.keys()) {
      if (!active.has(pid)) {
        this.previous.delete(pid);
      }
    }
  }
}

export function systemClockTicks() {
  const value = Number(execFileSync("getconf", ["CLK_TCK"], { encoding: "utf8" }).trim());
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error("Unable to determine Linux CLK_TCK");
  }
  return value;
}

function integer(value, name) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed)) {
    throw new Error(`Invalid ${name} in /proc PID stat record`);
  }
  return parsed;
}
