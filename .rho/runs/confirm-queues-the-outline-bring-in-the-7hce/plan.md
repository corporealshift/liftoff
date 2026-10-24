# Plan

## Approach

Two steps, no production code written by this run. First, cherry-pick the five finished commits from
`rho/confirm-queues-the-outline-bring-in-the-gny6` by sha, in the brief's order, keeping their messages. The
source branch is checked out in another worktree, so it is never checked out here. The picks land on top of
`1f8d5fc goal: round 18 check`, which adds only a roadmap doc, plus this run's own `run:` commits under
`.rho/runs/`, so nothing they touch has moved under them. Then add one view-model test file,
`LaunchpadConfirmTest`, on the harness the brief names (`LaunchpadDraftControlsTest`), in the view-model-only shape
`LaunchpadDraftGuardsTest` already uses in this package: Robolectric, in-memory Room, a real `SettingsStore` on a
cache-dir file, a `MissionManager` with a fixed clock, a `Dispatchers.Unconfined` scope plus a `Job` and a
recording `CoroutineExceptionHandler`, and recording `queueGeneration`/`cancelGeneration` lambdas. It proves the
four wiring cases the brief names. That shape is what the rest of this package already does. It is also the only
way to reach the view model without WorkManager: the screen's own factory (`launchpadViewModel(container, scope)`)
asks `AppContainer` for its enqueuer, so the new file builds the view model through its constructor, on its own
database and manager.

## Files

**Written by this run (step 2):**

- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadConfirmTest.kt`: new. View model + DAO only: no
  Compose, no compose rule, no WorkManager, no fake daemon.

**Changed only by the cherry-picks** (what each holds is in the map):

- `data/MissionManager.kt`: `confirm` becomes the queue path returning `ConfirmOutcome`; `continueWithoutOutline`
  is added and returns the open outline ids; `retryOutline` is added; `setPattern` gains the open-outline freeze.
- `data/GenerationDao.kt`: the two outline reads for a mission (the live `Flow` and the open-rows list).
- `domain/Coach.kt`: new file, `coachIsConfigured`.
- `ui/launchpad/LaunchpadViewModel.kt`: the two helper params, `notConfiguredMessage`, the three actions
  returning `Job?`, `retry()`, `continueWithoutOutline()`.
- `ui/launchpad/LaunchpadScreen.kt`: the `launchpadViewModel` factory, the draft's way out and the
  not-configured line.
- Test files the picks also touch:
  - `MissionManagerTest`: fixture moves, plus the new confirm-queue, continue-without-outline and `retryOutline`
    cases.
  - `GenerationDaoTest`: the new live outline read.
  - `LaunchpadDraftControlsTest`: the two new UI cases.
  - The rest carry no new behaviour: `MissionManagerOutlineTest`, `MissionManagerPlanTest`, `LaunchpadStatesTest`,
    `LaunchpadReFlyTest`, `LaunchpadScrubTest`, `LaunchpadDraftGuardsTest`, `InFlightLandScrubTest`,
    `InFlightLeaveTest`, `InFlightRunTest`, `GenerationWorkerTest`. All of them except `LaunchpadDraftGuardsTest`
    have their `confirm(...)` fixtures changed to `continueWithoutOutline(...)`.
  - The four Launchpad test files that build a view model (`DraftControls`, `DraftGuards`, `ReFly`, `Scrub`) gain
    the two no-op helper arguments. That is the only change to `LaunchpadDraftGuardsTest`.

**Not touched:**

- Anything under `.rho/runs/`.
- `app/schemas/`: there is no Room schema change.
- The `work/` sources. The worker, enqueuer and canceller and their tests stay as they are, apart from
  `GenerationWorkerTest`'s fixture moves listed above.
- `ARCHITECTURE.md` and `DESIGN.md`. The brief asks for no doc change. The PR body carries the statement about
  the moved fixtures, which the picked commit messages already spell out.

## Order

1. Cherry-pick `c748582`. It adds `continueWithoutOutline` and moves fixtures, and changes no behaviour. Compile
   check.
2. Cherry-pick `29b1821`: confirm queues, the pattern freeze, `coachIsConfigured`, the two DAO reads. Compile
   check.
3. Cherry-pick `99aceec`: `retryOutline`. Compile check.
4. Cherry-pick `6a16f32`: the view-model wiring. Its own commit already updates the Launchpad test construction
   sites, so the constructor change never leaves a broken build. Compile check.
5. Cherry-pick `9dae7bb`: the draft's way out and the not-configured line, with its two UI tests.
6. Run the full gate detached: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`.
   Read the log and the XML totals. Fix only what it names. Expect nothing, since each of these commits passed the
   gate on the source branch.
7. Write `LaunchpadConfirmTest`. Run just that class, then the whole gate again, detached.
8. Commit the test file alone, staging only that path, with the message `test: cover the draft's confirm, way out
   and retry through the view model` (CLAUDE.md: `area: lowercase summary`).

Each cherry-pick is its own commit with its original message, so the branch shows the same five commits the PR
describes. No `run:` commit and no `.rho/runs/` file is picked.

## Testing

All cases are Robolectric view-model + DAO tests in `LaunchpadConfirmTest`, with no Compose, no WorkManager and no
fake daemon. The enqueue and cancel helpers are not re-tested here; `GenerationEnqueuerTest` and
`GenerationCancellerTest` already own them. Each test does the following:

- Writes its settings (`setDaemonHost`, `setDaemonToken`, `setDefaultPattern` with an `L`-first pattern such as
  `"LRL"`) before `onAppOpen()`.
- Waits for `state` to reach `Draft` under a 5-second timeout.
- Calls the action, asserts the returned `Job` is non-null, and joins it under a timeout.
- Asserts that the exception handler's list is empty.

The cases:

- **Confirm with a host and token set:** exactly one QUEUED OUTLINE row exists for the draft, and its `sortieId`
  is null. The queue lambda recorded exactly `[thatId]`. The Mission is still DRAFT with no sorties. The row is
  the only thing Confirm did.
- **Confirm with no usable settings:** three tests, one per setting the brief names: a blank host, a
  whitespace-only host, and a blank token with a valid host. Each test gets its own fresh database and draft from
  `@Before`. In each, Confirm writes no generation row (`getAll()` is empty), neither lambda records anything,
  and `notConfiguredMessage` is `NOT_CONFIGURED_MESSAGE`. Then the test sets a valid host and token and confirms
  again. That Confirm clears the line and queues one row.
- **Continue without outline after a Confirm:** after the Confirm and its join, the test waits for `Draft` again,
  then calls `continueWithoutOutline()` and joins it.
  - The Mission is ACTIVE.
  - Sortie 1 is current: `currentSortie` over the mission's sorties is the lowest-index sortie, a LIFT.
  - Because the pattern starts with `L`, that sortie stays PENDING, waiting on a plan generation. So `state`
    reaches `LaunchpadState.Pending` for that sortie's id, not `Planned`.
  - The cancel lambda recorded exactly `[outlineId]`, and that row is CANCELLED.
- **Retry after a FAILED outline row:** the test creates the FAILED row the way production would.
  - It confirms first and joins.
  - It marks the queued row with `generationDao().markFailed(id, "<error text>", finishedAt)`.
  - It waits for `Draft`, then calls `retry()` and joins it.
  - A new QUEUED row is now the newest non-cancelled outline, read through
    `getNewestNonCancelledOutlineForMission`, and its id differs from the failed one.
  - The FAILED row keeps its status and its exact error text.
  - The queue lambda recorded `[failedId, newId]`: the new id once, after the first Confirm's id.

  The view model swallows `IllegalStateException`, so a refused retry would not reach the exception handler. The
  "new row exists" assertion is what catches that case.
- **The picks did not break the rest:** the whole suite runs once at the end; there are no per-class gates. The
  fixture moves cross the In-Flight, Launchpad, Landed/Re-fly, worker and shell classes, and a per-class run never
  reaches them. All of these stay green: `MissionManagerTest`, `GenerationDaoTest`, the five Launchpad classes,
  `InFlightLandScrubTest`, `InFlightLeaveTest`, `InFlightRunTest`, `GenerationWorkerTest`,
  `GenerationEnqueuerTest`, `GenerationCancellerTest`, `LiftoffShellTest`, `ShellNavTest`, `MainActivityTest`, the
  export/import tests, the Mission Control / Test connection tests, and the domain tests.

## Risks

- **A pick conflicts.** Only `1f8d5fc` (a roadmap doc) and this run's `.rho/runs/` commits sit above the
  merge-base, so a conflict means a pick touched a file one of those touched. Read `git show --stat` for the pick
  and resolve toward the picked content. Never resolve by dropping the pick's test changes.
- **A `run:` commit or a `.rho/runs/` file rides along.** Pick exactly the five shas. After each one, check its
  file list and drop any `.rho/runs/` path before committing.
- **`stateIn(Lazily)` makes a case pass vacuously.** An action called while `state` is still `Loading` returns
  null and writes nothing. Collect `state.first { it is LaunchpadState.Draft }` under a 5-second timeout before
  every action, assert the returned `Job` is non-null, and join it under a timeout.
- **The cancel lambda must cancel the row for real.** It records its ids *and* calls
  `generationDao().markCancelled(id, finishedAt)`, the way `GenerationCanceller` does. Otherwise "the row is
  CANCELLED" proves nothing about the wiring.
- **The fixed clock makes every `createdAt` identical**, so "newest" is decided by the `id DESC` tie-break in the
  DAO's ordering. The FAILED row comes from the first Confirm, so it always has the lower id. Read the winner back
  through `getNewestNonCancelledOutlineForMission`.
- **Expecting `Planned` after the way out.** With an `L`-first pattern, `prepareCurrent` returns
  `AwaitGeneration` for sortie 1, so it stays PENDING and the state is `Pending`. Waiting for `Planned` would time
  out.
- **One current-week draft per database.** With a fixed clock, `onAppOpen()` yields a single draft, so "a fresh
  draft" per not-configured setting means a separate `@Test` per setting, each on the database `@Before` builds.
- **`notConfiguredMessage` has a `private set`.** The only way to set it is to drive `confirm()` or `retry()` on
  blank settings. A test that assigns it will not compile.
- **The confirm behaviour change reaches the fixtures of 10 test classes** (13 test files are touched in all). If
  a class on the brief's must-not-break list fails, fix which entry point its fixture calls. Never weaken or
  delete an assertion, and never "fix" a test by moving it back to `confirm`.
- **A Launchpad test that builds a view model outside the four test files `6a16f32` updated** would fail to
  compile. The compile check after pick 4 names any such site; there is no other way to find it.
- **The gate can hang for about five minutes** on this machine and write no `TEST-*.xml`.
  - Run it detached with a log, and put an `EXIT=$?` sentinel inside the script, not after a pipe.
  - Re-run the same tree before treating a hang as a failure.
  - Move a stale `app/build/test-results` aside with `mv` rather than deleting it with `rm`.
  - Never run `gradlew --stop`: it kills every other worktree's daemon.
- **A schema file appearing would mean an entity or DAO change slipped into a pick.** Check
  `git diff --quiet 2a22c82 HEAD -- app/schemas` after the picks; the brief says no schema change.

## Decisions

**Added by review: each not-configured setting is its own test on its own database.** The brief says "each on a
fresh draft". The alternative is to reuse one draft and change settings between three Confirms. A fixed clock
gives one current-week draft per database, and a reused draft would carry the previous attempt's state. So the
review chose three `@Test`s, each with the blank setting written before `onAppOpen()`, as the brief's harness
rule says. The follow-up "fix settings, Confirm again" check runs inside each of them.

**A configured Confirm leaves the Launchpad looking exactly as it did before.** After the picks, pressing Confirm
with a host and token writes one QUEUED outline row, and the draft stays a draft: no "Coach is planning…" line, no
progress, no failure, no Retry. The alternative is to show something, since a tap that visibly does nothing looks
broken. The picked UI commit `9dae7bb` draws only the way out and the not-configured line, and says outright that
"a later run owns" live outline status, failure and Retry. So this run adds no status display and no test asserts
one.

**The not-configured line is cleared by the next attempt, and the new test proves that.** The brief states the
rule: a Confirm or Retry that found no host or token sets `notConfiguredMessage`, and a later Confirm or a
successful continue-without-outline clears it. But the brief's case list only proves the setting. The alternative
is to test only what is listed. The part the user would notice is the line lingering after they fix their
settings in Mission Control and come back, so the plan asserts the clear inside the not-configured case rather
than adding a separate case.

**A Retry the manager refuses stays silent.** `retryOutline` throws `IllegalStateException` unless the draft's
newest non-cancelled outline row has FAILED. The picked wiring commit `6a16f32` swallows `IllegalStateException`
in all three draft actions, so a mistimed Retry writes nothing, shows nothing and does not crash. The alternative
is to surface "Nothing to retry." The plan does neither, for three reasons: the swallow ships in a commit this run
only moves, no UI reaches `retry()` yet (`9dae7bb` draws no Retry control), and the brief's case list does not name
the refusal.

**Continue-without-outline cancels the outstanding outline, and that is the end of it.** Taking the way out
activates the week and marks the mission's open outline rows CANCELLED. The alternative is to let the coach finish
and land the outline later. The brief mandates the cancel. The row is the one WorkManager would have driven, so
cancelling it stops a generation from landing on a week the user has already started without one.

**Pressing the way out on a draft that never queued an outline is a no-op that still activates the week.** The
button is drawn on every draft, so this is reachable without a prior Confirm. The manager returns an empty id list
and the cancel lambda is called zero times. The alternative is to hide the button until an outline exists. The
picked UI test `draftAlwaysOffersContinueWithoutOutline` already taps it on a draft with nothing queued and
asserts the draft stops being a draft. So the new file covers only the after-a-Confirm case, which is the one that
proves the cancel path.

**"Configured" means host and token each non-empty once trimmed.** A host of spaces, or a token of spaces, counts
as not configured and gets the warning line. The alternative is a stricter or looser check. This is not this run's
choice: `coachIsConfigured` is picked in `29b1821`, and it is the same rule `AppContainer` and
`DaemonConnectionCheck` already apply. The brief names only a blank host, a whitespace-only host and a blank token,
so the plan notes that the trim rule covers both fields, and the test sets exactly the three settings the brief
names.
