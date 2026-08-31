import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

export const INSTANCE_SCHEMA = "genericclient_instance.v1";

export class InstanceRegistry {
  constructor(
    directory,
    {
      fetchImpl = globalThis.fetch,
      pidExists = defaultPidExists,
      healthTimeoutMs = 2_000,
    } = {},
  ) {
    this.directory = directory;
    this.fetchImpl = fetchImpl;
    this.pidExists = pidExists;
    this.healthTimeoutMs = healthTimeoutMs;
  }

  async scan() {
    let entries;
    try {
      entries = await readdir(this.directory, { withFileTypes: true });
    } catch (error) {
      if (error?.code === "ENOENT") {
        return { instances: [], rejected: [] };
      }
      throw error;
    }

    const candidates = entries
      .filter((entry) => entry.isFile() && entry.name.endsWith(".json"))
      .map((entry) => path.join(this.directory, entry.name))
      .sort();
    const checked = await Promise.all(candidates.map((file) => this.#validateFile(file)));
    return {
      instances: checked
        .filter((entry) => entry.instance)
        .map((entry) => entry.instance)
        .sort((left, right) => left.instance_id.localeCompare(right.instance_id)),
      rejected: checked
        .filter((entry) => entry.rejected)
        .map((entry) => entry.rejected),
    };
  }

  async resolve(instanceId) {
    const { instances } = await this.scan();
    if (instanceId) {
      const selected = instances.find((instance) => instance.instance_id === instanceId);
      if (!selected) {
        throw new Error(`No healthy GenericClient instance '${instanceId}'`);
      }
      return selected;
    }
    if (instances.length === 0) {
      throw new Error("No healthy GenericClient instances");
    }
    if (instances.length > 1) {
      throw new Error("instance_id is required when more than one GenericClient exists");
    }
    return instances[0];
  }

  async #validateFile(file) {
    let descriptor;
    try {
      descriptor = JSON.parse(await readFile(file, "utf8"));
      validateDescriptor(descriptor);
    } catch (error) {
      return { rejected: rejection(file, "invalid_descriptor", error.message) };
    }

    if (!(await this.pidExists(descriptor.pid))) {
      return { rejected: rejection(file, "stale_pid", `PID ${descriptor.pid} is not alive`) };
    }

    let health;
    try {
      const response = await this.fetchImpl(`${stripSlash(descriptor.control_url)}/health`, {
        signal: AbortSignal.timeout(this.healthTimeoutMs),
      });
      if (!response.ok) {
        throw new Error(`health returned HTTP ${response.status}`);
      }
      health = await response.json();
    } catch (error) {
      return { rejected: rejection(file, "unhealthy_endpoint", error.message) };
    }

    const mismatch = healthMismatch(descriptor, health);
    if (mismatch) {
      return { rejected: rejection(file, "identity_mismatch", mismatch) };
    }
    return {
      instance: {
        ...descriptor,
        descriptor_path: file,
        health,
      },
    };
  }
}

export function validateDescriptor(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("descriptor must be an object");
  }
  if (value.schema !== INSTANCE_SCHEMA) {
    throw new Error(`unsupported schema '${value.schema}'`);
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value.instance_id || "")) {
    throw new Error("instance_id is invalid");
  }
  if (!Number.isSafeInteger(value.pid) || value.pid <= 0) {
    throw new Error("pid must be a positive integer");
  }
  if (!Number.isSafeInteger(value.started_epoch_millis) || value.started_epoch_millis <= 0) {
    throw new Error("started_epoch_millis must be a positive integer");
  }
  if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(value.control_url || "")) {
    throw new Error("control_url must be an IPv4 loopback endpoint");
  }
  const port = Number(new URL(value.control_url).port);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error("control_url port is invalid");
  }
  if (typeof value.lifecycle !== "string" || value.lifecycle.length === 0) {
    throw new Error("lifecycle is required");
  }
  if (typeof value.dense !== "boolean") {
    throw new Error("dense must be boolean");
  }
  return value;
}

function healthMismatch(descriptor, health) {
  if (!health?.ok) {
    return "health did not report ok";
  }
  if (health.schema !== INSTANCE_SCHEMA) {
    return "health schema differs from descriptor";
  }
  if (health.instance_id !== descriptor.instance_id) {
    return "health instance_id differs from descriptor";
  }
  if (health.pid !== descriptor.pid) {
    return "health pid differs from descriptor";
  }
  if (stripSlash(health.control_url) !== stripSlash(descriptor.control_url)) {
    return "health control_url differs from descriptor";
  }
  return null;
}

function rejection(file, reason, detail) {
  return { descriptor_path: file, reason, detail };
}

function stripSlash(value) {
  return typeof value === "string" ? value.replace(/\/$/, "") : value;
}

async function defaultPidExists(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error?.code === "EPERM") {
      return true;
    }
    if (error?.code === "ESRCH") {
      return false;
    }
    throw error;
  }
}
