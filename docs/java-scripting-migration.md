# Java scripting cutover

GenericClient and the maintained catalog now use Java scripts compiled against
an independent implementation of the supported DreamBot public API. Retired
interpreter sources, manifests, dependencies, and tooling are removed without an
archive. Functional validation passes; the broader quantitative acceptance gates
remain open as recorded below.

## Ownership

- `org.dreambot.api` owns the supported source interfaces and documented API semantics.
- `com.genericclient.script` owns the execution contract and catalog extensions.
- `GenericClientScriptHost` owns script selection, scheduling and event ownership.
- `GenericClientScriptRun` owns one worker, input lifetime, pauses, intents and results.
- `GenericClientScriptActions` dispatches semantic requests to native input owners.
- `GenericClientScriptRegistry` validates annotated JARs and installs replacements atomically.
- `GenericClientAutomationRuntime` owns the automation lifecycle, emergency recovery and manual takeover.
- `GenericClientNativeInputs` constructs, cancels and closes native input services.
- `GenericClientWorldSnapshot` captures observed world state with native entity lifetime IDs.
- `GenericClientWalkJourney` and its planner own journey progress and constrained routes.
- `GenericClientScripts` owns account, training, quest and random-event decisions.

The plugin retains RuneLite event integration and the existing Desktop and
MouseProfiles presentation owners. Superseded runtime, input-services and walker
state implementations are removed.

## Implemented behavior

- Annotated JAR discovery, metadata validation, source compilation and atomic replacement.
- Cancellable script workers, scheduled-stop callbacks, paint ownership, event solvers and independent manual/emergency pauses.
- Supported DreamBot lifecycle, queries, containers, widgets, dialogue, spells, walking and timing methods.
- Native lifetime identities for retained entities and final input resolution.
- All 24 maintained catalog entry points, including six quest families, with their inputs, buttons and event bindings.
- Complete destination journeys with continuations, corridor points, arrival alternatives, account edge memory and verified directed transports.
- Explicit behavior policies and short intents for conversations, bank batches, rewards and item sequences. Long approaches stay outside intents.
- Gameplay activities retain owned-time accounting when discretionary behavior is suppressed. Manual observation and waiting for operator control remain separate.
- Java MCP diagnostics, catalog controls, installers, harness integration and current documentation.

Regression work also fixed a reused-environment scope binding that let an old
scope clear a newer worker binding, hidden widget lookup and capture truncation,
retired query targets, wrong entity kinds in menu matches, and queued callbacks
that retained input after cancellation. Container and item operations preserve
native ownership; snapshots and action receipts remain distinct from observed
quest postconditions.

The integration includes native input changes through client `0ba8b18` and
catalog cleanup through `fcdc31a`. Their Java integration is client `afa788d`
and catalog `2278f5f`. Both remote `main` branches were refreshed on 2026-09-05:
client `057f7c5` and catalog `e04832d`. The client publication's subsequent
PowerShell timeout-test correction and source receipt are also incorporated.

[The quest development handoff](../HANDOFF.md) records the next session's entry
points and remaining work. The prepared checkpoint uses consolidated commits
based on both published `main` revisions, keeping local checkpoint artifacts
out of its added history.

## Functional verification

Combined client validation at `afa788d`, using catalog `2278f5f`, passed
under Xvfb in 1 minute 43 seconds:

```bash
xvfb-run -a ./gradlew test pmdMain pmdTest pmdRouteAudit cpdMain sdkJar shadowJar \
  jacocoCombinedReport routeAudit scriptCatalogAudit \
  -PscriptCatalog=/path/to/GenericClientScripts --offline --console=plain
```

| Check | Result |
| --- | --- |
| Client tests | 666; zero failures, errors or skips |
| Catalog tests | 93; zero failures, errors or skips |
| PMD main/test/route audit, both repositories | Passed; cyclomatic and cognitive limits are strictly below 22 |
| Combined production CPD | Zero duplicate groups at the configured 100-token threshold |
| Production source size | Largest client file: 951 lines; largest catalog file: 246 lines |
| Native route audit | 38 journeys, 66 account-profile checks and all 56 directed transport entries; zero failures |
| Production catalog registry | 24 scripts, 14 inputs, 7 buttons and 17 event NPC bindings |
| Client, SDK and catalog JARs | Built; no retired interpreter code or sources |
| SDK packaging | Only `org.dreambot.api` and `com.genericclient.script` classes |
| MCP and PowerShell monitor | 15 tests passed, including 11 actual PowerShell scenarios |
| Harness | 56 tests previously passed; relevant source unchanged |
| Installers | Bash and Windows PowerShell replacement/preservation scenarios previously passed; relevant source unchanged |

The unchanged official DreamBot first-script example compiles and executes in
the host tests. All GUI input tests use the real AWT event path under Xvfb.
Catalog scenarios drive observable inventory, entity, dialogue and quest changes.
They do not establish live completion of every account workflow.

The later catalog scenarios cover stale rewards, event invitations, delayed scene
objects, rejected inputs, quest message boxes and safe stopping. Recovery scenarios
verify Death's Office retrieval and exit, item gains after fees, and Safety Net's
completed, pending and unsuccessful emergency outcomes. The shared scene fixture
keeps server observations separate from input receipts. An unused default quest
escape implementation was removed; all six quests retain their own escape methods.

## Quantitative acceptance

Combined JaCoCo includes client tests and catalog scenarios. The public SDK has
100% line, branch, method and instruction coverage: 524 lines, 388 branches,
347 methods and 4,039 instructions. This does not imply complete client or catalog
coverage, and JaCoCo instruction coverage is not a statement-coverage measurement.

| Scope | Lines | Branches | Methods | Instructions |
| --- | --- | --- | --- | --- |
| Entire client production source | 69.84% | 56.38% | 72.61% | 70.29% |
| Entire catalog production source | 47.06% | 29.03% | 47.40% | 43.00% |
| Public SDK | 100% | 100% | 100% | 100% |
| Catalog random events | 100% | 85.40% | 100% | 99.05% |
| Catalog recovery | 100% | 84.48% | 100% | 99.26% |

Coverage outside the SDK does not satisfy the required 100% gate.

The latest joint SDK PIT run at client `ee1d48d` and catalog `4ba67cf` generated
645 mutations: 637 killed, 5 timed out, 3 survived and none lacked coverage.
The survivors replace the hash result of `Tile`, `Entity` and `EntityReference`
with zero. That retains equality/hash consistency; no exact hash assertions or
mutation exclusions were added to manufacture a pass. The zero-survivor gate
remains unmet. A narrower container/spell run killed all 190 mutations with no
survivors, timeouts or uncovered mutations.

The full catalog PIT run at `6bd63ee` generated 3,507 mutations: 914 killed,
7 timed out, 400 survived and 2,186 lacked coverage. It completed in 1 minute
26 seconds. Within that run, random events had 312 killed and 76 surviving
mutations; recovery had 65 killed and 11 surviving mutations. Neither package
had uncovered mutations, but the catalog mutation gate remains unmet.
The earlier complete client baseline at `5f88c0a` generated 12,955 mutations:
5,100 killed, 58 timed out, 2,441 survived, 5,354 lacked coverage and two exhausted
worker memory. The subsequent SDK/runtime baseline at `8d0fede` generated 1,482:
684 killed, one timed out, 282 survived and 515 lacked coverage. Those are older
baselines, not acceptance of the final client tree. A later full client run at
`80f19df` with catalog `6bd63ee` is still running at this checkpoint; it has
reported timed-out and memory-exhausted workers and is not a passing receipt.
XML reports are retained in `build/reports/mutation-baselines/` in the migration
checkout.

Statement coverage, Halstead Difficulty and CRAP remain unmeasured because no
configured analyzer supplies them. PMD reports no unused private methods/fields,
parameters, labels or local variables; whole-program reachability remains
unmeasured. CPD and source review do not substitute for those measurements. No
analyzer exclusions, coverage suppressions or dependencies were added to hide
these gaps.

## Remaining acceptance

- Close meaningful runtime/catalog coverage gaps and actionable mutation survivors; the recorded broad gates are not passed.
- Preserve final source and mutation measurements when making further code changes.
- Continue from the matching Java checkouts described in the handoff. The normal checkouts remain on the earlier behavior-framework branches; their committed implementation changes are incorporated without switching another session's checkout.
- Refresh both `main` branches immediately before declaring the work finished; incorporate and validate any newly published changes.

No artifact from this migration has been installed into a running game or used for
account progression. Historical live receipts apply to their original
revisions. Loaded-artifact and watched live acceptance are separate from these
local source, build and scenario results.

## Compatibility references

- https://dreambot.org/javadocs/org/dreambot/api/script/AbstractScript.html
- https://dreambot.org/javadocs/org/dreambot/api/script/impl/TaskScript.html
- https://dreambot.org/javadocs/org/dreambot/api/methods/walking/impl/Walking.html
- https://dreambot.org/javadocs/org/dreambot/api/utilities/Sleep.html
- https://dreambot.org/javadocs/org/dreambot/api/methods/interactive/NPCs.html
- https://dreambot.org/javadocs/org/dreambot/api/wrappers/interactive/Entity.html
- https://dreambot.org/javadocs/org/dreambot/api/wrappers/interactive/Player.html
- https://dreambot.org/javadocs/org/dreambot/api/wrappers/items/Item.html
- https://dreambot.org/guides/scripting/creating-your-first-script/

The exported API is an independent implementation of supported source methods.
Private DreamBot internals and arbitrary precompiled client JARs are outside the
contract. Unsupported members must not be represented by success stubs.
