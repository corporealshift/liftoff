# Tasks — export and import in Mission Control (bring-in run gwve)

Seven tasks, in this order. Tasks 1–5 are the brief's six cherry-picks, in the brief's order, each landing as its
own commit with its original message; nothing is squashed and no `.rho/runs/` file arrives. Task 6 is this run's
only new code: a test-only fix commit. Task 7 is the two cold gate runs the brief's Done-when asks for.

Every test name below is spelled exactly as `.rho/runs/export-and-import-in-mission-control-gwve/verify.sh` spells
it. The picks carry all 12 new names, so no task invents a test; the task that brings a pick is the session that
writes it. The names verify.sh checks that already exist on HEAD (`systemBackFromATabGoesToLaunchpad`,
`exportedV1SchemaMatchesDatabase`, `databaseRefusesVersionMismatchInsteadOfWiping`, and the must-not-break classes)
need no task of their own.

Plan step 9 — reading the pull request's CI run and fixing what its log names — is not a task here: this run does
not push or open the pull request, so no session in this run can reach that run. Task 6 is the part of it that can
be done now, and it is the part the earlier branch's CI log actually named.

For any Gradle run longer than a single compile: `mkdir -p app/build` first, run it detached
(`> app/build/<name>.log 2>&1; echo "EXIT=$?" >> app/build/<name>.log`) and wait on the `EXIT=` sentinel rather
than re-launching a build that is still going. Scratch logs stay under `app/build/` and are never committed. Stage
named files only; never touch `verify.sh`.

- [x] 1. Bring in the backup view model and its plain-Kotlin tests (`1375e11`)
  Cherry-pick `1375e11` ("ui: add the backup view model with busy, result rows and the dated export name") with its
  original message. It adds `app/src/main/java/com/liftoff/app/ui/control/BackupViewModel.kt` (+129) and
  `app/src/test/java/com/liftoff/app/ui/control/BackupViewModelTest.kt` (+332). Nothing composes the view model yet;
  no other file changes.
  Tests this task writes (all in `BackupViewModelTest`): `exportShowsBusyThenThatItWasSaved`,
  `importShowsBusyThenHowMuchWasRestored`, `backupWorkRunsOffTheCallingThread`,
  `aSecondActionWhileWorkIsRunningChangesNothing`, `workThatThrowsBecomesAFailureRowNotACrash`,
  `theDefaultExportFileNameCarriesTodaysLocalDate`, `refusedImportShowsTheDataLayersMessageUnchanged`,
  `aCancelledPickerChangesNothing`.
  Done when the pick is committed and `bash gradlew.sh :app:compileDebugUnitTestKotlin` passes, then a run of
  `:app:testDebugUnitTest --tests '*BackupViewModelTest*' --no-build-cache` reports 8 tests, 0 failures, 0 errors in
  the JUnit XML, with all eight names above present.

- [x] 2. Bring in the proof that an import reaches room's flows (`265e5bf`)
  Cherry-pick `265e5bf` ("test: prove the backup import action writes through room's flows") with its original
  message. It adds only `app/src/test/java/com/liftoff/app/ui/control/BackupImportRoomTest.kt` (+267): Robolectric
  over in-memory Room, a worker thread the test owns, the Flow subscription started before the action. It builds on
  task 1's view model, so it comes after it.
  Test this task writes: `anImportThroughTheBackupActionReachesRoomsFlows` (in `BackupImportRoomTest`).
  Done when the pick is committed and `:app:testDebugUnitTest --tests '*BackupImportRoomTest*' --no-build-cache`
  reports 1 test, 0 failures, 0 errors in the XML, with that name present.

- [x] 3. Bring in the BACKUP card and put it on the app's own database (`7f855ad`, then `75e7490`)
  Two cherry-picks, in this order, each its own commit with its original message. `7f855ad` ("ui: draw the backup
  card's rows, result line and export/import buttons") adds `ui/control/BackupSection.kt` (+168) and nothing else.
  `75e7490` ("ui: put the backup card on mission control on the app's own database") edits
  `ui/control/MissionControlScreen.kt` (+54: the card, the two system pickers, the `database`/`missionManager`
  parameters), `ui/LiftoffShell.kt` (+3: `database = container.database`, `missionManager =
  container.missionManager` at the Mission Control call site), and `MissionControlScreenTest.kt` (+13/-5:
  `launchScreen` passes the new parameters beside the in-memory database it already builds, BACKUP joins the heads
  list, the `@Config` window goes from `w411dp-h2000dp` to `w411dp-h2800dp` so the new card is on-screen, and the
  `assertDoesNotExist("Export")` line is dropped from `showsNoUnbuiltPlaceholders` — that helper matches case
  sensitively, so it cannot be reworded to spare the new EXPORT button).
  No new test name: this task is proved by the cases that already exist in `MissionControlScreenTest` passing with
  the card on screen, `showsEverySectionHeadInOrder` among them — that is the case whose head list `75e7490` extends
  with BACKUP, so it is what shows the card is actually composed.
  Done when both picks are committed, `bash gradlew.sh :app:assembleDebug` passes, and
  `:app:testDebugUnitTest --tests '*MissionControlScreenTest*' --no-build-cache` reports 0 failures and 0 errors
  with `MissionControlScreenTest` present in the XML.

- [x] 4. Bring in the three BACKUP cases driven from the screen test (`601e8d8`)
  Blocked by task 3: this patch is written against `75e7490`'s version of `MissionControlScreenTest.kt` and does not
  apply on its own. Cherry-pick `601e8d8` ("test: drive the BACKUP card's rows, its buttons and its busy guard from
  the screen test") with its original message. It changes only
  `app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt` (+189/-13): three cases plus the helpers
  they need — an injected `backupVm(...)` built on the file's own `backupWorkerPool`/`backupWorker` dispatcher
  (closed in `tearDown`), `backupNode(text)`, `waitForRow(text)` using `waitUntil(timeoutMillis = WAIT_MS)` with
  `WAIT_MS = 5_000L`, and a `CountDownLatch` pair that parks the fake export so the busy guard is really hit. No
  test clicks a system picker.
  Tests this task writes: `backupShowsExportAndImportEnabledAndTheExclusionLine`,
  `successAndFailureRowsAreDrawnFromTheState`, `bothBackupButtonsAreDisabledWhileWorkIsRunning`.
  Done when the pick is committed and `:app:testDebugUnitTest --tests '*MissionControlScreenTest*'
  --no-build-cache` reports 0 failures and 0 errors with all three names present in the XML. If a case cannot see
  the card in the taller window, use `performScrollTo` in that case rather than making the window taller.

- [ ] 5. Record export and import as built in ARCHITECTURE.md (`578d3b5`)
  Cherry-pick `578d3b5` ("docs: record export and import as built in M1 and M5") with its original message. It
  rewrites two status cells in `ARCHITECTURE.md`'s milestone table and nothing else: M1 becomes `Done — test
  connection comes later; export and import are built`, M5 drops "its Mission Control actions come next" and ends
  `export and import are built`.
  No test of its own; verify.sh reads the 4th `|`-split field of the lines starting `| M1 |` and `| M5 |`, splits it
  on `; , . — –`, and needs one clause naming export and import that calls them built, with no clause holding them
  back (a clause that claims only "the export and import data layer" while naming nothing on the Mission Control
  side counts as held back).
  Done when the pick is committed and both cells read as above — check them against those rules before committing,
  and confirm HEAD's pre-pick wording is what the check rejects.

- [ ] 6. Fix the wait budget CI failed on, after auditing the three new test classes
  This run's only new code, and it is test-only. First audit the three classes the picks brought
  (`BackupViewModelTest`, `BackupImportRoomTest`, `MissionControlScreenTest`) against the brief's five hazards:
  every wait bounded; no assertion depending on the time zone (CI runs in UTC); the worker thread and every database
  closed in `tearDown`; the screen unmounted before its database closes; nothing clicking a system picker. The map
  records that they already do all of this — confirm it by reading the files, and if a hazard is real, give that
  class its own fix commit that keeps every assertion.
  Then give `app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt`'s shared `waitFor` helper (lines 36-40; the
  `waitUntil(5_000)` budget is on line 37) real headroom: about 20 s, defined once as a named constant. That helper
  is what CI's log pointed at (`ComposeTimeoutException` at `LiftoffShellTest.kt:37`, 1 failure out of 539, in a file
  the six picks never touch, on a tree that passed locally). Change no assertion, no other file, and nothing in the data
  layer or the Room schema. Commit message in the `area: lowercase summary` form, with a body naming CI run
  `39288220848` and the failing test.
  Test this task covers (it already exists; it must still pass with its assertions intact):
  `systemBackFromATabGoesToLaunchpad`, in `LiftoffShellTest`.
  Done when the fix commit is in and `:app:testDebugUnitTest --tests '*LiftoffShellTest*' --no-build-cache` reports
  0 failures and 0 errors with `systemBackFromATabGoesToLaunchpad` present.

- [ ] 7. Run the full gate twice back to back, cold
  Blocked by tasks 1–6. Run `bash gradlew.sh --stop`, `mkdir -p app/build`, delete
  `app/build/test-results/testDebugUnitTest`, then run
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` detached
  (`> app/build/gate-1.log 2>&1; echo "EXIT=$?" >> app/build/gate-1.log`) and wait on the `EXIT=` sentinel; repeat
  with the results directory deleted again for the second run. Two runs, back to back, both passing — two passing
  logs from runs that were not consecutive do not count. Read `tests=`, `failures=`, `errors=` from the JUnit XML,
  not from stdout, and confirm the log does not show `:app:testDebugUnitTest FROM-CACHE` or `UP-TO-DATE`.
  Before treating any failure as real, re-run once: `doneBodyweightSetStepperShowsBW` is the known flake the brief
  names (closed connection pool, passes on a re-run). A failure in anything else is real — fix it without weakening
  an assertion, then run both gate runs again.
  Done when two consecutive cold gate runs each end `EXIT=0` with `failures=0 errors=0` in the XML. A gate run leaves
  no file in the repo, so record the two runs' counts, and anything that had to be re-run or fixed, in a commit body
  (`area: lowercase summary` message, e.g. `build: two cold gate runs pass back to back`; `--allow-empty` if no file
  changed) — a note the gate cannot read is invisible to it.
