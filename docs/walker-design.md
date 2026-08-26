# Walker design: static ground routes first

Status: implemented and live-tested from Falador to Varrock on 2026-08-25.
This work does not start the deferred headless mode.

Source snapshots:

- [RuneLite `2624bcc4136cea1011bf1bb154581a4b16c7a3ca`](https://github.com/runelite/runelite/tree/2624bcc4136cea1011bf1bb154581a4b16c7a3ca), 2026-08-24.
- [Microbot `56c423becc6ff6d69f289d8dcbeb728021cc2c51`](https://github.com/chsami/microbot/tree/56c423becc6ff6d69f289d8dcbeb728021cc2c51), 2026-08-23.
- The GenericClient implementation in this checkout.

## Decision

GenericClient now exposes one destination-bearing semantic Lua action:

```lua
local receipt = gc.await {
  action = {
    type = "walk.to",
    destination = { x = 3210, y = 3424, plane = 0 },
    within = 1,
  },
  timeout = { game_ticks = 600 },
}
```

`GenericClientCollisionMap` loads the pinned, packaged world map.
`GenericClientPathfinder` runs bounded deterministic A* over its eight-directional
ground edges. `GenericClientWalker` follows the result five tiles at a time
through `GenericClientGameInput`. Each cursor leg uses the active recorded mouse
profile. A visible ground tile uses the canvas; an occluding object is bypassed
by right-clicking and selecting `Walk here`; a tile outside the 3D viewport uses
RuneLite's minimap projection. Arrival is based on later player snapshots, not
click dispatch.

A reliable whole-world walker also needs explicit semantic edges for doors,
stairs, ships, teleports, and other discontinuities. Those are deliberately not
part of this first ground-route implementation. Microbot's feature named “Web
Walker” is local rather than a remote service: it loads a packaged collision
archive and transport/restriction TSVs, runs its own pathfinder, and executes
the result in the client ([startup](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/ShortestPathPlugin.java#L221-L254), [collision resource loader](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/pathfinder/SplitFlagMap.java#L102-L129), [transport resource loader](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/Transport.java#L590-L627)).

Therefore:

- Do not build or depend on an online “web-walker node” now.
- Do not hand-author ordinary road/path waypoints.
- Keep the Falador-to-Varrock live receipt as the ground-walker regression route.
- Overlay the loaded scene's live collision flags when a real failure shows the
  static data is stale around a dynamic object.
- Add transition definitions and handlers one real route at a time instead of importing every possible transport flow.

## Collision map source

Use the maintained [`Skretzo/shortest-path`](https://github.com/Skretzo/shortest-path) map, not Microbot's older forked copy. The source inspected for this design is commit [`44a691aafad48bd8f4ef6d00680d627d2aa8153c`](https://github.com/Skretzo/shortest-path/tree/44a691aafad48bd8f4ef6d00680d627d2aa8153c), dated 2026-08-19.

Its [`src/main/resources/collision-map.zip`](https://github.com/Skretzo/shortest-path/blob/44a691aafad48bd8f4ef6d00680d627d2aa8153c/src/main/resources/collision-map.zip) is 1,200,495 bytes, contains 2,726 region entries, and has SHA-256 `2fca3c83778995c96a6511cc523e157352ef526f3b0a969892b62010d5c5e717`. GenericClient vendors that exact artifact and its upstream BSD-2-Clause text. It does not download map data at client startup; provenance is recorded in [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).

The reproducible update path is [`osrs-pathfinding/shortest-path-tooling`](https://github.com/osrs-pathfinding/shortest-path-tooling), pinned here at [`d37d53c129d61dc8e2bb8456eb39801688a000c2`](https://github.com/osrs-pathfinding/shortest-path-tooling/tree/d37d53c129d61dc8e2bb8456eb39801688a000c2). Its collision workflow downloads a selected OSRS cache and XTEA keys from [OpenRS2's archive](https://archive.openrs2.org/caches), runs `CollisionMapDumper` through RuneLite's cache module, and produces the replacement `collision-map.zip` ([workflow](https://github.com/osrs-pathfinding/shortest-path-tooling/blob/d37d53c129d61dc8e2bb8456eb39801688a000c2/collision-map-update/README.md), [dumper](https://github.com/osrs-pathfinding/shortest-path-tooling/blob/d37d53c129d61dc8e2bb8456eb39801688a000c2/collision-map-update/CollisionMapDumper.java)).

The static archive supplies unloaded-world terrain. This implementation uses it
as-is. A live RuneLite scene overlay remains a future correction for routes
where current doors or dynamic collision disagree with the static plan.

## What stock RuneLite actually provides

### Live local navigation data

Each `WorldView` exposes the current `Scene` and a collision map per plane ([`WorldView`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/WorldView.java#L50-L82)). A `CollisionData` map is explicitly a 104 by 104 array addressed by scene X/Y coordinates ([`CollisionData`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/CollisionData.java#L29-L48)). The flags describe directional walls and full movement blocks from objects, floor decoration, and terrain such as water ([`CollisionDataFlag`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/CollisionDataFlag.java#L27-L53)).

RuneLite already contains the exact per-step collision logic needed to validate an eight-directional search. `WorldArea.canTravelInDirection` checks the destination footprint, directional wall flags, diagonal corner constraints, and an optional extra predicate ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/coords/WorldArea.java#L225-L438)). Stock RuneLite does not expose a general route-finder API around this data; GenericClient must run its own bounded BFS or A* over a copied collision frame.

The scene is local by definition. `Scene.getTiles()` is 4 by 104 by 104, and its base X/Y identifies the world coordinate represented by scene tile 0,0 ([`Scene`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Scene.java#L27-L47), [base coordinates](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Scene.java#L117-L170)). `LocalPoint.fromWorld` returns no point for a destination outside that scene. Live collision therefore cannot answer whether a tile in another region is reachable.

### Walking and progress observation

RuneLite exposes `MenuAction.WALK`, `Client.menuAction(...)`, and the client's current local destination ([`MenuAction.WALK`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/MenuAction.java#L135-L139), [`Client.menuAction`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Client.java#L2064-L2073), [`getLocalDestinationLocation`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Client.java#L1077-L1084)). The injected Jagex client remains responsible for the short local route resulting from a walk click. Our planner selects safe, globally directed click targets; it does not need to replace the client's per-click movement engine.

For longer local steps, `Perspective.localToMinimap` projects a `LocalPoint` into the minimap and deliberately caps the default projection to about twenty tiles, accounting for minimap zoom and camera yaw ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Perspective.java#L509-L618)). GenericClient uses this projection whenever the selected route tile has no safe canvas polygon.

### Data that looks relevant but is not a route graph

RuneLite's `WorldMapData` only answers whether a coordinate belongs to the displayed surface. `WorldMapRenderer` exposes 64-by-64 display regions and map icons, not collision or connectivity ([`WorldMapData`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/worldmap/WorldMapData.java#L27-L40), [`WorldMapRenderer`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/worldmap/WorldMapRenderer.java#L27-L44)). `WorldMapPointManager` is a thread-safe list of overlay markers, and clicking one only focuses the displayed map ([manager](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/ui/overlay/worldmap/WorldMapPointManager.java#L34-L53), [overlay click](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/ui/overlay/worldmap/WorldMapOverlay.java#L86-L117)).

RuneLite also has a curated `TransportationPointLocation` enum for world-map icons. Some rows carry a second point, while many carry only the icon location; it does not encode complete connectivity, the action to execute, or player requirements ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/plugins/worldmap/TransportationPointLocation.java#L35-L84)). It can provide hints when adding a transport later, but it is not a walker backend.

## What Microbot's “Web Walker” owns

Microbot demonstrates the data missing from stock RuneLite. It is useful as an architectural reference, but copying its complete runtime would contradict the plan to fill GenericClient only as real automations demand features.

### Static tile graph

The checked-in `collision-map.zip` contains 2,724 region entries and is 1,050,209 bytes in this source snapshot. `SplitFlagMap` loads them into region maps; `FlagMap` represents each 64-by-64 region with two directional bits per tile and a plane count ([`SplitFlagMap`](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/pathfinder/SplitFlagMap.java#L17-L47), [`FlagMap`](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/pathfinder/FlagMap.java#L8-L61), [packaged archive](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/resources/net/runelite/client/plugins/microbot/shortestpath/collision-map.zip)). A dense set of ordinary waypoint objects is unnecessary: tile neighbors are generated lazily from those edge bits.

Microbot includes the offline generator. It reads cache terrain, locations, and object definitions, applies object dimensions/orientation and terrain settings, and writes each region's two-bit passability map ([`CollisionMapDumper`](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/cache/src/main/java/net/runelite/cache/CollisionMapDumper.java#L45-L151), [object/terrain collision construction](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/cache/src/main/java/net/runelite/cache/CollisionMapDumper.java#L162-L349)). The generator also contains a large curated exception list for objects whose cache properties do not directly express desired walker semantics ([source](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/cache/src/main/java/net/runelite/cache/CollisionMapDumper.java#L355-L617)). A world map can be generated, but accuracy still requires versioning and corrections.

### Semantic transition graph

Microbot's transport resources total 8,763 TSV lines and about 0.5 MB in this checkout. `Transport` models origin, destination, interaction action/target/object ID, type, duration, and membership, skill, quest, item, currency, varbit, and varplayer requirements ([model](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/Transport.java#L28-L118), [parsing](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/Transport.java#L217-L422), [resource directory](https://github.com/chsami/microbot/tree/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/resources/net/runelite/client/plugins/microbot/shortestpath)). Network rows such as fairy rings are expanded into origin/destination permutations ([source](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/Transport.java#L537-L584)).

Before searching, `PathfinderConfig` filters the catalog against current world type, skill levels, quest state, varbits/varps, inventory/bank items, currency, and feature availability ([eligibility](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/pathfinder/PathfinderConfig.java#L1215-L1368)). These are the hand-authored “nodes” we eventually need: not arbitrary road waypoints, but discontinuous or conditional edges that ordinary collision cannot describe.

### Planning and execution are separate problems

Microbot expands eight walking neighbors plus eligible transport edges. Its walking frontier uses an A*-style `f = g + heuristic` queue; transport nodes use a separate cost queue, and very long single-target paths can use a bidirectional search ([neighbors](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/pathfinder/CollisionMap.java#L232-L343), [queues](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/pathfinder/Pathfinder.java#L35-L85), [mode selection](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/pathfinder/Pathfinder.java#L731-L778)). It smooths completed paths without collapsing across transports ([source](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/shortestpath/pathfinder/Pathfinder.java#L163-L201)).

The execution layer is substantially larger than the search. `walkTo` is a serialized, off-client-thread operation ([source](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java#L1165-L1310)). It advances along the route with minimap/canvas clicks, then handles doors, transport-specific widgets/dialogues, stalls, off-path movement, live collision invalidation, and partial-route retries ([minimap clicks](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java#L3525-L3607), [off-path replanning](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java#L2061-L2108), [partial retry budget](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java#L3091-L3146), [transport dispatcher](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java#L8690-L9034)). Its smaller nonblocking `walkStep` explicitly omits the door, transport, and stuck-recovery pipeline ([contract](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/walker/Rs2Walker.java#L1446-L1463)).

This source snapshot contains about 3,239 lines in the basic collision/search/transport model, another 2,311 lines of pathfinder configuration, and a 12,444-line full walker. The point is not that GenericClient needs that code now. It shows why “find a path” and “reliably finish a world route” must be staged.

## Approach comparison

| Approach | What it solves | What it cannot solve alone | Recommendation |
| --- | --- | --- | --- |
| Live scene BFS/A* | Correct current collision, dynamic objects, short same-plane routes | Anything outside the loaded 104-by-104 scene; transitions | Add as an overlay after the first real failure |
| Repeated straight-line clicks | Almost no planner code | Routinely fails around rivers, walls, mountains, buildings, and scene-scale detours | Do not call this a walker |
| Hand-authored waypoint network | Can bridge selected known routes | High authoring burden and weak off-route recovery; duplicates walkable terrain | Do not use for ordinary ground |
| Static whole-world tile graph | Routes ordinary ground across unloaded regions | Doors, plane changes, requirements, interfaces, teleports; revision drift | Implemented |
| Static graph plus transport edges | Practical whole-world routing | Still needs an executor and maintenance | Long-term local design |
| External route service | Central distribution/computation for many clients | Network/version dependency; still cannot execute or confirm transitions client-side | Optional adapter later |

## Implemented vertical slice

The implementation has four narrow pieces:

```text
Lua gc.await
    -> GenericClientWalker (lifecycle and receipt)
        -> GenericClientPathfinder (static-map A*)
        -> GenericClientGameInput (synthetic canvas/minimap click)
```

### Exact supported scope

- Top-level, non-instanced world view only.
- One-tile player footprint.
- Start and destination must be on the same plane.
- Search uses the packaged global map, all eight directions, and the archive's
  diagonal corner rules. It stops after 250,000 expanded nodes.
- The requested Chebyshev arrival radius is an integer from 0 through 10.
- No door/object interaction, plane transition, teleport, ship, dialogue, or widget operation.
- One active walk for the whole plugin. A second request returns `busy` rather than silently replacing the first.
- The Lua request owns the game-tick timeout. The follower allows six replans
  after its initial plan.
- There is no straight-line or random fallback. A failure is returned in the
  action receipt.

The static map can still be stale around changed or dynamic game objects.

### Planning

The plugin loads the 2,726 compressed region entries once at startup. Each tile
has a north-edge and east-edge passability bit per plane; south and west are
derived from neighboring tiles. A* runs on one dedicated daemon thread so the
RuneLite client thread never performs a world search.

The planner:

- treats a blocked start tile specially so the player can leave it;
- prevents diagonal corner cutting;
- uses orthogonal cost 10, diagonal cost 14, and an octile heuristic;
- accepts the first cheapest tile inside the requested arrival radius;
- uses fixed neighbor ordering and insertion-order tie breaking.

Lua receives no collision arrays or route nodes.

### Execution and recovery

At each game tick, the walker observes the immutable player snapshot and active
route:

1. Complete `arrived` when the player is on the correct plane and within the requested radius.
2. Match the player to a nearby point on the active route; replan when more than
   three tiles off route.
3. Click at most ten path tiles ahead. Prefer a visible canvas polygon; use a
   minimap projection when the tile is outside the 3D viewport.
4. If an object owns the canvas left-click, right-click and move to the exposed
   `Walk here` row. Confirm canvas selections through `MenuOptionClicked`;
   confirm minimap dispatch through subsequent player progress.
5. Retain the accepted waypoint until the player is within two tiles of it or
   has passed its route index. Retain the final waypoint until arrival instead
   of re-clicking the same endpoint. Keep a two-game-tick minimum between
   clicks, replan after eight ticks without player movement, and reduce click
   distance after a rejected canvas target.
6. Finish after five consecutive click failures, six replans, the requested
   timeout, logout/plane change, explicit script cancellation, no route, or arrival.

### Receipt and diagnostics

```lua
{
  status = "arrived", -- or unreachable, unsupported_transition, timed_out,
                      -- cancelled, busy, click_failed
  requested = { x = 3200, y = 3200, plane = 0 },
  reached = { x = 3200, y = 3199, plane = 0 },
  within = 1,
  game_ticks = 14,
  plans = 2,
  clicks = 4,
  path_tiles = 31,
  expanded_nodes = 287,
  reason = "arrival_radius",
}
```

Console and `client.log` receive `WALK_REQUESTED`, `WALK_PLANNING`,
`WALK_PLANNED`, `WALK_CLICK`, `WALK_PROGRESS`, `WALK_CLICK_REJECTED`, and
`WALK_COMPLETED` records. The Lua script receives only the terminal receipt.

## Live verification

GenericClient 0.6.1 changed route pacing from a two-tick-only gate to retained
waypoints. The exact release artifact completed the Grand-Exchange-to-Varrock
leg in 42 game ticks with eight clicks, one plan, and no rejected clicks. Six
handoffs occurred one or two tiles from the accepted waypoint; one occurred at
three tiles only after the player had passed that waypoint's route index. The
76-tile return completed in 52 ticks with nine clicks and one plan, leaving the
account at the Grand Exchange. The final waypoint was never re-clicked. The
comparable pre-fix walk used 15 clicks over 48 ticks and could replace a
waypoint from four tiles away.

The bundled [`lumbridge-varrock.lua`](../src/main/resources/com/genericclient/scripts/lumbridge-varrock.lua)
targets `{ x = 3210, y = 3424, plane = 0 }` with a three-tile arrival
radius and a 600-game-tick timeout.

The first attempt from Falador (`3007,3394`) reproduced an execution failure at
the Falador garden trees: canvas points were either outside the viewport or had
`Chop down` as the top action. After adding minimap projection and context-menu
walking, the route completed from `2996,3392` to `3209,3427` in one plan, 172
game ticks, 56 clicks, and 278 path tiles. Every click had a preceding
`SYNTHETIC_MOUSE_PATH_GENERATED profile=default-2dc51a50 points=128`
receipt. A second return from north Varrock reached `3213,3427` in one plan, 26
game ticks, and nine clicks.

Four bounded canvas stress runs separately exercised the right-click branch.
`Chop down` and `Search` covered several selected points; each produced
`WALK_CONTEXT_OPEN`, a template-generated move to the context row,
`dispatch=context_menu action=WALK option=Walk here`, and a completed script.

## Expansion sequence driven by real automations

1. **Live scene overlay.** Overlay current RuneLite collision onto the static
   region data when the first stale/dynamic tile requires it.
2. **First required transition.** Add one edge containing origin, destination,
   semantic action, prerequisites, completion observation, and cost. Implement
   only the handler demanded by the route under test.
3. **Further transports.** Grow the catalog from observed route failures. Keep
   doors, ladders, network widgets, and item/spell teleports as separate
   executors because their confirmation contracts differ.
4. **Optional distribution service.** If maintaining several installations on
   one revision becomes burdensome, distribute signed/versioned bundles. Route
   execution and success confirmation remain local.

This preserves the small Lua interface while letting the Java navigation layer deepen only under pressure from real scripts. No decision here requires the deferred headless work.
