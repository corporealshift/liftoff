# Plan

## Approach

The validator already reads and normalizes every exercise name for its duplicate pass. Every other "the coach said something the phone cannot store" case in this file is an error line, not an exception. So the fix is one guard in that same place. When `ExerciseNames.normalize(name)` has no letter or digit in it, `liftErrors` adds `exercises[$position].name: '$name' has no letters or digits` and does not put that name in the duplicate map. An empty result is the case the issue reports: the store's `IllegalArgumentException` comes from it. A result that is only hyphens and spaces is the other case (see Decisions). Nothing else changes. `GenerationWorker` already turns any `CoachReplyResult.Invalid` error list into a repair prompt in the same session (§7.3's two repairs). Routing the symbols-only name through the existing invalid path therefore gives it the repair the issue says it never got. The store's `IllegalArgumentException` stays as the backstop for callers that are not a coach reply.

## Files

- `app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt`: in `liftErrors`'s name branch (the `if (name != null)` block), add the no-letters-or-digits error line and keep such a name out of `firstByName`. No new file, no signature change.
- `app/src/test/resources/fixtures/coach/symbols-only-exercise-name.txt`: new. A full lift-plan reply with one exercise named `"!!!"`, everything else valid.
- `app/src/test/java/com/liftoff/app/coach/CoachFixtures.kt`: a `const val` naming the new fixture.
- `app/src/test/java/com/liftoff/app/coach/CoachReplyFixtureTest.kt`: a test asserting that fixture's exact single error line, and the new fixture added to the private `COVERED` set.
- `app/src/test/java/com/liftoff/app/coach/CoachReplySemanticTest.kt`: three inline cases. Two symbols-only names; a hyphens-only name (`"---"`); and a name whose letters survive surrounding symbols.

What each file already holds is in the map.

## Order

1. Add the guard in `liftErrors`. A normalized name with no letter or digit gets its own line and is left out of `firstByName`. Build and every existing test stay green, because no current fixture has such a name.
2. In one step, add `symbols-only-exercise-name.txt`, its `CoachFixtures` constant, the `CoachReplyFixtureTest` test for its exact error list, and its entry in `COVERED`. Doing them together means the fixture-coverage test never sees an uncovered file. Gate: the three `CoachReply*Test` classes.
3. Add the three inline cases to `CoachReplySemanticTest`. Gate: that class plus `ExerciseResolverTest`, which must stay green unchanged.
4. Run the brief's gate, `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, and read the totals.

## Testing

- A lift-plan reply with an exercise name `"!!!"` is rejected with exactly one line, `exercises[<position>].name: '!!!' has no letters or digits`. Covered by the fixture test in `CoachReplyFixtureTest`. `errorsOf` asserts the exact list, so a `Valid` result or an extra line both fail.
- Two symbols-only names each get their own line, and there is no `duplicates` line pairing them. Inline test in `CoachReplySemanticTest`.
- A hyphens-only name (`"---"`) is rejected with the same line. Inline test in `CoachReplySemanticTest`.
- A name that normalizes to a real exercise is still accepted: `"!!! squat !!!"` validates clean. Inline test in `CoachReplySemanticTest`, so the guard cannot drift into "reject anything holding punctuation".
- The store's guard is untouched. `ExerciseResolverTest.blankNameIsRejected` still passes unchanged. No new test for it.
- The repair path needs no new test. `GenerationWorkerTest` already covers `Invalid` → `CoachPromptBuilder.repairPrompt`, and the new line is just another entry in that error list.

## Risks

- `CoachReplyFixtureTest` has a private `COVERED` set. Forgetting the new fixture there fails `everyFixtureFileIsCoveredByATest`. Order step 2 lands the file, the constant, the test and the `COVERED` entry together.
- If a rejected name is left in `firstByName`, a second symbols-only name also earns a `duplicates` line. The inline test asserts the exact list, so that shows up as a failure rather than a silent extra line.
- The guard has to sit inside the branch that already has a string name, so the schema checker still reports an absent or non-string name only once. If the guard sits elsewhere, the existing `schema-violation` fixture and `aTypeThatIsNeitherLiftNorRunIsReportedOnceByTheChecker` would gain a line. Running the coach classes catches it.
- The position in the message must be the same 0-based index every other exercise line uses. The name must be quoted raw, as the coach sent it, not normalized.
- No existing fixture holds a name without letters or digits (checked across all 14), so the guard should change no existing assertion. If a coach test changes, the guard is wider than intended.
- `ExerciseDao.resolve` and `ExerciseResolverTest.blankNameIsRejected` stay as they are. The fix is at the validator, not at the store.
- The suite total is branch-relative. Measure it on this branch before writing an expected count into `tasks.md`.

## Decisions

**What counts as "no letters or digits".** The plan's check was exactly "`ExerciseNames.normalize(name)` returned empty". Under that check `"!!!"`, `"   "` and `"– –"` are rejected, while `"!!! squat !!!"`, `"café"` and `"squat-1"` pass. The alternative is to reject any name holding a character outside letters, digits, spaces and `-`. The store keys each exercise on its normalized name, so a name that normalizes to a real one already names the exercise the coach meant. The failure in this issue is the name that normalizes to nothing.
Changed by review: the plan chose "normalized name is empty"; the review chose "normalized name contains no letter or digit", because:
- The error line says "has no letters or digits", so the check should match its own words.
- Under the plan's check, `"---"` or `"- -"` passes the validator, and the store quietly creates an exercise row named `---`. Nobody using the app would expect that.
- The new check still rejects every empty case, so the store can no longer throw on the coach path.
- `"!!! squat !!!"`, `"café"` and `"squat-1"` still pass.

**The wording of the new error line.** The brief offers `exercises[$position].name: '$name' has no letters or digits`, and the plan uses it as written. The alternative is to borrow the store's sentence, `Exercise name cannot be blank or symbols-only: '$name'`. The validator's lines are repair-prompt text the coach reads. Every one of them names the path and quotes the raw value the coach sent, for example `exercises[3].name: 'bench  press' duplicates 'Bench Press' at exercises[0]`. The DAO's sentence states a storage rule the coach has no part in. The brief's wording is what the surrounding lines already sound like.

**A garbled name that normalizes to something real stays accepted.** `"!!!squat!!!"` normalizes to `squat`, and the store will create that exercise. The plan does not validate names against a list of known exercises, because there is no such list to validate against. `ExerciseDao.resolve` creates a new exercise by design (§7.7), and a coach naming a new drill is a normal reply. Someone reading the result may notice a junk exercise row appearing, but rejecting names the phone has not seen would break the case that is supposed to work.

**Two symbols-only names each get their own line, not a duplicate line.** Take a reply with `"!!!"` at `exercises[0]` and `"???"` at `exercises[2]`. It produces the two "has no letters or digits" lines and no `duplicates` line pairing them. The alternative is to report all three. The validator's existing rule is that a value already rejected is named only once: for example, a `type` word the enum does not accept is not also compared with the pattern. Also, a `duplicates` line about two names that are not exercises at all tells the coach nothing about what to fix.

**The DAO's guard stays.** `ExerciseDao.resolve` keeps throwing for a blank or symbols-only name even though the validator now rejects those replies. The alternative is to drop it now that the coach path cannot reach it. `resolve` is reached through `FlightPlanDao.writePlan`, which `MissionManager` also calls when re-flying a plan that is already stored. So the guard also covers callers that are not a coach reply. The issue is not that the guard exists but that nothing catches it on the coach path.
