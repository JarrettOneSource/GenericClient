import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const powershell = ["pwsh", "powershell.exe"].find((command) =>
  spawnSync(command, ["-NoProfile", "-NonInteractive", "-Command", "exit 0"]).status === 0,
);
const localScript = fileURLToPath(new URL("../scripts/wait-client.ps1", import.meta.url));
const scriptPath = powershell === "powershell.exe" && process.platform !== "win32"
  ? spawnSync("wslpath", ["-w", localScript], { encoding: "utf8" }).stdout.trim()
  : localScript;
const runtime = { skip: powershell ? false : "PowerShell is unavailable" };

function status({ script = "route", state = "RUNNING", result = "", hp = 50,
  recovering = false, safety = "idle", attention = false, deathTick = -1 } = {}) {
  return {
    game_state: "LOGGED_IN",
    lua: { active_script: script, run_id: 7, script_status: state,
      script_state: "travel", active: { result: { status: result } } },
    player: { current_hitpoints: hp, max_hitpoints: 75, world: { x: 3200, y: 3400 } },
    safety: { recovering, last_event: safety },
    random_event: { attention_required: attention, state: "idle" },
    death_forensics: { last_death_tick: deathTick },
    behavior: { state: "ACTIVE", break_remaining_millis: 60001 },
  };
}

async function waitClient(statuses, { timeout = 3 } = {}) {
  const encodedStatuses = Buffer.from(JSON.stringify(statuses)).toString("base64");
  const command = `
$ErrorActionPreference = 'Stop'
$global:observations = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${encodedStatuses}')) | ConvertFrom-Json
$global:observationIndex = 0
function Invoke-RestMethod {
  param($Method, $Uri, $ContentType, $Body, $TimeoutSec)
  $request = [Text.Encoding]::UTF8.GetString($Body) | ConvertFrom-Json
  if ($request.method -ne 'status') { exit 98 }
  if ($global:observationIndex -ge $global:observations.Count) { exit 99 }
  [Console]::WriteLine('{"test_event":"poll"}')
  $observation = $global:observations[$global:observationIndex]
  $global:observationIndex++
  return @{ ok = $true; result = $observation }
}
function Start-Sleep {
  param([int]$Milliseconds)
  [Console]::WriteLine(('{"test_event":"sleep","milliseconds":' + $Milliseconds + '}'))
}
& '${scriptPath.replaceAll("'", "''")}' -ScriptId route -RunId 7 -PollMilliseconds 25 -TimeoutSeconds ${timeout}
exit $LASTEXITCODE
`;
  const child = spawn(powershell, ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand",
    Buffer.from(command, "utf16le").toString("base64")]);
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8").on("data", (chunk) => { stdout += chunk; });
  child.stderr.setEncoding("utf8").on("data", (chunk) => { stderr += chunk; });
  const timer = setTimeout(() => child.kill(), 10_000);
  try {
    const code = await new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("close", resolve);
    });
    const events = stdout.split(/\r?\n/).filter((line) => line.startsWith("{")).map(JSON.parse);
    assert.notEqual(code, null, `PowerShell did not exit: ${stderr}`);
    assert.ok(events.length > 0 || timeout === 0, `No monitor output: ${stderr}`);
    return { code, events, stderr };
  } finally {
    clearTimeout(timer);
  }
}

test("emergency grace retains the polling interval while waiting for death evidence", runtime, async () => {
  const result = await waitClient([
    status(),
    status({ script: "none", state: "IDLE", recovering: true, safety: "triggered" }),
    status({ script: "none", state: "IDLE" }),
    status({ script: "none", state: "IDLE", hp: 0 }),
  ]);
  assert.equal(result.code, 6);
  assert.equal(result.events.at(-1).fatal, "death_detected");
  assert.deepEqual(result.events.filter((event) => event.test_event).map((event) => event.test_event),
    ["poll", "sleep", "poll", "sleep", "poll", "sleep", "poll"]);
  assert.ok(result.events.filter((event) => event.test_event === "sleep")
    .every((event) => event.milliseconds === 25));
});

for (const [name, observations, code, fatal] of [
  ["completed run", [status({ state: "COMPLETED", result: "complete" })], 0],
  ["failed result", [status({ state: "COMPLETED", result: "supplies_required" })], 4, "script_result_failed"],
  ["faulted run", [status({ state: "FAULTED" })], 4],
  ["stopped run", [status({ state: "COMPLETED", result: "stopped" })], 5],
  ["vanished run", [status(), status({ script: "none", state: "IDLE" })], 5],
  ["death before attention", [status({ hp: 0, attention: true })], 6, "death_detected"],
  ["attention before vanished run", [status(), status({ script: "none", state: "IDLE", attention: true })], 3],
]) {
  test(`wait-client reports ${name}`, runtime, async () => {
    const result = await waitClient(observations);
    assert.equal(result.code, code, result.stderr);
    assert.equal(result.events.find((event) => event.fatal)?.fatal, fatal);
    assert.equal(result.events.find((event) => event.game).break_minutes, 2);
  });
}

test("a first recorded death is detected after the player has respawned", runtime, async () => {
  const result = await waitClient([status(), status({ deathTick: 42 })]);
  assert.equal(result.code, 6, result.stderr);
  assert.equal(result.events.at(-1).fatal, "death_detected");
});

test("wait-client uses its documented timeout exit", runtime, async () => {
  const result = await waitClient([], { timeout: 0 });
  assert.equal(result.code, 2, result.stderr);
});
