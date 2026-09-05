# GenericClient

GenericClient is a RuneLite plugin with a Lua 5.4 scripting host over immutable
client snapshots, seeded per-account behavior profiles, and synthetic
client-only input.

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
- **Automations** runs manifest scripts and shows their output.
- **Schedules** shows named time windows, rule decisions, the active rule lease,
  and Enable/Pause/Reload controls.
- **Console** contains the Lua REPL plus manual status and walk checks.
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

## Lua scripts

The current scripting interface is API 3, reported by
`gc.read("runtime").api_version`. Install the matching
[GenericClientScripts catalog](https://github.com/Pernasua/GenericClientScripts)
with the client. Scripts live under `~/.runelite/genericclient/scripts/`;
startup creates an empty manifest only when one is absent.

`manifest.json` registers entry files, optional named modules and random-event
solver IDs. Modules load through `gc.require` and remain inside the script's
sandbox. `script_get` exposes their sources; `script_save` preserves an existing
filename and module bindings. External edits take effect after manifest reload
and a new start/restart. The client does not update the external catalog by
itself.

| Interface | Purpose |
| --- | --- |
| `gc.read(subject, query)` | Read copied world/account/UI state from one pinned frame |
| `gc.await(request)` | Wait for ticks or a semantic action receipt |
| `gc.activity(name, policy)` | Declare independent behavior and safety policy |
| `gc.state(name)`, `gc.phase(name, options)` | Report script state and evaluate major transitions |
| `gc.intent(name, fn)` | Share one behavior boundary across a short sequence |
| `gc.walk.to(options)` | Plan and execute a constrained destination journey |
| `gc.log`, `gc.overlay` | Publish diagnostics and up to four overlay rows/scene markers |
| `gc.next_action()` | Cooperatively consume a declared script button |
| `gc.require(name)` | Load a manifest-declared module |
| `gc.checkpoint`, `gc.clear_checkpoint` | Persist or clear an account/script integer checkpoint |

An entry file returns a descriptor with `run(input)`, optional choice inputs
and optional action buttons. The dashboard and MCP validate the same input
metadata. A complete destination script can use:

```lua
local destinations = {
  varrock = { x = 3210, y = 3424, plane = 0 },
  grand_exchange = { x = 3164, y = 3487, plane = 0 },
}

return {
  inputs = {
    {
      id = "destination",
      label = "Destination",
      type = "choice",
      default = "varrock",
      choices = {
        { value = "varrock", label = "Varrock" },
        { value = "grand_exchange", label = "Grand Exchange" },
      },
    },
  },
  run = function(input)
    gc.activity("travel")
    local journey = gc.walk.to {
      destination = destinations[input.destination],
      within = 3,
      ticks = 600,
      interrupt_on = { dialogue = true },
    }
    if journey.status ~= "arrived" then return journey end
    gc.phase("route.arrived")
    gc.await { action = { type = "mouse.offscreen" } }
    return journey
  end,
}
```

Journeys combine bundled global terrain with current scene collision and
eligible directed transports. `via` preserves required corridors,
`avoid_tiles` excludes quest hazards, `arrival_tiles` restricts allowed final
tiles, and `resume` carries observed progress after an interruption. Native
input verifies the selected object, widget or conversation and observes the
transport landing. Door failures are remembered per account and trigger
replanning. The [walker contract](docs/walker-design.md) defines limits,
interrupt priority, cancellation and receipt fields.

Semantic actions own their native substeps and one completion boundary. Micro
pressure accrues with owned active time; completed actions and phases provide
places to take a due break. Intents suppress discretionary behavior between
related steps, while long approaches and training loops stay outside them.
Use `gc.activity("combat", { breaks = true })` for repeatable combat that should
retain combat's damage and prayer policy while allowing breaks. Expected damage
does not disable forced healing or emergency escape.

Operator REPL calls are plain unless an await or phase explicitly sets
`humanize = true`. Standalone scripts keep their declared policy. One-off
urgent behavior belongs in a `policy` table; the per-await `breaks` field and
`interrupt_on_dialogue` alias are rejected.

Snapshots include player, skills, inventory, equipment, bank cache, quests,
offers, cash, scene entities, widgets and dialogue. Unknown or stale data cannot
stand in for a fresh account frame. A dispatched action is not automatically a
quest postcondition; the script observes the expected state before advancing.

The detailed interfaces are documented in
[Lua runtime](docs/lua-scripting-design.md),
[behavior system](docs/behavior-system.md), and
[MCP/Lua control](docs/mcp-lua-control.md).
Random-event ownership and solver registration are described in
[random-events.md](docs/random-events.md). Source tests, a loaded artifact and
fresh live receipts remain separate acceptance steps.

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
          "target_level": "30",
          "method": "auto"
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
game canvas as a PNG, execute Lua snippets, save and run scripts, and control the
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

The MCP tools, Lua REPL examples, manifest format, and junior-friendly script
workflow are documented in
[`docs/mcp-lua-control.md`](docs/mcp-lua-control.md).

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
preempts synthetic input and pauses the current Lua action. After 1.5 seconds
without another physical mouse event, GenericClient resumes the same script,
retaining verified input or retrying a canceled attempt from fresh state. A physical Escape keypress is the manual stop:
it cancels client input, stops Lua, and keeps Idle from moving the cursor until
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
./gradlew clean jar shadowJar
```

Artifacts:

- `build/libs/GenericClient-thin.jar`
- `build/libs/GenericClient.jar`

Run the standalone artifact with:

```bash
java -ea -jar build/libs/GenericClient.jar
```
