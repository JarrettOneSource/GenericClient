# Multi-instance memory optimization research

Status: research only. No runtime or Harness implementation is included here.

## Goal

Run roughly ten GenericClient instances on Linux with the official Jagex
Launcher and RuneLite while reducing marginal RAM and CPU cost without
weakening script reliability.

The important metric is the physical cost of the fleet and of each additional
client, not the sum of per-process RSS values. The design should preserve one
client session per failure domain until measurements justify a more invasive
multi-session client.

## Current local evidence

- The installed RuneLite runtime is Temurin HotSpot 17.0.19.
- RuneLite's current bootstrap arguments include `-Xmx768m`, `-Xss2m`,
  `-XX:CompileThreshold=1500`, and `-XX:+DisableAttachMechanism`.
- The currently loaded Windows RuneLite process had about 1.1 GiB working set,
  about 1.15 GiB private bytes, and 86 threads during this investigation. Those
  are not Linux PSS measurements and must not be projected directly to ten
  clients.
- `GenericClient.jar` is about 36 MiB and contains 12,648 classes: 4,015 under
  `net.runelite` and 224 under `com.genericclient`.
- The injected `client` class alone exposes 303 static members. RuneLite,
  configuration, callbacks, UI, and plugin management add more process-global
  state.
- The bundled mouse profile contains 6,069 templates. Its retained primitive
  arrays are approximately 5-6 MiB per process after parsing.
- The bundled collision map expands to about 4.4 MiB plus its map and array
  overhead per process.
- GenericClient currently constructs presentation, overlays, dashboard,
  synthetic input, Lua, planning, control, behavior, and safety from one plugin
  startup path.

These observations establish candidates; they are not yet a Linux baseline.

## Measure the right memory

Linux exposes each mapping's RSS, proportional set size (PSS), and private and
shared pages through `/proc/<pid>/smaps`; `smaps_rollup` provides the aggregate
at much lower read cost. PSS divides each shared resident page among its
sharers. `Private_Clean + Private_Dirty` is a useful approximation of unique
set size (USS). [Linux proc documentation](https://docs.kernel.org/filesystems/proc.html),
[`proc_pid_smaps(5)`](https://man7.org/linux/man-pages/man5/proc_pid_smaps.5.html)

For every experiment record:

- fleet total and per-instance PSS, USS, RSS, swap, and `Pss_Anon/File/Shmem`;
- marginal PSS when moving from 1 to 2, 4, 6, 8, and 10 clients;
- cgroup v2 `memory.current`, `memory.peak`, `memory.stat`, swap, and memory PSI;
- Java heap committed/used/live-set, class count, metaspace, code cache, GC
  threads, thread stacks, and native allocations;
- CPU, major faults, GC pauses, client-tick and game-tick gaps, action latency,
  and script receipts.

Use HotSpot Native Memory Tracking only in profiling runs. It separates Java
heap, class metadata, threads, code, and GC memory, but excludes some third-party
native allocations and imposes an officially documented 5-10% overhead.
[Oracle NMT](https://docs.oracle.com/en/java/javase/17/vm/native-memory-tracking.html)

Use JFR allocation profiles and a heap histogram/dump to identify retained
objects. Measure stable scenarios separately: login screen, idle world, walking,
bank, widget-heavy interaction, combat, and on-demand screenshot.

## What Linux already shares

Processes using the same JRE, JARs, native libraries, and immutable data files
already share clean file-backed pages through the kernel page cache. Every
instance must therefore use the same inodes for versioned immutable artifacts;
copying the same bytes into ten private instance directories defeats this
sharing.

Use one read-only artifact set for:

- the JRE;
- GenericClient/RuneLite JARs;
- an AppCDS/AOT archive;
- immutable GenericClient data;
- the installed Lua catalog.

Give each instance a private writable profile, account state, logs, transient
cache, and descriptor directory. Containers, if used, should share one
read-only lower layer rather than contain ten independent copies.

## High-value near-term design

The preferred practical architecture is a dense RuneLite agent behind the
existing client-kernel seam:

```text
Official Jagex Launcher under Wine
             |
             v
Harness launch bridge and fleet runtime
  |-- shared JRE, JAR, CDS/AOT archive, immutable data, Lua catalog
  |-- shared planning, mouse-template selection, and fleet orchestration
  |
  |-- RuneLite agent A: Jagex state + snapshots + actions + safety
  |-- RuneLite agent B: Jagex state + snapshots + actions + safety
  `-- RuneLite agent N: Jagex state + snapshots + actions + safety
```

The Launcher remains the authentication and character-selection owner. A
Linux launch adapter can run the official Launcher binary under Wine and bridge
its normal RuneLite child handoff to a native Linux JVM. This needs a dedicated
proof; running RuneLite itself under Wine is the fallback oracle, not the
memory-optimal endpoint.

The RuneLite-side external interface should remain small:

```java
interface ClientKernel extends AutoCloseable
{
    WorldFrame observe();
    CompletionStage<ActionReceipt> submit(ActionIntent intent);
    CompletionStage<RenderedFrame> render(RenderRequest request);
}
```

Keep the safety controller, authoritative frame collection, target
re-resolution, action dispatch, lifecycle, and minimal control transport inside
each client. Move reusable catalog data, global collision data, mouse-template
selection, planning, and fleet-level scheduling into the Harness.

## Dense RuneLite runner

A plugin can enable low-memory mode, limit FPS, remove overlays, and clear
renderer caches, but it cannot avoid constructing much of normal RuneLite's UI
and plugin graph. GenericClient already owns the main entry point, so the larger
near-term win is a dedicated minimal RuneLite runner that retains:

- `ClientLoader` and the injected Jagex client;
- Guice bindings required by the client and GenericClient;
- `ClientThread`, callbacks, event buses, configuration, and session handoff;
- the thin GenericClient agent and local safety;
- an offscreen AWT surface and rendering on demand.

It should omit or delay:

- normal `ClientUI` and sidebar construction;
- toolbar, dashboard, tray, notifications, Discord, and telemetry;
- Plugin Hub and unrelated core plugins;
- GPU/LWJGL initialization;
- overlays except during an explicitly visible diagnostic mode;
- eager screenshots and renderer-only asset retention.

The renderer should have three internal modes:

1. `STATE_ONLY`: preserve every logic cycle and server tick, but suppress normal
   scene presentation and renderer-only work.
2. `ON_DEMAND`: render login/loading transitions, relevant widget changes, and
   requested screenshots.
3. `VISIBLE_ORACLE`: normal software rendering for differential acceptance.

The visible oracle and dense runner must execute the same script scenarios.
Acceptance is based on frames, event ordering, action receipts, and
postconditions, not merely on a lower process-memory number.

One shared Xvfb process for the fleet may save more memory than one X server per
client because GenericClient actions do not require the operating-system cursor.
Benchmark one Xvfb, one Xvfb per client, and two five-client Xvfb shards. The
sharded option may be the best compromise between memory and failure isolation.

## Sharing application classes with AppCDS

HotSpot's base CDS archive is already active in the current JRE. AppCDS extends
this to application and library class metadata and memory-maps the archive so
multiple JVMs share read-only pages. JDK 17 supports application classes and
supported custom class loaders. [Oracle CDS](https://docs.oracle.com/en/java/javase/17/vm/class-data-sharing.html)

Build a representative archive after RuneLite and GenericClient reach a stable
state, then launch clients with `-Xshare:auto`. Version the archive by:

- JDK vendor/version/hash;
- architecture and operating system;
- GenericClient/RuneLite/injected-client hashes;
- class path and relevant VM flags.

Regenerate atomically when any input changes. Never use archive size as the
claimed saving; compare fleet PSS. Runtime bytecode transformation can make
transformed classes ineligible for sharing, so record the shared/unshared class
log whenever render transforms change.

AppCDS is the first direct experiment for reusing the same runtime bits. A
modest result measured in tens of MiB per additional client would still
compound usefully across ten processes.

## Newer HotSpot density features

JDK 25 makes compact object headers a supported opt-in feature. It reduces
object headers from 96/128 bits to 64 bits and Oracle describes an average
saving of about four bytes per object. This reduces each private Java heap; it
does not share ordinary heap objects across processes. The reported 22% saving
in one SPECjbb configuration is evidence that the feature can matter, not an
estimate for RuneLite. [JDK 25 Java launcher](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html),
[JEP 519 record](https://bugs.openjdk.org/browse/JDK-8354672)

JDK 25/26 AOT caches extend CDS with ahead-of-time class loading/linking,
profiles, and cached startup objects. Their compatibility and steady-state PSS
benefit must be tested with the exact injected client and native Lua library.
[OpenJDK Leyden](https://openjdk.org/projects/leyden/)

The experiment order is:

1. Temurin HotSpot 17 baseline;
2. HotSpot 17 plus AppCDS;
3. HotSpot 25/26 compatibility baseline;
4. compact headers;
5. AOT cache plus compact headers.

Do not change the production JVM until login, widgets, Lua, actions,
screenshots, shutdown, and long-running GC behavior pass.

## JVM and native-memory tuning

The current `-Xmx768m` is a ceiling, not proof of a 768 MiB live heap. Choose a
smaller ceiling only after measuring the live set and allocation headroom.
Lower `MinHeapFreeRatio`/`MaxHeapFreeRatio` and collector choices are valid
experiments, but GC CPU and pauses are part of acceptance. Oracle explicitly
notes that minimizing heap size reduces dynamic footprint while potentially
reducing throughput. [Oracle GC footprint guidance](https://docs.oracle.com/en/java/javase/17/gctuning/factors-affecting-garbage-collection-performance.html)

Ten JVMs should not each size GC, compiler, and common pools as if they owned
the whole machine. Benchmark:

- `-XX:ActiveProcessorCount=2` per client;
- G1 with reduced automatic thread counts versus Serial GC;
- C1-only or a lower tiered-compilation ceiling versus normal tiered JIT;
- a smaller code cache if measurements show unused committed code memory;
- a lower thread stack only after a stack-depth stress suite.

The observed 86-thread client and `-Xss2m` make thread count and committed stack
pages worth measuring, but virtual stack reservation is not physical RAM.

On Linux, glibc can create allocator arenas based on online CPU count. Benchmark
`GLIBC_TUNABLES=glibc.malloc.arena_max=2` and, on JDK 25+, a conservative
`-XX:TrimNativeHeapInterval`. Both can reduce retained native pages but may
increase allocator contention or trimming CPU. [glibc malloc tunables](https://sourceware.org/glibc/manual/latest/html_node/Tunables.html),
[JDK 25 launcher options](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html)

## Shared immutable GenericClient data

Convert large immutable GenericClient resources to a versioned binary file and
map the same read-only inode into every process:

- packed mouse-template metadata and primitive paths;
- packed global collision regions;
- other measured immutable lookup tables.

Use `FileChannel.map(READ_ONLY, ...)` or an equivalent foreign-memory adapter.
Avoid constructing thousands of per-template Java objects and arrays. Linux
then accounts the pages as shared file-backed memory, and each process retains
only a small view/index.

This is a concrete saving of roughly 10 MiB plus parsing/transient allocation
per additional client from the two currently identified resources. It is not
the largest opportunity, but it is deterministic and low-risk.

Do not attempt to externalize Jagex definition caches before a heap profile
shows their retained size. The injected client expects Java object graphs; a
shared serialized copy does not remove its private copy unless the consumer is
rewritten.

## Linux KSM experiment

Kernel Samepage Merging scans registered anonymous private pages and replaces
identical pages with copy-on-write shared pages. Linux warns that scanning can
consume considerable CPU and exposes both profitability and COW counters.
[Linux KSM](https://docs.kernel.org/admin-guide/mm/ksm.html)

Modern Linux provides `PR_SET_MEMORY_MERGE`. It enables KSM for compatible VMAs
for a process and is inherited across fork/exec, allowing the Harness to use a
small `ksm-run` wrapper before `exec(java)` without modifying HotSpot.
[Linux KSM self-test](https://android.googlesource.com/kernel/common/+/dc99c0ff53f588bb210b1e8b3314c7581cde68a2/tools/testing/selftests/mm/ksm_functional_tests.c)

Treat KSM as an isolated A/B test after AppCDS and the dense runner:

- enable it only for the test fleet;
- use conservative scan settings/smart scan;
- record `pages_sharing`, `pages_unshared`, `pages_volatile`, per-process
  `ksm_stat`, `cow_ksm`, ksmd CPU, PSS, and tick/action latency;
- disable it if the CPU/COW cost or jitter offsets the saved pages.

Moving Java heaps contain pointers and frequently changing objects, so the
actual yield may be small. KSM is not a substitute for explicit file-backed
sharing.

## OpenJ9 experiment

Eclipse OpenJ9 can share class data, AOT code, and JIT data in a cross-VM shared
cache. This is a potentially stronger sharing mechanism than HotSpot AppCDS.
[OpenJ9 shared classes](https://eclipse.dev/openj9/docs/shrc/),
[`-Xshareclasses`](https://eclipse.dev/openj9/docs/xshareclasses/)

Run it as a separate compatibility adapter after the HotSpot lanes. Required
proof includes AWT, injected-client loading, Guice, LuaJava native loading,
Jagex session handoff, screenshots, and long script runs. Do not introduce JVM
abstraction into the Harness until both HotSpot and OpenJ9 actually pass.

## Why one JVM with ten RuneLite class loaders is unattractive

Separate child class loaders could technically give each copy of `client` its
own static fields, but they would also duplicate RuneLite and injected-client
class metadata and compiled methods. AWT toolkit/event state, system
properties, native libraries, default handlers, filesystem globals, and JNI
loading remain process-wide. The native Lua library adds another class-loader
ownership problem. One crash or GC pause would affect the whole fleet.

The interface complexity and reduced isolation are not justified by the likely
savings. Keep one injected client per JVM and share file-backed data explicitly.

Forking a warmed multithreaded JVM is not a viable clone mechanism. After
`fork()`, POSIX permits only async-signal-safe work before `exec`, while JVM,
AWT, JIT, and native-library locks may be held by threads that no longer exist.
CRaC targets startup restoration and requires socket/file/native-resource
coordination; it does not promise shared steady-state heaps.

## Protocol-native multi-session client

A custom client is the theoretical density ceiling. One process could share:

- protocol code and I/O scheduler;
- cache definitions, maps, collision, interfaces, and client-script data;
- immutable navigation and item data;
- Lua code/catalog and worker pools;
- one control and observability runtime.

Each session would retain only its socket/crypto, entity state, local player,
containers, varps, widget/client-script state, action receipts, and script
state. A struct-of-arrays or arena representation could be far denser than ten
general-purpose Java object graphs.

It would also require maintaining authentication/session bootstrap, protocol
framing and revision changes, JS5 cache/archive handling, maps and collision,
entity lifecycle, client scripts and widgets, menus/actions, time, and local
state semantics. The correct path is differential development:

1. keep the injected Jagex client as the oracle;
2. record ordered inputs and immutable semantic frames;
3. run a shadow reducer beside it;
4. promote state families only after replay and live parity;
5. preserve the same `ClientKernel` interface when a native adapter becomes
   complete.

The dedicated minimal RuneLite runner is therefore the recommended advanced
implementation. The protocol-native client remains a deliberate later program,
not an optimization hidden inside a plugin.

## Recommended experiment ladder

1. Build the Linux memory laboratory in the Harness: PSS/USS, cgroup, NMT/JFR,
   GC, thread, tick, action, and script-receipt capture.
2. Establish visible software-rendered 1/2/4-client baselines using the same
   JRE/JAR inodes.
3. Establish the displayless low-memory profile and visible-oracle parity.
4. Remove unrelated Plugin Hub/core plugins and split GenericClient runtime
   from presentation.
5. Implement the dedicated minimal RuneLite runner.
6. Add and measure AppCDS.
7. Pack/mmap GenericClient mouse and collision data; centralize reusable
   planning and Lua orchestration where measurements justify it.
8. Test JDK 25/26 compact headers and AOT cache.
9. Tune processor count, GC/JIT threads, stacks, glibc arenas, and native trim.
10. Scale through 2/4/6/8/10 clients and identify marginal PSS.
11. A/B KSM, shared versus sharded Xvfb, zram/zswap, and idle-client reclaim.
12. Run an isolated OpenJ9 shared-cache comparison.
13. Begin the protocol shadow reducer only if the dense RuneLite result still
    misses the fleet target.

## Acceptance contract

Every optimization must report:

- exact OS/kernel/JDK/RuneLite/GenericClient/injected-client hashes;
- fleet and marginal PSS/USS with warm-cache and cold-cache runs;
- CPU, page faults, memory/CPU PSI, GC pauses, and thread count;
- complete client/game tick continuity;
- login, world hop, NPC/object lifecycle, inventory, bank, dialogue, widgets,
  random-event attention, recovery, logout, and screenshot receipts;
- an A/B restored-baseline run after the optimized run.

An optimization is accepted only when its memory saving is repeatable and its
script outcomes and latency remain within the chosen baseline tolerances.
