# Random-event interruption and solver scripts

GenericClient detects and owns random-event handling itself. It has no runtime
dependency on RuneLite's optional Random Events plugin or any external plugin.
The RuneLite implementation is used only as a reference for the current NPC set
and ownership predicate.

## Detection contract

An event is accepted only when all of these are true:

1. the interaction target is the local player;
2. the local player is not already interacting back with the source;
3. the source is an NPC in GenericClient's internal current random-event ID set.

That matches RuneLite's ownership rule and prevents another player's event from
interrupting this client. GenericClient snapshots the NPC ID, name, index,
WorldPoint, and actions immediately. The record stays latched when the NPC
despawns; despawn only changes `present` to `false`.

The detection source is RuneLite's core event bus and API. The reference list is
maintained from RuneLite's
[`RandomEventPlugin`](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/randomevents/RandomEventPlugin.java),
but that plugin does not need to be enabled or installed.

## Interruption lifecycle

On detection GenericClient immediately cancels in-flight walker, menu, combat,
bank, and Grand Exchange input, stops the active standalone Lua coroutine, and
blocks scheduled automation. A busy REPL call is interrupted too. Once that
cleanup barrier finishes, a fresh REPL remains available for event inspection
and manual solving. A manually owned script is retained as a restart descriptor:
script ID plus its validated input values.

The state is available in both places:

```lua
local event = gc.read("random_event")
```

```text
client_status.random_event
```

An unregistered event reports `state = "attention_required"`. After the interrupt
barrier, GenericClient immediately selects `Talk-to` once to hold or advance the
event while a dedicated solver is written. `auto_talk_status` records whether
that click was dispatched; talking never clears the event latch. Registered
solvers own their interactions and do not receive this fallback click.

The MCP monitor prints the event fields and exits with code `3`, so a background
job wakes Codex instead of mistaking the interruption for normal script
completion. The alert is also written to GenericClient's log/chat reporter and
RuneLite's notifier.

`random_event_acknowledge` only records that the alert was seen. It never clears
the latch. After an unknown event has been solved and its outcome observed, call
`random_event_complete` with a short receipt. The default restarts an interrupted
manual script from current observed game state; set `resume_interrupted = false`
when that is not appropriate.

GenericClient never selects `Dismiss` as a framework fallback.

The bundled Mime solver accepts the show, records the Mime's last recognized
animation until the response panel appears, and clicks the matching performance.
It repeats until the Mime despawns and the emote-unlock message is observed. The
answer must be selected from the last animation before the panel opens; reacting
to the first animation after the previous click can select a stale performance.
The first live receipt completed five correctly observed rounds, unlocked the
Lean emote, and returned the player to `(2487, 3420, 0)` on the interrupted
Gnome Stronghold course before the runner resumed.

## Registering a standalone solver

A solver is an ordinary standalone Lua script. Add the NPC IDs it owns to the
optional `random_events` manifest field:

```json
{
  "id": "miles-solver",
  "name": "Miles Solver",
  "description": "Solve Miles from observed dialogue and item state.",
  "file": "miles-solver.lua",
  "random_events": [5437, 5440]
}
```

`script_save` accepts the same `random_events` array. Registry validation rejects
unsupported IDs, duplicate IDs in one entry, and two scripts claiming the same
event NPC.

Keep the solver explicit and state-driven:

```lua
return {
  run = function()
    local event = gc.read("random_event")
    if not event.active then
      error("Miles solver started without a pending random event")
    end

    local talked = gc.await {
      action = {
        type = "npc.interact",
        id = event.npc_id,
        action = "Talk-to",
        within = 12,
      },
      breaks = false,
    }
    if talked.status ~= "dispatched" then
      error("Miles interaction failed: " .. tostring(talked.result))
    end

    -- Continue from observed dialogue/widgets and return only after a concrete
    -- completion receipt, such as the reward message or event NPC despawn.
    return { status = "solved", npc_id = event.npc_id }
  end,
}
```

When a registered solver starts, the normal script and schedule remain blocked.
It starts only after old Lua/input cancellation and active-break cleanup have
finished. Only the solver's `COMPLETED` terminal state releases the event. A `FAULTED` or
stopped solver changes the latch to `attention_required`; it does not resume the
interrupted script. Once released, a manual script restarts with its prior inputs
when requested, while the schedule engine simply evaluates its rules again.

This intentionally does not provide a blanket solver. Add one standalone script
per event family as real event behavior is observed, keeping any reusable
dialogue/widget mechanics in normal GenericClient framework APIs.

## Capt' Arnav evidence

On 2026-08-29 GenericClient live-detected Capt' Arnav NPC `5426`, interrupted
normal execution, latched his ID/name/index/location/actions, posted the client
alert, and blocked Quest Runner until the event was resolved. The live puzzle
labels were `RING`, `COINS`, and `BAR`, with dial varbits `2`, `2`, and `0`.
The solver aligned the three dials, confirmed the chest, observed the reward
message `Your reward is: 1 x Gold bar.`, completed the event with reason
`capt_arnav_reward_observed`, and resumed the interrupted Quest Runner.

The bundled `capt-arnav` solver is registered for NPC `5426`. It handles the
exact affirmative dialogue `Yes, I'll help you unlock your chest.`, reads the
three copied dial varbits (`9585`, `9593`, `9594`) and immutable widget labels,
rotates each four-state dial with `ui.click`, and requires the reward chat
receipt before returning `COMPLETED`.

## Pinball evidence

On 2026-08-29 GenericClient live-detected Flippa NPC `6744`, interrupted AIO
Magic, and entered the Pinball arena through the exact affirmative dialogue
`Yes, pinball is fun.`. The client cache defines current post, next post, score,
and completion as varbits `2119` through `2122` on varp `727`. Live play proved
the current-post mapping: tree `0`, iron `1`, coal `2`, fishing `3`, and essence
`4`. GenericClient tagged ten correct posts, observed score `10` and completion
`1`, exited through object `9293`, received `2 x Diamond`, and resumed AIO Magic.

The bundled `pinball` solver is registered for NPC `6744`. It uses those copied
varbits and observed scene objects, so it is independent of camera angle,
screen coordinates, and instance placement. Event dialogue, post tags, and the
exit all bypass breaks as one time-sensitive interruption; the solver returns
only after the reward message is present.

## Count Check evidence

On 2026-08-29 GenericClient live-detected Count Check NPC `12551` while Quest
Runner was crossing the Tree Gnome Village maze. It interrupted and retained
the quest, completed the event's automatic account check, observed the exact
`You do not have a Bank PIN, so you fail my checks!` outcome, drained the final
Continue page, and resumed from the same maze state. No reward exists for that
outcome, and GenericClient did not attempt to create an account-security PIN.

The bundled `count-check` solver owns surface NPC `12551` and underwater NPC
`12552`. It accepts the account-check choice when one is shown, handles the
automatic random-event dialogue, and treats either the pass or fail message
plus NPC departure as the completion receipt. A successful security check can
leave its lamp in inventory for the account planner; a failed check is still a
fully processed event.
