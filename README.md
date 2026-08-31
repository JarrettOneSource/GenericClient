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
- **Settings** contains mouse movement/trail options and the behavior profile.

Settings can save account-specific behavior overrides or restore the original
seeded profile. The dashboard intentionally omits low-value runtime counters;
complete diagnostics remain available through the Console and MCP.
During a long break, a compact **Break** banner appears above the connection
status in the dashboard sidebar. Its × button ends that break immediately and
restores a logged-out session before script execution resumes.

## Linux fleet dashboard

The external Harness serves one loopback page for all supervised GenericClient
processes. It discovers atomic instance descriptors, validates PID and endpoint
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

Open `http://127.0.0.1:3765`, then launch dense instances from the page or with
`node harness/src/cli.mjs launch-dense --runtime "$runtime_dir" --instance client-01`.
The server is loopback-only and does not expose client control endpoints or a
raw RPC proxy. Dense Linux instances are displayless Xvfb clients with a
low-rate software canvas, not `java.awt.headless=true` processes. See
[`docs/linux-harness-poc.md`](docs/linux-harness-poc.md) for operation and live
proof boundaries and [`docs/harness-dashboard.md`](docs/harness-dashboard.md)
for the web contract.

## Lua scripts

GenericClient creates an empty external script registry at:

```text
~/.runelite/genericclient/scripts/
```

Install the maintained script catalog from
[GenericClientScripts](https://github.com/Pernasua/GenericClientScripts), or
create scripts through the dashboard or MCP. `manifest.json` registers the
scripts shown in Automations. Press **Reload manifest** after editing files
manually.

An optional `random_events` array registers a standalone script as the solver
for those random-event NPC IDs. GenericClient detects owned events internally,
interrupts normal automation, and never requires RuneLite's Random Events plugin.
Solver implementations live in GenericClientScripts.
The lifecycle and solver template are in
[`docs/random-events.md`](docs/random-events.md).

Large scripts may declare named modules in that same manifest entry. Modules
stay inside the script sandbox and are loaded with `gc.require`:

```json
"modules": {
  "config": "my-script/config.lua",
  "actions": "my-script/actions.lua"
}
```

```lua
local config = gc.require("config")
```

Each module returns one Lua value, normally a small table of data and functions.
GenericClient reads only the declared files; Lua still has no unrestricted
filesystem or `package` access.

The catalog README documents its included automations and script-specific
validation state.

The current scripting interface intentionally contains only:

```lua
gc.read(subject, query)
gc.await(request)
gc.log(level, event, fields)
gc.activity(name)
gc.state(name)
gc.phase(name, options)
gc.overlay(rows)
gc.next_action()
gc.require(name) -- only for modules declared by this script
```

Implemented reads are `runtime`, `player`, `random_event`, `npcs`, `messages`,
`objects`, `ground_items`, `dialogue`, `vars`, `scene`, `instance`, `widgets`,
`behavior`, `skills`, `inventory`, `equipment`, `bank`,
`quests`, `grand_exchange`, `cash`, and the
combined `account` frame. `combat` reports the current attack-style index and
auto-retaliate state. A bank read explicitly reports `unknown`, `open`, or
`cached`; it never treats an unseen bank as empty. NPC rows distinguish scene
presence, canvas clickability, and line of sight. `messages` exposes a bounded
history of client messages, including public chat. Implemented waits are game
ticks, tick counts, `walk.random`, `walk.to`, and `mouse.offscreen`. New semantic
actions are added when an automation actually needs them. Object, inventory,
equipment, item-on-entity, dialogue, bank-loadout, GE-buy, UI-close, and combat-cast
actions use the same receipt model. Lua can arm or clear the framework's
tick-priority emergency guard with `safety.configure` and `safety.clear`.
`npc.interact`
selects the nearest matching NPC, faces it once when needed, resolves the requested menu option, and uses
the same template-generated synthetic cursor for either a left-click or
context-menu interaction.

```lua
local result = gc.await {
  action = {
    type = "npc.interact",
    name = "Banker",
    action = "Bank",
    within = 8,
  },
  breaks = false,
}
```

Combat settings use semantic client interactions too:

```lua
local result = gc.await {
  action = { type = "combat.set_style", style = 0 },
  breaks = false,
}
```

```lua
local result = gc.await {
  action = { type = "combat.set_auto_retaliate", enabled = false },
  breaks = false,
}
```

`walk.to` uses the pinned global collision map outside the loaded scene and the
current RuneLite collision frame inside it. Closed live edges are routeable
only when the exact wall orientation contains an approved traversal object.
For each ordinary leg it turns the client camera, selects the farthest currently
projectable route tile, and follows it with a template-generated synthetic
canvas, context-menu, or minimap click without moving the operating-system
cursor:

```lua
local result = gc.await {
  action = {
    type = "walk.to",
    destination = { x = 3210, y = 3424, plane = 0 },
    within = 3,
    run = true,
  },
  timeout = { game_ticks = 600 },
}
```

Walking enables run by default when at least 10% energy is available, verifies
the orb state, and may enable it again after energy drains and recovers. A
script phase that must conserve energy sets `run = false` on that `walk.to`.

Every composite mouse-movement-and-click interaction captures an immutable
activity context. Travel and skilling independently roll for a break and cursor
release after every route click; combat, banking, trading, and dialogue suppress
both. `gc.activity("questing")` describes the broad workflow while semantic
actions select their safe leaf activity. A time-sensitive sequence can bypass
all discretionary behavior for its interactions:

```lua
local result = gc.await {
  action = { type = "walk.random" },
  breaks = false,
}
```

Major completed states can request a heavier profile-shaped evaluation:

```lua
gc.phase("banking.complete")
```

This slice supports one-tile, same-plane, non-instanced ground movement and
bounded same-plane traversal actions such as opening an accessible door. It
verifies the live edge or object state before resuming. Explicit locked-door
feedback returns an immediate unreachable receipt; stairs, ships, teleports, and other plane or interface
transitions remain explicit future handlers. Exact design, map provenance,
diagnostics, and live receipts are in
[`docs/walker-design.md`](docs/walker-design.md).

Each Lua file returns a descriptor. The optional `inputs` and `actions` arrays
tell the dashboard what controls to render; `run(input)` receives the selected
values. The first concrete input type is `choice`:

```lua
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
  actions = {
    { id = "refresh", label = "Refresh" },
  },
  run = function(input)
    gc.await { event = "game.tick" }
    gc.overlay {
      { label = "Destination", value = input.destination },
      { label = "State", value = "Waiting" },
    }
    local action = gc.next_action()
    gc.log("info", "selected", { destination = input.destination })
  end,
}
```

The dashboard and `script_run` validate supplied values against that descriptor.
Active Script buttons enqueue their declared ID; Lua consumes one queued ID at
a safe point with `gc.next_action()`. Stop and Restart remain immediate host
actions. `gc.overlay` accepts at most three label/value rows. While a script is
running, RuneLite renders those rows beneath a compact automatic name/runtime
header; the overlay disappears when the script stops or completes.
The destination names and coordinates in Walker stay in Lua; collision loading,
pathfinding, camera projection, and synthetic clicking stay in the Java plugin.
After its route and arrival phase finish, Walker uses `mouse.offscreen` to park
the synthetic cursor at the active behavior profile's stable idle edge. The
next action randomizes a different coordinate along that same side before
generating its return path.

GenericClient never refreshes or replaces external scripts. When no manifest is
present it creates an empty one; installing or updating a script catalog is an
explicit operation.

The active design is documented in
[`docs/lua-scripting-design.md`](docs/lua-scripting-design.md). The packet-driven
headless design is saved separately and remains deferred.

The seeded profile, active-time hazard, persistence, phase weighting,
off-canvas idle, logout/re-login flow, and numeric envelopes are documented in
[`docs/behavior-system.md`](docs/behavior-system.md).

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

The included stdio MCP server lets Codex read live, behavior, and scheduling state, capture
the fully rendered game canvas as a PNG, execute Lua snippets, save and run
scripts, and control the Jagex-backed game session.
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
