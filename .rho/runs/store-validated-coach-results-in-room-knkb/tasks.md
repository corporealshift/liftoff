# Tasks

Run: store-validated-coach-results-in-room-knkb — "Store validated coach results in Room".
Base: `a58a9bd`. Six tasks, in order; each ends in a commit. Nothing here changes the Room schema.

Test names below are spelled exactly as `.rho/runs/store-validated-coach-results-in-room-knkb/verify.sh` spells them
(`t <method>`, matched by method name across every JUnit report, so the class and package are the implementer's
choice). All **13** are new — none exists in `app/src/test` today. `verify.sh` also class-checks 55 existing classes
(MissionManager's confirm/scrub/launch/land/Re-fly/rollover tests, the coach tests, export/import, every screen,
`GenerationDaoTest`, `FlightPlanDaoTest`, `SchemaExportTest`, …): those must keep running and passing, and none of
them needs a task.

The plan's Testing list asks for two cases `verify.sh` no longer names (a second plan replacing the one already on a
sortie; an outline for a past week or one that no longer fits the pattern). They are the plan's Decisions that go
beyond the brief, so they are not gate-required — write them anyway, in the task that owns the operation they test.

- [x] 1. The generation row helpers on `GenerationDao`
  The brief's third heading, and the only task nothing else depends on. `GenerationDao` today has only the CRUD
  export/import needed, so this is new `@Query` updates and two lookups on the interface: mark RUNNING; mark
  SUCCEEDED / FAILED / CANCELLED (the last three stamp `finishedAt`, FAILED also keeps its error text); save the
  session id and `lastEventId`; bump `attempt` (counts up from 0, not a number the caller passes); find the newest
  non-cancelled generation for a sortie, and for a Mission. Newest is by `createdAt`, tie-break `id` — the table has
  no activity column and the brief forbids a schema change. Cancelled rows are excluded outright, not ranked last.
  The Mission lookup returns only that Mission's own `OUTLINE` generations, never its sorties' `FLIGHT_PLAN` rows.
  Files: `app/src/main/java/com/liftoff/app/data/GenerationDao.kt`; extend
  `app/src/test/java/com/liftoff/app/data/GenerationDaoTest.kt` (same Robolectric in-memory shape it already has).
  Tests it writes:
  `markingAGenerationRunningSucceededFailedOrCancelledSetsItsStatusAndFinishedAt`,
  `savingTheSessionIdAndLastEventIdAndBumpingTheAttemptUpdatesTheGenerationRow`,
  `theNewestNonCancelledGenerationForASortieIgnoresCancelledRows`,
  `theNewestNonCancelledGenerationForAMissionIsItsOwnOutlineGeneration`.
  Done when: `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests '*GenerationDaoTest*'` reports those
  four names plus the existing `insertGetUpdateDelete` in that class's XML with `failures="0" errors="0"`, and
  `--tests '*SchemaExportTest*'` in the same invocation is still green (it is what proves no schema change happened).

- [x] 2. `Valid` carries the validated JSON, and the fixture loader becomes shared
  Two small prerequisites the store operations need, in one commit.
  (a) `CoachReplyResult.Valid` gains the JSON text of the object the validator accepted, which `validate` already has
  in hand through `extract(raw).document`. The caller's raw reply — which can hold a code fence or prose — is never
  what gets stored, so `lift-plan-valid.txt` and `lift-plan-fenced.txt` must yield the same text. `validate` is the
  only constructor call, and today's tests read only `.plan`, so nothing else moves. `coach` gains no Android import.
  (b) Lift the fixture loader out of `CoachReplyFixtureTest`, where `fixture` / `validPlan` / `errorsOf` and the
  context constants are `private`, into a shared helper the data tests can use too (`coach` and `data` are the same
  Gradle module). `CoachReplyFixtureTest` then uses the shared loader; its `COVERED` set and
  `everyFixtureFileIsCoveredByATest` stay as they are.
  Files: `coach/CoachModels.kt`, `coach/CoachReplyValidator.kt`, `app/src/test/java/com/liftoff/app/coach/` (new
  shared loader), `coach/CoachReplyFixtureTest.kt`.
  Tests it writes: one plain test that `Valid`'s JSON text is the accepted object for `lift-plan-valid.txt` and equals
  what `lift-plan-fenced.txt` yields, with no fence markers or prose around it.
  Done when: a cold run of `--tests '*CoachReplyFixtureTest*' --tests '*CoachReplySemanticTest*' --tests
  '*CoachReplyExtractionTest*' --tests '*CoachSchemaFilesTest*' --tests '*CoachSchemaLoadTest*' --tests
  '*SchemaChecker*' --tests '*CoachPrompt*' --tests '*ExerciseNamesTest*'` (all in one invocation) reports 0 failures
  and `everyFixtureFileIsCoveredByATest` still passing.

- [ ] 3. The coach plan → `FlightPlanDraft` mapping, and the flight-plan store operation
  The brief's second heading, happy paths. A new file beside `ReFlyCopy.kt` maps a `CoachLiftPlan` or `CoachRunPlan`
  to a `FlightPlanDraft` with `source = GENERATED` and `rawJson` = the validated JSON from task 2 — for a lift: title,
  estimated minutes, warmup, notes, exercises with their equipment keys passed straight through to
  `ExerciseDraft.equipmentIds`, rest and sets; for a run: the coach's own title, kind, target distance and pace, and
  segments. The operation on `MissionManager` hands that draft to the existing `FlightPlanDao.writePlan`, which
  already deletes the sortie's current plan, resolves exercise names through `ExerciseDao.resolve`, and writes
  exercises, sets and segments in reply order; then it moves the sortie with `markPlanned` **only when it was
  PENDING**. It returns an outcome naming the result (stored, or refused with the reason) rather than throwing — the
  caller is a background worker that has to branch on it. A coach plan of the wrong type for the sortie is a caller
  bug and throws (`require`), because the validator already rejected that mismatch.
  Fixture setup, from the plan: the lift fixture plans the sortie at pattern position 1, so that one has to be made
  current and PENDING — scrub sortie 0 through the manager, or insert the ACTIVE mission's sorties with sortie 0
  already SCRUBBED. The run fixture plans position 0, and `settingsStore.setGenerateRunPlans(true)` is what leaves
  that current run sortie PENDING instead of written a simple plan. Fixed clock `2026-01-12T00:00:00Z` with
  `zone = { ZoneOffset.UTC }`, `MissionManagerTest`'s shape, so the tests behave the same on the CI runner in UTC.
  Files: new `data/CoachPlanCopy.kt`; `data/MissionManager.kt`; new
  `app/src/test/java/com/liftoff/app/data/MissionManagerPlanTest.kt`.
  Tests it writes:
  `aValidatedLiftPlanIsStoredWithItsExercisesAndSetsAndTheSortieBecomesPlanned`,
  `aStoredCoachPlanIsMarkedGeneratedAndHoldsTheValidatedJson`,
  `aValidatedRunPlanIsStoredWithItsKindTargetDistancePaceAndSegments`.
  Also write the plan's replacement case (a second plan for the same sortie replaces the one already there) — not
  gate-named, but the plan asks for it.
  Done when: a cold `--no-build-cache` run of `--tests '*MissionManagerPlanTest*'` reports those three names present
  with `failures="0" errors="0"`, and `--tests '*FlightPlanDaoTest*' --tests '*ExerciseResolverTest*'` in the same
  invocation is still green.

- [ ] 4. The flight-plan refusals: a stale result changes nothing
  The other half of the brief's second heading, on the operation task 3 added. A valid plan offered for a sortie of
  the right type that is **not** the Mission's current one, and for a sortie that is IN_FLIGHT, LANDED or SCRUBBED
  (the current sortie once launched; a sortie already landed or scrubbed). Each is refused, names its reason in the
  returned outcome, and writes nothing: the test reads the mission, the sorties and the plans back and requires them
  untouched, so every guard has to run before the first write — `writePlan` is itself a transaction nested in the
  manager's, and it deletes the sortie's existing plan.
  Files: `app/src/test/java/com/liftoff/app/data/MissionManagerPlanTest.kt`, and `data/MissionManager.kt` only if a
  guard is missing or fires after a write.
  Tests it writes:
  `aPlanForASortieThatIsNoLongerCurrentChangesNothing`,
  `aPlanForASortieThatIsInFlightLandedOrScrubbedChangesNothing`.
  Done when: a cold run of `--tests '*MissionManagerPlanTest*'` shows task 3's three names plus these two in that
  class's XML with `failures="0" errors="0"`, and each refusal test asserts the mission, the sorties and the plans read
  back unchanged — not only that the outcome names the reason.

- [ ] 5. The outline operation: storing an outline activates the Mission
  The brief's first heading. On `MissionManager`, beside `confirm`: guard, insert one sortie per pattern letter in the
  pattern's own order — each taking `focus` and `focusRationale` from the outline entry at the same index instead of
  `confirmWithoutOutline`'s two hardcoded defaults — write `Mission.outlineNotes`, then reuse the private `advance` so
  sortie 1 becomes current. `advance` is also what writes the simple 'Run' plan (`source = SIMPLE_RUN`, title "Run",
  the outline's own run focus as its notes) when run generation is off; the `AwaitGeneration` branch stays empty
  because nothing queues a generation in this run. Refusals come back as the returned outcome, not an exception: the
  Mission is no longer a DRAFT, its week has ended, or the outline no longer matches the Mission's pattern (a draft's
  pattern can still be edited). The mapping from outline to sorties lives in `data`; no file in `domain` changes.
  Fixture: `outline-valid.txt` against the `OUTLINE_CONTEXT` (pattern `RLRLR`, 60-minute sorties), run generation off.
  Files: `data/MissionManager.kt`, `data/CoachPlanCopy.kt` if the outline mapping sits there; new
  `app/src/test/java/com/liftoff/app/data/MissionManagerOutlineTest.kt`.
  Tests it writes:
  `theStoredOutlineActivatesTheMissionWithItsSortiesFocusesAndNotes`,
  `theStoredOutlineMakesSortieOneCurrentAndLeavesTheMissionOpen`,
  `aRunSortieStillGetsTheSimpleRunPlanWhenRunGenerationIsOff`,
  `anOutlineStoredForAMissionThatIsNoLongerADraftChangesNothing`.
  Also write the plan's two further refusals (a past week, a pattern edited after the outline was validated) — not
  gate-named, but the plan asks for them.
  Done when: a cold run of `--tests '*MissionManagerOutlineTest*'` reports at least those four names with
  `failures="0" errors="0"`; the Mission reads ACTIVE with all five sorties present in pattern order and only the first
  moved on from PENDING; and
  `--tests '*MissionManagerTest*' --tests '*MissionManagerReFlyTest*'` in the same invocation is still green.

- [ ] 6. The whole gate, run once
  Nothing new to write: the cross-cutting check no per-class gate covers — `MissionManagerTest`'s rollover, the coach
  tests, export/import, every screen, and `SchemaExportTest`.
  Run `bash .rho/runs/store-validated-coach-results-in-room-knkb/verify.sh` detached (`mkdir -p app/build` first,
  `nohup … > app/build/verify.log 2>&1; echo EXIT=$? >> app/build/verify.log`) and wait for its `EXIT=` sentinel; do
  not start a second run while one is going. Do not edit `verify.sh`. The known flake
  (`doneBodyweightSetStepperShowsBW`, a closed Room pool) can fail a green tree; the script re-runs the gate for it, so
  a failure there is not a break — a failure anywhere else is, and is reported rather than patched over.
  Done when the log ends `BRIEF DONE.` / `EXIT=0`. Record the evidence in the commit body — the gate command, the
  `EXIT=` code, the `:app:testDebugUnitTest` line showing it executed rather than FROM-CACHE/UP-TO-DATE, and the
  suite totals against the untouched baseline of 72 classes / 558 tests / 0 failures — as an empty commit
  (`git commit --allow-empty`) if the run turned out to need no code change. Keep the log under `app/build/`, which is
  gitignored.

## Notes for whoever does these

- The plan's own order maps onto these tasks as: step 1 → task 1, step 2 → task 2, step 3 → tasks 3–4, step 4 → task
  5, step 5 → task 6.
- Dependencies: tasks 3, 4 and 5 read their fixtures through the shared loader task 2 lifts out, and store
  `rawJson` from `Valid`, so 2 has to land before them. Task 4 tests the guards on the operation task 3 adds. Task 1
  is independent of all of them.
- `verify.sh` was rewritten after `map.md` was first written (`a58a9bd` "run: verify.sh rewritten by claude review"):
  it now names **13** tests, not 15 — `anOutlineForAPastWeekOrOneThatNoLongerFitsThePatternIsRefusedAndChangesNothing`
  and `aSecondPlanForTheSameSortieReplacesThePlanAlreadyThere` were dropped as decisions the owner reviews in the pull
  request. `map.md` has been corrected to match.
- Commit messages follow CLAUDE.md: `area: lowercase summary`, LF endings, stage named files only, never `git add -A`.
  Never touch `verify.sh`, `app/schemas/`, or `com.liftoff.app.rho`.
