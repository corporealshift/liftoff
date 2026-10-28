# Tasks — sortie generation status on the Launchpad, derived and live (no UI)

Six tasks, in the order plan.md §Order sets them; each ends in a commit. Test names below are spelled exactly as
`.rho/runs/sortie-generation-status-on-the-nwwr/verify.sh` spells them. All 18 names it requires that are new to this
branch are written
here, each by exactly one task; two more names are written that verify.sh does not check, because plan.md §Files and
§Testing ask for them. verify.sh's 19th `t` name, `exportedV1SchemaMatchesDatabase`, already exists and needs no task.

| test | task |
| --- | --- |
| `anOpenFlightPlanRowThatIsNotMarkedOfflineIsPlanning` | 2 |
| `anOpenRowCarryingTheOfflineMarkIsOfflineAndAFailedRowCarryingItIsFailed` | 2 |
| `aFailedRowReportsFailedWithItsReasonWordForWord` | 2 |
| `theValidatorErrorsStoredOnARejectedReplyAreSplitOutOfTheReasonInOrder` | 2 |
| `aFailedRowWithNoErrorTextStillReportsFailedWithAFallbackReason` | 2 |
| `aSortieWithNoGenerationRowAndAConfiguredCoachIsFailedWithAReason` | 2 |
| `aSortieWithNoGenerationRowAndABlankHostOrTokenIsNotConfigured` | 2 |
| `aSucceededOrCancelledNewestRowIsAnsweredAsIfThereWereNoRow` | 2 |
| `aPlannedSortieWithAnOpenRowIsRePlanning` | 2 |
| `aPlannedSortieWithAFailedOrSucceededRowOrNoRowIsIdle` | 2 |
| `theRhoSessionIdIsHandedOutWhenTheRowHasOneAndNoneWhenItDoesNot` | 2 |
| `aSourcePlanOffersReFlyWhenTheGenerationFailedIsOfflineOrUnconfigured` | 2 |
| `aSourcePlanDoesNotOfferReFlyWhileAGenerationIsOpen` | 2 |
| `noSourcePlanOffersReFlyUnderNoAnswer` | 2 |
| `oneSubscriptionToAPendingSortieFollowsItsRowFromPlanningThroughOfflineToFailed` | 5 |
| `aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt` | 5 |
| `theCardFollowsThisSortiesNewestNonCancelledRowAndNotACancelledOrAnotherSortiesRow` | 5 |
| `theCardDoesNotReEmitForAnotherSortiesRowAPlanForAnotherSortieOrAnUnchangedSetting` | 5 |
| `generationFlowFollowsTheNewestNonCancelledRowForOneSortie` (the twin's own proof; not verify-named) | 1 |
| the new rendered Re-fly case in `LaunchpadReFlyTest` (plan §Testing's last paragraph; not verify-named) | 4 |

Gate conventions (CLAUDE.md and the run's map): `bash gradlew.sh …`, never bare gradle; scratch scripts and logs under
`app/build/`, launched detached with the log and an `EXIT=$?` sentinel appended **without a pipe**; `mkdir -p app/build`
first; move `app/build/test-results/testDebugUnitTest` aside with `mv`, never `rm`; never `gradlew --stop`; every class
a check names runs in ONE invocation; re-run the identical tree once (no `--stop`) if the build kills the test task at
five minutes without writing any `TEST-*.xml`, and judge a detached run only from its own log. Baseline for the whole
suite on this branch is 704 tests in 84 report files. Mutation checks are a one-token edit to the **implementation**
file, kept as a byte copy under `app/build/` for restoring, with the mutant's log kept separate from the green run's.
Do not touch `verify.sh`. No doc file is touched: the brief asks for no doc change and plan.md says `ARCHITECTURE.md`
is not touched.

- [x] 1. The DAO's live read for one sortie
  Covers plan step 1. `app/src/main/java/com/liftoff/app/data/GenerationDao.kt`: a Flow twin of
  `getNewestNonCancelledForSortie` (76) — the same query over the same columns, with no `kind` filter (an OUTLINE
  row carries no `sortieId`), following the Flow precedent at 90; map.md records the query. Nothing calls it yet, so
  no other file changes and `app/schemas` stays byte-identical. Add one case
  to the existing `GenerationDaoTest` — `generationFlowFollowsTheNewestNonCancelledRowForOneSortie`, not named by
  verify.sh but the only direct proof the twin is the twin: it emits this sortie's newest non-cancelled row, ignores a
  CANCELLED row and another sortie's row, and emits again when a newer row for this sortie arrives.
  Done when: `bash gradlew.sh :app:compileDebugKotlin` reports `BUILD SUCCESSFUL`, and one cold detached
  `:app:testDebugUnitTest --no-build-cache --tests '*GenerationDaoTest'` reports `BUILD SUCCESSFUL` with 0 failures and
  that name present in the class's `TEST-*.xml`; `git status --short` shows nothing under `app/schemas`. Write the
  query, the gate command, EXIT and the XML totals into the commit body.

- [x] 2. The derivation and its plain JUnit cases
  Covers plan step 2. New `app/src/main/java/com/liftoff/app/domain/SortieGenerationStatus.kt` and new plain-JUnit
  class `app/src/test/java/com/liftoff/app/domain/SortieGenerationStatusTest.kt` (no Robolectric, no Room, no
  coroutine, no context). The derivation takes plain values — the newest row's status / error / offline mark / session
  id, or no row — plus `coachConfigured` and whether a re-fly source plan exists — and answers the status, the reason
  and the split-out validator lines, the session id, and whether §5.4 offers Re-fly. The draft's `CoachStatus` is not
  touched. Split the stored invalid-reply error at `"; "` with the `invalid output: ` lead taken off
  (`GenerationWorker.kt:266`), **not** one per line as the brief's sentence says. plan.md does not settle whether this
  is one function taking the card kind or two entry points: decide and record the choice in the commit body. Write
  these 14 tests, one call of the derivation each: `anOpenFlightPlanRowThatIsNotMarkedOfflineIsPlanning`,
  `anOpenRowCarryingTheOfflineMarkIsOfflineAndAFailedRowCarryingItIsFailed`,
  `aFailedRowReportsFailedWithItsReasonWordForWord`,
  `theValidatorErrorsStoredOnARejectedReplyAreSplitOutOfTheReasonInOrder` (three validator errors, worker's order),
  `aFailedRowWithNoErrorTextStillReportsFailedWithAFallbackReason`,
  `aSortieWithNoGenerationRowAndAConfiguredCoachIsFailedWithAReason`,
  `aSortieWithNoGenerationRowAndABlankHostOrTokenIsNotConfigured`,
  `aSucceededOrCancelledNewestRowIsAnsweredAsIfThereWereNoRow`, `aPlannedSortieWithAnOpenRowIsRePlanning`,
  `aPlannedSortieWithAFailedOrSucceededRowOrNoRowIsIdle`,
  `theRhoSessionIdIsHandedOutWhenTheRowHasOneAndNoneWhenItDoesNot`,
  `aSourcePlanOffersReFlyWhenTheGenerationFailedIsOfflineOrUnconfigured`,
  `aSourcePlanDoesNotOfferReFlyWhileAGenerationIsOpen`, `noSourcePlanOffersReFlyUnderNoAnswer`.
  Done when: one cold detached `:app:testDebugUnitTest --no-build-cache --tests '*SortieGenerationStatusTest'` is
  `BUILD SUCCESSFUL` with that class reporting 14 tests / 0 failures and all 14 names present. Then prove the cases
  bite: a one-token edit to the derivation that answers a FAILED "coach unreachable" row as offline, and one that
  offers Re-fly while a row is open — each reddening exactly the case named for it — restoring from the copy under
  `app/build/`. Write the gate command, EXIT, the totals and the two mutations into the commit body.

- [x] 3. Pending and Planned carry the status; the join appears only on those two arms
  Covers plan step 3's first half. `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt`: `Pending` and
  `Planned` each carry the derived status; `Pending.reFlyAvailable` becomes the derivation's §5.4 answer, so the
  PENDING arm feeds `chooseReflySource(current, reflyCandidates(...)) != null` in as an input instead of using it as
  the answer (191-198); the PLANNED-with-no-plan arm (171) and the arm `currentSortie` never returns (224) keep
  refusing Re-fly, and `Planned` (156) carries the status. `LaunchpadStates.derive()` (93): the ACTIVE arm works out
  `currentSortie` **before** it picks flows, so only the PENDING and PLANNED arms join the plan changes with this
  sortie's new generation Flow and the coach-configured setting, and end in `distinctUntilChanged()`; CLOSED,
  IN_FLIGHT and the no-current-sortie arm stay on their existing plain mapping of the plan changes exactly as they
  are (map: the four construction sites are the only ones that need updating, and no test constructs these two
  classes). No test is written by this task.
  Done when: `:app:compileDebugKotlin` is green and one cold detached `:app:testDebugUnitTest --no-build-cache` naming
  in ONE invocation `LaunchpadStatesTest`, `LaunchpadConfirmTest`, `LaunchpadReFlyTest`, `LaunchpadScrubTest`,
  `LaunchpadRegenerateTest`, `LaunchpadDraftControlsTest`, `LaunchpadDraftGuardsTest`, `LaunchpadCoachStatusTest`,
  `LaunchpadDraftStatusTest`, `CoachStatusTest`, `MainActivityTest`, `LiftoffShellTest` is `BUILD SUCCESSFUL` with 0
  failures. If a `reFlyAvailable` assertion flips, the test changes to §5.4 — the rule does not change to the test —
  and the commit body names it for the PR (the map records why none is expected to). Write which arms join, and any
  test updated, into the commit body.

- [ ] 4. The screen keeps compiling and stops telling the owner something untrue
  Covers plan step 3's second half and plan §Files' `LaunchpadScreen` row. `PendingActions` (365): Re-fly stays shown
  only when `pending.reFlyAvailable`, and "No earlier plan to re-fly." (376) is shown only when Re-fly is refused
  **and** the status is not planning — under failed, offline or not configured a refusal can only mean no source. No
  status line is drawn; that is a later run's, and the change only removes text (the class renders in a fixed
  `w411dp-h2000dp` window). Add one Compose case to `LaunchpadReFlyTest` (plan §Testing's last paragraph, not named by
  verify.sh): a source plan exists, host and token are set, and the current PENDING sortie has an open FLIGHT_PLAN row
  → neither "Re-fly" nor "No earlier plan to re-fly." is on screen, and "Scrub" still is. Its three existing cases
  keep their assertions (they never configure the coach and seed no generation rows).
  Done when: one cold detached `:app:testDebugUnitTest --no-build-cache` naming in ONE invocation
  `LaunchpadReFlyTest`, `LaunchpadConfirmTest`, `LaunchpadScrubTest`, `LaunchpadRegenerateTest`,
  `LaunchpadDraftControlsTest`, `LaunchpadDraftGuardsTest`, `LaunchpadStatesTest`, `MainActivityTest`,
  `LiftoffShellTest` is `BUILD SUCCESSFUL` with 0 failures and `LaunchpadReFlyTest` reporting 4 tests / 0 failures with
  the new name present. Then prove the line condition bites: a one-token edit to `LaunchpadScreen.kt` that shows the
  line while the status is planning, watching the new case go red, restoring from the copy under `app/build/`. Write
  the gate command, EXIT, the totals and the mutation into the commit body.

- [ ] 5. The live cards, one subscription at a time
  Covers plan step 4. New `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadSortieStatusTest.kt`, harness
  copied from `LaunchpadCoachStatusTest` (the map records it whole): `@Config(sdk = [34])`, in-memory Room with
  `allowMainThreadQueries()`, a real `SettingsStore` over its own file in `context.cacheDir`, a real `MissionManager`
  on `Clock.fixed(2026-01-12T00:00:00Z, UTC)` with `zone = { ZoneOffset.UTC }`, no WorkManager, no daemon, no Compose;
  one collector launched before the writes and left open, `at(step)`-staggered `createdAt`, every wait bounded,
  `db.close()` only after the collector is cancelled. Host and token are set unless the case says otherwise — with
  them set, `continueWithoutOutline` → `advance` already queues the QUEUED `FLIGHT_PLAN` row the case follows, and the
  week's current sortie must be a **LIFT** one (a RUN current sortie with `generateRunPlans` off is prepared with a
  plan instead and queues nothing). For a PLANNED current sortie use
  `manager.storePlan(sortie.id, CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT))`
  (`LaunchpadRegenerateTest.kt:359`). Write these four tests:
  **`oneSubscriptionToAPendingSortieFollowsItsRowFromPlanningThroughOfflineToFailed`** — QUEUED →
  `setCoachOfflineMark(id, Generation.COACH_OFFLINE)` → `markFailed(id, reason, t)`, three emissions from that one
  collector, same sortie id and index throughout, and the session id carried once `saveSessionId` has written it.
  **`aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt`** — the card still shows the same plan and now says
  re-planning. **`theCardFollowsThisSortiesNewestNonCancelledRowAndNotACancelledOrAnotherSortiesRow`** — this sortie's
  FAILED row, a newer CANCELLED row for it, a newer open row for another sortie: the card answers failed with this
  sortie's reason. **`theCardDoesNotReEmitForAnotherSortiesRowAPlanForAnotherSortieOrAnUnchangedSetting`** — each of
  those three produces no new emission in a bounded wait, then a new open row for this sortie does, proving the
  collector is still live.
  Done when: one cold detached `:app:testDebugUnitTest --no-build-cache` naming in ONE invocation
  `LaunchpadSortieStatusTest`, `LaunchpadStatesTest`, `LaunchpadReFlyTest`, `LaunchpadCoachStatusTest`,
  `LaunchpadDraftStatusTest`, `LaunchpadConfirmTest`, `LaunchpadRegenerateTest`, `LaunchpadScrubTest`,
  `LaunchpadDraftControlsTest`, `LaunchpadDraftGuardsTest` is `BUILD SUCCESSFUL` with 0 failures and
  `LaunchpadSortieStatusTest` reporting 4 tests / 0 failures with all four names present. Then prove the no-re-emit
  case bites by dropping `distinctUntilChanged()` from the joined arms, watching it go red, and restoring the file
  from the copy under `app/build/`. Write the gate command, EXIT, the totals and the mutation into the commit body.

- [ ] 6. The whole gate, once, and whatever it turns up
  Covers plan step 5. No new behaviour and no new test: this task runs the gate and fixes only what it names, in its
  own commit. One cold detached `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, from a
  scratch script under `app/build/`, re-run once on the identical tree if it hangs. Read the log for `FAILED` as well
  as summing the `TEST-*.xml` totals. What it confirms: 724 tests / 0 failures — the 704 baseline (map.md) plus the 20
  cases these tasks add (14 + 4 + the DAO case + the rendered Re-fly case);
  every class verify.sh §4 names with `c` ran green in that run; all 18 verify-named names read pass in that run's own
  reports; `exportedV1SchemaMatchesDatabase` passes; `app/schemas` is byte-identical to the tree this run started
  from, including the set of files under it.
  Done when: that run's log shows `BUILD SUCCESSFUL` with `EXIT=0`, `:app:testDebugUnitTest` executed rather than
  FROM-CACHE/UP-TO-DATE, 0 failures / 0 errors, and the 18 names present in the reports. If the gate turns up nothing
  to fix, commit the gate record anyway (`git commit --allow-empty`) with the gate command, EXIT, the
  executed-vs-cached line, the report totals, the class count, and — for the pull request — which existing Launchpad
  tests were updated to the §5.4 rule (or that none were) in the body. Do not edit or run `verify.sh`.
