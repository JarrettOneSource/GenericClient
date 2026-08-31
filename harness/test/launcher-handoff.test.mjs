import assert from "node:assert/strict";
import { mkdtemp, rm, stat } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { LauncherBroker, JAGEX_HANDOFF_SCHEMA } from "../src/launcher-broker.mjs";
import {
  forwardLauncherHandoff,
  HandoffUnavailableError,
} from "../src/launcher-handoff-client.mjs";
import { LauncherHandoffServer } from "../src/launcher-handoff-server.mjs";

test("matches an armed stock launch without exposing launcher credentials", async () => {
  const launches = [];
  let now = 1_000;
  const broker = new LauncherBroker({
    registry: { scan: async () => ({ instances: [], rejected: [] }) },
    supervisor: {
      startFromLauncher: (request) => {
        launches.push(request);
        return {
          instance_id: request.spec.instance_id,
          mode: request.spec.launch_mode,
          launch_source: "jagex_launcher",
        };
      },
    },
    socketPath: "/tmp/unused.sock",
    now: () => now,
    createId: () => "request-one",
  });
  const armed = await broker.arm({
    instance_id: "main-character",
    expected_display_name: "Main Character",
    runelite_profile: "main-profile",
  });
  assert.equal(armed.state, "awaiting_jagex_play");
  assert.equal(broker.status().pending.length, 1);

  const receipt = await broker.accept(handoff({
    JX_SESSION_ID: "session-secret",
    JX_CHARACTER_ID: "character-secret",
    JX_DISPLAY_NAME: "Main Character",
    DISPLAY: ":9",
  }, ["--safe-mode"]));
  assert.equal(receipt.instance_id, "main-character");
  assert.equal(receipt.request_id, "request-one");
  assert.equal(receipt.mode, "stock");
  assert.equal(launches[0].spec.runelite_profile, "main-profile");
  assert.deepEqual(launches[0].arguments, ["--safe-mode"]);
  assert.equal(launches[0].environment.JX_SESSION_ID, "session-secret");
  assert.equal(JSON.stringify(receipt).includes("session-secret"), false);
  assert.equal(JSON.stringify(broker.status()).includes("character-secret"), false);
  assert.deepEqual(broker.status().pending, []);
  assert.equal(broker.status().starting[0].instance_id, "main-character");
  broker.reconcile([{ instance_id: "main-character" }]);
  assert.deepEqual(broker.status().starting, []);

  now += 1;
});

test("accepts an unarmed normal Play handoff as stock and validates session identity", async () => {
  let launched;
  const broker = new LauncherBroker({
    registry: { scan: async () => ({ instances: [], rejected: [] }) },
    supervisor: {
      startFromLauncher: (request) => {
        launched = request;
        return { instance_id: request.spec.instance_id, mode: "stock" };
      },
    },
    socketPath: "/tmp/unused.sock",
    createId: () => "generated-id",
  });

  const receipt = await broker.accept(handoff({
    JX_SESSION_ID: "session",
    JX_CHARACTER_ID: "character",
  }));
  assert.equal(receipt.instance_id, "jagex-generated-id");
  assert.equal(launched.spec.launch_mode, "stock");
  await assert.rejects(
    () => broker.accept(handoff({ JX_CHARACTER_ID: "character" })),
    /JX_SESSION_ID/,
  );
});

test("prefers an exact character request over an earlier wildcard request", async () => {
  const launched = [];
  const ids = ["wildcard-request", "exact-request"];
  const broker = new LauncherBroker({
    registry: { scan: async () => ({ instances: [], rejected: [] }) },
    supervisor: {
      startFromLauncher: (request) => {
        launched.push(request.spec.instance_id);
        return { instance_id: request.spec.instance_id, mode: "stock" };
      },
    },
    socketPath: "/tmp/unused.sock",
    createId: () => ids.shift(),
  });
  await broker.arm({ instance_id: "any-character" });
  await broker.arm({ instance_id: "specific-character", expected_display_name: "Specific" });

  await broker.accept(handoff({
    JX_SESSION_ID: "session",
    JX_CHARACTER_ID: "character",
    JX_DISPLAY_NAME: "Specific",
  }));
  assert.deepEqual(launched, ["specific-character"]);
  assert.deepEqual(
    broker.status().pending.map((request) => request.instance_id),
    ["any-character"],
  );
});

test("Unix handoff server forwards inherited values in memory and returns only safe receipts", async (context) => {
  const directory = await temporaryDirectory(context);
  const socketPath = path.join(directory, "runtime", "launcher.sock");
  let accepted;
  const server = new LauncherHandoffServer({
    socketPath,
    broker: {
      accept: async (value) => {
        accepted = value;
        return { instance_id: "socket-client", mode: "stock" };
      },
    },
  });
  await server.start();
  context.after(() => server.close());
  assert.equal((await stat(socketPath)).mode & 0o777, 0o600);

  const receipt = await forwardLauncherHandoff({
    socketPath,
    environment: {
      JX_SESSION_ID: "socket-session-secret",
      JX_CHARACTER_ID: "socket-character-secret",
      JX_DISPLAY_NAME: "Socket Character",
      UNRELATED_SECRET: "must-not-cross",
    },
    arguments: ["--profile=socket"],
  });
  assert.deepEqual(receipt, { instance_id: "socket-client", mode: "stock" });
  assert.equal(accepted.environment.JX_SESSION_ID, "socket-session-secret");
  assert.equal("UNRELATED_SECRET" in accepted.environment, false);
  assert.equal(JSON.stringify(receipt).includes("socket-session-secret"), false);
});

test("handoff client distinguishes an absent Harness from a rejected handoff", async (context) => {
  const directory = await temporaryDirectory(context);
  await assert.rejects(
    () => forwardLauncherHandoff({ socketPath: path.join(directory, "missing.sock") }),
    HandoffUnavailableError,
  );

  const socketPath = path.join(directory, "reject", "launcher.sock");
  const server = new LauncherHandoffServer({
    socketPath,
    broker: { accept: async () => { throw new Error("request rejected safely"); } },
  });
  await server.start();
  context.after(() => server.close());
  await assert.rejects(
    () => forwardLauncherHandoff({ socketPath }),
    /request rejected safely/,
  );
});

function handoff(environment, arguments_ = []) {
  return {
    schema: JAGEX_HANDOFF_SCHEMA,
    environment,
    arguments: arguments_,
  };
}

async function temporaryDirectory(context) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "genericclient-handoff-test-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}
