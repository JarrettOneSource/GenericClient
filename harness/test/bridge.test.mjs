import assert from "node:assert/strict";
import { execFile, execFileSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import test from "node:test";

import { LauncherHandoffServer } from "../src/launcher-handoff-server.mjs";

const execute = promisify(execFile);
const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const harnessDirectory = path.resolve(testDirectory, "..");
const bridge = path.join(harnessDirectory, "bin", "GenericClient-RuneLite.exe");

test("direct bridge preserves launcher environment without persisting values", async (context) => {
  const temporary = await temporaryDirectory(context, "genericclient-bridge-");
  const output = path.join(temporary, "receipt.json");
  const secrets = ["direct-session-value", "direct-character-value", "Direct Character"];

  await execute(bridge, ["one", "two"], {
    env: probeEnvironment(output, secrets),
  });

  const { receipt, source } = await receiptAt(output);
  assert.equal(receipt.native_platform, "linux");
  assert.equal(receipt.argument_count, 2);
  assert.deepEqual(receipt.inherited, expectedInheritance());
  for (const secret of secrets) {
    assert.equal(source.includes(secret), false);
  }
});

test("Wine CreateProcess executes the native bridge and preserves launcher environment", async (context) => {
  if (!available("wine") || !available("xvfb-run")) {
    context.skip("Wine and Xvfb are required for the compatibility receipt");
    return;
  }
  const temporary = await temporaryDirectory(context, "genericclient-wine-bridge-");
  const prefix = path.join(temporary, "wine-prefix");
  const output = path.join(temporary, "receipt.json");
  const secrets = ["wine-session-value", "wine-character-value", "Wine Character"];
  const env = {
    ...probeEnvironment(output, secrets),
    WINEPREFIX: prefix,
    WINEDEBUG: "-all",
  };
	await mkdir(prefix);

  try {
    await execute(
      "xvfb-run",
      [
        "-a",
        "bash",
        "-c",
        [
          'wine start /unix "$1" wine-proof >/dev/null 2>&1 || true',
          "for attempt in $(seq 1 200); do",
          '  test -f "$GENERICCLIENT_BRIDGE_PROBE_OUTPUT" && exit 0',
          "  sleep 0.1",
          "done",
          "exit 1",
        ].join("\n"),
        "genericclient-wine-bridge",
        bridge,
      ],
      { env, timeout: 30_000 },
    );
  } finally {
    if (available("wineserver")) {
      try {
        execFileSync("wineserver", ["-k"], { env, stdio: "ignore" });
      } catch {
        // The Wine server may already have exited.
      }
    }
  }

  const { receipt, source } = await receiptAt(output);
  assert.equal(receipt.native_platform, "linux");
  assert.equal(receipt.argument_count, 1);
  assert.deepEqual(receipt.inherited, expectedInheritance());
  for (const secret of secrets) {
    assert.equal(source.includes(secret), false);
  }
});

test("Jagex bridge defaults to full stock RuneLite while supervised dense is explicit", async (context) => {
  const temporary = await temporaryDirectory(context, "genericclient-bridge-mode-");
  const jar = path.join(temporary, "GenericClient.jar");
  await writeFile(jar, "test artifact");
  const environment = {
    ...process.env,
    DISPLAY: process.env.DISPLAY || ":99",
    GENERICCLIENT_JAVA: "/bin/echo",
    GENERICCLIENT_JAR: jar,
    GENERICCLIENT_RUNTIME_DIR: temporary,
    GENERICCLIENT_INSTANCE_ID: "mode-proof",
    GENERICCLIENT_HARNESS_SOCKET: path.join(temporary, "missing.sock"),
  };

  const stock = await execute(bridge, ["--profile=normal"], { env: environment });
  assert.match(stock.stdout, /com\.genericclient\.GenericClientLauncher/);
  assert.doesNotMatch(stock.stdout, /GenericClientDenseLauncher|genericclient\.dense=true/);
  assert.doesNotMatch(stock.stdout, /-Duser\.home=/);
  assert.doesNotMatch(stock.stdout, /ActiveProcessorCount|Xss1m/);
  assert.match(stock.stdout, /-Xmx768m/);

  const dense = await execute(bridge, [], {
    env: { ...environment, GENERICCLIENT_LAUNCH_MODE: "dense" },
  });
  assert.match(dense.stdout, /com\.genericclient\.GenericClientDenseLauncher/);
  assert.match(dense.stdout, /genericclient\.dense=true/);
  assert.match(dense.stdout, /-Duser\.home=/);
});

test("Jagex bridge hands an official session to a running Harness without printing it", async (context) => {
  const temporary = await temporaryDirectory(context, "genericclient-bridge-handoff-");
  const socketPath = path.join(temporary, "runtime", "launcher.sock");
  let accepted;
  const server = new LauncherHandoffServer({
    socketPath,
    broker: {
      accept: async (value) => {
        accepted = value;
        return { instance_id: "handed-off", mode: "stock" };
      },
    },
  });
  await server.start();
  context.after(() => server.close());
  const secret = "bridge-handoff-session-secret";

  const result = await execute(bridge, ["--profile=main"], {
    env: {
      ...process.env,
      GENERICCLIENT_HARNESS_SOCKET: socketPath,
      JX_SESSION_ID: secret,
      JX_CHARACTER_ID: "bridge-character-secret",
      JX_DISPLAY_NAME: "Bridge Character",
    },
  });

  assert.equal(result.stdout, "");
  assert.equal(result.stderr, "");
  assert.equal(accepted.environment.JX_SESSION_ID, secret);
  assert.deepEqual(accepted.arguments, ["--profile=main"]);
  assert.equal(JSON.stringify(result).includes(secret), false);
});

function probeEnvironment(output, [session, character, displayName]) {
  return {
    ...process.env,
    GENERICCLIENT_BRIDGE_PROBE_OUTPUT: output,
    JX_SESSION_ID: session,
    JX_CHARACTER_ID: character,
    JX_DISPLAY_NAME: displayName,
    JX_ACCESS_TOKEN: "",
    JX_REFRESH_TOKEN: "",
  };
}

function expectedInheritance() {
  return {
    JX_SESSION_ID: true,
    JX_CHARACTER_ID: true,
    JX_DISPLAY_NAME: true,
    JX_ACCESS_TOKEN: false,
    JX_REFRESH_TOKEN: false,
  };
}

async function receiptAt(output) {
  const source = await readFile(output, "utf8");
  return { receipt: JSON.parse(source), source };
}

function available(command) {
  try {
    execFileSync("sh", ["-c", `command -v ${command}`], { stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}

async function temporaryDirectory(context, prefix) {
  const directory = await mkdtemp(path.join(os.tmpdir(), prefix));
  context.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}
