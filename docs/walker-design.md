# Journey planning and execution

GenericClient plans a complete destination journey over bundled terrain, captured live collision and eligible directed transports. Lua supplies the destination, required corridor points, forbidden tiles and interruption conditions. Lua owns quest choices, supply handling and recovery decisions.

This describes scripting API 3. [Navigation map revisions](navigation-map-revisions.md) records artifact provenance, [navigation transitions](navigation-transitions.md) records the transport evidence, and [planner performance](planner-performance.md) records controlled measurements. Current source/test acceptance and pending live work are tracked in [behavior-framework-implementation.md](behavior-framework-implementation.md).

## Lua contract

```lua
return gc.walk.to {
  destination = { x = 3210, y = 3424, plane = 0 },
  within = 3,
  ticks = 600,
  run = true,
  via = { { x = 3205, y = 3420, plane = 0 } },
  avoid_tiles = { { x = 3208, y = 3423, plane = 0 } },
  interrupt_on = { dialogue = true },
}
```

The equivalent semantic action is `gc.await { action = { type = "walk.to", ... }, timeout = { game_ticks = ... } }`. `gc.walk.to` moves `activity`, `policy`, `humanize`, `ticks` and `timeout` onto the await envelope. Ordinary REPL calls are plain; an explicit `humanize = true` enables discretionary behavior.

| Field | Contract |
| --- | --- |
| `destination` | Required integer WorldPoint; coordinates 0–32767 and plane 0–3 |
| `within` | Chebyshev arrival radius, 0–10; native default 1 |
| `run` | Defaults to true; false conserves energy and disables an enabled run orb |
| `via` | Up to 512 ordered WorldPoints, potentially on different planes |
| `avoid_tiles` | Up to 512 forbidden WorldPoints on any valid plane |
| `arrival_tiles` | Optional nonempty allowed arrival set, up to 512 points on the destination plane inside its radius |
| `interrupt_on` | Typed predicates evaluated against each fresh frame |
| `resume` | Single-use continuation from an interrupted or unavailable journey |
| `ticks`, `timeout` | Await time budget; native walk default 600 game ticks |

The catalog's `shared_movement.walk(destination, within, options)` defaults to radius 3 and 900 ticks. Its `approach` helper checks the current distance before issuing the same journey API. These are script helper defaults, separate from the native defaults.

An avoided start tile can be left, but the route cannot enter avoided tiles. `arrival_tiles` restricts the entire final-target selection, including alternate targets during hazardous refresh. It represents alternatives to one another; `via` represents required progress in order. The retired `interrupt_on_dialogue` alias and unknown walk fields are rejected.

A via point is passed only when an observed player position comes within two tiles on its plane. A planned route alone does not pass it. Click reach is capped at the next unpassed via requirement, preventing the game's own local route from cutting across a required corridor.

## Interrupts and continuation

Interrupts run before arrival, route matching, recovery and input dispatch. The order is:

1. Named area membership.
2. Dialogue not owned by the selected transport.
3. Poison.
4. Missing inventory-name prefixes.
5. Inventory quantity minimums.
6. Boosted skill minimums.
7. Varbit equality.
8. Run-energy threshold.

```lua
local areas = gc.require("monkey_madness_areas")
local interrupts = {
  area = {
    name = "prison",
    bounds = areas.prison_bounds(),
  },
  dialogue = true,
  poisoned = true,
  missing_item = { "Antipoison" },
  inventory_below = { { id = 379, quantity = 4 } },
  skill_below = { prayer = 12 },
  varbit_equals = { { id = 25, value = 0 } },
  run_energy_below = 60,
}
```

The quest runner passes this table as `interrupt_on = interrupts`. Its declared area module supplies the actual prison rectangles with the safe exit removed. Areas may also be an ordered list of named areas, each containing rectangles. Area bounds and inventory names are supplied by Lua; the walker contains no prison or potion decisions. Numeric-ID conditions use arrays of rows, preserving IDs such as 0 or 1 through Lua table conversion. Skill thresholds use boosted values. Energy thresholds use percent, while `player.run_energy` remains hundredths of a percent.

A match returns `status = "interrupted"`, its predicate name as `reason`, and observed `detail`. A dialogue interruption includes the triggering dialogue frame. Required data that is unavailable returns `status = "unavailable"` with a specific snapshot reason. Input owned by the journey is revoked before either receipt returns.

Those receipts include a `continuation`. Resumption must keep the original destination, radius, ordered via list and allowed arrival set. It preserves observed via progress, an attempted transport's remaining steps and active-time budget, and failed transport groups. Lua may refresh avoids and interrupt conditions from current observations. The token binds the account and original request, is consumed once, and comes from a bounded cache of the latest 64 continuations. Unknown, changed and replayed tokens are rejected. A new request without a token starts fresh.

A supply handler should re-read state and rebuild its predicate before resuming. For example, an active stamina effect should first wait for effect expiry; an inactive effect should interrupt for the energy threshold only when the handler would actually drink. Reissuing a predicate that is already true creates an immediate interruption loop.

## Planning and live authority

The bundled collision archive contains 2,726 region entries; the door archive contains 773. Both were generated for game revision 240, from different caches. Startup reports their separate revisions and artifact hashes and compares each with an observed positive client revision. Matching revision numbers do not prove cache equality or current edge correctness.

The loaded scene supplies copied, immutable collision data and object/NPC footprints. Loaded tiles are authoritative locally. Unloaded or uninitialized scene cells use static knowledge; unknown scene-edge sentinels are not treated as solid terrain. A captured multi-tile object's footprint identifies the crossing, while its reported centre remains the exact native interaction target.

Planning runs on a dedicated worker. It uses eight-directional movement with cardinal cost 10, diagonal cost 14 and corner checks. Doorway crossings add 80 cost units. A complete plan has a 250,000-node search budget shared by its via legs. Via legs and selected transport edges remain attached to the final route.

Transport costs can make distant destinations cheap. The heuristic therefore computes a lower bound over eligible transport endpoints using reverse Dijkstra and unobstructed same-plane walking distances. It ignores collisions in that lower bound and includes transport costs, so a cheap connection cannot invalidate A* through an overestimated geometric distance. Deterministic tie ordering and primitive sparse search storage preserve reproducibility without allocating a node map for the whole world.

A local rejoin searches at most 4,096 nodes for nearby retained route points, within 32 tiles. It cannot join beyond the next via requirement or pending transport origin. If that connector fails, the planner searches a complete route again. Rejoin does not change the global search budget or remove valid large detours.

A queued plan captures its start, via progress, edge-memory view and eligible transports. Acceptance checks those observations again, including failed search results. A moved start, changed quest requirement, expired memory entry or changed via progress cannot install a stale result. A bounded connection from the latest start may reuse a valid suffix.

The planner operates on supplied world coordinates with a one-tile player footprint. `gc.read("instance", ...)` provides explicit template-to-scene mapping when a quest needs it; the walker does not infer arbitrary instance destinations or transport requirements.

## Route input and pacing

The native input owner projects candidate route points into the current viewport or minimap. It rechecks live geometry and menu identity after camera movement. A canvas obstruction can require a verified `Walk here` context-menu selection. Minimap dispatch is followed by observed movement; a dispatched click is not arrival proof.

Ordinary journeys sample a profile cadence, usually 2–6 seconds, with occasional longer gaps. Reaching an accepted target does not trigger another click early, and waiting deliberately there does not count as a stall. A profile-selected near click uses 60–90% of available reach. Required via points and transport origins cap both near and far candidates.

`walk_refresh` permits the short hazardous cadence through final arrival. It can refresh or select another allowed final tile while respecting `arrival_tiles`, occupancy and corridor constraints. The default hazardous minimum is one game tick; ordinary rejected/retry input keeps its two-tick floor.

Run toggling requires at least 10% energy when enabled and is verified through the captured run state. It rearms after energy falls below that threshold. Run input cannot compete with an in-flight click, door or transport operation.

After eight ticks without movement, the walker can replan around observed blocking NPC footprints or shorten an ineffective leg. When NPC occupancy temporarily seals the route, it can wait for that occupancy to change and exclude the blocked interval from its execution timeout. The first detour click can be capped to the adjacent planned tile so the game does not collapse it back through the occupied route.

The recovery allowance starts at six plans, adds one per 16 tiles of initial distance, and caps at 32. Routine planning and a successful transport landing are distinct from recovery. Repeated ineffective paths get bounded retries; five consecutive click failures end the journey.

Camera/mouse/input time, behavior pauses, emergency pauses and modeled NPC-block waits are excluded from `active_game_ticks`. `game_ticks` retains elapsed observed journey time. Cancellation accounts for the elapsed part of an input before revoking it. The host's ordinary action timeout does not compete with the walker's active-time budget.

## Doors and account edge memory

A blocked edge with a matching live traversal object gets a bounded interaction. The obstacle owner waits eight ticks for an observed change and permits up to three attempts. Crossing the edge or observing it clear takes precedence over older failure feedback. Locked, solid or exhausted edges trigger a new plan with that edge blocked; another viable route can continue the journey.

Edge observations persist under `navigation/edges-<profile-id>.json`:

| Observation | Lifetime | Routing effect |
| --- | --- | --- |
| Locked feedback | Four hours | Block the edge |
| Solid edge, exhausted or rejected interaction | Five minutes | Block the edge |
| Cleared crossing | One day | Retain the observation; never override static/live collision |

Only game/spam feedback containing the whole word `locked` establishes a lock. Player chat, `blocked` and `unlocked` do not. Door failures also clear when the captured quest enum or numeric main stage changes; solid and cleared observations retain their own expiry. Quest capture refreshes every ten ticks, and unknown quest state does not flush memory. Auxiliary access flags require their own observed requirements.

Every plan uses an immutable captured memory view. Expiry or new observations invalidate queued plans. Account changes cancel old travel and clear continuations/snapshots. A corrupt account memory file makes navigation unavailable without retaining the previous account's knowledge or preventing independent behavior/scheduler activation.

## Directed transports

The catalog currently contains 56 directed entries, including alternative standing origins for some services. It covers supported ordinary stairs/ladders, the Waterfall raft, Glarial's exit, the GE-to-Stronghold spirit tree, both supported glider directions, repeat Daero travel and the modeled Waydar/Lumdo conversations.

Each entry owns a standing origin, representative destination, arrival region, interaction cost, ordered steps and observed requirements. The planner's 80-unit penalty per semantic step is a route preference, not a measured duration. Some seed restrictions deliberately match the supported quest phase; their evidence and limits are recorded in [navigation-transitions.md](navigation-transitions.md).

An entry executes only after the player reaches its exact standing origin. The selected object/NPC action is re-resolved from the frame. Widget selection is scoped to the specified menu and expected destination label, and visible ancestors and labels are checked again before dispatch. A transport conversation advances only an expected speaker or permitted choice; foreign dialogue returns to Lua even if the request did not ask for ordinary dialogue interruption.

Dispatch does not complete a transport. It waits for the observed landing, and conversation services also require closed dialogue across successive frames. Native input time is excluded from the 60-active-tick transition budget. Missing targets, changed requirements, rejected input or unverified arrival block that service group for the journey and trigger replanning. Scene-loading snapshot gaps preserve completed input until a fresh landing frame is available.

A pause or interrupted continuation never blindly reissues an attempted climb. The retained state observes whether the first attempt landed and continues any remaining owned conversation. Arrival and route matching cannot skip a pending transport.

Item/spell teleports, initial quest conversations, Femi's incompletely proved toll, puzzles and hazardous prayer/supply boundaries remain explicit quest behavior. The catalog grows only from supported origins, requirements, target identities and postconditions.

## Ownership and receipts

| Owner | Responsibility |
| --- | --- |
| `GenericClientWalkRequest`, `GenericClientWalkInterrupts` | Immutable constraints and ordered snapshot predicates |
| `GenericClientWalker` | Admission, tick precedence, plan acceptance and native dispatch |
| `GenericClientWalkJourney` | Progress, cadence, time accounting and terminal receipt |
| `GenericClientWalkPlanner`, `GenericClientPathfinder`, `GenericClientTransportGraph` | Captured work, search and admissible transport estimates |
| `GenericClientWalkObstacles`, `GenericClientEdgeMemory` | Door attempts and account observations |
| `GenericClientWalkTransitions`, `GenericClientTransportCatalog`, `GenericClientTransport` | Selected transport progress, evidence-backed entries and native steps |
| Existing game/object/NPC/widget/dialogue inputs | Projection, target validation and scoped synthetic input |

Journey, click, obstacle and transport mutations stay under the walker monitor. Worker plans use captured immutable inputs. Native calls run outside that monitor and retain the context/revision selected inside it. Completion checks the original journey and revision before changing state. The journey's input ticket is revoked before a terminal receipt is delivered; independently owned safety input remains valid.

Only one journey is active. Another request receives `busy`. Terminal statuses include `arrived`, `interrupted`, `unavailable`, `unreachable`, `search_limit`, `unsupported_transition`, `timed_out`, `cancelled` and `click_failed`. `unsupported_transition` includes an unmodeled plane change or lack of an eligible plane connection. A failed via leg includes its one-based `segment`.

Receipts include requested/reached positions, arrival constraints, via progress, elapsed/active ticks, plan and rejoin counts, expanded nodes, click receipts, obstacle outcomes, `blocked_edges`, `edge_memory`, selected `transports`, and the terminal reason. Interrupted/unavailable receipts can add continuation and triggering observations. `last_plan_millis` includes time from queueing to acceptance, not only worker CPU time.

## Verification

```bash
./gradlew --offline routeAudit
./gradlew --offline plannerBenchmark
```

The current audit plans 34 authored journeys across 59 account-profile checks and selects all 56 catalog entries with passable standing endpoints. It includes constrained corridors, landing transitions and failed-quest eligibility. Pure optimality checks compare transport searches against exhaustive small graphs; native-input tests cover menu identity, queued cancellation, hidden widgets and conversation ownership.

Historical builds were exercised around Port Sarim doors and the Varrock/Grand Exchange route. Those receipts predate the current journey, transport and behavior cutover. Source tests and historical routes do not establish a loaded artifact or fresh live acceptance. Installation and safe live receipts are separate gates; hazardous routes require the user watching.
