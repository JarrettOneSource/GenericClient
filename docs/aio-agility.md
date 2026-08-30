# AIO Agility

Status: Gnome Stronghold course live-proven from level 1 through the exact
level-25 account milestone.

`aio-agility.lua` is a standalone skilling script. Quest Runner may require an
Agility level, but it does not own Agility mechanics.

## Modules

| Module | Responsibility |
| --- | --- |
| `config.lua` | Supported targets, member-first course data, route, gate, and obstacle facts. |
| `travel.lua` | Segmented long-route recovery, the Stronghold gate, and Femi's one-time dialogue. |
| `courses/gnome_stronghold.lua` | Position-derived course state and verified obstacle execution. |
| `progress.lua` | Compact level, gained XP, XP/hour, and next-level ETA overlay. |
| `runner.lua` | Exact target stop, cooperative stop, terminal receipt, and cursor parking. |

The course reducer resumes from the observed player plane and tile. It does not
keep a local obstacle counter as authority.

## Current course

The basic Gnome Stronghold course is non-failable. The live game revision
awarded 111 integer XP for a complete lap:

| State | Object | Action | Landing |
| --- | ---: | --- | --- |
| Log | 23145 | `Walk-across` | `(2474,3429,0)` |
| South net | 23134 | `Climb-over` | `(2473,3423,1)` |
| Branch up | 23559 | `Climb` | `(2473,3420,2)` |
| Rope | 23557 | `Walk-on` | `(2483,3420,2)` |
| Branch down | 23560 | `Climb-down` | `(2487,3420,0)` |
| North net | 23135 | `Climb-over` | `(2487,3428,0)` |
| Pipe | 23139 | `Squeeze-through` | `(2487,3437,0)` |

Each click runs as `skilling`: ordinary breaks and independent cursor release
are eligible. Level-up dialogue is drained without breaks, then the script
waits for the obstacle animation to settle before resolving the next state.

## Live receipt

On 2026-08-30 genericBoss started at 111 Agility XP and first completed target
10 at exactly 1,154 XP. That receipt recorded 68 obstacles, 9 completed laps,
and 1,043 gained XP at an observed rate near 7,000 XP/hour.

The continued target-25 proof ended at 7,888 XP: level 25 with only the XP from
the obstacle that crossed the 7,842 threshold. Its terminal resumed-run receipt
recorded 3,068 XP, 191 obstacles, 28 laps, and about 5,900 XP/hour. Across the
full proof the runner survived a long AFK break with launcher relogin, resumed
mid-course after a Mime interruption, handled every level-up panel, stopped
without starting another obstacle, and parked the synthetic cursor off-screen.
