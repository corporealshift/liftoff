# Tasks — issue 86: run duration should allow setting seconds too

Seven tasks, in plan.md's order, each ending in its own commit. Task 4 is the hinge: `parseRunEntry`
gains its third text and that reaches the dialog, the screen and the view model at once, so the build
is red until all four move together.

`verify.sh` is this run's definition of done. It checks the CI gate, **9 named cases** and 15 existing
classes that must stay green (24 checks). On the untouched tree it is 17 ok / 8 FAIL: the eight named
cases that do not exist yet fail as "did not run at all", and
`InFlightRunTest.runLandWithDistanceAndDurationStoresBoth` already exists and passes. Ownership of
the eight new names: task 1 writes 1, task 3 writes 2, task 5 writes 3, task 6 writes 2 — plus task 2's
one extra render, which the plan asks for and verify.sh does not name.

**Case names are fixed by `verify.sh`, not by plan.md.** A case written in a class named differently
is never found. Only `MissionLandedDetailTest` is a new file; every other named case goes in the
existing file named below.

Gate on this machine (the committed `gradlew.sh` is Windows-only; `./gradlew` here is the POSIX
wrapper): `JAVA_HOME=$HOME/android-toolchain/jdk ANDROID_HOME=$HOME/android-toolchain/sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain`, or the run's
own `bash .rho/runs/issue-86-run-duration-should-allow-bq30/verify.sh` (run it, never edit it).
Reports land in `app/build/test-results/testDebugUnitTest/TEST-*.xml` — a `--tests` run leaves only
those classes' reports, so clear that directory before any run whose reports you read, and run the
whole gate once before reading them. Scratch tests and gate logs go under `app/build/`, never
`app/src/test`. Read `map.md` before writing: it records the traps.

- [x] 1. The recorded-duration formatter, with no callers yet
  `app/src/main/java/com/liftoff/app/ui/sortie/PlanFormat.kt` (119 lines) only, plus its test file:
  one formatter for a **recorded** duration — whole minutes plus whole seconds, a zero part dropped
  (`32 min`, `27 min 34 s`, `45 s`), the seconds taken through whole seconds **with rounding**, so
  `27.566666…` reads `27 min 34 s` and not `27 min 33 s`. `formatMinutes` (59) stays exactly as it
  is: it formats coach-authored plan segments (`InFlightState.kt:146`, `LandedState.kt:240`,
  `FlightPlanSection.kt:101`), which are planned values, not the owner's duration. Nothing calls the
  new one yet — tasks 2 and 3 wire it in.
  Tests: `PlanFormatTest.formatRunDurationMinutesAndSeconds` in the existing
  `app/src/test/java/com/liftoff/app/ui/sortie/PlanFormatTest.kt` (354 lines), covering the awkward
  values verify.sh lists: 32.0, `27.566666…`, 18.5, 27.75, 0.75.
  Done when: a run of `--tests "com.liftoff.app.ui.sortie.PlanFormatTest"` is BUILD SUCCESSFUL with
  that name in its report and the two existing `formatMinutes` cases (88, 94) still green. Commit.

- [x] 2. The Mission tab's landed run line, and the render that reads it
  `app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt` (346 lines): the run line at 251-259
  replaces `state.runMinutes?.let { "%.0f min".format(it) }` (254) with task 1's formatter — that
  `%.0f` is the visible bug, printing `19 min` for a stored 18.5. `LandedDetailView` (227) becomes
  `internal` so a test can read the line, the way `RunLandDialogContent` already is; leave
  `SortieDetailView` (185) private. `SortieDetailState.Landed` already carries the raw
  `runDistance`/`runMinutes` (`MissionState.kt:86-97`), so the render needs no database and no waits.
  Tests: new file `app/src/test/java/com/liftoff/app/ui/mission/MissionLandedDetailTest.kt` with
  `landedRunDetailShowsTheRecordedDurationInMinutesAndSeconds` — `3.2 mi · 18 min 30 s` where the line
  draws `19 min` today, still `32 min` for a whole 32.0, and the em dash for a null duration.
  **verify.sh does not name this case** (its header rules out a UI render of that screen); plan.md's
  step 2, its Files list and its Testing section ask for it, so write it here and keep it static — no
  Room, no `waitUntil`, no view model — so it does not join the flaky Compose classes.
  Done when: that class compiles and its one case passes, and `MissionStatesTest` (which seeds
  `runMinutes = 18.5` at 329-344 and asserts the raw state fields) is still green. Commit.

- [x] 3. The Landed history's TIME and the coach's History line
  `app/src/main/java/com/liftoff/app/ui/landed/LandedState.kt`: `recordedDuration` (245) uses task 1's
  formatter; the segments line (240) keeps `formatMinutes`.
  `app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt` (339 lines): `runLine` (172-179)
  prints the recorded duration the same way, through a **private helper written in `coach/`** —
  `coach/` imports only `data` and `settings` and never `ui` (ARCHITECTURE.md invariant 2), so the
  reading is deliberately written twice rather than imported. `number(Double?)` (311) stays for the
  distance and every other number.
  Tests: `LandedStatesTest.runDetailRecordedDurationReadsMinutesAndSeconds` in the existing
  `LandedStatesTest.kt` (555 lines) — 18.5 now reads `18 min 30 s` where it reads `18.5 min` today,
  and a whole 32.0 still reads `32 min` (the three existing assertions at 317, 332, 349 are all
  whole-minute, so none of them moves); `CoachPromptBuilderTest.historyLineWritesARunDurationInMinutesAndSeconds`
  in the existing `CoachPromptBuilderTest.kt` (721 lines) — a stored 27.566666… writes
  `  run: 3.1 mi in 27 min 34 s` where `number()` writes `27.566666666666666 min` today, and a whole
  28.0 still writes `  run: 3.1 mi in 28 min`. Reuse that file's `landed(...)` / `scrubbed(...)`
  history builders (640-675) and its `historyLines(prompt)` helper.
  **Edit no golden prompt file** under `app/src/test/resources/prompts/` (the run lines are at
  `outline.txt:44`, `lift-flight-plan-with-outline.txt:45`, `run-flight-plan.txt:45`, all
  `run: 3.1 mi in 28 min` from `runMinutes = 28.0`). If `CoachPromptGoldenFileTest` disagrees, the
  builder is wrong, not the fixture.
  Done when: both named cases pass and `CoachPromptGoldenFileTest`, `LandedStatesTest`,
  `CoachPromptBuilderTest` are green, task 2's `MissionLandedDetailTest` still passes, and
  `git status` shows no change under `app/src/test/resources/`. Commit.

- [x] 4. The entry: seconds through the parser, the card, the screen and the view model
  Main code, all four files in one commit, because the extra text reaches them at once:
  `ui/inflight/RunLanding.kt` (51 lines) — `parseRunEntry` takes the seconds text, `RunEntry.Valid`
  keeps one `minutes: Double?` that is now minutes + seconds/60, `RunEntry.Invalid` gains a
  `secondsError`, a `SECONDS_ERROR` text joins `DISTANCE_ERROR`/`MINUTES_ERROR` (15-16), and the
  above-0 rule moves from each field to the **combined total**: `parseAmount` (45) rejects `<= 0`, so
  a `0` in the minutes field has to stop being a rejection while `0` minutes with nothing in seconds
  is still refused with the duration error. Seconds are whole and 0-59; `90`, `60`, `-5`, `0.5` and
  text are refused, not rolled into minutes.
  Then `ui/inflight/RunLandDialog.kt` (123 lines) — a third `remember`ed blank field, label `Seconds`
  (Duration and Seconds share one `Row`, each half the card; `LabeledTextField` takes no `Modifier`
  and fills its own width — see map.md before laying the two out), numeric
  keypad, and `onLand` reporting three strings; `ui/inflight/InFlightScreen.kt:222`; and
  `ui/inflight/InFlightViewModel.kt`'s `landRun` (111-149) handing the combined minutes to
  `missionManager.land`.
  Also in this commit: the existing tests this breaks, updated deliberately and not relaxed.
  `LandScrubDialogTest.kt`'s `onLand = { _, _ -> }` lambdas (149, 153, 190), its field counts —
  `assertCountEquals(4)` at 170 and `assertCountEquals(2)` at 209 — and the vertical ordering in
  `runLandDialogShowsEachFieldErrorUnderItsOwnField` (208-221), which now has Duration and Seconds
  side by side on one row. `RunLandingTest`'s 16 two-argument `parseRunEntry` calls and the 10
  two-argument `vm.landRun(...)` calls in `InFlightRunTest` / `InFlightViewModelTest` must still
  compile: give the new parameter a default or pass it at each call site, but do not delete or rename
  an existing case.
  Tests: none new — task 5 writes the card's new case, task 6 the landing cases.
  Done when: `:app:compileDebugUnitTestKotlin` reports no `e:` lines and `RunLandingTest`,
  `LandScrubDialogTest`, `InFlightRunTest` and `InFlightViewModelTest` are green with the case counts
  they had before. Commit.

- [x] 5. The parser's seconds cases, and the Land card's Seconds field
  Tests only, against task 4's entry — no production change expected, and if a case exposes a rule
  task 4 left out, fix the rule in this same commit.
  Tests, in the existing files: `RunLandingTest.secondsOutsideZeroToFiftyNineAreRejectedWithTheSecondsError`
  — `90`, `60`, `-5`, `0.5` and `abc` each raise the seconds error and no other field's error, and a
  whole 0-59 beside minutes gives minutes + seconds/60 (27 min + 34 s → 27.566666…);
  `RunLandingTest.secondsWithNoMinutesIsValidAndZeroMinutesWithZeroSecondsIsRejected` — seconds with
  the minutes field blank or `0` is accepted as that fraction of a minute (45 s → 0.75), and 0 minutes
  with 0 seconds is still refused with the duration error;
  `LandScrubDialogTest.runLandDialogShowsSecondsBesideDurationAndItsErrorUnderItsOwnField` — a Compose
  render of `RunLandDialogContent` showing a Seconds field beside Duration on the same row, all three
  fields present and starting empty, and the seconds error drawn under the Seconds field and not under
  Distance or Duration. Feed it `parseRunEntry`'s own `RunEntry.Invalid`, the way the case at 178
  does; `@RunWith(AndroidJUnit4::class)` + `createComposeRule` are already on that class.
  Done when: a run of `--tests "com.liftoff.app.ui.inflight.RunLandingTest" --tests
  "com.liftoff.app.ui.inflight.LandScrubDialogTest"` is BUILD SUCCESSFUL with all three names in its
  report and every pre-existing case in both classes still green. Commit.

- [x] 6. Landing with seconds: the view model's refusal and the stored row
  Tests only, against task 4's `landRun`.
  Tests, in the existing files: `InFlightViewModelTest.landRunRejectsInvalidSecondsWithoutTouchingTheManager`
  — a good distance and duration with a refused seconds returns false, raises the seconds error alone
  (no distance or duration error), raises no `message`, calls its `onDone` zero times, and the sortie
  row is still `IN_FLIGHT` with both run fields null; model it on the two cases already there (610,
  636). `InFlightRunTest.runLandWithMinutesAndSecondsStoresTheCombinedDuration` — landing 27 minutes
  and 34 seconds leaves the sortie `LANDED` with `runMinutes` 27 + 34/60 read back out of `sortieDao`,
  the seconds stored and not rounded into the minutes; model it on `runLandWithDistanceAndDurationStoresBoth`
  (338-363), which verify.sh also names and which must keep asserting 5.2 mi and 31.0 min with the
  seconds field left blank.
  Done when: a run of `--tests "com.liftoff.app.ui.inflight.InFlightRunTest" --tests
  "com.liftoff.app.ui.inflight.InFlightViewModelTest"` is BUILD SUCCESSFUL with both new names and
  `runLandWithDistanceAndDurationStoresBoth` in its reports, and both classes are green whole. Commit.

- [ ] 7. The gate, and the brief read back against the tests
  Run `bash .rho/runs/issue-86-run-duration-should-allow-bq30/verify.sh` end to end and read its
  output line by line: 8 named cases plus the existing whole-minute one, `CoachPromptGoldenFileTest`,
  and the 14 neighbour classes (`SortieDaoTest`, `MissionManagerTest`, `DatabaseExportTest`,
  `DatabaseImportTest`, `BackupRoundTripTest` among them — the export, backup and import carry
  `runMinutes` as a number of minutes and must pass untouched; `BackupRoundTripTest:202` already
  round-trips 27.75). Then read each bullet of `brief.md` and each "Must show" comment above a named
  case against the case that now proves it, and write or fix whatever a bullet has no case for, in the
  file verify.sh names. Add no case verify.sh does not name. No Room migration, no schema bump, no
  `BackupDocument` change, no DESIGN.md edit (DESIGN.md §16, 810-811, keeps timers and GPS out of
  scope and this is still manual entry at landing). `LiftoffShellTest` and `InFlightScreenTest` are
  the recorded flaky classes: a failure there is retried by the script, not fixed here.
  Done when: `bash .rho/runs/issue-86-run-duration-should-allow-bq30/verify.sh` exits 0 printing
  `BRIEF DONE` with all 24 checks ok, `git status` is clean, and the commit message records any bullet
  that had to be chased down. Commit.
