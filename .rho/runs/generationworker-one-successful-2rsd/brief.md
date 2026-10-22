# GenerationWorker: one successful generation end to end

Add `GenerationWorker`, a WorkManager `CoroutineWorker` in `com.liftoff.app.work`. Given a `Generation` row id, it runs one coach turn and stores a valid reply. This run covers only the success path. A later run adds repairs and the failure outcomes.

Read only what's named here. An earlier attempt stalled because it read too much at once. Write the worker file early, then build.

Build on:
- the turn runner already in `com.liftoff.app.work`, with its fake-daemon tests. It creates or reuses the rho session, sends the prompt with a client_id, follows events until the turn ends, answers ui.ask and permission.request, and returns the session id, the cursor, the end state and the last assistant text. It also has a stop helper.
- `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`, which scripts turns: `script(DaemonScript.Assistant(text), DaemonScript.State("idle"))`.
- `AppContainer`, reached in the worker as `(applicationContext as LiftoffApplication).container`. It has `database`, `settingsStore`, `missionManager`, `daemonConnectionCheck()` (whose `check()` returns `ConnectionOutcome.Connected` or another outcome) and `daemonClient()` (unconnected; the worker connects and closes it).
- `GenerationDao`: `get(id)`, `markRunning(id)`, `saveSessionId(id, s)`, `saveLastEventId(id, e)`, `markSucceeded(id, finishedAt)`, `markFailed(id, error, finishedAt)`. The rho session id lives in the `nabuSessionId` column; the row also has `kind` (OUTLINE or FLIGHT_PLAN), `missionId`, `sortieId?`, `status`, `lastEventId?` and `attempt` (starts at 0).
- `PromptInputLoader(db, settingsStore)`: `outlineInput(missionId)` and `flightPlanInput(missionId, sortie.index)`. `CoachPromptBuilder.prompt(input)`.
- `CoachReplyValidator.validate(text, GenerationContext(kind, mission.pattern, sortie?.type, activeEquipmentKeys, settings.sortieLengthMinutes))` returns `CoachReplyResult.Valid` or `CoachReplyResult.Invalid(errors)`. The active keys come from `equipmentDao().observeActive().first()`.
- `MissionManager.storeOutline(missionId, valid)` and `storePlan(sortieId, valid)` return `StoreOutcome.Stored` or `StoreOutcome.Refused(reason)`.

The worker:
- Missing row: `Result.failure()`, and do nothing. A row already SUCCEEDED, FAILED or CANCELLED: leave it unchanged and return `Result.success()`.
- Mark the row RUNNING. Run the connection check, and connect the client only on `Connected`. For any other outcome, return `Result.retry()` for now.
- Run the turn with:
  - workspace = `settings.coachWorkspacePath`
  - labels `["liftoff", "liftoff:outline"]` or `["liftoff", "liftoff:flight-plan"]` (never starting with `run:`)
  - a one-line description
  - client_id `<generationId>-<attempt>`
  - the row's stored session id and cursor
  - a 20-minute limit
  - saving the session id and cursor to the row through the callbacks
- On an idle or completed end with a valid reply: store it and mark the row SUCCEEDED. Then stop the rho session. Close the client on every path. Other ends return `Result.retry()` for now; the next run refines them.

Test traps, each found by reading this code:
1. Use one container only. Tests must use `(ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication).container`, never a second `AppContainer(context)`. A second one would open a second DataStore and a second Room database on the same files.
2. Nothing seeds equipment. Insert the four active keys `barbell`, `dumbbell`, `bench` and `pullup-bar` (`CoachFixtures.KEYS`) through `equipmentDao().insertAll`, or the lift fixture fails as unknown equipment.
3. `container.missionManager` uses the real clock. Seed with `onAppOpen()`, so the DRAFT is for the current week. A fixed past date makes `storeOutline` refuse ('the week … has ended').
4. Seeding for each case:
   - outline: `onAppOpen()` only, then a row with kind OUTLINE and no sortie.
   - lift: `onAppOpen()`, `confirm(id)`, then `scrub` the index-0 sortie. Index 1 is then current and PENDING.
   - run: `settingsStore.setGenerateRunPlans(true)` before `onAppOpen()`, then `confirm(id)`. Index 0 is a current PENDING run.
   Check the default pattern if a case needs a lift or a run at a given index.
5. Write the fake's `host()` and `port()`, a non-blank token and a coach workspace path into `settingsStore`. Build the worker with `TestListenableWorkerBuilder<GenerationWorker>(context).setInputData(...)`, and run `doWork()` inside `runBlocking { withTimeout(...) }`. In teardown, stop the fake, then close the database, delete `liftoff.db` and delete the settings file, the way the existing screen tests do.

Replies come from `CoachFixtures.fixture(OUTLINE | LIFT_PLAN | RUN_PLAN)`.

Tests, Robolectric `@Config(sdk = [34])`:
- outline happy path: `Result.success()`, the row SUCCEEDED with `nabuSessionId` and `lastEventId` set, the Mission ACTIVE with sortie 1 current
- lift Flight Plan happy path: the sortie PLANNED, and the plan stored with source GENERATED
- run Flight Plan happy path: the same, for the run
- a row that already has a session id: no `rho.session.create` frame
- a missing row returns failure, and a SUCCEEDED row is left unchanged
- one `rho.session.stop` frame after success

Every wait has a timeout, and nothing depends on timing, so the tests pass on the GitHub CI runner (ubuntu-latest, UTC). Don't change the Room schema. `coach` stays free of Android imports.

Must not break: the turn runner's tests, the coach, data and domain tests, Test connection, and the build gate. Follow CLAUDE.md conventions. The full gate can hang once for about 5 minutes on this machine. Run it detached, and re-run it if that happens.

Done when: the tests above pass, ARCHITECTURE.md's `work` row names the worker, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
