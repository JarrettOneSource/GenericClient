# Lua runtime and scripting API

GenericClient embeds native Lua 5.4 through LuaJava 4.1.0. Scripting API 3 exposes copied game state and semantic client actions. Lua chooses quest/training goals and reacts to observations; Java owns native input, behavior, safety and navigation.

This document describes the implemented runtime. [MCP and Lua control](mcp-lua-control.md) contains the action/query reference, [behavior-system.md](behavior-system.md) defines policy and intents, and [walker-design.md](walker-design.md) defines journeys and continuations. Implementation checks and live acceptance are tracked separately in [behavior-framework-implementation.md](behavior-framework-implementation.md).

## Runtime ownership

| Owner | Responsibility |
| --- | --- |
| `GenericClientLuaHost` | Standalone lifecycle, scheduler, current frame and run ownership |
| `GenericClientLuaScript` | One native VM, root coroutine, sandbox, descriptor and execution budget |
| `GenericClientLuaAwait` | Validated requests, action identity and captured activity context |
| `GenericClientLuaActions` | Semantic dispatch, behavior boundaries, completion and emergency pause handling |
| `GenericClientLuaIntent` | Nested sequence scopes and their input authority |
| `GenericClientLuaRepl` | Persistent operator globals and one outstanding evaluation |
| `GenericClientLuaCatalog`, `GenericClientScriptRegistry` | Source, declared modules, metadata, inspection and explicit manifest reload |
| `GenericClientLuaRun` | Active/completed run identity, ownership and receipts |
| Snapshot owners | Immutable world, account, quest, widget and dialogue observations |

A host scheduler serializes Lua work. RuneLite callbacks capture client state and return; Lua does not run on the client thread or Swing event thread. Native actions resolve and dispatch on their required threads, then post completions back to the host scheduler.

A coroutine has one outstanding await. A standalone script and the operator REPL have separate VMs, activity declarations and request identities. They share native input owners, which enforce their own admission and cancellation rules. The REPL can read while a standalone script waits; it does not gain another independent walker.

Only one standalone run is active. Manual Start/Restart replaces that run; a scheduled start is idle-only and preserves an existing manual owner. Declared script buttons enter a cooperative queue and do not become direct operator actions.

## Pinned observations

`gc.read(subject, query)` reads the immutable frame pinned for the current Lua resume. Repeated reads before the next await are consistent with that frame. Lua receives values, arrays and tables rather than mutable RuneLite objects. Native input re-resolves target identity after the boundary and rechecks it before dispatch.

```lua
local account = gc.read("account")
return {
  api = account.runtime.api_version,
  tick = account.runtime.game_tick,
  player = account.player,
  inventory = account.inventory,
  quests = account.quests,
}
```

`runtime.api_version` is 3. `runtime.game_revision` is the separately captured game-client revision. The account frame combines player, skills, inventory, equipment, bank cache, quests, offers and cash state. A stale or unknown bank cache cannot establish available cash or supplies.

Logout, loading and account changes invalidate runtime/Lua/walker snapshots. Frames queued before invalidation cannot republish an old account. An unavailable subject remains unavailable until a fresh capture exists. Queries and actions must handle that state according to their own contracts.

Quest snapshots include normalized state and numeric main progress. Progress may be unknown for unsupported rows and is refreshed on the quest cache's ten-tick cadence. Transport and edge-memory decisions use the captured requirement data; they do not infer unrelated quest flags.

## Await requests

Choose one wait form:

```lua
gc.await { event = "game.tick" }
gc.await { ticks = 3 }

local receipt = gc.await {
  action = { type = "npc.interact", name = "Banker", action = "Bank", within = 10 },
  activity = "banking",
  timeout = { game_ticks = 30 },
}
```

The accepted envelope fields are `action`, `ticks`, `event`, `phase`, `timeout`, `activity`, `policy` and `humanize`. `game.tick` is the supported event. Tick waits require a positive count. Action types and their parameters are validated before native dispatch. Unknown envelope fields, including the retired per-await `breaks`, are rejected.

Every action returns its own status and evidence. `dispatched` confirms the input contract; a quest still verifies the intended game-state change. Actions such as a bank loadout or prayer change include their own stronger postconditions. Lua must check the relevant status before advancing a phase.

```lua
local clicked = gc.await {
  action = { type = "item.interact", id = 526, action = "Bury" },
  timeout = { game_ticks = 20 },
}
if clicked.status ~= "dispatched" then return clicked end

-- A quest or trainer now observes the expected inventory/XP change.
gc.await { event = "game.tick" }
return gc.read("inventory")
```

Ordinary action timeouts pause while a behavior break is active. Expiry revokes the pending action before returning a `timed_out` receipt. Tick waits retain their game-tick semantics. A journey owns its active-time timeout so camera/input, break, emergency and modeled blockage intervals do not consume it twice.

The await's request and attempt have cancellation identities. Stale completions cannot advance a replacement coroutine or alter its configuration/prayer owner. A verified action that finishes during an emergency pause is retained; a canceled/rejected attempt may be retried with fresh authority when the emergency ends.

## Activity, policy and intents

```lua
gc.activity("combat", { breaks = true })
gc.state("training")
```

An activity supplies a preset. Policy fields independently control breaks, cursor release, mouse speed, expected damage, prayer ownership, walk refresh and fidgets. Per-await overrides affect that request. The resolver applies live safety/input ownership and reports declared/effective policy and its reasons. Expected damage never disables forced healing or emergency escape.

`gc.phase(name, options)` records a major transition and evaluates an eligible phase boundary. `options.activity` updates the declaration first; `policy` and `humanize` apply to the phase request. `gc.state(name)` changes only the displayed state. Calling `gc.activity()` or `gc.state()` reads the corresponding name.

`gc.intent(name, fn)` groups a short sequence:

```lua
local equipment = gc.require("shared_equipment")
return gc.intent("gear.equip", function()
  local staff = equipment.equip(1387, "Wield")
  if staff.status ~= "complete" and staff.status ~= "unchanged" then return staff end
  return equipment.equip(1059, "Wear")
end)
```

This example uses the catalog's declared `shared_equipment` module, which verifies each equipment change. The [catalog scope tests](https://github.com/Pernasua/GenericClientScripts/blob/main/tests/behavior-intents.lua) also exercise actual banking, dialogue and item sequences. Approaches finish before scope entry, and training loops resume after scope exit.

The host opens one outer boundary. Nested intents flatten into it, and awaits inside suppress discretionary behavior. Receipts include the outer intent name. Normal return preserves every Lua return value; errors unwind and propagate. Timeouts remain receipts that the body may handle. Cancellation revokes scope input, while emergency pauses preserve scope progress. A scope running more than 30 seconds logs `INTENT_LONG` once.

Urgent one-off actions may declare `policy = { breaks = false, cursor_release = "none", fidget = "none" }`. Those independent fields preserve the original urgent input contract without depending on an ordinary break finishing first.

## Journeys

```lua
return gc.walk.to {
  destination = { x = 3164, y = 3487, plane = 0 },
  within = 3,
  ticks = 600,
  interrupt_on = { dialogue = true },
}
```

`gc.walk.to` is the convenience form of the `walk.to` action. It supports ordered `via` points, `avoid_tiles`, alternative `arrival_tiles`, typed interrupts and a single-use `resume` continuation. The native planner includes eligible directed transports and verifies their landings. Item/spell teleports and quest-specific decisions remain explicit Lua behavior.

A journey returns only its terminal receipt. Per-click behavior, replanning, obstacle outcomes and selected transport progress are inside that receipt. Interrupt conditions run before arrival and input, and the old `interrupt_on_dialogue` alias is rejected. See [walker-design.md](walker-design.md) for the precise request, timeout and continuation contracts.

## Standalone source and modules

The client reads `~/.runelite/genericclient/scripts/manifest.json`. A fresh installation gets an empty registry; the maintained catalog lives in the separate GenericClientScripts repository.

```json
{
  "schema": "genericclient_scripts",
  "scripts": [
    {
      "id": "inspect-player",
      "name": "Inspect player",
      "description": "Log a frame on each Refresh request.",
      "file": "inspect-player.lua",
      "modules": { "config": "inspect-player/config.lua" }
    }
  ]
}
```

`inspect-player/config.lua` returns `{ event = "player" }` in this example. Each entry file returns one descriptor with `run(input)`. Modules return their own values and are declared by name in that entry's `modules` object. `gc.require(name)` loads only those modules, with a cache confined to the script VM. It cannot load arbitrary files or native packages.

```lua
local config = gc.require("config")
return {
  actions = { { id = "refresh", label = "Refresh" } },
  run = function()
    gc.activity("general")
    while true do
      gc.log("info", config.event, gc.read("player"))
      repeat gc.await { event = "game.tick" } until gc.next_action() == "refresh"
    end
  end,
}
```

Descriptor initialization constructs metadata. Gameplay work belongs in `run`. Choice inputs declare an ID, label, allowed values and optional default. Supplied input names and values are validated before activation. Up to four declared actions become buttons; the pending queue is bounded to eight and repeated queued buttons are deduplicated. The coroutine consumes them at a safe point with `gc.next_action()`.

`gc.overlay(rows, markers)` supplies up to four label/value rows and optional scene markers. Passing `nil` clears the rows. Run completion removes its overlay and markers. `gc.log(level, event, fields)` writes the Lua log and the host's bounded recent-log buffer; completed run results remain available in status.

## Editing, restart and checkpoints

`script_get` returns source, declared inputs/actions, module filenames and module sources. `script_save` updates the entry source and metadata while preserving an existing filename and module bindings. A new entry cannot overwrite another registered entry's filename. The registry validates IDs, paths and random-event ownership before publication; source and manifest writes use atomic file replacement.

External edits become available through explicit manifest reload and the next start/restart. Reloading the list does not rewrite a running VM. Start compiles the candidate descriptor and validates inputs before replacing the active run, then activates its root coroutine. Restart begins new Lua control flow and input authority; old pending callbacks cannot advance it.

Checkpoints hold non-negative integers scoped to account and script:

```lua
local step = gc.checkpoint("verified_step")
gc.checkpoint("verified_step", 2)
gc.clear_checkpoint("verified_step")
```

They persist under `~/.runelite/genericclient/checkpoints/` across Stop and restart. Lua owns their interpretation, validates them against current game state, and clears obsolete entries. They do not serialize coroutine stacks or replace observed arrival/quest state.

## Operator REPL

`lua_eval` accepts a function body and returns its value, logs, status and current game tick. Globals survive between evaluations; locals belong to one invocation. The REPL has one outstanding evaluation, and reset requires it to be idle.

```lua
sample_count = (sample_count or 0) + 1
return { samples = sample_count, player = gc.read("player") }
```

Operator actions and phases are plain by default even if they call `gc.activity`. An explicit `humanize = true` on an await or phase enables the behavior policy. A manually started standalone script still uses its declared policy; a cooperative `script_action` preserves that standalone run's origin.

```lua
return gc.await {
  action = { type = "npc.interact", name = "Banker", action = "Bank", within = 10 },
  activity = "travel",
  humanize = true,
}
```

## Sandbox and execution limits

Each script has a native Lua state with base, string, table, math and UTF-8 facilities. The host privately retains the coroutine and instruction hook. Scripts cannot access Java bindings, filesystem/process libraries, package loading, dynamic code loading or the debug/coroutine controls removed by the sandbox.

Source initialization has a 100 ms wall-time budget; each resumed script slice has 20 ms. The hook checks every 1,000 Lua instructions. Catching the hook error in Lua cannot convert an exhausted slice into success: the host checks the exhausted flag after the native call. Long-running algorithms must yield between bounded slices. Native heap allocation uses Lua's normal allocator.

Snapshots, semantic receipts and explicit checkpoints are the runtime's state interfaces. Production diagnostics use bounded state and normal client logs. This workflow does not create video or JSONL gameplay recordings.

## Verification

```bash
xvfb-run -a ./gradlew --offline qualityReport scriptCatalogAudit routeAudit pmdRouteAudit
```

`scriptCatalogAudit` uses the actual registry and Lua VM against the external catalog, with an isolated host and no gameplay actions. `-PscriptCatalog=/path/to/GenericClientScripts` selects another checkout. The catalog's `python3 tools/validate.py` checks syntax, literal policy contracts and Lua behavior scenarios.

Native tests cover module save/reload, sandbox limits, immutable observations, plain operator origin, typed requests, checkpoints, intent nesting/unwinding, emergency pauses and stale input callbacks. Catalog tests verify that short sequences share scopes while movement and training stay outside them. MCP tests preserve nested journey and intent receipts over the bridge. Loaded artifact verification and fresh live receipts remain separate acceptance steps.
