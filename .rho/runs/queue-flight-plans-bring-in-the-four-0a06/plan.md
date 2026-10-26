# Plan

## Approach

This run moves code that has already been reviewed. It writes none of its own. Cherry-pick the four commits by sha in the brief's order and keep their messages. Do one pick per step, and after each pick check that the branch compiles and that the test classes the pick names pass. Then run the whole suite once, which the source branch never did after its last commit, and fix only what it reports. The picks stand on their own: the data layer queues, and the worker and the Launchpad schedule what the data layer queued. No screen or `AppContainer` change is needed, because `LaunchpadScreen` already asks the container for the enqueuer at action time and In-Flight has no enqueue wiring at all. The last step rewrites the closing clause of the M3 milestone row so the map describes the code that now exists. File lists and the shape of the picked code are in `map.md`.

## Files

Nothing is created. The four picks change these files (`map.md` lists them per commit): `data/GenerationDao.kt`, `data/MissionManager.kt`, `work/GenerationWorker.kt`, `ui/launchpad/LaunchpadViewModel.kt`, and the test classes `MissionManagerTest`, `GenerationWorkerTest`, `LaunchpadConfirmTest` and `LaunchpadScrubTest`.

- `ARCHITECTURE.md` (line 82, the M3 row): replace only its closing clause, "Queueing Flight Plans and the Launchpad's display of the current sortie's status come next". The new text says what the code now does: Flight Plans are queued as sorties become current, the run-plans toggle is honoured, nothing is queued without a host and token, the worker and the Launchpad enqueue what their own transactions queued after those transactions commit, and Re-fly cancels the sortie's open Flight Plan generation. It ends: In-Flight's enqueue, Regenerate and the Launchpad's sortie status come next. Keep it one line in the same row format.
- Any file that a red whole-suite gate names, and only those.
- `app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json`: must show no diff at all.

## Order

1. Cherry-pick `bcf6bae`. `:app:compileDebugKotlin` and `MissionManagerTest` pass.
2. Cherry-pick `a74b099`. `MissionManagerTest` passes on its own.
3. Cherry-pick `1afea86`. `GenerationWorkerTest` and its neighbours `GenerationCancellerTest` and `GenerationEnqueuerTest` pass.
4. Cherry-pick `a27d999`. These Launchpad classes pass together: `LaunchpadConfirmTest`, `LaunchpadScrubTest`, `LaunchpadReFlyTest`, `LaunchpadStatesTest`, `LaunchpadDraftControlsTest`, `LaunchpadDraftGuardsTest`, `LaunchpadDraftStatusTest`, `LaunchpadCoachStatusTest`.
5. Run the whole gate detached: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` with `--no-build-cache`, logging under `app/build/`. Read the log and the test reports before editing anything. If the run hangs, or the test task dies without writing a report, re-run the same tree (see Decisions).
6. If a test fails with a reported assertion, fix the cause it names in its own commit, then re-run the whole gate.
7. Rewrite the M3 row and commit it on its own, with a `docs:` prefix.
8. Confirm that `app/schemas` shows no diff and the tree is clean.

Each step leaves the branch compiling and the tests it touched green. Step 5 is the only run that covers the classes no earlier step named.

## Testing

The four picks bring their own tests. This run's job is to prove they pass together and that nothing else moved. The suite must show the behaviours below. The picked commits cover the first group; the last two are this run's own checks.

- `advance` inserts exactly one QUEUED `FLIGHT_PLAN` row for a current PENDING lift when a host and token are set, and returns its id. Covered by `MissionManagerTest`, reading the Room row back.
- It inserts no second row while one is QUEUED or RUNNING for that sortie, and returns the open row's id instead. Covered by the guard cases in `MissionManagerTest`.
- With the toggle off, a run gets the simple `'Run'` plan and no generation row. With the toggle on, it gets a QUEUED `FLIGHT_PLAN` row. Covered by `MissionManagerTest`.
- With no host or token, nothing is queued and the sortie stays PENDING. Covered by `MissionManagerTest` and the Launchpad cases where Mission Control is not set up.
- `land` and `scrub` return the id queued for the sortie that became current, and null when nothing was queued. Covered by `MissionManagerTest`.
- `storeOutline` and `continueWithoutOutline` call `onQueued` only after their transaction commits, so the caller can read the row back. Covered by `MissionManagerTest`.
- `refly` returns the sortie's open `FLIGHT_PLAN` ids and queues nothing itself. Covered by `MissionManagerTest` and `MissionManagerReFlyTest`.
- The worker schedules the plan its own `storeOutline` queued, after its transaction commits. `GenerationWorkerTest` reads the scheduled work back out of WorkManager as `ENQUEUED` under `gen-<id>`. The rest of that class builds the worker with no WorkManager running, which keeps proving that a stored reply still ends in success when nothing can be scheduled.
- The Launchpad passes the exact ids its actions queued to the enqueuer, and Re-fly's ids to the canceller. Covered by `LaunchpadConfirmTest` and `LaunchpadScrubTest`, through tests on the injected lambdas.
- Nothing else moved: the whole suite is green in one cold run. That includes the shell tests that mount the real container with no WorkManager, In-Flight and reopening, Landed, export/import, Mission Control and the domain tests. This is the project gate.
- The exported Room schema is unchanged: `SchemaExportTest` stays green and `app/schemas` shows no diff.

## Risks

- **Breakage across classes that the source branch never saw.** Its commits were gated one class at a time, and `GenerationDao`'s count queries cover the whole table. A test that counts generations in a fixture with Mission Control configured now counts one more row. `GenerationCancellerTest` is the case the brief names: its outline store now activates the week and leaves a QUEUED `FLIGHT_PLAN` row behind. The whole-suite run at step 5 catches this. The fix follows what the report names and is never a loosened count.
- **A red that these commits did not cause.** On this machine the gate can hang for about five minutes, and then the test task times out and writes no report. Run the gate detached, read its log, and re-run on a hang. A failure with a reported assertion is a real result, even in a file the picks never touched.
- **A pick bringing in something unwanted.** After applying each pick, check its file list against `map.md`. Stage named files only. Never `git checkout` the source branch: it is checked out in another worktree, so cherry-picking by sha is the only way.
- **The branch moving during the work.** Check HEAD and the tree before each pick. Another session may also edit the source branch while this run works, so confirm each sha's message matches the brief.
- **A Room schema change nobody notices.** No pick touches an entity, but `app/schemas` is compared against HEAD at the end, and `SchemaExportTest` is in the gate.
- **The orphaned QUEUED row from In-Flight** (see Decisions) is accepted and not fixed here.
- Do not touch `verify.sh`; the runner owns it.

## Decisions

**A red gate is re-run before it is believed.** The brief forbids loosening a count or an assertion, but it does not say what to do about a failure these commits did not cause. This machine can hang the full gate for about five minutes, and In-Flight has a known flaky case. The plan re-runs a red gate once on the same tree before treating it as broken. It treats a failure in a file none of the four commits touched as a timing problem, and fixes it with a test-only commit that gives the test a bounded wait, never by weakening what it asserts. The alternative, treating any red as a real defect, would send a work session to "fix" a test that is already correct.
Changed by review: the plan chose to treat any failure in a file the picks never touched as a timing problem. The review chose to re-run only on a hang or a missing report, and to treat every reported assertion failure as real, wherever it is. The reason: the brief names `GenerationCancellerTest`, which no pick edits, as a test that now sees an auto-queued row. Whole-table counts mean untouched files are exactly where these commits break things. Calling those failures timing problems would hide real breakage. In that case the fix reads the cause. Where the new row is correct behaviour, the test should say so: scope the count to the kind it means, or assert the `FLIGHT_PLAN` row explicitly. Never drop or loosen the check. A timing fix is for a failure that really is a wait timing out.

**Landing or scrubbing from In-Flight queues a Flight Plan that nothing schedules.** `advance` queues on its own, whoever calls it, and the brief leaves `InFlightViewModel` ignoring the id `land` and `scrub` return. So a land or scrub from In-Flight leaves the next sortie's plan `QUEUED` with no work scheduled. Until something passes that id to the enqueuer, the duplicate guard stops anything from queuing a replacement. The alternatives were to wire In-Flight here, or to make `advance` queue only when the caller can schedule. The source branch's In-Flight commit was deliberately reset, and the brief says to leave In-Flight alone. A run that brings in reviewed commits should carry those four and no new unreviewed code. The row is not wrong and not lost: it is a plan that has not started, and the follow-up run starts plans from the action that queues them.

**Only the M3 row changes.** The picks honour the run-plans toggle and queue run Flight Plans when it is on, which the M6 row describes as "Not started". They also touch Re-fly, which M5 describes as done for PENDING only. The brief asks for one row, so M5 and M6 are left as they are, and the PR body says that M6's toggle half now exists in `advance`. The alternative was to rewrite three rows in a run whose stated scope is "nothing else". The owner reviews milestone wording in the pull request anyway.

**The PR names the tests the picks actually changed.** The brief lists `GenerationWorkerTest`, `GenerationCancellerTest` and `LaunchpadConfirmTest` as the tests that now see an auto-queued row. The four commits edit `MissionManagerTest`, `GenerationWorkerTest`, `LaunchpadConfirmTest` and `LaunchpadScrubTest`. `GenerationCancellerTest` is affected by behaviour, not by an edit, unless step 6 has to change it. The PR lists what the commits actually touched, says separately which tests only *see* the new row, and names any test this run itself changed and why, so the pull request matches the diff instead of the brief's summary.
