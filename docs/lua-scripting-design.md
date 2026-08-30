# GenericClient Lua scripting design

Status: **Skeleton implemented; expanded only by real automation requirements**.

Research snapshot: 2026-08-25. This document covers the scripting pipeline only. The proposed headless client is deliberately deferred to a separate design.

## Decision

Build GenericClient scripting as an in-process, sequential-coroutine Lua host over immutable RuneLite snapshots and semantic client-thread actions.

Use **LuaJava 4.1.0 with native PUC Lua 5.4** for the first implementation. Keep the runtime behind an internal seam, but ship only the Lua 5.4 adapter initially. A deterministic fake adapter is sufficient to test `ScriptHost`; a second production VM is not useful enough to justify LuaJ's language mismatch and one-Java-thread-per-coroutine behavior.

Expose six script-facing primitives:

```lua
gc.read(subject, query) -- inspect one pinned immutable frame
gc.await(request)       -- yield for a tick, event, or semantic action receipt
gc.log(level, event, fields)
gc.overlay(rows)        -- publish up to four compact label/value rows
gc.next_action()        -- consume one dashboard action id, or nil
gc.activity(name)       -- set/query this coroutine's activity descriptor
```

`gc.phase(name, options)` is a Lua convenience wrapper over `gc.await`; its
optional `activity` value updates the coroutine before the phase evaluation.

Manifest entries may also declare named module files. For those scripts the
registry composes a private, cached `gc.require(name)` loader before the entry
file runs. It can load only that entry's declared UTF-8 Lua files, rejects
unknown/circular/nil-returning modules, and never exposes `package`, `io`, or a
filesystem path to Lua. Small scripts remain one file; the bundled AIO Magic and
Quest Runner scripts use modules to keep configuration, progress/state, and
supply policy separate from orchestration.

Reactive streams and a declarative reconciliation DSL were explored as radically different interfaces. Both can later be implemented as Lua libraries over these primitives. Neither belongs in the host interface.

### Implemented vertical slice

The current checkout implements:

- LuaJava 4.1.0 with native PUC Lua 5.4 in the standalone fat JAR;
- one active standalone script plus one persistent REPL state on one scheduler thread;
- `gc.read` subjects `runtime`, `player`, `behavior`, `random_event`, `skills`, `inventory`,
  `equipment`, `bank`, `quests`, `grand_exchange`, `cash`, `combat`, the combined
  `account` frame, bounded `npcs` queries with clickability and line-of-sight
  facts, bounded system `messages` without player chat, and adjacent-edge
  `scene` collision inspection;
- `gc.await` for `game.tick`, tick counts, the synthetic `walk.random` action,
  the same-plane `walk.to` ground-route action, `npc.interact`,
  `combat.set_style`, `combat.set_auto_retaliate`, and profile-owned
  `mouse.offscreen` idle placement;
- per-coroutine activity contexts, independent post-action break/cursor rolls,
  per-interaction `breaks=false`, phase transitions, seeded mouse timing, and a
  behavior controller that pauses coroutine action progression without blocking control;
- `gc.log` to `client.log` and the GenericClient dashboard;
- `gc.overlay` plus the automatic name/runtime game overlay;
- descriptor-declared Active Script buttons consumed cooperatively through
  `gc.next_action`;
- manifest-registered one-file descriptor scripts with start/reload/stop
  controls, validated `choice` inputs, and generic dashboard controls;
- a loopback control bridge and stdio MCP server for live/account status, bound
  RuneLite Notes, REPL evaluation, script registration, and on-demand execution;
- an internal owned-random-event detector that latches NPC context, interrupts
  normal execution, auto-runs manifest-registered standalone solvers, and keeps
  unknown or faulted events blocked for MCP inspection;
- a bounded three-click `walk-stress.lua` automation;
- `walker.lua`, whose Lua-owned destination catalog drives the Java-owned
  collision planner and synthetic interaction pipeline;
- the read-only `account-auditor.lua` and cap-aware `aio-melee.lua` standalone
  scripts;
- per-resume instruction/deadline interruption and pinned-frame reads;
- focused tests for NPC queries, coroutine/action flow, pinned frames, and infinite-loop termination.

Every automated movement and click now runs through the recorded template
matcher and synthetic canvas input. Route clicks face the client camera and use
the farthest currently projectable path tile. The operating-system pointer is
not read or moved. The complete behavior contract is in
[`behavior-system.md`](behavior-system.md).

The MCP and manifest interface is documented in
[`mcp-lua-control.md`](mcp-lua-control.md).
The random-event ownership, interruption, and standalone-solver contract is in
[`random-events.md`](random-events.md).

The walker combines the global collision reader with a pinned live-scene
overlay, bounded A* planning, verified same-plane obstacle actions, and the
route lifecycle needed by this automation. Its implementation status and limits are documented in
[`walker-design.md`](walker-design.md). The later Magic and quest slices added
the object, inventory, equipment, dialogue, bank, GE, spell, and emergency actions they actually
exercise; their current contract is in
[`quest-runner-design.md`](quest-runner-design.md).

### Live verification

The installed artifact is validated from current stock-RuneLite behavior, not a
local release-number history. Live receipts cover:

- Jagex Launcher startup, login, logout, click-to-play readiness, and account
  profile binding;
- the compact dashboard, active-script controls, synthetic cursor, break banner,
  account notes, and behavior overrides;
- recorded-template mouse movement without moving the operating-system cursor;
- breaks-enabled walking, camera turns, retained waypoints, live collision
  overlays, and traversal-object recovery;
- semantic NPC, object, inventory, equipment, dialogue, widget, bank, Grand
  Exchange, spell, and safety actions;
- modular AIO Melee and Magic scripts with exact XP stops and recovery receipts;
- modular Witch's House and Waterfall Quest completion, including hostile-area
  safety, long-break logout/relogin, exact-ID ritual interactions, and verified
  rewards;
- GenericClient-owned random-event interruption plus a live-completed Capt'
  Arnav solver.

Artifact hashes and suite counts remain transient build receipts produced by the
validation workflow; they are not maintained as design history.

This is a refinement of the initial LuaJ-first idea. The decisive reasons are:

- PUC Lua coroutines are language-level threads, not operating-system threads ([Lua 5.4 manual](https://www.lua.org/manual/5.4/manual.html#2.6)). LuaJ's `LuaThread`, including the fork used by LuaJava, starts a Java `Thread` for each coroutine and parks it across yields ([LuaJ source](https://github.com/wagyourtail/luaj/blob/f062b53a3422ff8949914b292ac8e21dab36d7a8/luaj-core/src/main/java/org/luaj/vm2/LuaThread.java#L207-L254)). GenericClient expects handlers to wait on ticks, events, and action receipts, so lightweight suspended coroutines matter.
- LuaJava exposes a stable Java interface over Lua 5.1-5.5, LuaJIT, and LuaJ, while the selected Lua 5.4 adapter uses native Lua coroutine stacks ([LuaJava platforms and artifacts](https://github.com/gudzpoz/luajava/blob/v4.1.0/README.md#platforms-and-versions)).
- LuaJava is active, releases Java 8-compatible bytecode, and therefore fits GenericClient's Java 11 target ([LuaJava build](https://github.com/gudzpoz/luajava/blob/v4.1.0/build.gradle#L21-L33)). Standalone LuaJ's latest Maven Central release remains 3.0.1 from 2015, and its source repository's latest commit is from 2020 ([Maven metadata](https://repo1.maven.org/maven2/org/luaj/luaj-jse/maven-metadata.xml), [repository](https://github.com/luaj/luaj)).
- LuaJava 4.1.0's released Lua 5.4 artifact contains 5.4.8. Lua 5.5 is now the current language line and Lua 5.4 received its final release on this research date ([official version history](https://www.lua.org/versions.html)), but the Lua 5.4 Java 11/fat-JAR path has been exercised locally and is the lower-risk first adapter. Recorded interface-level replays can validate a future move to 5.5 after the host interface settles.

The scripting interface must not depend on Lua 5.5-only syntax.

## Runtime comparison

| Runtime | Language | Packaging | Coroutine implementation | Limits and inspection | Fit |
| --- | --- | --- | --- | --- | --- |
| LuaJava 4.1.0 + PUC Lua 5.4 | Lua 5.4.8 in the released artifact | Java interface plus a desktop native JAR; Windows, Linux, and macOS x86/ARM binaries are supplied | Native Lua coroutine stacks; no OS thread per coroutine | Private debug hook can count instructions/check deadlines; native allocation requires an extension for a hard per-state byte cap | **Recommended first adapter** |
| LuaJava 4.1.0 + PUC Lua 5.5 | Lua 5.5.0 in the released artifact | Same native arrangement | Native Lua coroutine stacks | Same as 5.4 | Possible later promotion, not a second initial adapter |
| LuaJava 4.1.0 + LuaJ | Mostly Lua 5.2 | Pure Java, but the backend is a JitPack-pinned LuaJ fork | One Java thread per Lua coroutine | LuaJ debug hooks can stop runaway handlers; allocations live on the JVM heap | Evaluated and rejected for the initial implementation |
| Standalone LuaJ 3.0.1 | Mostly Lua 5.2 | One 346 KiB Maven Central JAR | One Java thread per Lua coroutine | A custom `DebugLib` or hidden `debug.sethook` can enforce instruction/deadline checks | Useful only for throwaway experiments |
| LuaJava + LuaJIT | Lua 5.1 plus LuaJIT extensions | Native per platform | Native coroutine stacks | JIT compilation makes deterministic instruction accounting and debugging less direct | Not needed for the first version |

LuaJ's own sandbox example leaves out `CoroutineLib` because script-created coroutines produce threads outside the server's control, while retaining a hidden debug hook to stop scripts after an instruction budget ([source](https://github.com/luaj/luaj/blob/daf3da94e3cdba0ac6a289148d7e38bd53d3fe64/examples/jse/SampleSandboxed.java#L91-L143)). That example validates the instruction-hook technique but also confirms why LuaJ is a poor match for await-heavy handlers.

Artifact size is not a blocker. The released LuaJava 5.4 Java adapter is about 6 KiB, its common Java interface about 79 KiB, and the compressed all-desktop native JAR about 1.4 MiB ([LuaJava](https://repo1.maven.org/maven2/party/iroiro/luajava/luajava/4.1.0/), [Lua 5.4 adapter](https://repo1.maven.org/maven2/party/iroiro/luajava/lua54/4.1.0/), [desktop natives](https://repo1.maven.org/maven2/party/iroiro/luajava/lua54-platform/4.1.0/)). Only one native Lua version should ship in the production bundle.

### Local feasibility spike

A temporary Java 11 spike using `lua54:4.1.0` and `lua54-platform:4.1.0:natives-desktop` verified the specific mechanics this design needs on Linux:

- selective standard-library loading;
- Lua coroutine yield/resume;
- a per-coroutine `debug.sethook` instruction limit that terminated an infinite loop;
- selective loading of base, coroutine, string, table, math, UTF-8, and a private debug hook without exposing `java`, `package`, `io`, or `os`;
- packaging and execution from a GenericClient-style fat JAR.

The resulting fat JAR was approximately 1.5 MiB and contained the supplied Windows, Linux, Intel/ARM macOS native variants. This is a feasibility receipt, not a cross-platform acceptance test; the implementation still needs the launch-matrix test listed below.

### Important LuaJava details

LuaJava 4.1.0 supports selecting libraries individually, creating/resuming Lua threads, and supplying an external module loader. It does not make one Lua state safe for concurrent Java access; its documentation requires external synchronization, or a separate main state dedicated to each OS thread ([Java interface](https://github.com/gudzpoz/luajava/blob/v4.1.0/docs/java.md), [thread-safety documentation](https://github.com/gudzpoz/luajava/blob/v4.1.0/docs/threadsafety.md)). GenericClient avoids the problem by confining every VM to one scheduler thread.

LuaJava creates native states through `luaL_newstate` ([source](https://github.com/gudzpoz/luajava/blob/v4.1.0/luajava/src/main/java/party/iroiro/luajava/AbstractLua.java#L85-L100)). It does not currently expose construction with a custom `lua_Alloc`. Consequently:

- instruction and wall-time caps can be enforced immediately with a private debug count hook;
- current Lua allocation can be sampled through a private retained `collectgarbage("count")` function;
- a hard native-memory ceiling needs a small LuaJava extension which creates each state with `lua_newstate(quotaAllocator, quota)`.

LuaJava does not bind `lua_Debug`/`lua_Hook` uniformly because the structure differs between Lua versions. Its own documentation recommends using the Lua `debug` library and demonstrates a Java callback installed with `debug.sethook` ([debug documentation](https://github.com/gudzpoz/luajava/blob/v4.1.0/docs/examples/debug.md)). GenericClient can open `debug` during bootstrap, retain only private hook/traceback functions, and remove the table before user code runs.

Java callbacks must not try to yield through LuaJava's Java/C/Java stack: `Lua.yield` is deliberately unsupported for that path ([source](https://github.com/gudzpoz/luajava/blob/v4.1.0/luajava/src/main/java/party/iroiro/luajava/Lua.java#L799-L817)). `gc.await` therefore uses a tiny Lua wrapper: a Java host function creates an operation token and returns; Lua then calls `coroutine.yield` itself. The scheduler resumes that coroutine with the completed result.

### Alternatives screened out

- [Cobalt](https://github.com/cc-tweaked/Cobalt) is an actively developed pure-Java Lua 5.2 implementation with arbitrary interruption/resumption and yielding from native functions. Those properties are attractive, but its own README explicitly says not to use it as a general Lua runtime because its interface changes with CC:Tweaked. Current source also targets Java 17, not GenericClient's Java 11.
- [Rembulan](https://github.com/mjanicek/rembulan) is a pure-Java Lua 5.3 implementation designed around sandboxing, CPU accounting, and asynchronous scheduling. Those are almost exactly the desired mechanics, but it has no release and its repository stopped in 2016.
- [MovingBlocks JNLua](https://github.com/MovingBlocks/JNLua) has native memory limiting and state serialization, but it remains on Lua 5.2/5.3, uses snapshot artifacts and a more involved native build, and has little recent development.
- [Luau](https://github.com/luau-lang/luau) is active and has a strong embeddable VM and type tooling, but no maintained first-party Java binding. Choosing it would add a binding project and a different language rather than solving GenericClient's Lua host.

## System shape

```text
RuneLite client thread
    |
    | copy current state; never expose RuneLite objects
    v
SnapshotCollector ---> immutable WorldFrame store
    |                          |
    | normalized events       | frame/query handle
    v                          v
bounded mailbox --------> ScriptHost scheduler thread
                               |
                               | resume one root coroutine per script
                               v
                         LuaRuntime seam
                          /           \
                  LuaJava/5.4    deterministic fake
                               |
                               | SemanticIntent
                               v
                         ActionDispatcher
                               |
                               | re-resolve target
                               v
                       RuneLite client thread
                               |
                               | ActionReceipt
                               +----> mailbox ----> resume coroutine
```

The deep module is `ScriptHost`. Its external Java interface should remain small:

```java
interface ScriptHost extends AutoCloseable
{
    ReconcileResult reconcile(DesiredScripts desired);
    void publish(ScriptSignal signal);
    ScriptHostSnapshot snapshot();
}
```

Behind that interface it owns discovery, lifecycle, file watching, VM creation, event matching, coroutine scheduling, budgets, logging, reload, and replay metadata. Frames, normalized events, action receipts, and shutdown/reload notices all enter through `ScriptSignal`; callers do not need separate completion plumbing.

The internal runtime seam is real because production and deterministic test adapters both use it:

```java
interface LuaRuntime
{
    RuntimeDescriptor descriptor();
    LuaVm createVm(ScriptIdentity identity, RuntimePolicy policy, HostFunctions host);
}

interface LuaVm extends AutoCloseable
{
    CompileResult load(SourceBundle source);
    ResumeResult start(String function, Value event, ResumeBudget budget);
    ResumeResult resume(CoroutineId coroutine, Value result, ResumeBudget budget);
    DebugSnapshot inspect(CoroutineId coroutine);
}
```

`ScriptHost`, not Lua code and not a runtime adapter, defines event ordering and action semantics. Swapping a runtime can change language compatibility and performance; it must not change snapshots, event envelopes, intents, receipts, or logs.

## Thread and state rules

1. RuneLite callbacks copy only the data needed to build immutable records, then return. Lua never runs on the client thread or Swing event-dispatch thread. RuneLite's event bus invokes subscribers immediately on the posting thread ([RuneLite `EventBus.post`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/eventbus/EventBus.java#L202-L221)).
2. One scheduler thread owns all Lua states for one game client. It enters only one VM at a time. The runtime uses one suspended root coroutine per script.
3. Use one main Lua state per script package. This gives globals, modules, GC, logs, budgets, and reload a clean lifecycle. Do not put unrelated scripts into different environments inside one shared state.
4. Mutating intents are serialized per game client. Read-only queries can be served from immutable frames without entering the client thread.
5. Every event, intent, receipt, log record, and coroutine carries `scriptId`, script `generation`, client `epoch`, and a monotonic `sequence`.
6. Logout, login, and world-hop identity changes advance the epoch. Reload advances the script generation. Old references and late completions are rejected rather than silently redirected.

## Snapshot interface

All live RuneLite types stop at `SnapshotCollector`. Lua must never receive `Client`, `NPC`, `Widget`, `Tile`, `WorldView`, or any other mutable RuneLite object.

`WorldFrame` is an immutable Java value containing:

- `epoch`, RuneLite revision, game state, world and world-view identity;
- server tick, client tick, and monotonic sequence;
- player state and location;
- indexed NPCs, players, tile objects, ground items, projectiles, inventory/container state, widgets, and normalized dialog state;
- generation-bearing references for anything that can be acted on.

A reference contains enough identity to re-resolve safely, for example:

```text
NpcRef(epoch, worldViewId, npcIndex, npcId, spawnGeneration)
ObjectRef(epoch, worldViewId, plane, sceneX, sceneY, objectId, spawnGeneration)
WidgetRef(epoch, packedWidgetId, childIndex, widgetGeneration)
```

Lua query calls operate on an immutable frame index and return copied Lua tables. A script may modify those tables, but that cannot mutate RuneLite state or another script's view. Large collections stay indexed in Java and are marshalled only after a bounded query; do not clone an entire scene into every VM on every tick.

Actions accept a reference and a semantic action name. On the client thread, `ActionDispatcher`:

1. checks epoch and generation;
2. finds the current RuneLite entity/widget;
3. checks that the requested action still exists;
4. calculates the current `MenuAction` parameters;
5. dispatches through `Client.menuAction(...)`;
6. correlates `MenuOptionClicked` and any requested postcondition into an `ActionReceipt`.

This keeps obfuscated packet IDs, raw widget operations, and RuneLite object lifetimes out of scripts.

## Interface alternatives

Three radically different shapes were evaluated before choosing the host interface.

### Sequential coroutine

```lua
gc.read(subject, query)
gc.await(request)
gc.log(level, event, fields)
```

The script is one ordinary top-to-bottom coroutine. Reads use one pinned immutable frame; `await` suspends for a tick, event, or action receipt. This is the smallest interface, maps naturally from Microbot's loop and `sleepUntil` style, hides client-thread races, and is easy to replay. It is the selected core.

That migration benefit is concrete: current Microbot scripts inherit a ten-thread scheduled executor ([source](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/Script.java#L26-L46)), while `sleepUntil` repeatedly polls and sleeps a Java thread ([source](https://github.com/chsami/microbot/blob/56c423becc6ff6d69f289d8dcbeb728021cc2c51/runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/Global.java#L117-L151)). A Lua coroutine preserves the readable sequential control flow without retaining those sleeping worker threads.

### Reactive journal

```lua
gc.events("game.tick")
  :filter(predicate)
  :concat_map(action)
  :subscribe(handler)
```

This makes multiple concurrent workflows, cancellation, and backpressure explicit. It also forces every script author to understand stream operators, subscription ordering, queue policies, and error supervision. It is powerful but too conceptually expensive as the foundation. A reactive library can later translate stream operations into `gc.read` and `gc.await` calls.

### Declarative reconciler

```lua
return gc.reconciler {
  rules = {
    gc.rule {
      when = query,
      ensure = desired_state,
      propose = semantic_action,
      confirm = observed_postcondition,
    },
  },
}
```

This yields excellent static validation, conflict arbitration, convergence diagnostics, and replay behavior. Its cost is expressiveness: Lua becomes a policy declaration language, and unusual algorithms require new host operators. A reconciler can later be a library/compiler above the minimal core; it should not constrain the first scripting surface.

## Selected Lua interface

The active interface has five functions.

### `gc.read(subject, query)`

Reads from the frame pinned when the coroutine was last resumed. Supported subjects begin with `runtime`, `player`, `npcs`, `messages`, `objects`, `ground_items`, `inventory`, `widgets`, and `dialog`. Queries have a shared bounded shape:

```lua
local bankers = gc.read("npcs", {
  where = { name = "Banker" },
  within = 15,
  action = "Bank",
  order = { "distance", "index" },
  limit = 1,
})
```

Results are copied Lua scalars, maps, and arrays with canonical ordering. They contain opaque generation-bearing references but no Java or RuneLite objects. Mutating a returned table cannot mutate the client or another script.

### `gc.await(request)`

Accepts one tick, event, or action request and suspends the root coroutine:

```lua
gc.await { ticks = 2 }

local walk = gc.await {
  action = {
    type = "walk.to",
    destination = { x = 3210, y = 3424, plane = 0 },
    within = 3,
  },
  timeout = { game_ticks = 600 },
}

-- The event-list and entity-interaction forms below remain planned shapes.
gc.await {
  event = { "npc.spawned", "game.tick" },
  timeout = { game_ticks = 10 },
}

local receipt = gc.await {
  action = {
    type = "interact",
    target = banker.ref,
    option = "Bank",
  },
  timeout = { game_ticks = 5 },
}
```

Other semantic action types include `walk`, `widget`, `dialog.choose`, and `dialog.continue`. Lua never supplies `MenuAction` parameters, packet opcodes, or canvas coordinates. A `dispatched` receipt proves current target resolution and entry into the client action path, not completion of the intended game effect; scripts establish success by rereading state or awaiting a semantic event.

The wrapper yields in Lua, outside the Java callback stack:

```lua
local host_yield = coroutine.yield

function gc.await(request)
  local response = host_yield({
    protocol = "gc.await.v1",
    request = request,
  })

  if response.host_error then
    error(response.host_error.message, 2)
  end

  return response.value
end
```

After bootstrap, scripts receive only the wrapped `gc.await`; the raw coroutine library is not exposed.

### `gc.log(level, event, fields)`

Produces a structured record. The host automatically attaches script ID, generation, source line, epoch, frame sequence, client cycle, and game tick.

### `gc.overlay(rows)`

Publishes zero to four compact label/value rows. RuneLite supplies the script
name and elapsed wall-clock runtime automatically. Passing `nil` or an empty
array clears the script rows. The overlay is visible only while the standalone
script is running; scripts do not control coordinates, colors, fonts, or draw
arbitrary shapes.

Within a running script, `gc.read("runtime").script_runtime_millis` reports the
same monotonic wall-clock runtime shown in the overlay header. Scripts can use
it for rates and ETAs without access to the operating-system clock.

```lua
gc.overlay {
  { label = "Destination", value = "Varrock" },
  { label = "State", value = "Walking" },
}
```

### `gc.next_action()`

Returns and removes one action ID queued from Active Script or MCP, or returns
`nil`. Actions are declared in the descriptor and processed only when the root
coroutine reaches a safe polling point. This preserves the one-coroutine model;
immediate lifecycle operations remain the host-owned Stop and Restart buttons.

### Complete example

Each script chunk returns a descriptor. The host validates optional inputs and
uses `run(input)` as the root coroutine:

```lua
return {
  actions = {
    { id = "snapshot_now", label = "Snapshot now" },
  },
  run = function(input)
    gc.log("info", "nearby-diagnostics.started")

    while true do
      gc.await { event = "game.tick" }

      local npcs = gc.read("npcs", {
        within = 15,
        limit = 50,
      })

      if gc.next_action() == "snapshot_now" then
        gc.log("info", "manual-snapshot")
      end

      gc.overlay {
        { label = "Nearby NPCs", value = #npcs },
      }

      gc.log("info", "nearby-npcs", { count = #npcs })
    end
  end,
}
```

## Event scheduling and backpressure

RuneLite callbacks are normalized into ordered envelopes containing sequence, epoch, game tick, client tick, type, frame ID, and copied payload. `ScriptHost` stores them in a bounded journal/mailbox without invoking Lua on the client thread.

The runtime has one root coroutine and at most one active await per script. Between resumes, the host matches events against that await. While the coroutine is running, its frame remains pinned; an event cannot interleave between a `gc.read` and the following `gc.await`, eliminating the usual read/register race.

Snapshot-like events can coalesce to the latest full frame. Action receipts, epoch changes, and specifically awaited edge events cannot be silently dropped. If bounded history no longer covers an awaited event, `gc.await` returns `event_gap` and the script must reconcile from a fresh frame. `client_tick` is opt-in; most scripts should use `game.tick` and semantic events.

Reactive streams or multiple managed coroutines can be added as a Lua library after real scripts demonstrate the need. They must not expand the host interface or change the deterministic journal order.

## Per-script environment

Bootstrap a fresh VM with only:

- base functions selected by GenericClient;
- coroutine, string, table, math, and UTF-8 libraries;
- the `gc` table.

The runtime loads one source file and does not open `package`. Do not expose LuaJava's `java` module, `io`, `os`, `debug`, native module loading, arbitrary source/bytecode loading, or the OS wall clock. LuaJava states start with its Java module available and its standard-library helper can open everything, so initialization must explicitly remove `java`, avoid `openLibraries()`, open selected libraries one at a time, install the private hook, then remove `debug`, raw coroutine access, `load`, `loadfile`, `dofile`, `collectgarbage`, and similar host-controlled functions ([LuaJava Java interface](https://github.com/gudzpoz/luajava/blob/v4.1.0/docs/java.md#open-libraries), [Lua 5.4 standard libraries](https://www.lua.org/manual/5.4/manual.html#6)).

Compile and execute the user chunk under the same instruction/deadline hook; it
must return a descriptor table with a `run` function. Parse and validate its
optional input descriptor before creating the root coroutine. Do not execute
unbudgeted top-level user code on the main Lua state.

The private debug hook and traceback helpers live in registry references unavailable to script globals. Host values are marshalled as Lua primitives and tables rather than general Java userdata.

## Budgets and limits

Installation-wide configuration carries the first limits. Initial values are hypotheses to profile, not permanent constants:

| Limit | Initial value | Enforcement |
| --- | ---: | --- |
| Instructions per resume | 100,000 | Private count hook every 1,000 instructions |
| Wall time per resume | 5 ms | Monotonic deadline checked by the same hook and around every host call |
| Scheduler CPU per script | 50 ms/second | Token bucket across resumes |
| Queued events | 256 | Coalesce replaceable events; fault on durable overflow |
| Root coroutines | 1 per script | Fixed by the runtime model |
| Outstanding actions | 1 per script | A new action can only be issued after the prior await resumes |
| Query rows | 512 per call | Query adapter truncates with an explicit flag |
| Structured log data | 64 KiB/minute | Token bucket plus truncation record |
| Lua allocation | 16 MiB soft, 32 MiB hard target | Sample Lua allocation initially; custom quota allocator for a hard ceiling |

On an instruction or deadline breach, the hook marks the resume budget exhausted and raises a private host sentinel. Even if Lua code catches the error, `ScriptHost` discards the nominal return once it sees the exhausted budget and faults that script generation. Do not attempt to resume an arbitrarily interrupted Lua stack.

The hard native-memory ceiling is the only item requiring a LuaJava patch. Until it exists, use one native state per script, cap every Java-to-Lua payload, sample allocation after each resume, run collection over the soft ceiling, and close the entire VM if it remains over the hard target. That detects growth at yield/call boundaries but cannot stop one Lua instruction from exhausting native memory; document that limitation in the first implementation.

## Script files and versioning

Version 2 registers one-file scripts in `manifest.json`:

```text
.runelite/genericclient/scripts/
  nearby-diagnostics.lua
  banking-test.lua
```

The manifest provides the stable script ID, name, description, and filename. A
script declares its required host interface in a header and returns its
descriptor:

```lua
-- genericclient-interface: 2

return {
  inputs = {
    {
      id = "destination",
      label = "Destination",
      type = "choice",
      choices = {
        { value = "varrock", label = "Varrock" },
      },
    },
  },
  actions = {
    { id = "refresh", label = "Refresh" },
  },
  run = function(input)
    -- script body
  end,
}
```

Walker introduced the small `choice` input schema. Active Script introduced
bounded action metadata, `gc.next_action`, and three-row overlay publication.
Additional input types, multi-file packages, and dependency metadata remain
deferred until an automation requires them.

Version the GenericClient scripting interface independently of RuneLite and Lua:

- adding optional fields is a minor interface change;
- changing event order, reference meaning, required fields, or action outcomes is a major change;
- every recording contains the exact interface schema, runtime ID/version, RuneLite version/revision, and script source hash;
- `gc.experimental` includes RuneLite revision requirements where applicable.

## Hot reload

Reload is a generation replacement, not an attempt to preserve coroutine stacks:

1. debounce filesystem changes and hash the source;
2. compile it in a fresh VM, validate its descriptor, and run the candidate only
   until its first cooperative `gc.await`, with mutating action submission
   disabled during validation;
3. if initialization fails, close the candidate and leave the old generation untouched;
4. at the old generation's next yield boundary, atomically install the candidate and register its yielded wait or action request;
5. cancel the old wait or pending operation with `reloaded` and ignore late completions tagged with the old generation;
6. close the old VM and resume the new generation against the latest full frame.

Reload deliberately resets Lua globals and control flow. State migration, stack preservation, and serialized checkpoints are deferred until a real script demonstrates the need. A script must reread current state before acting after every start, reload, login, or world hop.

## Logs and debugger

Every log record is structured:

```text
ScriptLog(
  timestamp,
  sequence,
  epoch,
  gameTick,
  scriptId,
  generation,
  level,
  message,
  fields,
  source,
  line
)
```

Send it to:

- the normal RuneLite/GenericClient log file;
- a bounded in-memory ring per script;
- the GenericClient diagnostic panel with script/level filters;
- the record/replay stream when recording is enabled.

The first debugger should be deliberately small and runtime-neutral:

- list script status, generation, mailbox depth, CPU/instruction counters, memory sample, current wait, and pending action;
- retain a stack trace and source line when the root coroutine faults;
- allow enable, disable, reload, and stop from the diagnostic panel;
- expose no arbitrary JVM object evaluation.

Line breakpoints, stepping, paused-local inspection, and an interactive evaluator can come later. LuaJava intentionally does not provide one uniform native `lua_Debug` binding, so the runtime adapter gathers the initial fault trace through private Lua-level `debug.getinfo`/`debug.traceback` helpers ([LuaJava debug documentation](https://github.com/gudzpoz/luajava/blob/v4.1.0/docs/examples/debug.md)).

## Record and replay

Record at the GenericClient seam, not raw Lua values and not live RuneLite objects.

Header:

```text
format version
GenericClient build and scripting-interface version
RuneLite version and game revision
Lua runtime ID/version/language level
script source hashes
initial epoch, virtual-clock origin, deterministic RNG seed
```

Stream records:

```text
WorldFrame / frame delta
EventEnvelope
SemanticIntent
ActionReceipt
ScriptLog
Script lifecycle/reload/fault
```

Replay replaces the live adapters:

- `ReplayStateSource` emits recorded frames and events using a virtual tick clock;
- `AssertingActionDispatcher` compares the script's semantic intents with the recorded intents and returns the recorded receipts;
- host random functions use the recorded seed; scripts receive no OS clock;
- expected logs, intents, and final script status form the golden result.

Required tests:

1. deterministic replay twice on the same runtime;
2. stale references after despawn, NPC index reuse, world hop, and reload;
3. widget/dialog load, change, close, continue, and option choice;
4. action dispatch, rejection, observed click, postcondition, timeout, and late receipt;
5. event coalescing, awaited-edge loss, and `event_gap` recovery;
6. infinite loop, recursive growth, log flood, and oversized query;
7. hot reload success, compile/init failure, and old-generation completion;
8. native library extraction on supported Windows, Linux, Intel macOS, and Apple Silicon macOS launch paths;
9. clean shutdown with no scheduler or native Lua states left alive.

## Implementation slices

1. Define the smallest immutable frame needed by a diagnostic script: runtime, local player, nearby NPCs, inventory, widgets/dialog, and generation-bearing references.
2. Define semantic action requests and receipts, then build the client-thread dispatcher for walk, NPC/object/widget interaction, and dialog actions.
3. Add LuaJava 4.1.0 with released PUC Lua 5.4 natives, the restricted bootstrap, private count/deadline hook, one state and root coroutine per script, and the narrow `gc` interface.
4. Add one vertical integration script that logs nearby NPCs, interacts with a selected target, handles dialog, and proves that RuneLite's client thread never blocks.
5. Add atomic file reload, panel start/stop/reload/status controls, structured logs, and fault traces.
6. Add a compact frame/event/intent/receipt journal and replay the integration script without RuneLite.
7. Expand snapshot subjects and action types only as concrete scripts require them; add the native quota allocator before accepting unbounded long-running script packages.
8. Run the Windows, Linux, Intel macOS, and Apple Silicon launch matrix and controlled scheduler/heap/native-memory measurements.

The first usable milestone is complete when a hot-reloadable script can query nearby NPCs, inspect normalized dialogs/widgets, submit a semantic action, await its receipt without blocking RuneLite's client thread, and reproduce the same intent/log trace offline.
