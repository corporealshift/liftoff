# Tasks — issue 68: In-Flight un-launch back to PLANNED

Six tasks, in this order. Each is one session and ends in its own commit. Task 3 needs task 2's action,
task 5 needs tasks 2 and 4, task 6 is last so the documents describe what actually shipped.

`verify.sh` is fixed and is the definition of done. It names **10 tests that do not exist yet**; each is
written by exactly one task below, under that exact name (spelling matters — `unlaunch`, one word). It also
requires 19 classes to have run with everything in them passing; they pass today, so they are the
must-not-disturb net. Baseline from this run's own gate run on this branch's code (re-read from
`app/build/test-results/testDebugUnitTest/TEST-*.xml` at the tasks step, so a work step need not re-measure it): **736 tests in 89 reports, 0 failures / 0 errors /
0 skipped**, cold with `--no-build-cache`. Scratch files live under `app/build/`; never put a scratch test
class under `app/src/test`. Do not touch `verify.sh`.

- [x] 1. The `IN_FLIGHT → PLANNED` transition in the domain
  `domain/Sorties.kt`: add `IN_FLIGHT to PLANNED` to `SortieTransitions.legalTransitions`, and an un-launch
  free function beside `launch` / `markPlanned` returning `copy(state = PLANNED, launchedAt = null)` —
  `markPlanned` leaves `launchedAt` alone, so reusing it is what this task must not do. `domain/SortiesTest.kt`:
  `legalTransitionsSucceed` gains the new pair; `illegalTransitionsAreRejected` must not claim it while still
  rejecting every pair it always rejected, so LANDED and SCRUBBED stay terminal. No manager or UI change.
  Done when: `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests "com.liftoff.app.domain.SortiesTest"`
  is BUILD SUCCESSFUL and that class's `TEST-*.xml` shows the new pair passing with no existing case removed,
  renamed or skipped. Commit.

- [x] 2. `MissionManager.unlaunch` and its guards
  `data/MissionManager.kt`: `suspend fun unlaunch(sortieId: Long)` sitting between `launch` and `land`, in one
  `db.withTransaction`: Mission ACTIVE → this sortie is the current one → its state is IN_FLIGHT → nothing
  recorded on **any** set of `db.flightPlanDao().getPlan(sortieId)` (a set counts as recorded when
  `status != OPEN` or any of `actualReps` / `actualSeconds` / `actualWeight` is non-null; read every exercise,
  skipped ones included). Then the domain copy from task 1 and `update`. Do **not** copy `launch`'s
  `requireNoneInFlight` — the target sortie is itself the one in flight, so that guard would refuse the only
  case the action exists for. No `advance`, no Generation row. Tests in `data/MissionManagerTest.kt`, named
  exactly:
  `unlaunchReturnsAnUntouchedInFlightLiftToPlannedWithLaunchedAtNull` — also carries the plan's Decisions: the
  plan, exercise and set rows byte-for-byte unchanged (same ids, same planned values, nothing added or
  removed); the same sortie still `currentSortie`; no Generation row written; a `Sortie.notes` and an exercise
  `userNotes` written beforehand read the same afterwards; a set created by `flightPlanDao.addSet` neither
  blocks the un-launch nor is removed.
  `unlaunchIsRefusedOnceAnySetIsRecorded` — via `checkSet`, `saveSetEdit`, `skipSet`, a set recorded on a
  *later* exercise, and `skipExercise` on a later exercise while the first card is still all OPEN (the plan's
  named risk); the row left IN_FLIGHT with `launchedAt` intact.
  `unlaunchIsRefusedForANonCurrentSortieOrANonActiveMission` — a non-current sortie of an ACTIVE Mission, and a
  DRAFT and a CLOSED Mission.
  `unlaunchIsRefusedForASortieThatIsNotInFlight` — a PLANNED and a PENDING current sortie are the cases that
  isolate the state check, because a LANDED or SCRUBBED sortie is never `currentSortie`.
  `anInFlightRunOrASortieWithNoChecklistIsUnLaunchable` — a RUN with a plan, a RUN with no plan row, and a LIFT
  whose plan holds no exercises, each back to PLANNED with `launchedAt` null.
  Assert a refusal the way this file already does (`try { manager.…; throw AssertionError(…) } catch
  (IllegalStateException) { }`), and snapshot the rows before the call to compare after.
  Done when: a cold `--tests "com.liftoff.app.data.MissionManagerTest"` run is green and its report lists all
  five names as passing. Commit.

- [x] 3. After un-launch: Launch and Regenerate still work on the same sortie
  Tests only, in `data/MissionManagerTest.kt`, against task 2's action — no production change expected, since
  `regenerateFlightPlan` already accepts a PLANNED current sortie of an ACTIVE Mission.
  `launchWorksAgainAfterUnlaunch`: on the same sortie id `manager.launch` succeeds again and the row is
  IN_FLIGHT with a non-null `launchedAt`.
  `regenerateFlightPlanIsAcceptedAfterUnlaunch`: `manager.regenerateFlightPlan` on the un-launched sortie does
  not throw, queues a `FLIGHT_PLAN` Generation for that sortie, and leaves it PLANNED. Use the fixture the
  existing regenerate tests use (`LaunchpadRegenerateTest.activeWeek()`: host + token + defaultPattern,
  `onAppOpen`, `continueWithoutOutline`, a LIFT current sortie so the run toggle is irrelevant) and read the
  queued row back the way `queuedRowEnqueued` does; the call's **return value is the ids it superseded**, empty
  when there were none. If either test exposes a gap in task 2's action, fix the action in this same commit.
  Done when: a cold MissionManagerTest run is green with both names in its report, and `git show --stat` lists
  only `MissionManagerTest.kt` (or that file plus `MissionManager.kt` if a gap was found). Commit.

- [ ] 4. The derived "can un-launch" flag
  `ui/inflight/InFlightState.kt`: the availability rule beside the free `needsLandConfirmation` (57), deciding
  all three loaded arms — `Ready`, `Run`, and `NoChecklist` (available: no sets, so nothing is recorded).
  Loading is the only arm without it. For `Ready` the rule must see every set of the plan: `Ready.progress` is
  already built over every exercise including a skipped one (`InFlightState.kt:222`), while `cards` is the arm
  that also carries the actuals — either way it must not stop at the active card. Nothing is needed in
  `InFlightStates.kt`: its combine already re-runs `deriveInFlight` on every plan change, so the flag appears
  and disappears live. Tests in `ui/inflight/InFlightStatesTest.kt`, named exactly:
  `unlaunchIsAvailableForAnUntouchedLiftAndForARun` and
  `unlaunchIsUnavailableOnceAnySetOfThePlanIsRecorded` — unavailable after `checkSet`, `saveSetEdit`, `skipSet`
  and `skipExercise` on a later exercise while the first card is all OPEN; available again after `uncheckSet` /
  `reopenSet` / `unskipExercise`, which restore status OPEN with null actuals.
  Done when: a cold `--tests "com.liftoff.app.ui.inflight.InFlightStatesTest"` run is green with both names in
  its report. Commit.

- [ ] 5. The action on the In-Flight screen, and the leave to the Launchpad
  `ui/inflight/InFlightViewModel.kt`: an un-launch action in the `land` (91) / `scrub` (152) shape — `if
  (left.get()) return; if (busy) return; busy = true; message.value = null;` → `scope.launch { try {
  missionManager.unlaunch(sortieId); leaveOnce(onDone) }` with the `CancellationException` rethrow, a
  `message.value = "Couldn't …"` on refusal, `finally { busy = false }`. No `enqueueQuietly`: un-launch queues
  nothing. `ui/inflight/InFlightScreen.kt`: thread the new closure through the three loaded `when (state)` arms
  (NoChecklist 81, Run 99, Ready 118) into the shared `Footer` (988), which draws it as an
  `UnderlinedTextButton` **only when the state says it is available** — absent, never dimmed — ordered Land,
  Back to Launchpad, Scrub; `RunContent.kt:87` passes the new argument too. `Loading` keeps its `BackFooter`
  (1018) unchanged. The label cannot be the bare word "Back": `assertFooterOffersLandAndScrub`
  (`InFlightScreenTest.kt:483`) rejects any node whose text is exactly BACK, ignoreCase, and
  `UnderlinedTextButton` upper-cases its label. No shell change — `onBack = nav.back()` already returns to the
  Launchpad tab.
  Test required by `verify.sh`: `unlaunchOnThisScreenLeavesOnlyOnce` in `ui/inflight/InFlightLeaveTest.kt` — the
  view-model harness (no Compose), the `AtomicInteger` leave counter reaching 1 and staying 1 under
  `withTimeout`, and the row PLANNED with `launchedAt` null. The plan also asks for the footer present/absent
  assertions in `InFlightScreenTest.kt` and the refusal-message and double-tap cases in
  `InFlightLandScrubTest.kt`; `verify.sh` names none of those, so they are extra coverage on top of the required
  name, and the existing footer assertions must still pass.
  Done when: the CI gate runs cold and green — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest
  --no-build-cache` — with `unlaunchOnThisScreenLeavesOnlyOnce` in the reports and every class `verify.sh`
  lists still passing. This task runs the whole suite rather than one class because a taller footer can break a
  fixed-`@Config`-height assertion in `InFlightScreenTest`, `LandScrubDialogTest` or `NoteDialogTest`. Commit.

- [ ] 6. DESIGN.md amendment and ARCHITECTURE.md
  `DESIGN.md`: a dated amendment as the **first** entry under "Amendments after approval" (newest first; the
  newest today is 2026-10-20) saying what changed, when and why — un-launch back to PLANNED while nothing on the
  sortie is recorded, `launchedAt` cleared, plan and notes untouched, no confirmation dialog, and the PLANNED
  card's existing Regenerate being the way to a different plan. Inline marks in §5.1 (add
  `IN_FLIGHT ──un-launch──► PLANNED` to the state diagram with the nothing-recorded condition) and §9 (the
  In-Flight action list); §6 is the prose list of In-Flight's actions and is worth a line too.
  `ARCHITECTURE.md`: the `com.liftoff.app.ui` package row (line 37) names In-Flight's actions — add this one.
  That file has no sortie-transition table, so nothing else in it changes. No code, no Gradle run.
  Done when: the amendment is the first entry in that section, §5.1's diagram carries the new edge with its
  condition, §9 lists the action, and `git show --stat` for this commit lists only `DESIGN.md` and
  `ARCHITECTURE.md`. Commit.
