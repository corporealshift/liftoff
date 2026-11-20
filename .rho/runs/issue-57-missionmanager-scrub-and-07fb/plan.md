# Plan

## Approach
The codebase already has one shape for "this action supersedes the sortie's open generations": `refly`,
`regenerateFlightPlan`, `continueWithoutOutline` and `resetWeek` each read the open rows inside their
transaction and return their ids, and the view model cancels each id through the `cancelGeneration` helper the
`AppContainer` hands out (`GenerationCanceller.cancel`, which ends the work, marks the row CANCELLED and gives
up the nabu session). The fix puts `scrub` and `launch` into that shape rather than inventing a second one:
`MissionManager` reads the sortie's open FLIGHT_PLAN rows and returns their ids, and the Launchpad and In-Flight
view models cancel them. `scrub` already returns something the caller enqueues — the id `advance` queued for the
sortie that becomes current — so that channel moves to the `onQueued` callback the other superseding actions
already use, exactly as `continueWithoutOutline` does it. No new DAO query (`getOpenFlightPlanForSortie`
already exists), no change to `GenerationCanceller`, no new UI, and no DESIGN.md amendment: §7.3's "Superseded
results" rule already says this is what should happen. See the map for the current signatures and call sites.

## Files
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt` — `launch` returns the launched sortie's open
  FLIGHT_PLAN generation ids instead of Unit; `scrub` gains an `onQueued: (Long) -> Unit = {}` parameter for the
  id `advance` queued and returns the scrubbed sortie's open FLIGHT_PLAN ids instead of that id. Both read the
  rows inside the transaction, before the write. Doc comments follow `refly`'s wording.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` — `confirmScrub` hands `queueGeneration`
  to `scrub` as `onQueued` and cancels each id `scrub` returns; `launch` cancels each id `manager.launch` returns
  on the Planned branch. Both already hold `cancelGeneration`.
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt` — gains a
  `cancelGeneration: suspend (Long) -> Unit = {}` constructor parameter; `scrub` hands `{ enqueueQuietly(it) }` as
  `onQueued` and cancels each returned id.
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` — passes
  `cancelGeneration = { id -> container.generationCanceller().cancel(id) }`, the way `LaunchpadScreen` does.
- `app/src/test/java/com/liftoff/app/data/MissionManagerTest.kt` — the three tests that read `scrub`'s old
  `Long?` move to the `onQueued` form with their assertions intact; new tests for `scrub` and `launch` returning
  the seeded open rows (`seedFlightPlan` is already there) and returning nothing when none are open.
  `aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported` (~854-878) is the one that shows
  the shape: it scrubs a PENDING sortie that has its own open QUEUED row while `advance` reuses a *different*
  sortie's RUNNING row, so after the change the cancel list is the scrubbed sortie's row and `onQueued` gets the
  other id. Assert both.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadScrubTest.kt` — assert the cancel is called for the
  superseded row and that the queued id is still enqueued.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadLaunchTest.kt` (new) — plain view-model test in
  `LaunchpadScrubTest`'s style: a PLANNED sortie with an open FLIGHT_PLAN row, `viewModel.launch(...)` cancels that
  row's id and the row reads back CANCELLED.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt` — the view-model fixture gains a
  recording `cancelGeneration`; assert an In-Flight scrub cancels the sortie's open row and still enqueues the id
  it queued.

## Order
1. `MissionManager.launch` returns the launched sortie's open FLIGHT_PLAN ids. Nothing reads the value yet, so
   the build stays green.
2. `LaunchpadViewModel.launch` cancels them — the Launchpad half of the fix, with its test, stands on its own.
3. `MissionManager.scrub` gains `onQueued` and returns the scrubbed sortie's open ids. This is the only step that
   breaks call sites, so it carries its own fixes: `LaunchpadViewModel.confirmScrub` passes `queueGeneration` as
   `onQueued` and cancels the ids (it already holds `cancelGeneration`), `InFlightViewModel.scrub` passes
   `enqueueQuietly` as `onQueued` and leaves the list unread until step 4, and the three `MissionManagerTest` cases
   that read the old `Long?` move to the `onQueued` form.
4. `InFlightViewModel` gains `cancelGeneration`, `InFlightScreen` passes the container's canceller, and In-Flight
   `scrub` cancels the ids it was handed.
5. Read both manager methods and both view models once against DESIGN.md §7.3, run the full gate, and commit as
   `data: scrub and launch cancel the sortie's open generation`.

## Testing
- Scrubbing a PENDING sortie with an open QUEUED FLIGHT_PLAN row returns that row's id. MissionManager unit test
  over seeded rows (`seedFlightPlan`).
- Scrubbing a PLANNED or IN_FLIGHT sortie returns its open RUNNING row too — scrub is legal from all three states.
  MissionManager unit test.
- Scrub with nothing open returns an empty list and still scrubs; launch with nothing open returns an empty list
  and still launches. MissionManager unit tests.
- Launch on a PLANNED sortie with an open row returns that row's id. MissionManager unit test.
- The ids `scrub` returns are the scrubbed sortie's, and never the id `advance` queued for the sortie that became
  current; that id still reaches `onQueued`, and only after the transaction commits. MissionManager unit tests —
  this is what the three rewritten cases must keep asserting.
- The ids returned are exactly the sortie's open rows: QUEUED and RUNNING are both included, a row already
  SUCCEEDED, FAILED or CANCELLED is not. MissionManager unit tests — the manager only hands ids back, so it is the
  view-model tests that mark the row cancelled and read it back as CANCELLED.
- Launchpad `confirmScrub` cancels each id `scrub` returned and still enqueues the id it queued. View-model test
  with recording enqueue/cancel lambdas (`LaunchpadScrubTest`).
- Launchpad `launch` cancels the id `manager.launch` returned. View-model test (`LaunchpadLaunchTest`).
- In-Flight `scrub` cancels the sortie's open row and still enqueues the id it queued. View-model test
  (`InFlightLandScrubTest`).
- Nothing else moves: the `refly` / `regenerate` / `continueWithoutOutline` cancel lists, the app-open sweep, and
  the existing Launchpad and In-Flight card tests keep passing unchanged. The full unit suite is the check.

## Risks
- `scrub`'s return type is the risky part: it is not an unused value today, it is the id the next sortie's plan
  gets enqueued from, and three `MissionManagerTest` cases assert it. The work rewrites those cases in place onto
  `onQueued` rather than dropping them, and keeps the enqueue call in both view models. If an assertion cannot be
  expressed with `onQueued`, stop and re-check the shape instead of loosening the test.
- Cancelling must happen after the manager's transaction returns, as `refly` and `regenerate` already do:
  `GenerationCanceller` opens its own transaction.
- Read the open rows before the write, the way `regenerateFlightPlan` does. `advance` keys its own lookup by the
  *new* current sortie, so its row cannot leak into the scrubbed sortie's list, but reading afterwards is the trap
  that comment exists to warn about.
- `InFlightViewModel`'s new parameter defaults to a no-op, so a call site that forgets it cancels nothing silently.
  `InFlightScreen` passes the real canceller and the In-Flight test asserts the cancel was handed the id.
- View-model tests must not use the real `GenerationCanceller` — it reaches WorkManager and the nabu daemon. They
  record the ids and mark the row cancelled in the fake, the way `LaunchpadRegenerateTest` does, so a read-back is
  real without a daemon.
- Launch dropping a Regenerate the user asked for is a visible behaviour change: the plan the sortie flies is the
  one it already held. That is the decision above and belongs in the PR body. A generation that stores its plan
  just before the launch commits is a race this change does not address; `storePlan`'s refusal is what protects the
  scrubbed case.
- Scope: `land`, `unlaunch`, `resetWeek` and the app-open sweep all touch generations too. The brief names `scrub`
  and `launch`; the work does not extend the pattern to the others.
- Gate on this machine: this worktree's `gradlew.sh` is the committed Windows-only variant. Run the same tasks
  through `JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk
  ~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest`, and do not edit `gradlew.sh` —
  it is not one of this issue's files.

## Decisions
**Launch while a Regenerate is in flight: cancel the generation, or block the Launch?** The plan cancels the in-flight generation and lets Launch proceed, as the brief directs. The alternative is to disable the Launch button until the generation finishes, so the user always launches the freshest plan. Cancelling is what a user would expect: they pressed Launch, the plan on screen is the one they start, and the regeneration has been superseded — DESIGN.md §7.3 already says superseded generations are cancelled and their sessions stopped. Blocking the button would turn a working action into one that silently does nothing.

**Scrub mid-generation: does anything new appear in the UI?** No new copy or state. The cancelled generation lands in the same cancelled state a user-initiated cancel already produces, so any screen that shows generation status shows it as cancelled, and the existing app-open sweep behaviour is unchanged. The alternative — a "generation cancelled" message on the Launchpad or In-Flight screen — is out of scope for this issue and would need copy the brief does not ask for.

**Scrub and Launch keep working exactly as they do today when nothing is open.** The change adds a return value and a cancel; it does not add a guard. A PENDING sortie with no open generation scrubs as before, and a PLANNED sortie with no open generation launches as before. The alternative — refusing to scrub or launch while a generation is running — would be a visible behaviour change the brief does not ask for.

**Only the sortie's own open FLIGHT_PLAN generations are cancelled.** A scrub or a launch does not touch generations belonging to other sorties, or generations of other kinds. The brief names FLIGHT_PLAN generations of the sortie; a user who scrubs one sortie should not see another sortie's coach status change.
