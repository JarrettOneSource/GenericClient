# Account builder

Status: active persistent goal. Historical baseline captured 2026-08-27; latest
live reconciliation recorded after Fight Arena completion on 2026-08-30.

## Account goal

Build the active account into the simulator's `med80_77` PvP account:

| Skill | Hard target |
| --- | ---: |
| Attack | 80 |
| Strength | 99 |
| Defence | 75 |
| Hitpoints | 99 |
| Ranged | 99 |
| Magic | 99 |
| Prayer | 77 |

Required combat unlocks are Piety, Rigour, Augury, and Ancient Magicks. The
intended profitable end-state is a budget NH kit with one protected premium
weapon, using the account's approximately 111 combat level to reach wealthy med
and max-main targets.

Attack, Defence, and Prayer are exact caps. No script or planner may request XP
past 80 Attack, 75 Defence, or 77 Prayer. A method whose quest reward or delayed
XP could cross a cap is ineligible.

## Historical baseline

Official Hiscores returned total level 36 and total XP 1,685:

| Skill | Level | XP |
| --- | ---: | ---: |
| Attack | 1 | 12 |
| Defence | 1 | 0 |
| Strength | 4 | 300 |
| Hitpoints | 10 | 1,154 |
| Ranged | 1 | 12 |
| Prayer | 1 | 0 |
| Magic | 1 | 9 |
| Cooking | 1 | 70 |
| Woodcutting | 1 | 25 |
| Fishing | 1 | 10 |
| Firemaking | 1 | 40 |
| Smithing | 1 | 18 |
| Mining | 1 | 35 |
| Other currently ranked skills | 1 | 0 |

The account always has membership. Member quests, transportation, equipment,
training areas, minigames, and supply methods are the default. Free-to-play
support belongs inside each AIO script later, but does not constrain the first
member-optimized implementation.

## Current live progress

The 2026-08-30 GenericClient account snapshots verified:

- Attack 40 / 37,467 XP, Strength 30 / 14,050 XP, Defence 1 / 0 XP;
- Hitpoints 28 / 10,980 XP, Ranged 1 / 12 XP, Prayer 1 / 0 XP, Magic 32 /
  16,774 XP, Thieving 14 / 2,175 XP, and Agility 25 / 7,888 XP;
- Fight Arena, Tree Gnome Village, Waterfall Quest, Witch's House, Ernest the
  Chicken, X Marks the Spot, and Learning the Ropes complete;
- known cash was 14,135,734 coins and the 5,000,000 reserve remained intact;
- the pre-existing Old school bond sell offer remains active and untouched.

## Live Account Auditor receipt

GenericClient reconciled the official baseline against the logged-in client on
2026-08-27 at the Grand Exchange (3167, 3488, plane 0):

- the seven target combat skills and exact XP matched the official Hiscores;
- inventory and all equipment slots were empty;
- the bank satisfied the 5,000,000-coin reserve and contained 23 starter-item
  slots;
- the only finished quests were Ernest the Chicken, X Marks the Spot, and
  Learning the Ropes; no quest was in progress;
- one pre-existing Grand Exchange offer was selling one Old school bond with
  zero completed quantity.

The bond offer is observed state, not planner authority. Do not cancel, collect,
or replace it without a later explicit decision. Only the amount above the
5,000,000 reserve is available for just-in-time progression purchases.

## Economic authority

- Keep at least 5,000,000 coins liquid or banked.
- Buy only the quantities required for the next selected milestone.
- Reuse banked equipment and supplies before placing a Grand Exchange offer.
- Do not pre-buy speculative future training supplies.
- Do not buy a bond, transfer wealth, drop valuables, or enter risk PvP without
  explicit approval.
- Record every automated purchase with item, quantity, unit price, purpose, and
  resulting cash reserve.

## Script contract

The account planner chooses milestones; it does not contain skilling mechanics.
Progress comes from standalone AIO scripts that remain independently useful.

Each AIO script must own its complete domain loop:

- configurable target and exact stop condition;
- method and location selection from observed account state;
- navigation, inventory, equipment, banking, and just-in-time restocking;
- interruption recovery and idempotent resume from the current world state;
- hard-cap enforcement before every XP-producing action;
- compact overlay, runtime controls, structured receipts, and progress logs;
- member-first methods now, with a clean ruleset seam for later F2P support.

Melee is one AIO domain because Attack, Strength, and Defence share equipment,
targets, and combat state. `AIO Melee Trainer` will expose the trained style and
target level rather than duplicating three narrow scripts.

## Durable progress ledger

The RuneLite configuration profile is bound to the account and its built-in
Notes panel contains the human-facing Account Goal. GenericClient may
update that note after verified milestones. This document remains the technical
source for policy, evidence, and automation design.

## Member-first progression envelope

The planner should select one bounded milestone at a time from this dependency
order. The order is deliberately coarse: live account evidence and current
prices decide the exact method immediately before execution.

1. Audit the live client, bank, quest state, equipment, and active GE offers.
2. Establish early combat and transport with high-value quest rewards. The
   opening candidates are Waterfall Quest, Witch's House, Tree Gnome Village,
   The Grand Tree, and Fight Arena; only incomplete quests are eligible.
3. Unlock the transport and utility prerequisites needed by later standalone
   trainers: teleports, spirit trees, fairy rings, and practical bank routes.
4. Complete the Desert Treasure I dependency chain and its skill requirements,
   then unlock Ancient Magicks.
5. Reach 65 Defence, complete the King's Ransom chain and Knight Waves, then
   unlock Piety at 70 Defence and 70 Prayer.
6. Train Prayer to exactly 77 at a hosted gilded altar outside the Wilderness.
   Buy only the bones needed for the remaining XP. Read the Dexterous and Arcane
   prayer scrolls only after the corresponding live-state checks pass.
7. Finish the combat targets through reusable AIO Melee, Ranged, and Magic
   trainers. Every exact-cap skill uses remaining XP, not merely displayed
   level, as its stop condition.

The initial quest candidates are not an authorization to blindly run a fixed
quest list. Account Auditor must first prove current quest state and supplies.
Each quest runner must also account for mandatory and selectable XP before it
starts.

## Opening milestones

Witch's House was completed first to validate the framework and raise the
account from 12 to 25 Hitpoints. Waterfall Quest then completed through the
modular Quest Runner, raising Attack and Strength to 30 and satisfying a direct
Desert Treasure I prerequisite. Its bank preparation, long-break logout and
resume, tomb recovery, doors, six-pillar ritual, statue transition, live
chalice discovery, rewards, and normalized completion are all live-proven.

Before running a quest that crosses dialogues, item restrictions, object
interactions, and hostile rooms, the first live validation is a zero-cost AIO
Melee Trainer target of 2 Attack at the open Lumbridge goblin spawns. This is an
infrastructure receipt, not a replacement for Waterfall Quest. It exercises
world travel, combat-style selection, nearest-NPC menu interaction, live XP and
Hitpoints guards, cooperative stop, disengage, mouse idle, and exact target
receipts while adding only a small amount of useful Attack XP.

The bounded live run completed on 2026-08-27. Its terminal receipt reported
Attack 2 at 84 XP, up 28 XP from the final validated start of 56 XP. Earlier
diagnostic attempts raised the account from the original 12 XP baseline to 56
XP before the final run, so total account progress was 72 Attack XP. The script
disengaged with no active target, moved the synthetic cursor off-screen, and a
post-run Account Auditor snapshot found 6/10 Hitpoints. A break-free safety walk
then parked the account at Lumbridge courtyard (3225, 3218).

The later release-gate walk back through the same goblin area exposed one more
unsafe implicit input: enabled auto-retaliate added 8 Attack XP without a script
attack request, bringing Attack to 92 XP while remaining level 2. AIO Melee now
disables auto-retaliate before target checks or travel, and the account snapshot
reports that state. This prevents ambient attackers from bypassing the script's
XP-producing action boundary.

Two live failures were fixed and regression-tested during the run:

- `session.login` no longer treats `LOGGED_IN` as proof that click-to-play is
  gone; it requires the local player tile to project into the rendered world;
- `npc.interact` resolves a menu entry through its NPC object when the menu's
  local identifier differs from the WorldView-aware NPC index.

## AIO Magic live validation

The first Magic run exposed that Lumbridge goblins were an unsafe training
method for a 10-HP account. The run reached Magic 2 at 83 XP, but its first
emergency preemption could not reopen Inventory while Wind Strike was selected.
The script stopped at 4 HP, the account died, and the first-death tutorial moved
it to Death's Office. GenericClient then completed the tutorial, used the exit
portal, returned to the grave, and reconciled every item: 395 mind runes (400
loaded minus five casts), all six wines, and the equipped air staff. Nothing was
lost.

That recovery supplied concrete framework regressions for direct dialogue
widgets, object/NPC camera facing, instanced object menu identity, the selected-
spell Inventory double-click, and emergency escape latching. Controlled tests
at full HP subsequently proved both the wine action and a no-food escape.

The active Magic method now enters the Port Sarim jail's public corridor and
casts through the locked cell bars. The core walker opens the public entrance;
it does not treat the locked cell doors as destinations. A live Wind Strike on
the caged Pirate consumed one mind rune and raised Magic XP from 83 to 90 while
the player remained at 10/10 Hitpoints. AIO Magic resumes from the nearest
route waypoint, skips the Grand Exchange when its carried loadout is already
complete, rotates among the Pirate, Thief, Mugger, and Black knight, and keeps
an 8-HP Port Sarim escape armed.

The later Witch's House preparation and quest run advanced Magic to 16 at 3,080
XP and Hitpoints to 25 at 8,184 XP. Fire Strike autocast, exact-ID reacquisition
after level-up dialogue, the global 30%-HP forced-food rule, and continued
combat after a successful wine were all exercised live.

A later exact-target run completed Magic 30 at 13,386 XP and Hitpoints 27 at
10,674 XP. It gained 6,654 Magic XP from its validated 6,732-XP start and
returned a terminal target receipt rather than stopping on an approximate
level observation.

Tree Gnome Village completed through its modular Quest Runner, including maze
navigation, Count Check interruption, logout/relogin break recovery, the orb
sequence, and the Khazard Warlord safespot. Fight Arena then completed through
the same root script. Its live run proved just-in-time restocking, a Castle Wars
route, arena death re-entry, template-to-instance safespot mapping, Earth Bolt
autocast against Bouncer, the post-fight Khazard cutscene, verified
`Quick-escape`, and final hand-in. The exact rewards raised Attack from 36 to
40 and Thieving from 1 to 14; combat raised Hitpoints to 28 and Magic to 32.

The standalone AIO Agility script then reached level 25 at 7,888 XP. Its live
proof survived a logout-length break, a Mime interruption, exact mid-course
resume, and stopped after the threshold-crossing obstacle. The Mime event
unlocked Lean and produced a registered standalone solver.

The Grand Tree is now in progress at varp 70. Quest Runner opened the Grand
Tree door, handled the low-combat quest warning, reached Hazelmere from Castle
Wars through both Yanille gates and the island bridges, used emergency food
under jungle-spider pressure, translated Hazelmere's report for King Narnode,
warned Glough, questioned Charlie, obtained Glough's journal, confronted Glough,
and stopped after the guard moved the player into Charlie's cell.

Sources:

- [Pay-to-play melee training](https://oldschool.runescape.wiki/w/Pay-to-play_melee_training)
- [Waterfall Quest quick guide](https://oldschool.runescape.wiki/w/Waterfall_Quest/Quick_guide)
- [Chicken](https://oldschool.runescape.wiki/w/Chicken)

## Exact-cap XP ledger

OSRS cumulative XP thresholds make the hard ceilings 1,986,068 Attack XP,
1,210,421 Defence XP, and 1,475,581 Prayer XP. The planner reserves all known
mandatory quest rewards before approving ordinary training.

| Unlock | Mandatory combat XP relevant to caps | Planning consequence |
| --- | --- | --- |
| Holy Grail | 15,300 Defence; 11,000 Prayer | Required before King's Ransom; include before calculating remaining Defence and Prayer XP. |
| King's Ransom | 33,000 Defence; 5,000 Magic | Requires 65 Defence before starting. Its reward and the later Knight Waves reward remain comfortably below 75 Defence. |
| Knight Waves | 20,000 Attack, Strength, Defence, and Hitpoints | Required for Piety. Reserve the Attack and Defence XP before any exact-cap training. |
| Rigour | 74 Prayer and 70 Defence; Dexterous prayer scroll | The scroll is consumed when read, so purchase and consume just in time. |
| Augury | 77 Prayer and 70 Defence; Arcane prayer scroll | Prayer training stops at the exact 77 threshold before consumption. |
| Ancient Magicks | Desert Treasure I and its prerequisite chain | Requires 53 Thieving, 50 Magic, 50 Firemaking, and 10 Slayer (or the documented gas-mask route). |

At the minimum 65 Defence threshold (449,428 XP), King's Ransom and Knight
Waves bring the account to 502,428 Defence XP, leaving 707,993 XP before level
75. This proves the required Piety chain is compatible with the target, but it
does not permit untracked Defence XP from other quests or shared combat styles.

Reference surfaces used for this envelope:

- [Optimal quest guide](https://oldschool.runescape.wiki/w/Optimal_quest_guide)
- [Desert Treasure I](https://oldschool.runescape.wiki/w/Desert_Treasure_I)
- [King's Ransom](https://oldschool.runescape.wiki/w/King%27s_Ransom)
- [Knight Waves](https://oldschool.runescape.wiki/w/Knight_Waves)
- [Dexterous prayer scroll](https://oldschool.runescape.wiki/w/Dexterous_prayer_scroll)
- [Arcane prayer scroll](https://oldschool.runescape.wiki/w/Arcane_prayer_scroll)
- [Pay-to-play Prayer training](https://oldschool.runescape.wiki/w/Pay-to-play_Prayer_training)

## Next evidence gate

Continue The Grand Tree from the varp-70 cell checkpoint. Restock food only when
its next live phase requires it, before the eventual combat section. Continue enforcing exact
Attack, Defence, and Prayer caps and the 5,000,000-coin reserve.
