import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { INSTANCE_SCHEMA, InstanceRegistry } from "../src/registry.mjs";

test("discovers multiple healthy instances and routes explicitly", async (context) => {
  const directory = await temporaryDirectory(context);
  const first = descriptor("alpha", 101, 41_001);
  const second = descriptor("beta", 202, 42_002);
  await writeDescriptor(directory, first);
  await writeDescriptor(directory, second);
  const registry = registryFor([first, second], directory);

  const scan = await registry.scan();
  assert.deepEqual(
    scan.instances.map((instance) => instance.instance_id),
    ["alpha", "beta"],
  );
  assert.equal(scan.rejected.length, 0);
  assert.equal((await registry.resolve("beta")).pid, 202);
  await assert.rejects(() => registry.resolve(), /instance_id is required/);
});

test("uses the sole healthy instance when no id is supplied", async (context) => {
  const directory = await temporaryDirectory(context);
  const only = descriptor("only", 303, 43_003);
  await writeDescriptor(directory, only);
  const registry = registryFor([only], directory);

  assert.equal((await registry.resolve()).instance_id, "only");
});

test("rejects malformed stale and endpoint-mismatched descriptors", async (context) => {
  const directory = await temporaryDirectory(context);
  const stale = descriptor("stale", 404, 44_004);
  const mismatch = descriptor("mismatch", 505, 45_005);
  await writeDescriptor(directory, stale);
  await writeDescriptor(directory, mismatch);
  await writeFile(path.join(directory, "broken.json"), "{not-json\n");

  const registry = new InstanceRegistry(directory, {
    pidExists: async (pid) => pid !== stale.pid,
    fetchImpl: async (url) => {
      if (url.startsWith(mismatch.control_url)) {
        return Response.json({ ...health(mismatch), instance_id: "someone-else" });
      }
      throw new Error(`unexpected endpoint ${url}`);
    },
  });

  const scan = await registry.scan();
  assert.equal(scan.instances.length, 0);
  assert.deepEqual(
    scan.rejected.map((entry) => entry.reason).sort(),
    ["identity_mismatch", "invalid_descriptor", "stale_pid"],
  );
});

function registryFor(descriptors, directory) {
  return new InstanceRegistry(directory, {
    pidExists: async (pid) => descriptors.some((descriptorValue) => descriptorValue.pid === pid),
    fetchImpl: async (url) => {
      const descriptorValue = descriptors.find((candidate) =>
        url.startsWith(candidate.control_url),
      );
      if (!descriptorValue) {
        return new Response("missing", { status: 404 });
      }
      return Response.json(health(descriptorValue));
    },
  });
}

function descriptor(instanceId, pid, port) {
  return {
    schema: INSTANCE_SCHEMA,
    instance_id: instanceId,
    pid,
    started_epoch_millis: 1_788_000_000_000 + pid,
    control_url: `http://127.0.0.1:${port}`,
    lifecycle: "login_screen",
    dense: true,
    runelite_profile: null,
    launcher_display_name: null,
    account_profile_id: null,
  };
}

function health(descriptorValue) {
  return {
    ok: true,
    name: "GenericClient",
    protocol: 1,
    schema: descriptorValue.schema,
    instance_id: descriptorValue.instance_id,
    pid: descriptorValue.pid,
    control_url: descriptorValue.control_url,
  };
}

async function writeDescriptor(directory, value) {
  await writeFile(
    path.join(directory, `${value.instance_id}.json`),
    `${JSON.stringify(value)}\n`,
  );
}

async function temporaryDirectory(context) {
  const directory = await mkdtemp(path.join(os.tmpdir(), "genericclient-registry-"));
  context.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}
