# Seeded behavior system

GenericClient derives one stable behavior profile from RuneLite's unique
per-RuneScape-account hash. The display name is never part of the seed, and the
raw account hash is never written to logs or profile files.

The profile is deterministic. Individual break decisions and durations use a
fresh runtime random source.

## Profile traits

Independent SHA-256 labels derive an attention style and separate short, long,
duration, mouse, phase, logout, and idle-edge traits. A Gaussian copula softly
correlates attention style with short cadence, long cadence, short duration,
and phase sensitivity while preserving all four combinations of frequent or
rare short and long breaks.

| Trait | Envelope |
| --- | --- |
| Post-action micro probability | 2-100%, population midpoint 35% |
| Micro body median | 2-6 seconds |
| Micro tail | 4-12% chance of a log-uniform 12-120 second duration |
| Micro hard bounds | At least 1 second and strictly below 120 seconds |
| Long cadence | 40-300 active minutes, population midpoint about 110 |
| Long median | 7-22 minutes |
| Long hard bounds | 3-60 minutes |
| Phase short weight | Equivalent to 1-4 ordinary short chances |
| Long-mode reversal | 2-15% chance of the non-favored AFK/logout choice |
| Idle edge | One stable choice from left, right, top, or bottom |
| Mouse move duration | 300-650 milliseconds in 25 ms steps |

The dashboard and MCP status expose a title and plain-language summary generated
only from these numeric values. That text never feeds back into behavior.

While a break is active, a small top-center overlay shows only its kind and
remaining time. It disappears completely when the profile is ready.
Long breaks also show a transient **Break** banner above the dashboard's
connection status. Its × button manually ends only the active long break;
micro breaks keep their original timer.

### Manual overrides

The Settings page can override the understandable profile controls per account:
micro chance and duration, long-micro chance, long-break interval and duration,
phase boost, preferred AFK/logout style, style-switch chance, idle edge, and
mouse move duration.
Derived refractory, hazard scale, summary, and downtime are recomputed from the
custom values. **Use seeded** deletes the override and restores the exact
account-derived profile.

Overrides live beside runtime state as `overrides-<profile-id>.json`. The
profile ID is a one-way derived identifier; the raw account hash is not stored.

## Action contract

A composite client interaction evaluates breaks. For walking, one interaction
contains any needed camera turn, one recorded-template cursor movement, and the
click that advances the interaction. Context-menu walking treats its right-click
and menu-selection click as separate interactions and reopens the menu if a
break closes it.
Every dispatched route interaction runs its own before/after evaluation. A
`walk.to` task containing eight route clicks therefore performs eight micro
rolls, not one roll around the whole task. Low-level mouse path samples do not
roll independently.

Automated Lua and MCP actions default to breaks enabled:

```lua
local result = gc.await {
  action = {
    type = "walk.to",
    destination = { x = 3210, y = 3424, plane = 0 },
  },
}
```

A time-sensitive sequence can bypass both micro and long breaks for every
composite interaction it performs:

```lua
local result = gc.await {
  action = { type = "walk.random" },
  breaks = false,
}
```

Explicit dashboard actions are operator commands and bypass behavior. Status,
reads, logging, script stop/reset, manifest editing, and MCP control remain
responsive during a break.

## Micro breaks and off-canvas idle

A micro roll occurs after each dispatched composite interaction. If selected:

1. the recorded mouse-template matcher moves the synthetic client cursor to the
   account's stable off-canvas edge;
2. the Lua action receipt waits for the forced micro duration;
3. after the timer ends, script execution may continue, but the cursor stays
   outside the canvas until another action needs it.

The profile's idle side is stable. The next action randomizes a new off-screen
origin along that same side before applying its recorded movement template, so
the cursor does not repeatedly leave and re-enter through one identical point.

The natural off-canvas idle between actions is workload-driven and is not part
of forced-break downtime.

## Long breaks

Long pressure is an independent active-time renewal process. For profile cadence
`M`, refractory `R = clamp(0.3M, 10, 60)` minutes, and
`lambda = (M - R) / 0.886226925`, cumulative hazard after `s` active minutes is:

```text
H(s) = (max(0, s - R) / lambda)^2
```

A fresh exponential budget is persisted. Long becomes due when cumulative
hazard reaches that budget and begins at the next eligible safe boundary.
`breaks=false` and long-running composite interactions preserve accumulated pressure.
Micro breaks never reset or suppress it. If long and micro are both selected,
long wins.

At long-break start, the profile fresh-rolls its stable AFK/logout preference.
At the deadline GenericClient uses the Jagex Launcher session to return to the
world if the client logged out naturally or deliberately. A completed long
break resets both behavior processes and suppresses the first post-return micro
roll.

Manually ending a long break follows the same completion path: it cancels the
remaining timer, restores the Jagex-backed session when needed, resets long
pressure, suppresses the first micro roll, and only then resumes the waiting
script action.

## Phase transitions

`gc.phase` marks a major completed state and evaluates a heavier profile-shaped
break roll before the next phase runs:

```lua
gc.phase("banking.complete")
```

Repeating the current phase is a no-op. Accepted heavy evaluations have a
two-active-minute global cooldown and a five-active-minute per-name cooldown.
The short chance is `1 - (1 - p)^k`, where `k` is the profile's 1-4 phase
weight. The long bonus grows with the square of long-cycle maturity, so an
early phase cannot repeatedly force extended breaks.

## Persistence and diagnostics

State files live in:

```text
~/.runelite/genericclient/behavior/
```

They contain only the derived profile ID, active-time progress, fresh hazard
budget, phase cooldowns, counts, and an in-progress break deadline. Writes are
atomic. An in-progress break resumes after a plugin restart.

`client_status`, `behavior_profile`, `behavior_status`, and
`gc.read("behavior")` expose structured diagnostics. `session_logout` and
`session_login` expose the same synthetic widget/login controller used by long
breaks for direct diagnostics and orchestration.

## Synthetic input

Every automated mouse movement and click is delivered as client-canvas events.
The existing recorded template matcher still generates the complete path, but
GenericClient no longer reads or moves the operating-system pointer. Returning
from off-canvas idle emits focus/enter events at the actual randomized edge
crossing; leaving emits exit/focus-loss events. Synthetic events are marked so
manual mouse-profile recording ignores them. Walking rotates the client camera through RuneLite's injected camera yaw
target before recomputing the canvas/minimap projection; it never moves the
operating-system cursor.
