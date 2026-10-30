# Plan

## Approach

Rewrite the two tables in `ARCHITECTURE.md`: seven of the eight package-layout rows (the root row is already correct) and the seven milestone cells. Leave everything else in the document alone. Every claim written into a cell has already been checked against the tree and recorded in `map.md`. The five `work` files all exist. `ui/control` is a real Mission Control screen. All the Launchpad controls the brief hedges on are on this branch. `Settings` holds exactly the §8 list. `LiveCoachTest` is the one named thing that does not exist. Because the change is confined to one markdown file, the gate only shows that nothing broke; there is no new behaviour for it to test, and that is what the brief asks for. The long `work` and M3 cells shrink to short phrases. Their detail is not lost, because those rules live in `DESIGN.md` §7.3 and §10 and in the code's own comments.

## Files

- `ARCHITECTURE.md` is the only file changed.
  - Package table (lines 31–37): the `rho`, `data`, `settings` and `domain` responsibility cells are brought to what is really there. `work` goes from `Partly` to ✅, with the five files in short phrases and the run-on cut to one or two sentences. `ui` names Mission Control as a real screen in `ui/control` and lists the Launchpad's controls. `coach` is checked and left as it is.
  - Milestones (lines 79–85): M1–M6 each become one short sentence reading Done. M2 loses the In-Flight placeholder clause. M6 says how the run-generation toggle is honoured. M0 says what exists and that `LiveCoachTest` has not been run.
  - Topology, resource locations, data flow and invariants are not touched. What each cell will claim is in `map.md`.

## Order

1. Package table, rows `rho`, `data`, `settings` and `domain`. Re-check each named file against the tree, then rewrite the responsibility cell and make the status cell ✅.
   - `rho`: the three copied files with the rho commit hash, plus `DaemonConnectionCheck.kt`.
   - `data`: entities, DAOs, `LiftoffDatabase`, `MissionManager`, export/import, and the Re-fly copy (`ReFlyCopy.kt`).
   - `settings`: the responsibility cell names all the §8 settings (connection, coach workspace, pattern, sortie length, units, history window, run toggle, objectives, constraints), not just "units, run-toggle".
   - `domain`: adds the draft coach status (`CoachStatus.kt`) and the sortie generation status (`SortieGenerationStatus.kt`).
2. `work` row: ✅, with short phrases for `CoachTurnRunner`, `GenerationWorker`, `GenerationEnqueuer`, `GenerationCanceller` and `AppOpenSweep`, one or two sentences in total.
3. `ui` row:
   - Mission Control is a real screen in `ui/control` (settings, equipment, Test connection, export/import).
   - The Launchpad draws the draft's coach status, and the Pending and Planned cards' generation status, with Retry, Regenerate, Re-fly, View in rho and Scrub.
   - The row also covers Mission, In-Flight (including notes and run content), Landed, the shell, `ui/sortie` and `ui/theme`.
   - No "stub" is left in the row.
4. `coach` row: read the seven files against what the row claims, and change it only if a claim is wrong. `map.md` found every claim holds, so this step is a check with no edit.
5. Milestone table. Each of M1–M6 gets one short sentence starting "Done", with no "comes next", "placeholder" or "in progress" left over:
   - M2 no longer says In-Flight is a placeholder.
   - M3 covers the worker, queueing, Regenerate and the Launchpad's generation status.
   - M5 covers Landed, Re-fly and export/import.
   - M6 says the toggle is honoured: a simple "Run" plan when it is off, a generated run Flight Plan when it is on.
   - M0 stays Partial (see Decisions): the `coach/` template, schemas, validator and prompt builder exist, and `LiveCoachTest` has not been run.
6. Read the whole document once for leftovers that the tables now contradict, and fix only those. Look for any remaining "stub", "placeholder" or "comes next", and for a status word that no longer matches its row.
7. Run the gate detached: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` as a detached process, logging under `app/build/`. Re-run it once if it hangs. Then commit, staging `ARCHITECTURE.md` by name, with a `docs: ` message.

## Testing

No test is written or changed. This is a markdown-only edit, so the suite is the evidence that nothing broke.

- The gate passes: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` reports BUILD SUCCESSFUL with no failed tests. The last whole-suite run had 736 tests in 89 reports. A different count is not by itself a failure, because no test file is touched, but note it in the PR.
- The docs commit touches no Kotlin, resource or build file: its file list is `ARCHITECTURE.md` alone.
- The stale wording is gone. Check this by searching for the old phrasing, not by requiring new wording:
  - no package row still reads `Partly`;
  - no cell still calls Mission Control a stub;
  - no milestone cell still says In-Flight is a placeholder or names what "comes next".
- Every source file the two tables name resolves to a real path. Kotlin files are under `app/src/main/java/com/liftoff/app/`, the coach template is under the repo-root `coach/`, schemas are under `app/src/main/resources/schemas/`, and fixtures and prompts are under `app/src/test/resources/`. This makes the claim that the document matches the code checkable, not just asserted.
- The claims rest on tests that already exist and pass:
  - Mission Control as a screen: `MissionControlScreenTest`.
  - The Launchpad controls: `LaunchpadPendingStatusTest`, `LaunchpadPlannedActionsTest`, `LaunchpadRegenerateTest` and `LaunchpadViewInRhoTest`.
  - The `work` row: `GenerationWorkerTest`, `GenerationEnqueuerTest`, `GenerationCancellerTest` and `AppOpenSweepTest`.
  - The settings row: `SettingsStoreTest`.
  - M6: `InFlightRunTest` and `RunLandingTest`.

## Risks

- **The gate can hang for about five minutes** without writing any `TEST-*.xml`. Run it as a detached process with a log and an `EXIT=` sentinel under `app/build/`. This machine reaps background shells, so a plain background shell may be killed. Re-run the identical tree once before treating a hang as a failure. Do not run `gradlew --stop`, which kills every other worktree's daemon.
- **The document's rows contain em dashes**, and the edit tool renders one as a replacement character, so an edit that rewrites a whole row can corrupt the file. Work from a fresh read and anchor each edit on a short line with no em dash in it. If a row has to be rewritten whole, check the result with `od -c` or a byte-level substitution.
- **A markdown table row is one line**, so a stray pipe or a wrapped line silently breaks the table. After the edits, read both tables back and confirm each row has the same number of cells as its header.
- **Cutting the `work` and M3 cells down loses detail that appears nowhere else in this document.** That detail is not invented here: the resume, supersede and offline rules are in `DESIGN.md` §7.3 and §10, and the code comments carry them too. The shortened cells point at the files rather than restating the rules.
- **Unused `PlaceholderScreen` imports in `LaunchpadScreen.kt` and `MissionScreen.kt`** look like the stub the document used to describe. Removing them is a Kotlin change and out of scope; the document just stops calling Mission Control a stub.
- **Line endings.** Editing on Windows can introduce CRLF into a file that `.gitattributes` pins to LF. Check the diff for `^M` before committing.
- **The `rho` row records the rho commit the client was copied from** (`ffce07d3…`), and invariant 4 depends on it. Keep the hash when rewording the cell around it.

## Decisions

**M0 is not marked Done.** The brief makes M1–M6 Done. For M0 it says the coach template, schemas, validator and prompt builder exist but the live measurement has not been run. The plan keeps M0 short of Done and says plainly what exists and that `LiveCoachTest` has not been run. The alternative was to mark every milestone Done so the table reads uniformly green. That would be the one place the document overstates itself, and a reader who goes to run the live measurement and finds nothing there would stop trusting the rest of the table.

**Anything named in the brief that is not on this branch is left out of the document and named in the run's notes instead.** The brief hedges on the Launchpad's Pending status lines, Planned Regenerate and View in rho. The plan checks each against the source and writes only what is actually there. Any that is missing is recorded in the plan or map so the pull request can say so. The alternative was to describe the intended feature set as if it had merged. The document is read as a description of this branch, and a reader who looks for a control that does not exist stops believing the table. Checked against the tree, all three are present, so the pull request has nothing to report as missing.

**The rest of the document is left alone except where the source plainly contradicts it.** Topology, invariants and data flow stay as they are unless a specific line is wrong. The alternative was a general cleanup pass over the prose. The brief asks for a document that matches the code, not a better-written one, and a diff limited to claims checked against the tree is easier to review.

**The package table keeps its existing rows rather than listing every package in the tree.** The brief names seven rows to correct, and the plan corrects those. A new row is added only if the source holds a package with shipped behaviour that no existing row accounts for. The alternative was to list every directory under `com.liftoff.app`. The table is a map of responsibilities, and a directory listing would bury the rows that matter.
