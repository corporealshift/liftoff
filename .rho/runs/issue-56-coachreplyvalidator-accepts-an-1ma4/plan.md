# Plan

## Approach

The validator already reads and normalizes every exercise name for its duplicate pass, and every other "the coach said something the phone cannot store" case in this file is an error line rather than an exception. So the fix is one guard in that same place: when `ExerciseNames.normalize(name)` comes back empty, `liftErrors` adds `exercises[$position].name: '$name' has no letters or digits` and does not put that name in the duplicate map. Nothing else changes. `GenerationWorker` already turns any `CoachReplyResult.Invalid` error list into a repair prompt in the same session (§7.3's two repairs), so routing the symbols-only name through the existing invalid path is what gives it the repair the issue says it never got — the store's `IllegalArgumentException` stays as the backstop it is for callers that are not a coach reply.

## Files

- `app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt` — in `liftErrors`'s name branch, add the empty-normalized-name error line and keep such a name out of `firstByName`. No new file, no signature change.
- `app/src/test/resources/fixtures/coach/symbols-only-exercise-name.txt` — new: a full lift-plan reply, one exercise named `"!!!"`, everything else valid.
- `app/src/test/java/com/liftoff/app/coach/CoachFixtures.kt` — a `const val` naming the new fixture.
- `app/src/test/java/com/liftoff/app/coach/CoachReplyFixtureTest.kt` — a test asserting that fixture's exact single error line, and the new fixture added to the private `COVERED` set.
- `app/src/test/java/com/liftoff/app/coach/CoachReplySemanticTest.kt` — two inline cases: two symbols-only names, and a name whose letters survive surrounding symbols.

What each file already holds is in the map.

## Order

1. Add the guard in `liftErrors`: an empty normalized name gets its own line and is left out of `firstByName`. Build and every existing test stay green — no current fixture has a symbols-only name.
2. Add `symbols-only-exercise-name.txt`, its `CoachFixtures` constant, the `CoachReplyFixtureTest` test for its exact error list, and its entry in `COVERED`, in one step so the fixture-coverage test never sees an uncovered file. Gate: the three `CoachReply*Test` classes.
3. Add the two inline cases to `CoachReplySemanticTest`. Gate: that class plus `ExerciseResolverTest`, which must stay green unchanged.
4. Run the brief's gate — `:app:assembleDebug :app:testDebugUnitTest` — and read the totals.

## Testing

- A lift-plan reply with an exercise name `"!!!"` is rejected with exactly one line, `exercises[<position>].name: '!!!' has no letters or digits` — fixture test in `CoachReplyFixtureTest`; `errorsOf` asserts the exact list, so a `Valid` result or an extra line both fail.
- Two symbols-only names each get their own line and no `duplicates` line pairing them — inline test in `CoachReplySemanticTest`.
- A name that normalizes to a real exercise is still accepted (`"!!! squat !!!"` validates clean) — inline test in `CoachReplySemanticTest`, so the guard cannot drift into "reject anything holding punctuation".
- The store's guard is untouched: `ExerciseResolverTest.blankNameIsRejected` still passes unchanged. No new test for it.
- The repair path needs no new test: `GenerationWorkerTest` already covers `Invalid` → `CoachPromptBuilder.repairPrompt`, and the new line is just another entry in that error list.

## Risks

- Forgetting the new fixture in `CoachReplyFixtureTest`'s private `COVERED` set fails `everyFixtureFileIsCoveredByATest`; Order step 2 lands the file, the constant, the test and the `COVERED` entry together.
- If the empty-normalized name is left in `firstByName`, a second symbols-only name also earns a `duplicates` line. The inline test asserts the exact list, so that shows up as a failure rather than a silent extra line.
- The guard has to sit inside the branch that already has a string name, so an absent or non-string name is still reported once by the schema checker. The existing `schema-violation` fixture and `aTypeThatIsNeitherLiftNorRunIsReportedOnceByTheChecker` are the ones that would gain a line if it does not — running the coach classes catches it.
- The position in the message must be the same 0-based index every other exercise line uses, and the raw name quoted as the coach sent it, not the normalized one.
- No existing fixture holds a symbols-only name (checked across all 14), so the guard should change no existing assertion; if a coach test changes, the guard is wider than intended.
- `ExerciseDao.resolve` and `ExerciseResolverTest.blankNameIsRejected` stay as they are — the fix is at the validator, not at the store.
- The suite total is branch-relative; measure it on this branch before writing an expected count into `tasks.md`.

## Decisions

**The wording of the new error line.** The brief offers `exercises[$position].name: '$name' has no letters or digits`, and the plan uses it as written. The alternative is to borrow the store's sentence, `Exercise name cannot be blank or symbols-only: '$name'`. The validator's lines are repair-prompt text the coach reads, and every one of them names the path and quotes the raw value the coach sent (`exercises[3].name: 'bench  press' duplicates 'Bench Press' at exercises[0]`); the DAO's sentence states a storage rule the coach has no part in. The brief's wording is what the surrounding lines already sound like.

**What counts as "no letters or digits".** The check is exactly "`ExerciseNames.normalize(name)` returned empty", so `"!!!"`, `"   "` and `"– –"` are rejected, while `"!!! squat !!!"`, `"café"` and `"squat-1"` pass. The alternative is to reject any name holding a character outside letters, digits, spaces and `-`. The store keys each exercise on its normalized name, so a name that normalizes to a real one already names the exercise the coach meant; the failure in this issue is the name that normalizes to nothing.

**A garbled name that normalizes to something real stays accepted.** `"!!!squat!!!"` normalizes to `squat` and the store will create that exercise. The plan does not validate names against a list of known exercises. There is no such list to validate against: `ExerciseDao.resolve` creates a new exercise by design (§7.7), and a coach naming a new drill is a normal reply. Someone reading the result may notice a junk exercise row appearing, but rejecting names the phone has not seen would break the case that is supposed to work.

**Two symbols-only names each get their own line, not a duplicate line.** A reply with `"!!!"` at `exercises[0]` and `"???"` at `exercises[2]` produces the two "has no letters or digits" lines and no `duplicates` line pairing them. The alternative is to report all three. The validator's existing rule is that a value already rejected is named once — a `type` word the enum does not accept is not also compared with the pattern — and a `duplicates` line about two names that are not exercises at all tells the coach nothing about what to fix.

**The DAO's guard stays.** `ExerciseDao.resolve` keeps throwing for a blank or symbols-only name even though the validator now rejects those replies. The alternative is to drop it now the coach path cannot reach it. `resolve` is reached through `FlightPlanDao.writePlan`, which `MissionManager` also calls when re-flying a plan that is already stored, so the guard covers callers that are not a coach reply too; the issue is not that the guard exists but that nothing catches it on the coach path.
