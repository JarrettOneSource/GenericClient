# Account builder

Status: active persistent goal. Baseline captured 2026-08-27 from the official
Old School RuneScape Hiscores endpoint; live client reconciliation is pending
the expanded Account Auditor snapshot.

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

## Current baseline

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

## Live Account Auditor receipt

GenericClient 0.11.0 reconciled the official baseline against the logged-in
client on 2026-08-27 at the Grand Exchange (3167, 3488, plane 0):

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

## First bounded milestone

Waterfall Quest is the selected first substantive milestone. The current
pay-to-play melee guide identifies it as the fastest level 1-30 opening; it has
no skill requirement, awards 13,750 Attack and Strength XP, and is a direct
Desert Treasure I prerequisite. The live account has not started it. Its first
JIT supply delta is small: the bank already contains all six water runes, more
than six air runes, food, and four of the six earth runes; rope and two earth
runes remain to be acquired when the Quest Runner is ready.

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

Install the validated build containing AIO Melee, verify the live combat-style
index, and run only the 2 Attack target. Acceptance requires a terminal script
receipt, Attack XP at or above 83 with level exactly 2, no continuing combat,
positive Hitpoints, and a post-run Account Auditor snapshot. Do not extend the
target during the same run.
