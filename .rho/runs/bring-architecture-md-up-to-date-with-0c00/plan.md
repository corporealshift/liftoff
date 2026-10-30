# Plan

## Approach

Rewrite the two tables in `ARCHITECTURE.md` — the eight package-layout cells and the seven milestone cells — and leave everything else in the document alone. Every claim written into a cell is one already checked against the tree and recorded in `map.md`: the five `work` files all exist, `ui/control` is a real Mission Control screen, the Launchpad controls the brief hedges on are all on this branch, `Settings` holds exactly the §8 list, and `LiveCoachTest` is the one named thing that does not exist. Because the change is confined to one markdown file, the gate is a check that nothing was broken rather than a check of new behaviour, which is what the brief asks for. The long `work` and M3 cells shrink to short phrases; the detail they carry is not lost, because those rules live in `DESIGN.md` §7.3 and §10 and in the code's own comments.

## Files

- `ARCHITECTURE.md` — the only file changed. Package table (lines 30–37): `rho`, `data`, `settings`, `domain` responsibility cells brought to what is really there; `work` from `Partly` to ✅ with the five files in short phrases and the run-on cut to one or two sentences; `ui` with Mission Control as a real screen in `ui/control` and the Launchpad's controls named; `coach` checked and left as is. Milestones (lines 79–85): M1–M6 each one short sentence reading Done, M2 with the In-Flight placeholder clause gone, M6 naming how the run-generation toggle is honoured, M0 naming what exists and that `LiveCoachTest` has not been run. Topology, resource locations, data flow and invariants are not touched. What each cell will claim is in `map.md`.

## Order

1. Package table, first four rows (`rho`, `data`, `settings`, `domain`): re-check each named file against the tree, then rewrite the responsibility cell and make the status cell ✅. Build untouched, so the gate still passes.
2. `work` row: ✅, five short phrases for `CoachTurnRunner`, `GenerationWorker`, `GenerationEnqueuer`, `GenerationCanceller`, `AppOpenSweep`, one or two sentences total.
3. `ui` row: Mission Control as a real screen in `ui/control` (settings, equipment, Test connection, export/import), the Launchpad's draft status and the Pending/Planned card controls, In-Flight including notes and run content, Mission, Landed, the shell and `ui/theme`.
4. `coach` row: read the seven files against what the row claims; change only if a claim is wrong. It is not, so this step is a check with no edit.
5. Milestone table: M1–M6 rewritten as one short Done sentence each, M0 worded as the decision above.
6. Read the whole document once for leftovers the tables now contradict — any remaining "stub", "placeholder", "comes next", or a status word that no longer matches the row it sits in — and fix only those.
7. Run the gate detached (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` under `nohup`, log under `app/build/`), re-run once if it hangs, then commit staging `ARCHITECTURE.md` by name with a `docs: ` message.

## Testing

No test is written or changed: this is a markdown-only edit, so the suite is the evidence that nothing broke.

- The gate passes cold and unchanged: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, with the same 89 test classes and the same test count as before the edit (the last whole-suite gate measured 736 tests in 89 reports).
- The docs commit touches no Kotlin, resource or build file: its file list is `ARCHITECTURE.md` alone.
- The stale wording is gone, checked by rejecting the old phrasing rather than demanding a new one: no package row still reads `Partly`, no cell still calls Mission Control a stub, no milestone cell still says In-Flight is a placeholder or names what "comes next".
- Every file name the two tables name resolves to a real path in `app/src/main/java/com/liftoff/app/` — the claim that the document matches the code is checkable, not just asserted.
- The claims rest on tests that already exist and pass: `MissionControlScreenTest` for Mission Control as a screen, `LaunchpadPendingStatusTest` / `LaunchpadPlannedActionsTest` / `LaunchpadViewInRhoTest` for the Launchpad controls, `GenerationWorkerTest`, `GenerationEnqueuerTest`, `GenerationCancellerTest` and `AppOpenSweepTest` for the `work` row, `SettingsStoreTest` for the settings row, `InFlightRunTest` and `RunLandingTest` for M6.

## Risks

- **The gate can hang for about five minutes** with no `TEST-*.xml` written. Run it detached with a log and an `EXIT=` sentinel under `app/build/`, and re-run the identical tree once before treating a hang as a failure; do not `gradlew --stop`, which kills every other worktree's daemon.
- **The document's rows contain em dashes**, and the edit tool renders one as a replacement character, so an edit that rewrites a whole row can corrupt the file. Work from a fresh read, anchor each edit on a short line that has no em dash in it, and check the result with `od -c` or a byte-level substitution if a row has to be rewritten whole.
- **A markdown table row is one line**, so a stray pipe or a wrapped line silently breaks the table. After the edits, read the two tables back and confirm each row has the same number of cells as its header.
- **Cutting the `work` and M3 cells down loses detail that appears nowhere else in this document.** That detail is not invented here: the resume, supersede and offline rules are `DESIGN.md` §7.3 and §10, and the code comments carry them. The shortened cells point at the files rather than restating the rules.
- **Unused `PlaceholderScreen` imports in `LaunchpadScreen.kt` and `MissionScreen.kt`** look like the stub the document used to describe. Removing them is a Kotlin change and out of scope; the document just stops calling Mission Control a stub.
- **Line endings.** Editing on Windows can introduce CRLF in a file `.gitattributes` pins to LF. Check the diff for `^M` before committing.
- **The `rho` row records the rho commit the client was copied from** (`ffce07d3…`), which invariant 4 depends on. Keep the hash even while rewording the cell around it.

## Decisions

**M0 is not marked Done.** The brief makes M1–M6 Done but describes M0 as holding the coach template, schemas, validator and prompt builder while the live measurement has not been run. The plan keeps M0 short of Done and states plainly which half exists and that `LiveCoachTest` has not been run. The alternative was to mark every milestone Done so the table reads uniformly green; that is the one place the document would overstate itself, and a reader who runs the live measurement and finds nothing there loses trust in the rest of the table.

**Anything named in the brief that is not on this branch is left out of the document, and named in the run's notes instead.** The brief hedges on the Launchpad's Pending status lines, Planned Regenerate and View in rho. The plan checks each against the source and writes only what is actually there; any that is missing is recorded in the plan/map so the pull request can say so. The alternative is to describe the intended feature set as if it had merged. The document is read as a description of this branch, and a reader who looks for a control that does not exist stops believing the table. Checked against the tree: all three are here, so the pull request has nothing to report as missing.

**The rest of the document is left alone except where the source plainly contradicts it.** Topology, invariants and data flow stay as they are unless a specific line is wrong. The alternative is a general cleanup pass over the prose. The brief asks for a document that matches the code, not a better-written one; a diff limited to claims checked against the tree is also easier to review.

**The package table keeps its existing rows rather than listing every package in the tree.** The brief names seven rows to correct, and the plan corrects those. A new row is added only if the source holds a package with shipped behaviour that no existing row accounts for. The alternative is to enumerate every directory under `com.liftoff.app`. The table is a map of responsibilities, and a directory listing would bury the rows that matter.
