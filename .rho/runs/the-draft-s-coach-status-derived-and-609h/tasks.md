# Tasks — the draft's coach status, derived and live from Room (no UI)

Four tasks, in order. Each ends in a commit. The plan's Order is followed; its steps 2 and 3
(`MissionManager`'s configured-ness Flow, then `Draft` carrying the status) are one task, because the
Flow has no other caller and the two only make sense together.

`verify.sh` names twelve tests, all new to this run. Tasks 1 and 3 write them; no other task writes a
test that `verify.sh` names. Read `map.md` before you start: it records the DAO read that already
exists, the harness to copy, and which names `verify.sh` deliberately does not check. Commit with
CLAUDE.md's `area: lowercase summary`, staging the named files only, never `git add -A`.

- [x] 1. The derivation and its status type, in `domain`, with plain JUnit
  Covers the whole pure half of the run: the sealed coach status the draft will carry (editable,
  planning, offline, failed-with-reason, not configured), each variant saying which actions it offers —
  Confirm only on editable, "Continue without outline" on planning/offline/failed/not-configured, Retry
  only on failed — and the plain derivation over a newest outline row's status, error and offline flag
  (or no row) plus one "coach is configured" Boolean. No Android or Room types in the signature: the
  `Generation` entity stays out, `GenerationStatus` from `data/Enums.kt` is fine. Follow the plan's
  Decisions (a SUCCEEDED row with blank settings is not configured; a FAILED or open row outranks the
  settings; a CANCELLED status is treated as no row; a FAILED row with no reason text gets a fixed
  neutral line). Files: `app/src/main/java/com/liftoff/app/domain/CoachStatus.kt` (new, beside
  `Coach.kt`'s `coachIsConfigured`, which the derivation must not call itself),
  `app/src/test/java/com/liftoff/app/domain/CoachStatusTest.kt` (new, plain JUnit like `MissionsTest`:
  no `@RunWith`, no Robolectric, no Room).
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `noOutlineRowWithAConfiguredCoachIsEditableAndOffersConfirmAlone`,
  `aSucceededOutlineRowWithAConfiguredCoachIsEditableAndOffersConfirmAlone`,
  `queuedAndRunningOutlineRowsArePlanningAndOfferContinueWithoutOutlineButNotConfirm`,
  `anOpenOutlineRowCarryingTheOfflineMarkIsOfflineRatherThanPlanning`,
  `aFailedOutlineRowReportsFailedWithItsReasonWordForWordAndOffersRetryAndContinueWithoutOutline`
  (assert the exact string, one worker-shaped reason and one validator-shaped reason with punctuation
  and inner spacing),
  `aBlankHostOrTokenWithNoOutlineRowIsNotConfiguredAndOffersContinueWithoutOutlineButNotConfirm`,
  `anOpenOutlineRowOutranksBlankSettingsAndIsPlanningOrOffline`.
  Also write the plan's four decision cases (CANCELLED as no row, FAILED with no reason text, FAILED
  outranking blank settings, SUCCEEDED with blank settings): `verify.sh` does not name them — the owner
  reviews those in the pull request — but the plan's Testing section asks for them.
  Done when: nothing in `main` reads the new code yet, `bash gradlew.sh :app:compileDebugKotlin
  :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.domain.*"` passes cold — the new class
  and the six existing domain classes, counts read from the `TEST-*.xml`. Commit only these two new files.

- [x] 2. Make the draft live: the configured-ness Flow, `Draft` carrying the status, the DRAFT arm's join
  Covers the wiring half, and the only step that can break something. `MissionManager` gains one public
  Flow answering whether the coach is configured — the `SettingsStore` it already holds, mapped through
  `coachIsConfigured`, `distinctUntilChanged` — so neither `LaunchpadStates` nor `LaunchpadViewModel`
  gains a constructor parameter and the six `LaunchpadViewModel(...)` test call sites do not move.
  `LaunchpadState.Draft` gains the derived status; the DRAFT arm of `LaunchpadStates.observe()`/
  `derive()` joins `GenerationDao.observeNewestNonCancelledOutlineForMission(missionId)` and that Flow,
  reads `isCoachOffline` off the row and hands the plain values to the derivation, and ends in
  `distinctUntilChanged` so a change that cannot change the answer emits nothing. `Planned`, `Pending`,
  `InFlight`, `Closed` and `Loading` keep today's behaviour exactly. No `onStart` placeholder for the
  inner join (the plan's risk list: it makes the draft flicker to editable between two real values).
  `LaunchpadScreen.kt` and `LaunchpadViewModel.kt` are expected to need no edit at all — the map
  confirms the screen never constructs `Draft` and reads only `weekStart` and `pattern`; any edit there
  is a compile-only fix and must not change what is drawn. No Room schema change, no entity change.
  Files: `data/MissionManager.kt`, `ui/launchpad/LaunchpadState.kt`.
  Tests it writes: none named by `verify.sh`; this task is proven by the compile and by the six
  Launchpad classes staying green untouched. Do not weaken or edit an existing Launchpad test to make it
  pass — if one fails, the wiring is wrong.
  Done when: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.launchpad.*"` passes cold with all six classes
  (`LaunchpadStatesTest`, `LaunchpadConfirmTest`, `LaunchpadDraftControlsTest`,
  `LaunchpadDraftGuardsTest`, `LaunchpadReFlyTest`, `LaunchpadScrubTest`) green and unedited, and
  `git diff` shows no change under `app/schemas`.

- [x] 3. The live draft through one subscription: `LaunchpadCoachStatusTest`
  Covers the Robolectric proof that task 2's join is live rather than re-derived per read. One
  subscription to `LaunchpadStates.observe()` for the current week's draft, collector started before the
  writes, every emission read out of that one collection, kept alive across them by a launched collector
  job with bounded `withTimeout` waits — not a chain of `.first()` calls, each of which re-subscribes. A
  second `observe()` or a fresh `.first` after a write proves nothing. Same harness as
  `LaunchpadStatesTest`: `@Config(sdk = [34])`, in-memory
  Room with `allowMainThreadQueries()`, a real `SettingsStore` over a
  `PreferenceDataStoreFactory` file deleted in `tearDown`, `MissionManager` on
  `Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)` with `zone = { ZoneOffset.UTC }`,
  no WorkManager, every wait bounded by the class's own `withTimeout` (the build kills a test task that
  goes five minutes without finishing). Seed the mission so the draft is the current week's, and set host
  and token unless the case says otherwise; a seeded `Generation` row must pass `createdAt` itself.
  File: `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadCoachStatusTest.kt` (new). If a case
  fails because the wiring is wrong — a missing `distinctUntilChanged`, a join that re-subscribes, a
  status that never reaches `Draft` — fix `LaunchpadState.kt` or `MissionManager.kt` in the same commit.
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `theDraftReportsPlanningWhenAQueuedOutlineRowIsWrittenToRoom` (first Draft editable, then one QUEUED
  OUTLINE row for that mission emits planning),
  `theDraftReportsOfflineWhenItsOutlineRowTakesTheCoachOfflineMark` (same row still QUEUED after
  `setCoachOfflineMark(id, Generation.COACH_OFFLINE)`),
  `theDraftReportsFailedWithTheExactReasonItsOutlineRowWasMarkedFailedWith` (`markFailed(id, reason, t)`
  → failed carrying that exact string),
  `theDraftDoesNotReEmitForAnotherMissionsRowAFlightPlanRowOrACancelledRow` (each write leaves the
  collected emissions unchanged within a bounded wait; the cancelled row is inserted already cancelled,
  not cancelled while the draft is showing it),
  `theDraftFollowsTheSettingsFromNotConfiguredToEditableWithoutReSubscribing` (blank host/token → not
  configured; a weight-unit edit emits nothing; filling host and token turns the same subscription's
  draft editable).
  Done when: `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.launchpad.*"` passes cold — the five new tests plus the six classes task 2 left
  green — with zero failures, and the run wrote a `TEST-*.xml` naming all five new tests.

- [ ] 4. The whole gate, run once, and any fallout it turns up
  Covers the brief's Done-when and the cross-cutting tests no per-class gate above ran. Run
  `.rho/runs/the-draft-s-coach-status-derived-and-609h/verify.sh` detached through a script under
  `app/build/` (`mkdir -p app/build` first; log plus an `EXIT=$?` sentinel with no pipe): it runs the
  whole-suite gate itself — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`,
  after moving `app/build/test-results/testDebugUnitTest` aside with `mv` — then looks up every name
  `verify.sh` lists in that run's reports. Read its log: the gate's own output, the report totals it prints
  (tests / failures / errors / skipped), and the ✓/✗ list. Never `rm` the results directory (a stranded test
  executor holds `binary/output.bin`) and never `gradlew --stop` (machine-wide, it takes down another
  worktree's daemon). If the gate is what fails, run the two Gradle tasks directly to see the failure on its
  own; a hang that writes no `TEST-*.xml` is the hang the brief warns about — move the results aside and
  re-run once, and if the same tree hangs twice find the worker JVM and `jstack` it rather than re-running a
  third time.
  Covers fixing what only a whole-suite run catches — a class outside the launchpad that this run's new
  `Draft` field or new Flow broke — and confirming nothing under `app/schemas` moved and
  `exportedV1SchemaMatchesDatabase` still passes. The known flake is `doneBodyweightSetStepperShowsBW`
  (`ui/inflight/InFlightScreenTest.kt`): red at random, green on a re-run of the same tree, so re-run
  before treating it as broken. Do not weaken or delete an existing assertion to get green.
  Done when: that detached run prints `BRIEF DONE` and exits 0, with the gate passing on some attempt and
  the report totals showing zero failures and zero errors. Commit any fix it forced; if none was needed, this
  task commits nothing and says so in its final report.
