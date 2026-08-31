# MCP and Lua control

GenericClient exposes the live RuneLite client to a local MCP server. The MCP
process uses standard input/output with Codex and forwards tool calls to the
plugin's loopback bridge.

```text
Codex
  -> GenericClient MCP server (stdio)
      -> http://127.0.0.1:17343/rpc
          -> Lua host
              -> snapshots, behavior controller, walker, and synthetic input
```

RuneLite must be running with GenericClient loaded. The dashboard and MCP tools
share the same Lua host and script registry.

## Install the MCP server

From the repository:

```bash
cd mcp
npm install
```

On this Windows + WSL installation, register the server with Windows Node so it
can reach RuneLite's Windows loopback address:

```bash
codex mcp add genericclient -- \
  "/mnt/c/Program Files/nodejs/node.exe" \
  '\\wsl.localhost\Ubuntu\home\user\GenericClient\mcp\src\server.mjs'
```

When Codex and RuneLite run on the same operating system, use that system's
`node` command and local path instead. `GENERICCLIENT_URL` optionally overrides
the default `http://127.0.0.1:17343` bridge address.

Confirm the saved entry:

```bash
codex mcp get genericclient
codex mcp list
```

Start a new Codex session after adding the server. Local Codex clients share
their MCP configuration through `~/.codex/config.toml`, as described by the
[official OpenAI MCP documentation](https://developers.openai.com/codex/extend/mcp).

If the RuneLite setting **MCP bridge port** changes, set `GENERICCLIENT_URL` to
the same port in the MCP server configuration.

## MCP tools

| Tool | Purpose |
| --- | --- |
| `client_status` | Read player position, game state, Lua/scripts, random-event state, mouse profile, and recent logs. |
| `client_screenshot` | Return the next fully rendered RuneLite game canvas as a PNG image. |
| `account_snapshot` | Read one pinned frame of skills, items, bank state, quests, GE offers, and known cash. |
| `account_note_get` | Read the Notes text stored in the bound RuneLite profile. |
| `account_note_set` | Replace that note with an updated goal and verified progress ledger. |
| `behavior_profile` | Read the deterministic human-readable profile and numeric traits. |
| `behavior_status` | Read the current break, countdown, long pressure, and break counts. |
| `behavior_end_break` | Manually end the active break, matching the X on the in-client banner. |
| `random_event_status` | Read the latched owned random event, NPC snapshot, solver, and attention state. |
| `random_event_acknowledge` | Mark the alert as seen without releasing the automation block. |
| `random_event_complete` | Release a solved event and optionally restart the interrupted manual script. |
| `automation_status` | Read schedules, rule truth/reasons, cooldowns, selection, and the active lease. |
| `automation_config_get` | Read the complete active-account rule configuration. |
| `automation_config_set` | Validate and atomically replace that configuration. |
| `automation_enable` | Persistently enable or disable scheduled execution. |
| `automation_pause` | Pause scheduling and stop only a rule-owned script. |
| `automation_resume` | Resume evaluation after a pause or manual Stop. |
| `automation_reload` | Reload the active account's rule and state files. |
| `session_logout` | Deliberately log out through visible widgets with synthetic input. |
| `session_login` | Restore the active Jagex Launcher session and enter the world. |
| `lua_eval` | Execute an ad-hoc Lua snippet and receive its returned value. |
| `lua_repl_reset` | Clear globals created by previous REPL calls. |
| `script_list` | List scripts registered in the manifest. |
| `script_get` | Read one script's metadata, declared inputs, and source. |
| `script_save` | Write a complete Lua file and register or update its manifest entry. |
| `script_run` | Start a registered script by id with optional input values. |
| `script_stop` | Stop the active standalone script. |
| `script_action` | Queue one action declared by the running script. |
| `script_reload_manifest` | Reload the manifest after external file edits. |

Start with `client_status` so coordinates and login state come from the current
client rather than assumptions. It includes the 20 newest structured chat and
system messages, and a completed script's structured return value remains on
`lua.active.result` for diagnosis. Call `account_snapshot` before planning account work. Use
`client_screenshot` whenever those structures do not fully explain visible
world, camera, widget, dialogue, or menu state. If the bank state is `unknown`,
open the bank once before treating the cash or supply inventory as complete.

`client_status.random_event` is GenericClient's own latched event state. When
`attention_required` is true, inspect it with `random_event_status`, use the REPL
and screenshot surface to solve the event, then call `random_event_complete`
only after observing completion. `random_event_acknowledge` does not release the
block. Manifest-registered solvers can run automatically; the complete lifecycle
and registration example are in [`random-events.md`](random-events.md).

## Scheduled automation

Scheduled rules use named time windows and three-valued account facts. The
following configuration runs AIO Melee on weekdays during the specified window
while Strength remains below 30:

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

Pass that object to `automation_config_set`, then inspect
`automation_status`. An idle rule starts only when its complete condition is
`true`; `unknown` facts block a new start and include a reason. In particular,
`cash.known_total_value` remains unknown until bank wealth has been observed.
Manual scripts own the slot ahead of scheduled work, and manual Stop persists a
pause until `automation_resume` is called.

The complete cash-rule example, operators, overnight windows, account-specific
paths, sticky ownership, and the requirement that GenericClient itself remain
running are documented in
[`automation-scheduling.md`](automation-scheduling.md).

## Lua REPL

`lua_eval` accepts the body of a Lua function. Use `return` to send a value back
to MCP:

```lua
return gc.read("player")
```

```lua
return gc.read("npcs", {
  within = 12,
  limit = 20,
})
```

Visible widgets are copied into the same immutable tick frame. Query packed
widget IDs instead of reading mutable client objects:

```lua
return gc.read("widgets", {
  ids = { 1703958, 1703959, 1703960 },
  limit = 3,
})
```

Rows include packed/group/component IDs, dynamic index, cleaned text/name,
actions, item/model/sprite IDs, and canvas bounds. A script may click a packed
ID or one of its dynamic children through the shared synthetic input path:

```lua
return gc.await {
  action = { type = "ui.click", widget_id = 1703941 },
  breaks = false,
}
```

```lua
return gc.await {
  action = {
    type = "ui.click",
    widget_id = 20054020,
    widget_index = 12,
  },
  breaks = true,
}
```

Numbered game menus can be selected without moving the operating-system cursor:

```lua
return gc.await {
  action = { type = "ui.key", key = "2" },
  breaks = true,
}
```

The standard 5x5 sliding-puzzle adapter returns the normalized board, blank
position, dynamic widget ID, and a legal move sequence:

```lua
return gc.read("sliding_puzzle")
```

NPC rows include separate `in_scene`, `clickable`, and `line_of_sight` fields,
plus canvas geometry and health state. Scripts can require the interaction
facts they actually need:

```lua
return gc.read("npcs", {
  where = {
    name = "Thief",
    clickable = true,
    line_of_sight = true,
    dead = false,
  },
  action = "Attack",
  within = 15,
})
```

Recent messages are tick-stamped and newest-first. The bounded local buffer
includes game feedback, level-ups, unlocks, NPC/random-event speech, public and
private chat, friends chat, clan chat, broadcasts, and notifications. Examine
spam and RuneLite console messages are excluded:

```lua
local before = gc.read("runtime").game_tick
-- perform an interaction
return gc.read("messages", {
  since_tick = before,
  contains = "reach",
  limit = 10,
})
```

`scene` compares one adjacent world edge against the pinned live collision
frame. It reports the raw flags and human-readable blockers for both tiles:

```lua
return gc.read("scene", {
  from = { x = 3011, y = 3197, plane = 0 },
  to = { x = 3011, y = 3196, plane = 0 },
})
```

The core walker uses this same frame as the local authority over its bundled
global map. It may execute an accessible same-plane traversal object on the
exact oriented edge, then verifies the edge or object changed before resuming.
Explicit locked-door feedback or an unchanged obstacle returns an `unreachable`
walk receipt.

`instance` maps a canonical template tile into the current dynamic scene. A
quest can keep one authored safespot while the server assigns a different live
base and chunk rotation on every entry:

```lua
return gc.read("instance", {
  template = { x = 2598, y = 3162, plane = 0 },
})
```

The result reports whether the scene is instanced and returns every matching
live WorldPoint. Fight Arena uses this to map the Bouncer safespot and exit door
after re-entry.

The same account frame exposed by `account_snapshot` is available inside Lua:

```lua
local account = gc.read("account")
return {
  attack_xp = account.skills.attack.xp,
  inventory = account.inventory.items,
  bank_state = account.bank.state,
  waterfall = account.quests.waterfall_quest.state,
  offers = account.grand_exchange.offers,
  known_cash = account.cash.known_total_value,
  cash_complete = account.cash.complete,
}
```

The bundled Account Auditor uses this frame and stays idle after its first
snapshot. Run it from Automations, then use its **Refresh** action whenever a
new receipt is needed. It does not start automatically.

The REPL keeps global variables between calls:

```lua
sample_count = (sample_count or 0) + 1
return sample_count
```

Locals belong only to the current call. `lua_repl_reset` clears the persistent
state.

REPL code can wait for ticks and semantic actions:

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

return result
```

`walk.to` enables run by default once at least 10% energy is available. Use
`run = false` only for a route or phase that explicitly needs to conserve it.
The core toggles and verifies the visible run orb without repeatedly clicking
an unchanged state.

NPC actions require a name or numeric ID plus the exact menu option. Supplying
both makes the ID authoritative and uses the name as an additional check. The
nearest matching NPC inside the radius is used, and the receipt states whether
the synthetic cursor used a left-click or context menu. Spell actions reject
`npc_no_line_of_sight` before moving the cursor:

```lua
return gc.await {
  action = {
    type = "npc.interact",
    id = 3996,
    name = "Witch's experiment",
    action = "Attack",
    within = 12,
  },
  breaks = false,
}
```

The same request shape covers exact object, inventory, widget, dialogue,
banking, GE, and spell interactions. Lua supplies semantic facts rather than canvas
coordinates or RuneLite menu opcodes:

```lua
local result = gc.await {
  action = {
    type = "combat.cast",
    spell = "wind_strike",
    npc_name = "Goblin",
    within = 15,
  },
}
```

Equipped items use the same semantic menu seam. For example, Waterfall removes
Glarial's amulet before using it on the statue without encoding the equipment
tab or amulet-slot coordinates:

```lua
return gc.await {
  action = { type = "equipment.interact", id = 295, action = "Remove" },
  breaks = false,
}
```

`combat.set_autocast` opens Combat Options, selects the requested offensive
spell, and verifies the client's autocast varbit. A script should use its
`unsupported` receipt to choose manual casting; other failures remain explicit
instead of silently changing the training method:

```lua
local autocast = gc.await {
  action = { type = "combat.set_autocast", spell = "water_strike" },
  breaks = false,
}
```

Protection prayers use a semantic setter and verify the copied prayer varbit:

```lua
local protection = gc.await {
  action = {
    type = "prayer.set",
    prayer = "protect_from_missiles",
    enabled = true,
  },
  breaks = false,
}
```

The initial surface supports Protect from Magic, Missiles, and Melee. It rejects
insufficient real Prayer levels or depleted current Prayer points instead of
clicking a disabled widget.

Composite workflows return their individual click receipts. `bank.loadout`
verifies an exact inventory allowlist; `ge.buy` preserves unrelated offers and
rejects any maximum spend that would cross the configured cash reserve. A
same-item zero-fill buy with stale quantity or price is canceled, collected,
and replaced with the requested JIT offer; partial fills and conflicting
completed offers remain diagnostic stops.

Large unstackable purchases can set `collect_mode` to `bank`; `notes` and the
default `items` mode are also supported. Bank collection verifies that the
requested item left the offer panel, then collects any coin refund and requires
the offer slot to clear. Resuming an already-funded matching offer does not
require enough loose coins to fund it a second time.

Combat scripts can arm the framework's tick-priority emergency guard while
keeping item policy in Lua:

```lua
gc.await {
  action = {
    type = "safety.configure",
    minimum_hitpoints = 3,
    consumables = {
      { id = 1993, action = "Drink", heal_amount = 11 },
    },
    continue_after_consumable = true,
    escape = { x = 3225, y = 3218, plane = 0, within = 3 },
  },
  breaks = false,
}
```

Remote areas that cannot be escaped by walking can use an exact carried item
and its dialogue destination. Emergency dialogue choices bypass ordinary
reading pace and all discretionary behavior:

```lua
gc.await {
  action = {
    type = "safety.configure",
    minimum_hitpoints = 4,
    consumables = {
      { id = 379, action = "Eat", heal_amount = 12 },
    },
    continue_after_consumable = true,
    allow_overheal = true,
    escape = {
      type = "inventory_dialogue",
      item_id = 2564,
      action = "Rub",
      choice = "Castle Wars Arena",
      x = 2440,
      y = 3089,
      plane = 0,
      within = 10,
    },
  },
  breaks = false,
}
```

Each approved consumable declares its exact heal amount. By default,
GenericClient ends any active break and eats as soon as a food's complete heal
fits, without stopping the script. Below 30% max HP it forces a food even when
that low-max-HP case must overheal. If no approved food can be used at that
forced-heal point or the hard HP floor, it stops the script and uses the
optional escape. A configured escape now starts on any failed approved-food
dispatch, before sustained damage can carry the player down to the hard floor.
Set `continue_after_consumable=false` for an encounter that
must stop after a threshold heal. `allow_overheal=true` remains available for
an encounter-specific hard floor above the automatic 30% rule. Use
`safety.clear` when a script no longer wants the guard armed.

`mouse.offscreen` parks the synthetic cursor at the current account profile's
stable idle edge without rolling another break:

```lua
return gc.await {
  action = { type = "mouse.offscreen" },
  breaks = false,
}
```

Each composite client interaction captures the coroutine's activity. Semantic
actions automatically classify travel, dialogue, combat, banking, and trading;
scripts can describe a wider state explicitly:

```lua
gc.activity("skilling")
```

For a one-action override, put the activity on the await envelope:

```lua
gc.await {
  activity = "banking",
  action = { type = "object.interact", id = 4483, action = "Use" },
}
```

Travel and skilling allow independent break and cursor-release rolls. Combat,
banking, trading, and dialogue allow neither. A time-sensitive task can bypass
all discretionary behavior for an interaction:

```lua
return gc.await {
  action = { type = "walk.random" },
  breaks = false,
}
```

Major state transitions can request the profile's heavier evaluation:

```lua
return gc.phase("banking.complete")
```

An activity transition can be atomic with the phase:

```lua
return gc.phase("route.start", { activity = "travel" })
```

`gc.read("behavior")` returns the same structured state exposed by
`behavior_status`, including the account-seeded or manually overridden typing
speed in words per minute.

## Standalone scripts

Standalone scripts live in:

```text
~/.runelite/genericclient/scripts/
```

`manifest.json` is the registry shown on the dashboard's Automations page and returned by
`script_list`:

```json
{
  "schema": "genericclient_scripts",
  "scripts": [
    {
      "id": "where-am-i",
      "name": "Where am I?",
      "description": "Log the current player snapshot once.",
      "file": "where-am-i.lua"
    }
  ]
}
```

For a larger script, add a `modules` object to its entry and import those names
from the entry file:

```json
"modules": {
  "config": "aio-example/config.lua",
  "supplies": "aio-example/supplies.lua"
}
```

```lua
local config = gc.require("config")
local supplies = gc.require("supplies")
```

`script_get` returns the entry `source`, module file map, and `module_sources`.
Modules are cached per run and can require other modules declared by the same
manifest entry. They cannot access arbitrary files or native Lua packages.

The matching `where-am-i.lua` is:

```lua
return {
  run = function(input)
    gc.await { event = "game.tick" }
    gc.log("info", "player", gc.read("player"))
  end,
}
```

Every file returns one descriptor table with a `run(input)` function. Inside
that function, scripts use only:

```lua
gc.read(subject, query)
gc.await(request)
gc.log(level, event, fields)
gc.activity(name) -- omit name to read the current descriptor
gc.state(name) -- task-specific script state; omit name to read it
gc.phase(name, options)
gc.overlay(rows)
gc.next_action()
gc.require(name) -- only modules declared by this manifest entry
```

A script can declare a dropdown without adding Java UI code:

```lua
return {
  inputs = {
    {
      id = "destination",
      label = "Destination",
      type = "choice",
      default = "grand_exchange",
      choices = {
        { value = "grand_exchange", label = "Grand Exchange" },
        { value = "varrock_center", label = "Varrock Center" },
      },
    },
  },
  actions = {
    { id = "refresh", label = "Refresh" },
  },
  run = function(input)
    gc.overlay {
      { label = "Destination", value = input.destination },
    }
    local action = gc.next_action()
    gc.log("info", "selected", { destination = input.destination })
  end,
}
```

`script_get` returns the input and action metadata. `script_run` accepts an `inputs`
object such as `{ "destination": "varrock_center" }`. Missing values use the
declared defaults; unknown ids and values outside the declared choices fail
before the script starts. The dashboard renders the same metadata and passes
the selected values through the same validation path.

The Active Script page shows the resolved configuration and immediate Restart
and Stop controls. Descriptor actions become compact buttons and are also
available through `script_action`. They enter a bounded queue; the Lua root
coroutine consumes them cooperatively with `gc.next_action()` at a safe point.

Every running standalone script automatically gets a compact RuneLite overlay
with its display name and wall-clock runtime. `gc.overlay` may add up to four
label/value rows. Passing `nil` clears those rows, and the whole overlay hides
when the script stops or completes.

The easiest programmatic path is `script_save`, which writes both the Lua file
and manifest entry. Its optional `random_events` integer array registers that
script as the unique solver for those supported event NPC IDs. For manual
editing, add the file and manifest row, then
press **Reload list** in Automations or call `script_reload_manifest`.

Only one standalone script is active at a time. A manual start replaces the
current run; a scheduled start is idle-only and never replaces a manual run.
The REPL is separate, so short interactive queries can run while a diagnostic
standalone script is waiting on ticks.

## Developer checks

```bash
./gradlew test
cd mcp
npm test
```

The Java tests cover the manifest, persistent REPL, and loopback RPC. The Node
tests cover bridge errors and MCP tool registration/forwarding.
