# Waterfall Quest Runner

Status: implemented and live-proven end to end under
`quest-runner/waterfall/`, with separate preparation, navigation, tomb, and
ritual modules. The final receipt reported normalized quest state `FINISHED`,
raw varp 10, and the exact quest rewards on genericBoss.

## Decision

Build Waterfall Quest as data-driven phases over a small set of reusable
semantic actions. Do not translate the guide into a long sequence of blind
clicks. The runner must choose its next phase from server quest state, current
zone, inventory, equipment, and cached bank contents every time it starts or
resumes.

The reference state machine is Zoinkwiz Quest Helper at exact commit
[`c264be77fddb68ab3dfc553f9f113f6ffc60fb71`](https://github.com/Zoinkwiz/quest-helper/tree/c264be77fddb68ab3dfc553f9f113f6ffc60fb71),
dated 2026-08-25. Its Waterfall implementation supplies the stage grouping,
condition order, zones, targets, and inventory gates
([state and zones](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/helpers/quests/waterfallquest/WaterfallQuest.java#L144-L200),
[interactions](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/helpers/quests/waterfallquest/WaterfallQuest.java#L202-L294),
[stage dispatch](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/helpers/quests/waterfallquest/WaterfallQuest.java#L296-L355)).

Numeric IDs below were verified against the exact RuneLite 1.12.37
`runelite-api` artifact. The inspected JAR has SHA-256
`e4851cb2c48e211b66e69124b61c1742e0d8345368c1946560de8db2584413c9`
([artifact](https://repo.runelite.net/net/runelite/runelite-api/1.12.37/runelite-api-1.12.37.jar),
[gameval source](https://github.com/runelite/runelite/tree/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/gameval)).

The gameplay cross-check uses exact current OSRS Wiki revisions retrieved on
2026-08-27: [Waterfall Quest](https://oldschool.runescape.wiki/w/Waterfall_Quest?oldid=15297455),
[quick guide](https://oldschool.runescape.wiki/w/Waterfall_Quest/Quick_guide?oldid=15126652),
[transcript](https://oldschool.runescape.wiki/w/Transcript:Waterfall_Quest?oldid=15294265),
[Glarial's Tomb](https://oldschool.runescape.wiki/w/Glarial%27s_Tomb?oldid=15318344),
and [Waterfall Dungeon](https://oldschool.runescape.wiki/w/Waterfall_Dungeon?oldid=15263096).

## Authoritative state

### Quest state

The raw quest stage is server varp `65`, named
`VarPlayerID.WATERFALL_QUEST`. Quest Helper maps the following values
([varp registration](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/questinfo/QuestVarPlayer.java#L38-L58)):

| Varp 65 | Quest Helper phase |
| ---: | --- |
| 0 | Talk to Almera and accept the quest. |
| 1 | Reach Hudon's island and finish his dialogue. |
| 2 | Reach the tourist centre, obtain the book, and read it. |
| 3-4 | Obtain the pebble, then loot the amulet and urn, or resume the final sequence if those items already exist. |
| 5-8 | Prepare and execute the waterfall dungeon ritual. |

Quest Helper notes that value `7` did not occur in its own testing. It does not
map `9`, and it does not use a guessed raw value to prove completion. The runner
must use RuneLite's normalized `Quest.WATERFALL_QUEST.getState(client)` as the
completion authority and retain varp 65 only for phase selection. RuneLite's
quest API returns `NOT_STARTED`, `IN_PROGRESS`, or `FINISHED` from the game
quest-status script
([source](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Quest.java#L185-L260)).

The live completion run observed varp 8 after the statue raised the floor and
varp 10 at normalized completion. Varp 8 changes the inner-door destination:
crossing it can place the player directly in the chalice chamber rather than
the six-pillar room.

Three named Waterfall varbits exist in RuneLite 1.12.37:

| Varbit | ID | Use |
| --- | ---: | --- |
| `WATERFALL_GERALD_CHAT` | 9108 | Exposed diagnostic only; not required by Quest Helper. |
| `WATERFALL_HADLEY_CHAT` | 9109 | Exposed diagnostic only; not required by Quest Helper. |
| `WATERFALL_GOLRIE_CHAT` | 9110 | Value `1` proves Golrie has supplied the pebble. |

Only `WATERFALL_GOLRIE_CHAT == 1` is a verified routing condition in the
reference implementation. Do not infer meanings for other values before a live
snapshot establishes them.

### Items

| Item | Gameval | ID | Required state |
| --- | --- | ---: | --- |
| Book on Baxtorian | `BAXTORIAN_BOOK_WATERFALL_QUEST` | 292 | Obtain and `Read`. |
| Golrie key | `GOLRIE_KEY_WATERFALL_QUEST` | 293 | Temporary basement gate key. |
| Glarial's pebble | `GLARIALS_PEBBLE_WATERFALL_QUEST` | 294 | Use on tombstone. |
| Glarial's amulet | `GLARIALS_AMULET_WATERFALL_QUEST` | 295 | Loot; equip before falls entry; later use on statue. |
| Glarial's urn, full | `GLARIALS_URN_FULL_WATERFALL_QUEST` | 296 | Loot; use on chalice. |
| Glarial's urn, empty | `GLARIALS_URN_EMPTY_WATERFALL_QUEST` | 297 | Diagnostic failure state only. |
| Baxtorian key | `BAXTORIAN_KEY_WATERFALL_QUEST` | 298 | Loot from falls crate and use to pass the inner door. |
| Water rune | `WATERRUNE` | 555 | Six consumed by pillars. |
| Air rune | `AIRRUNE` | 556 | Six consumed by pillars. |
| Earth rune | `EARTHRUNE` | 557 | Six consumed by pillars. |
| Rope | `ROPE` | 954 | Required for the rock and tree; not consumed. |

The quest rewards are 13,750 Attack XP, 13,750 Strength XP, one quest point,
two diamonds, two gold bars, and 40 mithril seeds. The XP reward is compatible
with the account's 80 Attack cap and is already reserved in the account-builder
ledger. Five free inventory slots are required before the final urn-on-chalice
interaction so all rewards can be received
([guide and rewards](https://oldschool.runescape.wiki/w/Waterfall_Quest?oldid=15297455)).

### NPCs and objects

The runner should address entities by gameval ID, validate the live name and
available action, and only then dispatch the synthetic interaction. It must not
assume a menu index.

| Kind | Entity | Gameval | ID | Intended interaction |
| --- | --- | --- | ---: | --- |
| NPC | Almera | `ALMERA_WATERFALL_QUEST` | 4181 | `Talk-to` |
| NPC | Hudon | `HUDON_WATERFALL_QUEST` | 4182 | `Talk-to` |
| NPC | Golrie | `GOLRIE_WATERFALL_QUEST` | 4183 | `Talk-to` |
| Object | Log raft | `LOGRAFT_WATERFALL_QUEST` | 1987 | `Board` |
| Object | Bookcase | `BOOKCASE_WATERFALL_QUEST` | 1989 | `Search` |
| Object | Golrie crate | `GOLRIE_CRATE_WATERFALL_QUEST` | 1990 | `Search` |
| Object | Golrie gate | `GOLRIE_GATE_WATERFALL_QUEST` | 1991 | `Open` with key present |
| Object | Glarial's tombstone | `GLARIALS_TOMBSTONE_WATERFALL_QUEST` | 1992 | Use pebble on object |
| Object | Glarial's tomb/coffin | `GLARIALS_TOMB_WATERFALL_QUEST` | 1993 | `Search` |
| Object | Amulet chest, closed/open | `GLARIALS_CHEST_*_WATERFALL_QUEST` | 1994/1995 | `Open` |
| Object | Crossing rock | `CROSSING_ROCK_WATERFALL_QUEST` | 1996 | Use rope on object; never bare `Swim to` |
| Object | Falls crate | `BAXTORIAN_CRATE_WATERFALL_QUEST` | 1999 | `Search` |
| Object | Inner door | `BAXTORIAN_DOOR_2_WATERFALL_QUEST` | 2002 | `Open` with key present |
| Object | Six stone pillars | `STONEPILLAR_SMALL_WATERFALL_QUEST` | 2005 | Three rune-on-object uses per pillar |
| Object | Statue of Glarial | `STATUE_QUEEN_WATERFALL_QUEST` | 2006 | Use amulet on object |
| Object | Waterfall entrance | `WATERFALL_LEDGE_DOOR` | 2010 | `Open` with amulet equipped |
| Object | Chalice of Eternity | `BAXTORIAN_CHALICE_WATERFALL_QUEST` | 2014 | Use full urn on object; never bare `Take treasure` |
| Object | Overhanging tree | `OVERHANGING_TREE1_WATERFALL_QUEST` | 2020 | Use rope on object; never bare `Climb` |
| Object | Barrel | `BARREL_WATERFALL_QUEST` | 2022 | `Get in` |
| Object | Gnome dungeon ladder | `ROVING_GOLRIE_LADDER_TO_CELLAR` | 5250 | `Climb-down` |
| Object | Tourist-centre stairs | `SPIRALSTAIRS` / `SPIRALSTAIRSTOP` | 16671/16673 | `Climb-up` / `Climb-down` |

## World geometry

Quest Helper defines rectangular zones rather than testing one exact player
tile. Use inclusive WorldPoint bounds:

| Zone | South-west | North-east |
| --- | --- | --- |
| Tree Gnome basement | `(2497, 9552, 0)` | `(2559, 9593, 0)` |
| Golrie room | `(2502, 9576, 0)` | `(2523, 9593, 0)` |
| Glarial's Tomb | `(2524, 9801, 0)` | `(2557, 9849, 0)` |
| Hudon's island | `(2510, 3476, 0)` | `(2515, 3482, 0)` |
| Dead-tree island | `(2512, 3465, 0)` | `(2513, 3475, 0)` |
| Waterfall ledge | `(2510, 3462, 0)` | `(2513, 3464, 0)` |
| Tourist centre upstairs | `(2516, 3424, 1)` | `(2520, 3431, 1)` |
| Waterfall dungeon | `(2556, 9861, 0)` | `(2595, 9920, 0)` |
| Inner-door/pillar room | `(2561, 9902, 0)` | `(2570, 9917, 0)` |
| Chalice chamber | `(2599, 9890, 0)` | `(2608, 9916, 0)` |

Important action anchors are Almera `(2521,3495,0)`, raft
`(2509,3493,0)` (live object anchor; Quest Helper's marker is one tile north),
Hudon `(2511,3484,0)`, rock `(2512,3468,0)`, tree
`(2512,3465,0)`, barrel `(2512,3463,0)`, tourist stairs
`(2518,3430,0)`, bookcase `(2520,3427,1)`, gnome ladder
`(2533,3155,0)`, gnome crate `(2548,9565,0)`, gate `(2515,9575,0)`,
Golrie `(2514,9580,0)`, tombstone `(2559,3445,0)`, amulet chest
`(2530,9844,0)`, coffin `(2542,9812,0)`, falls crate `(2589,9888,0)`,
inner door `(2566,9901,0)` and statue `(2565,9916,0)`. Quest Helper marks the
chalice at `(2604,9911,0)`; the live scene exposed object 2014 at
`(2603,9910,0)`. The runner discovers that exact-ID object at execution time
instead of relying on either static marker.

The Tree Gnome maze route encoded by Quest Helper is:

```text
(2505,3190) (2512,3190) (2512,3188) (2532,3188) (2532,3182)
(2523,3181) (2523,3185) (2521,3185) (2520,3179) (2514,3179)
(2514,3177) (2527,3177) (2527,3179) (2529,3179) (2529,3177)
(2531,3177) (2531,3179) (2533,3179) (2533,3177) (2544,3177)
(2544,3174) (2549,3174) (2549,3165) (2545,3165) (2545,3159)
(2550,3159) (2550,3156) (2548,3156) (2548,3145) (2538,3145)
(2538,3150) (2541,3150) (2541,3148) (2544,3148) (2544,3150)
(2545,3150) (2545,3155) (2533,3155)
```

All points are plane 0. Preserve this as one quest-specific route hint; normal
ground movement still belongs to the core walker. The six pillars deliberately
have no hard-coded coordinates in Quest Helper. Discover all nearby object ID
2005 instances inside the pillar/end-room area, de-duplicate by WorldPoint, and
require exactly six before starting the ritual.

## Inventory contracts

### Investigation

Carry one rope and enough food for travel. The rope is not consumed by the rock
or tree. Leave at least one slot for the book and later one slot for the Golrie
key and pebble.

### Glarial's Tomb

Use a strict allowlist loadout rather than trying to maintain the game's entire
restriction denylist:

- equipped items: none;
- inventory: one Glarial's pebble, approved food, explicitly allowed jewellery,
  and at least two empty slots;
- everything else: banked.

When an amulet already exists in inventory or bank, the strict loadout carries
it into the tomb. This skips the western chest route and goes directly to the
urn rather than discarding useful observed progress.

The current Wiki says the tomb rejects weapons, armour, runes, ranged ammo,
logs and bow-making supplies, looting bags, clue scrolls, several capes, and
other combat-related items. It explicitly permits food, potions, jewellery,
coins, and ordinary clothing, with listed exceptions
([complete current restrictions](https://oldschool.runescape.wiki/w/Glarial%27s_Tomb?oldid=15318344)).
The narrow allowlist is simpler and safer for this runner. If using the pebble
does not change the zone, treat it as a preflight failure and return to the
bank; never spam the tombstone.

### Final dungeon

Before boarding the final raft, require:

- rope;
- at least six air, six water, and six earth runes;
- full urn;
- amulet;
- enough food to satisfy the HP policy;
- five reward slots, accounting for runes that will be consumed.

The amulet may be carried while rafting but must be equipped before opening the
waterfall entrance. Weapons and armour are allowed in the waterfall dungeon.
Purchase only the missing quantity immediately before this phase, consistent
with the account's just-in-time policy.

## Implemented phase table

Each row is idempotent. After an interaction, wait for one of its listed
postconditions and then recompute the entire phase instead of advancing an
in-memory counter.

| Phase | Entry condition | Action | Verified postcondition / resume rule |
| --- | --- | --- | --- |
| `complete` | Quest API says `FINISHED` | Stop and emit rewards/XP receipt. | Terminal. Never infer completion from position or animation. |
| `accept` | Varp 65 is 0 | Walk to Almera, `Talk-to`, continue, choose exact option `Yes.` | Quest state becomes in progress and/or varp becomes 1. The transcript shows a low-combat warning before the choice, so dialogue handling must tolerate extra Continue pages. |
| `reach_hudon` | Varp is 1 and outside Hudon zone | Board raft 1987. | Player enters Hudon zone. |
| `talk_hudon` | Varp is 1 and in Hudon zone | Talk to Hudon and continue all pages. | Varp becomes 2. The guide explicitly warns incomplete dialogue prevents the book from spawning. |
| `reach_tourist_centre` | Varp is 2; no book; on Hudon island | Use rope on rock 1996. | Player enters dead-tree island zone. |
| `descend_tree` | Varp is 2; on dead-tree island | Use rope on tree 2020. | Player enters ledge zone. |
| `leave_ledge` | Varp is 2; on ledge | Get in barrel 2022. | Player washes up near the tourist centre. |
| `obtain_book` | Varp is 2; no book | Walk to tourist centre, climb stairs, search bookcase 1989. | Inventory contains item 292. |
| `read_book` | Varp is 2; book present | Use inventory action `Read`, then close/continue the book interface. | Varp becomes 3 or 4. Do not merely obtain the book; reading it unlocks the basement key. |
| `obtain_golrie_key` | Varp 3-4; no pebble in inventory/bank; in gnome basement; no key | Search crate 1990. | Inventory contains item 293. |
| `open_golrie_gate` | Same, key present, outside Golrie room | Open gate 1991. | Player enters Golrie room. |
| `obtain_pebble` | Same, in Golrie room | Talk to Golrie and continue all pages. | Inventory contains item 294 or varbit 9110 equals 1. One empty slot is mandatory. |
| `tomb_preflight` | Pebble obtained; amulet or urn absent from inventory/bank | Bank to the strict tomb allowlist; restore HP/run first. | Equipment empty, no disallowed inventory, two free slots, safety gates pass. |
| `enter_tomb` | Tomb preflight passes; outside tomb | Use pebble on tombstone 1992. | Player enters Glarial's Tomb zone. |
| `obtain_amulet` | In tomb; amulet absent from inventory/bank | Walk west and open chest 1994/1995. | Inventory contains item 295. |
| `obtain_urn` | In tomb; amulet exists; urn absent from inventory/bank | Walk south and search coffin 1993. | Inventory contains full urn 296. Searching can be interrupted by damage only after the game action completes; keep the HP guard active. |
| `final_preflight` | Amulet and urn exist in inventory/bank | Exit, bank, and withdraw exact final loadout. | Exact items above, five eventual reward slots, safety gates pass. |
| `reach_falls` | Final loadout passes; outside Hudon zone | Return to raft and board. | Hudon zone. |
| `reach_ledge_final` | Hudon/dead-tree island | Use rope on rock, then rope on tree. | Ledge zone. Never use the bare object actions. |
| `enter_falls` | On ledge | Equip amulet if needed, then open door 2010. | Falls dungeon zone. If washed out, reacquire an amulet before retrying. |
| `obtain_baxtorian_key` | In falls zone; key 298 absent | Search crate 1999 in east room. | Inventory contains key 298. |
| `open_inner_door` | Key present; not in end chamber | Open/pass door 2002, then travel past the fire giants. | Pillar room, or direct chalice chamber when the raised-floor state is already active. |
| `charge_pillars` | Six unique pillars visible; ritual incomplete | For each unique pillar, use one air, one water, and one earth rune. | Observe inventory deltas and game messages after every use. An already-placed rune is not treated as failure. Re-scan the six objects after scene changes. |
| `place_amulet` | Pillars charged | Use amulet on statue 2006. | Floor rises and end chamber/chalice becomes reachable. If this washes the player out, pillar state was incomplete; recover another amulet and resume without assuming the pillars reset. |
| `finish_quest` | Chalice reachable; full urn present; five free slots | Discover object 2014, approach its observed WorldPoint, and use the urn on it. | Quest API becomes `FINISHED`. Never invoke the chalice's bare `Take treasure` action. |

Quest Helper's nested conditional steps are first-match-wins
([dispatcher](https://github.com/Zoinkwiz/quest-helper/blob/c264be77fddb68ab3dfc553f9f113f6ffc60fb71/src/main/java/com/questhelper/steps/ConditionalStep.java#L379-L409)).
The phase order above preserves that precedence: local recovery states win over
travel defaults, and verified item/bank state can skip already-completed work.

## Dialogue and interface contract

Only one choice text is required: Almera's `Yes.`. Hudon and Golrie use ordinary
Continue pages with no encoded choice in Quest Helper. The current transcript
also establishes three interface details:

- accounts below combat level 25 see an extra warning before Almera's start
  choice;
- Hudon's full initial conversation must finish;
- the Book on Baxtorian must be opened with `Read` and then closed/continued.

Dialogue automation therefore needs text-aware Continue and exact-option
selection, not fixed widget coordinates or a fixed number of clicks
([current transcript](https://oldschool.runescape.wiki/w/Transcript:Waterfall_Quest?oldid=15294265)).

## Safety gates

Waterfall Quest has no combat requirement and can technically be completed at
level 3, but that is not equivalent to a strict survival guarantee. The current
Wiki reports a max hit of 14 for the level 84 moss guardian in Glarial's Tomb
and 11 for the level 86 fire giants in Waterfall Dungeon. A failed unroped
waterfall traversal costs 8 Hitpoints. genericBoss now has 25 maximum
Hitpoints after Witch's House, clearing the configured 15-HP preflight while
still requiring the runtime food and movement gates below
([quest hazards](https://oldschool.runescape.wiki/w/Waterfall_Quest?oldid=15297455),
[dungeon hazards](https://oldschool.runescape.wiki/w/Waterfall_Dungeon?oldid=15263096)).

Default runner policy:

- outside hostile zones, require current HP at maximum before the next
  dangerous transition;
- strict mode blocks tomb entry below 15 current HP and falls entry below 12;
- require enough carried food to restore at least two relevant max hits after
  reserving the two tomb-loot slots or five reward slots;
- require at least 60% run energy before tomb entry and 50% before waterfall
  dungeon entry; wait or restore rather than walking into the hazard depleted;
- enable run for hostile traversals and confirm movement after each click;
- suppress all micro and long break rolls from tomb entry until the player is
  back on the surface, and from waterfall entry until the chalice chamber or a
  safe exit; these are time-sensitive critical sections;
- eat before the next movement action whenever current HP is not above the
  area's known max hit, without exceeding inventory constraints;
- stop on death, disconnection, missing local player, unexpected combat lock,
  or a failed zone transition; never substitute repeated clicks.

The 60%/50% run thresholds and two-hit food reserve are GenericClient policy,
not claims from the source guides. The Wiki's low-level mitigation is to engage
a lower-level skeleton or zombie in the tomb, and a shadow spider in the falls,
because both areas are single combat; that can prevent the more dangerous
monster from attacking. This mitigation itself needs combat-targeting and
disengage logic, so it is an optional explicit strategy, not a hidden fallback.
The present account has already raised maximum Hitpoints through Witch's House,
so no low-HP override is needed or planned for this run.

## Resumption rules

- Recompute from normalized quest state, raw varp, zone, inventory, equipment,
  and cached bank contents on startup, after login, after every interaction,
  and after every unexpected displacement.
- A pebble in the bank locks the Golrie phase complete. Both amulet and urn in
  inventory or bank lock the tomb-loot phase complete. This mirrors Quest
  Helper's bank-aware conditions.
- If an intermediate quest item is absent, return to its source: key to crate,
  pebble to Golrie, amulet to tomb chest, urn to coffin, Baxtorian key to falls
  crate. Do not assume the last in-memory action succeeded.
- If washed downstream before reading the book, resume at the tourist centre.
  If washed out after entering the final sequence, bank/recover the missing
  amulet and supplies before returning.
- The Wiki says charged pillars remain active after leaving or being washed
  out. On resume, iterate all six discovered pillars and use each rune type;
  treat the game's already-placed message as success and inventory consumption
  as a newly completed placement. Never rely only on a local 18-bit mask.
- An empty urn (297), fewer than six unique pillars, a bare chalice menu action,
  or a quest stage outside the documented groups is a diagnostic stop requiring
  a live snapshot, not an invitation to guess.

## Minimal GenericClient capabilities

This quest should add only the reusable primitives its phases need:

1. `quest.state`: normalized RuneLite quest state plus selected raw varps and
   varbits. The script supplies the IDs; the core reads them on the client
   thread.
2. `scene.objects`: nearby objects with gameval ID, name, WorldPoint, shape,
   canvas/minimap visibility, and live actions. It must return every matching
   pillar, not just the nearest object.
3. `object.interact`: interact with a live object by ID, optional WorldPoint,
   and semantic action text, then wait for a movement/zone/item/state
   postcondition.
4. `item.interact`, `equipment.interact`, and `item.use_on_object`: `Read`,
   `Wear`, `Remove`, and selected-item-on-scene-object through the synthetic
   client-only cursor.
5. `dialogue`: inspect current Continue/choice widgets, continue, and choose an
   exact visible option string. It must tolerate additional warning pages.
6. `bank.loadout`: deposit all, withdraw exact quantities, equip/unequip, keep
   a five-million-coin reserve, and verify the resulting allowlist before
   closing the bank.
7. `player.vitals`: current/max Hitpoints, run energy, run-enabled state,
   interacting actor, animation, and movement destination. RuneLite 1.12.37
   exposes these underlying client surfaces, including `getEnergy`, skill
   levels, varps/varbits, item containers, NPCs, scene, and menu actions
   ([Client API](https://github.com/runelite/runelite/blob/2624bcc4136cea1011bf1bb154581a4b16c7a3ca/runelite-api/src/main/java/net/runelite/api/Client.java)).
8. `food.eat`: choose an approved carried food item and verify the HP increase
   or item consumption.
9. `critical_section`: execute a bounded group with behavior breaks disabled,
   while preserving cooperative cancellation and safety monitoring.
10. `wait.until`: wait on explicit game-tick postconditions with a timeout and
    structured failure receipt. No quest phase should use wall-clock sleeps as
    proof of success.

Quest-specific facts remain in the standalone Lua script: varp/varbit IDs,
zones, item/entity IDs, phase ordering, dialogue option, loadouts, and the
pillar ritual. Entity discovery, menu dispatch, banking, dialogue, vitals,
break suppression, and postcondition waits belong in the plugin core.

## Live acceptance

On 2026-08-29 genericBoss completed Waterfall Quest through the standalone
Quest Runner. The decisive receipts were:

- a strict bank loadout with a five-million-coin reserve and only observed
  deficits purchased;
- ordinary breaks enabled for travel and banking, including a natural
  18-minute AFK break that logged out and automatically resumed;
- breaks suppressed only in the hostile tomb, falls, ritual, and recovery
  sections;
- exact-ID door traversal, six distinct pillar WorldPoints, all 18 rune
  placements, and successful resume from already-placed rune messages;
- Statue of Glarial object 2006 at `(2565,9916,0)`, followed by the raised
  chalice chamber and raw varp 8;
- direct varp-8 inner-door transition to `(2604,9901,0)`;
- live discovery of Chalice of Eternity object 2014 at `(2603,9910,0)`, a
  dispatched urn-on-object action, and the completion dialogue;
- normalized quest state `FINISHED`, raw varp 10, Attack 30 at 13,842 XP,
  Strength 30 at 14,050 XP, two diamonds, two gold bars, 40 mithril seeds, and
  one quest point.
