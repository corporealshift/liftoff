# Plan

## Approach

This run moves reviewed code rather than writing any: cherry-pick the four commits by sha in the
brief's order, keeping their messages, one pick per step, checking after each that the branch
compiles and the test classes that pick names pass, then run the whole suite once — which the source
branch never did after its last commit — and fix only what it reports. The picks are self-contained:
the data layer queues, the worker and the Launchpad schedule what the data layer queued, and no
screen or `AppContainer` change is needed because `LaunchpadScreen` already asks the container for
the enqueuer at action time and In-Flight has no enqueue wiring at all. The last step rewrites the M3
milestone row's closing clause so the map describes the code that now exists. File lists and the
shape of the picked code are in `map.md`.

## Files

Nothing is created. The four picks change these (their per-commit file lists are in `map.md`):
`data/GenerationDao.kt`, `data/MissionManager.kt`, `work/GenerationWorker.kt`,
`ui/launchpad/LaunchpadViewModel.kt`, and the test classes `MissionManagerTest`,
`GenerationWorkerTest`, `LaunchpadConfirmTest`, `LaunchpadScrubTest`.

- `ARCHITECTURE.md` (line 82, the M3 row): replace only its closing clause — "Queueing Flight Plans
  and the Launchpad's display of the current sortie's status come next" — with what the code now
  does: Flight Plans are queued as sorties become current, the run-plans toggle is honoured, nothing
  is queued without a host and token, the worker and the Launchpad schedule what their own
  transactions queued, and Re-fly cancels the sortie's open Flight Plan generation; In-Flight's
  enqueue, Regenerate and the Launchpad's sortie status come next. One line, same row format.
- Any file a red whole-suite gate names, and only those.
- `app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json`: must show no diff at all.

## Order

1. Cherry-pick `bcf6bae`. `:app:compileDebugKotlin` plus `MissionManagerTest` pass.
2. Cherry-pick `a74b099`. `MissionManagerTest` passes on its own.
3. Cherry-pick `1afea86`. `GenerationWorkerTest` plus its neighbours `GenerationCancellerTest` and
   `GenerationEnqueuerTest` pass.
4. Cherry-pick `a27d999`. The Launchpad classes pass together: `LaunchpadConfirmTest`,
   `LaunchpadScrubTest`, `LaunchpadReFlyTest`, `LaunchpadStatesTest`, `LaunchpadDraftControlsTest`,
   `LaunchpadDraftGuardsTest`, `LaunchpadDraftStatusTest`, `LaunchpadCoachStatusTest`.
5. Run the whole gate detached — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` with
   `--no-build-cache`, log under `app/build/`, and read the log before editing anything. Re-run the
   same tree once before believing a red (see Decisions).
6. If it is red, fix only what it names, in its own commit, then re-run the whole gate.
7. Rewrite the M3 row and commit it on its own (`docs:` prefix).
8. Confirm `app/schemas` shows no diff and the tree is clean.

Each step leaves the branch compiling and the tests it touched green; step 5 is the one run that
covers the classes no earlier step named.

## Testing

The four picks bring their own tests; this run's job is to prove them together and prove nothing
else moved. The behaviors the suite must show — the picked commits cover the first group, the last
two are this run's own checks:

- `advance` inserts exactly one QUEUED `FLIGHT_PLAN` row for a current PENDING lift when a host and
  token are set, and returns its id — `MissionManagerTest`, Room row read-back.
- It inserts no second row while one is QUEUED or RUNNING for that sortie, and returns the open id
  instead — `MissionManagerTest`, guard cases.
- Toggle off gives a run the simple `'Run'` plan and no generation row; toggle on gives it a QUEUED
  `FLIGHT_PLAN` row — `MissionManagerTest`.
- With no host or token nothing is queued and the sortie stays PENDING — `MissionManagerTest`, and
  the unconfigured Launchpad cases.
- `land` and `scrub` return the id queued for the sortie that became current, and null when nothing
  was queued — `MissionManagerTest`.
- `storeOutline` and `continueWithoutOutline` call `onQueued` only after their transaction commits,
  so the caller can read the row back — `MissionManagerTest`.
- `refly` hands back the sortie's open `FLIGHT_PLAN` ids and queues nothing itself —
  `MissionManagerTest`, `MissionManagerReFlyTest`.
- The worker schedules the plan its own `storeOutline` queued, after its transaction commits —
  `GenerationWorkerTest`, which reads the scheduled work back out of WorkManager as `ENQUEUED` under
  `gen-<id>`. The rest of that class builds the worker with no WorkManager up, which is what keeps
  proving that a stored reply still ends as success when nothing can be scheduled.
- The Launchpad hands the exact ids its actions queued to the enqueuer, and Re-fly's ids to the
  canceller — `LaunchpadConfirmTest`, `LaunchpadScrubTest`, lambda-seam tests on the injected
  lambdas.
- Nothing else moved: the whole suite green in one cold run, including the shell tests that mount the
  real container with no WorkManager, In-Flight and reopening, Landed, export/import, Mission
  Control, and the domain tests — the project gate.
- The exported Room schema is unchanged — `SchemaExportTest` stays green and `app/schemas` shows no
  diff.

## Risks

- **Cross-class breakage the source branch never saw.** Its commits were gated class by class, and
  `GenerationDao`'s count queries are whole-table, so a test that counts generations in a
  coach-configured fixture now counts one more. The whole-suite run at step 5 is what catches this;
  the fix is whatever the report names, not a loosened count.
- **A red that is not these commits' fault.** This machine can hang the gate for about five minutes
  (the test task times out and writes no report), and In-Flight has a known flaky case. Run the gate
  detached, read its log, and re-run the same tree once before treating a red as a defect. On CI —
  ubuntu-latest, slower — a shell test that waits on a real container can time out; the fix is a
  test-only commit that gives it a bounded wait to work with.
- **A pick dragging in something unwanted.** Verify each pick's file list against `map.md` after
  applying it, stage named files only, and never `git checkout` the source branch — it is checked out
  in another worktree, so only cherry-pick by sha works.
- **The branch moving under the work.** Re-check HEAD and the tree before each pick; the source branch
  may also be edited by another session while this run works.
- **A silent Room schema change.** No pick touches an entity, but `app/schemas` is compared against
  HEAD at the end, and `SchemaExportTest` is in the gate.
- **The orphaned QUEUED row from In-Flight** (see Decisions) is accepted, not fixed here.
- Do not touch `verify.sh`; the runner owns it.

## Decisions

**Landing or scrubbing from In-Flight queues a Flight Plan that nothing schedules.** `advance`
queues on its own, whoever calls it, and the brief leaves `InFlightViewModel` ignoring the id
`land`/`scrub` return. So a land or scrub from In-Flight leaves the next sortie's plan `QUEUED`
with no work scheduled, and the duplicate guard means nothing queues a replacement for that sortie
either — the plan for that sortie is never generated. The alternative was to wire In-Flight here,
or to make `advance` queue only when the caller can schedule. The source branch's In-Flight commit
was deliberately reset, so this has already been declined once, and a bring-in run should carry the
four reviewed commits and no new unreviewed code. The row is not wrong and not lost: it is a plan
that has not been started, and the follow-up run starts plans at the action that queues them.

**A red gate is re-run before it is believed.** The brief forbids loosening a count or an
assertion, but it does not say what to do about a failure that is not caused by these commits.
This machine can hang the full gate for about five minutes, and In-Flight has a known flaky case.
The plan re-runs a red gate once on the same tree before treating it as broken, and treats a
failure in a file none of the four commits touched as a timing problem: fix it with a test-only
commit that gives the test a bounded wait to work with, never by weakening what it asserts. The
alternative — treating any red as a real defect — would send a work session to "fix" a test that
is already correct.

**Only the M3 row changes.** The picks honour the run-plans toggle and queue run Flight Plans when
it is on, which is what the M6 row describes as "Not started", and they touch Re-fly, which M5
describes as done for PENDING only. The brief asks for one row, so M5 and M6 are left as they are
and the PR body says that M6's toggle half now exists in `advance`. The alternative was to rewrite
three rows in a run whose stated scope is "nothing else"; the owner reviews milestone wording in
the pull request anyway.

**The PR names the tests the picks actually changed.** The brief lists `GenerationWorkerTest`,
`GenerationCancellerTest` and `LaunchpadConfirmTest` as the tests that now see an auto-queued row.
The four commits edit `MissionManagerTest`, `GenerationWorkerTest`, `LaunchpadConfirmTest` and
`LaunchpadScrubTest`; `GenerationCancellerTest` is affected by behaviour, not by an edit. The plan
lists what the commits actually touched and says separately which tests only *see* the new row, so
the pull request matches the diff instead of the brief's summary.
