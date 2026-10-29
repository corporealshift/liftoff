# Tasks

Four tasks, in this order. Task 1 brings in the finished derivation and changes nothing by hand. Tasks 2 and 3 are
the two halves of the PENDING card's drawing, each proved by the half of the new test class it makes possible — 3
adds to the file 2 creates. Task 4 is the whole gate.

`verify.sh` names exactly five new tests by method name, split 3 / 2 across tasks 2 and 3, spelled exactly as
`verify.sh` spells them. It names no other new test. In particular it does **not** name a test for the
`regenerateAvailable == false` half, and says that half is proved by the plan's mutation check. That check cannot
bite on its own: dropping `&& pending.regenerateAvailable` makes the guard **weaker**, and on every card the five
named cases build — a LIFT sortie — `regenerateAvailable` is already true, so RETRY is drawn either way and no named
test changes. Task 3 therefore writes the one case that does reach `regenerateAvailable == false` (the map worked out
how), and the mutation check bites on it. Its eleven whole-class checks are the six Launchpad classes the brief names
plus `LiftoffShellTest`, the other four Launchpad classes, and nothing else — `MissionManagerTest` is not one of
them.

- [x] 1. Bring in the derivation: `regenerateAvailable` on the Pending and Planned cards
  Covers plan step 1, in one commit. Cherry-pick `e2199c5` from `rho/draw-generation-status-regenerate-and-n766` with
  its message unchanged, staging only its two files and nothing from that branch's `.rho/runs/`. That branch's newer
  commit `e53b0d6` is its own run's bookkeeping and is not brought in. If the pick cannot be made, write the same
  change by hand at the four construction sites the map lists in `LaunchpadState.kt`, using the brief's expression
  `regenerateAvailable = coachConfigured && (current.type != SortieType.RUN || generateRunPlans)` and `false` in
  `deriveActive`'s fallback, and commit with the brief's message.
  Files it ends up touching: `app/src/main/java/com/liftoff/app/data/MissionManager.kt` (`generateRunPlans:
  Flow<Boolean>` beside `coachConfigured`, plus the KDoc paragraph on `regenerateFlightPlan`) and
  `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` (`regenerateAvailable` on `Planned` after
  `rhoSessionId` and on `Pending` after `reFlyAvailable`, `manager.generateRunPlans` joined into the PENDING/PLANNED
  `combine`, set at all four construction sites). No test edits: no test builds either state directly.
  Tests it writes: none. `LaunchpadSortieStatusTest`, `LaunchpadStatesTest` and `LaunchpadRegenerateTest` must pass
  unedited.
  Done when: `git log -1 --stat` shows a commit whose subject is `e2199c5`'s own and lists only those two files, and
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.launchpad.LaunchpadSortieStatusTest" --tests
  "com.liftoff.app.ui.launchpad.LaunchpadStatesTest" --tests
  "com.liftoff.app.ui.launchpad.LaunchpadRegenerateTest"` passes cold with every case in all three classes green.

- [x] 2. `PendingContent`: draw the coach status where the fixed line is, and the three renders that need no Retry
  Covers plan step 2. File: `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` only. In
  `PendingContent`, replace the fixed "Flight Plan not ready yet." line with a status line of text plus colour,
  worked out by a private file-level helper in
  `draftStatusLine`'s shape (text + colour, `LiftoffType.note()`): Planning → 'Coach is planning…' in `Muted`,
  Offline → 'Mission Control offline — retrying' in `Muted` (the existing `OFFLINE_LINE` already says that
  sentence), NotConfigured → `NOT_CONFIGURED_MESSAGE` in `Muted`, Failed → `reason` in `Red` with each
  `validatorErrors` entry on its own line under it in `Muted` (the plan's Decision; never re-split the raw row
  error), Idle and RePlanning → 'Flight Plan not ready yet.' kept as the fallback so the `when` stays exhaustive.
  The pending card needs its own planning constant: `PLANNING_LINE` is the draft's 'Coach is planning the week…' and
  `LaunchpadDraftStatusTest` looks for it, so keep both. Theme parts only, from `com.liftoff.app.ui.theme`.
  File: `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadPendingStatusTest.kt` (new). Copy
  `LaunchpadDraftStatusTest`'s setUp/tearDown harness rather than rebuilding it: in-memory Room with
  `allowMainThreadQueries()`, a real `SettingsStore` over a `PreferenceDataStoreFactory` file in `cacheDir`,
  `MissionManager` on `Clock.fixed(2026-01-12T00:00:00Z, UTC)` with `zone = { ZoneOffset.UTC }`, a
  `CoroutineScope(Dispatchers.Unconfined + Job())`, a `LaunchpadViewModel` with no-op `queueGeneration` /
  `cancelGeneration`, `LaunchpadContent(viewModel, onOpenInFlight = {})` inside `LiftoffTheme`,
  `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")`, every wait a `composeRule.waitUntil(timeoutMillis = 5_000)`
  on a known string, and a `tearDown` that cancels the scope's `Job` **before** `db.close()`. Reach the PENDING card
  the way `LaunchpadSortieStatusTest.activeWeek()` does — `setDaemonHost("127.0.0.1")`, `setDaemonToken("t")`,
  `setDefaultPattern("LRL")`, `manager.onAppOpen()`, `manager.continueWithoutOutline(missionId)` — then change the
  status by writing to the row `continueWithoutOutline` queued, read with
  `db.generationDao().getOpenFlightPlanForSortie(sortieId)`: offline is `update(row.copy(error =
  Generation.COACH_OFFLINE))`. No seeded row may carry a `createdAt` later than the fixed clock's. No text fields, no
  dialogs, no WorkManager.
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `pendingCardShowsThePlanningLineAndNoRetry` (the QUEUED row: 'Coach is planning…' displayed in place of the fixed
  line, and no `RETRY` node on screen at all),
  `pendingCardShowsTheOfflineLineAndNoRetry` (that row carrying `Generation.COACH_OFFLINE`: 'Mission Control offline
  — retrying' displayed, no `RETRY`),
  `pendingCardShowsThatMissionControlIsNotSetUpAndNoRetry` (host and token left blank, so `advance` queues no row:
  `NOT_CONFIGURED_MESSAGE` displayed, no `RETRY`).
  Do not weaken or edit an existing Launchpad test to make these pass.
  Done when: one cold run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.launchpad.LaunchpadPendingStatusTest" --tests
  "com.liftoff.app.ui.launchpad.LaunchpadDraftStatusTest" --tests
  "com.liftoff.app.ui.launchpad.LaunchpadReFlyTest"` passes with those three cases green and the other two classes
  unedited and fully green, and the run wrote a `TEST-*.xml` naming all three new cases.

- [x] 3. `PendingActions`: the Retry button, the renders that need it, and the one that must not
  Covers plan step 3, in `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`, plus the cases that
  depend on the button, added to the class task 2 created — read what it left there first and reuse its seeding
  helpers.
  `PendingActions` gains a Retry button drawn **above** the Re-fly line and Scrub, only
  when `pending.generationStatus` is `Failed` **and** `pending.regenerateAvailable`; Re-fly, the "No earlier plan to
  re-fly." line and Scrub stay exactly as they are. The Pending arm of `ActionButtons` has no regenerate callback
  today — its `onRetry` is the draft's outline retry — so it gains its own callback bound to
  `viewModel.regenerate()` and passes it down. `PrimaryButton` upper-cases its label, so the tests look for `RETRY`;
  it has no `enabled` parameter, so an unavailable Retry is absent, never dimmed.
  Tests it writes, spelled exactly as `verify.sh` uses them:
  `pendingCardShowsAFailedGenerationsReasonAndEachValidatorErrorOnItsOwnLineAndRetry` (the row marked with
  `generationDao().markFailed(row.id, "invalid output: sets must not be empty; reps must be at least 1", <a later
  millis>) so the domain splits it: 'invalid output' displayed as its own line, 'sets must not be empty' and 'reps
  must be at least 1' each displayed as its own line, and `RETRY` displayed and enabled),
  `tappingRetryOnAFailedPendingCardQueuesAFreshGenerationAndShowsThePlanningLine` (the same FAILED row: tapping
  `RETRY` moves the card to 'Coach is planning…' where the failure was, which is the new QUEUED row winning
  `createdAt DESC, id DESC` — the cancel helper is a no-op here, so the FAILED row stays FAILED).
  Also write the one case `verify.sh` does not name, because it is the only observable proof that the field task 1
  brought in reaches the card — the map's "toggle case" section worked it out:
  `retryIsAbsentOnAFailedRunSortieWhileGenerateRunPlansIsOffAndDrawnWhileItIsOn`. Setup: host and token set, then
  `setGenerateRunPlans(true)` **before** `onAppOpen()` — `continueWithoutOutline` is what prepares the current sortie
  and reads the setting then — on the **default** pattern `RLRLR`, whose index 0 is a RUN, so `prepareCurrent` gives
  `AwaitGeneration` rather than `SimplePlan` and the current sortie stays PENDING with one QUEUED FLIGHT_PLAN row;
  `markFailed` that row. `GenerationWorkerTest`'s run cases seed it exactly this way, so copy their seeding rather
  than inventing one. Assert `RETRY` is drawn **before** the toggle is flipped — otherwise the absence proves
  nothing — then `setGenerateRunPlans(false)` mid-render and wait for the card to change: the 4-flow `combine`
  re-emits, `regenerateAvailable` becomes false, `RETRY` is gone, and the failure's reason is still drawn. Nothing
  re-runs `prepareCurrent`, so the card stays PENDING. If the mid-render toggle flip cannot be made to re-emit, say
  what you found in the commit body rather than dropping the assertion.
  Then prove the `&& pending.regenerateAvailable` half bites: back up `LaunchpadScreen.kt` byte-for-byte under
  `app/build/`, drop that condition, run the class cold, and confirm the toggle case fails because `RETRY` stays
  drawn after the toggle goes off — the five `verify.sh`-named cases cannot fail this mutation, since their LIFT card
  has `regenerateAvailable` true either way. Restore with `cp` (a `git checkout -- file` is denied in a runner
  session) and re-run green. Keep the mutation log in its own file under `app/build/`, never staged.
  Done when: one cold run `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache --tests
  "com.liftoff.app.ui.launchpad.LaunchpadPendingStatusTest" --tests
  "com.liftoff.app.ui.launchpad.LaunchpadReFlyTest"` passes with every case in the new class green — task 2's three
  included — `LaunchpadReFlyTest` unedited and fully green, the reports naming all five of `verify.sh`'s names, and
  the mutation log showing the toggle case failing while the condition was out. Record the mutation log path and its
  result in the commit body, since the evidence is not a file in the repo.

- [ ] 4. The whole gate, run once, and any fallout it turns up
  Covers plan step 5 and the brief's Done-when. Run
  `.rho/runs/pending-card-coach-status-lines-and-j5et/verify.sh` detached through a copy under `app/build/`
  (`mkdir -p app/build` first; run the copy, never the real file, so editing it mid-run cannot give a phantom syntax
  error), logging to `app/build/` with an `EXIT=$?` sentinel on its own line and no pipe — a status after a pipe is
  `tail`'s, not the build's. It moves `app/build/test-results/testDebugUnitTest` aside with `mv` itself and runs
  `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, then looks up every name and class it
  lists. One Gradle invocation in this worktree at a time; never `rm` the results dir (a stranded test executor holds
  `binary/output.bin`); never `gradlew --stop` (machine-wide). A hang that writes no `TEST-*.xml` is the hang the
  brief warns about — the script re-runs the gate itself on that signature; if it hangs twice, find the worker JVM
  and `jstack` it rather than re-running again.
  Fallout the brief allows: if an existing Compose class can no longer see a control because the new lines crowd its
  window, give that class `@Config(sdk = [34], qualifiers = "w411dp-h2000dp")` and say so in the commit message,
  which is where the pull-request note comes from. The actions live in the Scaffold `bottomBar`, so only a taller bar
  can crowd them. Do not weaken an assertion.
  Done when: the log ends with `EXIT=0` and `BRIEF DONE`, with the five new names ✓ and all eleven whole-class checks
  ✓, and the report totals it prints show 0 failures and 0 errors. Fix and commit anything the gate turns up; if it
  turns up nothing, record the log path and the report totals in a commit message so the run's evidence is in the
  history.
