import { callInstance as defaultCallInstance } from "./client.mjs";
import { readProcessMemory as defaultReadProcessMemory } from "./memory.mjs";

export const DASHBOARD_COMMANDS = Object.freeze([
  "session.login",
  "session.logout",
  "behavior.break.end",
  "scripts.run",
  "scripts.stop",
  "scripts.action",
  "random_event.acknowledge",
  "random_event.complete",
  "automation.pause",
  "automation.resume",
]);

export class FleetController {
  constructor(
    {
      registry,
      supervisor,
      metricsSampler,
      callInstance = defaultCallInstance,
      readProcessMemory = defaultReadProcessMemory,
      now = () => Date.now(),
      launchReservationMs = 120_000,
      launcherBroker = null,
    },
  ) {
    if (!Number.isSafeInteger(launchReservationMs) || launchReservationMs <= 0) {
      throw new Error("launchReservationMs must be a positive integer");
    }
    this.registry = registry;
    this.supervisor = supervisor;
    this.metricsSampler = metricsSampler;
    this.callInstance = callInstance;
    this.readProcessMemory = readProcessMemory;
    this.now = now;
    this.launchReservationMs = launchReservationMs;
    this.launcherBroker = launcherBroker;
    this.pendingLaunches = new Map();
    this.sequence = 0;
  }

  async snapshot() {
    const scanned = await this.registry.scan();
    this.#reconcileLaunches(scanned.instances);
    this.launcherBroker?.reconcile(scanned.instances);
    this.metricsSampler?.forgetMissing(scanned.instances.map((instance) => instance.pid));
    const instances = await Promise.all(
      scanned.instances.map((instance) => this.#normalizeInstance(instance)),
    );
    const launcher = this.launcherStatus();
    const snapshot = {
      schema: "genericclient_fleet.v1",
      sequence: ++this.sequence,
      generated_at_epoch_millis: this.now(),
      summary: summarize(
        instances,
        scanned.rejected,
        this.pendingLaunches.size,
        launcher.pending.length,
        launcher.starting.length,
      ),
      instances: instances.sort((left, right) => left.instance_id.localeCompare(right.instance_id)),
      pending_launches: [...this.pendingLaunches.keys()].sort(),
      launcher,
      rejected: scanned.rejected,
    };
    return snapshot;
  }

  async get(instanceId) {
    if (!instanceId) {
      throw new Error("instance_id is required");
    }
    const snapshot = await this.snapshot();
    const instance = snapshot.instances.find((candidate) => candidate.instance_id === instanceId);
    if (!instance) {
      throw new Error(`No healthy GenericClient instance '${instanceId}'`);
    }
    return instance;
  }

  async start(spec = {}) {
    const requestedId = spec.instance_id || spec.instance;
    if (!requestedId) {
      throw new Error("instance_id is required");
    }
    this.#reconcileLaunches([]);
    if (this.pendingLaunches.has(requestedId)) {
      throw new Error(`GenericClient instance '${requestedId}' launch is already pending`);
    }
    const reservation = this.now() + this.launchReservationMs;
    this.pendingLaunches.set(requestedId, reservation);
    try {
      const { instances } = await this.registry.scan();
      if (instances.some((instance) => instance.instance_id === requestedId)) {
        throw new Error(`GenericClient instance '${requestedId}' is already running`);
      }
      return this.supervisor.start({ ...spec, instance_id: requestedId });
    } catch (error) {
      if (this.pendingLaunches.get(requestedId) === reservation) {
        this.pendingLaunches.delete(requestedId);
      }
      throw error;
    }
  }

  async stop(instanceId) {
    if (!instanceId) {
      throw new Error("instance_id is required");
    }
    const descriptor = await this.registry.resolve(instanceId);
    return this.supervisor.stop(descriptor);
  }

  async armLauncher(spec) {
    if (!this.launcherBroker) {
      throw new Error("Jagex launcher handoff is unavailable");
    }
    return this.launcherBroker.arm(spec);
  }

  cancelLauncher(instanceId) {
    if (!this.launcherBroker) {
      throw new Error("Jagex launcher handoff is unavailable");
    }
    return this.launcherBroker.cancel(instanceId);
  }

  launcherStatus() {
    return this.launcherBroker?.status() || {
      available: false,
      transport: null,
      socket_path: null,
      default_mode: null,
      pending: [],
      starting: [],
    };
  }

  async refresh(instanceId) {
    return this.get(instanceId);
  }

  async command(instanceId, request) {
    if (!instanceId) {
      throw new Error("instance_id is required");
    }
    if (!request || typeof request !== "object" || Array.isArray(request)) {
      throw new Error("command request must be an object");
    }
    const command = request.command;
    if (!DASHBOARD_COMMANDS.includes(command)) {
      throw new Error(`Dashboard command '${command}' is not allowed`);
    }
    const params = validateCommand(command, request.params ?? {});
    const descriptor = await this.registry.resolve(instanceId);
    const result = await this.callInstance(descriptor, command, params);
    return { instance_id: instanceId, command, result };
  }

  async #normalizeInstance(descriptor) {
    const warnings = [];
    const [statusResult, memoryResult, cpuResult] = await Promise.allSettled([
      this.callInstance(descriptor, "status", {}, 5_000),
      this.readProcessMemory(descriptor.pid),
      this.metricsSampler?.sample(descriptor.pid) ?? Promise.resolve(null),
    ]);
    const status = settledValue(statusResult, "status", warnings);
    const memory = settledValue(memoryResult, "memory", warnings);
    const cpu = settledValue(cpuResult, "cpu", warnings);
    const player = status?.player || null;
    const lua = status?.lua || null;
    const behavior = status?.behavior || null;
    const randomEvent = status?.random_event || null;
    const activeScript = activeScriptName(lua);
    const scripting = Boolean(activeScript);
    const breaking = typeof behavior?.state === "string" && behavior.state.endsWith("_break");
    const attentionRequired = Boolean(randomEvent?.attention_required);
    const gameState = status?.game_state || descriptor.health?.game_state || null;
    const loggedIn = gameState === "LOGGED_IN";
    const degraded = !status;
    return {
      instance_id: descriptor.instance_id,
      pid: descriptor.pid,
      mode: descriptor.dense ? "dense-x11" : "stock",
      dense: descriptor.dense,
      lifecycle: descriptor.lifecycle,
      game_state: gameState,
      started_epoch_millis: descriptor.started_epoch_millis,
      uptime_millis: Math.max(0, this.now() - descriptor.started_epoch_millis),
      launcher_display_name: descriptor.launcher_display_name || null,
      runelite_profile: descriptor.runelite_profile || null,
      account_profile_id: descriptor.account_profile_id || null,
      display_name: descriptor.launcher_display_name || player?.name || descriptor.instance_id,
      world: player?.world || null,
      player,
      runtime: status?.runtime || null,
      active_script: activeScript,
      scripting,
      activity: lua?.activity || "idle",
      script_state: lua?.script_state || "idle",
      scripts: Array.isArray(lua?.scripts) ? lua.scripts : [],
      recent_logs: Array.isArray(lua?.recent_logs) ? lua.recent_logs : [],
      behavior,
      automation: status?.automation || null,
      safety: status?.safety || null,
      random_event: randomEvent,
      attention_required: attentionRequired,
      breaking,
      logged_in: loggedIn,
      recent_messages: Array.isArray(status?.recent_messages) ? status.recent_messages : [],
      last_status: status?.last_status || null,
      health: degraded ? "degraded" : attentionRequired ? "attention" : "healthy",
      warnings,
      memory: memory ? normalizeMemory(memory) : null,
      cpu_percent: roundCpu(cpu?.cpu_percent),
      controls: {
        can_stop: true,
        can_login: gameState === "LOGIN_SCREEN",
        can_logout: loggedIn,
        can_end_break: breaking,
        can_stop_script: scripting,
        can_acknowledge_random_event: attentionRequired,
      },
    };
  }

  #reconcileLaunches(instances) {
    const active = new Set(instances.map((instance) => instance.instance_id));
    const now = this.now();
    for (const [instanceId, expiresAt] of this.pendingLaunches) {
      if (active.has(instanceId) || expiresAt <= now) {
        this.pendingLaunches.delete(instanceId);
      }
    }
  }
}

function settledValue(result, label, warnings) {
  if (result.status === "fulfilled") {
    return result.value;
  }
  warnings.push({ source: label, message: result.reason?.message || String(result.reason) });
  return null;
}

function activeScriptName(lua) {
  const value = lua?.active_script;
  if (typeof value !== "string" || value === "none" || value.trim() === "") {
    return null;
  }
  return value;
}

function normalizeMemory(memory) {
  return {
    rss_bytes: memory.rss_bytes || 0,
    pss_bytes: memory.pss_bytes || 0,
    uss_bytes: memory.uss_bytes || 0,
    swap_bytes: memory.swap_bytes || 0,
    pss_anon_bytes: memory.pss_anon_bytes || 0,
    pss_file_bytes: memory.pss_file_bytes || 0,
    pss_shmem_bytes: memory.pss_shmem_bytes || 0,
  };
}

function summarize(
  instances,
  rejected,
  pendingLaunches,
  pendingJagexLaunches,
  startingJagexLaunches,
) {
  const memory = {
    rss_bytes: 0,
    pss_bytes: 0,
    uss_bytes: 0,
    swap_bytes: 0,
  };
  let cpuPercent = 0;
  let cpuSamples = 0;
  for (const instance of instances) {
    if (instance.memory) {
      for (const field of Object.keys(memory)) {
        memory[field] += instance.memory[field] || 0;
      }
    }
    if (typeof instance.cpu_percent === "number") {
      cpuPercent += instance.cpu_percent;
      cpuSamples++;
    }
  }
  return {
    healthy: instances.filter((instance) => instance.health === "healthy").length,
    degraded: instances.filter((instance) => instance.health === "degraded").length,
    starting: pendingLaunches + startingJagexLaunches + instances.filter((instance) =>
      instance.game_state === "STARTING" || instance.lifecycle === "created").length,
    awaiting_jagex_play: pendingJagexLaunches,
    attention_required: instances.filter((instance) => instance.attention_required).length,
    logged_in: instances.filter((instance) => instance.logged_in).length,
    breaking: instances.filter((instance) => instance.breaking).length,
    scripting: instances.filter((instance) => instance.scripting).length,
    total_instances: instances.length,
    rejected: rejected.length,
    cpu_percent: cpuSamples ? roundCpu(cpuPercent) : null,
    memory,
  };
}

function roundCpu(value) {
  return typeof value === "number" && Number.isFinite(value)
    ? Math.round(value * 100) / 100
    : null;
}

function validateCommand(command, value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("command params must be an object");
  }
  switch (command) {
    case "scripts.run":
      return {
        id: requiredString(value.id, "scripts.run id"),
        inputs: optionalObject(value.inputs, "scripts.run inputs"),
      };
    case "scripts.action":
      return { action: requiredString(value.action, "scripts.action action") };
    case "random_event.complete":
      return {
        reason: optionalString(value.reason) || "completed_via_dashboard",
        resume_interrupted: optionalBoolean(value.resume_interrupted, true),
      };
    default:
      return {};
  }
}

function requiredString(value, label) {
  const result = optionalString(value);
  if (!result) {
    throw new Error(`${label} is required`);
  }
  return result;
}

function optionalString(value) {
  if (value == null) {
    return null;
  }
  if (typeof value !== "string") {
    throw new Error("command string parameter is invalid");
  }
  const trimmed = value.trim();
  return trimmed || null;
}

function optionalObject(value, label) {
  if (value == null) {
    return {};
  }
  if (typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} must be an object`);
  }
  return value;
}

function optionalBoolean(value, defaultValue) {
  if (value == null) {
    return defaultValue;
  }
  if (typeof value !== "boolean") {
    throw new Error("command boolean parameter is invalid");
  }
  return value;
}
