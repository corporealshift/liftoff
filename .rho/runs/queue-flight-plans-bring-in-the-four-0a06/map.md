# Map — queue flight plans (bring-in)

Branch here: `rho/queue-flight-plans-bring-in-the-four-0a06`. HEAD moves every step; check it before each
pick. Merge-base with the source branch is `4dcf27d` (Draw the draft's coach status, #41), i.e. the source
branch is based on this branch's own tip, so the picks should apply without conflict.
Baseline for the schema comparison in `verify.sh` §6 is `d2fb423^`, where `d2fb423 run: brief` is the commit
that added this run's `brief.md`.
`verify.sh` was rewritten after this map's first pass (`0ece443 run: verify.sh rewritten by claude review`);
the twelve names below are what the current script asks for, re-read from it at the tasks step.

## The four commits to pick (branch `rho/queue-a-flight-plan-when-a-sortie-vmn4`)

That branch is checked out in another worktree, so cherry-pick by sha only — never `git checkout` it.
Its own `run:` commits (`7f153ff`, `b819c55`, `d9a9bb9`, `bcbbccd`, `d106603`, `7cda190`, `fbdfeb8`,
`455063f`, `996cae6`, `1393637`, `ae6e4d9`) and its later In-Flight commit are not wanted.

| sha | message | files it touches |
|---|---|---|
| `bcf6bae` | data: queue a flight plan when advance reaches a pending sortie | `GenerationDao.kt` +8, `MissionManager.kt` +105/-35, `MissionManagerTest.kt` +93 |
| `a74b099` | test: cover the guard cases around queuing a flight plan | `MissionManagerTest.kt` +157 |
| `1afea86` | work: enqueue the flight plan the worker's own store queued, after its transaction commits | `GenerationWorker.kt` +26/-3, `GenerationWorkerTest.kt` +65 |
| `a27d999` | launchpad: enqueue the flight plan its actions queued, cancel re-fly's open plans | `LaunchpadViewModel.kt` +20/-6, `LaunchpadConfirmTest.kt` +42, `LaunchpadScrubTest.kt` +62 |

No `.rho/runs/` file and no In-Flight file is in any of the four.

## What the picked code does (read off the four patches)

- `MissionManager.advance` (private, `data/MissionManager.kt`) now returns `Long?`: the FLIGHT_PLAN
  generation id it inserted for the current PENDING sortie, **or the id of the open row it found
  instead of inserting a second one**, or null (mission closed, no current sortie, `Preparation`
  wrote a plan directly, coach not configured, or the sortie needs no generation). It inserts only
  when `Preparation.AwaitGeneration` and `coachIsConfigured(settings.daemonHost, settings.daemonToken)`;
  `coachIsConfigured` is `domain/Coach.kt:10`. Toggle off ⇒ a run gets the simple `'Run'` plan through
  the existing `Preparation` branch, unchanged.
- `land(...)` and `scrub(...)` return that `Long?`. `storeOutline(missionId, valid, onQueued)` and
  `continueWithoutOutline(missionId, onQueued)` take a defaulted `onQueued: (Long) -> Unit` that is
  called **after** `db.withTransaction` returns (the id is captured in a local `var` inside the
  transaction). `continueWithoutOutline` still returns only the open *outline* ids, so a plan id can
  never reach the cancel loop.
- `refly(sortieId)` returns `List<Long>` — the sortie's open FLIGHT_PLAN ids, read inside its
  transaction via the new DAO query — and still uses `markPlanned`, not `advance`.
- `GenerationDao.getOpenFlightPlanForSortie(sortieId)`: `sortieId = :sortieId AND kind = 'FLIGHT_PLAN'
  AND status IN ('QUEUED','RUNNING') ORDER BY createdAt DESC, id DESC`. `GenerationDao`'s other count
  queries are whole-table, so a test that counts must account for the new row.
- `GenerationWorker`: `StoreDecision.Wrote` gained a `queuedPlanId: Long?` field; the worker passes a
  lambda as `storeOutline`'s `onQueued` to capture the id while its own transaction is open, and calls
  `enqueueQueuedPlan(container, id)` after the transaction, on the success path only, after
  `giveUpSession`. `enqueueQueuedPlan` rethrows `CancellationException` and swallows everything else —
  a process with no WorkManager must not fail a reply that is already stored.
- `LaunchpadViewModel`: `queueGeneration` is a plain `(Long) -> Unit` (l.27) and `cancelGeneration` is
  `suspend (Long) -> Unit`, so a suspend lambda is **not** assignable to the manager's `onQueued`.
  `continueWithoutOutline()` passes `queueGeneration` straight through; `confirmScrub` does
  `queuedPlanId?.let(queueGeneration)` inside the `scope.launch` try block; `refly()` loops
  `manager.refly(...)`'s ids into `cancelGeneration`.
- `ui/launchpad/LaunchpadScreen.kt:57` already builds `queueGeneration` as
  `container.generationEnqueuer().enqueue(id)` at action time (not a bound reference), so no screen
  or container change is needed.
- `InFlightViewModel` is untouched by the picks: it still ignores what `land`/`scrub` return.

## Where enqueue is reached (nothing sweeps QUEUED rows)

`GenerationEnqueuer.enqueue` (`work/GenerationEnqueuer.kt:22`, unique name `gen-<id>`,
`ExistingWorkPolicy.KEEP`) is called from exactly two places after the picks: `LaunchpadScreen`'s
lambda, and `GenerationWorker.enqueueQueuedPlan`. `MissionManager.onAppOpen` does **not** enqueue
anything, so a QUEUED row nobody scheduled stays QUEUED. Re-enqueuing the id `advance` found already
open is safe because of `KEEP`.

## Tests that can see the new auto-queued row

Only tests that set a host and token can queue anything. Those are:
`data/MissionManagerTest.kt`, `work/GenerationWorkerTest.kt`, `work/GenerationCancellerTest.kt`,
`work/GenerationEnqueuerTest.kt`, `AppContainerTest.kt`, `ui/launchpad/LaunchpadConfirmTest.kt`,
`LaunchpadDraftControlsTest.kt`, `LaunchpadDraftStatusTest.kt`, `LaunchpadCoachStatusTest.kt`,
`ui/control/{ConnectionViewModelTest,MissionControlScreenTest,MissionControlViewModelTest}.kt`,
`settings/SettingsStoreTest.kt`, `rho/DaemonConnectionCheckTest.kt`.
`GenerationCancellerTest` seeds `Generation` rows directly, but it does set a host/token and calls
`container.missionManager.storeOutline(...)` (l.174) and `onAppOpen()`, so its outline case now
activates the week and leaves an extra QUEUED `FLIGHT_PLAN` row behind — that is the test the brief
means when it says a test "now sees an auto-queued row" without the file being edited.
No In-Flight, Landed, export/import, shell (`LiftoffShellTest`, `ShellNavTest`, `MainActivityTest`) or
domain test sets a host/token, so `advance` queues nothing in them.

`domain/CoachStatus.kt:60 deriveCoachStatus` reads only the newest **outline** row, so a FLIGHT_PLAN
QUEUED row cannot change the draft's status line — the draft status tests are safe unless they count
generations.

## The verify script (`.rho/runs/queue-flight-plans-bring-in-the-four-0a06/verify.sh`)

Structure: CI gate → twelve named tests read out of this run's JUnit XML reports → whole-class
"must not break" checks → the exported schema. `t <method>` looks the name up in `app/build/verify-work/methods`
(one awk pass over every `TEST-*.xml`, worst outcome kept per name); `c <Class>` looks up `…<Class>` in
`…/classes`. Gradle exits 0 for a name that never existed, so a missing name is reported "did not run" and counted
as a failure. The twelve names are copied from the source run's own verify.sh (`git show 7cda190:…/verify.sh`,
kept at `app/build/vmn4-verify.sh`) minus its three In-Flight names, which belong to the reset In-Flight commit this
brief excludes.

The twelve names, exactly as the four commits write them (verified against the per-commit patches at
`app/build/d1.txt`–`d4.txt`; each is `fun <name>() = runBlocking {` except the worker's, which is a plain `fun`):

| pick | name |
|---|---|
| `bcf6bae` | `storingAnOutlineQueuesSortieOnesFlightPlanAndReportsItsId` |
| `bcf6bae` | `continuingWithoutAnOutlineQueuesSortieOnesFlightPlanAndStillHandsBackTheOpenOutlineIdsToCancel` |
| `bcf6bae` | `landingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId` |
| `bcf6bae` | `scrubbingTheCurrentSortieQueuesTheNextSortiesFlightPlanAndReportsItsId` |
| `a74b099` | `aCurrentRunSortieWithRunGenerationOnQueuesAFlightPlanAndWritesNoSimplePlan` |
| `a74b099` | `aCurrentRunSortieWithRunGenerationOffGetsTheSimpleRunPlanAndQueuesNothing` |
| `a74b099` | `turningRunGenerationOnAfterwardsLeavesTheSimpleRunPlanThatSortieAlreadyHas` |
| `a74b099` | `aCurrentSortieQueuesNothingWhenMissionControlHasNoHostOrToken` |
| `a74b099` | `aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported` |
| `1afea86` | `aWorkerThatStoresAnOutlineEnqueuesTheFlightPlanItQueuedForSortieOne` |
| `a27d999` | `continueWithoutOutlineQueuesSortieOnesFlightPlanGenerationForEnqueuing` |
| `a27d999` | `scrubFromTheLaunchpadQueuesTheNextSortiesFlightPlanGenerationForEnqueuing` |

`a27d999` adds no test for Re-fly's cancel half (its commit message says "refly hands each id manager.refly reports
to cancelGeneration", but `LaunchpadReFlyTest` is not edited by any of the four) — the script says so in its header
and relies on `LaunchpadReFlyTest` / `MissionManagerReFlyTest` staying green plus Kyle reading the diff.

Gate: `timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, results dir moved
aside first (`mv`, never `rm`), up to three attempts, retried only on a hang / build-killed signature or when the
only failing test name is `doneBodyweightSetStepperShowsBW` (the recorded random Room-pool flake). No `gradlew --stop`.

Against the untouched branch (verified 2026-10-26, log `app/build/verify-run.log`): gate attempt 1 passed in 2m47s,
662 tests / 0 failures / 0 errors / 0 skipped, 50 checks passed, **12 failed — exactly the twelve names above, each
"did not run"**, schema check passed, exit 1.

## Docs and gate

- `ARCHITECTURE.md:82` is the M3 row (one 2128-char line). Its closing clause to replace is exactly:
  `Queueing Flight Plans and the Launchpad's display of the current sortie's status come next`.
  M5 (Re-fly) and M6 (run-generation toggle, run Flight Plans — "Not started") are other rows.
- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (`gradlew.sh` exports
  `ANDROID_HOME`). Scratch scripts/logs go under `app/build/`, run detached, `mkdir -p app/build` first.
- `app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json` is the only exported schema; no pick
  changes a `@Database` entity, so it must stay byte-identical. `data/SchemaExportTest.kt` exists.
- `CLAUDE.md`: `area: lowercase summary` messages, stage named files only (never `git add -A`),
  §2 vocabulary, short comments (why, not what), LF endings.

## Tasks step (2026-10-26) — what was re-checked against the files

- `tasks.md` has six tasks: one cherry-pick each (`bcf6bae`, `a74b099`, `1afea86`, `a27d999`), then the
  whole-suite gate, then the M3 row. Each pick task's Done-when is `git show --stat HEAD` matching that
  commit's own file list plus one cold class-scoped Gradle run; only the gate task runs the whole suite.
- The per-commit file lists were re-read from `git show --stat` and match the table above: `bcf6bae`
  3 files +171/-35, `a74b099` 1 file +157, `1afea86` 2 files +88/-3, `a27d999` 3 files +113/-11.
- None of the twelve names exists under `app/src/test` today (grep found no match), so every one arrives
  inside its pick and no task writes a test of its own.
- Every class the task gates name exists as a file: `MissionManagerTest`, `GenerationDaoTest`,
  `GenerationWorkerTest`, `GenerationCancellerTest`, `GenerationEnqueuerTest`, the eight Launchpad
  classes, `LiftoffShellTest`, `ShellNavTest`, `MainActivityTest`, `SchemaExportTest`.
- `GenerationDaoTest` has no reflection guard over `GenerationDao`'s method names, so the new
  `getOpenFlightPlanForSortie` cannot trip a name guard (the `MissionDaoTest` "delete" case is the
  shape this would have been).
- `MissionManagerTest` already has `private suspend fun configureCoach(host: String = "coach.local",
  token: String = "t0ken")` at l.85, used by ten existing cases; the picked cases use that helper.
- `ARCHITECTURE.md:82` is 2128 chars and the clause to replace occurs exactly once in the file.
