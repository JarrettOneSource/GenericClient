# Navigation map revisions

Provenance verified 2026-09-05. P12 uses these values for startup diagnostics
and numeric quest-stage capture. Installation and live validation remain
pending. [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) records attribution;
[walker-design.md](walker-design.md) describes runtime use of the data.

## Verified values

| Data | Game revision | Cache ID | Artifact identity |
| --- | --- | --- | --- |
| Bundled collision map | **240** | **2664** | Shortest Path commit `44a691aafad48bd8f4ef6d00680d627d2aa8153c`; SHA-256 `2fca3c83778995c96a6511cc523e157352ef526f3b0a969892b62010d5c5e717` |
| Bundled door map | **240** | **2686** | SHA-256 `a5d95b4ddecda08bf0016af72f48b358b68d34d0af7930c3ae55eb57cd3eb2ec`; source recipe recorded by GenericClient |
| Locally cached RuneLite injected client 1.12.38 | **240 after initialization** | Not exposed by `Client.getRevision()` | Artifact SHA-256 `7fdedf1194261cc5b99faa35e0d2b4e45b6d56665402ccbde7f3aa6207c3f947` |

The source ZIPs were hashed directly. Collision contains 2,726 region entries
and is 1,200,495 bytes; doors contains 773 entries and is 131,783 bytes. Both
have empty ZIP comments and only region files, so neither archive embeds its
game revision.

## Collision provenance

The pinned upstream archive matches GenericClient's collision SHA-256 exactly.
The generating GitHub Actions run **32314278086**, job **96263271809**, provides
the missing link between this artifact and a game-cache revision. Its
`0_build.txt` records:

```text
2026-08-19T23:41:16.0865578Z Latest OSRS live cache id: 2664
2026-08-19T23:48:25.9807059Z commit_long_sha: 44a691aafad48bd8f4ef6d00680d627d2aa8153c
```

Those authenticated, read-only log receipts establish the input cache and
resulting commit; the commit date alone was not used to infer a revision.
OpenRS2 identifies cache 2664 as `oldschool / live / en`, Jagex source, build
**240**, timestamp `2026-08-19T10:30:10.426193Z`.
[Exact upstream artifact](https://github.com/Skretzo/shortest-path/blob/44a691aafad48bd8f4ef6d00680d627d2aa8153c/src/main/resources/collision-map.zip),
[generating run and logs](https://github.com/Skretzo/shortest-path/actions/runs/32314278086/job/96263271809),
[cache 2664](https://archive.openrs2.org/caches/runescape/2664).

The pinned workflow otherwise checks out current tooling and RuneLite and
downloads the latest cache. Its source alone is not a reproducible cache pin;
future updates should retain the selected cache ID and game revision alongside
the artifact identity.
[Workflow at the artifact commit](https://github.com/Skretzo/shortest-path/blob/44a691aafad48bd8f4ef6d00680d627d2aa8153c/.github/workflows/ExtractCollisionMap.yml).

## Door provenance

`GenericClientCollisionMap` records cache **2686**, game revision **240**,
RuneLite commit `84402b97c378ce2aeed93d633940e3307a4d377b`, and dumper base
`51d94a082fcd5f1c80be83656e3e27a820e46b27`. The vendored hash agrees with those
local records. OpenRS2 independently identifies cache 2686 as
`oldschool / live / en`, Jagex source, build **240**, timestamp
`2026-09-02T10:30:06.395938Z`.
[Local metadata](../src/main/java/com/genericclient/GenericClientCollisionMap.java),
[cache 2686](https://archive.openrs2.org/caches/runescape/2686),
[pinned dumper base](https://github.com/osrs-pathfinding/shortest-path-tooling/blob/51d94a082fcd5f1c80be83656e3e27a820e46b27/collision-map-update/CollisionMapDumper.java).

This verifies the recorded cache's revision and the current artifact's
integrity; it does not independently regenerate the door archive. The pinned
upstream dumper supplies wall/door classification but does not itself write
GenericClient's separate `door-map.zip`; reproducing that sidecar also requires
the extraction modification: record each edge classified as a passable
wall-or-door before flattening it into ordinary collision, then write those
directional bits into the separate regional archive. The local design records
that recipe and its resulting hash; the extraction modification is not vendored
as a runnable generator.

## Runtime comparison

RuneLite `Client.getRevision()` returns the game-client revision. Static
inspection of the installed SDK artifact found `client.init` passing `240` to
the game-engine initializer; `tq.agf` stores it in the field decoded by
`client.getRevision`. This is artifact evidence, not a claim about a currently
running session. GenericClient already copies this API result into
`GenericClientSnapshot` and exposes it as `runtime.game_revision`.
[RuneLite API](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/Client.java#L710-L715),
[injected client 1.12.38](https://repo.runelite.net/net/runelite/injected-client/1.12.38/injected-client-1.12.38.jar),
[snapshot capture](../src/main/java/com/genericclient/GenericClientSnapshot.java).

Safe comparisons are:

- Compare an observed positive `game_revision` with collision revision **240**
  and door revision **240**, independently. Missing or uninitialized values are
  unknown, not a mismatch or a match.
- Retain collision and door hashes as artifact identities. Learned live
  obstacles have their own reason and expiry; they do not establish that a
  static bundle is current.
- Keep Git revisions, OpenRS2 cache IDs, RuneLite version `1.12.38`, and game
  revision `240` in separate fields; they are different namespaces.

Revision equality is only a coarse compatibility check. These two source
caches differ while both are revision 240. `Client.getRevision()` cannot prove
cache-content equality or that every static edge matches the current world.
Live scene collision remains authoritative.

## Quest progress for edge invalidation

The 1.12.38 `Quest` API exposes its ID, name, and normalized enum state. Its
`getState` calls native script **4029**, `quest_status_get`, which obtains
numeric progress from script **4024**, `quest_progress_get`, and collapses it
using the native unstarted/end thresholds. Progress changes can therefore occur
while the enum stays `IN_PROGRESS`.
[Quest API implementation](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/Quest.java#L246-L263),
[native status normalization](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/%5Bproc%2Cquest_status_get%5D.cs2).

The smallest supported path needs no new per-quest varp/varbit table:

```java
client.runScript(4024, quest.getId());
int progress = client.getIntStack()[0];
```

Script 4024 accepts the same quest database-row ID and returns the native
progress value, or `-1` for an unsupported row. It owns the switch selecting
each quest's actual progress variable. The inspected SDK has a named constant
for 4029 but not 4024; a single documented script-ID constant is sufficient.
Run the call on the client thread outside another running client script, and
copy the result immediately: `runScript` is explicitly not reentrant.
[Native progress dispatch](https://github.com/runelite/cs2-scripts/blob/c3d0cee8fdf0340cd62e43671c0f566d7e43b40a/scripts/%5Bproc%2Cquest_progress_get%5D.cs2),
[script constants](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/ScriptID.java#L125-L137),
[execution contract](https://github.com/runelite/runelite/blob/ac79ed8bd8926bec7bf172aa291574b4d944b0e7/runelite-api/src/main/java/net/runelite/api/Client.java#L1555-L1565).

The quest cache now captures numeric progress alongside the enum and copies
it before the native stack is reused. It retains the observation tick and
refreshes every ten game ticks, so invalidation can lag a stage change by up
to ten ticks. `-1` is exposed as unknown, not a real quest stage.

This covers the quest's main progress value, not every auxiliary puzzle,
dialogue, inventory, or access flag. A requirement tied to another varp/varbit
must still observe that actual requirement. The existing copied varps are
useful once a dependency is known, but there is no reason to treat every varp
change as quest progress. No live invocation or timing benchmark was performed
for this research.
