# GenericClient

GenericClient is a RuneLite plugin with Java scripting, a DreamBot source API,
immutable game snapshots, per-account behavior profiles, and synthetic input.

## Install on Windows

Close RuneLite, then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

The installer downloads `GenericClient.jar`, preserves RuneLite's original
launch configuration as `config.stock.json`, and configures the normal Jagex
Launcher **Play** action to start GenericClient. It does not use RuneLite
development mode.

To restore the original launch configuration:

```powershell
Copy-Item "$env:LOCALAPPDATA\RuneLite\config.stock.json" `
  "$env:LOCALAPPDATA\RuneLite\config.json" -Force
```

## Run from source

```bash
./gradlew run
```

This launches the current stock RuneLite release and loads `GenericClientPlugin`
through `ExternalPluginManager.loadBuiltin` without development mode.

Click the GenericClient toolbar icon to open the resizable dashboard popout:

- **Active Script** shows the current script, elapsed runtime, configuration,
  cooperative script buttons, Restart, and Stop.
- **Automations** runs Java scripts and shows their output.
- **Schedules** shows named time windows, rule decisions, the active rule lease,
  and Enable/Pause/Reload controls.
- **Console** contains Java diagnostics plus manual status and walk checks.
- **Settings** contains mouse movement/trail options, a persistent **Show mouse
  tile** switch with world coordinates, and the behavior profile.

Settings can save account-specific behavior overrides or restore the original
seeded profile. The dashboard intentionally omits low-value runtime counters;
complete diagnostics remain available through the Console and MCP.
The profile also chooses keyboard-only or mouse-only dialogue interaction.
Mouse dialogue choices retain their horizontal lane across stacked options;
keyboard choices use non-text hotkeys so they cannot type into chat.
During a long break, a compact **Break** banner appears above the connection
status in the dashboard sidebar. Its × button ends that break immediately and
restores a logged-out session before script execution resumes.

## Linux fleet dashboard

The external Harness serves one loopback page for all GenericClient processes,
including ordinary clients started by pressing Play in the official Jagex
Launcher. It discovers atomic instance descriptors, validates PID and endpoint
identity, streams changed fleet state, reports proportional memory and CPU,
proxies cached screenshots, and requires an explicit instance ID for every
mutation.

```bash
./gradlew shadowJar
runtime_dir=$(mktemp -d /tmp/genericclient-fleet.XXXXXX)

npm --prefix harness run dashboard -- \
  --runtime "$runtime_dir" \
  --port 3765
```

Open `http://127.0.0.1:3765`. The primary **New client** flow arms an identity;
select the character in the official Jagex Launcher and press Play. If the
Harness is not running, that same bridge starts full normal RuneLite directly
with GenericClient and MCP. The advanced dense form remains available for
displayless local tests.
The server is loopback-only and does not expose client control endpoints or a
raw RPC proxy. Dense Linux instances are displayless Xvfb clients with a 1 FPS
render target and suppressed normal canvas presentation; they are not
`java.awt.headless=true` processes. See
[`docs/linux-harness-poc.md`](docs/linux-harness-poc.md) for operation and live
proof boundaries and [`docs/harness-dashboard.md`](docs/harness-dashboard.md)
for the web contract.

## Java scripts

Build scripts against `build/libs/GenericClient-script-api.jar` and place their
JARs in `~/.runelite/genericclient/scripts/`. Entry points extend DreamBot's
`AbstractScript` and declare `@ScriptManifest`. The optional `@ScriptSettings`
annotation supplies a catalog ID, inputs, cooperative buttons, and event IDs.

The maintained [GenericClientScripts](https://github.com/JarrettOneSource/GenericClientScripts)
catalog provides training, quest, recovery, and random-event workflows. It is
compiled separately; no standalone catalog is embedded in the client artifact.

`Walking.walk()` dispatches one route step. GenericClient's `Navigation` extension
handles complete journeys with via points, avoided tiles, arrival alternatives,
and resumable interruptions. Script workers use copied state and revocable input
authority. Physical mouse takeover pauses them; ESC stops them.

See [Java scripting](docs/java-scripting.md) for supported API methods, lifecycle,
compilation, and concurrency rules. Source compilation requires a JDK; loading
precompiled catalog JARs does not.

The Java catalog uses runtime API 3. `Automation.activity(name, policy)` declares
independent behavior and safety choices; `Automation.intent(name, body)` groups
short action sequences. Long approaches and training loops stay outside intents.
Gameplay continues to count owned time while discretionary behavior is suppressed.
See the [behavior contract](docs/behavior-system.md), [journey contract](docs/walker-design.md),
and [MCP control reference](docs/mcp-control.md).

## Scheduled rules

GenericClient can run registered scripts from account-specific schedule and
state rules. This example makes AIO Melee eligible Monday-Friday from 08:00
through 17:00 Eastern while Strength is below 30:

```json
{
  "schema": "genericclient_automation.v1",
  "zone": "America/New_York",
  "enabled": true,
  "schedules": {
    "work-hours": {
      "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
      "windows": [{ "from": "08:00", "until": "17:00" }]
    }
  },
  "rules": [
    {
      "id": "train-strength",
      "priority": 50,
      "when": {
        "all": [
          { "schedule": "work-hours" },
          { "fact": "skills.strength.level", "lt": 30 }
        ]
      },
      "run": {
        "script": "aio-melee",
        "inputs": {
          "skill": "strength",
          "target_level": "30"
        }
      },
      "retry_after": "PT10M"
    }
  ]
}
```

Rules are stored per derived account profile under
`~/.runelite/genericclient/automation/`. Manual scripts retain precedence;
scheduled scripts never replace them. Bank-dependent cash facts remain unknown
until the bank cache is complete, and incomplete wealth is never compared as
zero. The full schema, cash example, lifecycle, persistence, and process-lifetime
limit are documented in
[`docs/automation-scheduling.md`](docs/automation-scheduling.md).

## MCP control

The included stdio MCP server lets Codex read live, behavior, and scheduling
state, highlight scene tiles/entities for inspection, capture the fully rendered
game canvas as a PNG, execute Java diagnostics, compile and run scripts, and control the
Jagex-backed game session.
GenericClient exposes its control bridge only on `127.0.0.1`; the default port
is `17343`.

```bash
cd mcp
npm install

codex mcp add genericclient -- \
  "/mnt/c/Program Files/nodejs/node.exe" \
  '\\wsl.localhost\Ubuntu\home\user\GenericClient\mcp\src\server.mjs'
```

The tools and Java diagnostic workflow are documented in
[MCP control](docs/mcp-control.md).

## Mouse profiles

Mouse movement uses a local Template Match implementation with the bundled
6,069-trajectory recorded profile. Generated paths are injected into RuneLite's
canvas; only manual profile recording reads the real cursor. The dashboard can
show PMouse's fading **Trail**, the active **Path** with green progress, or no
extra effect. A synthetic cursor is always drawn on the game canvas; when it
moves off-canvas, a small arrow is pinned to the corresponding edge. Profiles
live in:

```text
~/.runelite/genericclient/mouse-profiles/
```

The active behavior profile supplies the account's mouse movement duration;
Settings can save a manual duration as part of that account-specific profile.

Physical mouse movement, dragging, canvas entry/exit, or a press immediately
preempts synthetic input and pauses the current script action. After 1.5 seconds
without another physical mouse event, GenericClient resumes the same script,
retaining verified input or retrying a canceled attempt from fresh state. A physical Escape keypress is the manual stop:
it cancels client input, stops scripts, and keeps Idle from moving the cursor until
another standalone automation starts. Synthetic mouse and key events never
trigger either boundary. Emergency food or escape already in progress retains
input ownership; Escape remains the explicit way to stop it.

Select, reload, and record profiles from the dashboard's Settings page. The
matcher, profile schema, recording flow, effects, and source data hashes are documented in
[`docs/mouse-profiles.md`](docs/mouse-profiles.md).

Diagnostics are written to:

- the terminal running RuneLite;
- `~/.runelite/logs/client.log`;
- the GenericClient Console tab.

Log lines use the `[GenericClient]` prefix.

## Build

```bash
./gradlew clean jar shadowJar sdkJar
```

Artifacts:

- `build/libs/GenericClient-thin.jar`
- `build/libs/GenericClient.jar`
- `build/libs/GenericClient-script-api.jar`

Validate Java and native canvas interactions on a Linux host without a display:

```bash
xvfb-run -a ./gradlew test pmdMain pmdTest cpdMain
npm --prefix mcp test
npm --prefix harness test
```

Include the maintained catalog's workflow tests when mutation-testing the SDK:

```bash
xvfb-run -a ./gradlew pitest -PscriptCatalog=../GenericClientScripts
```

On a desktop, run the Gradle command directly. Canvas interaction tests need a
display; `java.awt.headless=true` cannot execute those checks.

Run the standalone artifact with:

```bash
java -ea -jar build/libs/GenericClient.jar
```
