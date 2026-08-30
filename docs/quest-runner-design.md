# Quest Runner design

Status: four modular quest definitions are live-proven end to end: Witch's
House, Waterfall Quest, Tree Gnome Village, and Fight Arena. The Grand Tree is
live-proven through its lumber-order checkpoint at varp 90.

## Decision

Quest Runner is one standalone Lua script whose descriptor exposes a quest
dropdown and cooperative controls. Lua owns quest facts and phase selection.
Java owns immutable client observations and reusable synthetic interactions.
No quest name, item ID, object ID, coordinate, dialogue answer, or phase order
belongs in the Java plugin.

The existing scripting interface remains the seam:

```lua
gc.read(subject, query)
gc.await(request)
gc.log(level, event, fields)
gc.overlay(rows)
gc.next_action()
```

New subjects and action types deepen that interface; they do not add new global
functions. A script author should learn the same five operations whether a
quest has ten steps or one hundred.

## Ownership

```text
quest-runner.lua
  dropdown, active-quest selection, overlay, orchestration
  |
  +-- quest-runner/shared/
  |     state, preparation, travel mechanics used by multiple quests
  |
  +-- quest-runner/witchs_house/
  |     config, reducer, quest interactions, garden, combat, completion
  +-- quest-runner/waterfall/
  |     config, reducer, navigation, preparation, ritual, tomb
  +-- quest-runner/tree_gnome_village/
  |     config, reducer, interactions, navigation, combat, runner
  +-- quest-runner/fight_arena/
  |     config, reducer, interactions, navigation, combat, runner
  `-- quest-runner/the_grand_tree/
        config, reducer, interactions, navigation, quest, runner
                         |
                         | gc.read / gc.await
                         v
GenericClient snapshots and interaction modules
  vars, player vitals, objects, dialogue, item containers
  entity menu selection, item use, dialogue, bank loadout, GE offer
                         |
                         v
RuneLite client thread + recorded synthetic input
```

The deletion test is deliberate: deleting Quest Runner should move quest logic
back into every standalone quest script, while deleting a Java interaction
module should move menu geometry, context selection, event matching, timeout,
and behavior receipts back into every caller. Both modules therefore earn
their depth.

## Quest definition interface

Each quest folder owns its config, pure phase reducer, interactions, recovery,
and quest-specific subsystems. The root descriptor maps the selected dropdown
value to those modules. Adding a quest means adding one cohesive folder and
manifest module entries, not adding quest facts to Java or growing one
cross-quest Lua file.

`resolve(state)` is pure. It returns one phase ID from normalized quest state,
raw quest varps, player zone, inventory, equipment, cached bank contents,
dialogue, and nearby objects. Tests call this interface with table fixtures.
The runtime never increments a local step counter as proof that the game
accepted an action.

Each phase declares:

- a stable ID and compact overlay label;
- whether ordinary breaks are allowed;
- a bounded action function;
- a postcondition and game-tick timeout;
- whether `stop_safely` may terminate immediately or must first reach a safe
  checkpoint.

After every action, login, displacement, or timeout, the runner reads a fresh
pinned frame and calls the reducer again.

## Snapshot subjects

Only observations required by the first two quests are added:

| Subject | Result |
| --- | --- |
| `vars` | Requested raw varp values from the tick's copied varp array. IDs are supplied by Lua and bounded to 32 per read. |
| `objects` | Nearby scene objects filtered by ID/name/action/radius, including kind, WorldPoint, distance, and live actions. Same-ID objects at different points remain distinct. |
| `dialogue` | Closed, Continue, or choice state; visible text, speaker, and ordered option text/index. |
| `instance` | Map one canonical template WorldPoint into every matching tile in the current dynamic scene, preserving chunk rotation. |
| `player` | Existing fields plus current/max HP, run energy, run-enabled state, and world destination. |

Normalized quest completion remains `gc.read("quests")`. Raw varps route
in-progress phases but never prove completion.

## Semantic action types

| Action | Interface guarantee |
| --- | --- |
| `object.interact` | Re-resolve an object by ID and optional WorldPoint, validate its live action, face it once if off-camera, use synthetic left/context click, and return the observed menu event. |
| `item.interact` | Re-resolve an inventory slot by item ID and invoke a named action such as `Read`, `Wear`, `Eat`, or `Rub`. |
| `equipment.interact` | Open Equipment, re-resolve the exact worn item, and invoke a semantic action such as `Remove`. |
| `item.use_on_object` | Select `Use` on the requested inventory item, then resolve and click the exact object ID/WorldPoint. Both clicks receive behavior receipts unless `breaks=false`. |
| `dialogue.continue` | Click the currently visible Continue surface; reject if the dialogue is a choice. |
| `dialogue.choose` | Click an exact visible option string and return its index/text. No substring-first or fixed-index fallback. |
| `bank.loadout` | With a bank open, deposit inventory/equipment as requested, withdraw exact item quantities, verify the resulting allowlist and free slots, and close. |
| `ge.buy` | Preserve existing offers, enforce the configured cash reserve against known cash, place one bounded buy offer, collect it, and return item/quantity/unit-price/reserve receipts. |
| `safety.configure` | Arm the framework guard with a hard HP floor, ordered consumables and heal amounts, automatic exact-fit healing, forced healing below 30% max HP, and an escape fallback. |
| `safety.clear` | Disarm the current emergency guard. |

Object, NPC, inventory, and widget clicks should share one internal menu-input
implementation. Target resolvers differ; hover verification, context-menu row
selection, `MenuOptionClicked` matching, cancellation, and behavior ordering do
not. This is an internal seam with a live RuneLite adapter and deterministic
test adapter, not another Lua-facing interface.

Lua implements `wait_until` by awaiting game ticks and rereading snapshots.
Lua implements a critical section by sending `breaks=false` on every contained
interaction. Those are composition rules over the existing interface, not new
Java actions.

## Receipts and postconditions

Every mutating action returns:

- `status`: `dispatched`, `complete`, `unchanged`, `rejected`, or `timed_out`;
- the resolved target/item/widget identity;
- dispatch path and actual click count;
- behavior receipts for every composite interaction;
- an action-specific observed result, never a claim that an unobserved server
  transition succeeded.

Quest phases then prove success separately: zone change, inventory delta, raw
varp change, dialogue change, HP increase, equipment change, or normalized
quest completion. A dispatched click without its phase postcondition is a
retryable or terminal phase failure according to the quest definition.

## Banking and purchases

`bank.loadout` takes an allowlist rather than a tomb denylist. For Glarial's
Tomb the quest definition requests no equipment and only pebble, approved food,
and explicit jewellery. If an amulet is already owned, the observed-state
loadout retains it and skips reacquiring it. The bank module rejects extra
items before the tombstone can be targeted.

`ge.buy` is just-in-time. It accepts item ID, quantity, a maximum unit price,
and minimum cash reserve. It never cancels or replaces an unrelated offer. An
exact matching zero-fill buy below the requested ceiling may be aborted,
collected, and recreated in the same slot at that ceiling. The first
two quest definitions may buy only their next phase's missing quantities. A
price or reserve failure stops with a receipt for review.

## Safety model

- Auto-retaliate is disabled before quest travel and rechecked after login.
- Each combat script arms `safety.configure` with its own hard floor, approved
  consumables, and optional safe destination. The Java guard preempts breaks and
  active input. It normally chooses a heal that fits, forces an approved heal
  below 30% max HP even when it overheals, and lets the Lua fight continue after
  a successful heal. It stops and escapes only when food is unavailable at the
  forced point or hard floor.
- Safe travel may use normal behavior rolls. Hostile rooms and irreversible
  interaction groups use `breaks=false` until a safe checkpoint.
- The runner checks HP/run/food immediately before every hostile transition and
  after every tick while exposed.
- A cooperative stop inside a critical section first exits or reaches the next
  declared safe tile. Hard host Stop still cancels active input immediately.
- Unknown quest stages, missing local player, death, lost dialogue, an
  unexpected zone, or an unverified item delta stop with diagnostics. They do
  not trigger blind repeat clicks.
- Waterfall strict mode requires at least 15 current/max HP for Glarial's Tomb
  and at least 12 for the falls. The current account reaches this through the
  Witch's House definition before Waterfall; no low-HP override is enabled.

## Rejected shapes

- A Java class per quest duplicates quest facts and makes RuneLite updates
  require plugin releases.
- A generic declarative quest DSL attempts to solve unknown future quests before
  two concrete definitions have exercised the seam.
- Direct menu invocation bypasses the recorded cursor and produces no visual or
  interaction receipt.
- Fixed widget coordinates and menu indexes are brittle across layouts and live
  action ordering.
- One long imperative Waterfall coroutine cannot resume safely after a restart.

## Acceptance state

The framework interaction, snapshot, registry, walker, instance-mapping, and
Lua-host surfaces are exercised by the four completed quests. Fight Arena added
the strongest recovery receipt: after a death at Bouncer, the runner restocked,
used the arena's re-entry dialogue, mapped the canonical safespot into the new
private scene, killed Bouncer, crossed the second Khazard instance through its
`Quick-escape` door, and reached normalized `finished` state. The terminal
account snapshot recorded Attack 40, Hitpoints 28, Magic 32, and Thieving 14.

The Grand Tree added live evidence for resumable open dialogue, low-combat quest
confirmation, static-map door staging, Castle Wars return routing, repeated
translation choices, same-ID ladder approaches, hostile-floor safety
preservation, repeated same-varp location reduction, and post-break semantic
target reacquisition. Shipyard proof adds a dialogue-gated object transition,
an in-yard walk, resumable NPC dialogue, and item-plus-varp completion evidence.
Its current stable stop is varp 90 with the lumber order verified.

Quest-specific evidence and exact phase tables live in
[`witchs-house-quest-runner.md`](witchs-house-quest-runner.md) and
[`waterfall-quest-runner.md`](waterfall-quest-runner.md).
