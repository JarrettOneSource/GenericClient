# Witch's House Quest Runner

Status: implemented and completed live on genericBoss. The Quest API reports
`finished`, varp 226 is 7, and the reward raised Hitpoints to level 25 at 8,184
XP. Source modules live under `quest-runner/witchs_house/`.

## Decision

Build Witch's House as a standalone, restartable Lua quest runner over reusable
GenericClient actions. The runner must recompute its phase from the normalized
quest state, raw quest varp, current zone, inventory, equipment, live NPCs, and
cached bank contents after every interaction. It must never advance from an
in-memory click counter.

The account was prepared without raising Defence: Magic reached 16 and
Hitpoints reached 12 before the experiment. The live run used Fire Strike,
recoils, wine, exact-ID targeting, two verified safespots, and an immediate
Games necklace exit after recovering the ball. Completing the quest added
6,325 Hitpoints XP and cleared the 15-HP Waterfall preflight.

## Live acceptance record

- Terminal state: normalized quest `finished`, varp 226 value 7.
- Reward audit: Hitpoints 25 / 8,184 XP; Magic 16 / 3,080 XP.
- North safespot: player `(2936,3465,0)`, size-one form
  `(2937,3466,0)`, stable for 16 ticks without damage.
- South safespot: player `(2936,3459,0)`; bear and wolf remained out of melee
  reach.
- Food guard: wine dispatched at 3 HP, combat continued, and the escape did not
  fire.
- Completion: all four exact NPC IDs transitioned, ball 2407 was recovered,
  retained through the Burthorpe teleport, and returned to Boy 3994.

The reference state machine is Zoinkwiz Quest Helper at exact commit
[`c264be77fddb68ab3dfc553f9f113f6ffc60fb71`](https://github.com/Zoinkwiz/quest-helper/tree/c264be77fddb68ab3dfc553f9f113f6ffc60fb71),
dated 2026-08-25. Its implementation supplies the zones, targets, checkpoint,
condition precedence, and recovery branches
([zones and requirements](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/helpers/quests/witchshouse/WitchsHouse.java#L126-L170),
[interactions](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/helpers/quests/witchshouse/WitchsHouse.java#L172-L213),
[stage dispatch](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/helpers/quests/witchshouse/WitchsHouse.java#L215-L266)).

Numeric IDs below were verified against the exact RuneLite 1.12.37
`runelite-api` JAR. The inspected artifact has SHA-256
`e4851cb2c48e211b66e69124b61c1742e0d8345368c1946560de8db2584413c9`
([artifact](https://repo.runelite.net/net/runelite/runelite-api/1.12.37/runelite-api-1.12.37.jar),
[gameval source](https://github.com/runelite/runelite/tree/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/gameval)).

The gameplay cross-check uses exact OSRS Wiki revisions current when retrieved
on 2026-08-27: [guide](https://oldschool.runescape.wiki/w/Witch%27s_House?oldid=15168391),
[quick guide](https://oldschool.runescape.wiki/w/Witch%27s_House/Quick_guide?oldid=15291737),
[transcript](https://oldschool.runescape.wiki/w/Transcript:Witch%27s_House?oldid=15320534),
[raw varp](https://oldschool.runescape.wiki/w/RuneScape:Varplayer/226?oldid=14448641),
and [experiment stats](https://oldschool.runescape.wiki/w/Witch%27s_experiment?oldid=15206938).

## Authoritative state

### Quest progression

The raw quest stage is server varp `226`, RuneLite gameval
`VarPlayerID.BALLQUEST` and Quest Helper `QUEST_WITCHS_HOUSE`. Quest Helper
registers Witch's House as a varp-backed quest
([registration](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/questinfo/QuestVarPlayer.java#L87-L92),
[quest mapping](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/questinfo/QuestHelperQuest.java#L456-L459)).
The Wiki's raw-varplayer record gives the exact meanings:

| Varp 226 | Meaning | Resume consequence |
| ---: | --- | --- |
| 0 | Not started. | Talk to the Boy. |
| 1 | Quest accepted; also the reset value after being caught before the durable diary checkpoint. | Recover/re-enter the house and rebuild the magnet sequence from observed items and zone. |
| 2 | Magnet obtained from the basement cupboard. | Return upstairs and unlock the garden. |
| 3 | Magnet attached to the mouse; garden door unlocked. | Pick up and open the diary immediately; do not enter the exposed garden first. |
| 5 | Diary opened after the garden was unlocked. | Durable checkpoint intended to prevent the garden-door progress from resetting when caught. |
| 6 | All four experiment forms defeated. | Recover the ball; never repeat combat merely because the ball is absent. |
| 7 | Ball returned to the Boy; quest complete. | Terminal. |

There is no verified Witch's House-specific varbit in RuneLite 1.12.37 or in
the reference helper. Do not invent one. Use normalized
`Quest.WITCHS_HOUSE.getState(client)` as the terminal authority and varp 226 for
phase selection. A raw value outside the table is a diagnostic stop.

The checkpoint has an ordering requirement: obtaining or reading the diary
before the door is unlocked is not enough. Varp 5 is set when the diary is
opened after varp 3. The runner must wait for `226 == 5` before entering the
garden. This also resolves the apparent guide/helper difference: the guide says
to read the diary on entry, while the helper deliberately locks a second read
behind the post-unlock checkpoint.

### Quest items and supplies

| Item | Gameval | ID | Contract |
| --- | --- | ---: | --- |
| Cheese | `CHEESE` | 1985 | Use on mouse hole; consumed. Carry two for one bounded retry. |
| Leather gloves | `LEATHER_GLOVES` | 1059 | Wear before gate; not consumed. Buy one just in time rather than relying on the basement boxes' estimated 1/26 drop. |
| Ball | `BALL` | 2407 | Ground item in shed; take only after varp 6. |
| Witch's diary | `WITCHES_DIARY` | 2408 | Ground item at `(2903,3471,0)`; `Take`, then `Read` after varp 3 until varp 5. |
| Door key | `WITCHES_DOORKEY` | 2409 | Obtained under plant; opens front door. |
| Magnet | `MAGNET` | 2410 | Obtained from cupboard; use on the spawned mouse. |
| Shed key | `WITCHES_SHEDKEY` | 2411 | Obtained from fountain; use on shed door. |
| Air rune | `AIRRUNE` | 556 | Fire Strike supply; two per cast with a fire staff. |
| Mind rune | `MINDRUNE` | 558 | Fire Strike supply; one per cast. |
| Staff of fire | `STAFF_OF_FIRE` | 1387 | Supplies the fire-rune component and adds Magic accuracy. |
| Ring of recoil | `RING_OF_RECOIL` | 2550 | Safety reserve; the Wiki says at most four can cover the forms' combined 144 HP. |
| Games necklace (8) | `NECKLACE_OF_MINIGAMES_8` | 3853 | Preferred immediate shed exit to Burthorpe after taking the ball. Any tested non-Wilderness one-click/selection teleport may satisfy the same contract. |

The quest-specific minimum is cheese, suitable gloves, combat gear, and food.
The guide recommends four free inventory slots; Quest Helper only checks for
two. Use the more conservative four-slot requirement before starting. For the
strict low-level route, withdraw exactly one fire staff, 150 mind runes, 300 air
runes, four recoils, one charged exit teleport, two cheese, one pair of leather
gloves, and fill remaining usable slots with approved food. Return unused
consumables to the bank afterward. Rune quantities are GenericClient safety
policy, not a guide claim.

### NPCs, objects, and actions

Never select by menu index. Resolve a live entity by ID and optional
WorldPoint, validate its name and exact current action, then dispatch through
the synthetic client-only cursor.

| Kind | Entity | Gameval | ID | Anchor / intended interaction |
| --- | --- | --- | ---: | --- |
| NPC | Boy | `BALLBOY` | 3994 | `(2928,3456,0)`, `Talk-to` |
| NPC | Nora T. Hagg | `NORA_T_HAGG` | 3995 | Patrol only; action is `Attack`, which must be unrepresentable in this script |
| NPC | Experiment: skavid | `SHAPESHIFTERGLOB` | 3996 | `(2935,3463,0)`, `Attack` |
| NPC | Experiment: spider | `SHAPESHIFTERSPIDER` | 3997 | Live transformed NPC, `Attack` |
| NPC | Experiment: bear | `SHAPESHIFTERBEAR` | 3998 | Live transformed NPC, `Attack` |
| NPC | Experiment: wolf | `SHAPESHIFTERWOLF` | 3999 | Live transformed NPC, `Attack` |
| NPC | Mouse | `WITCHRAT` | 4000 | Around `(2902,3467,0)`; item-on-NPC only |
| Object | Front door | `WITCHHOUSEDOOR` | 2861 | `(2900,3473,0)`, `Open` with door key present |
| Object | Garden door | `WITCHBACKDOOR` | 2862 | Live `Open` only after varp 3/5 |
| Object | Shed door | `WITCHSHEDDOOR` | 2863 | `(2934,3463,0)`, shed-key-on-object, then `Open` |
| Object | Fountain | `WITCHFOUNTAIN` | 2864 | `(2910,3471,0)`, exact live action `Check` |
| Object | Electrified gate, right | `SHOCKGATER` | 2866 | `(2902,9873,0)`, `Open` while gloves are equipped |
| Object | Potted plant | `WITCHPOT` | 2867 | `(2900,3474,0)`, `Look-under` |
| Object | Cupboard, closed | `MAGNETCBSHUT` | 2868 | `(2898,9873,0)`, `Open` |
| Object | Cupboard, open | `MAGNETCBOPEN` | 2869 | `(2898,9873,0)`, `Search` |
| Object | Mouse hole | `WITCHMOUSEHOLE` | 2870 | `(2903,3466,0)`, cheese-on-object |
| Object | Upstairs return stairs | `GRIM_WITCH_HOUSE_SPOOKYSTAIRSTOP` | 24673 | `(2907,3471,1)`, descend |
| Object | Basement ladder up | `GRIM_WITCH_LADDER_UP` | 24717 | `(2907,9876,0)`, `Climb-up` |
| Object | Basement ladder down | `GRIM_WITCH_LADDER_DOWN` | 24718 | `(2907,3476,0)`, `Climb-down` |
| Object | Basement boxes | `GRIM_BASEMENT_CRATE_MANY` | 24692 | Optional `Search` fallback for gloves; never the default JIT plan |

The action names for the plant, gate, cupboard, fountain, diary, and ball are
cross-checked against their current first-party-data transcriptions on the Wiki
([plant](https://oldschool.runescape.wiki/w/Potted_plant_(witch%27s_house)?oldid=14857714),
[gate](https://oldschool.runescape.wiki/w/Gate_(witch%27s_house)?oldid=15159899),
[cupboard](https://oldschool.runescape.wiki/w/Cupboard_(witch%27s_house)?oldid=14857728),
[fountain](https://oldschool.runescape.wiki/w/Fountain_(witch%27s_house)?oldid=15013387),
[diary](https://oldschool.runescape.wiki/w/Diary_(Witch%27s_House)?oldid=15282383),
[ball](https://oldschool.runescape.wiki/w/Ball?oldid=15183509)).

## World geometry

Quest Helper uses inclusive rectangular zones:

| Zone | South-west | North-east |
| --- | --- | --- |
| House, ground floor | `(2901,3466,0)` | `(2907,3476,0)` |
| House, upper floor | `(2900,3466,1)` | `(2907,3476,1)` |
| Basement west of gate | `(2897,9870,0)` | `(2902,9878,0)` |
| Basement east of gate | `(2903,9870,0)` | `(2909,9878,0)` |
| Garden south/main strip | `(2900,3459,0)` | `(2933,3465,0)` |
| Garden north strip | `(2908,3466,0)` | `(2933,3467,0)` |
| Garden fountain pocket | `(2908,3467,0)` | `(2912,3475,0)` |
| Shed | `(2934,3459,0)` | `(2937,3467,0)` |

The last-two-forms safespot recorded by Quest Helper is
`(2936,3459,0)`, at the south of the shed. The current guide further states
that the bear and wolf can be attacked from the south wall between the sacks
and ball crate.

Quest Helper does not encode the first-two-forms lure. Its exact three tiles
were recovered from the published marker export and frame sequence in the
[Steeleygames HCIM guide](https://www.youtube.com/watch?v=54ltDNUdAJc): start
at `(2937,3466,0)`, attack and wait for the size-one form to reach
`(2937,3465,0)`, step onto that occupied tile, wait until the form is displaced
north into `(2937,3466,0)`, then step west to the safespot at
`(2936,3465,0)`. The blocked northwest furniture corner traps the form
diagonally. Every NPC and player tile is a postcondition; a prose-only
"north-east corner" is not sufficient. The bear and wolf are size 2, while the
first two forms are size 1.

The garden uses an explicit cover route because its grass restriction and
witch detection are not represented by the static collision map. Live trials
at varp 5 recorded the successful chain:

`(2901,3460) -> (2908,3460) -> (2916,3460) -> (2924,3460) ->
(2931,3460) -> (2933,3466) -> (2927,3466) -> (2920,3466) ->
(2913,3466) -> (2912,3466) -> (2911,3467) -> (2911,3468)`.

The south and north lanes are broken into hedge-covered pockets. Each exposed
gap waits for a recorded patrol window: Nora absent to the east at the west
entry, moving west before the south/east transitions, absent to the west at
the first north transition, and moving east at `x >= 2928` before the final
westward crossing. A live failed trial at player `(2919,3460)` with Nora at
`(2916,3463)` proved that line of sight plus her interaction target precedes
the teleport. The successful trial reached the fountain pocket, checked object
2864, received item 2411, and retained varp 5. The runner stops at the key
checkpoint before combat preparation.

## Experiment combat contract

All four forms are consecutive melee-only enemies:

| Form | ID | Combat | Size | HP | Max hit | Style | Safest route |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| Skavid | 3996 | 19 | 1 | 21 | 2 | Crush | Three-tile northeast displacement lure, then Magic safespot |
| Spider | 3997 | 30 | 1 | 31 | 3 | Crush | Repeat the three-tile northeast lure, then Magic safespot |
| Bear | 3998 | 42 | 2 | 41 | 4 | Slash | South tile `(2936,3459,0)`, Magic safespot |
| Wolf | 3999 | 53 | 2 | 51 | 5 | Stab | Remain at south safespot, Magic |

They have Magic level 1 and zero Magic-defence bonus. Fire Strike at Magic 13
is therefore the smallest sensible ranged attack for this account. The runner
must attack exactly the expected current form, confirm that the player is no
longer receiving melee hits from the safespot, and continue until the next
expected NPC ID appears. A form transition is the postcondition; an animation
or one absent NPC tick is not.

The north lure was live-proven on genericBoss at 12 Hitpoints. The correct push
left form 3996 on `(2937,3466,0)` and the player on `(2936,3465,0)` for 16
consecutive game ticks without damage. A separate trace reproduced the guide's
wrong-push branch, with the form landing on `(2936,3465,0)`; that branch must
return to the start tile, heal, and retry instead of treating the green tile as
a valid NPC destination.

Combat rules:

- Enter at full current HP and at least 12 maximum HP, with run enabled, Fire
  Strike selected/available, one recoil equipped, three
  replacements, the tested teleport, and six wines (66 total healing).
- Initiate combat by attacking the experiment, not by taking the ball; the
  transcript says premature ball attempts lower combat stats.
- Disable all micro and long breaks from shed entry through teleport exit.
- The framework eats an approved food as soon as its full heal fits and keeps
  combat running. Below 30% max HP it forces a heal even if a low-max-HP account
  must overheal; for 12 HP that means wine at 3 HP. It stops and escapes only if
  no approved wine can be used. At the bear/wolf stage, any received melee hit
  means the safespot failed and positioning must be recovered rather than
  tanking forward.
- Detect a recoil shatter and equip exactly one replacement. Never keep
  clicking a missing ring slot or assume recoil damage killed a form.
- Never leave the shed during forms 1-4. Leaving resets the entire four-form
  fight. Disconnect, death, missing local player, an unexpected NPC ID, an
  exhausted spell supply, or inability to establish the safespot is a hard
  stop.
- The dwarf multicannon is not supported in the shed. No fallback may attempt
  to place one.

The 12-HP, 60-healing, spell-supply, and break-suppression thresholds
are GenericClient policy. The form statistics, 144 combined HP, four-recoil
maximum, consecutive-fight reset, no-cannon restriction, and safespot methods
come from the current guide and experiment data linked above.

## Implemented phase table

Every action waits for a game-state postcondition and then recomputes the full
phase. Inventory checks include the bank where noted.

| Phase | Entry condition | Action | Verified postcondition / recovery |
| --- | --- | --- | --- |
| `complete` | Quest API says `FINISHED` or varp 226 is 7 | Stop; record the 6,325 HP XP and four-QP receipt. | Terminal. |
| `preflight` | Varp 0; strict loadout/stats absent | Train only the approved Magic/HP preparation or bank to the exact JIT loadout. | Magic >=13, max/current HP >=12/full, run >=80%, inventory/equipment contract satisfied. No quest interaction before this passes. |
| `accept` | Varp 0 and preflight passes | `Talk-to` Boy; select exact visible choices `What's the matter?`, `Ok, I'll see what I can do.`, and `Yes.` while tolerating the low-combat warning. | Varp becomes 1 / normalized state becomes in progress. |
| `obtain_house_key` | Varp 1-2/5-6; outside house; no key in inventory/bank | `Look-under` plant 2867. | Inventory contains 2409. |
| `enter_house` | Key exists; outside house/garden/shed | Open front door 2861. | Player enters house zone. |
| `descend_basement` | Varp 1; no magnet; in house | Climb down ladder 24718. | East-basement zone. |
| `equip_gloves` | East basement; gloves carried but not worn | Inventory `Wear`. | Equipment contains 1059. |
| `open_gate` | East basement; gloves equipped | Open gate 2866. | West-basement zone. If HP falls, the glove postcondition was false: stop and heal, never retry blindly. |
| `open_cupboard` | West basement; magnet absent; object 2868 visible | `Open`. | Object 2869 appears. |
| `obtain_magnet` | West basement; magnet absent; object 2869 visible | `Search`. | Inventory contains 2410 and/or varp becomes 2. |
| `return_upstairs` | Magnet exists; in basement | Climb up 24717. | House zone. |
| `lure_mouse` | Varp 2; house zone; cheese and magnet present | In one break-free critical section: cheese-on-hole 2870, wait for mouse 4000, then magnet-on-NPC. | Varp becomes 3. On mouse timeout, confirm one cheese consumed; permit one retry with the second cheese, then stop. |
| `diary_checkpoint` | Varp 3 | Take diary 2408 at `(2903,3471,0)` if absent, `Read`, traverse/close its interface as required. | Varp becomes exactly 5. Never enter the garden on item possession alone. |
| `garden_to_fountain` | Varp 5; shed key absent; outside shed | Execute only validated cover-to-cover hints, rechecking witch 3995 before each segment; `Check` fountain 2864. | Inventory contains 2411. Caught/teleported: recompute varp and items; never assume the checkpoint survived. |
| `enter_shed` | Varp 5; shed key present; strict combat gate passes | Shed-key-on-door 2863, then open/enter. | Shed zone and expected form 3996 visible, or perform one ball `Take` attempt only if no form spawned. If another player is inside, wait outside without spamming. |
| `fight_skavid` | Shed; NPC 3996 | Establish the recorded start/walk/safe displacement lure, then attack with Fire Strike. | NPC 3997 appears. |
| `fight_spider` | Shed; NPC 3997 | Re-establish the same exact three-tile displacement lure, then attack. | NPC 3998 appears. |
| `fight_bear` | Shed; NPC 3998 | Move to south safespot `(2936,3459,0)`, verify no melee reach, attack. | NPC 3999 appears. |
| `fight_wolf` | Shed; NPC 3999 | Remain at south safespot and attack. | Varp becomes 6 and no experiment form remains. |
| `take_ball` | Varp 6; in shed; ball absent | Take ground item 2407 at `(2935,3460,0)`. | Inventory contains 2407. If absent after a loss, the current quick guide says to hop worlds; expose a manual diagnostic instead of auto-hopping in the first slice. |
| `teleport_with_ball` | Ball carried; in house/garden/shed | Use the pretested non-Wilderness exit teleport immediately. | Player leaves all quest zones and ball remains in inventory. Never walk past Nora by default. |
| `return_ball` | Ball carried; outside hostile quest zones | Walk to Boy 3994 and `Talk-to`; continue dialogue. | Quest API says `FINISHED` and varp is 7. |

## Safety and resumption

- Always restore current HP and run energy outside the exposed garden/shed. Do
  not begin a cover segment, lure, or form transition while a break is active.
- Disable behavior breaks for cheese-to-mouse timing, every exposed garden
  segment, all four experiment forms, ball pickup, and teleport exit. Preserve
  cooperative cancellation and vitals monitoring.
- Varp 1 after an unexpected displacement means the durable checkpoint did not
  hold; rebuild from current inventory/zone. Varp 5 means do not waste another
  cheese or magnet merely because the client restarted.
- Quest Helper recorded on 2025-09-20 that the shed door did not reliably remain
  unlocked. If entry fails at varp 5 or 6, recover another shed key from the
  fountain under the same stealth policy; do not spam the door.
- At varp 6, never attack a respawned experiment. Re-enter to take the ball. If
  caught while carrying the ball, expect to lose it, recompute state, recover
  it from the shed, and teleport out.
- Keep at least one free slot for the door key, magnet, diary, shed key, and
  ball as each becomes relevant. Drop only explicitly disposable quest
  duplicates; never drop user valuables to make room.
- An unrecognized varp, unexpected hostile NPC, witch missing from the live
  scene during an exposed segment, menu action mismatch, failed item delta,
  stat drain, or timeout is a diagnostic stop. No phase substitutes repeated
  clicks.

## Reusable GenericClient capabilities

The capabilities shared directly with the Waterfall runner should be built
once in the plugin core:

1. `quest.state`: normalized quest state plus script-selected raw varps and
   varbits, read on the client thread.
2. `scene.objects`, `scene.npcs`, and `scene.ground_items`: enumerate all
   matching live entities with ID, name, WorldPoint, footprint, orientation,
   actions, and canvas/minimap visibility.
3. `object.interact`, `npc.interact`, `ground_item.take`, `item.interact`,
   `item.use_on_object`, and `item.use_on_npc`: semantic action selection through
   the synthetic cursor, never menu indexes.
4. `dialogue`: inspect Continue/choice widgets and choose exact visible text
   while tolerating warning pages and variable page counts.
5. `bank.loadout`: deposit/withdraw exact quantities, equip/unequip, honor the
   five-million-coin reserve, and verify the result before closing.
6. `player.vitals`: current/max HP, run energy, run state, animation,
   interacting actor, movement destination, and incoming hitsplats.
7. `food.eat`: select approved food and verify item consumption plus HP change.
8. `combat.cast`: select the configured spell, target an exact NPC ID/footprint,
   and verify attack/transition state without owning quest phase logic.
9. `critical_section`: suppress all behavior breaks for a bounded operation
   while retaining cancellation and safety monitors.
10. `wait.until`: explicit game-tick postconditions with bounded timeout and a
    structured receipt; wall-clock sleeps are never proof of success.

Witch's House adds only two primitives beyond Waterfall's existing minimum:
item-on-NPC and exact-NPC combat. The quest-specific Lua script owns varp 226,
all IDs and zones, dialogue strings, garden route hints, relative safespot
logic, form order, thresholds, loadout, and recovery precedence.

## Acceptance result

The reducer, semantic interaction surfaces, JIT loadout, garden controller,
combat controller, completion flow, and installed-script migration have
automated coverage. The complete live receipt is recorded above. A restart at
varp 5 or 6 remains state-derived: it does not use a local form counter, and a
completed account terminates without repeating any quest interaction.
