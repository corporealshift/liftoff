# Plan

## Approach

Two steps, no production code written by this run. First, cherry-pick the five finished commits from
`rho/confirm-queues-the-outline-bring-in-the-gny6` by sha, in the brief's order, keeping their messages; the
source branch is checked out in another worktree, so it is never checked out here. The picks land on top of
`1f8d5fc goal: round 18 check`, which adds only a roadmap doc, so nothing they touch has moved under them. Then
add one view-model test file, `LaunchpadConfirmTest`, on the harness the brief names
(`LaunchpadDraftControlsTest`), in the view-model-only shape `LaunchpadDraftGuardsTest` already uses in this
package — Robolectric, in-memory Room, a real `SettingsStore` on a cache-dir file, a `MissionManager` with a
fixed clock, a `Dispatchers.Unconfined` scope, and recording `queueGeneration`/`cancelGeneration` lambdas — and
prove the four wiring cases the brief names. That shape is what the rest of this package already does, and it is
the only way to reach the view model without WorkManager: the screen's own factory
(`launchpadViewModel(container, scope)`) asks `AppContainer` for its enqueuer, so the new file builds the view
model through its constructor on its own database and manager instead.

## Files

**Written by this run (step 2):**

- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadConfirmTest.kt` — new. Four tests, view model + DAO
  only: no Compose, no compose rule, no WorkManager, no fake daemon.

**Changed only by the cherry-picks** (what each holds is in the map):

- `data/MissionManager.kt` — `confirm` becomes the queue path returning `ConfirmOutcome`, `continueWithoutOutline`
  is added and returns the open outline ids, `retryOutline` is added, `setPattern` gains the open-outline freeze.
- `data/GenerationDao.kt` — the two outline reads for a mission (the live `Flow` and the open-rows list).
- `domain/Coach.kt` — new file, `coachIsConfigured`.
- `ui/launchpad/LaunchpadViewModel.kt` — the two helper params, `notConfiguredMessage`, the three actions
  returning `Job?`, `retry()`, `continueWithoutOutline()`.
- `ui/launchpad/LaunchpadScreen.kt` — the `launchpadViewModel` factory and the draft's way out plus the
  not-configured line.
- Test files the picks also touch: `MissionManagerTest` (fixture moves plus the new confirm-queue,
  continue-without-outline and retryOutline cases), `GenerationDaoTest` (the new live outline read) and
  `LaunchpadDraftControlsTest` (the two new UI cases). The rest carry no new behaviour —
  `MissionManagerOutlineTest`, `MissionManagerPlanTest`, `LaunchpadStatesTest`, `LaunchpadReFlyTest`,
  `LaunchpadScrubTest`, `LaunchpadDraftGuardsTest`, `InFlightLandScrubTest`, `InFlightLeaveTest`,
  `InFlightRunTest`, `GenerationWorkerTest`: their `confirm(...)` fixtures become `continueWithoutOutline(...)`
  (all but `LaunchpadDraftGuardsTest`, which only gains the helper arguments), and the four Launchpad test files
  that build a view model — `DraftControls`, `DraftGuards`, `ReFly`, `Scrub` — gain the two no-op helper
  arguments.

**Not touched:** anything under `.rho/runs/`, `app/schemas/` (no Room schema change), the `work/` sources — the
worker, enqueuer and canceller and their tests stay as they are, apart from `GenerationWorkerTest`'s fixture
moves listed above — and `ARCHITECTURE.md` and `DESIGN.md` (the brief asks for no doc change; the PR body carries
the statement about the moved fixtures, which the picked commit messages already spell out).

## Order

1. Cherry-pick `c748582` (adds `continueWithoutOutline`, moves fixtures; behaviourally neutral). Compile check.
2. Cherry-pick `29b1821` (confirm queues, pattern freeze, `coachIsConfigured`, the two DAO reads). Compile check.
3. Cherry-pick `99aceec` (`retryOutline`). Compile check.
4. Cherry-pick `6a16f32` (view-model wiring; its own commit already updates the Launchpad test construction
   sites, so the constructor change never leaves a broken build). Compile check.
5. Cherry-pick `9dae7bb` (the draft's way out and the not-configured line, with its two UI tests).
6. Run the full gate detached — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` —
   and read the log plus the XML totals. Fix only what it names; expect nothing, since these commits were each
   gated on the source branch.
7. Write `LaunchpadConfirmTest` with the four cases; run just that class, then the whole gate again detached.
8. Commit the test file alone, message `test: cover the draft's confirm, way out and retry through the view
   model` (CLAUDE.md: `area: lowercase summary`), staging only that path.

Each cherry-pick is its own commit with its original message, so the branch shows the same five commits the PR
describes. No `run:` commit and no `.rho/runs/` file is picked.

## Testing

All four cases are Robolectric view-model + DAO tests in `LaunchpadConfirmTest` — no Compose, no WorkManager, no
fake daemon. The enqueue and cancel helpers are not re-tested here; `GenerationEnqueuerTest` and
`GenerationCancellerTest` already own them.

- **Confirm with a host and token set:** exactly one QUEUED OUTLINE row exists for the draft, its `sortieId` is
  null, its id reached the queue lambda exactly once, and the Mission is still DRAFT with no sorties — the row is
  the only thing Confirm did.
- **Confirm with no usable settings:** a blank host, a whitespace-only host and a blank token, each on a fresh
  draft, each writes no generation row, records nothing in either lambda, and leaves `notConfiguredMessage` at
  `NOT_CONFIGURED_MESSAGE`; a following Confirm with a host and token clears the line and queues.
- **Continue without outline after a Confirm:** the Mission is ACTIVE, sortie 1 is current (the state reaches
  `Planned` on the first sortie in pattern order), the queued id reached the cancel lambda once, and that row is
  CANCELLED.
- **Retry after a FAILED outline row:** a new QUEUED row is now the mission's newest non-cancelled outline, the
  FAILED row keeps its status and its error text, and the new id is recorded once by the queue lambda. The scope's
  `CoroutineExceptionHandler` records whatever escapes and every test asserts that list is empty, so a manager
  refusal the view model no longer swallows would fail loudly rather than pass quietly.
- **The picks did not break the rest:** the whole suite once at the end, not per-class gates — the fixture moves
  cross the In-Flight, Launchpad, Landed/Re-fly, worker and shell classes, and a per-class run never reaches
  them. `MissionManagerTest`, `GenerationDaoTest`, the five Launchpad classes, `InFlightLandScrubTest`,
  `InFlightLeaveTest`, `InFlightRunTest`, `GenerationWorkerTest`, `GenerationEnqueuerTest`,
  `GenerationCancellerTest`, `LiftoffShellTest`, `ShellNavTest`, `MainActivityTest`, the export/import and
  Mission Control / Test connection tests, and the domain tests all stay green.

## Risks

- **A pick conflicts.** Only `1f8d5fc` (a roadmap doc) sits above the merge-base, so a conflict means a pick
  touched a file round 18 touched: read `git show --stat` for the pick, resolve toward the picked content, and
  never resolve by dropping the pick's test changes.
- **A `run:` commit or a `.rho/runs/` file rides along.** Pick exactly the five shas; after each one, check its
  file list and drop any `.rho/runs/` path before committing.
- **`stateIn(Lazily)` makes a case pass vacuously.** An action called while `state` is still `Loading` returns
  null and writes nothing. Collect `state.first { it is LaunchpadState.Draft }` under a 5-second timeout before
  every action, assert the returned `Job` is non-null, and join it.
- **The cancel lambda must cancel the row for real.** It records its ids *and* calls
  `generationDao().markCancelled(id, finishedAt)`, the way `GenerationCanceller` does; otherwise "the row is
  CANCELLED" proves nothing about the wiring.
- **The fixed clock makes every `createdAt` identical**, so "newest" is decided by the `id DESC` tie-break in the
  DAO's ordering. Seed the FAILED row before the retried one and read the winner back through
  `getNewestNonCancelledOutlineForMission`.
- **`notConfiguredMessage` has a `private set`.** The only way to set it is to drive `confirm()` or `retry()` on
  blank settings; a test that assigns it will not compile.
- **The confirm behaviour change reaches 10 test classes' fixtures** (13 test files are touched in all). If a
  class on the brief's must-not-break list fails, fix which entry point its fixture calls — never weaken or delete
  an assertion, and never "fix" a test by moving it back to `confirm`.
- **A Launchpad test that builds a view model outside the four test files `6a16f32` updated** would fail to
  compile. The compile check after pick 4 names any such site; there is no other way to find it.
- **The gate can hang for about five minutes** on this machine and write no `TEST-*.xml`. Run it detached with a
  log and an `EXIT=$?` sentinel inside the script (not after a pipe), re-run the same tree before treating a hang
  as a failure, move a stale `app/build/test-results` with `mv` rather than `rm`, and never `gradlew --stop` —
  that kills every other worktree's daemon.
- **A schema file appearing would mean an entity or DAO change slipped into a pick.** Check
  `git diff --quiet 2a22c82 HEAD -- app/schemas` after the picks; the brief says no schema change.

## Decisions

**A configured Confirm leaves the Launchpad looking exactly as it did before.** After the picks, pressing Confirm with a host and token writes one QUEUED outline row and the draft stays a draft: no "Coach is planning…" line, no progress, no failure, no Retry. The alternative is to show something, since a tap that visibly does nothing looks broken. The picked UI commit `9dae7bb` draws only the way out and the not-configured line and says outright that "a later run owns" live outline status, failure and Retry, so this run adds no status display and no test asserts one.

**The not-configured line is cleared by the next attempt, and the new test proves that.** The brief states the rule (`notConfiguredMessage` is set by a Confirm or Retry that found no host or token, and cleared by a later Confirm or a successful continue-without-outline) but its case list only proves the setting. The alternative is to test only what is listed. The line lingering after the user fixes their settings in Mission Control and comes back is the part they would notice, so the plan asserts the clear inside the not-configured case rather than adding a fifth case.

**A Retry the manager refuses stays silent.** `retryOutline` throws `IllegalStateException` unless the draft's newest non-cancelled outline row has FAILED, and the picked wiring commit `6a16f32` swallows `IllegalStateException` in all three draft actions, so a mistimed Retry writes nothing, shows nothing and does not crash. The alternative is to surface "Nothing to retry." The plan does neither: the swallow is shipped by a commit this run only moves, no UI reaches `retry()` yet (`9dae7bb` draws no Retry control), and the brief's case list does not name the refusal.

**Continue-without-outline cancels the outstanding outline, and that is the end of it.** Taking the way out activates the week and marks the mission's open outline rows CANCELLED; the alternative is to let the coach finish and land the outline later. The brief mandates the cancel, and the row is already the one WorkManager would have driven, so cancelling it is what stops a generation from landing on a week the user has already started without one.

**Pressing the way out on a draft that never queued an outline is a no-op that still activates the week.** The button is drawn on every draft, so this is reachable without a prior Confirm: the manager returns an empty id list and the cancel lambda is called zero times. The alternative is to hide the button until an outline exists. The picked UI test `draftAlwaysOffersContinueWithoutOutline` already taps it on a draft with nothing queued and asserts the draft stops being a draft, so the new file covers only the after-a-Confirm case, which is the one that proves the cancel path.

**"Configured" means host and token each non-empty once trimmed.** A host of spaces, or a token padded with whitespace, counts as not configured and gets the warning line. The alternative is a stricter or looser check. This is not this run's choice — `coachIsConfigured` is picked in `29b1821` and is the same rule `AppContainer` and `DaemonConnectionCheck` already apply — but the brief names only a blank host, a whitespace-only host and a blank token, so the plan notes that the trim rule covers both fields and the test sets exactly the three settings the brief names.

