# Plan

## Approach

Bring the six commits over in the brief's order and then fix the one thing that actually failed in CI. The map shows why the picks are the cheap part: the source branch forked from `c183c1c` (PR #27, the export/import data layer), which is three commits below HEAD, and the only commits HEAD has added since are `.rho/runs/` bookkeeping — `git merge-tree` reports no conflicts and five of the six patches apply clean standalone. So this is a straight transfer, not a merge, and no feature is written here.

The CI part is already diagnosed rather than guessed. The failed log for run `39288220848` is readable and says exactly one test failed out of 539: `LiftoffShellTest.systemBackFromATabGoesToLaunchpad`, `ComposeTimeoutException` at `LiftoffShellTest.kt:37` — the shared `waitFor` helper's `waitUntil(5_000)`. That test and that helper are in a file none of the six commits touch, and the same tree was green locally, so the cause is the wait budget, not the new code: the picks add roughly 800 lines of test to a suite that ends up at 539 tests, all of them run in one forked JVM (`gradle.properties` sets no `forkEvery`/`maxParallelForks`), and 5 s is the budget every Compose wait in this repo already uses. The fix is to give the shell test's shared wait real headroom and to audit the three new test classes against the brief's five hazards — the map records that they already bound every wait and close their worker thread and databases in `tearDown` — without removing or loosening any assertion.

## Files

Six cherry-picks, in the brief's order; their per-file line counts are in the map.

- `app/src/main/java/com/liftoff/app/ui/control/BackupViewModel.kt` — new, from `1375e11`.
- `app/src/test/java/com/liftoff/app/ui/control/BackupViewModelTest.kt` — new, from `1375e11`.
- `app/src/test/java/com/liftoff/app/ui/control/BackupImportRoomTest.kt` — new, from `265e5bf`.
- `app/src/main/java/com/liftoff/app/ui/control/BackupSection.kt` — new, from `7f855ad`.
- `app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt` — the BACKUP card, its two pickers and the `database`/`missionManager` parameters, from `75e7490`.
- `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt` — two arguments at the Mission Control call site, from `75e7490`.
- `app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt` — new parameters and the BACKUP head at `75e7490`, then the three BACKUP cases and their helpers at `601e8d8`.
- `ARCHITECTURE.md` — the M1 and M5 status cells, from `578d3b5`.

One file is changed by this run's own fix commit:

- `app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt` — raise the shared `waitFor` budget (line 36-39) so a slow runner cannot expire it; no assertion changes. If the audit finds a hazard in one of the three new test classes, that class gets its own fix commit.


## Order

1. Cherry-pick `1375e11`. `bash gradlew.sh :app:compileDebugUnitTestKotlin` passes; `BackupViewModelTest` passes on its own.
2. Cherry-pick `265e5bf`. `BackupImportRoomTest` passes on its own.
3. Cherry-pick `7f855ad`. Compiles; `BackupSection` is not yet composed anywhere, so no test covers it yet.
4. Cherry-pick `75e7490`. `:app:assembleDebug` passes and `MissionControlScreenTest`'s existing cases pass with the BACKUP card on screen.
5. Cherry-pick `601e8d8`. The three BACKUP screen cases pass. This pick must come after step 4 — its patch is written against `75e7490`'s version of the file.
6. Cherry-pick `578d3b5`. ARCHITECTURE.md's M1 and M5 cells read as the brief's Done-when requires.
7. Fix commit, test-only: give `LiftoffShellTest`'s shared `waitFor` a larger budget, after the audit of the three new classes against the brief's five hazards. Message in the `area: lowercase summary` form, body naming the CI run id and the failing test.
8. Two full cold gate runs in a row, detached (`mkdir -p app/build` first, `--no-build-cache`, `EXIT=` sentinel in the log), reading `tests=`/`failures=`/`errors=` out of the JUnit XML. Re-run once before treating any failure as real, for the known In-Flight flake.

Each step commits its own pick with its original message; nothing is squashed, so the six messages the brief lists are the ones that land.


## Testing

Nothing here is a new test the plan invents; the brief's Done-when is met by the tests the picks carry. What has to be true after this run:

- Export and Import report busy and a result row, and the busy guard rejects a second action — plain-Kotlin view model tests (`BackupViewModelTest`).
- The suggested file name is today on the device's own zone, not the UTC date, and no picker is opened to show it — plain JUnit against a fixed clock.
- An import into a database that already holds data is refused, and the data layer's own sentence is shown unchanged; a cancelled picker changes nothing — view model tests.
- An import reaches the flows the other tabs observe, rather than only the database it was given — Robolectric + in-memory Room, subscription started before the action (`BackupImportRoomTest`).
- The BACKUP card draws its head, both buttons enabled at idle, and the line saying settings and the daemon token are not in the file — Compose screen test, nothing clicked.
- Success and refusal rows are drawn from the state, and a refusal replaces the success row — Compose screen test with an injected view model whose work runs on a worker this test owns.
- Both buttons are disabled while work is running and enabled again after it, with a still-enabled control on the same screen as the mirror — Compose screen test with the worker held on a latch.
- The shell's system-back navigation still proves it reaches the Launchpad, with a longer wait and the same assertions — Compose activity test (`LiftoffShellTest`).
- Settings and equipment editing, the other screens, and the data-layer export/import tests are untouched and still pass — the existing classes, run by the gate.
- No test clicks a system picker; every wait in the suite has a timeout.


## Risks

- **The same test goes red in CI again.** It is a CI-only wait-budget flake and a longer budget is not a guarantee. The fix raises the shell test's shared `waitFor` well past 5 s while keeping every assertion, and stays far inside the five-minute per-test timeout in `app/build.gradle.kts`. If CI fails on `systemBackFromATabGoesToLaunchpad` again after that, the work reports it in the PR as the known flake rather than gutting or deleting the test.
- **The cause is inferred, not proven.** Local runs are green, so the plan cannot reproduce the failure. The audit of the three new classes is the hedge: bounded waits, no time-zone-dependent assertion, worker and databases closed in `tearDown`, screen unmounted before the database closes. The PR says the diagnosis came from the CI log and that the change was not reproducible locally.
- **The taller Robolectric window (`h2800dp`) is the one thing the picks change about how tests render.** If a BACKUP case cannot see the card, the work uses `performScrollTo` in that case rather than making the window taller still.
- **`601e8d8` depends on `75e7490`.** Its patch does not apply on its own; the order in Order is what makes it apply. If a pick still conflicts, resolve hunk by hunk keeping both sides and record it in the commit body and in Decisions.
- **The known In-Flight flake (`doneBodyweightSetStepperShowsBW`) can fail either gate run.** Re-run the gate before treating it as broken, and require two consecutive green runs rather than two different logs.
- **A cold gate run can outlast a shell command's limit, and a stranded test executor can hold `app/build/test-results`.** Run detached with an `EXIT=` sentinel and wait on it; `bash gradlew.sh --stop` and clear the stale results before a cold run rather than re-launching a build that is already running.


## Decisions

**The date in the export file name is the day on the phone, not the day in UTC.** The brief warns that CI runs in UTC and asks for the tests to be made deterministic, and the name `liftoff-YYYY-MM-DD.json` is the one place a date reaches the user. A user who exports on the evening of the 19th expects a file named `liftoff-2026-10-19.json`, because that is the day they pressed Export; naming it for the UTC date would be a file name nobody chose. So the view model keeps the device's own date, and the test derives the name it expects from the same clock the view model uses rather than hardcoding a date that only holds in one time zone. The alternative — pin the whole feature to UTC — would make the file name agree with CI but would surprise anyone near a timezone boundary.

**Import refuses instead of overwriting, and the card says so before you press it.** The brief does not say what happens when someone picks a backup file while their current week is already in the database. The merged data layer refuses: only an empty database is accepted — the one exception being the draft week the app creates on every cold start, which is dropped so the file's own week can take its place — and the card's Import row is titled "Import a backup file into an empty database", with the data layer's own refusal sentence shown as it is. The alternative is a confirm-and-replace flow, which would let a wrong file erase training history that cannot be recreated. Refusing is what a user would expect from a safety net, and it needs no extra dialog because the precondition is stated on the row. If the owner wants replace-with-confirmation, this is the row to change.

**A cherry-pick conflict would be resolved by keeping both sides, not by taking the incoming file.** This branch has moved since the earlier run branched, so taking the earlier branch's version of a shared file wholesale would quietly undo work that landed here. Each conflict would be resolved hunk by hunk, keeping this branch's change and adding the earlier run's, and the resolution written into the commit body. In fact the picks turn out to be conflict-free: the source branch forked from this branch's data-layer merge (`c183c1c`), everything this branch added since is `.rho/runs/` bookkeeping, and a test merge reports no conflicts — so this rule is a fallback the work should not need.

**The CI fix is the shell test's five-second wait, not the new backup tests.** The failed log for the earlier branch's CI run is readable, and it names one failure out of 539 tests: `LiftoffShellTest.systemBackFromATabGoesToLaunchpad`, timing out in the shell test's shared `waitFor` helper — a test and a helper that none of the six commits touch, on a tree that was green locally. The plan treats that as the runner being slow rather than the code being wrong, and raises that helper's wait budget while leaving every assertion in place. The alternatives were to change nothing and re-push, betting the flake does not repeat, or to widen the budget across the whole suite, which would touch a dozen test files for a failure in one. The PR says the diagnosis came from the log and that the failure could not be reproduced locally.
