import assert from "node:assert/strict";
import { execFile, execFileSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import test from "node:test";

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
