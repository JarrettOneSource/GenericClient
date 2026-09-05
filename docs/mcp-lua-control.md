# MCP and Lua control

GenericClient exposes the live RuneLite client to a local MCP server. The MCP
process uses standard input/output with Codex and forwards tool calls to the
plugin's loopback bridge.

```text
Codex
  -> GenericClient MCP server (stdio)
      -> configured URL or healthy instance descriptor
          -> http://127.0.0.1:<client-port>/rpc
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
the default `http://localhost:17343` bridge address.

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

The direct URL remains the zero-configuration path for one normally launched
client: the first process binds `127.0.0.1:17343`. When another normal RuneLite
process finds that port occupied, GenericClient binds an ephemeral port and
publishes it in the shared instance registry instead of failing startup.

For a Harness-managed or multi-client setup, configure the MCP process with:

```text
GENERICCLIENT_INSTANCE_DIRECTORY=/home/user/.runelite/genericclient/instances
GENERICCLIENT_INSTANCE_ID=main-character
```

The MCP bridge revalidates that descriptor and resolves its current loopback
endpoint on every tool call, so it survives a client restart. With exactly one
healthy descriptor, `GENERICCLIENT_INSTANCE_ID` may be omitted. With several,
run separately named MCP registrations with one explicit instance ID each;
there is never a silent global active client.

## MCP tools

| Tool | Purpose |
| --- | --- |
| `client_status` | Read player position, game state, Lua/scripts, random-event state, mouse profile, and recent logs. |
| `client_screenshot` | Return the next fully rendered RuneLite game canvas as a PNG image. |
| `scene_highlight` | Replace the MCP-owned scene markers for tiles, NPCs, objects, ground items, players, or the mouse tile. |
| `scene_clear` | Clear MCP-owned scene markers without touching script markers or the mouse-tile setting. |
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

Use `scene_highlight` before `client_screenshot` when the target is hard to see.
Each call replaces the previous MCP-owned marker set, so one screenshot can show
several related targets without leaving stale highlights behind:

```json
{
  "markers": [
    { "tile": { "x": 2746, "y": 2799, "plane": 0 }, "label": "Safe passage" },
    { "npc_id": 5270, "label": "Monkey's Aunt", "color": "#ff5c7a" },
    { "object_id": 4749, "label": "Banana tree" }
  ]
}
```

Call `scene_clear` when the screenshot investigation is finished. The Settings
page's **Show mouse tile** option is independent and persists through RuneLite's
GenericClient configuration. Mouse-tile markers are hidden while the synthetic
mouse is moving so automation does not obscure the tile under the physical cursor;
all other scene markers remain visible.

`client_status.death_forensics` reports the latest automatic death capture.
GenericClient retains the final 20 game ticks and writes a JSON timeline plus
a rendered PNG under `~/.runelite/genericclient/forensics/` when HP reaches
zero. The report includes HP and position history, poison value, nearby NPC IDs,
combat levels and interaction targets, script/global state, safety/break state,
and recent chat. `probable_attackers` lists NPCs observed interacting with the
player before death.

`client_status.combat_guard` reports observed attackers, damage classification,
protection prayer and its owner. The policy resolver keeps the declared activity
and exposes `declared_policy`, `effective_policy` and `policy_reasons`. A threat
or the bounded damage grace suppresses discretionary behavior when the declared
policy does not expect damage. Supported poison/venom evidence does not refresh
that grace; an unavailable or ambiguous frame does not establish safe state.

Use `gc.activity("combat", { breaks = true })` for repeatable combat that should
permit ordinary breaks while retaining fast mouse input, expected damage and
guard prayer ownership. `hazardous_travel` adds short walk-click refresh through
arrival. `skilling` assigns prayer ownership to the script. A script can also
set `prayer_owner = "script"` or disable `combat_prayer` through
`client.behaviors.configure` when it owns protection itself.

Expected damage changes discretionary policy only. Forced healing and emergency
escape retain their own thresholds. Guard-owned prayer/potion actions are
serialized, revoked on ownership changes and checked again before their late
callbacks can change state. See [behavior-system.md](behavior-system.md) for
preset fields, time-based pressure, cursor behavior and ownership precedence.

Emergency food has priority over prayer, potion, and ordinary script input. The
Lua action and walker stay paused until a game tick confirms an HP increase or
inventory decrease. Prayer changes are serialized and stale queued changes are
cancelled when emergency food starts. Prayer switching resumes on the first tick
after food is confirmed. An emergency escape rechecks current HP immediately
before it executes and is skipped when the emergency has already cleared.

Random events detected during `hazardous_travel` are recorded but do not cancel
movement or take input ownership. The record closes automatically when that NPC
despawns; normal random-event interruption remains unchanged outside hazardous
travel.

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

`lua_eval` accepts the body of a Lua function. Its actions and phases are plain
by default, including when `gc.activity` is declared. Set `humanize = true` on
an await or phase to opt into discretionary behavior. A manually started
standalone script continues to use its own declared policy.

The current scripting interface reports `gc.read("runtime").api_version == 3`
when a runtime frame is available. Use `return` to send a value back to MCP:

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
}
```

```lua
return gc.await {
  action = {
    type = "ui.click",
    widget_id = 20054020,
    widget_index = 12,
  },
}
```

Numbered game menus can be selected without moving the operating-system cursor:

```lua
return gc.await {
  action = { type = "ui.key", key = "2" },
}
```

Use `key = "SPACE"` for a keyboard Continue prompt whose message-box widget is
not retained in the next game-tick snapshot.

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
private chat, friends chat, clan chat, broadcasts, and notifications. Examine, console and unknown message types are excluded:

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

The core walker uses this frame as local authority over the bundled global map.
It matches a blocked edge against observed traversal objects, verifies the
crossing, and records failed edges in account memory before replanning. A locked
or exhausted door can lead to another route. Selected directed transports use
separate observed landing and conversation postconditions.

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

The GenericClientScripts Account Auditor uses this frame and stays idle after its first
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
    interrupt_on = { dialogue = true },
    avoid_tiles = {
      { x = 3208, y = 3423, plane = 0 },
    },
  },
  timeout = { game_ticks = 600 },
}

return result
```

When an awaited action exhausts its game-tick timeout, GenericClient cancels
the active client interaction before returning the `timed_out` receipt. A
timed-out walk, menu action, bank operation, or other input cannot continue in
the background and interfere with the next Lua action.

`walk.to` enables run by default once at least 10% energy is available. Use
`run = false` only for a route or phase that explicitly needs to conserve it.
The core toggles and verifies the visible run orb without repeatedly clicking
an unchanged state. `avoid_tiles` accepts up to 512 WorldPoints on any valid plane. The
pathfinder will not enter those tiles, including during replans; a player who
starts on one may still leave it. Scripts supply this list for walkable quest
hazards because collision alone cannot identify damaging floor tiles.

Use `interrupt_on = { dialogue = true }` when Lua owns dialogue handling during
a route. Interrupts precede arrival and new input. A dialogue match returns
`status = "interrupted", reason = "dialogue"`, the triggering `dialogue` frame,
and a single-use `continuation`. Selected transport conversations consume only
their own expected pages; foreign dialogue returns to Lua. The retired alias
is rejected.

`via` supplies required corridor points in order. `arrival_tiles` supplies
alternative allowed final tiles within the destination radius. An interrupted
journey can pass its token as `resume`, preserving observed via and transport
progress while Lua refreshes avoids and interrupt conditions. Tokens bind the
account and original destination/radius/via/arrival constraints. See
[walker-design.md](walker-design.md) for all predicates, limits and receipts.

`walk.click` is the lower-level single native click operation. Its receipt does
not establish arrival; Lua must observe the intended result when using it:

```lua
local click = gc.await {
  action = {
    type = "walk.click",
    destination = { x = 2746, y = 2799, plane = 0 },
  },
}
```

This dispatches exactly one projected tile click and returns its input receipt.
It does not verify arrival or click again; scripts should observe subsequent
game ticks and fail safely if the destination is not reached.

Cross-region preparation can use the standard-spellbook home teleport without
exposing tab or spell widget IDs:

```lua
return gc.await {
  action = { type = "travel.home_teleport" },
  timeout = { game_ticks = 80 },
}
```

The action returns `complete` only after observing the player in Lumbridge. If
the spell is unavailable or on cooldown, callers receive a rejected receipt
and should stop with `safe_transport_required`; they must not replace it with
an unarmed cross-region walk.

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

Utility spells that target inventory items use the same selected-widget seam.
The client opens the inventory after selecting the named spell and verifies the
exact item ID and optional slot before dispatching the cast:

```lua
local result = gc.await {
  action = {
    type = "spell.cast_on_item",
    spell = "superheat_item",
    item_id = 440,
  },
}
```

Opening the tabs, selecting the spell and clicking the item share one semantic
action boundary. Native substeps do not roll breaks. The receipt carries
`behavior_before` and `behavior_after` at the action boundary; the obsolete
`action_bundle` envelope has been removed.

Equipped items use the same semantic menu seam. For example, Waterfall removes
Glarial's amulet before using it on the statue without encoding the equipment
tab or amulet-slot coordinates:

```lua
return gc.await {
  action = { type = "equipment.interact", id = 295, action = "Remove" },
}
```

`combat.set_autocast` opens Combat Options, selects the requested offensive
spell, and verifies the client's autocast varbit. A script should use its
`unsupported` receipt to choose manual casting; other failures remain explicit
instead of silently changing the training method:

```lua
local autocast = gc.await {
  action = { type = "combat.set_autocast", spell = "water_strike" },
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
}
```

Inventory crafting uses exact item IDs and verifies both selection steps:

```lua
local strung = gc.await {
  action = {
    type = "item.use_on_item",
    item_id = 1759,
    target_item_id = 4022,
  },
}
```

Selected-item actions keep selection and target clicks inside one semantic
boundary, including `item.use_on_object` and `item.use_on_npc`. The host may
evaluate behavior after the operation's verified completion.

The initial surface supports Protect from Magic, Missiles, and Melee. It rejects
insufficient real Prayer levels or depleted current Prayer points instead of
clicking a disabled widget.

`dialogue.continue` and `dialogue.choose` accept `reading = false` for an exact
time-critical prompt. Set `keyboard = true` on `dialogue.choose` to select the
matching visible option with its number key even when the account profile uses
mouse dialogue. Ordinary dialogue remains paced by the account profile.

At the login screen, select an exact loaded world before `session_login`:

```lua
return gc.await {
  action = { type = "world.select", world = 302, members = true },
}
```

When RuneLite's world list is loaded, the action verifies the membership type.
At a fresh login screen it constructs the exact official world endpoint from
the requested ID and membership classification. Callers should still verify the
logged-in world from the resulting account state. The action never depends on
the optional RuneLite World Hopper plugin.

Composite workflows return their individual click receipts. `bank.loadout`
verifies an exact inventory allowlist; `ge.buy` preserves unrelated offers and
rejects any maximum spend that would cross the configured cash reserve. A
new buy starts at the displayed guide price. If it does not fill immediately,
the zero-fill offer is canceled and collected, then retried once with the
visible `+5%` price control. That offer waits for up to one minute. If it remains
completely unfilled, one final offer is placed at no more than 25% above the
guide price per item. `maximum_unit_price` remains a hard ceiling rather than
the initial offer price. Partial fills and conflicting completed offers remain
diagnostic stops; the final offer is never repriced again.

Large unstackable purchases can set `collect_mode` to `bank`; `notes` and the
default `items` mode are also supported. Bank collection verifies that the
requested item left the offer panel, then collects any coin refund and requires
the offer slot to clear. Resuming an already-funded matching offer does not
require enough loose coins to fund it a second time.

Every standalone run starts with GenericClient's automatic emergency
consumables, emergency escape, unexpected-combat prayer, and auto-retaliate
enabled. A Lua script that owns one or more of those decisions can disable them
for its own run:

```lua
gc.await {
  action = {
    type = "client.behaviors.configure",
    emergency_consumables = false,
    emergency_escape = false,
    combat_prayer = false,
    auto_retaliate = false,
  },
}
```

Omitted fields use their enabled defaults. The policy is reset before another
standalone script starts and immediately when the current script completes or
stops, so one script cannot leak disabled framework behavior into the next. If
a script directly enables a protection prayer, GenericClient releases that
specific prayer on exit; a prayer that was already active remains untouched.
Disabling emergency behavior does not clear its configured item policy; a
script may re-enable the behavior later in the same run with another configure
action.

Lua scripts do not need to identify potion doses to cure poison. The generic
consumable action checks the live poison varp, finds a carried standard or super
antipoison dose, drinks it only when poison is active, and waits for the poison
state to clear:

```lua
local cure = gc.await {
  action = { type = "consumable.cure_poison" },
}
```

It returns `unchanged` when the player is healthy or already protected,
`rejected` when poison is active but no supported antipoison is carried, and
`complete` only after the poison varp becomes non-positive. This action is
explicitly invoked by Lua; it is not an automatic background behavior.

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
}
```

Remote areas that cannot be escaped by walking can use an exact carried item
and its dialogue destination. Emergency dialogue choices bypass ordinary
reading pace and all discretionary behavior, then press the matching option's
number key immediately even when the behavior profile normally uses the mouse.
If stock OSRS leaves that choice open, GenericClient invokes the same exact
visible widget 25 milliseconds later without moving the cursor:

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
}
```

Each approved consumable declares its exact heal amount. By default,
GenericClient ends any active break and eats as soon as a food's complete heal
fits, without stopping the script. Below 30% max HP it forces a food even when
that low-max-HP case must overheal. After dispatching food, the guard waits for
an observed HP increase or inventory decrease before it can dispatch another;
an unchanged tick frame cannot consume the loadout repeatedly. If no approved
food can be used at that forced-heal point or the hard HP floor, it stops the
script and uses the optional escape. A configured escape starts on any failed
approved-food dispatch, before sustained damage can carry the player down to
the hard floor, and a verified arrival disarms that guard.
Emergency takeover cancels the current synthetic mouse or keyboard path before
dispatching food, so a canceled walk cannot leave the cursor busy and force an
unnecessary slower escape. The Lua host also suspends the current action receipt:
an input canceled for food cannot resume the coroutine and start another mouse
action. Once food is observed, that interrupted semantic action is retried.
Set `continue_after_consumable=false` for an encounter that
must stop after a threshold heal. `allow_overheal=true` remains available for
an encounter-specific hard floor above the automatic 30% rule. Use
`safety.clear` when a script no longer wants the guard armed.

`mouse.offscreen` parks the synthetic cursor at the current account profile's
stable idle edge without rolling another break:

```lua
return gc.await {
  action = { type = "mouse.offscreen" },
}
```

Each await captures the coroutine's activity and independent policy. If no
activity was declared, its action type selects a preset. A declaration remains
in force until the script changes it:

```lua
gc.activity("combat", { breaks = true })
```

For a one-action override, put activity and policy on the await envelope:

```lua
return gc.await {
  activity = "travel",
  policy = { breaks = false, cursor_release = "none", fidget = "none" },
  action = { type = "walk.random" },
}
```

Fields are independent. The former per-await `breaks` flag is rejected. Ordinary
micro pressure accrues with owned active time and is consumed at an eligible
completed action or phase, so more click boundaries do not generate more
per-click random trials. Long breaks also begin at completed actions or phases.
Entry checks can wait for an existing break but do not start a new one.

A registered script declaring `shared_equipment` can group a short gear change:

```lua
local equipment = gc.require("shared_equipment")
return gc.intent("gear.equip", function()
  local staff = equipment.equip(1387, "Wield")
  if staff.status ~= "complete" and staff.status ~= "unchanged" then return staff end
  return equipment.equip(1059, "Wear")
end)
```

Nested scopes share the outer boundary. Await receipts retain its `intent` name,
errors unwind it, and safety/takeover input remains independent. Keep long
approaches and training loops outside scopes.

Major state transitions can evaluate a phase, optionally changing the activity
first:

```lua
return gc.phase("route.start", { activity = "travel" })
```

The declared activity and script state stay separate. The resolver reports its
effective field overrides and reasons without changing the script's activity
label. Detailed presets and timing rules are in
[behavior-system.md](behavior-system.md).

When no standalone script or REPL is running, global state is idle, no combat,
random event, emergency, or client input is active, and the cursor is inside the
canvas for two stable ticks, GenericClient parks it at the profile's configured
off-screen edge. That idle park is preemptible: a newly started client action
cancels it and takes the cursor immediately.

`gc.read("behavior")` returns the same structured state exposed by
`behavior_status`, including the account-seeded or manually overridden typing
speed in words per minute. `dialogue_input_mode` is independently seeded per
account as `keyboard` or `mouse` and can be overridden in Settings. Keyboard
mode emits hotkey presses without typed characters, so dialogue digits cannot
leak into chat. Mouse mode preserves the cursor's horizontal lane across stacked
options and moves primarily up or down with only slight X deviation.

## Standalone scripts

Standalone scripts live in:

```text
~/.runelite/genericclient/scripts/
```

GenericClient creates an empty manifest on first use and never overwrites it.
The maintained catalog is published separately at
[GenericClientScripts](https://github.com/Pernasua/GenericClientScripts).

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

Every entry file returns one descriptor table with a `run(input)` function. Inside
that function, scripts use only:

```lua
gc.read(subject, query)
gc.await(request)
gc.log(level, event, fields)
gc.activity(name, policy) -- omit name to read the declared activity
gc.state(name) -- task-specific script state; omit name to read it
gc.phase(name, options)
gc.intent(name, fn) -- one boundary around a short sequence
gc.walk.to(options) -- destination journey with constraints and continuation
gc.overlay(rows, markers)
gc.next_action()
gc.require(name) -- only modules declared by this manifest entry
gc.checkpoint(key) -- read an account-and-script-scoped integer or nil
gc.checkpoint(key, value) -- atomically persist a non-negative integer
gc.clear_checkpoint(key)
```

Checkpoints live under `~/.runelite/genericclient/checkpoints/` and survive a
manual Stop, manifest reload, and client restart. They do not replace observed
game state: a script owns when a checkpoint is valid and when to clear it.

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

The optional second `gc.overlay` argument uses the same scene-marker format as
`scene_highlight`: `tile`, `npc_id`, `object_id`, `ground_item_id`,
`player_name`, or `mouse_tile=true`, plus optional `label` and `color` fields.

`script_save` writes the entry source and manifest metadata. Updating an
existing script preserves its filename and declared module bindings. A new ID
cannot overwrite another registered script's source file. The current catalog
and client use scripting API 3. Its optional `random_events` integer array registers that
script as the unique solver for those supported event NPC IDs. For manual
editing, add the file and manifest row, then
press **Reload list** in Automations or call `script_reload_manifest`.

Only one standalone script is active at a time. A manual start replaces the
current run; a scheduled start is idle-only and never replaces a manual run.
The REPL is separate, so short interactive queries can run while a diagnostic
standalone script is waiting on ticks.

## Developer checks

```bash
xvfb-run -a ./gradlew --offline qualityReport scriptCatalogAudit routeAudit pmdRouteAudit
cd mcp
npm test
```

For local client runs, `mcp/scripts/wait-client.ps1` exits `0` only for a
verified successful `COMPLETED` run. Exit `3` is random-event attention, `4` is
a Lua fault or an explicit failure result, `5` is a vanished/stopped run, `6` is HP zero or a new death-forensics
tick, and `2` is timeout. A death receipt includes the forensic report path.

The native catalog audit loads every registered descriptor and module through
the real Lua VM without gameplay actions. The catalog also runs
`python3 tools/validate.py` for syntax, policy lint and Lua behavior scenarios.
Native tests cover ownership, cancellation, registry reload and API contracts;
MCP tests preserve nested journey and intent receipts. Packaging, installation
and fresh live acceptance remain separate gates.
