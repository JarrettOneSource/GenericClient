import assert from "node:assert/strict";
import test from "node:test";

import { FleetController } from "../src/fleet-controller.mjs";

test("normalizes mixed fleets and aggregates health resources and activity", async () => {
  const descriptors = [
    descriptor("alpha", 101, true, "Alpha"),
    descriptor("beta", 202, false, "Beta"),
    descriptor("degraded", 303, true, null),
  ];
  const statuses = new Map([
    [101, status({
      gameState: "LOGGED_IN",
      player: { name: "Alpha", world: { x: 3200, y: 3201, plane: 0 } },
      activeScript: "quest-one",
    })],
    [202, status({
      gameState: "LOGIN_SCREEN",
      behaviorState: "long_break",
      attention: true,
    })],
  ]);
  const forgotten = [];
  const controller = new FleetController({
    registry: {
      scan: async () => ({
        instances: descriptors,
        rejected: [{ reason: "stale_pid", descriptor_path: "/tmp/stale.json" }],
      }),
    },
    supervisor: {},
    callInstance: async (instance) => {
      if (!statuses.has(instance.pid)) {
        throw new Error("status connection closed");
      }
      return statuses.get(instance.pid);
    },
    readProcessMemory: async (pid) => memory(pid),
    metricsSampler: {
      sample: async (pid) => ({ cpu_percent: pid / 10 }),
      forgetMissing: (pids) => forgotten.push(pids),
    },
    now: () => 1_788_200_000_000,
  });

  const fleet = await controller.snapshot();
  assert.equal(fleet.schema, "genericclient_fleet.v1");
  assert.equal(fleet.sequence, 1);
  assert.deepEqual(forgotten, [[101, 202, 303]]);
  assert.deepEqual(
    fleet.instances.map((instance) => instance.mode),
    ["dense-x11", "stock", "dense-x11"],
  );
  const alpha = fleet.instances[0];
  assert.equal(alpha.display_name, "Alpha");
  assert.equal(alpha.logged_in, true);
  assert.equal(alpha.active_script, "quest-one");
  assert.equal(alpha.scripting, true);
  assert.equal(alpha.controls.can_logout, true);
  assert.equal("control_url" in alpha, false);
  const beta = fleet.instances[1];
  assert.equal(beta.attention_required, true);
  assert.equal(beta.breaking, true);
  assert.equal(beta.health, "attention");
  const degraded = fleet.instances[2];
  assert.equal(degraded.health, "degraded");
  assert.match(degraded.warnings[0].message, /connection closed/);

  assert.deepEqual(fleet.summary, {
    healthy: 1,
    degraded: 1,
    starting: 0,
    attention_required: 1,
    logged_in: 1,
    breaking: 1,
    scripting: 1,
    total_instances: 3,
    rejected: 1,
    cpu_percent: 60.6,
    memory: {
      rss_bytes: 606_000,
      pss_bytes: 303_000,
      uss_bytes: 242_400,
      swap_bytes: 0,
    },
  });
});

test("gets one normalized instance and rejects missing identity", async () => {
  const only = descriptor("only", 404, true, null);
  const controller = new FleetController({
    registry: { scan: async () => ({ instances: [only], rejected: [] }) },
    supervisor: {},
    callInstance: async () => status({ gameState: "LOGIN_SCREEN" }),
    readProcessMemory: async () => memory(404),
    metricsSampler: { sample: async () => ({ cpu_percent: null }), forgetMissing() {} },
    now: () => 1_788_200_000_000,
  });

  assert.equal((await controller.get("only")).instance_id, "only");
  await assert.rejects(() => controller.get(), /instance_id is required/);
  await assert.rejects(() => controller.get("missing"), /No healthy/);
});

test("starts stops and refreshes through explicit lifecycle methods", async () => {
  const running = descriptor("running", 505, true, null);
  const calls = [];
  const controller = new FleetController({
    registry: {
      scan: async () => ({ instances: [running], rejected: [] }),
      resolve: async (instanceId) => {
        assert.equal(instanceId, "running");
        return running;
      },
    },
    supervisor: {
      start: (spec) => {
        calls.push({ action: "start", spec });
        return { instance_id: spec.instance_id, supervisor_pid: 88 };
      },
      stop: async (instance) => {
        calls.push({ action: "stop", instance });
        return { instance_id: instance.instance_id, stopped: true };
      },
    },
    callInstance: async () => status({ gameState: "LOGIN_SCREEN" }),
    readProcessMemory: async () => memory(505),
    metricsSampler: { sample: async () => ({ cpu_percent: 1 }), forgetMissing() {} },
    now: () => 1_788_200_000_000,
  });

  await assert.rejects(() => controller.start({ instance_id: "running" }), /already running/);
  const started = await controller.start({ instance_id: "new-one", heap: "384m" });
  assert.equal(started.instance_id, "new-one");
  assert.equal((await controller.refresh("running")).instance_id, "running");
  assert.deepEqual(await controller.stop("running"), { instance_id: "running", stopped: true });
  assert.deepEqual(calls.map((call) => call.action), ["start", "stop"]);
  await assert.rejects(() => controller.stop(), /instance_id is required/);
  await assert.rejects(() => controller.start({ heap: "384m" }), /instance_id is required/);
});

test("reserves launch identity until registration and rejects overlapping starts", async () => {
  let releaseScan;
  const firstScan = new Promise((resolve) => {
    releaseScan = resolve;
  });
  let scans = 0;
  const starts = [];
  let now = 1_000;
  const controller = new FleetController({
    registry: {
      scan: async () => {
        scans++;
        if (scans === 1) {
          return firstScan;
        }
        return { instances: [], rejected: [] };
      },
    },
    supervisor: {
      start: (spec) => {
        starts.push(spec);
        return { instance_id: spec.instance_id };
      },
    },
    metricsSampler: { forgetMissing() {} },
    now: () => now,
    launchReservationMs: 100,
  });

  const first = controller.start({ instance_id: "race-safe", heap: "384m" });
  await assert.rejects(
    () => controller.start({ instance_id: "race-safe", heap: "384m" }),
    /launch is already pending/,
  );
  releaseScan({ instances: [], rejected: [] });
  assert.equal((await first).instance_id, "race-safe");
  assert.equal(starts.length, 1);

  const pending = await controller.snapshot();
  assert.deepEqual(pending.pending_launches, ["race-safe"]);
  assert.equal(pending.summary.starting, 1);
  now = 1_101;
  const expired = await controller.snapshot();
  assert.deepEqual(expired.pending_launches, []);
  assert.equal(expired.summary.starting, 0);
});

test("routes only allowlisted commands to one explicit instance", async () => {
  const selected = descriptor("command-target", 606, true, null);
  const calls = [];
  const controller = new FleetController({
    registry: {
      resolve: async (instanceId) => {
        assert.equal(instanceId, "command-target");
        return selected;
      },
    },
    supervisor: {},
    metricsSampler: null,
    callInstance: async (instance, method, params) => {
      calls.push({ instance, method, params });
      return { accepted: true };
    },
  });

  const run = await controller.command("command-target", {
    command: "scripts.run",
    params: { id: "quest-one", inputs: { route: "safe" } },
  });
  assert.equal(run.command, "scripts.run");
  assert.deepEqual(calls[0], {
    instance: selected,
    method: "scripts.run",
    params: { id: "quest-one", inputs: { route: "safe" } },
  });

  await controller.command("command-target", {
    command: "random_event.complete",
    params: {},
  });
  assert.deepEqual(calls[1].params, {
    reason: "completed_via_dashboard",
    resume_interrupted: true,
  });
  await controller.command("command-target", { command: "session.logout" });
  assert.deepEqual(calls[2].params, {});

  await assert.rejects(
    () => controller.command("command-target", { command: "lua.eval", params: {} }),
    /not allowed/,
  );
  await assert.rejects(
    () => controller.command("command-target", {
      command: "scripts.run",
      params: { id: "" },
    }),
    /id is required/,
  );
  await assert.rejects(() => controller.command(null, { command: "scripts.stop" }), /instance_id/);
});

function descriptor(instanceId, pid, dense, displayName) {
  return {
    instance_id: instanceId,
    pid,
    dense,
    lifecycle: "login_screen",
    started_epoch_millis: 1_788_100_000_000 + pid,
    launcher_display_name: displayName,
    runelite_profile: null,
    account_profile_id: null,
    control_url: `http://127.0.0.1:${40_000 + pid}`,
    health: { game_state: "LOGIN_SCREEN" },
  };
}

function status({
  gameState,
  player = null,
  activeScript = null,
  behaviorState = "ready",
  attention = false,
}) {
  return {
    game_state: gameState,
    last_status: `GAME_STATE_CHANGED state=${gameState}`,
    player,
    runtime: { game_tick: 12 },
    recent_messages: [],
    lua: {
      active_script: activeScript || "none",
      activity: activeScript ? "running" : "idle",
      script_state: activeScript ? "running" : "idle",
      scripts: [{ id: "quest-one", name: "Quest one" }],
    },
    behavior: { available: true, state: behaviorState },
    automation: { available: true, mode: "idle" },
    safety: { armed: false },
    random_event: { attention_required: attention },
  };
}

function memory(pid) {
  return {
    pid,
    rss_bytes: pid * 1_000,
    pss_bytes: pid * 500,
    uss_bytes: pid * 400,
    swap_bytes: 0,
    pss_anon_bytes: pid * 300,
    pss_file_bytes: pid * 200,
    pss_shmem_bytes: 0,
  };
}
