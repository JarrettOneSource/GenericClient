# Seeded behavior system

GenericClient derives one stable behavior profile from RuneLite's unique
per-RuneScape-account hash. The display name is never part of the seed, and the
raw account hash is never written to logs or profile files.

The profile is deterministic. Individual break decisions and durations use a
fresh runtime random source.

## Profile traits

Independent SHA-256 labels derive an attention style and separate micro-break,
cursor-release, long-break, duration, mouse, phase, logout, and idle-edge
traits. A Gaussian copula softly correlates them without making one decision
depend on another.

| Trait | Envelope |
| --- | --- |
| Post-action micro probability | 2-100%, population midpoint 35% |
| Post-action cursor release | 15-95% |
| Micro body median | 2-6 seconds |
| Micro tail | 1-4% chance of a log-uniform 12-120 second duration |
| Micro hard bounds | At least 1 second and strictly below 120 seconds |
| Long cadence | 40-300 active minutes, population midpoint about 110 |
| Long median | 7-22 minutes |
| Long hard bounds | 3-60 minutes |
| Phase short weight | Equivalent to 1-4 ordinary short chances |
| Long-mode reversal | 2-15% chance of the non-favored AFK/logout choice |
| Idle edge | One stable choice from left, right, top, or bottom |
| Mouse move duration | 300-650 milliseconds in 25 ms steps |
| Typing speed | 35-100 WPM in 5 WPM steps |

The dashboard and MCP status expose a title and plain-language summary generated
only from these numeric values. That text never feeds back into behavior.

While a break is active, a small top-center overlay shows its kind, remaining
time, and a compact × that ends either break type without sending the click to
the game. It disappears completely when the profile is ready.
Long breaks also show a transient **Break** banner above the dashboard's
connection status. Its × button manually ends only the active long break;
micro breaks keep their original timer.

### Manual overrides

The Settings page can override the understandable profile controls per account:
micro chance and duration, long-micro chance, cursor-release chance, long-break
interval and duration, phase boost, preferred AFK/logout style, style-switch
chance, idle edge, mouse move duration, and typing speed from 20-180 WPM.
Derived refractory, hazard scale, summary, and downtime are recomputed from the
custom values. **Use seeded** deletes the override and restores the exact
account-derived profile.

Overrides live beside runtime state as `overrides-<profile-id>.json`. The
profile ID is a one-way derived identifier; the raw account hash is not stored.

## Activity and action contract

Each Lua coroutine owns two separate descriptors. `gc.activity(name)` is the
framework-level global state: a task-agnostic category such as `travel`,
`combat`, or `banking` that drives default behavior policy. `gc.state(name)` is
the script's own state-machine position, such as `fight_black_demon`; it is
shown beside the global state but does not change behavior by itself.
`gc.phase(name, { activity = name })` changes script state and performs the
profile's heavier major-transition evaluation. Every await captures an
immutable activity context, so the standalone script and REPL cannot leak
behavior state into one another.
One interaction can override the descriptor without changing later actions by
putting `activity = "banking"` beside its `action` in the `gc.await` request.

Semantic actions refine broad workflow labels such as `questing`:

| Activity | Breaks | Cursor release |
| --- | --- | --- |
| `general`, `questing`, `travel`, `skilling` | Allowed | Allowed |
| `dialogue`, `combat`, `banking`, `trading` | Suppressed | Suppressed |

`walk.*`, `bank.loadout`, `ge.buy`, dialogue actions, combat actions, NPC
`Talk-to`, and NPC `Attack` select their safe leaf activity automatically.
This means a quest can travel with normal behavior, then bank or fight without
either behavior, without treating the whole quest as one coarse policy.

A composite client interaction evaluates the two independent post-action
decisions allowed by its activity. For walking, one interaction contains any
needed camera turn, one recorded-template cursor movement, and the click that
advances the interaction. Context-menu walking treats its right-click and
menu-selection click as one interaction. Post-action behavior begins only after
both the synthetic click and its matching RuneLite menu event complete.
Every dispatched route interaction runs its own evaluation. A `walk.to` task
containing eight route clicks therefore performs eight eligible micro rolls and
eight eligible cursor-release rolls, not one pair around the whole task.
Low-level mouse path samples do not roll independently.

Automated Lua and MCP actions default to breaks enabled:

```lua
local result = gc.await {
  action = {
    type = "walk.to",
    destination = { x = 3210, y = 3424, plane = 0 },
  },
}
```

A time-sensitive sequence can bypass all discretionary behavior for every
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

## Independent breaks and cursor release

A cursor-release roll occurs after each eligible dispatched composite
interaction. If selected, the recorded matcher moves the synthetic client
cursor to the account's stable off-canvas edge and the action receipt waits only
for that movement. The next action re-enters from a randomized point on the same
edge.

A separate micro roll can pause the same action. It does not move the cursor.
The four outcomes are therefore all valid: neither behavior, release only,
break only, or release followed by a break. Long breaks likewise do not force a
cursor move.

An explicit `mouse.offscreen` action remains available when a script must park
the cursor deterministically at completion.

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
The cursor stays wherever the independent post-action roll left it.
At the deadline GenericClient uses the Jagex Launcher session to return to the
world if the client logged out naturally or deliberately. A completed long
break resets both behavior processes and suppresses the first post-return micro
roll.

The waiting semantic menu action resolves its target again after the restored
client is live. A completed long break gives canvas and widget geometry up to
five seconds to settle, so inventory, equipment, NPC, object, and widget actions
do not dispatch against pre-logout bounds.

Manually ending a long break follows the same completion path: it cancels the
remaining timer, restores the Jagex-backed session when needed, resets long
pressure, suppresses the first micro roll, and only then resumes the waiting
script action.

Manually ending a micro break cancels its remaining timer and resumes the same
waiting action immediately. The in-game overlay exposes the same control for
both break types; the dashboard's larger transient banner remains specific to
long breaks.

## Phase transitions

`gc.phase` marks a major completed state and evaluates a heavier profile-shaped
break roll before the next phase runs:

```lua
gc.phase("banking.complete")
```

The phase uses the coroutine's current activity policy. A protected banking,
trading, dialogue, or combat phase does not roll; scripts can transition and
set the next activity atomically with
`gc.phase("route.start", { activity = "travel" })`.

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
budget, phase cooldowns, break/cursor counts, and an in-progress break deadline. Writes are
atomic. An in-progress break resumes after a plugin restart.

`client_status`, `behavior_profile`, `behavior_status`, and
`gc.read("behavior")` expose structured diagnostics. `session_logout` and
`session_login` expose the same synthetic widget/login controller used by long
breaks for direct diagnostics and orchestration.

## Synthetic input

Every automated mouse movement, click, and keystroke is delivered as AWT
client-canvas events.
The existing recorded template matcher still generates the complete path, but
GenericClient no longer reads or moves the operating-system pointer. Returning
from off-canvas idle emits focus/enter events at the actual randomized edge
crossing; leaving emits exit/focus-loss events. Synthetic events are marked so
manual mouse-profile recording ignores them. Walking rotates the client camera through RuneLite's injected camera yaw
target before recomputing the canvas/minimap projection; it never moves the
operating-system cursor. Synthetic text timing uses the account profile's WPM
with per-key variance and waits for the Jagex input mode before typing.
