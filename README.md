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

- **Automations** runs manifest scripts and shows their output.
- **Console** contains the Lua REPL plus the three diagnostic actions.
- **Settings** contains mouse movement/trail options and the behavior profile.

Settings can save account-specific behavior overrides or restore the original
seeded profile. The dashboard intentionally omits low-value runtime counters;
complete diagnostics remain available through the Console and MCP.
During a long break, a compact **Break** banner appears above the connection
status in the dashboard sidebar. Its × button ends that break immediately and
restores a logged-out session before script execution resumes.

## Lua scripts

Editable scripts are installed in:

```text
~/.runelite/genericclient/scripts/
```

`manifest.json` registers the scripts shown in Automations. Each entry has a
stable id, display name, description, and Lua filename. Press **Reload
manifest** after editing it manually.

`npc-diagnostics.lua` can log nearby NPC snapshots on demand. `walk-stress.lua`
is a manual three-click stress script that uses the existing ground-tile
interaction. `walker.lua` exposes a destination dropdown and can walk to the
Grand Exchange, Varrock, Edgeville, Falador, Draynor, or Lumbridge through the
same public `walk.to` action.

The current scripting interface intentionally contains only:

```lua
gc.read(subject, query)
gc.await(request)
gc.log(level, event, fields)
gc.phase(name, options)
```

Implemented reads are `runtime`, `player`, `npcs`, and `behavior`. Implemented waits are
game ticks, tick counts, `walk.random`, `walk.to`, and `mouse.offscreen`. New
snapshot subjects and semantic actions are added when an automation actually
needs them.

`walk.to` plans ordinary ground routes against a pinned global collision map.
For each leg it turns the client camera, selects the farthest currently
projectable route tile, and follows it with a template-generated synthetic
canvas, context-menu, or minimap click without moving the operating-system
cursor:

```lua
local result = gc.await {
  action = {
    type = "walk.to",
    destination = { x = 3210, y = 3424, plane = 0 },
    within = 3,
  },
  timeout = { game_ticks = 600 },
}
```

Every composite mouse-movement-and-click interaction rolls the seeded behavior
profile by default. A multi-click walk therefore rolls after every route click.
A time-sensitive sequence can bypass both micro and long breaks for all of its
interactions:

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

This first slice supports one-tile, same-plane, non-instanced ground movement.
It does not yet execute doors, stairs, ships, teleports, dialogues, or widgets,
and it does not yet overlay the loaded scene's dynamic collision. Exact design,
map provenance, diagnostics, and live receipts are in
[`docs/walker-design.md`](docs/walker-design.md).

Each Lua file returns a descriptor. The optional `inputs` array tells the
dashboard what controls to render; `run(input)` receives the selected values.
The first concrete input type is `choice`:

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
  run = function(input)
    gc.await { event = "game.tick" }
    gc.log("info", "selected", { destination = input.destination })
  end,
}
```

The dashboard and `script_run` validate supplied values against that descriptor.
The destination names and coordinates in Walker stay in Lua; collision loading,
pathfinding, camera projection, and synthetic clicking stay in the Java plugin.
After its route and arrival phase finish, Walker uses `mouse.offscreen` to park
the synthetic cursor at the active behavior profile's stable idle edge.

On first 0.9 startup, a version-1 manifest is upgraded and its bundled scripts
are replaced with descriptor-based versions. Custom entries and files are kept;
custom scripts written for 0.8 must change their root return value from a
function to `{ run = function(input) ... end }`.

The active design is documented in
[`docs/lua-scripting-design.md`](docs/lua-scripting-design.md). The packet-driven
headless design is saved separately and remains deferred.

The seeded profile, active-time hazard, persistence, phase weighting,
off-canvas idle, logout/re-login flow, and numeric envelopes are documented in
[`docs/behavior-system.md`](docs/behavior-system.md).

## MCP control

The included stdio MCP server lets Codex read live and behavior state, execute
Lua snippets, save and run scripts, and control the Jagex-backed game session.
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

- `build/libs/generic-client-0.9.0.jar`
- `build/libs/GenericClient-0.9.0-all.jar`

Run the standalone artifact with:

```bash
java -ea -jar build/libs/GenericClient-0.9.0-all.jar
```
