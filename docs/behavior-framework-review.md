# Behavior framework review

Historical review of the pre-cutover runtime. Its source paths and measurements describe that revision; use [Java scripting](java-scripting.md) and the [migration checklist](java-scripting-migration.md) for the current implementation.

Date: 2026-09-04

Scope: `GenericClientBehaviorController`, `GenericClientActivityContext`,
`GenericClientActionBundle`, `GenericClientCombatGuard`, the input primitives,
`GenericClientLuaHost`, `GenericClientLuaScript`, `docs/behavior-system.md`, and
the Lua catalog in `GenericClientScripts`.

Evidence: the working tree as of the 2026-09-03 20:30 build, plus the client
logs for 2026-09-02, 2026-09-03, and the morning of 2026-09-04. Line numbers
refer to the working tree at the time of writing.

## 1. Summary

The models underneath the framework are sound. The seeded profile, the
correlated quantiles, the exponential long-break budget with its quadratic
hazard, the persisted per-account state, and the receipts all do what they
claim. The spaghetti comes from two structural decisions made early and
patched since:

1. The behavior boundary lives inside the input primitives, so the humanizer
   sees clicks, not decisions.
2. One activity label carries five unrelated policies, so scripts lie about
   the activity to get the policy they want, and the combat guard overrides
   them anyway.

Action bundles, the `breaks = false` flag on 264 awaits, the guard's
suppression window, and the short-lived `combat_training` activity are all
patches on those two decisions.

The first nine sections list eleven concrete issues, seven proposals, and a
map that shows which proposal closes which issue and why. The addenda in
sections 10 to 12 add Issues 12 to 19 and P8 to P13, and section 13 records
the decisions taken on 2026-09-04 together with one order across all
thirteen proposals. No code has been changed.

## 2. How the framework works today

- Every input primitive calls `behavior.beforeAction(ctx)` before it acts and
  `behavior.afterAction(ctx)` after a click is dispatched
  (`GenericClientGameInput.performWithBehavior`, line 171).
- `beforeAction` may start a long break when the hazard says one is due.
  `afterAction` may release the cursor, then may start a micro break, then may
  start a long break.
- The activity context (`GenericClientActivityContext.Activity`) decides in one
  value whether breaks are allowed, whether and when the cursor is released,
  the mouse move duration, who owns protection prayers, and whether walk
  clicks are refreshed.
- Scripts set the activity with `gc.activity`, `gc.phase`, or a per-await
  `activity` field, and disable breaks per await with `breaks = false`.
- Action bundles wrap a few two-click actions so that only the outer boundary
  rolls; the inner primitives run with discretionary behavior off.
- The combat guard treats any hitpoints decrease as combat, suppresses
  discretionary behavior for 60 s, and ends any active break.
- Long-break pressure accrues from active time whenever the client is logged
  in and no break is active. The state persists across runs and restarts.
- Micro breaks are a Bernoulli roll per eligible boundary with the profile's
  probability. Cursor release is a separate roll per eligible boundary except
  for skilling, where it happens only inside a micro break.

## 3. Root causes

### R1. The boundary is at the wrong layer

The humanizer is asked "may I pause here?" once per menu interaction. A human
pauses between decisions, not between the two clicks of one decision. Bundles
restore the decision-level boundary for five call sites; every other
multi-click action still exposes its seams, and every multi-await sequence has
no boundary at all.

### R2. One label carries five policies

`combat` means fast mouse, guard-owned prayers, no breaks, no cursor release,
and no walk refresh, all at once. `skilling` means breaks on, release only
inside a break, natural mouse, script-owned prayers. A script that wants
"combat with breaks" has no way to say it, so it declares `skilling` and
loses the fast mouse and the prayer guard, or it declares `combat` and loses
the breaks. The guard then adds its own suppression on top, regardless of
what was declared.

Everything downstream of R1 and R2 is a patch: the bundle class, the
`breaks` flag, `forActionBundleMember`, `postLongBreakResolveRetries`, the
precedence rules in `activityContext`, the guard's suppression window, and
the `combat_training` experiment.

## 4. Issues found

### Boundary placement

#### Issue 1. Breaks land inside human-atomic actions

Where: `GenericClientGameInput.performWithBehavior` (line 171) wraps every
menu interaction with its own boundary. Prayer, equipment, inventory, spell,
autocast, dialogue, and walker inputs all go through it.

What happens: an action a human performs as one motion becomes two or more
independent boundaries, each with its own micro-break roll, cursor-release
roll, and long-break check. Bundles patch this at five call sites only.

| Action | Primitives | Boundaries today | Bundled |
| --- | --- | --- | --- |
| `spell.cast_on_item` | spell, then item | 1 | yes, `GenericClientSpellInput.java:278` |
| `combat.cast` | spell, then NPC | 1 | yes, `GenericClientSpellInput.java:226` |
| `item.use_on_object`, `item.use_on_npc`, `item.use_on_item` | item, then target | 1 | yes, `GenericClientQuestActions.java:98` to `:106` |
| `travel.home_teleport` | magic tab, then spell | 2 | no, `GenericClientSpellInput.java:62` |
| `prayer.set` | prayer tab, then prayer | 2 | no |
| `equipment.interact` | equipment tab, then item | 2 | no |
| `item.interact` | inventory tab, then item | 2 | no |
| `walk.to` | route clicks, run toggle, obstacles | one per click | run toggle and obstacles roll under the walk's travel context, `GenericClientWalker.java:556` |

Why it matters: a micro break between opening the prayer tab and clicking the
prayer, or between the magic tab and the teleport, is the same defect that
motivated bundles. The bundle is the right idea applied as an exception. It
should be the default.

#### Issue 2. Sequences that span several awaits have no unit

Where: `GenericClientLuaScript.parseWait` (line 645) reads `breaks` per
await. The catalog today:

| Count | What |
| --- | --- |
| 122 | Lua files |
| 639 | `gc.await` calls |
| 264 | `breaks = false` |
| 82 | `breaks = true` |
| 68 | `gc.activity(...)` calls |
| 24 | `gc.phase(...)` calls |
| 14 | per-await `activity = "..."` overrides |

Examples: `scripts/quest-runner/the_grand_tree/interactions.lua` disables
breaks on dialogue continues but enables them on the Talk-to that opens the
dialogue. `scripts/aio-melee.lua` disables breaks at lines 51, 56, 148, and
219 around gear and prayer setup. `scripts/aio-magic.lua` disables them on
`safety.configure`, `dialogue.continue`, and `combat.set_autocast`.

Why it matters: the script author has to remember, per await, whether the
humanizer may interrupt here. A missed flag produces a break in the middle of
a bank trip or a dialogue chain. An over-applied flag silently removes breaks
from a whole script. The flag encodes a property of the sequence, not of the
await, and the framework has no way to say "sequence".

### Policy

#### Issue 3. One activity label carries five policies

Where: `GenericClientActivityContext.Activity` decides breaks, cursor
release mode, mouse duration (180 ms for combat and hazardous travel), prayer
ownership, and walk refresh from one enum value.
`GenericClientLuaScript.activityContext` (line 932) adds precedence rules on
top: `combat.*` becomes combat unless the declared activity is skilling or
hazardous travel; walks become travel unless the declared activity is combat,
hazardous travel, banking, trading, or dialogue; and so on.

What happens: `scripts/aio-melee.lua` declares `combat` at line 174, which
suppresses breaks and cursor release for the whole training session. To get
breaks during melee training the script would have to declare `skilling`,
which keeps breaks on for attack actions but drops the fast mouse and hands
prayer ownership back to the script. The `combat_training` activity that
existed only in the 17:02 to 17:15 build on 2026-09-03 was an attempt to add a
sixth preset for exactly this case.

Why it matters: every new combination needs either a new enum value or a lie
in the script, and the precedence rules in the script layer are the same
policy encoded a third time.

#### Issue 4. The guard overrides everything and its grace never expires under damage over time

Where: `GenericClientCombatGuard` treats any hitpoints decrease as combat
(lines 117 to 119), then calls `suppressBehavior()` and `endBreakIfNeeded()`
(lines 147 to 148). The window is `COMBAT_GRACE_MILLIS = 60_000` (line 19),
refreshed on every hit, applied through
`GenericClientBehaviorController.suppressDiscretionaryBehavior` (line 481).
The guard has no poison or disease handling.

| Damage source | Interval | Grace | Result |
| --- | --- | --- | --- |
| Poison | every 18 s | 60 s, refreshed per hit | suppression never expires while poisoned |
| Disease | every 18 s | same | same |
| Melee training | every few seconds while the NPC hits | same | breaks impossible even when the script wants them |

Why it matters: a poisoned player cannot take a break until the poison wears
off or is cured, and an AFK long break that is already running is ended by
the next poison tick. The guard has no way to know that the script expects to
take damage, because "expected damage" is not something a script can declare
(see Issue 3). The 2026-09-03 log shows 21 guard suppressions; the mechanism,
not the count, is the problem.

### Long breaks

#### Issue 5. Long breaks fire at the start of nearly every run

Where: `publishActiveTick` (line 203) adds active time whenever the client is
logged in and no break is active. It does not ask whether a script owns the
input. The state is persisted, so the hazard carries across runs and client
restarts. When the operator starts a script, the first eligible boundary
discharges it: `beforeAction` (line 253) or `enterPhase` (lines 422 and 434),
where the phase bonus `maturity^2` is also at its maximum.

Every long break over two days:

| Day | Start | Mode | Requested | Trigger | After script start | Ended |
| --- | --- | --- | --- | --- | --- | --- |
| 09-02 | 12:29:06 | logout | 12.2 min | action | 3 s | manual after 135 s |
| 09-02 | 13:49:16 | afk | 9.0 min | action | 0 s | manual after 20 s |
| 09-02 | 17:18:40 | afk | 16.5 min | action | 0 s | manual after 60 s |
| 09-03 | 08:21:05 | afk | 15.5 min | action | 0 s | manual after 22 s |
| 09-03 | 11:36:56 | logout | 13.7 min | action | 76 s | manual after 65 s |
| 09-03 | 13:52:31 | afk | 30.9 min | action | 0 s | manual after 41 s |
| 09-03 | 16:15:58 | afk | 27.4 min | phase `port_sarim_jail.arrived` | 182 s | completed |
| 09-03 | 18:46:12 | afk | 9.6 min | phase `superheat.bank` | 267 s | manual after 59 s |
| 09-03 | 20:36:18 | afk | 22.7 min | phase `low_alchemy.bank` | 0 s | manual after 65 s |

Nine long breaks, all within five minutes of a script start, six of them
within three seconds. Zero long breaks were taken in the middle of a run,
which is where a human would take them.

Why it matters: the operator watches the run start, sees the character sit
down or log out, and ends the break by hand. The long-break model has never
been observed doing its job.

#### Issue 6. A manually ended long break is credited in full

Where: `finishActiveBreak` (line 643) calls
`resetLongClock(nextExponentialBudget())` and `suppressNextMicro()` (lines 679
to 680, and again on the fallback path at 703 to 704) for every long break,
whether it ran 27 minutes or 20 seconds.

Why it matters: eight of the nine long breaks above ran for between 20 s and
135 s against requests of 9 to 31 minutes, and each one reset the clock as if
it had completed. The break the operator interrupted is never retried at a
better time. The next one appears a full budget later, and the mechanism in
Issue 5 makes it land at the next script start again.

#### Issue 7. The pre-action long-break check always wins

Where: `beforeAction` checks `longDue(0.0)` before every primitive (line
253). `afterActionBreak` also checks it (line 318, reason
`action_complete_due`), but that path can only fire if the hazard crosses the
budget during the few hundred milliseconds of the primitive itself. The logs
contain no `action_complete_due` break on any day.

What happens: when `beforeAction` starts a long break, the primitive has
already resolved its target and often moved the mouse. After the break the
target may be gone, the camera moved, or the client relogged.
`postLongBreakResolveRetries` exists for that reason, and the 12:29 break on
2026-09-02 ended with `WALK_CLICK_FAILED reason=mouse_missed_target` on the
first click after relogin.

Why it matters: the framework has two long-break placements, the wrong one is
the only one that fires, and the retries plumbing exists to clean up after it.

### Density

#### Issue 8. Micro-break and cursor-release density follow click density, not time

Where: `afterActionBreak` rolls `profile.getMicroBreakProbability()` on every
eligible boundary. `cursorRelease` (line 328) rolls
`profile.getCursorReleaseProbability()` the same way.
`GenericClientBehaviorProfile.REFERENCE_ELIGIBLE_INTERACTIONS_PER_HOUR = 36.0`
(line 30) is what the profile's downtime estimate assumes.

| Day | Activity | Eligible boundaries | Micro breaks | Median | Total downtime |
| --- | --- | --- | --- | --- | --- |
| 09-02 | questing and travel | 2214 route clicks plus actions | 200 | 5.1 s | 21 min |
| 09-03 | alchemy, superheat, questing | 1340 casts plus 1914 route clicks | 795 | 5.2 s | 95 min |
| 09-04 morning | alchemy | 189 casts | 96 | | 10 min |

On 2026-09-03 about 520 of the 1340 casts were followed by a micro break. An
alchemy loop produces 36 eligible boundaries in about two minutes, so the
profile's per-hour estimate is off by more than an order of magnitude for that
activity. Cursor releases follow the same pattern:

| Day | Releases during travel | Releases elsewhere |
| --- | --- | --- |
| 09-02 | 412 | 3 |
| 09-03 | 191 | 1057 (skilling, see Issue 9) |
| 09-04 morning | 43 | 60 |

Why it matters: the amount of downtime a run gets is decided by how many
clicks the script happens to make. A cast loop takes several times the breaks
of a quest, and no profile setting can make both right at once.

#### Issue 9. Cursor release has two semantics and is rolled before the break decision

Where: the non-skilling path of `afterAction` (line 278) rolls the cursor
release first and only then decides on a micro break, so a release can be
followed by a break in the same boundary and the two receipts are independent.
The skilling path (`releasesCursorOnlyDuringMicroBreak`) does the opposite and
releases only inside a micro break. Micro breaks start from three sites:
`afterActionBreak`, `enterPhase`, and `startMicroBreak` with its own release
combination for skilling (line 561).

Evidence: of the 1057 skilling releases on 2026-09-03, 744 were paired with a
micro break and 313 were not. All 313 came from builds before the 20:32
install, which confirms that the current build ties them for skilling only.

Why it matters: one concept with two behaviors, selected by the activity
label, plus an ordering the reader has to know to interpret the receipts.

### Operator

#### Issue 10. Operator actions are humanized like scripts

Where: the REPL, the MCP `script_action` path, and console awaits go through
the same host submit methods and the same boundaries as a script.

Evidence: this morning's console walk to the Al Kharid bank, with no script
running, produced 6 micro breaks and 10 cursor releases in about 90 s
(08:51:28 to 08:52:56).

Why it matters: when the operator is driving, the humanizer is noise. It slows
debugging and adds behavior events that belong to no run.

### Structure

#### Issue 11. Policy is restated in many places and the doc has drifted

| Concern | Places it is decided today |
| --- | --- |
| Whether breaks are allowed | activity `allowsBreaks()`, per-await `breaks`, bundle membership, guard suppression window, random-event and takeover interrupts |
| When a long break may start | `beforeAction` (253), `afterActionBreak` (318), `enterPhase` due (422), `enterPhase` bonus (434) |
| Where a micro or release roll happens | `afterActionBreak`, `enterPhase`, `startMicroBreak` skilling path, `cursorRelease` |
| Which activity applies | script declaration, per-await override, `activityContext` precedence, guard `effectiveActivity`, bundle member context |

`docs/behavior-system.md` (lines 174 to 175) states that a walk with eight
route clicks performs eight micro rolls and eight release rolls. The run
toggle and obstacle interactions add more under the same travel context.

Why it matters: a change to one rule has to be made in four places, and the
tests cover each place rather than the rule.

## 5. Proposals

### P1. One boundary per await, opened by the host

Change:

- `GenericClientLuaHost.submitQuestAction` (line 978), `submitNpcInteract`
  (955), `submitWalkTo` (928), and the other submit methods call
  `beforeAction` before the action resolves anything and `afterAction` after
  the action's click has been dispatched.
- The input primitives stop calling the controller.
  `GenericClientGameInput.performWithBehavior` becomes a plain `perform`.
  Prayer, equipment, inventory, spell, autocast, and dialogue inputs lose
  their behavior parameters.
- The walker is the one deliberate exception. The host hands it a route-click
  hook, and each route click calls the hook for its own roll. The run toggle
  and obstacle interactions do not roll.
- `GenericClientActionBundle`, `forActionBundleMember`, the `ACTION_BUNDLE_*`
  log lines, and `postLongBreakResolveRetries` are deleted.
- Receipts keep `behavior_before` and `behavior_after` at the await level.
  Walk receipts add a per-click list.

Why it addresses the issues:

- Issue 1: every action is atomic by construction, including home teleport,
  `prayer.set`, `equipment.interact`, `item.interact`, and the walk's run
  toggle and obstacles. The bundle no longer needs to exist because the
  boundary was moved rather than wrapped.
- Issue 7: a long break that starts in `beforeAction` now starts before
  target resolution, so nothing is stale after it and the retries plumbing
  goes away.
- Issue 11: one class opens the boundary, and the eight-rolls statement in
  the doc becomes exact.

### P2. An intent scope in Lua

Change:

- `gc.intent(name, fn)` runs `fn` inside a scope. The host opens one boundary
  at entry and closes it at exit, and every await inside runs with breaks
  off. Nested intents flatten into the outermost one. Errors propagate and
  still close the scope.
- Receipts inside a scope carry `intent = name`, `gc.read("behavior")`
  reports the active intent, and the log gains `INTENT_STARTED` and
  `INTENT_ENDED`.
- Intents are for human-atomic sequences of a few seconds to about half a
  minute: open a dialogue and click through it, use an item then continue,
  steal then eat, open the bank, withdraw, close. A scope that exceeds a
  threshold logs a warning so long scopes stay visible.

Why it addresses the issues:

- Issue 2: the unit of suppression becomes the sequence, declared once where
  it is written, instead of a flag on each await. Most of the 264
  `breaks = false` occurrences are contiguous clusters that map directly onto
  one scope.
- Issue 1: covers the cases a single-await boundary cannot, where the atomic
  action spans several awaits.
- Issue 11: removes one of the places policy is decided.

### P3. A policy record with presets and a single resolver

Change:

- `BehaviorPolicy` with independent fields: `breaks`, `cursorRelease` (none,
  with break, independent), `mouse` (natural, fast), `damageExpected`,
  `prayerOwner` (script, guard), `walkRefresh`.
- The activities become a preset table. `combat` is
  `{breaks=false, cursorRelease=none, mouse=fast, damageExpected=true, prayerOwner=guard, walkRefresh=false}`
  and so on. Scripts override fields:
  `gc.activity("combat", { breaks = true })` for melee training, and
  `gc.await { ..., policy = { breaks = false } }` where a one-off is still
  needed.
- One resolver takes the declared preset, the override, the guard state, the
  takeover state, and the random-event state, and returns the effective
  policy plus a reason list. It logs one `BEHAVIOR_POLICY` line whenever the
  effective policy changes. The controller reads only the resolved policy.
- The guard stops calling `suppressDiscretionaryBehavior`. It publishes
  `damageGraceUntil` and `threatsPresent`, and the resolver applies them only
  when `damageExpected` is false. Damage that matches the poison or disease
  state (varp active and loss equal to the current poison damage) is treated
  as expected and does not end an AFK break.
- The precedence rules in `GenericClientLuaScript.activityContext` are
  removed. The action type chooses a preset only when the script declared
  nothing.

Why it addresses the issues:

- Issue 3: the five policies become five fields, so melee training is
  `combat` with breaks on and no lie about the activity. Presets keep the
  short form for the common cases.
- Issue 4: the guard informs instead of overriding, `damageExpected` tells it
  what the script already knows, and damage over time no longer refreshes the
  grace or ends a break.
- Issue 11: one resolver replaces four suppression paths, and the reason list
  makes every decision explainable from the log.

### P4. Time-based micro pressure, with cursor release tied to breaks

Change:

- Micro pressure accrues with active time at a rate derived from the profile:
  `microBreakProbability * 36 / 60` per active minute, times an activity
  multiplier (skilling 1.0, questing 1.0, general 0.8, travel 0.6), all
  correlated per account through the existing copula. With that rate the
  downtime estimate the profile prints becomes true by construction.
- At each boundary the break fires with probability `1 - exp(-pressure)`, or
  when the pressure crosses a jittered threshold, and consumes the pressure.
  A small constant fidget probability of about 2 percent keeps long loops
  from being perfectly regular. Phase entries keep their bonus mechanism,
  expressed as added pressure.
- Cursor release becomes part of a micro break everywhere. The skilling rule
  becomes the rule. An independent glance-away release survives only for
  travel, capped per active minute.
- The state file gains the pressure value and bumps its version. An older
  state file loads with zero pressure.

Why it addresses the issues:

- Issue 8: downtime per active hour no longer depends on how many clicks the
  script makes, so a cast loop and a quest get the same profile-driven share.
- Issue 9: one release semantics, decided after the break decision because it
  is the break.
- Issue 10 in part: a 90 s console walk cannot accumulate six breaks' worth
  of pressure.

### P5. Long-break lifecycle rules

Change:

- Active time accrues only while the host owns input: a script session or the
  scheduler is running and the effective policy is not manual.
  `publishActiveTick` receives an owned flag from the host.
- The `beforeAction` long path (line 253) is removed. Long breaks start only
  from `afterAction` and `enterPhase`, using lookahead:
  `longDue(bonus + hazardGrowthOver(expectedGap))`, where the gap is the
  observed median time between boundaries in this run. The break then lands
  at the last boundary before the hazard would cross the budget.
- Start-of-run grace: no long break during the first ten active minutes of a
  session except at a phase, with the ten minutes drawn from the profile.
- Manual end defers instead of spending. The clock is not reset, the usual
  refractory of `clamp(0.3 M, 10, 60)` minutes applies, and the next eligible
  phase boundary after the refractory takes the break. The dashboard shows
  "long break deferred". `suppressNextMicro` applies only if the break ran
  for at least the micro-break scale.
- `gc.read("behavior")` and the status map expose
  `long_break_due_in_active_minutes`, so a script can route to a `.bank`
  phase when a break is near.

Why it addresses the issues:

- Issue 5: nothing accrues while the operator is idle in the client, so a
  run no longer starts with a full hazard, and the start-of-run grace catches
  the remainder.
- Issue 6: an interrupted break is retried at a better boundary rather than
  credited in full.
- Issue 7: the only placements left are after a completed action and at a
  phase, and the lookahead gives the phase path a real chance to win.

### P6. Operator actions default to plain execution

Change:

- REPL, MCP `script_action`, and dashboard-issued awaits run with
  `breaks=false`, `cursorRelease=none`, and a natural mouse.
  `gc.await { ..., humanize = true }` opts in. Scheduled and standalone
  scripts are unchanged.

Why it addresses the issues:

- Issue 10: the humanizer applies to runs, not to the operator's hands.

### P7. Retire the per-await `breaks` flag with a lint and a codemod

Change:

- After P2 and P3 land, a codemod converts contiguous `breaks = false`
  clusters into `gc.intent` scopes and single occurrences into
  `policy = { breaks = false }`.
- `parseWait` stops accepting `breaks`. A lint in `tests/` fails on
  `breaks =` and on a `skilling` declaration in a file whose awaits are
  `combat.*`.

Why it addresses the issues:

- Issue 2: the flag cannot come back.
- Issue 11: one fewer place where policy is decided, and the catalog stops
  encoding it.

## 6. Issue to proposal map

| Issue | Addressed by | Why it is resolved |
| --- | --- | --- |
| 1. Breaks inside human-atomic actions | P1, P2 | The boundary moves to the await, so each action is atomic without a bundle. The scope covers actions that span several awaits. |
| 2. Per-await `breaks` flags | P2, P7 | The scope replaces the flag for sequences, and the lint stops the flag from returning. |
| 3. One label carries five policies | P3 | Independent fields with presets. Melee training declares `combat` with breaks on instead of lying. |
| 4. Guard overrides; grace never expires under damage over time | P3 | The resolver applies guard state only when damage is unexpected, and poison or disease damage is exempt. |
| 5. Long breaks at run start | P5 | Accrual requires script ownership, and the start-of-run grace catches leftover hazard. |
| 6. Manual end credited in full | P5 | A manual end defers the break instead of resetting the clock. |
| 7. Pre-action long break between resolve and click | P1, P5 | The host boundary precedes resolution, and only post-action and phase placements remain. |
| 8. Density follows clicks | P4 | Pressure accrues with active time, not with boundaries. |
| 9. Two release semantics, wrong order | P4 | Release is part of the break everywhere, decided after the break. |
| 10. Operator actions humanized | P6, P4 | Plain by default, and the pressure model cannot burst in a short console walk. |
| 11. Policy restated; doc drift | P1, P3, P7 | One boundary, one resolver, no per-await flag. The doc is rewritten around the resolver. |

## 7. Suggested order of work

| Step | Proposal | Size | Effect | Tests to touch |
| --- | --- | --- | --- | --- |
| 1 | P5 | small | long breaks stop firing at run start | `GenericClientBehaviorControllerTest` |
| 2 | P6 | tiny | console runs stop generating behavior events | host tests |
| 3 | P1 | mechanical, deletes code | bundles gone, every action atomic | delete `GenericClientActionBundleTest`, receipt assertions in `mcp` |
| 4 | P2 | small | intent scope available | new Lua tests in `tests/` |
| 5 | P3 | medium | policy record and resolver, guard refactor | controller, guard, and script tests, doc update |
| 6 | P4 | medium, model change | time-based density | controller tests, state versioning, calibration against the 09-03 log |
| 7 | P7 | small | catalog migrated and locked | Lua lint |

P5 and P6 go first because they are small and remove the two behaviors the
operator hits most. P1 comes before P2 and P3 because the boundary has to be
in the host before scopes and policies can attach to it. P4 is last among the
model changes because it changes persisted state and needs calibration.

Risks:

- The walker's per-click roll must stay. Route clicks are the one primitive
  where a human decides per click.
- Receipt shapes change for primitives, and the MCP tests assert on them.
- An intent that wraps a long sequence suppresses breaks for its whole
  duration. The warning threshold in P2 exists for that reason.
- P4 needs the state file versioned so older state loads cleanly.
- The HANDOFF constraints still apply. The dirty tree stays as it is, and
  none of this touches the bond offer, the coin reserve, or the level caps.

## 8. What to keep

- The profile derivation: SHA-256 labels, Gaussian copula, correlated
  quantiles, and the envelope midpoints.
- The exponential budget and quadratic hazard for long breaks, and the
  refractory clamp.
- Persisted per-account state.
- Receipts that carry `behavior_before` and `behavior_after`.
- ACTION timeouts pausing while a break is active, while tick waits keep
  counting.
- The takeover rules: physical mouse wins, the idle window, physical Escape
  as manual stop.
- The Lua test harness and the shape of the controller test suite.

## 9. Evidence notes and caveats

- Logs: `client_2026-09-02.0.log`, `client_2026-09-03.0.log`, and
  `client.log` for the morning of 2026-09-04 under
  `/mnt/c/Users/User/.runelite/logs/`. They are CRLF with some non-UTF-8
  bytes, so counts used `grep -a` and awk.
- The 2026-09-03 log spans several JAR builds. The 20:30 build installed at
  20:32 is the first one that matches the working tree. Counts before that
  time reflect older behavior; the 313 unpaired skilling releases are the
  clearest example.
- Guard suppressions were counted but not attributed to poison. The
  damage-over-time point is derived from the code, not from an observed
  incident.
- Per-hour figures are approximate. The alchemy cast cadence of about 3 s is
  the basis for the statement that a cast loop produces 36 boundaries in
  about two minutes.

## 10. Addendum, 2026-09-04: walker click model and objective-free cursor motion

Two follow-up questions from the operator: why hazardous travel frequently
drops into "click once and wait" while it is still following a path, and
whether objective-free mouse motion between actions makes sense.

### Issue 12. Every click target is treated as an endpoint

Where: after a click the walker keeps `clickTarget` and requests the next
click only when the player is within `WAYPOINT_ADVANCE_RADIUS = 2` tiles of
it or has passed its route index (`GenericClientWalker.java` lines 380 to
460), with `CLICK_COOLDOWN_TICKS = 2` between clicks. The next click then
needs a plan check, a synthetic mouse move, and a hover settle before it
lands.

Evidence from 2026-09-03, normal-mode walks only:

| Consecutive click pairs | Player within 1 tile of the previous target | Within 2 tiles | Farther | Mean gap |
| --- | --- | --- | --- | --- |
| 461 | 143 | 136 | 182 | 5.3 s |

Why it matters: a person clicking the minimap while travelling clicks again
whenever it feels right, usually before reaching the previous click, and the
target scatters along the direction of travel. The walker instead runs to a
tile, pauses while the mouse moves, and clicks the next tile. The click count
is decided by route geometry and minimap reach, which is also what feeds the
travel-heavy micro-break density in Issue 8.

### Issue 13. Hazardous travel drops into "click once" at every route leg

Where: the hazardous refresh (`refreshesWalkClicks()`) re-clicks every tick
except when the click target is the last tile of the current plan
(`finalWaypoint`, lines 399 to 407). That exception is uncommitted and was
introduced on 2026-09-03 between 09:16 and 09:41 (walks before it re-clicked
the final tile 6 to 12 times; walks after it click it once).

Three things combine to make "final waypoint" common:

- The Monkey Madness scripts issue one `walk.to` per route point
  (`scripts/quest-runner/monkey_madness_i/amulet_crafting.lua` `walk_route`,
  `garkor.lua` lines 201 and 772), so every route point is a walker
  destination and every leg has a final waypoint. The walker cannot tell a leg
  endpoint from the real destination.
- Route targets are chosen farthest-first with the minimap preferred
  (`GenericClientGameInput.targetForWorldPoint`), and the minimap projection
  reaches about twenty tiles. The final tile therefore becomes the click
  target as soon as the remaining leg fits in minimap reach, which for a
  short leg is the first click.
- Once in that mode the advance radius is 0, so the walker waits for exact
  arrival and the only recovery is the 8-tick stall timer followed by a leg
  backoff and a path retry.

Evidence, the 09:50:24 walk on 2026-09-03 in the Ape Atoll tunnels: plan 1
refreshed every tick for 29 tiles, a Monkey Zombie engaged the guard at
09:50:30, and the two-tile plan 2 to the final tile was clicked once and then
waited through five stalls of 4 to 5 s each, about 23 s in an aggressive
area with one click per stall.

"Visible" in the current code means "projects inside the minimap click
area", not "on screen". If the intent was to click the destination once when
it is on screen, the code checks a different condition.

Note that `scripts/shared/movement.lua` has its own `click_once` helper
(`walk.click` plus a tick loop), so there are two single-click mechanisms,
one in Lua and one inside the walker.

### Issue 14. The cursor is frozen between actions

Where: between actions the synthetic cursor stays exactly where the last
click landed until the next action or a cursor release. Objective-free motion
exists only as the idle park in `GenericClientPlugin.publishIdleCursor`,
which runs only when no script is running, the REPL is idle, the global
activity is idle, and no client input is active, after `IDLE_CURSOR_TICKS = 2`.

The pieces needed for something better already exist:

- `GenericClientSyntheticMouse.move(destination, preemptible, ctx)` supports
  preemptible moves. A real move cancels a preemptible one
  (`idle_cursor_preempted`) and starts from the current position, so the
  cancelled motion flows into the real one without a jump.
- The mouse profile holds 6,069 recorded templates with an `approach` flag,
  so non-approach templates are human free movements the matcher can play
  between any two points.
- The walker already treats a cursor within 12 px of its target as retained
  (`WALK_CURSOR_RETAINED`), so small drift near the minimap costs nothing.

### P8. A click cadence model for the walker, and route awareness

Change:

- Hazardous travel: remove the `finalWaypoint` exception. Re-click every one
  to two ticks until the arrival check, which runs first in the tick loop,
  ends the walk. On the final leg alternate between the destination tile and
  tiles inside the arrival radius so consecutive clicks are not identical.
  The stall timer stays as secondary recovery only.
- Normal travel: replace "click, wait until within two tiles, click again"
  with a cadence. The next click time is drawn from the profile, for example
  2 to 6 s with an occasional longer gap, independent of arrival at the
  previous target. Each click targets the farthest minimap-projectable route
  tile most of the time and a nearer tile at 60 to 90 percent of the reach
  some of the time. Waypoint arrival feeds stall detection only.
- Route awareness: the walker must know the real destination and treat
  intermediate points as pass-through with a handoff radius. This was first
  written as `walk.route` or a `leg = true` flag; P13 in section 12 replaces
  that with a `via` list on `walk.to`, and the `walk_route` helpers in the
  quest scripts are deleted rather than moved onto it.

Why it addresses the issues:

- Issue 13: no leg endpoint ever switches the walker into click-once mode,
  interruptions are cleared within a tick, and the 23 s stall in the Ape
  Atoll example becomes at most one tick of delay per interruption.
- Issue 12: clicks stop being endpoints, the run no longer pauses at each
  waypoint, and the click pattern matches the sporadic minimap clicking
  described by the operator.
- Issue 8: route clicks become roughly proportional to travel time rather
  than to geometry, which is the same shape as the time-based pressure in P4.

### P9. A cursor rest and fidget model

Change:

- The cursor always has a rest anchor and a rest style. Anchors come from the
  activity preset in P3: the minimap during travel, the inventory slot or
  spell during skilling, the prayer orb or the target during combat, the last
  click otherwise, and none during banking, trading, or dialogue.
- Fidget kinds: drift of a few pixels around the anchor over 100 to 400 ms,
  relocation to another anchor using a non-approach template, an offscreen
  glance (the existing cursor release), and anticipation, which drifts toward
  the next expected target when the script has already declared it.
- Gating: a fidget is scheduled only when no synthetic action is in flight,
  the host reports no imminent action (the script is blocked in a wait whose
  remaining time exceeds the fidget duration plus a margin, or the walker's
  next click is at least a few ticks away), no menu is open, and no takeover,
  random event, or emergency recovery is active. Every fidget is preemptible,
  so an unexpected action cancels it and starts from wherever the cursor is.
- Rates come from the profile and are correlated per account: fidgets per
  minute, drift amplitude, relocation share, anticipation probability. One
  `CURSOR_FIDGET` log line per move with kind and anchor. The policy record
  from P3 gains a `fidget` field (none, drift, full).
- The idle park in `publishIdleCursor` becomes one rest style instead of a
  separate mechanism.

Why it addresses the issues:

- Issue 14: the cursor is no longer frozen between actions, and because every
  fidget starts from the current position, ends near an anchor, and yields to
  the next real move, the motion flows instead of standing out.
- Issue 9 in part: cursor release becomes one of several rest styles rather
  than an independent roll per boundary.

| Issue | Addressed by | Why it is resolved |
| --- | --- | --- |
| 12. Click targets are endpoints | P8 | Cadence replaces arrival gating, and route awareness removes leg handoffs. |
| 13. Hazardous travel clicks once at every leg | P8 | The final-waypoint exception goes, and the walker knows the real destination. |
| 14. Cursor frozen between actions | P9 | Preemptible fidgets around activity anchors, gated on no imminent action. |

Order: P8's hazardous change is a one-line revert plus the alternating final
tile and can go with step 1. The cadence and the route awareness, now P13's
`via`, fit after P1, since
the per-click boundary hook lands in the same place. P9 belongs after P3
because anchors and the `fidget` field come from the policy record.

## 11. Addendum, 2026-09-04: how navigation works, and where it falls short of "one walk.to"

The operator's expectation: long travel is one `walk.to` to a far tile, the
client resolves the route over the whole map, clicks the farthest reachable
tile within reach, repeats, and handles doors, gates, and other obstacles on
the way. This section records what the code does today, with evidence, and
where it differs.

### 11.1 The map is a whole-world tile grid, not a mesh or a node graph

- `GenericClientCollisionMap` loads two archives at startup. `collision-map.zip`
  holds 2,726 regions of 64 by 64 tiles by 4 planes with two bits per tile
  (walk north, walk east; south and west come from the neighbour). It is the
  Skretzo shortest-path dump, pinned by SHA-256, from a cache selected on
  2026-08-19. `door-map.zip` holds 773 regions of door bits marking edges the
  dumper classified as a passable wall or door before flattening them into the
  ordinary map.
- The static map contains no plane transitions, stairs, ladders, ships,
  teleports, agility shortcuts, fairy rings, spirit trees, or dynamic objects.
  A request whose destination is on another plane fails at once with
  `unsupported_transition`.
- Inside the loaded 104 by 104 scene, `GenericClientSceneCollision` copies
  RuneLite's live collision flags and they override the static bits
  (`GenericClientSnapshot.canPlanMove`). A live-closed edge stays routeable
  only when an object spanning it exposes an approved traversal action: Open,
  Pass, Climb-over, Climb-through, Squeeze-through, Jump-over, Cross, or
  Go-through. Wall objects with those actions qualify; game objects qualify
  only for Open with "door" or "gate" in the name; a paired gate one tile off
  the edge also qualifies. Scene-edge and uninitialised sentinels fall back to
  the static map.

### 11.2 The search plans the whole route every time

- `GenericClientPathfinder.find` is A* over tiles: eight directions, cost 10
  orthogonal and 14 diagonal, an admissible octile heuristic, corner-cutting
  rules from the archive, an 80-unit surcharge on door edges, and a cap of
  250,000 expanded nodes. It accepts the first cheapest tile inside the
  arrival radius. It runs on one daemon planner thread.
- Every plan and replan searches from the player's current tile to the real
  destination. `findSegment` exists in the pathfinder but has no caller, so
  `docs/walker-design.md` is wrong where it says long routes are broken into
  global-guided local segments.
- The edge policy per plan rejects the walk's `avoid_tiles`, NPC-occupied
  tiles for a body-block replan, and the walk's own set of edges found solid
  earlier in the same walk.

Timing from the logs (planning line to planned line, second resolution):

| Date and time | Path tiles | Expanded nodes | Planning time |
| --- | --- | --- | --- |
| 2026-09-02 17:21:02 | 1,095 | 118,737 | about 1 s |
| 2026-09-02 17:23:27 | 839 | 82,307 | 3 s |
| 2026-09-04 08:45:30 | 590 | 69,307 | 3 s |
| 2026-09-03 18:37:48 | 338 | 18,188 | 2 s |

### 11.3 Execution picks the farthest projectable route tile

Per game tick `GenericClientWalker` runs, in order: arrival check; run toggle;
match the player to the route (within 3 tiles of a tile between 3 behind and
12 ahead of the last index, otherwise an `off_route` replan); scan the next 12
route edges against live collision. A blocked edge with a traversal object is
approached to within 3 tiles and interacted with, up to 3 attempts, verified by
the edge opening or the object changing; a "locked" game message, or a blocked
edge with no traversal object, marks that edge solid for the walk and replans.
Otherwise the click candidates are the remaining route from the far end back
to the next tile, and `GenericClientGameInput` turns the camera toward the
farthest one and takes the first candidate that projects: `LocalPoint.fromWorld`
must succeed (the tile must be inside the loaded scene), then the minimap
projection is preferred in route mode (about twenty tiles of reach), then a
visible canvas polygon. Recovery replans are budgeted at 6 plus one per 16
tiles of initial distance, capped at 32.

So for same-plane ground travel the operator's expectation is already the
implementation. Evidence over the three log days:

| Measure | 2026-09-02 | 2026-09-03 | 2026-09-04 |
| --- | --- | --- | --- |
| Walk requests | 189 | 181 | 2 |
| Median request distance, tiles | 18 | 19 | 51 |
| Requests over 100 tiles | 10 | 6 | 1 |
| Longest single plan, tiles | 1,095 | 338 | 745 |
| Arrived | 168 | 147 | 0 |
| Unreachable, all reasons | 12 | 6 | 0 |
| Obstacle interactions / cleared | 24 / 14 | 29 / 6 | 6 / 0 |

The 1,095-tile plan was one `walk.to` from Ardougne at (2442,3088) to the
Grand Exchange at (3163,3483) by the aio-thieving travel helper. On 2026-09-03
the click surface was the minimap 947 times and the canvas 13 times, and the
median click landed 8 tiles ahead of the player (90th percentile 14).

The Monkey Madness and Tree Gnome scripts chop routes into short legs by
choice, not because the walker needs it: the legs pin a safe corridor,
interleave stamina and recapture checks between legs, and pin exact maze
tiles with an arrival radius of 0. That choice is what produces the leg
endpoints in Issue 13. Most of the Sep 2 `unreachable` results were correct:
the account had been thrown into the Ape Atoll jail mid-walk (for example
09:19:43, player at (2775,2794) with the destination at (2721,2763)), the
`off_route` replan found no route out of a cell, and the Lua recapture
handling took over.

### Issue 15. No transitions in the graph

Where: `GenericClientPathfinder.find` returns `UNSUPPORTED_PLANE` for a
cross-plane request, and nothing in the map or the walker models stairs,
ladders, ships, shortcuts, or teleports. The scripts carry that logic by hand:
219 climb, ladder, or stairs references across the Lua tree, plus the
teleport helpers in `scripts/shared/travel.lua`.

Why it matters: a "single far walk.to" is only possible today when the whole
route is ground on one plane. Every route with a ladder, a boat, or a teleport
is a script-authored sequence of walk, interact, walk. That is the stage the
walker design chose deliberately, but it is the main gap against the
expectation.

### Issue 16. Replans re-search the whole remaining route

Where: `requestPlan` always calls `find(start, destination)`. An `off_route`
replan on a long route therefore costs as much as the initial plan.

Evidence, 2026-09-02 on the Ardougne to Grand Exchange walk: replans at
17:23:27, 17:23:30, and 17:23:32, each of 830 to 839 tiles and about 82,000
nodes, each taking 2 to 3 s, each started from a tile the player had already
left because the player kept moving while the previous plan ran. Three
replans in eight seconds produced no click. The 250,000-node cap is also
within reach: a 722-tile crow-flight route already expanded 118,737 nodes,
so a route with a large water or mountain detour can end with
`search_limit`, which the receipt reports as `unreachable`.

### Issue 17. Door knowledge lives only inside one walk

Where: the static map routes through every door edge at a surcharge, whatever
the account can actually open. The walker discovers a locked, keyed, or
quest-gated door only on arrival, adds the edge to `ActiveWalk.blockedEdges`,
and replans. That set dies with the walk (`GenericClientWalker.java` line
1286), so the next `walk.to` from the same script plans through the same door.

Evidence: 2026-09-02 12:33:53 and 12:49:38, two walks to (3269,3167) ending
in `obstacle_interaction_limit`, the second starting with zero blocked edges
sixteen minutes after the first learned the door. 2026-09-03 11:24:54 to
(2461,3382), same reason.

### P10. Plan once, rejoin locally, and make the search cheaper

Change:

- Keep the initial whole-route plan. On `off_route` and `stalled`, run a
  bounded local search whose goal is any tile of the existing path ahead of
  the last index (rejoin), with a node budget in the low thousands. Fall back
  to a whole-route plan only when the rejoin fails or the blocked-edge set
  changed.
- Replace the boxed `HashMap` open and closed sets with flat arrays over a
  bounding box around start and destination, which is where most of the 2 to
  3 s goes at 80,000 nodes. Raise the node cap only after that.
- Report `search_limit` as its own receipt status so scripts can react
  differently from a real dead end.
- The route awareness from P8, now the `via` list in P13, belongs here as
  well, so the walker plans to the true destination and treats via points as
  pass-through.

Why it addresses Issue 16: rejoins cost milliseconds instead of seconds and
start from the player's current tile, so a moving player never waits on a
stale plan, and long routes stop being the walks most likely to stall.

### P11. A transition catalog and executor

Change:

- Add a transport edge type to the pathfinder: origin tile, destination tile
  (any plane), object or widget action, cost, and requirements (quest, skill,
  item, coins, varbit). The packed node already carries the plane, so
  cross-plane edges need no change to the node encoding.
- Add a per-type executor in the walker alongside the existing door handler:
  ladders and stairs first, then gates with tolls, then ships and network
  widgets. Item and spell teleports stay script-declared options because they
  consume resources.
- Seed the catalog from the transitions the scripts already perform by hand,
  one real route at a time, as `docs/walker-design.md` already recommends.

Why it addresses Issue 15: a single `walk.to` can cross planes and use the
same handlers everywhere, and the 219 hand-written climb sequences become
catalog rows instead of script code.

### P12. Per-account edge memory

Change:

- Persist learned solid edges and door outcomes per account with a reason and
  an expiry (locked doors expire in hours, quest-gated doors clear when the
  quest state changes, dynamic blocks expire in minutes).
- Feed that memory into the edge policy of every plan, not only the current
  walk, and record it in the receipt so scripts can see why a route bent.
- Check the door map and collision map revisions against the live game
  revision at startup and log a warning on drift.

Why it addresses Issue 17: the second walk to the same destination plans
around the door it learned sixteen minutes earlier, and a stale map is
noticed before it costs a walk.

| Issue | Addressed by | Why it is resolved |
| --- | --- | --- |
| 15. No transitions in the graph | P11 | Transport edges and per-type executors let one walk cross planes and use shortcuts. |
| 16. Replans re-search everything | P10 | Local rejoin plus flat-array search keeps replans under a tick. |
| 17. Door knowledge dies with the walk | P12 | Persistent per-account edge memory feeds every plan. |

Order: P10's rejoin and array search are self-contained and low risk, and
they make P8 cheaper, so they can go right after P8. P12 is small and can go
any time. P11 is the largest piece of walker work and should wait until the
behavior items P1 to P5 have settled, because its executors sit on the same
boundary.

## 12. Addendum, 2026-09-04: removing the scripted route legs

Request: take the custom "short legs" out of the quest scripts, keep the Ape
Atoll prison handling that already works, and end up with less tangle, not
more. This section is a plan only. No code has changed.

A "leg" here means a scripted loop that issues one `walk.to` per point of a
hand-written route and runs side logic between the points. The prison capture
and recapture handling is not part of the problem. It is mentioned below only
where a leg loop currently hosts it, and the plan leaves it where it is.

### 12.1 What the legs carry today

| Where | Route | Side logic that runs between legs |
| --- | --- | --- |
| `monkey_madness_i/garkor.lua` valley loop, lines 178 to 215 | `ape_atoll_valley` | stamina check every leg, within 8 then 2, prison check that calls `complete_capture`, poison cure at each checkpoint |
| `garkor.lua` `route_to_garkor`, lines 765 to 790 | `prison_to_garkor` | stamina every leg, within 2, recapture check that reports `monkey_madness_recaptured_on_garkor_route` |
| `amulet.lua` `walk_route`, lines 95 to 160 | `zooknock_dungeon`, 31 points | checkpoint cursor `route_checkpoint`, nearest-point start, supply check with `retreat_for_supplies` every leg, 4 retries on `click_failed`, "precise corner" points 14 to 16 at within 1, final within 3 |
| `amulet_crafting.lua` `walk_route` lines 39 to 60, trapdoor loop lines 112 to 130, `reach_north_side` lines 405 to 420 | temple routes | stamina every leg, within 6 then 3, temple guard avoid tiles at within 0 on a 14 tick budget, `route_start_index` |
| `favor.lua` `walk_route`, lines 72 to 100 | favor routes | checkpoint per leg, within 6 then 2, 300 ticks per leg |
| `disguise.lua` `walk_route` lines 35 to 55, `click_once` at lines 117 and 367 | disguise routes | stamina every leg, within 2 then 0, 180 ticks per leg, single clicks at the staging tile |
| `infiltration.lua` `walk_route`, lines 25 to 55 | infiltration routes | within 6 then 1, prison zone check that reports `monkey_madness_infiltration_recaptured`, avoid tiles, `breaks` flag |
| `tree_gnome_village/navigation.lua` `walk_route`, lines 9 to 62 | `maze_route`, 40 points | within 0 at every point, 900 ticks, nearest-point start, reversible |
| `aio-agility/travel.lua` `follow`, lines 84 to 110 | course routes | nearest-point start, within 4 |
| `the_grand_tree/navigation.lua`, lines 30 to 90 | conditional waypoint lists | per-waypoint radius chosen by quest state |
| about 20 per-quest `local function walk(...)` wrappers | all | each fixes its own within, ticks, `breaks`, run and activity defaults |
| `shared/movement.lua` | all | `walk`, `approach`, `click_once`, `route_start_index` |

The wrapper files are waterfall/tomb.lua, waterfall/ritual.lua,
witchs_house/quest.lua, witchs_house/garden.lua, witchs_house/experiment.lua,
aio-thieving/travel.lua, quest-runner/shared/preparation.lua,
the_grand_tree/completion.lua, evil-bob/island.lua,
monkey_madness_i/navigation.lua and the six Monkey Madness stage files.

The side logic falls into four buckets, and each bucket has a different
right owner:

1. Routing: start index, checkpoint cursors, precise corners, click retries,
   per-point within values. The walker already does all of this inside one
   walk; the scripts do it again around the walk.
2. Travel upkeep: stamina, poison cure, supply checks. These are conditions
   that should end a walk early so the script can act. They are not code
   that belongs between legs, because a leg boundary is an arbitrary place to
   check them.
3. Safety: prison and recapture checks. Solved at the script level already.
   Not touched here.
4. Constraints: avoid tiles, and the corridor the points describe. The first
   is already a `walk.to` field. The second has no field, which is the only
   reason the corridor routes need points at all.

### 12.2 What the offline planner says about each route

Method: a small harness compiled against the built plugin classes and the
bundled maps plans each scripted route from its first point to its last with
within 2, or within 0 for the maze, and also plans it leg by leg with the
within values the scripts use. Deviation is the largest Chebyshev distance
from any intermediate scripted point to the nearest tile of the single plan.
This is the static map only. The live overlay from 11.1 is not involved, and
the harness was not committed.

| Route | Points | Single plan | Tiles | Nodes | Deviation | Leg chain tiles |
| --- | --- | --- | --- | --- | --- | --- |
| ape_atoll_valley | 4 | found | 77 | 531 | 4 | 81 |
| denture_safe_approach | 2 | found | 3 | 2 | 0 | 3 |
| garkor_to_dentures | 4 | found | 43 | 122 | 4 | 51 |
| garkor_to_west_ladder | 3 | found | 139 | 5,624 | 1 | 141 |
| marim_gate_to_garkor | 3 | found | 72 | 405 | 4 | 73 |
| prison_to_dentures | 4 | found | 50 | 514 | 13 | 63 |
| prison_to_garkor | 4 | found | 57 | 740 | 4 | 59 |
| prison_to_temple_entry | 4 | found | 29 | 107 | 5 | 41 |
| temple_to_monkey_child | 8 | found | 68 | 715 | 19 | 80 |
| temple_trapdoor_approach | 5 | found | 1 | 0 | 1 | 4 |
| zoo_to_grand_tree | 5 | search limit | 0 | 250,000 | none | 189, one leg fails |
| zooknock_dungeon | 31 | found | 520 | 2,988 | 4 | 652 |
| tree_gnome_village maze_route | 40 | found | 200 | 12,689 | 1 | 255 |

Reading the table:

- Every Ape Atoll route, the Zooknock dungeon and the Tree Gnome maze plan in
  one piece, and the single plan is shorter than the leg chain in every case.
  The maze needs no points at all: the map already knows the maze, and the
  planner threads it in 200 tiles while the 40 scripted points force 255.
- A deviation of 4 or less means the scripted points sit on or beside the
  shortest path, so they add nothing. That covers ten of the twelve routes
  that plan.
- Two routes deviate by 13 and 19 tiles. In `prison_to_dentures` the single
  plan cuts through the middle of Marim around x 2771 to 2777 while the
  script hugs the east wall at x 2784 and then runs west along y 2763. In
  `temple_to_monkey_child` the single plan runs west through the town centre
  along y 2785 to 2787 while the script goes north to y 2806 first and follows
  the north edge. Both corridors are safety choices, so these two routes need
  a corridor mechanism, not legs.
- `zoo_to_grand_tree` hits the node cap. Issue 19 explains why.

Timing: the longest successful single plan, the maze, took 29 ms, the
Zooknock dungeon took 7 ms, and the failing zoo route burned 751 ms reaching
the cap.

### Issue 18. The scripts re-implement routing as leg loops

There are six copies of a route loop, `walk_route` in five Monkey Madness
files and the maze file plus `follow` in the agility script, each with its
own within, tick budget and retry policy. Every leg boundary is a place
where the walker stops, reports, and waits for Lua to decide to issue the
next `walk.to` for the next point of the same journey. That boundary is what
produces the endpoint clicks of Issue 12 and the click-once behaviour of
Issue 13, it pays the round trip and the cursor cost of section 10 on every
point, and it is why `route_start_index`, the checkpoint cursors and
`click_once` exist: they are all workarounds for the fact that a scripted
route cannot be resumed after an interruption, while a walker route can be,
because the walker replans from the current tile on every request.

The loops also hide map problems. The zoo route works today only because its
legs are short enough that the missing crossing in Issue 19 is always inside
the loaded scene when the relevant leg starts. Nobody had to notice that the
static map cannot cross it, and a single walk from the zoo would fail at
planning time.

### Issue 19. Legs hide static map gaps, and gate resolution can pick the wrong object

Two facts, one from the bundled map and one from the Sep 3 log.

Static: the fence line between y 3383 and y 3384 at x 2458 to 2464, where the
Tree Gnome Stronghold's south gate stands, has no walkable edge and no door
bit in the bundled maps. The offline plan for the leg from (2463,3376) to
(2461,3445) and the single plan from the zoo both expand the full 250,000
node budget and return `SEARCH_LIMIT`, which the script sees as plain
`unreachable`. The scripted leg to (2461,3382) ends six tiles south of the
gate, inside scene range, so the next leg plans with the live overlay and the
traversal object rule lets it through. That is the only reason the route
works. A door census of the bundled maps gives the wider picture:

| Door edges in the door map | Walkable in the collision map | Blocked in the collision map |
| --- | --- | --- |
| 2,541 | 2,233 | 308 |

The planner uses `canMove` for passability and `crossesDoor` only for the
surcharge, so a door edge the collision dump marks solid is never planned
through statically. The Stronghold gate is worse than those 308: it is absent
from both maps.

Live: on Sep 3 at 11:24:40 the plan from (2535,3360) to (2461,3382) crossed
the edge (2517,3357) to (2516,3357). The bundled map marks that edge solid and
not a door, and the offline plan for the same leg runs along y 3356 without
touching it. The live plan crossed it because `canPlanMove` accepts a
live-blocked edge when `findTraversalObject` finds a candidate, and
`adjacentPairedGate` accepts any wall object named gate with an Open action
within one tile of either edge tile, diagonals included. The Gate object 2041
at (2518,3356) is the double gate that spans the edges between y 3356 and y
3357 at x 2517 and x 2518; the door map says so. It does not span the fence
edge the plan chose. The walker then dispatched Open on it three times
between 11:24:46 and 11:24:53, `routeBlockCleared` never saw the fence edge
open, and the walk ended `unreachable` with reason
`obstacle_interaction_limit` without a replan, even though the plain route
along y 3356 was available.

### P13. One walk per journey: `via`, interrupt reasons, and a walker that owns the route

Change:

1. `walk.to` gains `via = { point, point, ... }`. The planner searches start
   to the first via point, each via point to the next, and the last to the
   destination, concatenates the results into one route, and executes it as
   one walk with one cadence, one obstacle handler and one receipt. A via
   point counts as passed when the route comes within two tiles of it. This
   replaces the `walk.route` and `leg` idea in P8; P8's cadence rules apply
   to the whole route unchanged. Only the routes whose corridor is a safety
   choice pass via points, which today means `prison_to_dentures`,
   `temple_to_monkey_child` and the trapdoor approach with its avoid tiles.
   Every other route passes a destination and nothing else.
2. `walk.to` gains `interrupt_on`, a small host-evaluated list checked every
   tick. Each entry ends the walk with `status = "interrupted"` and
   `reason` set to the entry name. The first set is `dialogue`, which absorbs
   today's `interrupt_on_dialogue`, `run_energy_below = n`, `poisoned`,
   `area = name` for entering a named zone, and `missing_item = name`. The
   script handles the reason, for example by drinking a stamina potion
   through `consumables.ensure_stamina`, and then issues the same `walk.to`
   again. Because the walker replans from the current tile, no start index
   and no checkpoint cursor is needed. The walker never learns what stamina
   or a prison is, and the script never learns what a leg is.
3. When the obstacle attempts run out, mark the edge blocked and replan with
   reason `live_route_blocked` instead of ending the walk. The existing
   `MAX_OBSTACLE_ATTEMPTS` and `blockedEdges` machinery already does this for
   locked doors; extend it to the exhausted case.
4. Tighten `adjacentPairedGate`: the candidate must sit on the same line as
   the blocked edge and share its orientation, which is the neighbour tile
   along the gate line and never the diagonal. Log the rejected candidates at
   debug level so the next wrong pick is visible.
5. Add a static edge supplement, a small resource of edges that are known to
   be traversal objects with the object name and action that opens them,
   merged into the door and collision maps at load. The first entry is the
   Stronghold gate. Pair it with an offline route audit, the harness from
   12.2 turned into a Gradle task, that plans every route and destination the
   scripts declare and reports any `SEARCH_LIMIT` or `UNREACHABLE` result.
   With P10's `search_limit` status the report distinguishes a missing edge
   from a genuinely unreachable tile.
6. Delete the leg machinery: the six `walk_route` functions and `follow`,
   `click_once` and `route_start_index` in `shared/movement.lua`, the
   checkpoint cursors, and the per-quest `walk` wrappers. `shared/movement.lua`
   keeps `walk` and `approach`, and `walk` takes one options table with
   `within`, `run`, `activity`, `via`, `avoid_tiles`, `interrupt_on` and
   `ticks`. The `routes` tables in the config files shrink to a destination
   and, for the corridor routes, a short `via` list.

Ownership after the change:

| Concern | Owner |
| --- | --- |
| Planning, corridors, rejoin after displacement, click cadence, camera | walker |
| Doors, gates, obstacle retries, edge memory | walker |
| Run toggle, arrival, timeouts, receipts | walker |
| Interrupt detection for the declared reasons | walker |
| Where to go, which corridor, which tiles to avoid | script |
| What to do when a walk is interrupted | script |
| Prison, recapture and other safety handling | script, unchanged |
| Plane changes and transports, until P11 lands | script, unchanged |

Why it addresses Issue 18: with one walk per journey there is nothing left
for a leg loop to do. Routing is in one place, the upkeep checks become
declared interrupt reasons instead of polling between points, and the
resume-after-interruption problem that motivated the checkpoints disappears
because the walker resumes from wherever the account is.

Why it addresses Issue 19: the audit surfaces every static gap before a run,
the supplement closes the known one, the orientation check stops the planner
from routing through a fence because a gate stands next to it, and the
exhausted-attempts replan turns the remaining wrong picks into a detour
instead of a failed walk.

### 12.3 File by file

- `monkey_madness_i/config.lua`: keep the `points` table; reduce each route
  to a destination, and give `prison_to_dentures`, `temple_to_monkey_child`
  and `temple_trapdoor_approach` a `via` list made from their current
  interior points.
- `garkor.lua`: the valley loop becomes one walk with
  `interrupt_on = { run_energy_below = 20, poisoned = true, area = "prison" }`, with
  the existing stamina, cure and `complete_capture` calls as the handlers for
  each reason. `route_to_garkor` becomes one walk with the same shape and
  the existing recapture report as the handler.
- `amulet.lua`: `walk_route` goes. The dungeon walk is one `walk.to` with
  `interrupt_on = { missing_item = ... }` for the supply check, and
  `retreat_for_supplies` stays as the handler. The precise corner rule goes;
  the planner already turns those corners in 4 tiles of deviation or less.
- `amulet_crafting.lua`: `walk_route`, the trapdoor loop and
  `reach_north_side` become three `walk.to` calls, the trapdoor one carrying
  the guard avoid tiles and a via list.
- `favor.lua`, `disguise.lua`, `infiltration.lua`: `walk_route` and the
  checkpoint cursors go; the prison zone checks become `area = "prison"`
  interrupts with the existing reports as handlers; the two `click_once`
  calls become plain walks with within 0.
- `tree_gnome_village/navigation.lua`: the maze walk is a single `walk.to`
  to `maze_inside` or `maze_outside`; the 40 point route is deleted.
- `aio-agility/travel.lua`: `follow` becomes one walk to the course start.
- `the_grand_tree/navigation.lua`: the conditional lists collapse to one
  destination per quest state.
- The wrapper files: each `local function walk` is replaced by a call to
  `movement.walk` with the same options, which removes about 20 copies of
  the same defaults.
- `GenericClientLuaScript.java`: parse `via` and `interrupt_on`, keep
  `interrupt_on_dialogue` as an alias for one release, extend the receipt.
- `GenericClientWalker.java`: chained planning for via points, the interrupt
  evaluator, the exhausted-attempts replan.
- `GenericClientSnapshot.java`: the paired gate orientation check.
- `GenericClientCollisionMap.java` plus a new resource: the edge supplement.
- `build.gradle`: the route audit task.
- `docs/walker-design.md`: describe `via`, `interrupt_on`, the supplement
  and the audit, and remove the claim that routes are segmented.

### 12.4 What stays as it is

- The prison capture, recapture and Safety Net logic, which the user has
  already made work. It moves from the middle of a loop to an interrupt
  handler but its content does not change.
- `avoid_tiles`, the hazard activity contexts, and the run and arrival rules.
- Plane changes, ladders, ships and teleports remain scripted steps between
  walks until P11 gives the walker a transition catalog.

| Issue | Addressed by | Why it is resolved |
| --- | --- | --- |
| 18. Scripts re-implement routing as leg loops | P13 items 1, 2 and 6 | One walk per journey, corridors as `via`, upkeep as interrupt reasons, leg helpers deleted. |
| 19. Legs hide map gaps, wrong gate picked | P13 items 3, 4 and 5 | Audit finds gaps, supplement closes them, orientation check and exhausted-attempts replan stop the fence detour from failing a walk. |

Order: items 3 and 4 are small bug fixes and can go first, on their own.
Item 5 follows, because the audit is what proves the scripts can lose their
points safely. Items 1 and 2 are the host change and should land with P8's
cadence and P10's `search_limit` status, since all three touch the same walk
request and receipt. Item 6 is then done route by route, starting with the
Tree Gnome maze and the Zooknock dungeon where the single plans are plainly
better, and ending with the two corridor routes once `via` has been watched
live on Ape Atoll, which the handoff says the user wants to confirm in
person.

## 13. Addendum, 2026-09-04: decisions, and the points the addenda left open

After sections 10 to 12 were written, four questions were put to the user
and answered the same day. This section records the answers, settles the
details that the earlier sections left to whoever implements them, and gives
one order across all thirteen proposals. It is still a review document. The
answers fix what the proposals mean, not who does them or when.

### 13.1 Decisions taken

| Question | Decision | What it changes in the proposals |
| --- | --- | --- |
| How much of this to do | All of it, P1 to P13 | The order in 13.4 covers the whole set, and the walker changes are designed against the P1 to P5 boundary rather than around it. |
| State of the two worktrees before work starts | Checkpoint commit first, on a local branch, nothing pushed | Both dirty trees are committed as they stand so that every later change is reviewable on its own. The handoff rule against reset, clean and pull is unchanged. |
| What may run live during the work | Safe walks only; hazardous travel stays with the user | Walks outside the hazardous activity contexts, for example the Tree Gnome maze or the Ardougne to Stronghold road, may run live to check `via` and the interrupts. Ape Atoll, the Wilderness and anything else that puts the account at risk is walked with the user watching. |
| Where the `area` interrupt gets its zones | Bounds passed with the walk | Lua sends the rectangles and the host keeps no game knowledge. The `areas` module already holds the numbers. |

Live runs mean deploying to the installed tree, and the installed Lua tree
is already ahead of the repo in places: on 2026-09-04 the installed
`aio-melee.lua` and `shared/bank.lua` carried Sep 3 edits that the repo does
not have. A `diff -rq` between the two trees, and a copy of the newer files
into the repo, has to come before the checkpoint commit, or the checkpoint
records the wrong state and the first deploy overwrites live fixes.

### 13.2 Details the earlier sections left open

The request shape. P13 named the new fields; this is the shape they take,
with the zone bounds inline as decided. The point and area names in the
example are placeholders for whatever the configs and the `areas` module
already define:

```lua
gc.walk.to {
  destination = points.dentures,
  within = 2,
  run = true,
  via = { points.prison_east_wall, points.south_lane },
  avoid_tiles = temple_guard_tiles(),
  interrupt_on = {
    dialogue = true,
    run_energy_below = 20,
    poisoned = true,
    missing_item = { "Prayer potion", "Shark" },
    area = { name = "prison", bounds = areas.prison_bounds },
  },
}
```

- `via` is a list of points on the destination's plane. A via point on
  another plane is rejected when the request is parsed, until P11 lands.
- `missing_item` matches inventory names by prefix, so dose counts and
  stack sizes do not matter. The interrupt fires when any listed prefix has
  no match.
- `area.bounds` is a list of rectangles, each `{ x1, y1, x2, y2, plane }`.
  The interrupt fires on the first tick the account stands inside any of
  them. Several named areas can be passed as a list.
- `interrupt_on_dialogue = true` stays as an alias of `dialogue = true` for
  one release and is removed in the same codemod pass as P7.

The receipt. An interrupted walk returns `status = "interrupted"`, `reason`
set to the entry name, and `detail` carrying the zone name, the missing
prefix, or the energy value. The existing fields requested, reached, ticks,
plans and clicks stay. A plan that fails on a via segment returns the
existing unreachable or the new `search_limit` status with `segment` set to
the index of the segment that failed. A via point is never skipped silently,
because every via point in the scripts is a safety choice.

Tick order. The interrupts are evaluated at the top of the walker tick,
before the arrival check and before `nearestRouteIndex`. A prison capture on
Ape Atoll is also a displacement, and if the off-route check ran first the
walker would replan from inside the prison and start walking the account
out before Lua heard about it.

Via semantics. A via point counts as passed when the route comes within two
tiles of it, and the planner does not require standing on it. The segments
are planned in one planner call, back to back, and the receipt reports the
total tile count. Replans after `off_route` or `live_route_blocked` start
from the current tile to the next unpassed via point, so an interrupted walk
that is issued again does not revisit corridor points it already passed.

The edge supplement. A resource at
`src/main/resources/com/genericclient/navigation/edge-supplement.json`, a
list of entries with `from`, `to`, `plane`, `object`, `action`, `note` and
`added`. At load each entry sets the walk bit in both directions and the
door bit, so the surcharge applies and the traversal object rule handles
the live object as it does for any door. The first entry is the Tree Gnome
Stronghold south gate. Its exact edge is read from the live scene before it
is written down, since the static map shows only the fence line between
y 3383 and y 3384.

The route audit. The routes live in the Lua repo and the planner in the Java
repo, so the audit is two small pieces. The Lua side gets an exporter, run
with `lua5.4` like the tests, that loads every quest config, walks its
`routes` and `points` tables, and writes a tab-separated file with route
name, index, x, y and plane. The Java side gets a Gradle task, `routeAudit`,
that reads that file, plans every route first to last with the within value
the script uses, prints one line per route with status, tiles, nodes and
milliseconds, and fails on any `SEARCH_LIMIT` or `UNREACHABLE` result. The
file format is the one the harness in Appendix A already reads, and the
harness is the seed of the task.

### 13.3 What would show the proposals worked

- Both suites pass: `./gradlew test --offline` in the plugin, the Lua loop
  over `tests/*.lua` in the script catalog, and `node --test` in `mcp`.
- `routeAudit` reports found for all 13 routes of section 12.2 once the
  supplement is in, and the single-plan tile counts match or beat that table.
- `grep` finds no `walk_route`, `follow`, `click_once` or
  `route_start_index` in the Lua tree, and no per-quest `local function
  walk`.
- A unit test reproduces the Sep 3 gate case: a wall gate at (2518,3356)
  must not be accepted as the traversal object for the edge (2517,3357) to
  (2516,3357).
- A unit test covers the exhausted attempts: three failed Open dispatches
  end in a `live_route_blocked` replan, not an `obstacle_interaction_limit`
  receipt.
- A safe live walk through the Tree Gnome maze completes as one `walk.to`
  with one receipt and no scripted points.
- A hazardous walk on Ape Atoll, watched by the user, shows the P8 cadence
  with no click-once at corridor points, and the existing recapture handling
  fires from an `area` interrupt rather than from a loop.
- `docs/walker-design.md` describes `via`, `interrupt_on`, the supplement
  and the audit, and no longer claims that routes are segmented.

### 13.4 One order across all proposals

Sections 7, 10, 11 and 12 each gave their own order. Merged, with the
dependencies they stated:

1. Reconcile the installed Lua tree with the repo, then the checkpoint
   commits in both repos.
2. The self-contained fixes: P13 items 3 and 4, the exhausted-attempts
   replan and the paired gate orientation check; P13 item 5, the supplement
   and the audit, because the audit is what proves the scripts can lose their
   points; and P8's one-line hazardous revert with the alternating final
   tile.
3. P5 long-break lifecycle and P6 plain operator actions, which section 7
   put first because they remove the most visible interference.
4. P1, the boundary per await opened by the host. Then, as one walker change
   set on top of it: P8's cadence, P10's rejoin and `search_limit` status,
   and P13 items 1 and 2, `via` and `interrupt_on`. All three touch the same
   request, receipt and per-click hook.
5. P13 item 6, the script deletions, route by route: the Tree Gnome maze and
   the Zooknock dungeon first, then the plain Ape Atoll routes, and the two
   corridor routes last after a watched Ape Atoll run.
6. P2 intent scope and P3 policy record, then P9 fidgets after P3 because
   the anchors and the `fidget` field come from the policy record, then P4
   time-based micro pressure.
7. P12 edge memory, any time after step 2, since it extends the supplement's
   edge model with learned edges.
8. P11 transitions, last among the walker items, after P1 to P5 have
   settled.
9. P7, the codemod that retires `breaks` and the `interrupt_on_dialogue`
   alias.

## Appendix A. The offline route harness

The three programs below produced the tables in sections 11.2, 12.2 and
Issue 19. They were compiled against the built plugin classes and the
bundled maps, never committed, and are recorded here so the numbers can be
reproduced and so the route audit has a starting point. `routes.txt` is
tab-separated with route name, index, x, y and plane, one point per line,
dumped from the two quest configs with `lua5.4`:

```
mm.prison_to_dentures	1	2784	2806	0
mm.prison_to_dentures	2	2784	2770	0
mm.prison_to_dentures	3	2780	2763	0
mm.prison_to_dentures	4	2764	2763	0
```

Build and run, from the plugin repo root, with the newest `runelite-api`
jar under `~/.gradle/caches` on the classpath:

```
javac -cp "build/classes/java/main:$RUNELITE_API_JAR" -d out RouteCheck.java LegCheck.java DoorProbe.java
java -cp "out:build/classes/java/main:src/main/resources:$RUNELITE_API_JAR" com.genericclient.RouteCheck routes.txt
java -cp "out:build/classes/java/main:src/main/resources:$RUNELITE_API_JAR" com.genericclient.LegCheck routes.txt mm.zoo_to_grand_tree 2461,3383
java -cp "out:build/classes/java/main:src/main/resources:$RUNELITE_API_JAR" com.genericclient.DoorProbe
```

RouteCheck plans every route in the file first to last and leg by leg, and
prints the comparison table:

```java
package com.genericclient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

public final class RouteCheck
{
	public static void main(String[] args) throws Exception
	{
		GenericClientCollisionMap map = GenericClientCollisionMap.loadBundled();
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(map);
		Map<String, List<WorldPoint>> routes = new LinkedHashMap<>();
		for (String line : Files.readAllLines(Paths.get(args[0])))
		{
			String[] parts = line.split("\t");
			routes.computeIfAbsent(parts[0], key -> new ArrayList<>()).add(new WorldPoint(
				Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4])));
		}
		System.out.println("route\tpoints\tsingle\ttiles\tnodes\tms\tmaxDev\tworstPt\tlegTiles\tlegNodes\tlegFail");
		for (Map.Entry<String, List<WorldPoint>> entry : routes.entrySet())
		{
			List<WorldPoint> route = entry.getValue();
			WorldPoint first = route.get(0);
			WorldPoint last = route.get(route.size() - 1);
			int finalWithin = entry.getKey().startsWith("tgv") ? 0 : 2;
			long started = System.nanoTime();
			GenericClientPathfinder.Result single = pathfinder.find(first, last, finalWithin);
			long ms = (System.nanoTime() - started) / 1_000_000L;
			int maxDeviation = -1;
			int worst = -1;
			if (single.getStatus() == GenericClientPathfinder.Status.FOUND)
			{
				for (int index = 1; index < route.size() - 1; index++)
				{
					int best = Integer.MAX_VALUE;
					for (WorldPoint tile : single.getPath())
					{
						best = Math.min(best, Math.max(Math.abs(tile.getX() - route.get(index).getX()),
							Math.abs(tile.getY() - route.get(index).getY())));
					}
					if (best > maxDeviation)
					{
						maxDeviation = best;
						worst = index + 1;
					}
				}
			}
			int legTiles = 0;
			int legNodes = 0;
			int legFailures = 0;
			WorldPoint cursor = first;
			for (int index = 1; index < route.size(); index++)
			{
				int within = index == route.size() - 1 ? finalWithin : (entry.getKey().startsWith("tgv") ? 0 : 6);
				GenericClientPathfinder.Result leg = pathfinder.find(cursor, route.get(index), within);
				legNodes += leg.getExpandedNodes();
				if (leg.getStatus() != GenericClientPathfinder.Status.FOUND)
				{
					legFailures++;
					cursor = route.get(index);
					continue;
				}
				legTiles += leg.getPath().size();
				cursor = leg.getPath().get(leg.getPath().size() - 1);
			}
			System.out.println(entry.getKey() + "\t" + route.size() + "\t" + single.getStatus() + "\t" +
				single.getPath().size() + "\t" + single.getExpandedNodes() + "\t" + ms + "\t" +
				maxDeviation + "\t" + worst + "\t" + legTiles + "\t" + legNodes + "\t" + legFailures);
		}
	}
}
```

LegCheck prints each leg of one route, the single plan sampled every eight
tiles, and an edge probe of the static map around a tile:

```java
package com.genericclient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

public final class LegCheck
{
	public static void main(String[] args) throws Exception
	{
		GenericClientCollisionMap map = GenericClientCollisionMap.loadBundled();
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(map);
		String wanted = args[1];
		List<WorldPoint> route = new ArrayList<>();
		for (String line : Files.readAllLines(Paths.get(args[0])))
		{
			String[] parts = line.split("\t");
			if (parts[0].equals(wanted))
			{
				route.add(new WorldPoint(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4])));
			}
		}
		System.out.println("== " + wanted + " legs");
		WorldPoint cursor = route.get(0);
		for (int index = 1; index < route.size(); index++)
		{
			GenericClientPathfinder.Result leg = pathfinder.find(cursor, route.get(index), index == route.size() - 1 ? 2 : 6);
			System.out.println("leg " + index + " " + fmt(cursor) + " -> " + fmt(route.get(index)) + " " + leg.getStatus() +
				" tiles=" + leg.getPath().size() + " nodes=" + leg.getExpandedNodes());
			if (leg.getStatus() == GenericClientPathfinder.Status.FOUND)
			{
				cursor = leg.getPath().get(leg.getPath().size() - 1);
			}
			else
			{
				cursor = route.get(index);
			}
		}
		GenericClientPathfinder.Result single = pathfinder.find(route.get(0), route.get(route.size() - 1), 2);
		System.out.println("== single plan " + single.getStatus() + " tiles=" + single.getPath().size());
		StringBuilder sampled = new StringBuilder();
		for (int index = 0; index < single.getPath().size(); index += 8)
		{
			sampled.append(fmt(single.getPath().get(index))).append(' ');
		}
		System.out.println("sampled every 8 tiles: " + sampled);
		if (args.length > 2)
		{
			String[] probe = args[2].split(",");
			int px = Integer.parseInt(probe[0]);
			int py = Integer.parseInt(probe[1]);
			System.out.println("== edge probe around " + args[2]);
			for (int y = py + 3; y >= py - 3; y--)
			{
				StringBuilder row = new StringBuilder();
				for (int x = px - 3; x <= px + 3; x++)
				{
					boolean n = map.canMove(x, y, 0, 0, 1);
					boolean e = map.canMove(x, y, 0, 1, 0);
					boolean s = map.canMove(x, y, 0, 0, -1);
					boolean w = map.canMove(x, y, 0, -1, 0);
					boolean door = map.crossesDoor(x, y, 0, 0, 1) || map.crossesDoor(x, y, 0, 1, 0) ||
						map.crossesDoor(x, y, 0, 0, -1) || map.crossesDoor(x, y, 0, -1, 0);
					String cell = (!n && !e && !s && !w) ? "####" : ((n ? "N" : ".") + (e ? "E" : ".") + (s ? "S" : ".") + (w ? "W" : "."));
					row.append(door ? cell.toLowerCase() : cell).append(' ');
				}
				System.out.println("y=" + y + "  " + row);
			}
			System.out.println("legend: N/E/S/W passable sides, #### fully blocked, lowercase = a door edge on that tile");
		}
	}

	private static String fmt(WorldPoint point)
	{
		return "(" + point.getX() + "," + point.getY() + ")";
	}
}
```

DoorProbe prints the per-side walk and door bits around the Ardougne gate,
the static path for the leg that failed live on Sep 3, and the door census:

```java
package com.genericclient;

import net.runelite.api.coords.WorldPoint;

public final class DoorProbe
{
	public static void main(String[] args) throws Exception
	{
		GenericClientCollisionMap map = GenericClientCollisionMap.loadBundled();
		GenericClientPathfinder pathfinder = new GenericClientPathfinder(map);
		System.out.println("== per-side bits near the Ardougne gate (walk / door) N E S W");
		for (int y = 3358; y >= 3355; y--)
		{
			for (int x = 2515; x <= 2520; x++)
			{
				System.out.printf("(%d,%d) walk=%s%s%s%s door=%s%s%s%s   ",
					x, y,
					map.canMove(x, y, 0, 0, 1) ? "N" : ".", map.canMove(x, y, 0, 1, 0) ? "E" : ".",
					map.canMove(x, y, 0, 0, -1) ? "S" : ".", map.canMove(x, y, 0, -1, 0) ? "W" : ".",
					map.crossesDoor(x, y, 0, 0, 1) ? "N" : ".", map.crossesDoor(x, y, 0, 1, 0) ? "E" : ".",
					map.crossesDoor(x, y, 0, 0, -1) ? "S" : ".", map.crossesDoor(x, y, 0, -1, 0) ? "W" : ".");
			}
			System.out.println();
		}
		GenericClientPathfinder.Result leg = pathfinder.find(new WorldPoint(2536, 3357, 0), new WorldPoint(2461, 3382, 0), 6);
		System.out.println("== static leg 2 tiles with x in 2512..2522");
		StringBuilder line = new StringBuilder();
		for (WorldPoint point : leg.getPath())
		{
			if (point.getX() >= 2512 && point.getX() <= 2522)
			{
				line.append('(').append(point.getX()).append(',').append(point.getY()).append(") ");
			}
		}
		System.out.println(line);
		System.out.println("== global door edge census, planes 0..3");
		long doorEdges = 0;
		long doorEdgesWalkable = 0;
		for (int plane = 0; plane < 4; plane++)
		{
			for (int x = 1024; x < 4224; x++)
			{
				for (int y = 2496; y < 4224; y++)
				{
					if (map.crossesDoor(x, y, plane, 0, 1))
					{
						doorEdges++;
						if (map.canMove(x, y, plane, 0, 1)) doorEdgesWalkable++;
					}
					if (map.crossesDoor(x, y, plane, 1, 0))
					{
						doorEdges++;
						if (map.canMove(x, y, plane, 1, 0)) doorEdgesWalkable++;
					}
				}
			}
		}
		System.out.println("door edges=" + doorEdges + " walkable in collision map=" + doorEdgesWalkable + " blocked=" + (doorEdges - doorEdgesWalkable));
	}
}
```
