# Offline planner measurements

2026-09-04, OpenJDK 21.0.12 in the shared Linux development environment. These are planner measurements,
not live client travel or frame-rate measurements.

`./gradlew --offline plannerBenchmark` runs fixed road, long detour, local rejoin,
and exhausted-search workloads after warmup, then repeats the road after stress.
It reports median elapsed time and per-thread allocated bytes over 11 trials
(five for exhaustion). Every trial must preserve status, path and expanded nodes.
`-PplannerRecording=/absolute/path/planner.jfr` also captures an offline JFR profile.

The comparison used the collision/pathfinder sources from `27ac094`, then restored
the optimized sources and repeated the same benchmark without JFR. All statuses,
tile counts, node counts and path hashes matched between versions.

| Workload | Nodes | Previous median ms | Current median ms | Previous allocated bytes | Current allocated bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Zoo to Grand Tree, 248 tiles | 13,759 | 37.899 | 10.041 | 15,965,680 | 1,480,608 |
| Nearby rejoin, 247 retained/connector tiles | 8 | 0.167 | 0.116 | 19,080 | 10,032 |
| Synthetic sealed goal, search limit | 250,000 | 1,996.510 | 224.644 | 451,631,752 | 20,801,544 |
| Road repeated after stress, 29 tiles | 28 | 0.041 | 0.044 | 48,424 | 6,544 |

The initial short-road timing varied with JVM warmup (0.670 vs 0.735 ms), and its
post-stress timing shows no latency improvement. Allocation savings are consistent;
small timing differences in this shared environment should not be interpreted as
client performance changes.

JFR allocation samples pointed chiefly to boxed integers in collision-region
lookups and search bookkeeping. The implementation therefore changes both:

- Collision and door data use direct region arrays over the supported world
  coordinates. The two index arrays together use about 2 MiB with compressed
  references; region tile data remains sparse.
- Search costs, parents and closed state use growing primitive arrays indexed by
  packed coordinates. No rectangle around the endpoints clips a valid detour.
- The 250,000-node full-search cap and 4,096-node local-rejoin cap remain unchanged.
  Exhaustion remains distinct from an unreachable destination.

Acceptance includes all catalog routes, the existing long-detour and weighted-door
tests, and new coverage for coordinate zero, high-plane packed keys, table growth,
and extreme collision regions.

## Transport graph, 2026-09-05

The graph keeps directed transport actions with their selected route edges. Its
lower bound combines free-space walking distances with transport costs, using
reverse Dijkstra over the transport origins. Walking ignores collision in this
estimate; it remains admissible and consistent even when the cheapest route first
moves away from the goal, changes planes, or chains several transports. The goal's
arrival rectangle is a lower bound for a narrower allowed arrival-tile set.

The new planner was compared with an isolated checkout of `ccfe500`, consecutively
on the same host and with the same benchmark. No live client was involved.

| Workload | Nodes | Committed median ms | Transport planner median ms | Committed allocated bytes | Transport planner allocated bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Zoo to Grand Tree | 13,759 | 9.104 | 9.348 | 1,480,616 | 1,511,224 |
| Nearby rejoin | 8 | 0.073 | 0.153 | 10,032 | 10,840 |
| Sealed goal, search limit | 250,000 | 167.542 | 171.963 | 20,801,552 | 20,802,184 |
| Road after stress | 28 | 0.026 | 0.029 | 6,552 | 7,576 |

All path hashes and node counts match. These small timing differences do not
establish a live performance change. An initial implementation allocated about
29 MB for the sealed search; packed origin keys with JDK binary search and lazily
allocated transport-parent storage restore its prior allocation footprint.

Focused behavior tests cover cheap outward detours, parallel edges, plane cycles,
unconnected planes, table growth, via segments and rejoining before a pending
transport. Forty deterministic directed two-plane graphs also compare 80 searches
with exhaustive all-pairs shortest costs. The runtime catalog and executor are
separate implementation work; the measurements above preserve the walking-only
benchmark and do not measure transport interaction latency.

The final estimate uses the JDK priority queue for reverse Dijkstra. Its measured
coverage is 384/384 instructions, 48/48 lines, 24/24 branches and 10/10 methods;
PIT kills all 33 generated mutants, with none uncovered. This scoped result does
not establish the unfinished whole-change coverage or mutation gates.

## Eligible runtime catalog, 2026-09-05

The benchmark now includes the actual catalog under an explicit offline account
profile: Tree Gnome Village and The Grand Tree finished, Waterfall in progress,
and the supported Monkey Madness puzzle/intervention stages. This admits all 56
current entries. It does not use a live account or bypass their requirements.

Paired workloads in the same JVM produced these measurements:

| Workload | Walking nodes | Catalog nodes | Walking median ms | Catalog median ms | Walking allocated bytes | Catalog allocated bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Zoo to Grand Tree | 13,759 | 13,783 | 12.255 | 14.290 | 1,923,672 | 2,170,984 |
| Sealed goal, search limit | 250,000 | 250,000 | 208.241 | 241.641 | 28,802,152 | 28,819,424 |

The zoo route retains its 248 tiles and path hash. Three Grand Tree climbs followed
by the glider produce an 11-tile route with 10 expansions, 0.063 ms median planning
time and 22,000 allocated bytes. These are planning workloads; native interaction
latency and live-client performance are not measured here. The expanded warmup and
workload mix also differ from the earlier walking-only benchmark, so comparisons
with that earlier run should not be treated as isolated code regressions.

The route audit now passes all 13 retained routes for both account profiles and
checks every eligible catalog edge independently, including static endpoint
passability and selection of the expected connection. All 56 edges pass. No search
rectangle, expansion cap or heuristic was weakened to obtain those results.
