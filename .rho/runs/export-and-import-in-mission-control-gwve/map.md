# Map — export and import in Mission Control (bring-in run gwve)

## Where this branch sits

- HEAD = `6d9d3f4` on `rho/export-and-import-in-mission-control-gwve`; upstream `origin/nabu/goal-build-the-app-based-on-the-designs-in-zn7g`.
- `git merge-base HEAD rho/export-and-import-actions-in-mission-t9r9` = **`c183c1c` "Database export and import: bring in the finished data layer (#27)"** — i.e. the source branch forked from the data-layer merge, three commits below HEAD.
- The only commits between `c183c1c` and HEAD are `f203528 goal: round 15 check` (adds `.rho/runs/.../roadmap.md`), `3159ff8 run: brief`, `6d9d3f4 run: plan outline`. None touch app code or ARCHITECTURE.md.
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

## Project rules that bind this run

- CLAUDE.md: LF endings, `area: lowercase summary` commit messages, stage named files only.
- Brief's "must not break": `MissionControlScreenTest`, `MissionControlViewModelTest`, `EquipmentViewModelTest`, `LiftoffShellTest`, `DatabaseExportTest`, `DatabaseImportTest`, `BackupRoundTripTest`, the other screens, the build gate.
- No data-layer behaviour change, no Room schema change, no clicking a system picker in a test, unmount the screen before closing its database, every test wait needs a timeout.
