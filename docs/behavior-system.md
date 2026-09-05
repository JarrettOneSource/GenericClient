# Behavior system

This is the implemented scripting API 3 contract. GenericClient owns behavior timing, policy resolution and input cancellation; Lua declares activity and short action sequences. The source and test acceptance state is tracked in [behavior-framework-implementation.md](behavior-framework-implementation.md). Fresh acceptance of the API 3 build in the live client remains pending.

## Policy and activity

`gc.activity(name, policy)` declares the coroutine's activity and optional independent overrides. An await captures that declaration. Its optional `activity` selects another preset for that await, and its `policy` then overrides individual fields. When no activity has been declared, the action type selects the initial preset. `gc.state(name)` changes the script's displayed state without changing policy.

| Activity | Breaks | Cursor release | Mouse | Expected damage | Prayer owner | Walk refresh | Fidgets |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `general`, `questing` | Yes | `with_break` | `natural` | No | `guard` | No | `full` |
| `travel` | Yes | `independent` | `natural` | No | `guard` | No | `full` |
| `skilling` | Yes | `with_break` | `natural` | No | `script` | No | `full` |
| `combat` | No | `none` | `fast` | Yes | `guard` | No | `drift` |
| `hazardous_travel` | No | `none` | `fast` | Yes | `guard` | Yes | `drift` |
| `dialogue`, `banking`, `trading` | No | `none` | `natural` | No | `guard` | No | `none` |
| `manual` | No | `none` | `natural` | No | `script` | No | `none` |

The Lua policy fields are `breaks`, `cursor_release`, `mouse`, `damage_expected`, `prayer_owner`, `walk_refresh`, and `fidget`. The boolean fields require booleans; the other fields accept the names in the table. Unknown fields and values are rejected.

Repeatable combat can explicitly permit breaks while keeping combat's mouse, damage and prayer policy:

```lua
gc.activity("combat", { breaks = true })
```

A single urgent action can suppress every discretionary behavior:

```lua
return gc.await {
  action = { type = "consumable.cure_poison" },
  policy = { breaks = false, cursor_release = "none", fidget = "none" },
}
```

The fields are independent: disabling breaks alone does not disable an independent cursor release or fidgets. The retired per-await `breaks` field is rejected.

The resolver applies current observations to the captured policy:

- Disabling automatic combat prayer assigns prayer ownership to the script.
- An observed threat or the damage grace suppresses breaks, releases and fidgets and selects the fast mouse when damage is unexpected.
- Unavailable snapshots, physical takeover, random-event ownership, emergency recovery and an active intent suppress discretionary behavior.
- Plain execution suppresses discretionary behavior and selects the natural mouse while retaining the declared damage, prayer and walk settings.

The activity label remains the declared activity. `declared_policy`, `effective_policy`, and `policy_reasons` explain the resolution in behavior status and `BEHAVIOR_POLICY` logs.

## Semantic boundaries and intents

The Lua host owns one boundary around each semantic action. Target resolution and native input occur after the entry check. Entry may wait for an already active, permitted break; it does not start a new break. Successful input completion permits the post-action evaluation. A verified action result survives an error in that post-action evaluation, with the error attached to its behavior receipt.

An item-use operation, bank loadout, prayer change, or context-menu selection may contain several native clicks. Those clicks share the operation's boundary. Native input primitives do not make their own break decisions.

A journey performs the entry check once and gives each route click its own boundary. Run toggles, door attempts and transport steps do not add break boundaries. Journey completion does not duplicate the final click's evaluation. Reads, tick waits, checkpoints, safety configuration, client behavior configuration and explicit mouse parking do not introduce discretionary boundaries.

Use an intent for a short sequence whose steps belong together:

```lua
-- Reach the bank before entering this scope.
local banking = gc.require("shared_bank")
return gc.intent("bank.withdraw", function()
  local bank, failure = banking.open()
  if not bank then return failure end
  return gc.await {
    action = {
      type = "bank.loadout",
      items = { { id = 526, quantity = 28 } },
      minimum_free_slots = 0,
      close = true,
    },
  }
end)
```

`gc.intent(name, fn)` opens one outer boundary and runs `fn` with discretionary behavior suppressed. Nested intents share the outer boundary and receipt name. Normal returns preserve all values, including `nil`; errors unwind the scope and propagate. An awaited timeout is still a receipt that Lua may handle. Cancellation revokes the scope's input, and emergency pauses preserve its progress and verified results.

Scopes longer than 30 seconds produce one `INTENT_LONG` warning after entry. Long approaches, whole quests and training loops stay outside scopes. Urgent recovery actions retain explicit policy where waiting for an existing ordinary break would be inappropriate.

Receipts inside the scope include `intent`. `gc.read("behavior")` adds `intent`, `intent_depth`, `intent_elapsed_millis` and `last_intent`. Logs include `INTENT_STARTED` and `INTENT_ENDED`.

## Account profile

SHA-256 labels derived from RuneLite's account hash generate stable traits. A Gaussian copula correlates related traits; the raw account hash and display name are not persisted as the seed. Runtime decisions use a fresh JDK random source.

| Seeded trait | Envelope |
| --- | --- |
| Base micro rate | 0.72–36 per owned active hour, before activity weighting and phase boosts |
| Cursor release during an eligible micro break | 15–95% |
| Micro body median | 2–6 seconds |
| Micro tail | 1–4% chance of a 12–120 second tail; increased at a phase |
| Micro duration | 1,000–119,999 milliseconds |
| Long cadence | About 40–300 owned active minutes |
| Long duration median | 7–22 minutes; sampled duration bounded to 3–60 minutes |
| Long mode reversal | 2–15% chance of the non-favored AFK/logout mode |
| Mouse path duration | 300–650 milliseconds in 25 ms steps |
| Typing | 35–100 WPM in 5 WPM steps |
| Dialogue reading | 0–100 in 5-point steps |
| Ordinary walk cadence | Usual 2–6 second gaps, with occasional longer gaps |
| Near walk target | Profile-selected chance of 60–90% of projectable route reach |

Settings can override the exposed account controls. The profile reports effective values and whether it is customized. The serialized `micro_break_probability` field supplies the fraction of the 36-per-hour reference rate; `micro_rate_per_active_hour` reports that rate directly. The title and summary describe numeric traits and do not influence decisions. Profile-derived cursor and walk traits remain stable when unrelated manual settings change.

## Owned time and micro pressure

The controller accrues time only while logged in and a standalone script owns automatic input. Tick waits can still represent owned work. Idle, operator-only, manual, logged-out and active-break time do not accrue pressure. Each observed interval contributes at most five seconds, preventing a suspended process from charging a large unobserved gap.

For owned active hours `dt`, profile fraction `p`, and activity weight `w`:

```text
micro pressure increment = dt × 36 × p × w
```

General activity uses weight 0.8; travel and hazardous travel use 0.6; manual uses zero; other activities use 1.0. The standalone script supplies that activity even while an operator REPL call is active. A policy that temporarily forbids breaks can defer the next boundary without losing accumulated pressure.

One sampled exponential budget remains in force until pressure reaches it and an eligible completed action or phase can start the break. Repeated boundary checks do not resample the cumulative chance. Starting a micro break consumes all accumulated pressure and samples the next budget, so a delayed boundary cannot queue a burst of old breaks.

A qualifying phase adds profile-derived micro pressure. Phase boosts have a two-minute global and five-minute per-name cooldown in owned active time. Repeating a phase cannot repeatedly charge the same boost.

## Long breaks and phases

A long break uses one persisted exponential hazard budget. Its cumulative hazard is quadratic after the account's refractory period. The controller compares that budget at completed actions and phases, allowing for observed boundary spacing. It never starts a new long break between target resolution and input.

A new standalone session gets 6–14 owned active minutes of grace for post-action long breaks. Explicit phases can still acknowledge an existing due obligation. Phase bonuses grow with long-clock maturity and are subject to the phase cooldowns. A declared policy or current safety condition can defer evaluation.

Completing a long break resets the long clock and budget, clears micro pressure, and suppresses the next micro evaluation. Ending one early preserves the long obligation and marks it deferred. It becomes eligible again at a phase after the profile's active-time refractory period. Receipts report the actual `elapsed_millis` and `end_reason`; an early end is not credited as the full scheduled rest. A sufficiently long partial rest also clears micro pressure.

AFK and logout breaks use the same lifecycle. Logout mode waits for login and world readiness before releasing the suspended action. A running break persists across a client restart. The in-game countdown can end either break kind; the dashboard's long-break banner ends only that long break.

`gc.phase(name, options)` records a major transition. `options.activity` updates the declaration before evaluation, while `options.policy` and `options.humanize` apply to that phase request. `gc.state` only updates the displayed script state.

## Cursor behavior and input ownership

A micro-break cursor release is selected after the break starts and appears under that break's `cursor_release` receipt. An independent travel glance belongs to the cursor model and requires another minute of owned active time between glances.

The cursor model uses a sampled pressure budget during eligible quiet windows. Its `full` policy permits small drift, occasional relocation, declared-target anticipation and eligible travel glances; `drift` permits only small motion. It requires enough quiet time for the motion plus an input margin and uses captured viewport anchors. Idle parking requires a stable idle window and a known account profile.

Every rest movement has its own child scope. When the window ends, it cancels only that rest movement. It cannot cancel an independently owned break or explicit offscreen action. A newly dispatched action revokes stale rest input before taking the cursor.

Action, journey and click scopes carry cancellation tickets. Native queued events and delayed callbacks must still hold their original authority. A pause/resume or replacement run cannot revive an old selected click, prayer change, configuration update or transport step. Native operations execute outside the walker monitor.

Physical pointer input pauses ordinary automation and cancels its synthetic input. A 1.5-second quiet interval permits resumption. Physical Escape is the manual-stop boundary for the script, REPL, script safety and scheduler. Synthetic mouse/keyboard events are distinguished from physical input. Emergency recovery uses separate authority and remains independent of discretionary scopes.

## Damage and prayer

The combat guard publishes observed attackers and a bounded, 60-second damage grace. Copied hitsplats and consecutive HP/poison observations distinguish supported poison or venom damage from ordinary hits. Matching poison/venom evidence does not start or refresh damage grace. Missing or conflicting evidence does not become an exemption.

`damage_expected` controls the discretionary response; forced healing and emergency escape keep their own thresholds. `prayer_owner` decides who changes protection prayer. Guard input is revoked on reset, policy handoff or emergency takeover, and late prayer/potion completions cannot mutate a replacement owner. The guard releases only prayer it owns and respects physical takeover while idle.

## Persistence, ownership and checks

Behavior state uses `genericclient_behavior_state.v3`. It persists the long clock and budget, deferred obligation, micro pressure and budget, phase history, counters and an active break. Earlier supported state versions retain their long state and initialize micro pressure through the explicit migration. State and override writes use the shared atomic-file writer under the account-derived profile ID.

| Owner | Responsibility |
| --- | --- |
| `GenericClientBehaviorProfile` | Stable traits and duration/cadence sampling |
| `GenericClientBehaviorPolicy`, `GenericClientPolicyResolver` | Independent fields and current effective policy |
| `GenericClientBehaviorController`, `GenericClientBehaviorState`, `GenericClientBehaviorStore` | Owned time, pressure, break lifecycle and persistence |
| `GenericClientActionBoundary`, `GenericClientLuaIntent` | Semantic boundaries, nested scopes and cancellation |
| `GenericClientCursorBehavior` | Quiet-window cursor motion and rest ownership |
| `GenericClientCombatGuard`, `GenericClientDamageTracker` | Threat/damage observations and guard-owned prayer |

Use `./gradlew --offline qualityReport`, the native-input tests under Xvfb where required, and the catalog's `python3 tools/validate.py`. Tests cover dense/sparse boundary schedules, persistence, session grace, interrupted long breaks, policy precedence, native queued input, cursor cancellation and scope placement. Passing those checks is separate from packaging, installation and fresh live acceptance.
