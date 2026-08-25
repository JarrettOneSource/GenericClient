# RuneLite data pipeline, scripting, and headless design

Research snapshot: 2026-08-25.

The source analysis uses RuneLite commit [`2624bcc`](https://github.com/runelite/runelite/tree/2624bcc4136cea1011bf1bb154581a4b16c7a3ca), which identifies itself as `1.12.37-SNAPSHOT`. The released runtime resolved by GenericClient is RuneLite `1.12.36` with `injected-client-1.12.36.jar` (SHA-256 `fd8a49d6278431a8dcc966d97a1b84f546d4cc9715c4e64b1b55f98abf6c06f7`, build ID `32240473016.231`). Obfuscated class and method names below are evidence for that artifact only.

## Conclusion

RuneLite does not independently decode the game protocol and reconstruct the screen. It runs a revision-matched, instrumented copy of Jagex's Java client. That injected client still owns the game socket, cache/archive loading, client scripts, world state, scene construction, software rasterizer, and AWT canvas. RuneLite adds Java interfaces and callbacks to that client, then builds plugins, events, overlays, and the GPU renderer around those hooks.

The best GenericClient scripting seam is therefore the state-mutation seam RuneLite already exposes:

```text
Jagex cache/archive data -----+
                              v
Game socket -> packet decode -> Jagex client state -> RuneLite callbacks/events
                                                   |
                                                   v
                                      immutable GenericClient snapshots
                                                   |
                                                   v
                                        Lua queries and intents
                                                   |
                                                   v
                                      client-thread action adapter

Jagex client state -> scene/widgets -> software rasterizer -> pixel buffer -> AWT Canvas
                                      \-> DrawCallbacks -> RuneLite GPU renderer
```

Raw packet instrumentation is possible and useful for protocol diagnostics, but it is a worse public scripting API: it is revision-specific, lower-level, and duplicates semantic work the Jagex client has already done.

## What actually starts

`runelite-client` declares the matching `injected-client` as a runtime dependency. At startup, `ClientLoader` fetches Jagex's `jav_config.ws`, validates its codebase, initial JAR, and initial class fields, then loads the named class from RuneLite's existing classloader and casts it to `net.runelite.api.Client`. It does not download and run an unrelated plugin-side packet parser. See [the runtime dependency](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/build.gradle.kts#L47-L51) and [`ClientLoader.loadClient`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/rs/ClientLoader.java#L188-L199).

RuneLite then:

1. creates its Guice injector;
2. injects members into the game client;
3. initializes the game client;
4. loads configuration and plugins;
5. constructs the Swing UI;
6. starts plugins and unblocks the client thread.

The exact ordering is in [`RuneLite.start`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/RuneLite.java#L284-L354). `RuneLiteModule` binds `Callbacks` to `Hooks`, provides the loaded `Client`, and creates the normal and deferred event buses ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/RuneLiteModule.java#L138-L155)).

The public `Client` interface says directly that the injected client invokes `Callbacks` for events and `DrawCallbacks` for scene drawing ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Client.java#L66-L82)). In the released artifact, obfuscated `client` extends the Jagex game engine class and implements `net.runelite.api.Client`; it contains an injected `Callbacks` field and a `DrawCallbacks` field.

The current public RuneLite repository consumes that prebuilt artifact but does not contain the complete producer that generated its mappings and injected bytecode. GenericClient can use the published interfaces, post-process the released artifact, or maintain its own revision-mapping/injection toolchain; it cannot simply edit an unobfuscated `PacketReader.java` in the current RuneLite tree.

## The two Jagex data inputs

### 1. Cache and archive data

Models, animations, textures, sprites, maps, object/NPC/item definitions, interfaces, and client scripts live in Jagex cache archives. The live Jagex client owns archive fetching, decoding, and its on-disk cache. RuneLite points `jagex.userhome` at `.runelite` and can copy an existing `jagexcache` into that location before initialization ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/RuneLite.java#L284-L298), [cache-copy code](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/RuneLite.java#L473-L503)).

RuneLite also ships a separate `cache` module that can decode stored definitions, maps, models, interfaces, and scripts offline. That library is useful for tooling, but it is not the renderer driving the live client; see the [cache module source](https://github.com/runelite/runelite/tree/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/cache/src/main/java/net/runelite/cache).

### 2. Live game socket

The game connection delivers changing state: players and NPCs, object changes, inventory/container changes, varps, chat, widget/interface changes, projectiles, animations, and similar updates. The Jagex client frames and decodes those packets, then mutates its own Java object graph.

This can be verified from RuneLite's first-party [`injected-client-1.12.36.jar`](https://repo.runelite.net/net/runelite/injected-client/1.12.36/injected-client-1.12.36.jar):

```bash
javap -classpath injected-client-1.12.36.jar -p -c client
javap -classpath injected-client-1.12.36.jar -p uh dj xs jj
```

For this revision:

- `uh` wraps `java.net.Socket` and exposes read/write operations plus a `FileDescriptor`.
- `dj` owns the socket, packet buffer, current packet descriptor, and packet length.
- `xs` implements RuneLite's `PacketBuffer`.
- `client.tg(client, dj)` is the large incoming-packet routine. It checks socket availability, reads opcode bytes, decodes the opcode through `xs.pj`, selects a `jj` packet descriptor, resolves fixed or `-1`/`-2` variable lengths, reads the payload, and enters a large dispatch chain that mutates client state.

The names are obfuscated and will change. The structural sequence is the durable observation. `Client.getSocketFD()` is used by RuneLite's world-hopper only to read TCP timing information ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/plugins/worldhopper/WorldHopperPlugin.java#L910-L940)); it is not a stable packet-stream API.

The outgoing direction follows the inverse pattern: semantic client actions construct and queue outgoing packet nodes, and the packet writer flushes their bytes to the socket. GenericClient should enter this pipeline through `Client.menuAction(...)`, which preserves the Jagex client's current parameter and packet construction, rather than creating outgoing packets in Lua.

## From state mutation to plugin data

RuneLite's injection points post events where the Jagex client changes state. Disassembly of the released artifact shows, for example:

- the NPC composition setter constructing `NpcSpawned`, `NpcDespawned`, and `NpcChanged`;
- scene insertion constructing `GameObjectSpawned` with its `Tile` and `GameObject`;
- interface loading constructing `WidgetLoaded` with its group ID;
- the client-cycle routine draining `ItemContainerChanged` events and then posting `ClientTick`.

That is why a plugin can iterate real NPCs and widgets without inspecting pixels or decoding packets itself.

The current API contains 82 event classes, including NPC/player/object/item lifecycle, containers, varbits, widgets, chat, menus, client scripts, `ClientTick`, and `GameTick` ([events directory](https://github.com/runelite/runelite/tree/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/events)).

Timing matters:

- `ClientTick` occurs in a roughly 20 ms client cycle after packet and interface processing, but before client-script execution, menu sorting, click detection, and `PostClientTick` ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/events/ClientTick.java#L29-L46)).
- `GameTick` is posted after the server tick's packets have been processed and is approximately 600 ms ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/events/GameTick.java#L29-L54)).
- `Hooks.tick` replays deferred events, posts `GameTick`, increments the tick count, and drains `ClientThread` work; `tickEnd` drains end-of-cycle work and posts `PostClientTick` ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/callback/Hooks.java#L220-L290)).
- `EventBus.post` invokes subscribers immediately on the posting thread ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/eventbus/EventBus.java#L202-L221)).

Therefore an event subscriber must copy the state it needs quickly and leave the client thread. A Lua VM must never execute arbitrary script code directly inside a RuneLite callback.

## Rendering

The injected Jagex game engine is still an AWT application. The released `client` extends an obfuscated game-engine class that extends `java.awt.Panel`, owns a `Canvas`, and runs the client thread. RuneLite casts the `Client` to `Component`, places it in `ClientPanel`, and constructs a `JFrame` around it ([`ClientUI` constructor and init](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/ui/ClientUI.java#L195-L210), [`ClientPanel`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/ui/ClientPanel.java#L34-L51)).

### Software path

The normal Jagex rasterizer draws the scene and widgets into the primary buffer. RuneLite exposes that buffer as `BufferProvider` (`int[] pixels`, width, height) and `MainBufferProvider` (`Image`) ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/BufferProvider.java), [source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/MainBufferProvider.java)). `Hooks` adds overlays at scene, under-widget, interface, and always-on-top stages, then presents the image to the canvas ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/callback/Hooks.java#L388-L538)).

### GPU path

The GPU plugin implements `DrawCallbacks`, installs itself with `client.setDrawCallbacks(this)`, sets GPU flags, uploads/swaps scenes, renders with OpenGL, and removes the callbacks on shutdown ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java#L350-L455)). `DrawCallbacks` receives models, scene paints/models, scene begin/end, scene loads, and frame draws ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/hooks/DrawCallbacks.java)).

GenericClient should not replace `DrawCallbacks` merely to observe entities because there is only one active renderer and that would conflict with GPU mode. RuneLite's `RenderCallbackManager` supports multiple entity/tile/object filters and is the less invasive per-render seam ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/callback/RenderCallbackManager.java)). Semantic scripts generally should not depend on either render seam.

## Stable data surface for GenericClient

The existing API is already sufficient for the first scripting version:

| Domain | Primary API |
| --- | --- |
| World | `Client.getTopLevelWorldView()`, `WorldView.getScene()`, collision maps |
| NPCs and players | `WorldView.npcs()`, `players()`, actor location, animation, health, interaction, composition/actions |
| Objects and ground items | `Scene.getTiles()`, then game, ground, wall, decorative objects and tile items |
| Widgets | roots and `Client.getWidget(...)`; child trees, text, item fields, actions, visibility and bounds |
| State | item containers, skills, varps/varbits/varcs, game state, chat, projectiles |
| Events | spawn/despawn/change, containers, widgets, scripts, menus, client/server ticks |
| Actions | `Client.menuAction(...)` on the client thread |

See [`WorldView`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/WorldView.java), [`NPC`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/NPC.java), [`Tile`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Tile.java), [`Widget`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/widgets/Widget.java), and [`Client.menuAction`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Client.java#L2060-L2088).

Dialogue is not one first-class object. It is a semantic view over known widget groups, child text/options, item/model widgets, and `DIALOG`/`MESBOX` chat messages. RuneLite's own crowdsourcing code demonstrates that reduction across `ChatLeft`, `ChatRight`, `Chatmenu`, and object-box interfaces ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/plugins/crowdsourcing/dialogue/CrowdsourcingDialogue.java)). GenericClient should implement the same idea behind a stable `DialogSnapshot` API.

`Client.runScript(Object...)` invokes the game's existing client-script engine. `ScriptPreFired` and `ScriptPostFired` can observe it, but this is not a general source-code runtime: script IDs, stacks, and meanings are internal and revision-sensitive.

RuneLite also includes a JShell console whose prelude exposes `Client`, `ClientThread`, `ConfigManager`, and event subscription helpers ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-jshell/src/main/resources/net/runelite/jshell/prelude.jsh#L28-L75)). It is excellent for interactive reverse engineering, but it directly exposes the JVM and RuneLite implementation and carries Java compilation/class churn. It is not the best low-memory, stable end-user scripting contract.

## Hook-level choices

| Seam | What it sees | Maintenance | Recommended role |
| --- | --- | --- | --- |
| Public API plus events | Decoded semantic state and lifecycle | Lowest | Primary scripting API |
| Tick snapshot/diff | Complete consistent state even where an event is absent | Low | Primary reconciliation layer |
| Script/widget hooks | Client-script and interface transitions | Medium | Dialog/widget adapter and diagnostics |
| Render callbacks | Per-frame visibility, clickboxes, models and tiles | Medium | Graphics diagnostics only |
| Injected packet dispatcher | Decoded packet ID, length, payload and mutation timing | High; revision-pinned | Optional protocol tracer |
| Socket/proxy/pcap | Raw transport bytes | Very high semantic cost | Network diagnostics, not scripting |
| New protocol client | Everything must be reimplemented | Highest | Separate client project, not GenericClient's base |

If packet tracing is needed, install a Java agent or offline ASM transform before the obfuscated `client` class loads. Locate the parser by bytecode structure rather than the current name `client.tg`, inject callbacks immediately after opcode/length decoding and after dispatch, and pin the transform to the exact injected-client hash. This tracer should emit an experimental revisioned record; Lua scripts should still consume semantic snapshots.

## Recommended scripting architecture

Use one RuneLite adapter and keep all RuneLite types on the client thread:

```text
RuneLite events/API
      |
      v
WorldSnapshotCollector ----> immutable frames/deltas ----> Lua scheduler thread
      ^                                                    |
      |                                                    v
ActionDispatcher <---- validated semantic intents <---- script coroutines
      |
      v
ClientThread -> target re-resolution -> Client.menuAction -> action receipt
```

### State rules

- Capture a server-consistent frame on `GameTick` and UI/client deltas at `PostClientTick` or specific events.
- Never pass a live `NPC`, `Widget`, `Tile`, or `Client` object to Lua.
- Give each frame an `epoch`, server tick, client tick, and monotonic sequence.
- Increment the epoch on logout, login, world hop, and equivalent identity resets.
- References include kind, worldview, stable coordinates/index, ID, and spawn generation. An action re-resolves the reference on the client thread and rejects it if it is stale or the requested action no longer exists.
- Wrap stock `ClientThread.invoke` in a GenericClient command queue that completes a `CompletableFuture`; the stock adapter queues `Runnable`/`BooleanSupplier` work but has no value-returning future ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/callback/ClientThread.java#L35-L116)).
- Serialize mutating intents per client. A receipt distinguishes `DISPATCHED`, observed menu action, postcondition success, stale/unavailable rejection, and timeout.
- Build world/object caches from spawn/despawn events and periodically reconcile them against a complete scene scan.
- Record frames, events, intents, and receipts so scripts can be replay-tested without a live client.

The selected Lua interface is deliberately smaller than a module for every RuneLite concept: `gc.read(subject, query)`, `gc.await(request)`, and `gc.log(level, event, fields)`. Subjects and semantic action request types can grow without expanding the top-level host seam. The complete scripting design is in [`lua-scripting-design.md`](lua-scripting-design.md).

### Lua runtime

Lua is a good fit: small language, inexpensive native coroutines, quick reload, and a narrow host interface. Two JVM implementations were investigated:

- [LuaJ](https://github.com/luaj/luaj) is pure Java, but its mainline API targets Lua 5.2, the project is old, and each Lua coroutine creates and parks a Java thread ([source](https://github.com/luaj/luaj/blob/daf3da94e3cdba0ac6a289148d7e38bd53d3fe64/src/core/org/luaj/vm2/LuaThread.java#L27-L57)). That is the wrong scheduler shape for await-heavy scripts.
- [LuaJava](https://github.com/gudzpoz/luajava) actively supports Lua 5.1 through 5.5, LuaJIT, and LuaJ, with desktop native artifacts for current PUC Lua. Its Lua 5.4 adapter uses native Lua coroutine stacks and is the selected runtime.

Use LuaJava 4.1.0 with PUC Lua 5.4. A Java 11 spike verified selective libraries, coroutine yield/resume, a hidden per-coroutine instruction hook, removal of Java/package/I/O/OS access, and the existing GenericClient fat-JAR pattern. The all-platform probe JAR was approximately 1.5 MiB.

Create restricted globals rather than `standardGlobals`: expose no Java bridge, filesystem, network, process, OS, or debug library to normal scripts. Install an internal instruction hook for per-resume budgets, enforce wall-clock deadlines and bounded event queues, and give each script its own VM/environment and lifecycle. Lua runs on a dedicated scheduler thread, never the client thread or Swing EDT.

Scripts use one sequential root coroutine. `gc.await` yields in Lua rather than sleeping or yielding across a Java callback:

```lua
return function()
  while true do
    gc.await { event = "game.tick" }
    local banker = gc.read("npcs", {
      where = { name = "Banker" },
      within = 15,
      action = "Bank",
      limit = 1,
    })[1]

    if banker then
      local receipt = gc.await {
        action = { type = "interact", target = banker.ref, option = "Bank" },
        timeout = { game_ticks = 5 },
      }
      gc.log("info", "bank-action", receipt)
    end
  end
end
```

Compiled Java modules remain a useful second extension type for performance-heavy pathfinding or domain libraries. JShell remains a developer console. An out-of-process controller is useful for multi-language tooling or crash isolation, but a second JVM per client is less resource-efficient than the in-process Lua runtime.

## Headless modes

The packet-tap, virtual interaction display, synthetic camera/pointer, and shadow protocol-reducer design is preserved separately in [`headless-virtual-display-design.md`](headless-virtual-display-design.md). Its implementation is currently deferred while Lua scripting remains active.

Stock RuneLite has no true headless mode. `RuneLite.start` always initializes `ClientUI`; `ClientUI.init` constructs a Swing `JFrame`; and the injected game engine is an AWT `Panel` with a `Canvas`. Setting `java.awt.headless=true` while taking the normal startup path will therefore fail rather than merely hide the window.

The injected `1.12.36` game-engine loop is nevertheless promising. Its `run()` method uses a clock to execute one or more `ue()` logic cycles, then conditionally calls `cb()` for drawing and presents the canvas. Logic and rendering are separate. RuneLite's FPS-control source also states that the engine maintains 50 cycles per second even when drawing is forced to 1 FPS ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-client/src/main/java/net/runelite/client/plugins/fps/FpsDrawListener.java#L29-L48)).

When headless work is explicitly resumed, implement it in stages:

### Stage 1: displayless profile

- Keep the normal injected client and AWT surface.
- Run it on a virtual display on Linux, or a hidden/minimal window on Windows.
- Disable GPU, overlays, sidebar plugins, Discord, telemetry, and screenshots.
- Enable `Client.changeMemoryMode(true)`, which skips floors and lowers ground texture quality ([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Client.java#L1238-L1253)).
- Limit drawing to 1 FPS, mute music/effect/area volumes, and use direct `menuAction` dispatch instead of `Robot`.
- Preserve every 20 ms logic cycle and every server tick.

This is the fastest working mode and provides the parity baseline for more invasive work.

### Stage 2: render-gated runner

Add a revision-pinned transform around the injected game-engine draw branch. Continue every logic cycle, but call the draw/present path only:

- at 1 FPS initially;
- on login/loading transitions;
- after important widget loads if layout data is needed;
- when a screenshot or visible mouse action is requested.

Use semantic `menuAction` dispatch so normal actions do not require current canvas geometry. Expect `BeforeRender` and per-frame render callbacks to stop when frames are skipped. Validate dialog/widget state carefully because some geometry and clickboxes are calculated during rendering.

### Stage 3: dedicated GenericClient runner

Fork the small startup shell, not the entire gameplay API:

- retain `ClientLoader`, the injected client, Guice, `ClientThread`, `EventBus`, and GenericClient;
- replace `Hooks` with a UI-free callback implementation or make its draw methods no-ops;
- skip `ClientUI.init/show`, most core plugins, overlays, Discord, notifications, and toolbar construction;
- provide only the minimum AWT/offscreen surface still required by the injected game engine;
- render on demand through the gate.

Only after profiling should this mode selectively avoid model, texture, sprite, or audio work. The client still needs config, map/collision, interface, and client-script archives, and model construction may occur outside final presentation. Blindly suppressing archive loads will break initialization or state semantics.

### Stage 4: protocol-native client

Removing the injected Jagex client entirely would require reimplementing packet framing and revision changes, cache/archive definitions, maps/collision, client scripts, interfaces/dialogue, entity lifecycle, pathing semantics, and authentication/session bootstrap. That is a new client, not a lightweight RuneLite mode, and loses the main advantage of following Jagex behavior automatically.

### Jagex Launcher compatibility

GenericClient's current [`install.ps1`](../install.ps1) changes RuneLite's launch configuration so the Jagex Launcher invokes [`GenericClientLauncher`](../src/test/java/com/genericclient/GenericClientLauncher.java), which registers the plugin and delegates to `RuneLite.main`. A future mode-selecting wrapper can preserve the same launcher-provided process context and choose visible or displayless/headless startup. Rendering changes do not themselves replace account/session bootstrap. For unattended remote restarts, launcher/session orchestration remains a separate concern.

Use one game client per JVM. The injected client and RuneLite contain substantial static/global state, so multiple clients in one classloader are not a sensible memory optimization. A multi-client system should supervise multiple displayless processes and expose one control socket per process.

## Implementation sequence

1. Extract the smallest immutable player/NPC/inventory/widget/dialog frame from the current plugin and add epoch/generation references.
2. Add a client-thread `ActionDispatcher` that re-resolves references and uses `Client.menuAction`; preserve the current native-mouse adapter as an optional visible-mode implementation.
3. Embed LuaJava 4.1.0 plus PUC Lua 5.4, the three-function interface, one scheduler thread, one state/root coroutine per script, hidden instruction/deadline hooks, structured logs, and atomic one-file reload.
4. Prove one vertical script that logs nearby NPCs, interacts with a selected target, handles dialog, and never blocks RuneLite's client thread.
5. Add a compact frame/event/intent/receipt journal and replay that script without RuneLite.
6. Expand snapshot subjects and action types only when concrete scripts require them.

The displayless, render-gated, dedicated-runner, and packet-native work is deferred under [`headless-virtual-display-design.md`](headless-virtual-display-design.md) until explicitly resumed.

Current scripting acceptance includes login/logout, world hop, instanced `WorldView`s, NPC index reuse, object spawn/despawn, inventory changes, continue/options/item dialogue, widget load/close, direct world/NPC/object/widget actions, runaway-script termination, reload, and clean native-state shutdown. Headless performance and render-parity acceptance remains in the deferred design.
