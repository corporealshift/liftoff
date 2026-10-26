# Queue Flight Plans: bring in the four finished commits

Bring finished work onto this branch, update one ARCHITECTURE.md row, and run the gate. Nothing else.

Step 1: cherry-pick. The unmerged local branch `rho/queue-a-flight-plan-when-a-sortie-vmn4` holds four finished code commits. It is checked out in another worktree, so cherry-pick by sha (do not `git checkout` it). Pick exactly these, in this order, keeping their messages:
1. `bcf6bae` data: queue a flight plan when advance reaches a pending sortie
2. `a74b099` test: cover the guard cases around queuing a flight plan
3. `1afea86` work: enqueue the flight plan the worker's own store queued, after its transaction commits
4. `a27d999` launchpad: enqueue the flight plan its actions queued, cancel re-fly's open plans
Do not bring over any `run:` commit, any file under `.rho/runs/`, or any In-Flight change. That branch's later In-Flight commit was reset and is not wanted.

What exists after the picks (you do not need to re-derive this):
- `MissionManager.advance` (private) inserts one QUEUED FLIGHT_PLAN `Generation` row when the current sortie is PENDING, needs generation (every lift; a run only when 'Generate run plans' is on) and the coach is configured. It never inserts a second row while one is QUEUED or RUNNING for that sortie. With the toggle off a run gets the simple 'Run' plan. With no host or token nothing is queued and the sortie stays PENDING.
- `land(...)` and `scrub(...)` return `Long?`: the Flight Plan id queued for the sortie that became current, or null. `storeOutline` and `continueWithoutOutline` take an `onQueued: (Long) -> Unit` called after their transaction commits. `refly(sortieId)` returns the sortie's open FLIGHT_PLAN ids for the caller to cancel.
- `GenerationDao.getOpenFlightPlanForSortie(sortieId)`.
- `GenerationWorker` enqueues sortie 1's Flight Plan after a stored outline, outside its transaction.
- `LaunchpadViewModel` passes the queued id to `queueGeneration` after continue-without-outline and after `confirmScrub`, and passes Re-fly's ids to `cancelGeneration`.
- `InFlightViewModel` still ignores what `land`/`scrub` return. A later run wires that. Leave In-Flight alone here.

Step 2: run the whole gate. These commits were tested class by class but the whole suite never ran after the last one. If anything is red, fix only what it names. Do not loosen a count or an assertion to make it pass: read what the test reports and fix the cause.

Step 3: update the ARCHITECTURE.md M3 row (around line 82). Replace its closing 'Queueing Flight Plans ... come next' clause so it says what the code now does: Flight Plans are queued as sorties become current, the toggle is honoured, nothing is queued without a host and token, the worker and the Launchpad enqueue after their transactions, and Re-fly cancels the sortie's open Flight Plan generation. In-Flight's enqueue, Regenerate and the Launchpad's sortie status come next.

No Room schema change: the exported schema under `app/schemas` must stay byte-identical. In the PR, list the existing tests the picked commits changed and why: `GenerationWorkerTest`, `GenerationCancellerTest` and `LaunchpadConfirmTest` now see an auto-queued row when Mission Control is configured.

Must not break: the worker, enqueue and cancel tests, Confirm's outline queueing and the draft status, In-Flight and reopening, Landed, Re-fly, export/import, Mission Control, the shell tests (`LiftoffShellTest`, `ShellNavTest`, `MainActivityTest` compose the real container with no WorkManager), the domain tests and the build gate. Every test wait must be bounded. CI is ubuntu-latest, UTC, and slower than this machine. Follow CLAUDE.md conventions. When something is unclear, decide, record it in the plan, and carry on. The full gate can hang once for about 5 minutes on this machine. Run it detached and re-run it if that happens.

Done when: the four commits are on the branch, the M3 row is updated, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
