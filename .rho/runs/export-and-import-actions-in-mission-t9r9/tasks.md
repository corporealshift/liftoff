# Tasks — Export and Import actions in Mission Control

Six tasks, in the plan's order; each ends in one commit (`area: lowercase summary`, named files staged,
LF endings — CLAUDE.md). Each builds on the ones above it.

`.rho/runs/export-and-import-actions-in-mission-t9r9/verify.sh` (committed at `cb49a9c`) is the definition of
done. It looks for **ten test names that do not exist yet**, matched by method name across every JUnit
report, so which class each sits in is the task's call. They are listed below, spelled exactly as verify.sh
spells them, each written by exactly one task: task 1 writes seven, task 2 one, task 5 two. Task 4 updates
the existing `showsEverySectionHeadInOrder`. No name is written twice, and no name is left to nobody.

Compile with `bash gradlew.sh :app:compileDebugKotlin`; gate one or two classes at a time with
`bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests <ClassName>` and read the verdict out of
`app/build/test-results/testDebugUnitTest/TEST-*.xml`, not out of `BUILD SUCCESSFUL`. Scratch for a detached
run goes under `app/build/` (`mkdir -p app/build` first).

- [x] 1. The backup view model and its plain tests
  Plan Order step 1. New `app/src/main/java/com/liftoff/app/ui/control/BackupViewModel.kt`: Idle / Busy (naming
  which action runs) / Succeeded / Failed, each carrying the message to show; the export and import actions
  taking a nullable stream opener (null = the picker was cancelled: return without touching state); refusal to
  start while work is running; the injected dispatcher; `ImportResult.Refused` shown with the data layer's
  message unchanged; an exception from the opener or the data call mapped to this app's own short wording;
  `CancellationException` rethrown. It also owns the name Export suggests for the file the picker creates —
  `liftoff-<local date>.json`, the date on the device's own zone, not UTC — in plain Kotlin, so a test can
  assert it without opening a picker (the plan puts the dated name only in the screen's `CreateDocument` call;
  task 4 reads it from here). No `android.*` imports, so plain JUnit can drive it with fake work and a named
  worker dispatcher; every latch wait has a timeout and the latch is released in `finally`.
  Files: new `BackupViewModel.kt`; new `app/src/test/java/com/liftoff/app/ui/control/BackupViewModelTest.kt`.
  Tests it writes: `backupWorkRunsOffTheCallingThread`, `exportShowsBusyThenThatItWasSaved`,
  `importShowsBusyThenHowMuchWasRestored`, `refusedImportShowsTheDataLayersMessageUnchanged`,
  `workThatThrowsBecomesAFailureRowNotACrash`, `aCancelledPickerChangesNothing`,
  `theDefaultExportFileNameCarriesTodaysLocalDate`. The plan's Testing section also asks for a second-action-
  while-busy test (`aSecondActionWhileWorkIsRunningChangesNothing`), holding the worker with a latch so the
  second call must hit the guard; verify.sh does not name it, but write it.
  Done when: `:app:compileDebugKotlin` passes and `BackupViewModelTest` runs green with `--no-build-cache`,
  its XML listing all seven names above as passing with 0 failures and 0 errors.

- [ ] 2. Prove the import action writes through Room
  The brief's "After a successful import, the other tabs show the restored data … make sure the import goes
  through Room, not around it", which verify.sh puts in its own section. Export from a seeded in-memory
  `LiftoffDatabase`, then run task 1's import action against a fresh in-memory database with the real
  `importDatabase` / `MissionManager` while a Room `Flow` of the kind the other tabs observe is being
  collected, and show that flow emits the restored rows — an import writing around Room leaves it silent.
  No Compose and no screen. Follow `app/src/test/java/com/liftoff/app/data/DatabaseImportTest.kt` for the
  fixture shape (Robolectric `@Config(sdk = [34])`, `Room.inMemoryDatabaseBuilder`, a fixed `Clock`,
  `TemporaryFolder`); `importDatabase` already writes through Room DAOs in one transaction and calls
  `onAppOpen()`, so the re-emission is what is being proved, not built.
  Files: one new test class under `app/src/test/java/com/liftoff/app/ui/control/`.
  Test it writes: `anImportThroughTheBackupActionReachesRoomsFlows`.
  Done when: that class runs green with `--no-build-cache` and its XML lists the test as passing.

- [ ] 3. The BACKUP card's content
  Plan Order step 2. New `app/src/main/java/com/liftoff/app/ui/control/BackupSection.kt`: the Export and Import
  rows drawn from the view model's state, the line saying settings and the daemon token are not in the file,
  the busy/result row, and the one disabled-capable underlined text button this screen needs — built from
  `ui.theme` pieces exactly as the plan's Risks spell it (`Box` + `clickable(interactionSource, indication =
  null, role = Role.Button, enabled = …)` + a `LiftoffType.textButton()` label underlined in `drawBehind` from
  the text baseline, `Ink` normally and `Muted` when disabled). Not `InkButton` wrapped with
  `semantics { disabled() }`, and not the big red Launch/Land look. Ink-ruled rows via `InkRuledListRow`;
  `SectionCard` and the screen's other `internal` helpers are already visible to a new file in `ui.control` —
  widen anything still `private` in the same commit. Labels are uppercased by the theme, so on-screen text is
  `EXPORT` / `IMPORT`. No stock M3 tonal surfaces.
  Files: new `BackupSection.kt` only.
  Tests: none named by verify.sh; this task is checked by the build.
  Done when: `:app:compileDebugKotlin` passes and `MissionControlScreenTest` is still green (nothing composes
  the new card yet).

- [ ] 4. Put the card on Mission Control and hand it the app's own database
  Plan Order steps 3 and 4 together, because the screen's new parameters and the shell that supplies them have
  to land in one commit for the app to assemble. `MissionControlScreen.kt`: `database` and `missionManager`
  parameters, the defaulted seam that lets a test supply its own `BackupViewModel`, built on the `scope` the
  screen is already given, the two SAF launchers (`CreateDocument("application/json")` with task 1's dated
  default name, stream opened in `"wt"` mode so an existing file is truncated; `OpenDocument` with
  `openInputStream`), a null Uri passing a null opener, the BACKUP card drawn last, after EQUIPMENT.
  `LiftoffShell.kt`: pass `container.database` and `container.missionManager` at the Mission Control call site
  (:111, where `container` is already in scope) — the same `LiftoffDatabase` instance the other tabs observe,
  not a second one. In the same commit, the edits the new UI forces in `MissionControlScreenTest.kt` so the
  class stays green: `launchScreen` passes the new parameters (a `MissionManager(database, store)` beside the
  in-memory database it already builds), `"BACKUP"` joins the heads list, and the
  `assertDoesNotExist("Export")` line in `showsNoUnbuiltPlaceholders` goes — that helper matches case
  sensitively, so it cannot be reworded.
  Files: `MissionControlScreen.kt`, `LiftoffShell.kt`, `MissionControlScreenTest.kt`.
  Test it updates: `showsEverySectionHeadInOrder` (already exists) now lists `BACKUP`.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  com.liftoff.app.ui.control.MissionControlScreenTest --tests com.liftoff.app.ui.LiftoffShellTest` is green
  and the XML lists `showsEverySectionHeadInOrder` as passing.

- [ ] 5. The BACKUP card's screen tests
  Plan Order step 5, and the brief's Robolectric Done-when bullet. Add to the existing
  `app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt`. No test clicks Export or Import:
  a click opens a system picker that never arrives, so the work is driven by handing the screen's injected
  view model an in-memory stream. The injected dispatcher's work is not waited for by `waitForIdle()`, so wait
  for the row with `composeRule.waitUntil(timeoutMillis = …)`, then `waitForIdle()` and assert the row is still
  there — that is what "stays until the next action" means. Present-and-enabled is asserted, not present alone;
  for the disabled direction pair `assertIsNotEnabled()` with the still-enabled mirror in the same test, and
  release any parked fake before the test ends. Unmount the content before `tearDown` closes the database.
  Files: `MissionControlScreenTest.kt` only.
  Tests it writes: `backupShowsExportAndImportEnabledAndTheExclusionLine`,
  `successAndFailureRowsAreDrawnFromTheState`. Also the plan's step-5 test that both buttons are not enabled
  while work is running (`bothBackupButtonsAreDisabledWhileWorkIsRunning`) — verify.sh does not name it, but
  the plan's Decisions record the behaviour and the brief's "show that they are working" depends on it.
  Done when: `MissionControlScreenTest` runs whole and green with `--no-build-cache`, its XML showing 0
  failures and 0 errors and both names above listed as passing.

- [ ] 6. ARCHITECTURE.md, then the whole gate once
  Plan Order steps 6 and 7. `ARCHITECTURE.md`: M1's status cell (:80) becomes
  `Done — test connection comes later; export and import are built`; M5's cell (:84) drops its last two
  clauses (`the export and import data layer is built; its Mission Control actions come next`) and ends with
  `export and import are built`. Each cell needs a clause that names export and import, carries a built word,
  carries no deferral word, and does not narrow the claim to the data layer alone unless the same clause also
  credits the actions — that is verify.sh's `built_clause` / `deferred_clause`, splitting on `;` `,` `.` and
  both dashes. A deferral in a clause naming neither (M1's "test connection comes later") is fine. Then run
  the whole gate once, detached under `app/build/` with its log and an exit-status sentinel, and read the
  counts out of the JUnit XML; diagnose a hung run with `jstack` before touching code.
  Files: `ARCHITECTURE.md` only.
  Tests: none new.
  Done when: both cells pass verify.sh's clause check, and the detached gate log shows
  `:app:assembleDebug` and `:app:testDebugUnitTest` both succeeding with XML totals that have 0 failures and
  0 errors across the suite; put those totals in the commit body, since a note is invisible to the gate.
