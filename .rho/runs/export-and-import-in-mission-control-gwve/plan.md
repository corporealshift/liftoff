# Plan

## Approach

Bring the six commits over in the brief's order, then fix what actually failed in CI. The cherry-picks are the easy part. The source branch forked from `c183c1c` (PR #27, the export/import data layer), three commits below HEAD. The only commits HEAD has added since then are `.rho/runs/` bookkeeping. `git merge-tree` reports no conflicts, and five of the six patches apply cleanly on their own. So this is a straight transfer, not a merge, and no feature is written here.

The CI failure has already been diagnosed from its log, not guessed. The failed log for run `39288220848` can be read. Exactly one test of 539 failed: `LiftoffShellTest.systemBackFromATabGoesToLaunchpad`, with a `ComposeTimeoutException` at `LiftoffShellTest.kt:37`. That line is the shared `waitFor` helper's `waitUntil(5_000)`. None of the six commits touch that test or that helper, and the same tree passed locally. So the cause is the wait budget, not the new code. The picks add about 800 lines of tests, the suite ends up at 539 tests, and they all run in one forked JVM (`gradle.properties` sets no `forkEvery` or `maxParallelForks`). Every Compose wait in this repo uses the same 5 s budget.

The fix gives the shell test's shared wait real headroom. The work also audits the three new test classes against the brief's five hazards. The map records that they already put a bound on every wait and close their worker thread and databases in `tearDown`. No assertion is removed or loosened. The run is done only when the pull request's CI is green, not when the local gate passes.

## Files

Six cherry-picks, in the brief's order. The map has their line counts per file.

- `app/src/main/java/com/liftoff/app/ui/control/BackupViewModel.kt`: new, from `1375e11`.
- `app/src/test/java/com/liftoff/app/ui/control/BackupViewModelTest.kt`: new, from `1375e11`.
- `app/src/test/java/com/liftoff/app/ui/control/BackupImportRoomTest.kt`: new, from `265e5bf`.
- `app/src/main/java/com/liftoff/app/ui/control/BackupSection.kt`: new, from `7f855ad`.
- `app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt`: the BACKUP card, its two pickers and the `database`/`missionManager` parameters, from `75e7490`.
- `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`: two arguments at the Mission Control call site, from `75e7490`.
- `app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt`: the new parameters and the BACKUP head from `75e7490`, then the three BACKUP cases and their helpers from `601e8d8`.
- `ARCHITECTURE.md`: the M1 and M5 status cells, from `578d3b5`.

This run's own fix commit changes one file:

- `app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt`: raise the budget of the shared `waitFor` helper (lines 36-39) from 5 s to about 20 s, defined once as a named constant. No assertion changes. If the audit finds a hazard in one of the three new test classes, that class gets its own fix commit.


## Order

1. Cherry-pick `1375e11`. `bash gradlew.sh :app:compileDebugUnitTestKotlin` passes, and `BackupViewModelTest` passes on its own.
2. Cherry-pick `265e5bf`. `BackupImportRoomTest` passes on its own.
3. Cherry-pick `7f855ad`. It compiles. Nothing composes `BackupSection` yet, so no test covers it at this step.
4. Cherry-pick `75e7490`. `:app:assembleDebug` passes, and the existing `MissionControlScreenTest` cases pass with the BACKUP card on screen.
5. Cherry-pick `601e8d8`. The three BACKUP screen cases pass. This pick must come after step 4, because its patch is written against `75e7490`'s version of the file.
6. Cherry-pick `578d3b5`. The M1 and M5 cells in ARCHITECTURE.md read as the brief's Done-when requires.
7. Fix commit, test-only: first audit the three new classes against the brief's five hazards, then give `LiftoffShellTest`'s shared `waitFor` the larger budget. Use the `area: lowercase summary` form for the message, with a body that names the CI run id and the failing test.
8. Two full cold gate runs in a row, run detached: `mkdir -p app/build` first, `--no-build-cache`, and an `EXIT=` sentinel in the log. Read `tests=`, `failures=` and `errors=` from the JUnit XML. Before treating any failure as real, re-run once, because of the known In-Flight flake.
9. Once the pull request is open, read its CI run. If it is red, read the new log with `gh run view <id> --log-failed`, fix the cause it names, record that in Decisions and in the PR, push, and check again. Keep going until CI is green.

Each pick lands as its own commit with its original message. Nothing is squashed, so the six messages the brief lists are the ones that land.


## Testing

The plan invents no new tests. The tests the picks carry meet the brief's Done-when. After this run, all of the following must hold:

- Export and Import each report busy, then a result row, and the busy guard rejects a second action. Checked by plain-Kotlin view model tests (`BackupViewModelTest`).
- The suggested file name uses today's date in the device's own time zone, not the UTC date, and no picker is opened to show it. Checked by plain JUnit against a fixed clock.
- An import into a database that already holds data is refused, and the data layer's own refusal sentence is shown unchanged. A cancelled picker changes nothing. Checked by view model tests.
- An import reaches the flows the other tabs observe, not only the database it was given. Checked by Robolectric with in-memory Room, with the subscription started before the action (`BackupImportRoomTest`).
- The BACKUP card draws its head, both buttons are enabled when idle, and the line saying settings and the daemon token are not in the file is shown. Checked by a Compose screen test that clicks nothing.
- The success and refusal rows are drawn from the state, and a refusal replaces the success row. Checked by a Compose screen test with an injected view model whose work runs on a worker the test owns.
- Both buttons are disabled while work is running and enabled again afterwards. A control on the same screen that stays enabled serves as the comparison. Checked by a Compose screen test with the worker held on a latch.
- The shell's system-back navigation still reaches the Launchpad, with a longer wait and the same assertions. Checked by a Compose activity test (`LiftoffShellTest`).
- Settings and equipment editing, the other screens, and the data-layer export/import tests are untouched and still pass. Checked by the existing classes, run by the gate.
- No test clicks a system picker, and every wait in the suite has a timeout.
- The pull request's CI run passes the same gate.


## Risks

- **The same test, or another 5 s wait, fails in CI again.** It is a wait-budget failure seen only in CI, and a longer budget is not a guarantee. The fix raises the shell test's shared `waitFor` to about 20 s and keeps every assertion. That stays far inside the five-minute per-test timeout in `app/build.gradle.kts`. If CI is red again, the work reads that run's log and fixes what it names (step 9). It does not ship red as "a known flake", and it does not delete or weaken the test.
- **The cause is inferred, not reproduced.** Local runs pass, so the work cannot reproduce the failure. The audit of the three new classes is the hedge. It checks for:
  - bounded waits;
  - no assertion that depends on the time zone;
  - the worker and databases closed in `tearDown`;
  - the screen unmounted before its database closes.

  The PR says the diagnosis came from the CI log and that the failure could not be reproduced locally.
- **The taller Robolectric window (`h2800dp`) is the one change the picks make to how tests render.** If a BACKUP case cannot see the card, the work uses `performScrollTo` in that case rather than making the window taller still.
- **`601e8d8` depends on `75e7490`.** Its patch does not apply on its own; the sequence in Order is what lets it apply. If a pick still conflicts, resolve it hunk by hunk, keep both sides, and record the resolution in the commit body and in Decisions.
- **The known In-Flight flake (`doneBodyweightSetStepperShowsBW`) can fail either gate run.** Re-run the gate before treating it as broken. Two consecutive runs must both pass; two passing logs from runs that were not back to back do not count.
- **A cold gate run can outlast a shell command's time limit, and a stranded test executor can hold `app/build/test-results`.** Run detached with an `EXIT=` sentinel and wait on it. Run `bash gradlew.sh --stop` and clear stale results before a cold run, rather than re-launching a build that is still running.


## Decisions

**Added by review: the run is not done until the pull request's CI is green, and a repeat failure gets fixed, not labelled.** If the same test failed in CI again, the plan's Risks section had the work report it in the PR as a known flake. The brief's Done-when requires the gate to pass in the pull request's CI, and the owner asked this run specifically to make CI pass. The review therefore added step 9. After the push, the work reads the CI run. If it is red, the work reads that run's failed log, fixes the cause it names (still without weakening any assertion), records the cause here and in the PR, and pushes again until CI is green.

**The date in the export file name is the date on the phone, not the date in UTC.** The brief warns that CI runs in UTC and asks for the tests to be made deterministic. The name `liftoff-YYYY-MM-DD.json` is the one place a date reaches the user. A user who exports on the evening of the 19th expects a file named `liftoff-2026-10-19.json`, because that is the day they pressed Export. A name with the UTC date would show a date the user never chose. So the view model keeps the device's own date. The test derives the expected name from the same clock the view model uses, instead of hardcoding a date that is only right in one time zone. The alternative was to pin the whole feature to UTC. That would make the file name agree with CI, but it would surprise anyone exporting close to midnight.

**Import refuses rather than overwrites, and the card says so before you press it.** The brief does not say what happens when someone picks a backup file while their current week is already in the database. The merged data layer refuses: it accepts only an empty database. The one exception is the draft week the app creates on every cold start, which is dropped so the file's own week can take its place. The card's Import row is titled "Import a backup file into an empty database", and the data layer's own refusal sentence is shown as written. The alternative was a confirm-and-replace flow, which would let a wrong file erase training history that cannot be recreated. Refusing is what a user would expect from a safety net. It needs no extra dialog because the row states the precondition. If the owner wants replace-with-confirmation, this row is the one to change.

**A cherry-pick conflict would be resolved by keeping both sides, not by taking the incoming file.** This branch has moved on since the earlier run branched off. Taking the earlier branch's version of a shared file wholesale would quietly undo work that landed here. Each conflict would be resolved hunk by hunk: keep this branch's change, add the earlier run's, and write the resolution into the commit body. In practice the picks are conflict-free. The source branch forked from this branch's data-layer merge (`c183c1c`), everything this branch added since is `.rho/runs/` bookkeeping, and a test merge reports no conflicts. So this rule is a fallback the work should not need.

**The CI fix is the shell test's five-second wait, not the new backup tests.** The failed log for the earlier branch's CI run can be read, and it names one failure out of 539 tests: `LiftoffShellTest.systemBackFromATabGoesToLaunchpad`, timing out in the shell test's shared `waitFor` helper. None of the six commits touch that test or that helper, and the tree passed locally. The plan treats this as a slow runner rather than wrong code. It raises that helper's wait budget and leaves every assertion in place. One alternative was to change nothing and re-push, betting the failure would not repeat. Another was to widen the budget across the whole suite, which would touch a dozen test files for a failure in one. The PR says the diagnosis came from the log and that the failure could not be reproduced locally.
