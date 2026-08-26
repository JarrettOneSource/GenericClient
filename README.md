# GenericClient

GenericClient is a RuneLite plugin with a small Lua 5.4 scripting host over
immutable client snapshots and native game actions.

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

After logging in, open the GenericClient sidebar:

- **Print diagnostics** logs the RuneLite version, game revision, game state,
  player location, classloader, thread, tick count, and uptime.
- **Log nearby NPCs** logs each nearby NPC's name, ID, index, location,
  distance, combat level, animation, interaction target, and actions.
- **Walk to random tile** moves the native cursor to a nearby ground tile,
  follows the active recorded mouse profile to it, executes a left click when
  the selected action is `Walk here`, records the resulting
  `MenuOptionClicked`, and logs a fresh NPC snapshot.
- **Lua scripts** can be scanned, started, reloaded, and stopped from the
  sidebar. Script output appears in the Lua log pane and `client.log`.

## Lua scripts

Editable scripts are installed in:

```text
~/.runelite/genericclient/scripts/
```

`npc-diagnostics.lua` starts with the plugin and logs nearby NPC snapshots every
five game ticks. `walk-stress.lua` is a manual three-click stress script that
uses the existing native ground-tile interaction. `lumbridge-varrock.lua` is a
manual ground-route script prepared for its first test; start it outdoors in
Lumbridge and select it in the sidebar.

The current scripting interface intentionally contains only:

```lua
gc.read(subject, query)
gc.await(request)
gc.log(level, event, fields)
```

Implemented reads are `runtime`, `player`, and `npcs`. Implemented waits are
game ticks, tick counts, `walk.random`, and `walk.to`. New snapshot subjects and
semantic actions are added when an automation actually needs them.

`walk.to` plans ordinary ground routes against a pinned global collision map
and follows them with confirmed real-mouse clicks:

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

This first slice supports one-tile, same-plane, non-instanced ground movement.
It does not yet execute doors, stairs, ships, teleports, dialogues, or widgets,
and it does not yet overlay the loaded scene's dynamic collision. Exact design,
map provenance, diagnostics, receipts, and the first-test handoff are in
[`docs/walker-design.md`](docs/walker-design.md).

Each Lua file returns one root coroutine function:

```lua
return function()
  while true do
    gc.await { event = "game.tick" }
    local npcs = gc.read("npcs", { within = 15, limit = 25 })
    gc.log("info", "nearby-npcs", { count = #npcs })
  end
end
```

The active design is documented in
[`docs/lua-scripting-design.md`](docs/lua-scripting-design.md). The packet-driven
headless design is saved separately and remains deferred.

## Mouse profiles

Mouse movement uses a local Template Match implementation with the bundled
6,069-trajectory recorded profile. Profiles live in:

```text
~/.runelite/genericclient/mouse-profiles/
```

Select a filename in RuneLite's GenericClient settings and reload it from the
sidebar. The sidebar can also record manual RuneLite-canvas movement into a new
profile and activate it. The matcher, profile schema, recording flow, and source
data hashes are documented in
[`docs/mouse-profiles.md`](docs/mouse-profiles.md).

Diagnostics are written to:

- the terminal running RuneLite;
- `~/.runelite/logs/client.log`;
- the GenericClient sidebar.

Log lines use the `[GenericClient]` prefix.

## Build

```bash
./gradlew clean jar shadowJar
```

Artifacts:

- `build/libs/generic-client-0.3.0.jar`
- `build/libs/GenericClient-0.3.0-all.jar`

Run the standalone artifact with:

```bash
java -ea -jar build/libs/GenericClient-0.3.0-all.jar
```
