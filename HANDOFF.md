# Java quest development handoff

The client and maintained catalog use Java, with an independently implemented
subset of DreamBot's public source API. All 24 catalog entry points are ported,
including the six quest workflows. The source is a development checkpoint;
complete live quest acceptance and the broader quantitative gates remain open.

Read [Java scripting](docs/java-scripting.md),
[the migration results](docs/java-scripting-migration.md), and the catalog's
`CONTEXT.md` and `docs/quest-runner-design.md` before extending a quest.

## Continue quest work

1. Use matching Java revisions of `GenericClient` and `GenericClientScripts`.
   Keep them in adjacent directories, or pass their paths through the existing
   Gradle properties below.
2. Quest decisions belong in the catalog's
   `src/main/java/com/genericclient/scripts/quests/`. The client owns semantic
   inputs, snapshots, cancellation, and navigation. Continue using observed
   quest variables, inventory, position, dialogue, and entity state to choose
   the next phase; an accepted input alone does not complete a quest step.
3. Add a scenario for the chosen quest checkpoint, implement that behavior,
   and validate the resulting journey. Existing quest and scene fixtures keep
   input receipts separate from delayed game observations.
4. Before live progression, inspect the running client and account. These Java
   artifacts have not been installed or exercised on a live account by this
   migration session. Follow the existing controlled installation procedure
   and the user's account constraints.

From `GenericClient`, validate the pair with:

```bash
xvfb-run -a ./gradlew test pmdMain pmdTest pmdRouteAudit cpdMain sdkJar shadowJar \
  jacocoCombinedReport routeAudit scriptCatalogAudit \
  -PscriptCatalog=../GenericClientScripts --offline --console=plain
npm --prefix mcp test
```

For a focused catalog check, use its existing `test` task with
`-PgenericClientDir=../GenericClient`; it builds the matching SDK. Native GUI
tests need a display; Xvfb is used for the Linux validation above.

## Remaining work

- Complete and watch the chosen Java quest workflows on the intended account.
  Current scenario and route tests do not establish live completion of every
  quest branch.
- Continue meaningful coverage and mutation work. The public SDK has complete
  measured JaCoCo coverage, but the whole client and catalog do not meet the
  required coverage or zero-survivor gates. Unavailable measurements are listed
  in the migration results; none has been declared passed.
- Open diagnosis: check `MagicTrainer.observeCombat` with delayed first-cast
  XP and the `stop_after_cast` button. The observation loop may disengage or
  retry before the initial cast is observed. This has not yet been reproduced
  in a regression scenario or fixed.

Fetch both repositories before publication or a completion claim. Preserve
concurrent changes and keep source publication, installed artifacts, and live
account outcomes distinct.
