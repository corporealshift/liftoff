CoachReplyValidator accepts an exercise name that normalizes to empty, then the store throws

`app/src/main/java/com/liftoff/app/coach/CoachReplyValidator.kt`, in the exercise loop near `firstByName.putIfAbsent(ExerciseNames.normalize(name), ...)`.

A name such as `"!!!"` passes the schema (`minLength: 1`) and the duplicate check. `ExerciseNames.normalize` keeps only letters, digits, spaces and `-`, so it normalizes to `""`. `ExerciseDao.resolve` then throws `IllegalArgumentException("Exercise name cannot be blank or symbols-only")` inside the store transaction. Nothing catches it, so the generation never gets a repair prompt.

**Fix:** add a validator error when `ExerciseNames.normalize(name)` is empty, e.g. `exercises[$position].name: '$name' has no letters or digits`. That sends it down the normal in-session repair path.

**Test:** add a fixture under `app/src/test/resources/fixtures/coach/` and a validator test that expects that error.

---
Found reviewing #52 at `9454f0f`. Add a test for the behaviour change and run the gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`) before opening the PR.

(From issue corporealshift/liftoff#56: https://github.com/corporealshift/liftoff/issues/56)
