import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { ProcessSupervisor } from "../src/process-supervisor.mjs";

test("normalizes a dense launch and returns a detached supervisor receipt", async (context) => {
  const directory = await temporaryDirectory(context);
  const launches = [];
  let unrefCount = 0;
  const supervisor = createSupervisor(directory, {
    environment: { TEST_ENV: "kept" },
    spawnImpl: (executable, args, options) => {
      launches.push({ executable, args, options });
      return { pid: 9876, unref: () => unrefCount++ };
    },
    existsImpl: () => true,
  });

  const receipt = supervisor.start({
    instance_id: "dense-one",
    heap: "1G",
    jar: path.join(directory, "client.jar"),
    archive: path.join(directory, "dense.jsa"),
    runelite_profile: "profile-one",
  });

  assert.equal(receipt.instance_id, "dense-one");
  assert.equal(receipt.mode, "dense-x11");
  assert.equal(receipt.supervisor_pid, 9876);
  assert.equal(receipt.heap, "1g");
  assert.equal(unrefCount, 1);
  assert.equal(launches[0].executable, "xvfb-run");
  assert.equal(launches[0].args[0], "-a");
  assert.equal(launches[0].options.detached, true);
  assert.equal(launches[0].options.env.TEST_ENV, "kept");
  assert.equal(launches[0].options.env.GENERICCLIENT_INSTANCE_ID, "dense-one");
  assert.equal(launches[0].options.env.GENERICCLIENT_HEAP_SIZE, "1g");
  assert.equal(launches[0].options.env.GENERICCLIENT_RUNELITE_PROFILE, "profile-one");
  assert.equal(supervisor.specFor("dense-one").instance_id, "dense-one");
});

test("uses an existing display without adding Xvfb", async (context) => {
  const directory = await temporaryDirectory(context);
  let launch;
  const supervisor = createSupervisor(directory, {
    environment: { DISPLAY: ":44" },
    spawnImpl: (executable, args, options) => {
      launch = { executable, args, options };
      return { pid: 1234, unref() {} };
    },
    existsImpl: () => true,
  });

  supervisor.start({ instance_id: "existing-display" });
  assert.match(launch.executable, /genericclient-bootstrap$/);
  assert.deepEqual(launch.args, []);
  assert.equal(launch.options.env.DISPLAY, ":44");
});

test("stops one explicit process and waits for exit", async (context) => {
  const directory = await temporaryDirectory(context);
  const calls = [];
  let probes = 0;
  const supervisor = createSupervisor(directory, {
    killImpl: (pid, signal) => {
      calls.push({ pid, signal });
      if (signal === 0 && ++probes > 1) {
        const error = new Error("gone");
        error.code = "ESRCH";
        throw error;
      }
    },
    sleepImpl: async () => {},
  });

  const result = await supervisor.stop({ instance_id: "stop-one", pid: 7654 });
  assert.deepEqual(result, { instance_id: "stop-one", pid: 7654, stopped: true });
  assert.deepEqual(calls[0], { pid: 7654, signal: "SIGTERM" });
  assert.equal(calls.filter((call) => call.signal === 0).length, 2);
});

test("rejects invalid launch and stop inputs", async (context) => {
  const directory = await temporaryDirectory(context);
  const supervisor = createSupervisor(directory, { existsImpl: () => false });

  assert.throws(() => supervisor.start({ instance_id: "bad/id" }), /instance_id/);
  assert.throws(
    () => supervisor.start({ instance_id: "good-id", heap: "a-lot" }),
    /heap/,
  );
  assert.throws(
    () => supervisor.start({ instance_id: "good-id", jar: path.join(directory, "missing.jar") }),
    /JAR is unavailable/,
  );
  await assert.rejects(() => supervisor.stop({ instance_id: "bad/id", pid: 1 }), /valid instance/);
});

function createSupervisor(directory, overrides = {}) {
  const { environment = {}, ...dependencies } = overrides;
  return new ProcessSupervisor(
    {
      runtimeDirectory: path.join(directory, "runtime"),
      instanceDirectory: path.join(directory, "runtime", "instances"),
      repositoryDirectory: directory,
      harnessDirectory: path.join(directory, "harness"),
      environment,
    },
    dependencies,
  );
}

async function temporaryDirectory(context) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "genericclient-supervisor-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}
