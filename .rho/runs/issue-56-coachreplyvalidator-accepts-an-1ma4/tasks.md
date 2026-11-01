# Tasks

Four tasks, in the plan's Order. Task 1 is the only one that touches main source; tasks 2–4 are the tests the plan
names, and task 4 also runs the brief's gate. Every task ends in a commit; nothing is pushed and no PR is opened.

## Baseline on this branch (measured at `26c0b21`, untouched code)

Whole gate: **736 tests in 89 reports, 0 failures**, ~2.5 minutes cold. Do not use the recalled 737. Per-class counts
of the classes `verify.sh` checks, from those 89 reports:

| class | tests |
| --- | --- |
| `CoachReplyFixtureTest` | 16 |
| `CoachReplySemanticTest` | 12 |
| `CoachReplyExtractionTest` | 10 |
| `ExerciseNamesTest` | 8 |
| `ExerciseResolverTest` | 4 |
| `CoachPromptBuilderTest` | 15 |
| `GenerationWorkerTest` | 32 |

The four new tests add no new class, so the last task should read **740 tests in 89 reports, 0 failures**.

## How to run a gate

Detached, after `mkdir -p app/build`:
`bash gradlew.sh <tasks> > app/build/<name>-gate.log 2>&1; echo "EXIT=$?"` — never pipe the build through `tail`,
because the exit status after a pipe is `tail`'s and a FAILED build prints `EXIT=0`. Read the verdict from the log and
the counts from `app/build/test-results/testDebugUnitTest/TEST-*.xml`. Scratch files stay under `app/build/`. Never
`gradlew --stop`: it is machine-wide.

## The four names `verify.sh` looks for

They are new to this branch and must be spelled exactly as below, as `@Test fun <name>()` methods. `verify.sh` finds
them across every `TEST-*.xml` the run wrote, so which class each sits in is the implementer's call, but no task may
rename one or leave it to another task. The comment above each `t <name>` in `verify.sh` says what that test must
assert — read it before writing the test.

- Task 2 writes `symbolsOnlyExerciseNameFixtureIsRejectedWithItsOwnErrorLine`
- Task 3 writes `twoSymbolsOnlyExerciseNamesAreEachRejectedAndNotReportedAsDuplicates` and
  `aNameWithNoLettersOrDigitsIsRejected`
- Task 4 writes `aNameWhoseLettersSurviveSurroundingSymbolsIsStillAccepted`

---

- [x] 1. Reject an exercise name with no letter and no digit, in `liftErrors`
  Plan Order step 1. In `app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt`, inside `liftErrors`'s
  existing `if (name != null)` block (the one holding
  `firstByName.putIfAbsent(ExerciseNames.normalize(name), position to name)`, ~line 168): when the normalized name
  holds no letter and no digit, append `exercises[$position].name: '$name' has no letters or digits` to `errors` and
  do **not** put that name in `firstByName`. The check is "the normalized name contains no letter or digit", not
  "normalize returned empty" — `"---"` and spaces-only are rejected, `"!!! squat !!!"`, `"café"` and `"squat-1"` still
  pass. The position is the same 0-based index every other exercise line uses; the name is quoted raw as the reply
  sent it, not normalized. The guard stays inside that branch, so a name the schema checker already rejected is still
  reported once.
  Files: `CoachReplyValidator.kt` only. No new test, no fixture, no signature change, no doc change.
  Done when: `bash gradlew.sh :app:compileDebugKotlin` is green, and one cold run of
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests com.liftoff.app.coach.CoachReplyFixtureTest --tests com.liftoff.app.coach.CoachReplySemanticTest --tests com.liftoff.app.coach.CoachReplyExtractionTest --tests com.liftoff.app.coach.ExerciseNamesTest --tests com.liftoff.app.data.ExerciseResolverTest`
  reports 0 failures with the counts unchanged (16 / 12 / 10 / 8 / 4). No existing fixture holds a name without letters
  or digits, so no existing assertion may move — if one does, the guard is wider than intended. Commit.

- [x] 2. The symbols-only fixture, its constant, its test and its `COVERED` entry — in one commit
  Plan Order step 2, and the brief's "add a fixture under `app/src/test/resources/fixtures/coach/` and a validator
  test that expects that error". Add `app/src/test/resources/fixtures/coach/symbols-only-exercise-name.txt` as the
  15th fixture: a full lift-plan reply in the shape of `duplicate-exercise-names.txt` — same style, everything else
  valid against `CoachFixtures.LIFT_CONTEXT` (pattern `RLRLR`, the four active equipment keys, a 60-minute sortie so a
  75-minute budget) — with exactly one exercise whose `name` is the three characters `!!!`. Add its `const val` to
  `app/src/test/java/com/liftoff/app/coach/CoachFixtures.kt` alongside the other fixture names, a test in
  `app/src/test/java/com/liftoff/app/coach/CoachReplyFixtureTest.kt` that reads it through
  `CoachFixtures.errorsOf(<constant>, CoachFixtures.LIFT_CONTEXT)` and asserts the **exact** list, and that constant
  in that class's private `COVERED` set. All four in one commit: `everyFixtureFileIsCoveredByATest` fails any fixture
  no test reads.
  Test written, named exactly: `symbolsOnlyExerciseNameFixtureIsRejectedWithItsOwnErrorLine` — expects exactly one
  line, `exercises[<position>].name: '!!!' has no letters or digits`, where `<position>` is that exercise's 0-based
  index in the fixture. A `CoachReplyResult.Valid`, a second line, the normalized (empty) name in place of the raw
  one, or the store's own sentence "Exercise name cannot be blank or symbols-only" all fail it.
  Done when: one cold run of
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests com.liftoff.app.coach.CoachReplyFixtureTest --tests com.liftoff.app.coach.CoachReplySemanticTest --tests com.liftoff.app.coach.CoachReplyExtractionTest`
  reports 0 failures, `CoachReplyFixtureTest` now counts 17 tests with the new name present in
  `TEST-com.liftoff.app.coach.CoachReplyFixtureTest.xml`, `CoachReplySemanticTest` still 12 and
  `CoachReplyExtractionTest` still 10, and `everyFixtureFileIsCoveredByATest` passed in that same report. Commit.

- [x] 3. Two symbols-only names, and a name that is only hyphens or only spaces
  Plan Order step 3's first two cases, and the Decisions "Two symbols-only names each get their own line, not a
  duplicate line" and "What counts as 'no letters or digits'" as changed by review. Add two inline cases to
  `app/src/test/java/com/liftoff/app/coach/CoachReplySemanticTest.kt`, next to
  `duplicateExerciseNameNamesBothSpellingsAndBothIndexes`, built with that class's companion `liftPlan(exercises, …)`
  helper and its `errorsOf(raw, LIFT_CONTEXT)`, each asserting the exact `List<String>`:
  `twoSymbolsOnlyExerciseNamesAreEachRejectedAndNotReportedAsDuplicates` — one reply holding two exercises whose names
  hold no letter and no digit (for example `"!!!"` at one position, `"???"` at another) is Invalid with exactly two
  lines: each position's own `exercises[<n>].name: '<name>' has no letters or digits` line, in exercise order, and
  **no** `… duplicates '…' at exercises[<m>]` line pairing them; and `aNameWithNoLettersOrDigitsIsRejected` — an
  exercise named `---` (hyphens only, which `ExerciseNames.normalize` keeps, so the normalized name is not empty) is
  Invalid with exactly `exercises[<position>].name: '---' has no letters or digits`, and an exercise named with spaces
  only reads the same way. Asserting the exact list is what catches the guard leaving a rejected name in
  `firstByName`.
  Files: `CoachReplySemanticTest.kt` only.
  Done when: one cold run of
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests com.liftoff.app.coach.CoachReplySemanticTest --tests com.liftoff.app.data.ExerciseResolverTest`
  reports 0 failures, `CoachReplySemanticTest` now counts 14 tests with both new names present in its report, and
  `ExerciseResolverTest` still counts 4 with `blankNameIsRejected` and `duplicateNormalizedNameIsRejected` unchanged
  and green — the DAO's guard stays. Commit.

- [ ] 4. The guard's boundary case, then the brief's gate over the whole suite
  Plan Order step 3's last case and step 4. Add one more inline case to
  `app/src/test/java/com/liftoff/app/coach/CoachReplySemanticTest.kt`, named exactly
  `aNameWhoseLettersSurviveSurroundingSymbolsIsStillAccepted`: a lift-plan exercise named `"!!! squat !!!"`
  normalizes to `squat` and must validate as `CoachReplyResult.Valid`, with the parsed plan carrying that exercise
  under the name as the coach sent it. That class's private `errorsOf` asserts the reply *is* Invalid, so this case
  calls `CoachReplyValidator.validate(raw, LIFT_CONTEXT)` directly and asserts the `Valid` result and the parsed
  `CoachLiftPlan`. It is what stops the guard drifting into "reject anything holding punctuation".
  Then run the brief's gate once, cold and detached:
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` — move any old
  `app/build/test-results/testDebugUnitTest` aside with `mv` first, never `rm`, and read the totals out of the
  `TEST-*.xml` reports.
  Files: `CoachReplySemanticTest.kt`.
  Done when: `CoachReplySemanticTest` counts 15 tests with the new name present, and the whole-suite run reports
  **740 tests, 0 failures, 0 errors, 89 reports** (736 baseline + the four new tests). If the gate hangs or dies on a
  build task with no test failing, re-run the identical tree once — no `--stop`. If any other test fails, the guard is
  wider than the plan intended: fix the guard, not the test. Commit.

## Not a task

- The in-session repair path needs no new test. `GenerationWorkerTest` already has
  `anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds` and
  `afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing`, and
  `CoachPromptBuilderTest.repairPromptCarriesEveryValidatorErrorAndAsksForOnlyTheCorrectedJson` already runs a real
  validator over an invalid reply. `verify.sh` checks both classes whole, so they must stay green unchanged and no
  task edits them.
- `ExerciseDao.resolve`, `ExerciseResolverTest`, `ExerciseNames.normalize` and `ExerciseNamesTest` are not edited by
  any task. The fix is at the validator, not at the store.
- No doc change: `ARCHITECTURE.md`'s `com.liftoff.app.coach` row already names the validator and the reply fixtures,
  and invariant 8 already states the two in-session repairs.
