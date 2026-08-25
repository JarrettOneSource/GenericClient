# Headless virtual-display design

Status: **Deferred**. This document preserves the agreed technical direction. Lua scripting remains the active design and implementation track.

Research baseline: RuneLite [`2624bcc`](https://github.com/runelite/runelite/tree/2624bcc4136cea1011bf1bb154581a4b16c7a3ca) and released `injected-client` 1.12.36.

## Decision

The preferred headless architecture is a hybrid client kernel:

- Tap decoded inbound and outbound packets for recording, replay, and protocol research.
- Keep the injected Jagex client as the authoritative packet decoder, cache consumer, client-script engine, and world-state reducer initially.
- Replace the visible RGB renderer with a virtual interaction display containing entity/tile IDs, depth, widget hit regions, and menu candidates.
- Simulate camera and pointer state, then feed virtual input through Jagex's input/action machinery so its current outgoing packet implementation remains authoritative.
- Develop an independent packet-driven state reducer in shadow mode and compare it with Jagex state before replacing any part of the client kernel.

The virtual display simulates interaction semantics rather than visual appearance.

```text
Inbound socket
    |
    v
Jagex packet decoder ------> decoded packet recorder/replay
    |
    v
Jagex state + cache definitions + client scripts
    |
    v
Headless scene/hit-test pass
    |
    v
ID/depth buffer + widget hit tree + menu candidates
    |
    v
Virtual camera and pointer
    |
    v
Jagex input/menu/action machinery
    |
    v
Outbound socket ------> outbound packet recorder
```

## Why the raw packet stream is not the complete state

Packets are only one input to the client state machine. A faithful headless kernel also needs:

- cache definitions, maps, models, interfaces, and client scripts;
- client-cycle time and animation advancement;
- local camera, pointer, keyboard, and focus state;
- client-script effects on widgets and local variables;
- menu construction, depth ordering, and hit testing.

A packet may open an interface group, but locally executed client scripts can determine its children, text, layout, visibility, and actions. Camera and pointer positions are also locally owned. The complete reducer input is therefore closer to:

```text
ServerPacket
CacheDefinition
ClientTick
ClientScriptEffect
LocalInput
ClockAdvance
```

Starting with the injected client retains these semantics while the independent model is built and verified.

## External seam

Use one deep `HeadlessClientKernel` module. Callers, including Lua, should not learn packet opcodes, RuneLite objects, rasterizer details, or AWT event types.

```java
public interface HeadlessClientKernel extends AutoCloseable
{
    WorldFrame observe();

    CompletionStage<InputReceipt> submit(VirtualInputSequence input);
}
```

The interface includes these invariants:

- frames are immutable and monotonically sequenced within an epoch;
- epoch changes invalidate every entity and widget reference;
- all input is based on a specific frame and expires after a deadline;
- the kernel re-resolves targets immediately before dispatch;
- `DISPATCHED` only means input reached the client action machinery;
- observed menu selection, emitted packet, and world postcondition are separate receipt stages;
- packet and input callbacks never block the client thread.

## Internal seams and adapters

These seams are real because each has multiple justified adapters:

| Seam | Adapters |
| --- | --- |
| State source | Live injected client; recorded replay; future protocol reducer |
| Viewport | Offscreen RGB oracle; Jagex-assisted hit-test pass; independent semantic ID renderer |
| Input | AWT canvas events; injected Jagex-input adapter; direct `menuAction` diagnostic adapter |
| Clock | Live client clock; deterministic replay clock |

Lua and other callers use only `HeadlessClientKernel`; the adapter selection remains internal.

## Virtual interaction display

The output is a `HitFrame`, not a screenshot:

```text
HitFrame
  epoch and frame sequence
  camera pose and viewport
  entity or tile ID per covered pixel/region
  depth or occlusion ordering
  widget rectangles and z-order
  menu candidates at the virtual pointer
```

Three implementations provide an incremental route.

### Offscreen RGB oracle

Run the normal software renderer into an offscreen image. This is the easiest reference implementation and produces authoritative comparison data, but it retains most rendering cost.

### Jagex-assisted hit-test pass

Retain scene traversal, projection, depth ordering, clickbox testing, and menu construction while suppressing texture sampling, lighting, shading, RGB writes, overlays, and canvas presentation. Run this pass only when camera or pointer state changes, an interaction is planned, or an oracle comparison is requested.

This is the preferred implementation because it reuses Jagex behavior without paying for visible rendering.

RuneLite already exposes camera position/yaw/pitch, viewport geometry, target camera controls, worldview projections, model clickbox testing, convex hulls, and tile/object clickboxes. See [`Client`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Client.java#L227-L370), [`WorldView.getCanvasProjection`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/WorldView.java#L229-L246), and [`Perspective.getClickbox`](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Perspective.java#L970-L1080).

### Independent semantic ID renderer

Project snapshot geometry using a virtual camera and write target identifiers plus depth rather than color. Widgets form a separate rectangle/z-order tree. This can eventually operate without Jagex rendering code, but it must reproduce occlusion and menu-selection semantics and should remain behind the same viewport seam.

## Virtual camera and pointer

For a target interaction:

1. Resolve the target in the selected world frame.
2. Find visible pixels or hit regions belonging to that target.
3. Select a point inside a valid region.
4. Generate a timestamped mouse path from the current virtual pointer.
5. Apply camera motion if the target is not visible.
6. Feed move/press/release events through the selected input adapter.
7. Observe the selected menu entry, emitted action, outbound packet, and postcondition.

The first input adapter can retain a hidden or virtual AWT `Canvas` and dispatch events through the normal listener chain. A later AWT-free adapter should instrument the Jagex mouse and keyboard handlers and update their underlying queues/state directly.

Camera movement can similarly begin with synthetic input or RuneLite's camera target methods. The final input adapter should feed the same state used by the Jagex client so it remains responsible for any current camera, pointer, click, and action packet formats.

## Packet tap and replay

Install the tap after opcode and packet-length decoding, before the Jagex dispatch chain consumes the payload. The current released artifact performs this work in obfuscated `client.tg(client, dj)`; that name is revision-specific.

```text
PacketRecord
  injected-client hash and game revision
  direction
  decoded opcode
  immutable payload copy
  client cycle and server tick
  monotonic sequence and timestamp
```

The transform must:

- install before the injected `client` class loads;
- locate the parser by bytecode structure, not its current name;
- pin itself to the exact injected-client hash;
- copy payload bytes before dispatch mutates buffer position/state;
- keep I/O and serialization off the client thread;
- capture the outgoing direction at a semantic action or packet-writer seam.

Recorded sessions support deterministic replay, correlation of packets with mutations, visible-versus-headless comparisons, and development of the shadow reducer.

## Shadow protocol reducer

The independent reducer consumes recorded packets, cache data, deterministic ticks, local input, and modeled client-script effects. During migration it runs beside the injected client:

```text
same ordered inputs
      |--------------------|
      v                    v
Jagex client state    Shadow reducer state
      |                    |
      +------ diff --------+
```

Promote one state family at a time only after replay and live comparisons agree: login/game state, players, NPCs, scene objects, containers, varps, widgets/dialogue, projectiles, and menus. The Jagex state adapter remains the oracle until the complete acceptance inventory closes.

## Staged implementation

1. Add decoded inbound/outbound packet recording.
2. Add a deterministic replay clock and immutable world frames.
3. Model virtual camera and pointer state.
4. Retain an offscreen real-render oracle.
5. Implement the Jagex-assisted hit-test viewport.
6. Feed virtual input through the full Jagex input chain.
7. Disable normal RGB rendering and canvas presentation.
8. Implement and differentially test the shadow packet reducer.
9. Replace injected-client subsystems only after state-family parity.

## Verification

For the same recorded state, camera, and pointer, compare the offscreen oracle and hit-test viewport on:

- top menu entry and complete candidate ordering;
- selected entity, tile, or widget;
- occlusion and overlapping targets;
- instanced worldviews and transformed NPCs/objects;
- widget visibility, clipping, scroll position, and z-order;
- emitted semantic action and outbound packet;
- action postconditions.

Performance comparisons require controlled visible baseline, offscreen oracle, hit-test-only, and restored-baseline runs. Measure client-cycle and server-tick cadence, CPU, RSS, heap, allocation rate, and interaction latency. Do not infer savings from the absence of a window alone.

## Deferred boundary

No headless implementation begins until explicitly resumed. The Lua scripting interface should depend on immutable frames and semantic input receipts so it remains unchanged when the live client adapter is later replaced by this headless kernel.
