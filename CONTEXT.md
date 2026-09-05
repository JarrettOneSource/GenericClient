# GenericClient

GenericClient provides semantic game actions and observed state. Standalone scripts choose quest, training and recovery goals.

## Language

**Semantic action**: One client operation with an observed completion condition, such as selecting a prayer or loading bank supplies.

**Activity**: The kind of work a script declares, such as travel or combat. Its preset supplies discretionary behavior and safety expectations.

**Behavior policy**: Independent choices for breaks, cursor release, mouse speed, expected damage, prayer ownership, walk refresh and fidgets. An await can override these choices for that action without changing the declared activity.

**Damage grace**: A bounded period after ordinary hit evidence or unexplained HP loss. The policy resolver suppresses discretionary behavior during that period only when the declared policy does not expect damage. A supported poison/venom match does not start or refresh it.

**Intent**: A coroutine-owned sequence of semantic actions with one outer behavior boundary. Nested intents share that boundary; emergency recovery remains independent of the scope.

**Rest scope**: Discretionary cursor movement tied to the lifetime of an await, without replacing the await's active input child. Finishing or canceling the await closes its rest scopes.

**Journey**: Travel from the observed player position to an arrival condition, including required corridor points and interrupt conditions.

**Via point**: An ordered corridor requirement passed when the observed player comes within its handoff radius. Planning a path through it does not establish that it was passed.

**Arrival tiles**: An optional allowed set inside the destination radius. Standing elsewhere inside that radius does not satisfy the journey.

**Edge memory**: Account-scoped observations of solid edges and door outcomes. Each observation has a reason and expiry. Failed door entries also clear when the captured quest stage changes. Cleared outcomes never override live collision.

**Object footprint**: The tiles occupied by a captured scene object. Game objects can occupy several tiles around their reported centre; a door interaction keeps that centre as its input target while matching the blocked crossing against its footprint.

**Transport**: A directed journey edge performed by an interaction. Its standing origin, destination and cost participate in planning; the selected action stays attached to the route so clicks and local rejoins cannot skip it.

**Transport conversation**: Dialogue owned by an already selected service. Only the expected speaker or permitted choice can advance it, and a captured page is checked again before input. Foreign dialogue returns to the quest handler; safety interrupts keep their priority.

**Continuation**: Permission to resume an interrupted journey with its observed corridor progress and original destination constraints. An attempted transport retains its action progress, remaining active-tick budget and failed service groups.

**Owned active time**: Logged-in time during which a standalone script owns automation. Idle and operator-only time do not accrue discretionary break pressure.

**Micro pressure**: Owned active time weighted by the account's micro rate and the standalone script's activity. A single persisted threshold becomes eligible at a completed action or phase. Starting a micro break consumes the pressure; phase bonuses add pressure without resampling the threshold.

**Deferred long break**: A due long break ended early without satisfying its rest budget. The remaining obligation survives the interruption.

**Capture**: A quest-driven relocation into a prison area. Its recovery decisions belong to the quest script.

**Emergency recovery**: Forced healing or an approved escape when observed account state crosses the configured emergency threshold.
