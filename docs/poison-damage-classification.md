# Poison damage classification

Status: source implementation and automated tests complete, checked 2026-09-05. This note refines the damage
classification proposed in [behavior-framework-review.md](behavior-framework-review.md)
P3. No live account observations were made. `GenericClientDamageTracker` implements
the conservative matcher with copied local-player hitsplat evidence; runtime tests
verify that expected poison and declared combat preserve a break while ordinary
damage hidden by healing interrupts it.

An active poison varp and an equal HP decrease do not establish the damage
source. The narrow snapshot matcher below identifies a poison or venom
candidate. It must preserve independent threat detection and existing damage
grace. Disease has no supported HP exemption in this research.

## Encoding and damage

`VarPlayerID.POISON` is varp **102**; `DISEASE` is the separate varp **456**.
They are not varbits. RuneLite's documented poison encoding is:

| Value | Meaning |
| --- | --- |
| `v < -38` | Venom immunity, then poison immunity at `-38` |
| `-38 <= v < 0` | Poison immunity |
| `v == 0` | No poison or poison immunity |
| `1 <= v <= 100` | Normal poison severity; next damage `(v + 4) / 5` using integer division |
| `v >= 1_000_000` | Venom; next damage `min(20, 6 + 2 * (v - 1_000_000))` |

Sources: [generated varp IDs](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/gameval/VarPlayerID.java),
[RuneLite varp documentation](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/VarPlayer.java#L62-L83).

Current native client scripts independently confirm both formulas. Poison
script 5346 computes `(min(v, 100) + 4) / 5`; venom script 5347 caps damage at 20. The
native UI treats positive values below one million as poison, including values
above RuneLite's documented normal range. Conservatively reject `101..999999`
for automatic attribution rather than assuming those states are understood.
[Native poison formula](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/%5Bproc%2Cscript5346%5D.cs2),
[native venom formula](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/%5Bproc%2Cscript5347%5D.cs2),
[native next-hit display](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/%5Bproc%2Cbuff_bar_get_top_value%5D.cs2).

For Java, compute venom with `long` arithmetic or cap `v` at `1_000_007`
before multiplying. The severity can continue increasing after damage reaches
20; an unchanged damage amount does not imply an unchanged varp.
[RuneLite poison implementation](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-client/src/main/java/net/runelite/client/plugins/poison/PoisonPlugin.java#L218-L240).

## Timing and which value to use

The native cache gives both next-hit timers a **30 game tick** interval,
approximately 18 seconds. In the September 2 revision 240 dump,
`dump/structs/3740.json` and `3741.json` have `param_1541 = 30` and identify the
next poison and venom hit respectively. Native script 5940 computes remaining
time from that interval, `map_clock`, and a separate client variable
`varcint983`. The poison varp therefore does not provide the countdown phase.
[Cache release and dump](https://github.com/abextm/osrs-cache/releases/tag/2026-09-02-rev240),
[native timer selection](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/%5Bproc%2Cbuff_bar_get_value%5D.cs2#L218-L225),
[native countdown](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/%5Bproc%2Cscript5940%5D.cs2),
[RuneLite game tick contract](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/events/GameTick.java#L29-L44).

RuneLite recalculates its **next** damage from every new poison value and
restarts its display timer. Its natural-cure estimate is one remaining poison
interval per severity unit, while venom advances one severity unit per damage
step. Its `18_200` millisecond display constant is not an exact server-tick
clock. The supported natural-progression model is poison `v -> v - 1` and
venom `v -> v + 1` at the damage interval.
[RuneLite timer and next-damage calculation](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-client/src/main/java/net/runelite/client/plugins/poison/PoisonPlugin.java#L144-L192).

Consequently, after identifying a natural progression, match the completed HP
loss against **the previous snapshot's varp**. This ordering is an inference
from the native and RuneLite next-hit semantics, not a recovered server
damage-handler trace. Boundary examples make the distinction testable:

| Previous varp | Current varp | Completed candidate hit | Following hit |
| --- | --- | --- | --- |
| `6` | `5` | `2` | `1` |
| `1` | `0` | `1` | None |
| `1_000_000` | `1_000_001` | `6` | `8` |
| `1_000_007` | `1_000_008` | `20` | `20` |

GenericClient captures HP and copied varps in its `GameTick` subscriber;
RuneLite defines that event after packet processing. This supports comparison
of consecutive frames, but does not prove every unusual server update or
reconnect preserves the assumed HP/varp alignment. See
[`GenericClientPlugin.onGameTick`](../src/main/java/com/genericclient/GenericClientPlugin.java),
[`GenericClientSnapshot.capture`](../src/main/java/com/genericclient/GenericClientSnapshot.java),
and [`GenericClientQuestSnapshot.varp`](../src/main/java/com/genericclient/GenericClientQuestSnapshot.java).

## Recommended snapshot matcher

Use two valid, consecutive logged-in frames for the same player and world.
Both HP readings and `quest.varp(102)` must be available. Reset the baseline
across missing frames, lifecycle changes, and observation gaps.

With previous severity `before`, current severity `after`, and positive
`loss = previousHp - currentHp`, the exact candidate condition is:

```text
poison = 1 <= before <= 100
         and after == before - 1
         and loss == (before + 4) / 5

venom = before >= 1_000_000
        and after == before + 1
        and loss == min(20, 6 + 2 * (before - 1_000_000))

candidate = currentTick == previousTick + 1 and (poison or venom)
```

This is a proposed attribution heuristic, not an additional native rule.
Known attackers or other direct damage evidence in either frame veto an
exemption. A match must not clear previous unexpected-damage grace, erase
threats, disable emergency healing, or bypass escape handling. If tracking an
established poison cadence, reject a transition inconsistent with that cadence;
do not derive the first due tick from a positive varp alone.

Unchanged severity, first infection, stronger reapplication, curing, conversion
between venom and poison, skipped frames, and a different HP loss remain
unexplained. The terminal `1 -> 0` candidate is particularly ambiguous because
an explicit cure can produce the same transition.

Even an exact candidate does **not** prove that no enemy dealt damage. For
example, poison `2` plus enemy damage `1` plus healing `1` gives the same net
loss as poison alone. Conversely, healing can make a real poison tick fail
the exact matcher. An empty attacker list is not proof of no incoming hit.
These are consequences of observing a net HP total instead of damage events.

If an exemption must guarantee that enemy damage is not hidden, the present
observations are insufficient. Capture local-player `HitsplatApplied` events
with type and amount, retain other damage separately, and verify their timing
against HP updates. The event also fires for hitsplats that are not rendered.
Use the revision's `HitsplatID.POISON` and `VENOM` constants rather than old
numeric literals.
[Hitsplat event contract](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/events/HitsplatApplied.java#L31-L48),
[hitsplat type constants](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/HitsplatID.java).

## Disease and remaining evidence gaps

The review's assertion that disease produces the same periodic HP-loss problem
as poison is **unverified**. RuneLite recognizes disease varp 456 and a disease
hitsplat, but its poison plugin only changes the disease heart icon; it does
not calculate disease HP damage. Native `orbs_update_health` likewise uses
disease to choose a graphic. Neither implementation proves that disease never
affects HP, or identifies which skill is drained by a particular disease hit.
Do not copy the poison damage formula or its 30-tick interval onto disease.
[Disease varp documentation](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/VarPlayer.java#L41-L44),
[native health orb](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/%5Bclientscript%2Corbs_update_health%5D.cs2#L46-L72).

Confidence is high for IDs, damage-display formulas, and the native 30-tick
countdown. Confidence is moderate for using the previous frame with a natural
severity step to attribute a completed hit; boundary-aligned runtime evidence
is still needed. Whether and under what conditions disease drains HP remains
unresolved by the inspected primary sources. Simultaneous damage/healing cannot
be resolved from these snapshots alone.

## Hitsplat events as an attribution veto

Checked against RuneLite source commit `ac79ed8` and the locally cached
**1.12.38** API and injected-client artifacts. `HitsplatApplied` provides
`getActor()` and `getHitsplat()`. `Hitsplat` is an interface exposing
`getHitsplatType()`, `getAmount()`, `getDisappearsOnGameCycle()`, `isMine()`, and
`isOthers()`. Amount means the displayed value; the interface does not promise
HP damage, identify an attacker, or expose the impact tick or display delay.
[API interface](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/Hitsplat.java),
[event interface](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/events/HitsplatApplied.java).

Filter with `event.getActor() == client.getLocalPlayer()` while the local
player is available. `isMine()` alone is not a recipient check: RuneLite uses
it for both outgoing damage on NPCs and incoming ordinary hits on the local
player. It also includes blocked hits and excludes poison and venom.
[DPS counter](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-client/src/main/java/net/runelite/client/plugins/dpscounter/DpsCounterPlugin.java#L180-L233),
[idle notifier](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-client/src/main/java/net/runelite/client/plugins/idlenotifier/IdleNotifierPlugin.java#L541-L552).

For positive displayed amounts, use an explicit classification. These are
the current IDs, not permanent numeric protocol guarantees:

| Types | Treatment |
| --- | --- |
| `POISON=65`, `VENOM=5` | Direct poison/venom evidence; keep separate from ordinary damage. |
| Every `DAMAGE_ME`, `DAMAGE_OTHER`, and `DAMAGE_MAX_ME` variant | Ordinary HP damage evidence on the filtered local actor. IDs are `16..25`, `43..47`, and `53..55`, including cyan, orange, yellow, white, and poise variants. Veto poison-only attribution. |
| `HEAL=6` | Healing; never subtract it from the ordinary-hit evidence flag. |
| `CORRUPTION=0`, `PRAYER_DRAIN=60` | Prayer effects, not amounts to subtract from HP. They may still be hostile effects. |
| `SANITY_DRAIN=71`, `SANITY_RESTORE=72` | A separate sanity resource; do not add their amounts to HP damage/healing. |
| `DISEASE=4`, `DISEASE_BLOCKED=3` | Disease-specific observations; HP consequences remain unclassified here. |
| `BLEED=67`, `BURN=74` | Additional damage effects. They do not qualify for a poison/venom exemption. |
| `CYAN_UP=11`, `CYAN_DOWN=15`, `DOOM=73`, or any other unmapped ID | Do not infer HP damage from the name, colour, or positive amount. Retain as an unclassified event and veto poison-only attribution. |

The ordinary families and numeric mapping come from
[HitsplatID](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/HitsplatID.java).
Jagex explicitly describes corruption hitsplats as Prayer drain;
native scripts maintain sanity through a separate percentage bar. The native
Hitpoints guide identifies bleed and burn damage, but its generic introductory
wording also groups corruption under damage: that wording cannot establish
that every listed hitsplat subtracts HP.
[Jagex corruption description](https://secure.runescape.com/m=news/a-kingdom-divided?oldschool=1),
[native sanity bar](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/%5Bproc%2Csanity_update_bar%5D.cs2),
[native guide script 9162](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/script9162.cs2).

`BLOCK_ME=12` and `BLOCK_OTHER=13` do not establish positive HP damage.
Zero-amount hits must not become HP losses merely because `isMine()` is true.
An anomalous positive blocked/disease-blocked amount should remain unclassified.
For unresolved adverse effects, including disease, an unclassified-event veto
is conservative; its reason must not claim proven HP damage or an enemy source.

### Dispatch order and delayed display

The current injected client's actor method `dh.dn` posts its hitsplat event
through `dh.yf` and `Callbacks.post` during hit processing, including when
no visible slot can be assigned. `Hooks.post` dispatches synchronously.
`Hooks.serverTick` marks a pending game tick; `Hooks.tick` replays deferred
events, posts `GameTick`, and only then increments RuneLite's tick count.
This supports collecting local-player events since the previous `GameTick`
and consuming that batch once at the next one. Do not mix GenericClient's
incremented snapshot tick with RuneLite's not-yet-incremented counter.
[RuneLite dispatch](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-client/src/main/java/net/runelite/client/callback/Hooks.java#L221-L246),
[synchronous subscriber invocation](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-client/src/main/java/net/runelite/client/eventbus/EventBus.java#L217-L229),
[server-tick marker](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-client/src/main/java/net/runelite/client/callback/Hooks.java#L539-L543),
[injected client 1.12.38](https://repo.runelite.net/net/runelite/injected-client/1.12.38/injected-client-1.12.38.jar).

Static inspection of that injected artifact found expiry computed as
`current client cycle + supplied delay + hitmark-definition duration`.
`dv` implements `Hitsplat` over mutable native slot fields, so copy the type,
amount, and cycle values inside the event handler. Do not retain the interface
object as an immutable receipt, infer impact time from expiry, or assume one
fixed display duration. The artifact's SHA-256 is
`7fdedf1194261cc5b99faa35e0d2b4e45b6d56665402ccbde7f3aa6207c3f947`;
the inspected methods are `dh.dn`, `dh.yf`, `dv.az`, the three `dv` API getters,
and `client.getGameCycle`.

An event reports that the client processed a hitsplat, not that its visible
animation just began. The delay does not delay the event until rendering.
The API and inspected client code do not establish an exact server HP-update
correspondence for every delayed hit, so a per-batch sum is not an exact HP
ledger. Reset batch ownership across account/session transitions and retain
the existing unexplained-HP-loss path when no classified event explains a loss.

For the guard, use positive ordinary hits as an **independent unexpected-damage
signal**, even if the next HP frame is unchanged or higher. Keep that evidence
and its existing grace independently of healing and poison matches. This
detects an observed enemy hit that healing otherwise hides; the event itself
does not distinguish enemy damage from self-inflicted or environmental damage.
Known non-HP resource events remain separate, and unfamiliar positive types
must not silently pass the poison-only check. A poison/venom event by itself
does not prove the absence of every other damage source.
