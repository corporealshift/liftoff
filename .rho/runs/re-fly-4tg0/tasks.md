# Tasks — Re-fly (re-fly-4tg0)

Six steps in the plan's Order become five tasks: the docs row and the final cold whole-suite gate share
one commit, so the gate's evidence lands in a commit body. Each task ends with its own tests passing,
`:app:assembleDebug` compiling, and a commit of its named files only (`area: lowercase summary`).
Task 5 is the run's last commit; the runner pushes and opens the PR.

Read `.rho/runs/re-fly-4tg0/plan.md` (Approach, Files, Order, Testing, Risks, Decisions) and `map.md`
before writing any code. The test names below are exactly as `.rho/runs/re-fly-4tg0/verify.sh` spells
them; they are matched across every JUnit report, so class and package are the implementer's call.

Do them in order: 2 calls the chooser 1 defines, 3 reuses the candidate read 2 introduced, 4 renders the
field 3 added, and 5 is the run's gate over all of it.

- [x] Write the pure source chooser and its §13 cases
  Covers plan Order step 1: `app/src/main/java/com/liftoff/app/domain/ReFly.kt` (new) — the pure chooser
  and the candidate it weighs (a finished sortie plus whether it has a plan), no Android imports, no Room,
  no Compose. Ordering, the focus preference, the "most recent = the candidate's landed time" rule
  (Decision 1), blank-focus-is-no-match (Decision 2), null `landedAt` sorts oldest, and scrubbed /
  plan-less candidates excluded all live here, not in a caller. Test:
  `app/src/test/java/com/liftoff/app/domain/ReFlyTest.kt`, plain JUnit over hand-built candidates, in the
  fixture shape of `domain/SortiesTest.kt` / `SortiePlanningTest.kt`.
  Tests it writes (5): `reflyPrefersSameTypeAndFocusOverAMoreRecentDifferentFocusPlan`,
  `reflyFallsBackToTheMostRecentSameTypeWhenNoFocusMatches`,
  `reflyChoosesTheMostRecentlyLandedOfTheFocusMatches`,
  `reflyIgnoresScrubbedSortiesAndFinishedSortiesWithNoPlan`,
  `reflyReturnsNothingWhenNoLandedPlanOfThatTypeExists`.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache
  --tests 'com.liftoff.app.domain.ReFlyTest'` reports those 5 passing in
  `app/build/test-results/testDebugUnitTest/TEST-*.xml` (the filter is fully qualified so it cannot also
  pick up `MissionManagerReFlyTest`), and the commit names only those two files
  (e.g. `domain: re-fly source chooser`).

- [x] Copy the chosen plan through `MissionManager.refly`
  Covers plan Order step 2: `data/ReFlyCopy.kt` (new) turning a source `FlightPlanDetail` into a
  `FlightPlanDraft` (`source = REFLY`, plan fields unchanged, exercises' display names/equipment keys/rest
  seconds/coach notes kept, sets take planned `reps`/`seconds`/`weight` only with `added` sets dropped,
  segments copied, `rawJson = "{}"` as in `simpleRunPlan`); one new `MissionManager` operation `refly` in
  a single `withTransaction`, guarded exactly like `scrub`/`launch` (`check` → `IllegalStateException`):
  mission ACTIVE, sortie is `currentSortie`, sortie PENDING. It reads candidates through
  `SortieDao.getFinishedNewestFirst` + `FlightPlanDao.getTitlesForSortieIds` (filter to LANDED of the same
  type in Kotlin, chunk the ids — see the plan's Risks), calls the chooser from task 1, writes with the
  existing `writePlan`, moves the sortie with `markPlanned`, and never calls `advance()`. No coach or nabu
  call, so it is offline by what it reads.
  Test: `app/src/test/java/com/liftoff/app/data/MissionManagerReFlyTest.kt`, Robolectric + in-memory Room
  with `MissionManagerTest`'s fixture (fixed clock/zone, mission made ACTIVE, a source plan that really
  carries actuals, an added set and a skip), refusals asserted with try/catch on `IllegalStateException`.
  Tests it writes (8): `reflyCopiesPlannedValuesAndNotActuals` (also asserts the copy points at the same
  exercise ids, order, equipment, rest seconds and coach notes as the source, and carries the source's
  plan fields), `reflyDoesNotCopyAddedSets`, `reflyClearsSkippedFlagsAndUserNotes`,
  `reflyCopiesRunFieldsAndSegments`, `reflyMarksTheCopyReFlyAndMakesTheSortiePlanned` (source sortie still
  LANDED with its plan byte-for-byte unchanged), `reflyNeedsNoNetworkAndWritesNoGenerationRow`,
  `reflyRefusesASortieThatIsNotTheCurrentOne`, `reflyRefusesASortieThatIsNotPending`.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache
  --tests '*MissionManagerReFlyTest*'` shows those 8 passing, and the commit names only `ReFlyCopy.kt`,
  `MissionManager.kt` and that test file (e.g. `data: re-fly copies a landed plan onto the pending sortie`).

- [ ] Derive Re-fly availability into the Launchpad's PENDING state
  Covers plan Order step 3: `ui/launchpad/LaunchpadState.kt` (it holds both the sealed states and
  `LaunchpadStates.observe`/`derive`) — `LaunchpadState.Pending` gains whether a source plan exists;
  `derive` computes it in the PENDING branch only, through the same candidate read `refly` uses (share it
  so both ask the question the same way), and the two fallback branches that also build `Pending` — the
  PLANNED-with-no-plan-row one (around line 150) and the `else` one (around line 197) — pass false because
  `refly` would refuse those sorties. The real PENDING branch is around line 165. The history read stays
  out of the other branches: `derive` runs on every emission. No test builds a `Pending` by hand, so the
  new field only has to be supplied at those three call sites. The shared candidate read lives in `data/`
  (the plan puts it beside `refly`), so this task may have to widen or extract it — that file belongs in
  the commit too.
  Test: new cases in `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadStatesTest.kt`, seeded
  through the real flow as `pendingAfterScrub` does, every wait bounded by `withTimeout`, `job.cancel()`
  before `db.close()`.
  Tests it writes (2): `pendingOffersReFlyWhenALandedPlanOfTheSameTypeExists` (including the ordinary
  path: confirm, land the first sortie with a plan, read the PENDING state of the sortie that follows) and
  `pendingOffersNoReFlyWithoutAMatchingLandedPlan` (nothing ever landed with a plan; the only same-type
  finished sortie was scrubbed; the only landed plan is of the other type).
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache
  --tests '*LaunchpadStatesTest*'` shows those 2 passing plus every existing case in that class
  (`pendingAfterScrub`, `liveUpdateThroughStates` in particular), and the commit names only
  `LaunchpadState.kt`, `LaunchpadStatesTest.kt` and whichever `data/` file holds the shared candidate read
  (e.g. `launchpad: derive whether a re-fly source exists`).

- [ ] Put the Re-fly action and the muted line on the PENDING screen
  Covers plan Order step 4: `ui/launchpad/LaunchpadViewModel.kt` gains `refly()` with the same
  guard-and-catch shape as `confirmScrub`; `ui/launchpad/LaunchpadScreen.kt` — `PendingActions` renders
  `UnderlinedTextButton("Re-fly")` beside Scrub when the state says a source exists, and a short muted
  line in its place when it does not (Decision 5: no dead control; the words are the owner's review), and
  `ActionButtons` passes the new callback. One tap acts immediately — no confirmation dialog (Decision 3).
  Test: `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadReFlyTest.kt`, Robolectric, rendering
  `LaunchpadContent` with its own view model as `LaunchpadDraftControlsTest.kt` does, and `LaunchpadScrubTest.kt`'s
  poll-with-timeout helper.
  Tests it writes (3): `pendingScreenShowsReFlyBesideScrubWhenASourceExists`,
  `pendingScreenShowsAMutedLineAndNoReFlyWhenNoSourceExists`,
  `tappingReFlyCopiesThePlanAndMovesTheLaunchpadToPlanned` (the REFLY plan is written and the screen shows
  the Planned state with the copied title; the source plan in history is untouched).
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache
  --tests '*LaunchpadReFlyTest*'` shows those 3 passing, and the commit names only `LaunchpadViewModel.kt`,
  `LaunchpadScreen.kt` and that test file (e.g. `launchpad: offer re-fly on a pending sortie`).

- [ ] Mark Re-fly built under M5 and run the cold whole-suite gate
  Covers plan Order steps 5 and 6. First run the whole suite cold and detached — `bash gradlew.sh --stop`,
  `mkdir -p app/build`, then `nohup bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
  --no-build-cache > app/build/gate.log 2>&1 &` and `wait` on `EXIT=` — and fix whatever it breaks:
  `MissionManagerTest`, `LaunchpadStatesTest`, `LaunchpadScrubTest` and `LiftoffShellTest` in particular,
  plus the 24 must-not-break names in `map.md` (do not rename them). The known flake
  `doneBodyweightSetStepperShowsBW` (Room closed pool) can fail a run of an otherwise-green tree: re-run
  the same tree before treating it as broken. Then write `ARCHITECTURE.md`'s M5 status cell so
  `verify.sh`'s clause rules accept it: `| M5 |` at the line start, one status cell with no `|` inside it,
  clause delimiters `;` `.` and em dash (a comma does not split), a clause naming Re-fly carrying a built
  word (`built done shipped exists real complete wired in place available`), no clause naming Re-fly as
  missing, and export/import sitting in a clause that calls it missing (`not started`, `next`, `to come`,
  `no export/import yet`, …) so a bare "Done" fails. Also name the chooser in the `domain` package row and
  the Launchpad action in the `ui` row.
  Tests it writes: none — every name it needs already exists by now.
  Done when: the cold whole-suite log ends with `BUILD SUCCESSFUL` and 0 test failures, and the commit
  (staging `ARCHITECTURE.md` plus any files the gate forced you to fix) records the gate's test totals in
  its body, e.g. `docs: mark re-fly built under M5` (use `git commit -F` with the message file in the
  worktree root). Running `bash .rho/runs/re-fly-4tg0/verify.sh` — reading it, never editing it — is how
  this task knows the run is done: it re-runs the gate and every name above, and must print `BRIEF DONE`.

## Notes for every task

- `map.md` is the run's notes on the code; correct it and add to it as you read.
- Never touch `.rho/runs/re-fly-4tg0/verify.sh`; it is committed and only the runner may change it.
- Scratch files go under `app/build/` (a fresh worktree has none, so create it before a detached run).
- No Room schema change, no new columns. `FlightPlanSource.REFLY` already exists in `data/Enums.kt`.
- §2 vocabulary: "sortie", never "session". LF endings.
