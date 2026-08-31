import { randomUUID } from "node:crypto";

export const JAGEX_HANDOFF_SCHEMA = "genericclient_jagex_handoff.v1";

const INSTANCE_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const HANDOFF_ENVIRONMENT = new Set([
  "JX_SESSION_ID",
  "JX_CHARACTER_ID",
  "JX_DISPLAY_NAME",
  "JX_ACCESS_TOKEN",
  "JX_REFRESH_TOKEN",
  "DISPLAY",
  "WAYLAND_DISPLAY",
  "XAUTHORITY",
  "DBUS_SESSION_BUS_ADDRESS",
]);

export class LauncherBroker {
  constructor(
    {
      registry,
      supervisor,
      socketPath,
      defaultMode = "stock",
      requestTtlMs = 5 * 60_000,
      now = () => Date.now(),
      createId = randomUUID,
    },
  ) {
    if (!registry || !supervisor) {
      throw new Error("LauncherBroker requires registry and supervisor");
    }
    if (!["stock", "dense"].includes(defaultMode)) {
      throw new Error("defaultMode must be stock or dense");
    }
    if (!Number.isSafeInteger(requestTtlMs) || requestTtlMs <= 0) {
      throw new Error("requestTtlMs must be a positive integer");
    }
    this.registry = registry;
    this.supervisor = supervisor;
    this.socketPath = socketPath;
    this.defaultMode = defaultMode;
    this.requestTtlMs = requestTtlMs;
    this.now = now;
    this.createId = createId;
    this.pending = new Map();
    this.starting = new Map();
  }

  async arm(spec = {}) {
    this.#expire();
    const instanceId = requiredInstanceId(spec.instance_id);
    if (this.pending.has(instanceId) || this.starting.has(instanceId)) {
      throw new Error(`Jagex launch request '${instanceId}' is already armed`);
    }
    const { instances } = await this.registry.scan();
    if (instances.some((instance) => instance.instance_id === instanceId)) {
      throw new Error(`GenericClient instance '${instanceId}' is already running`);
    }
    const launchMode = optionalMode(spec.launch_mode, this.defaultMode);
    const request = {
      request_id: this.createId(),
      instance_id: instanceId,
      expected_display_name: optionalString(spec.expected_display_name),
      runelite_profile: optionalString(spec.runelite_profile),
      launch_mode: launchMode,
      created_at_epoch_millis: this.now(),
      expires_at_epoch_millis: this.now() + this.requestTtlMs,
      state: "awaiting_jagex_play",
    };
    this.pending.set(instanceId, request);
    return publicRequest(request);
  }

  cancel(instanceId) {
    if (!this.pending.delete(instanceId)) {
      throw new Error(`No pending Jagex launch request '${instanceId}'`);
    }
    return { instance_id: instanceId, cancelled: true };
  }

  async accept(rawHandoff) {
    const handoff = validateHandoff(rawHandoff);
    this.#expire();
    const displayName = optionalString(handoff.environment.JX_DISPLAY_NAME);
    const requests = [...this.pending.values()];
    const request = requests.find((candidate) =>
      candidate.expected_display_name === displayName) ||
      requests.find((candidate) => !candidate.expected_display_name);
    const instanceId = request?.instance_id || `jagex-${this.createId()}`;
    const { instances } = await this.registry.scan();
    if (instances.some((instance) => instance.instance_id === instanceId)) {
      throw new Error(`GenericClient instance '${instanceId}' is already running`);
    }
    const receipt = this.supervisor.startFromLauncher({
      spec: {
        instance_id: instanceId,
        launch_mode: request?.launch_mode || this.defaultMode,
        runelite_profile: request?.runelite_profile || null,
      },
      environment: handoff.environment,
      arguments: handoff.arguments,
    });
    if (request) {
      this.pending.delete(request.instance_id);
    }
    this.starting.set(instanceId, {
      instance_id: instanceId,
      launcher_display_name: displayName,
      launch_mode: receipt.mode,
      started_at_epoch_millis: this.now(),
      expires_at_epoch_millis: this.now() + this.requestTtlMs,
      state: "starting",
    });
    return {
      ...receipt,
      request_id: request?.request_id || null,
      launcher_display_name: displayName,
    };
  }

  status() {
    this.#expire();
    return {
      available: true,
      transport: "unix",
      socket_path: this.socketPath,
      default_mode: this.defaultMode === "dense" ? "dense-x11" : "stock",
      pending: [...this.pending.values()].map(publicRequest),
      starting: [...this.starting.values()].map(publicRequest),
    };
  }

  reconcile(instances) {
    const active = new Set(instances.map((instance) => instance.instance_id));
    for (const instanceId of this.starting.keys()) {
      if (active.has(instanceId)) {
        this.starting.delete(instanceId);
      }
    }
    this.#expire();
  }

  #expire() {
    const now = this.now();
    for (const [instanceId, request] of this.pending) {
      if (request.expires_at_epoch_millis <= now) {
        this.pending.delete(instanceId);
      }
    }
    for (const [instanceId, launch] of this.starting) {
      if (launch.expires_at_epoch_millis <= now) {
        this.starting.delete(instanceId);
      }
    }
  }
}

function validateHandoff(value) {
  if (!value || typeof value !== "object" || Array.isArray(value) ||
      value.schema !== JAGEX_HANDOFF_SCHEMA) {
    throw new Error("Unsupported Jagex launcher handoff");
  }
  if (!Array.isArray(value.arguments) || value.arguments.length > 100 ||
      value.arguments.some((argument) =>
        typeof argument !== "string" || argument.length > 4_096)) {
    throw new Error("Jagex launcher arguments are invalid");
  }
  if (!value.environment || typeof value.environment !== "object" ||
      Array.isArray(value.environment)) {
    throw new Error("Jagex launcher environment is invalid");
  }
  const environment = {};
  for (const [key, content] of Object.entries(value.environment)) {
    if (!HANDOFF_ENVIRONMENT.has(key) || typeof content !== "string" ||
        content.length > 65_536) {
      throw new Error("Jagex launcher environment is invalid");
    }
    if (content) {
      environment[key] = content;
    }
  }
  requiredString(environment.JX_SESSION_ID, "JX_SESSION_ID");
  requiredString(environment.JX_CHARACTER_ID, "JX_CHARACTER_ID");
  return { arguments: [...value.arguments], environment };
}

function requiredInstanceId(value) {
  if (typeof value !== "string" || !INSTANCE_ID.test(value)) {
    throw new Error("instance_id must use 1-128 safe identifier characters");
  }
  return value;
}

function requiredString(value, name) {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`Jagex launcher handoff requires ${name}`);
  }
  return value;
}

function optionalString(value) {
  if (value == null) {
    return null;
  }
  if (typeof value !== "string") {
    throw new Error("launcher string parameter is invalid");
  }
  return value.trim() || null;
}

function optionalMode(value, defaultMode) {
  const mode = value || defaultMode;
  if (!["stock", "dense"].includes(mode)) {
    throw new Error("launch_mode must be stock or dense");
  }
  return mode;
}

function publicRequest(request) {
  return { ...request };
}
