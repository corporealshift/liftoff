# Map — export and import in Mission Control (bring-in run gwve)

## Where this branch sits

- Branch `rho/export-and-import-in-mission-control-gwve`; upstream `origin/nabu/goal-build-the-app-based-on-the-designs-in-zn7g`.
- `git merge-base HEAD rho/export-and-import-actions-in-mission-t9r9` = **`c183c1c` "Database export and import: bring in the finished data layer (#27)"** — i.e. the source branch forked from the data-layer merge, a few commits below this branch's HEAD.
- Everything this branch has added since `c183c1c` is `.rho/runs/` bookkeeping (`f203528 goal: round 15 check`, `3159ff8 run: brief`, and the `run: plan` / `run: verify` commits). None of it touches app code or ARCHITECTURE.md, so re-check `git log --oneline c183c1c..HEAD` rather than trusting a hash written here.
- **The six cherry-picks apply cleanly.** `git merge-tree --write-tree --name-only HEAD rho/export-and-import-actions-in-mission-t9r9` → exit 0, no conflicted files. `git apply --check` on each `git format-patch` output is clean for five of six; the `601e8d8` screen-test patch only fails standalone because it builds on `75e7490`'s edits to the same file, which the ordered cherry-pick supplies. So the brief's "keep both sides" conflict rule should never fire.
- The six commits touch no `.rho/runs/` path, so nothing has to be stripped from them.

## The six commits (source branch `rho/export-and-import-actions-in-mission-t9r9`, also on origin)

| sha | subject | files |
|---|---|---|
| `1375e11` | ui: add the backup view model with busy, result rows and the dated export name | `ui/control/BackupViewModel.kt` (+129), `test/.../ui/control/BackupViewModelTest.kt` (+332) |
| `265e5bf` | test: prove the backup import action writes through room's flows | `test/.../ui/control/BackupImportRoomTest.kt` (+267) |
| `7f855ad` | ui: draw the backup card's rows, result line and export/import buttons | `ui/control/BackupSection.kt` (+168) |
| `75e7490` | ui: put the backup card on mission control on the app's own database | `ui/LiftoffShell.kt` (+3), `ui/control/MissionControlScreen.kt` (+54), `MissionControlScreenTest.kt` (+13/-5) |
| `601e8d8` | test: drive the BACKUP card's rows, its buttons and its busy guard from the screen test | `MissionControlScreenTest.kt` (+189/-13) |
| `578d3b5` | docs: record export and import as built in M1 and M5 | `ARCHITECTURE.md` (2 cells) |

Its own history also has `run: task N done` commits (`cf75d89`, `c81dcdf`, `d2f7ff8`, `b83d8d3`, `0a44116`, `1ed4b66`) — **not** cherry-picked.

## The CI failure (read from the log, not guessed)

- `gh run list --branch rho/export-and-import-actions-in-mission-t9r9` → run **`39288220848`**, `pull_request`, CI, conclusion `failure`.
- `gh run view 39288220848 --log-failed` (saved at `app/build/ci-t9r9-failed.log`, 107 lines): the `gate / build and test` step ran `./gradlew :app:assembleDebug :app:testDebugUnitTest`, assembleDebug succeeded, then
  `LiftoffShellTest > systemBackFromATabGoesToLaunchpad FAILED` — `androidx.compose.ui.test.ComposeTimeoutException at LiftoffShellTest.kt:37`; `539 tests completed, 1 failed`; `BUILD FAILED in 2m 47s`.
- Nothing else failed. No compile error, no hang, no timeout kill. The failing test is in a file the six commits never touch, and the same tree passed locally (that branch's own gate log claims `tests=539 failures=0`).

## app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt (201 lines) — the file the CI fix lands in

- `createAndroidComposeRule<MainActivity>()`; `@Before` runs `container.missionManager.onAppOpen()` in `runBlocking` then `waitForIdle()`.
- Line 36-39, the shared helper the failure points at:
  `private fun waitFor(matcher: SemanticsMatcher) { composeRule.waitUntil(5_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }`
- `systemBackFromATabGoesToLaunchpad` (:74-82): click `onNodeWithContentDescription("Mission")` → `waitFor(missionTitle)` → `pressBack()` → `waitFor(hasText("CONFIRM"))`. One of those two 5 s waits is what expired.
- `missionTitle = hasText("MISSION") and !isSelectable()` (:33) — needed because the bottom-bar label and the screen title both match.
- Across the whole suite every Compose wait is `5_000` (26 `waitUntil(5_000)` sites plus a handful of `timeoutMillis = 5_000`); nothing in the repo already uses a larger budget.

## app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt (261 lines)

- `MissionControlScreen(...)` is composed at :111 when `nav.missionControlOpen`; opened by the icon at :154 (`contentDescription = "Mission Control"`), which sets `nav = nav.openMissionControl()` (:145).
- Tabs: `Tab.Launchpad -> LaunchpadScreen(...)` :165, `Tab.Mission -> MissionScreen(container)` :172. The shell test's "Mission" click goes to `MissionScreen`, which these commits do not touch; Mission Control is not composed in that test.
- `75e7490` adds `database = container.database` and `missionManager = container.missionManager` at the Mission Control call site, so an import lands in the database the other tabs observe.

## What the brought-in code does (already written; the plan does not re-derive it)

- `BackupViewModel.defaultExportFileName(clock: Clock = Clock.systemDefaultZone())` = `"liftoff-${LocalDate.now(clock)}.json"`, commented "today on the device's own zone".
- `BackupViewModelTest` already handles the time zone: one test pins a `Clock.fixed(Instant.parse("2026-10-20T02:00:00Z"), ZoneId.of("America/New_York"))` and asserts `liftoff-2026-10-19.json` plus an explicit "the UTC date leaked" negative; another reads the real clock and accepts either `liftoff-$openedOn.json` or `liftoff-$closedOn.json`.
- `BackupImportRoomTest`: `@Config(sdk = [34])`, single-thread worker `Thread(..., "backup-import-worker")` → `asCoroutineDispatcher()`, scope = `Job() + Dispatchers.Unconfined + CoroutineExceptionHandler`; polls with a `System.currentTimeMillis()` deadline + `POLL_MILLIS`, `withTimeout(BOUND_MILLIS)`; `tearDown` joins the scope under a timeout, then `worker.close()`, then closes every database it opened.
- `MissionControlScreenTest` after `601e8d8`: `@Config(qualifiers = "w411dp-h2800dp")` (was `h2000dp`); its own `backupScopeJob`/`backupScope`/`backupWorker` ("backup-worker-under-test"); `waitForRow(text)` = `waitUntil(timeoutMillis = WAIT_MS)` because `waitForIdle()` does not wait for an injected dispatcher; `tearDown` unmounts and idles before `backupWorker.close()` then `database?.close()`.
- `75e7490`'s pickers: Export = `CreateDocument("application/json")` with the dated default name, output opened `"wt"` so a stale backup is truncated; Import = `OpenDocument` + `openInputStream`; a cancelled picker hands back no Uri and reaches the view model as a null opener, which does nothing. The BACKUP card is drawn last, after EQUIPMENT.
- `BackupSection.kt`'s wording: row 1 "Export the whole database to a JSON file" / button `Export`; row 2 "Import a backup file into an empty database" / button `Import`; then "Settings and the daemon token are not in this file." Inside `SectionCard("BACKUP")`, the way `EquipmentSection` is wrapped.
- `data/DatabaseImport.kt` `importDatabase(db, input, manager): ImportResult` (already on HEAD from PR #27): **only an empty database is accepted**, the sole exception being the DRAFT week `MissionManager.onAppOpen()` makes on a cold start, which is dropped so the file's own week does not collide on `weekStart`. `ImportResult.Refused(message)` / `ImportResult.Restored(missions, sorties)`; the refusal constants are `NOT_EMPTY_MESSAGE`, `NOT_A_LIFTOFF_FILE_MESSAGE`, `NEWER_LIFTOFF_MESSAGE`, `UNKNOWN_FORMAT_MESSAGE`, `INCOMPLETE_FILE_MESSAGE`, and Mission Control shows them as written.

## ARCHITECTURE.md milestone cells (the docs cherry-pick)

- M1 status becomes `Done — test connection comes later; export and import are built` (drops "the export and import data layer is built").
- M5 status drops `the export and import data layer is built; its Mission Control actions come next` and ends `export and import are built`.
- HEAD's two cells are the pre-image of `578d3b5` unchanged (merge-tree is clean), so the pick rewrites them without conflict.

## The test method names the picks add (for the verify step to name)

- `BackupViewModelTest`: `backupWorkRunsOffTheCallingThread`, `exportShowsBusyThenThatItWasSaved`, `importShowsBusyThenHowMuchWasRestored`, `refusedImportShowsTheDataLayersMessageUnchanged`, `workThatThrowsBecomesAFailureRowNotACrash`, `aCancelledPickerChangesNothing`, `theDefaultExportFileNameCarriesTodaysLocalDate`, `aSecondActionWhileWorkIsRunningChangesNothing`.
- `BackupImportRoomTest`: `anImportThroughTheBackupActionReachesRoomsFlows`.
- `MissionControlScreenTest` (new): `backupShowsExportAndImportEnabledAndTheExclusionLine`, `successAndFailureRowsAreDrawnFromTheState`, `bothBackupButtonsAreDisabledWhileWorkIsRunning`.

## Build and gate facts

- `app/build.gradle.kts` `testOptions.unitTests`: `isIncludeAndroidResources`, `isReturnDefaultValues`, and `all { it.timeout.set(Duration.ofMinutes(5)) }` — the fail-fast hang guard from PR #25.
- `gradle.properties` sets no `maxParallelForks` or `forkEvery`: every test class runs in one forked JVM, so a heavy class can slow the ones after it.
- Gate on this machine: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` (run detached, `> app/build/<name>.log 2>&1; echo EXIT=$? >> log`, after `mkdir -p app/build`). Counts come from `app/build/test-results/testDebugUnitTest/TEST-*.xml`, not stdout.
- Known flake the brief names: `doneBodyweightSetStepperShowsBW` (an In-Flight screen test) sometimes fails with a closed connection pool and passes on a re-run.

## verify.sh (this run's gate, written at the verify step)

`.rho/runs/export-and-import-in-mission-control-gwve/verify.sh`. Syntax-checked with `bash -n`; the gate was **not** run at this step (bring-in run: the named tests' absence is already the proof).

- **Gate section runs CI's two tasks twice back to back** (`timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, stale `$RESULTS` cleared before each, daemons stopped once before the first), because the brief's Done-when asks for "two full local gate runs in a row". Up to 4 attempts, needing **2 consecutive** passes; an attempt gets another only if it failed solely on `doneBodyweightSetStepperShowsBW`, or its log names a daemon/OOM/"could not clear" problem. A build that died before reaching the tests with no such line, or a real assertion failure, stops the loop. It also fails if any attempt shows `:app:testDebugUnitTest FROM-CACHE|UP-TO-DATE`; deleting `$RESULTS` first is what makes the second run re-execute the test task instead of reporting it UP-TO-DATE.
- Named tests are looked up by **method name** in the last gate run's reports (one awk index pass over every `TEST-*.xml`), so class and package stay the implementer's choice. Class checks (`c <ClassName>`) cover the classes the brief's Done-when and "must not break" list name: `BackupViewModelTest`, `BackupImportRoomTest`, `MissionControlScreenTest`, `LiftoffShellTest`, `MissionControlViewModelTest`, `EquipmentViewModelTest`, `DatabaseExportTest`, `DatabaseImportTest`, `BackupRoundTripTest`, `InFlightScreenTest`, `MainActivityTest`.
- **All 12 new test names below were confirmed to exist on the source branch** with `git grep -c "fun <name>" rho/export-and-import-actions-in-mission-t9r9 -- app/src/test` (one hit each), and the must-not-break classes plus `systemBackFromATabGoesToLaunchpad` exist on HEAD. So no named check can be unsatisfiable.
- Not checked, and said so in the script's header: the pull request's CI run (outside the repo, and the script runs before the PR exists — plan step 9 is where the work reads it with gh); "no test clicks a system picker" and "every wait has a timeout" (both surface as a hung suite, which the gate's `timeout` fails); the card's row wording, the refusal sentence as printed, the six commits' messages; the data-layer/schema freeze (covered by the two schema tests `exportedV1SchemaMatchesDatabase`, `databaseRefusesVersionMismatchInsteadOfWiping` and the three data classes).
- **ARCHITECTURE.md check rejects today's wording and accepts the pick's.** Both M1 and M5 cells must have a clause naming export/import that calls it built *and* no clause holding it back; a clause that claims only "the export and import data layer" while naming nothing on the Mission Control side counts as held back. Verified against both wordings: HEAD's M1 and M5 cells are rejected, `578d3b5`'s two cells pass, as do variants like "export and import are built in Mission Control" and "export and import (the data layer and its Mission Control actions) are built". Rows are read as the 4th `|`-split field of the line starting `| M1 |` / `| M5 |`. The area-map rows are not checked — `578d3b5` changes only the two milestone cells.
- Run-records check: nothing under `.rho/runs/export-and-import-actions-in-mission-t9r9` (that branch's own run directory, confirmed present on the source branch and absent from HEAD's tree) may be brought in by any commit on this branch or exist in the tree. Every one of that branch's `run:` commits touches it, so this covers both brief clauses.
- The `index_reports` / `t` / `c` helpers are byte-identical to the ones in `.rho/runs/database-export-and-import-bring-in-the-dp10/verify.sh`, which has passed as a gate before, so the XML parsing is not new. `mktemp -d` for `$WORK` is likewise what every earlier run's script does.
- The doc-check helpers were re-run as a doctest against the functions **extracted out of verify.sh itself** (`sed -n '/^names_the_feature() {/,/^for row in/p'` → source → 12 cases): HEAD's two cells rejected, `578d3b5`'s two accepted, "export and import are built in Mission Control" / "…(the data layer and its Mission Control actions) are built" / "backup and restore are in place" accepted, "export and import come next" and "the export and import data layer is built; its Mission Control actions come next" rejected, and a deferral of a *different* feature ("export and import are built; the coach comes later") accepted. Scratch files for this live under `app/build/` and are not committed.
- No separate test is named for "the card is on Mission Control": the three new `MissionControlScreenTest` cases call `launchScreen`, which composes the whole `MissionControlScreen` with `database = db`, `missionManager = MissionManager(db, store)` and `backupViewModel` (null → the screen builds its own over the real database). `showsEverySectionHeadInOrder` is *not* named because it passes today with the shorter head list.
- Fact a later step needs: the pick deletes `assertDoesNotExist("Export", ignoreCase = false)` from `showsNoUnbuiltPlaceholders`, and raises that file's `@Config` window from `w411dp-h2000dp` to `w411dp-h2800dp`. Its `tearDown` now unmounts the composition (`mounted` StateFlow + `waitForIdle`) before `database?.close()`, and closes the backup worker dispatcher.

## Project rules that bind this run

- CLAUDE.md: LF endings, `area: lowercase summary` commit messages, stage named files only.
- Brief's "must not break": `MissionControlScreenTest`, `MissionControlViewModelTest`, `EquipmentViewModelTest`, `LiftoffShellTest`, `DatabaseExportTest`, `DatabaseImportTest`, `BackupRoundTripTest`, the other screens, the build gate.
- No data-layer behaviour change, no Room schema change, no clicking a system picker in a test, unmount the screen before closing its database, every test wait needs a timeout.
