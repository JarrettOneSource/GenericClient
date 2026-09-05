# GenericClient automation

GenericClient provides semantic game actions and observed state. Standalone
scripts choose quest, training, and recovery goals.

## Language

**Run:** One execution of a selected automation, from start through cleanup.

**Owner:** The manual operator, schedule rule, or random event that currently
holds the right to execute automation.

**Snapshot:** The account and scene state observed at a game tick.

**Semantic action:** One client operation with an observed completion condition,
such as selecting a prayer or loading bank supplies.

**Intent:** A named group of operations sharing one outer discretionary behavior
boundary. Nested operations retain their safety policy and the run's revocable
input ownership.

**Receipt:** The observed result of a requested input operation. A dispatched
click and a completed game-state change are distinct results.

**Activity:** The kind of work a script declares, such as travel or combat. Its
preset supplies discretionary behavior and safety expectations.

**Journey:** Travel from the observed position to an arrival condition, including
required corridor points and interrupt conditions.
_Avoid_: route leg, when referring to the entire requested trip.

**Via point:** An ordered corridor requirement passed when the observed player
comes within its handoff radius. Planning through it does not establish passage.

**Arrival tiles:** An optional allowed set inside the destination radius.
Standing elsewhere inside that radius does not satisfy the journey.

**Continuation:** Permission to resume an interrupted journey with its observed
corridor progress and original destination constraints. An attempted transport
retains action progress, its remaining active-tick budget and failed service groups.

**Object footprint:** The tiles occupied by a captured scene object. A door
interaction targets the object's reported centre while matching the blocked
crossing against its full footprint.

**Manual takeover:** A temporary pause caused by physical mouse activity. It is
independent of emergency recovery and unconditional stopping.

**Event latch:** The unresolved random event that holds automation ownership
even after its initiating NPC disappears.

**Owned active time:** Logged-in time during which a standalone script owns
automation. Idle and operator-only time do not accrue discretionary break pressure.

**Deferred long break:** A due long break ended early without satisfying its
rest budget. The remaining obligation survives the interruption.

**Capture:** A quest-driven relocation into a prison area. Its recovery decisions
belong to the quest script.

**Emergency recovery:** Forced healing or an approved escape when observed account
state crosses the configured emergency threshold.

**Behavior policy:** Independent choices for breaks, cursor release, mouse speed,
expected damage, prayer ownership, walk refresh and fidgets. Each operation can
override these choices without changing the declared activity.

**Damage grace:** A bounded period after ordinary hit evidence or unexplained HP
loss. Unexpected damage suppresses discretionary behavior during that period. A
supported poison or venom match does not start or refresh it.

**Rest scope:** Discretionary cursor movement owned by an operation or sleep,
without replacing its active input child. Finishing or canceling the owner closes
its rest scopes.

**Entity reference:** A handle to one native actor or object lifetime. Reusing an
NPC or player index does not reuse that identity. State reads refresh the handle;
menu input must still resolve its exact target.

**Edge memory:** Account-scoped observations of solid edges and door outcomes,
with a reason and expiry. Failed door entries also clear when the quest stage
changes. Cleared outcomes never override live collision.

**Transport:** A directed journey edge performed by an interaction. Its standing
origin, destination and cost participate in planning; the selected action stays
attached to the route so clicks and local rejoins cannot skip it.

**Micro pressure:** Owned active time weighted by the account's micro rate and
the script's declared activity. One persisted threshold becomes eligible at
a completed action or phase. Starting a micro break consumes the pressure; phase
bonuses add pressure without resampling the threshold.

**Transport conversation:** Dialogue owned by an already selected service. Only
the expected speaker or permitted choice can advance it; input rechecks the
captured page. Foreign dialogue returns to the quest handler, and safety
interrupts retain priority.
