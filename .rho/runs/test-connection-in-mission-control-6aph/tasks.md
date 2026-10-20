# Tasks

Run: test-connection-in-mission-control-6aph — "Test connection in Mission Control".
Base: `842f3b7` (Copy the rho client into Liftoff, #30). Seven tasks, in order; each ends in a commit.

Test names below are spelled exactly as `.rho/runs/test-connection-in-mission-control-6aph/verify.sh` spells them
(`t <method>`, matched by method name across every JUnit report, so the class and package are the implementer's
choice). All ten are new — none exists in `app/src/test` today. `verify.sh` also class-checks
MissionControlScreenTest, MissionControlViewModelTest, SettingsStoreTest, EquipmentViewModelTest, DatabaseExportTest,
DatabaseImportTest, BackupRoundTripTest, BackupImportRoomTest, BackupViewModelTest, LiftoffShellTest, AppContainerTest
and every class under `com.liftoff.app.rho`: those must keep running and passing, none of them needs a task.

- [ ] 1. `ConnectionViewModel` and the five result rows
  The plain-Kotlin state holder for the action, modelled on `BackupViewModel` (no Android imports, no ViewModel
  superclass): a sealed state with Idle / busy / result and the message the card draws; the busy guard; the action
  that takes the screen's current draft (host, port text, port error, token); the `ConnectionOutcome` → row mapping.
  Files: new `app/src/main/java/com/liftoff/app/ui/control/ConnectionViewModel.kt`; new
  `app/src/test/java/com/liftoff/app/ui/control/ConnectionViewModelTest.kt` (plain JUnit 4, no Robolectric).
  Built from the `SettingsStore`, a `suspend () -> ConnectionOutcome` seam (the check seam the plan's Approach names —
  `DaemonConnectionCheck` is concrete, so the seam is the suspend call, not a fake of the class), the screen's scope,
  and a worker dispatcher defaulting to `Dispatchers.IO`. Liftoff's own protocol major comes from the copied client's
  `PROTOCOL_VERSION`, never a typed "1". Blank host/token are named from the trimmed draft fields, because
  `NotConfigured` does not say which field is blank. A `ProtocolMismatch` whose daemon version is empty, and a
  `Connected` with an empty daemon version, use the plan's unknown-version wording.
  Tests it writes:
  `aSuccessfulConnectionTestShowsTheDaemonsVersionAndTheProtocolVersion`,
  `aProtocolMajorMismatchShowsBothProtocolMajors`,
  `anUnreachableOrTimedOutDaemonShowsTheOfflineRow`,
  `aRefusedTokenShowsThatTheTokenWasRefused`,
  `missingHostAndMissingTokenEachSayWhichSettingIsMissing`.
  Done when: `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*ConnectionViewModelTest*'` reports
  those five names in that class's XML with `failures="0" errors="0"`, and
  `--tests '*DaemonConnectionCheckTest*'` in the same invocation is still green (the client copy is untouched).

- [ ] 2. What the action does before and while it runs
  The three behaviours the brief asks for that are not a message, tested over the same state holder from task 1.
  Files: `ConnectionViewModel.kt` (only what these three need: the guard, the awaited write of the draft host/port/
  token through the store before the seam is called, the worker dispatcher) and `ConnectionViewModelTest.kt`.
  The busy test holds the worker on a latch so the second call must meet the action while it is still busy — the
  check runs once and the state stays busy. The off-thread test is the BACKUP pattern: a single-thread worker
  dispatcher, asserted against the calling thread. The saved-first test hands the fields a host the store does not
  hold yet and reads the store from inside the fake check. An invalid port reports the port error and opens no
  socket; a blank field never reaches the seam.
  Tests it writes:
  `aSecondTapWhileTheTestIsRunningChangesNothing`,
  `theConnectionTestRunsOffTheCallingThread`,
  `theSettingsInTheFieldsAreSavedBeforeTheCheckRuns`.
  Done when: a cold run of `--tests '*ConnectionViewModelTest*'` shows all eight names in that class's XML with
  `failures="0" errors="0"`, and every wait in the class has a timeout.

- [ ] 3. The button, the CONNECTION card, and the wiring that makes it compile
  The Compose half of the action and every call site it changes, so `assembleDebug` and the existing screen tests
  pass with the button present. Files: new `ui/control/ConnectionSection.kt` (the "Test connection" button and the
  ink-ruled busy/result row, drawn the way `BackupSection` draws its row, with the muted note under the row holding
  an `Unreachable` reason); `ui/control/BackupSection.kt` (`BackupTextButton` goes `private` → `internal`, nothing
  else changes — `ui.theme` is not edited); `ui/control/MissionControlScreen.kt` (`ConnectionSection` inside the
  CONNECTION `SectionCard` below the token field, one new required parameter holding the check seam wrapped in the
  remembered view model, the tap passing the draft state, and the same row added to the preview copy of the
  CONNECTION card); `ui/LiftoffShell.kt` (passes `{ container.daemonConnectionCheck().check() }` — the only app
  caller); `app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt` (`launchScreen` gains the seam
  with a canned default answer, and `showsNoUnbuiltPlaceholders` is updated to assert the button is present instead
  of absent — the theme uppercases labels, so its `assertDoesNotExist("Test connection", ignoreCase = true)` fails
  once the button exists; say this in the commit body for the PR).
  Test it writes: `testConnectionButtonIsShownInTheConnectionSection`.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  '*MissionControlScreenTest*' --tests '*MissionControlViewModelTest*' --tests '*BackupViewModelTest*' --tests
  '*LiftoffShellTest*'` (all in one invocation) reports 0 failures, that name present, and every existing
  section-head `assertIsDisplayed` still passing at the `w411dp-h2800dp` qualifiers.

- [ ] 4. The row drawn from the state, and the busy look
  The brief's second Robolectric requirement, on the section task 3 added: a result row and the busy row drawn from
  the state the section is given, with no typing and no daemon. Files: `MissionControlScreenTest.kt` and, if the
  section needs one, a seam that lets the test hand it a chosen state (the plan's render-only shape: the state in
  `mutableStateOf`, set inside one `runOnIdle`, plus `key()`). The busy look is the plan's decision — the label
  stays, the button dims to Muted and is untappable, and the busy words sit in the ruled row. The seam holds on a
  `CompletableDeferred` that the test completes before it unmounts the screen; the screen is unmounted before
  `tearDown` closes the database.
  Test it writes: `aConnectionResultRowIsDrawnFromTheState`.
  Done when: a cold `--no-build-cache` run of `--tests '*MissionControlScreenTest*'` reports that name present and
  the whole class green (`failures="0" errors="0"`), with every wait a `waitUntil` with a timeout.

- [ ] 5. ARCHITECTURE.md's M1 row
  Line 80's status cell reads "Done — test connection comes later; export and import are built"; it must stop
  holding the connection test back while still saying the milestone is done. The cell is read as the 4th `|`-split
  field and split on `;` and on em/en dashes only, so a clause that names the connection test may not carry a
  deferral word (`later`, `to come`, `not built`, `not yet`, …). The plan's wording passes: "Done — test connection,
  export and import are built". File: `ARCHITECTURE.md` only.
  Done when: `bash -n` is clean and this prints the new cell with no deferral word in a clause naming the connection
  test: `awk -F'|' 'index($0,"| M1 |")==1{print $4; exit}' ARCHITECTURE.md`.

- [ ] 6. The whole gate, run once
  Nothing new to write: the cross-cutting check no per-class gate covers — the shell, the settings tests, the
  placeholder test whatever it is now called, and every class under `com.liftoff.app.rho`.
  Run `bash .rho/runs/test-connection-in-mission-control-6aph/verify.sh` detached (`mkdir -p app/build` first,
  `nohup … > app/build/verify.log 2>&1; echo EXIT=$? >> app/build/verify.log`) and wait for its `EXIT=` sentinel;
  do not start a second run while one is going. Do not edit `verify.sh`: if a check fails on something real, report
  it instead.
  Done when the log ends `BRIEF DONE.` / `EXIT=0`. Record the evidence in the commit body — the gate command, the
  `EXIT=` code, the `:app:testDebugUnitTest` line showing it executed rather than FROM-CACHE/UP-TO-DATE, and the
  per-class `tests=`/`failures=`/`errors=` totals — as an empty commit (`git commit --allow-empty`) if the run
  turned out to need no code change. Keep the log under `app/build/`, which is gitignored.

## Notes for whoever does these

- `verify.sh` was rewritten after `map.md` was written (`d95d34c`): it now names **ten** tests, not eleven —
  `theConnectionSectionShowsItIsWorkingWhileTheTestRuns` is gone, and the busy look is carried by
  `aConnectionResultRowIsDrawnFromTheState`. It also dropped the ButtonsTest / ThemePackageTest / InFlightScreenTest
  / MainActivityTest class checks. `map.md` has been corrected to match.
- The plan's own order maps onto these tasks as: step 1 → tasks 1–2, step 2 → task 3, step 3 → task 4, step 4 →
  task 5, step 5 → task 6.
- Commit messages follow CLAUDE.md: `area: lowercase summary`, LF endings, stage named files only. Never touch
  `com.liftoff.app.rho` or `verify.sh`.
