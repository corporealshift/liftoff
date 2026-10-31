# Confirm queues the outline: bring in the five finished commits and test the view model

Bring finished work onto this branch, then add one small test file. Nothing else.

Step 1: cherry-pick. The unmerged local branch `rho/confirm-queues-the-outline-bring-in-the-gny6` holds five finished code commits. It is checked out in another worktree, so cherry-pick by sha (do not `git checkout` it). Pick exactly these, in this order, keeping their messages:
1. `c748582` data: add MissionManager.continueWithoutOutline and move the confirm fixtures to it
2. `29b1821` data: confirm queues the week's outline, and an open outline row freezes the pattern
3. `99aceec` data: add MissionManager.retryOutline for a draft whose outline failed
4. `6a16f32` launchpad: wire the draft's actions to the enqueue and cancel helpers
5. `9dae7bb` launchpad: draw the draft's way out and the not-configured line
Do not bring over any `run:` commit or any file under `.rho/runs/`. Then run the gate. If it is red, fix only what it names.

What exists after the picks (you do not need to re-derive this):
- `MissionManager.confirm(missionId): ConfirmOutcome` (`com.liftoff.app.data`). `ConfirmOutcome` is `Queued(generationId: Long)` or `CoachNotConfigured`. Queued inserts, or reuses, one QUEUED OUTLINE `Generation` row (`sortieId = null`). CoachNotConfigured writes nothing. Either way the Mission stays DRAFT.
- `MissionManager.continueWithoutOutline(missionId): List<Long>` activates the week, makes sortie 1 current, and returns the ids of the Mission's still-open outline rows.
- `MissionManager.retryOutline(missionId): ConfirmOutcome`. It refuses with `IllegalStateException` unless the Mission is the current week's DRAFT and its newest non-cancelled OUTLINE row is FAILED.
- `setPattern` throws `IllegalStateException` while an outline row is QUEUED or RUNNING.
- `com.liftoff.app.domain.coachIsConfigured(host, token)`. `GenerationDao.observeNewestNonCancelledOutlineForMission(missionId): Flow<Generation?>` and `GenerationDao.getOpenOutlineForMission(missionId): List<Generation>`.
- `LaunchpadViewModel(db, manager, scope, queueGeneration: (Long) -> Unit, cancelGeneration: suspend (Long) -> Unit)`. Its `confirm()`, `continueWithoutOutline()` and `retry()` each return `Job?`: null when the state isn't `LaunchpadState.Draft`. They enqueue or cancel only after the manager call has returned. `notConfiguredMessage` holds `NOT_CONFIGURED_MESSAGE` after a Confirm or Retry that found no host or token. A later Confirm clears it, and so does a successful continue-without-outline. `state` is `stateIn(scope, Lazily, Loading)`, so wait for it to reach `Draft` before calling an action.
- `LaunchpadScreen.kt` has `internal fun launchpadViewModel(container, scope)`. It passes lambdas, not bound `::enqueue` references, because those would build WorkManager while composing and break `LiftoffShellTest`, `ShellNavTest` and `MainActivityTest`. Keep it that way.

Step 2: one new test file, `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadConfirmTest.kt`. Do not use WorkManager in it. The enqueue and cancel helpers already have their own tests (`GenerationEnqueuerTest`, `GenerationCancellerTest`). Hand the view model a `queueGeneration` lambda that records the ids it gets, and a `cancelGeneration` lambda that records its ids and marks the row cancelled through `GenerationDao.markCancelled(id, finishedAt)`, the way the real helper does. Copy the harness from `LaunchpadDraftControlsTest`: in-memory Room, a real `SettingsStore` on a cache-dir file, a `MissionManager` with a fixed clock, `@Config(sdk = [34])`, and a scope on `Dispatchers.Unconfined` plus a `Job` that is cancelled before the database closes. Set the host, the token and a default pattern starting with `L` before `onAppOpen()`. Join each returned `Job`. Put a timeout on every wait. CI is ubuntu-latest, UTC. Cases:
- Confirm with a host and token set: one QUEUED OUTLINE row, its id recorded once by the queue lambda, and the Mission still DRAFT with no sorties.
- Confirm with a blank host, a whitespace-only host, and a blank token, each on a fresh draft: no row, nothing recorded, and `notConfiguredMessage` set.
- Continue without outline after a Confirm: the Mission is ACTIVE, sortie 1 is current, the outline id has been passed to the cancel lambda, and the row is CANCELLED.
- Retry after a FAILED outline row with an error: a new QUEUED row is now the newest non-cancelled outline, the FAILED row keeps its status and error text, and the new id is recorded by the queue lambda.

No Room schema change. In the PR, say which existing tests the cherry-picked commits changed and why: confirm no longer activates the week, so fixtures moved to `continueWithoutOutline`.

Must not break: the worker, enqueue and cancel tests, In-Flight and reopening, Landed, Re-fly, export/import, Mission Control and Test connection, the domain tests, the shell tests, the Launchpad tests, and the build gate. Follow CLAUDE.md conventions and the §2 vocabulary. When something is unclear, decide, record the decision in the plan, and carry on rather than stopping to ask. The full gate can hang once for about 5 minutes on this machine. Run it detached, and re-run it if that happens.

Done when: all five commits are on the branch, the four cases pass, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
