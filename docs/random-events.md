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

An unregistered event reports `state = "attention_required"`. The MCP monitor
prints the event fields and exits with code `3`, so a background job wakes Codex
instead of mistaking the interruption for normal script completion. The alert is
also written to GenericClient's log/chat reporter and RuneLite's notifier.

`random_event_acknowledge` only records that the alert was seen. It never clears
the latch. After an unknown event has been solved and its outcome observed, call
`random_event_complete` with a short receipt. The default restarts an interrupted
manual script from current observed game state; set `resume_interrupted = false`
when that is not appropriate.

GenericClient never selects `Dismiss` as a framework fallback.

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
