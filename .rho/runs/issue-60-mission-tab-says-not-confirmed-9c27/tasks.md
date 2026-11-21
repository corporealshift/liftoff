# Tasks — issue 60: Mission tab says "Not confirmed yet" while the outline is generating

Six tasks, in the plan's order. Each one ends in its own commit. The gate that defines done for
the run is `bash .rho/runs/issue-60-mission-tab-says-not-confirmed-9c27/verify.sh`: the CI pair
(`:app:assembleDebug :app:testDebugUnitTest --no-build-cache`, whole suite green) plus five named
tests, all of them new `MissionStatesTest` cases. Tasks 4 and 5 write those five names between
them — 4 writes four of them, 5 writes the fifth; no name is left unwritten and none is written
twice. `verify.sh` is never edited. Baseline measured on the untouched tree: the gate is green at
**775 test records over 93 reports, 0 failures**; the five new names should take it to 780.

- [x] 1. Give the Mission tab's draft week a coach status
  Covers `app/src/main/java/com/liftoff/app/ui/mission/MissionState.kt`: the DRAFT arm of
  `MissionStates` joins `db.generationDao().observeNewestNonCancelledOutlineForMission(mission.id)`
  and `manager.coachConfigured` and derives through `domain/deriveCoachStatus` unchanged, in the
  `flatMapLatest` + flow-returning-`derive` shape `LaunchpadStates.observe`/`derive` already use
  (`LaunchpadState.kt` 96-136 is the copy to follow: the join and `distinctUntilChanged()` stay
  inside the DRAFT arm, ACTIVE and CLOSED become `flowOf(...)`/`map` of what they build today, and
  `observeSortie` is not touched). `MissionTabState.Week` gains that status as a field **with a
  default**, so `MissionTabStateTest.kt`'s `week(status)` helper (line 10) and `MissionTabPreview`
  (`MissionScreen.kt` ~270) still compile and the screen still draws the old line. No settings read
  outside the DRAFT arm.
  Done when: `:app:assembleDebug` compiles, the 12 existing `MissionStatesTest` cases and
  `MissionTabStateTest`'s one case pass unchanged, and the new field is reachable on a DRAFT week.
  No test name from `verify.sh` belongs to this task.

- [x] 2. Draw the coach line in the Mission tab's draft block
  Covers `app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt` 128-134: keep the
  `status == MissionStatus.DRAFT` gate, and inside it pick the line from the week's coach status
  instead of drawing one sentence for every draft — the Launchpad's planning line, its offline line,
  and for a failure the row's own reason in Red followed by one short muted pointer
  ("Retry or continue on the Launchpad."). "Not confirmed yet. Confirm this Mission on the
  Launchpad." stays for the statuses that mean nothing is in flight. No new buttons on this tab
  (DESIGN.md §9 gives it only Reset week); `canReset` and the Reset-week flow are unchanged.
  Done when: the tree builds, the whole unit-test suite still passes, and a draft with an open
  OUTLINE row no longer reads "Not confirmed yet" — this is where the reported bug is fixed. No
  Compose test of the drawn line is wanted here: the brief asks for a state test, and `verify.sh`
  names none, so the four and one cases in tasks 4-5 are what pin this.

- [x] 3. One copy of the planning/offline wording for both tabs
  Covers `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` 184-185: lift
  `PLANNING_LINE` and `OFFLINE_LINE` so `MissionScreen.kt` reads the same constants — the codebase's
  only shared coach string, `internal const val NOT_CONFIGURED_MESSAGE` in `LaunchpadViewModel.kt:18`,
  is the precedent for where such a constant lives. The PENDING sortie line's use of `OFFLINE_LINE`
  (249) must keep working, and the strings themselves must not change by one character:
  `LaunchpadDraftStatusTest` asserts them inline as literals and `LaunchpadPendingStatusTest`
  (347-348) keeps its own copies. If the lift turns out worse than restating them in
  `MissionScreen.kt`, take the restatement and record that choice in the run's `findings.md` so the
  PR can name it.
  Done when: both screens read one copy (or the fallback is recorded), and `LaunchpadDraftStatusTest`,
  `LaunchpadPendingStatusTest` and `LaunchpadPlannedActionsTest` pass unchanged.

- [x] 4. `MissionStatesTest`: the four draft-status cases
  Covers `app/src/test/java/com/liftoff/app/ui/mission/MissionStatesTest.kt` — Robolectric +
  in-memory Room, coach configured through `settingsStore.setDaemonHost`/`setDaemonToken`, OUTLINE
  rows written the way `LaunchpadCoachStatusTest` writes them (`Generation(kind = OUTLINE,
  missionId = …, status = QUEUED, createdAt = …)`, `generationDao.setCoachOfflineMark(row,
  Generation.COACH_OFFLINE)`, `generationDao.markFailed(row, reason, at)`). Assert the DRAFT week's
  coach status and its `offersConfirm`/`offersRetry`/`offersContinueWithoutOutline` flags.
  Writes these four tests, named exactly:
  `theMissionTabReportsPlanningWhileItsDraftHasAnOpenOutlineGeneration` (a QUEUED or RUNNING row —
  the brief's case), `theMissionTabReportsOfflineWhenItsOpenOutlineRowCarriesTheCoachOfflineMark`,
  `theMissionTabReportsFailedWithTheReasonItsOutlineRowCarriesAndOffersRetryAndContinue` (the row's
  reason word for word, and the DRAFT week's `canReset` still false — the tab gains no button),
  `theMissionTabKeepsTheUnconfirmedDraftStatusWhenNoOutlineGenerationIsOpen` (no OUTLINE row, and a
  draft whose only OUTLINE row is CANCELLED — both keep the status "Not confirmed yet" is drawn from).
  Done when: those four names appear as passing testcases in
  `app/build/test-results/testDebugUnitTest/TEST-*.xml` and the 12 existing cases still pass.

- [ ] 5. `MissionStatesTest`: the live-follow case
  Covers the same file, and the harness that case needs: open `states.observe()` **once** into a
  collected list before any write and read every emission out of that one collection, the way
  `LaunchpadCoachStatusTest`'s `openSubscription`/`awaitDraft` do — a second `observe()` or a fresh
  `.first` after a write re-subscribes and proves nothing.
  Writes this test, named exactly:
  `theMissionTabFollowsItsOwnOutlineRowFromQueuedToFailedWithoutReSubscribing` — on that one
  subscription the draft week reads unconfirmed, then planning once a QUEUED OUTLINE row is written
  for its own mission, then failed carrying the reason once that same row is marked FAILED.
  Done when: that name passes, and the test would fail if the DRAFT arm re-subscribed instead of
  following the row (it asserts new emissions out of the one collection, not a re-read).

- [ ] 6. Gate, sweep, findings
  Run `bash .rho/runs/issue-60-mission-tab-says-not-confirmed-9c27/verify.sh` from the repo root; it
  resolves the toolchain itself (`gradlew.sh` is Windows-only and is never edited) and retries only a
  dead build or a failure confined to `LiftoffShellTest`/`InFlightScreenTest`. Fix whatever it names;
  do not chase a flake in feature code. Then sweep for anything the change left behind — other
  readers of `MissionTabState.Week`, other places that draw the Mission tab's draft line, scratch
  files left under `app/src/test` (scratch belongs under gitignored `app/build/`). Write
  `.rho/runs/issue-60-mission-tab-says-not-confirmed-9c27/findings.md` with the gate numbers, the
  wording decision from task 3, and anything out of scope, and commit it even if the sweep found
  nothing — this task must leave a commit.
  Done when: `verify.sh` prints its "Issue 60 done" line, `git status` is clean, and `findings.md` is
  committed.
