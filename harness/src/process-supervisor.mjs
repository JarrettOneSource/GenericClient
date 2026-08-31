import { spawn } from "node:child_process";
import { closeSync, existsSync, mkdirSync, openSync } from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";

const INSTANCE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const HEAP_SIZE = /^\d+[mMgG]$/;

export class ProcessSupervisor {
  constructor(
    {
      runtimeDirectory,
      instanceDirectory,
      repositoryDirectory,
      harnessDirectory,
      environment = process.env,
    },
    {
      spawnImpl = spawn,
      killImpl = process.kill,
      existsImpl = existsSync,
      sleepImpl = sleep,
    } = {},
  ) {
    this.runtimeDirectory = path.resolve(runtimeDirectory);
    this.instanceDirectory = path.resolve(instanceDirectory);
    this.repositoryDirectory = path.resolve(repositoryDirectory);
    this.harnessDirectory = path.resolve(harnessDirectory);
    this.environment = environment;
    this.spawnImpl = spawnImpl;
    this.killImpl = killImpl;
    this.existsImpl = existsImpl;
    this.sleepImpl = sleepImpl;
    this.launchSpecs = new Map();
  }

  start(spec = {}) {
    const normalized = this.normalizeSpec(spec);
    const logDirectory = path.join(this.runtimeDirectory, "logs");
    mkdirSync(logDirectory, { recursive: true });
    mkdirSync(this.instanceDirectory, { recursive: true });
    const logPath = path.join(logDirectory, `${normalized.instance_id}.log`);
    const logFd = openSync(logPath, "a", 0o600);
    const bootstrap = path.join(this.harnessDirectory, "bin", "genericclient-bootstrap");
    const env = {
      ...this.environment,
      GENERICCLIENT_INSTANCE_ID: normalized.instance_id,
      GENERICCLIENT_RUNTIME_DIR: this.runtimeDirectory,
      GENERICCLIENT_INSTANCE_DIRECTORY: this.instanceDirectory,
      GENERICCLIENT_JAR: normalized.jar,
      GENERICCLIENT_HEAP_SIZE: normalized.heap,
    };
    if (normalized.archive) {
      env.GENERICCLIENT_SHARED_ARCHIVE = normalized.archive;
    }
    if (normalized.archive_output) {
      env.GENERICCLIENT_ARCHIVE_AT_EXIT = normalized.archive_output;
    }
    if (normalized.runelite_profile) {
      env.GENERICCLIENT_RUNELITE_PROFILE = normalized.runelite_profile;
    }

    const executable = env.DISPLAY ? bootstrap : "xvfb-run";
    const args = env.DISPLAY ? [] : ["-a", bootstrap];
    let child;
    try {
      child = this.spawnImpl(executable, args, {
        detached: true,
        env,
        stdio: ["ignore", logFd, logFd],
      });
    } finally {
      closeSync(logFd);
    }
    if (!Number.isSafeInteger(child?.pid) || child.pid <= 0) {
      throw new Error("Dense client supervisor did not start");
    }
    child.unref?.();
    this.launchSpecs.set(normalized.instance_id, normalized);
    return {
      instance_id: normalized.instance_id,
      mode: "dense-x11",
      supervisor_pid: child.pid,
      descriptor_directory: this.instanceDirectory,
      log_path: logPath,
      jar: normalized.jar,
      heap: normalized.heap,
      archive: normalized.archive,
    };
  }

  async stop(instance, { timeoutMs = 10_000 } = {}) {
    if (!instance || !INSTANCE_ID.test(instance.instance_id || "")) {
      throw new Error("A valid instance descriptor is required");
    }
    if (!Number.isSafeInteger(instance.pid) || instance.pid <= 0) {
      throw new Error("Instance PID is invalid");
    }
    this.killImpl(instance.pid, "SIGTERM");
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try {
        this.killImpl(instance.pid, 0);
        await this.sleepImpl(100);
      } catch (error) {
        if (error?.code === "ESRCH") {
          return { instance_id: instance.instance_id, pid: instance.pid, stopped: true };
        }
        throw error;
      }
    }
    throw new Error(`PID ${instance.pid} did not stop within ${timeoutMs}ms`);
  }

  specFor(instanceId) {
    const value = this.launchSpecs.get(instanceId);
    return value ? { ...value } : null;
  }

  normalizeSpec(spec) {
    const instanceId = spec.instance_id || spec.instance || randomUUID();
    if (!INSTANCE_ID.test(instanceId)) {
      throw new Error("instance_id must use 1-128 safe identifier characters");
    }
    const heap = spec.heap || "512m";
    if (!HEAP_SIZE.test(heap)) {
      throw new Error("heap must be a size such as 512m or 1g");
    }
    const jar = path.resolve(
      spec.jar || path.join(this.repositoryDirectory, "build/libs/GenericClient.jar"),
    );
    if (!this.existsImpl(jar)) {
      throw new Error(`GenericClient JAR is unavailable: ${jar}`);
    }
    const archive = optionalExistingPath(spec.archive, "AppCDS archive", this.existsImpl);
    const archiveOutput = spec.archive_output
      ? path.resolve(spec.archive_output)
      : null;
    const runeliteProfile = optionalString(spec.runelite_profile);
    return {
      instance_id: instanceId,
      heap: heap.toLowerCase(),
      jar,
      archive,
      archive_output: archiveOutput,
      runelite_profile: runeliteProfile,
    };
  }
}

function optionalExistingPath(value, label, exists) {
  if (!value) {
    return null;
  }
  const resolved = path.resolve(value);
  if (!exists(resolved)) {
    throw new Error(`${label} is unavailable: ${resolved}`);
  }
  return resolved;
}

function optionalString(value) {
  if (value == null) {
    return null;
  }
  const trimmed = String(value).trim();
  return trimmed || null;
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
