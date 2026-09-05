# Java scripting

GenericClient discovers Java classes in `~/.runelite/genericclient/scripts/*.jar`.
A script extends `org.dreambot.api.script.AbstractScript` and declares
`@ScriptManifest`. Build source against `GenericClient-script-api.jar` using
Java 11 bytecode. DreamBot scripts are recompiled against this SDK; private
DreamBot client internals and arbitrary precompiled client JARs are not a
compatibility target.

## Catalog and compilation

Each JAR may contain several script entry points and supporting classes. The
optional `com.genericclient.script.ScriptSettings` annotation gives an entry a
stable catalog ID, choice inputs, cooperative buttons, and random-event NPC IDs.
Without it, the fully qualified Java class name is the ID. Choice defaults,
unique control IDs, public no-argument constructors, and unique event solvers
are validated before publication.

The dashboard loads catalog metadata without running constructors. Each run gets
its own classloader and worker. Loading and startup setup run on that worker
with revocable input authority, so Stop does not wait for setup to finish.
Constructors run with the SDK bound to the worker, followed by `onStart`, repeated `onLoop` calls, and `onExit`. A negative
loop delay finishes the run; other delays are milliseconds.

`script_compile` accepts a class name and one source file. A JDK must be available
in the client process for this operation. Compilation uses Java 11 targeting and
disables annotation processors. The temporary JAR and the resulting catalog are
validated before an atomic replacement. Invalid source or metadata leaves the
installed script usable, including after restart. Compiled JAR loading itself
does not require the compiler.

## Supported DreamBot surface

The SDK implements the public methods present in these packages:

| Area | Types |
| --- | --- |
| Lifecycle | `AbstractScript`, `ScriptManifest`, `Category`, `TaskScript`, `TaskNode` |
| Timing and logging | `MethodProvider`, `Sleep`, `Condition`, `Logger` |
| World queries | `Players`, `NPCs`, `GameObjects`, `Filter`, `Tile`, `Area` |
| Entity wrappers | `Player`, `NPC`, `Character`, `GameObject`, `Entity`, `Identifiable`, `Locatable` |
| Containers | `Inventory`, `Equipment`, `Bank`, `Item`, `ContainerType` |
| Account and dialogue | `Client`, `Skills`, `Skill`, `PlayerSettings`, `Dialogues` |
| Spells and walking | `Magic`, `Normal`, `Spell`, `Walking` |
| Interfaces | `Widgets`, `Widget`, `WidgetChild`, `GrandExchange.isOpen` |

This is the implemented API surface, not a declaration that every DreamBot
class or overload exists. Unsupported members fail source compilation. Methods
are implemented through GenericClient's snapshot and input system; no private
DreamBot implementation is loaded. See the SDK source for exact signatures.

Key contracts:

- `Walking.walk()` dispatches one route step. Its success does not mean arrival.
- `Sleep.sleepUntil` polls conditions, supports resets and consecutive success,
  and excludes manual/emergency pause time from its timeout.
- NPC queries exclude dead NPCs. NPC and object `hasAction` requires every
  supplied action; player `hasAction` accepts any supplied action. Player action
  arrays preserve empty slots and refresh with each snapshot.
- Retained entity handles read current snapshots. Native lifetime IDs prevent
  a recycled NPC index or replaced object from taking over an old handle.
  `exists()` is nonblocking; state getters require a present reference.
- Player, NPC and object queries skip handles that disappear during filtering or ranking.
  Exceptions from the script's filter still propagate. ID overloads accept
  `Integer...`, and a filtered closest query can use an explicit origin tile.
  Wrappers for the same native entity share equality and hash identity.
- Player queries include loaded players on the current plane. IDs, indexes,
  levels, movement and animation come from native actors; other players do not
  receive invented local inventory or skill data.
- `Players.getLocal()` retains the last local-player reference after a frame
  disappears. Use `exists()` or `Client.isLoggedIn()` to check current presence.
- Actor menu selection requires the exact native actor reference. Object and
  ground-item matching also checks the menu action kind before comparing IDs
  and coordinates.
- Item quantity and slot describe the captured item. Interactions resolve and
  verify the current container slot before dispatch. `getContainerType()` keeps
  inventory, equipment and bank ownership distinct even when ID and slot match.
- `Magic.castSpellOn(Spell, Entity)` accepts the source-level entity signature
  and currently supports NPC targets. Item spell targets must belong to inventory;
  a bank or equipment item cannot redirect the cast to a matching inventory slot.
  Supported spells are the implemented `Normal` constants.
- Widget raw IDs, group IDs, child IDs, and subchild indices are distinct.
  `getID()` follows DreamBot's child-ID behavior; `getRawId()` returns the packed ID.
- Widget lookups retain loaded hidden controls. Use `isVisible()` or
  `Widgets.isVisible(...)` to check display state. Queries traverse all loaded
  nodes, including attached interfaces, without a fixed capture limit. Semantic
  `widgets` reads default to visible controls and accept `include_hidden=true`.
- Dialogue text preserves RuneScape markup and returns an empty string when no
  conversation is open. Numbered choices start at one and preserve the selected
  option when labels repeat. Text selection ignores case; native input rechecks
  the captured label and option index.

The unchanged example from DreamBot's official first-script guide is compiled
and executed in the host tests. Separate tests cover the actual lifetime,
interaction, pause, cancellation, catalog, and journey contracts.

## Ownership and pauses

Only the current run may issue script input. Stop revokes its input authority
before returning; late completions cannot advance a replacement. End-of-run
input cleanup happens before user `onExit`, so old cleanup cannot reset a new
owner. Script exceptions and errors are reported as faults. An action timeout
returns a `timed_out` receipt and revokes that action's input, allowing the script
to handle the failure and continue with a fresh action.

Physical mouse takeover and emergency recovery have independent pause ownership.
Releasing one does not release the other. Pauses suspend the current worker and
its input authority, preserve a retained walk, and resume through fresh target
resolution. Manual and ESC stops are unconditional. A scheduled stop calls
`onScheduledStop()` on the script worker; false defers the stop, with at most one
check per active second. True permits stopping. A callback can use the SDK
without recursively triggering itself.

`onPaint(Graphics2D)` begins only after `onStart` completes and finishes before
teardown. Paint may read snapshots but cannot issue input or block the client
thread with SDK waits. The host isolates script paint exceptions from rendering.

Scripts are trusted Java code in the client JVM. Classloader ownership is not a
security sandbox. Revoking SDK authority cannot forcibly terminate arbitrary
Java loops or undo unrelated file/network operations performed by a script.

## GenericClient workflow extensions

`Automation` provides configuration values, cooperative buttons, phase/activity,
results, overlay rows, scene markers, and account-bound numeric checkpoints.
Checkpoint IDs support Java class names as well as catalog IDs; restarting a host
preserves the stored progress and keeps script namespaces and accounts separate.
`Banking.loadout` prepares and verifies a complete inventory. `Navigation` provides
complete journeys and interruption receipts.

`Automation.activity(name, policy)` sets a persistent activity with optional
independent overrides such as `Map.of("breaks", true, "prayer_owner", "script")`.
An explicit declaration takes precedence over action inference. Operation arguments
can supply `activity`, `policy` and `humanize` overrides for that operation only.
`Automation.phase(name, options)` returns its behavior receipt, and
`Automation.sleepTicks(ticks, options)` applies temporary wait policy. The normal
DreamBot `Sleep` methods use the current declaration. Invalid options fail before
input; they do not replace the previous declaration.

`Automation.intent(name, supplier)` groups nested operations under one outer
behavior boundary and returns the supplier's value. The supplier runs on the
script worker. Inner actions and phases suppress discretionary behavior while
preserving their activity's safety settings. Pause preserves the scope; Stop
revokes its input. Errors unwind the scope before propagating to the caller.
The behavior snapshot reports the current intent, depth, active elapsed time,
and last completed scope. An intent running longer than 30 active seconds logs
one warning while continuing to wait for its action.

```java
Automation.activity("travel");
Navigation.Journey journey = new Navigation.Journey(new Tile(3210, 3424), 1)
    .via(new Tile(3200, 3424))
    .avoiding(List.of(new Tile(3208, 3423)))
    .timeout(600);
Map<String, Object> receipt = Navigation.walk(
    journey, Map.of("dialogue", true), null);
```

A journey can specify ordered via points, permitted arrival alternatives, and
avoided tiles. Interrupt predicates cover dialogue, areas, poison, missing items,
inventory/skill minimums, varbit equality, and run energy. An interrupted or
unavailable journey can return a single-use continuation token. Resume the same
destination, radius, via points, and arrival alternatives while refreshing the
upkeep predicates and avoided tiles. The caller owns how to handle the interrupt.

The maintained catalog contains those account and quest decisions. The client
owns graph planning, target resolution, synthetic input, and receipts.

## References

- [DreamBot script lifecycle](https://dreambot.org/javadocs/org/dreambot/api/script/AbstractScript.html)
- [DreamBot walking](https://dreambot.org/javadocs/org/dreambot/api/methods/walking/impl/Walking.html)
- [DreamBot timing](https://dreambot.org/javadocs/org/dreambot/api/utilities/Sleep.html)
- [DreamBot dialogues](https://dreambot.org/javadocs/org/dreambot/api/methods/dialogues/Dialogues.html)
- [DreamBot entity methods](https://dreambot.org/javadocs/org/dreambot/api/wrappers/interactive/Entity.html)
- [DreamBot item containers](https://dreambot.org/javadocs/org/dreambot/api/wrappers/items/Item.html)
- [DreamBot spell targets](https://dreambot.org/javadocs/org/dreambot/api/methods/magic/Magic.html)
- [DreamBot widget queries](https://dreambot.org/javadocs/org/dreambot/api/methods/widget/Widgets.html)
- [DreamBot widget groups](https://dreambot.org/javadocs/org/dreambot/api/methods/widget/Widget.html)
- [Official first-script example](https://dreambot.org/guides/scripting/creating-your-first-script/)
