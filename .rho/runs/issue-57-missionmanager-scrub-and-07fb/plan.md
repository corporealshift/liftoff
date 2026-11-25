# Plan

## Approach
The codebase already has one shape for "this action supersedes the sortie's open generations": `refly`,
`regenerateFlightPlan`, `continueWithoutOutline` and `resetWeek` each read the open rows inside their
transaction and return their ids, and the view model cancels each id through the `cancelGeneration` helper the
`AppContainer` hands out (`GenerationCanceller.cancel`, which ends the work, marks the row CANCELLED and gives
up the nabu session). The fix puts `scrub` and `launch` into that shape rather than inventing a second one:
`MissionManager` reads the sortie's open FLIGHT_PLAN rows and returns their ids, and the Launchpad and In-Flight
view models cancel them. `scrub` already returns something the caller enqueues: the id `advance` queued for the
sortie that becomes current. That channel moves to the `onQueued` callback the other superseding actions
already use, exactly as `continueWithoutOutline` does it.

Both screens close right after these actions. In-Flight's `watchLeave` closes it once the sortie stops being
IN_FLIGHT, and Launch navigates to In-Flight. Their view models run on `rememberCoroutineScope`, so cancels
started there can be killed part-way. Reset week hit the same problem and solved it with the shell's scope,
passed in as `actionScope` (`MissionViewModel`, `MissionScreen`, `LiftoffShell`). These cancels use that same
pattern. No new DAO query (`getOpenFlightPlanForSortie` already exists), no change to `GenerationCanceller`, no
new UI, and no DESIGN.md amendment: §7.3's "Superseded results" rule already says this is what should happen.
See the map for the current signatures and call sites.

## Files
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt`: `launch` returns the launched sortie's open
  FLIGHT_PLAN generation ids instead of Unit. `scrub` gains an `onQueued: (Long) -> Unit = {}` parameter for the
  id `advance` queued, and returns the scrubbed sortie's open FLIGHT_PLAN ids instead of that id. Both read the
  rows inside the transaction, before the write. Doc comments follow `refly`'s wording.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`: gains
  `actionScope: CoroutineScope = scope`, documented as `MissionViewModel`'s is. `confirmScrub` hands
  `queueGeneration` to `scrub` as `onQueued`. `launch`, on the Planned branch, takes the ids `manager.launch`
  returns. Both start one `actionScope.launch { cancelGeneration(id) }` per id and do not wait for them, so
  `onLaunched` navigates without waiting on a daemon round trip.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`: `LaunchpadScreen` and
  `launchpadViewModel` take an `actionScope` and pass it to the view model.
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt`: gains
  `cancelGeneration: suspend (Long) -> Unit = {}` and `actionScope: CoroutineScope = scope`. `scrub` hands
  `{ enqueueQuietly(it) }` as `onQueued`, then starts the cancels on `actionScope` before `leaveOnce`.
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt`: passes
  `cancelGeneration = { id -> container.generationCanceller().cancel(id) }`, the way `LaunchpadScreen` does, plus
  the `actionScope` it is now given.
- `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`: passes `shellScope` as `actionScope` to
  `LaunchpadScreen` and `InFlightScreen`, as it already does for `MissionScreen`.
- `app/src/test/java/com/liftoff/app/data/MissionManagerTest.kt`: the three tests that read `scrub`'s old
  `Long?` move to the `onQueued` form with their assertions intact. New tests check that `scrub` and `launch`
  return the seeded open rows (`seedFlightPlan` is already there), and return nothing when none are open.
  `aSortieWithAnOpenFlightPlanGenerationIsNotQueuedTwiceAndThatRowsIdIsReported` (~854-878) is the one that shows
  the shape. It scrubs a PENDING sortie that has its own open QUEUED row while `advance` reuses a *different*
  sortie's RUNNING row. After the change, the cancel list is the scrubbed sortie's row and `onQueued` gets the
  other id. Assert both.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadScrubTest.kt`: assert the cancel is called for the
  superseded row and that the queued id is still enqueued.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadLaunchTest.kt` (new): plain view-model test in
  `LaunchpadScrubTest`'s style. A PLANNED sortie with an open FLIGHT_PLAN row: `viewModel.launch(...)` cancels
  that row's id, the row reads back CANCELLED, and `onLaunched` is still called.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt`: the view-model fixture gains a
  recording `cancelGeneration` and a separate action scope. Assert that an In-Flight scrub cancels the sortie's
  open row and still enqueues the id it queued.

## Order
1. `MissionManager.launch` returns the launched sortie's open FLIGHT_PLAN ids. Nothing reads the value yet, so
   the build stays green.
2. `LaunchpadViewModel` gains `actionScope`, and `LaunchpadScreen` and `LiftoffShell` pass the shell's scope.
   `launch` cancels the returned ids there. This is the Launchpad half of the fix, and it stands on its own with
   its test.
3. `MissionManager.scrub` gains `onQueued` and returns the scrubbed sortie's open ids. This is the only step that
   breaks call sites, so it carries its own fixes. `LaunchpadViewModel.confirmScrub` passes `queueGeneration` as
   `onQueued` and cancels the ids on `actionScope`. `InFlightViewModel.scrub` passes `enqueueQuietly` as
   `onQueued` and leaves the list unread until step 4. The three `MissionManagerTest` cases that read the old
   `Long?` move to the `onQueued` form.
4. `InFlightViewModel` gains `cancelGeneration` and `actionScope`. `InFlightScreen` passes the container's
   canceller and the shell's scope, and In-Flight `scrub` cancels the ids it was handed.
5. Read both manager methods and both view models once against DESIGN.md §7.3, run the full gate, and commit as
   `data: scrub and launch cancel the sortie's open generation`.

## Testing
- Scrubbing a PENDING sortie with an open QUEUED FLIGHT_PLAN row returns that row's id. MissionManager unit test
  over seeded rows (`seedFlightPlan`).
- Scrubbing a PLANNED or IN_FLIGHT sortie returns its open RUNNING row too, because scrub is legal from all
  three states. MissionManager unit test.
- Scrub with nothing open returns an empty list and still scrubs. Launch with nothing open returns an empty list
  and still launches. MissionManager unit tests.
- Launch on a PLANNED sortie with an open row returns that row's id. MissionManager unit test.
- The ids `scrub` returns are the scrubbed sortie's, never the id `advance` queued for the sortie that became
  current. That id still reaches `onQueued`, and only after the transaction commits. MissionManager unit tests;
  this is what the three rewritten cases must keep asserting.
- The ids returned are exactly the sortie's open rows: QUEUED and RUNNING are both included, and a row already
  SUCCEEDED, FAILED or CANCELLED is not. MissionManager unit tests. The manager only hands ids back, so the
  view-model tests are the ones that mark the row cancelled and read it back as CANCELLED.
- Launchpad `confirmScrub` cancels each id `scrub` returned and still enqueues the id it queued. View-model test
  with recording enqueue/cancel lambdas (`LaunchpadScrubTest`).
- Launchpad `launch` cancels the id `manager.launch` returned and still calls `onLaunched`. View-model test
  (`LaunchpadLaunchTest`).
- In-Flight `scrub` cancels the sortie's open row and still enqueues the id it queued. View-model test
  (`InFlightLandScrubTest`).
- The cancels outlive the screen: cancel the screen scope as soon as `onDone` / `onLaunched` fires, and the
  cancel on the action scope still runs. In-Flight test, written the way `MissionViewModelResetTest` uses
  separate screen and action scopes.
- Nothing else moves: the `refly` / `regenerate` / `continueWithoutOutline` cancel lists, the app-open sweep,
  Reset week, and the existing Launchpad and In-Flight card tests keep passing unchanged. The full unit suite is
  the check.

## Risks
- `scrub`'s return type is the risky part. It is not an unused value today: it is the id the next sortie's plan
  gets enqueued from, and three `MissionManagerTest` cases assert it. The work rewrites those cases in place onto
  `onQueued` rather than dropping them, and keeps the enqueue call in both view models. If an assertion cannot be
  expressed with `onQueued`, stop and re-check the shape instead of loosening the test.
- Cancelling must happen after the manager's transaction returns, as `refly` and `regenerate` already do,
  because `GenerationCanceller` opens its own transaction.
- Screen lifetime: In-Flight's `watchLeave` can close the screen, and with it the screen's scope, the moment the
  scrub commits, before `scrub`'s coroutine reaches its cancel loop. Launch navigates away too. Cancels started on
  the screen scope would be cut off and leave the superseded turn running. That is why they go on `actionScope`.
  Do not put them on the screen scope "because the test passed": the test only sees this if its screen scope is
  cancelled.
- A failed cancel must not turn a committed scrub or launch into a failure. In-Flight's `scrub` catches every
  `Exception` and reports "Couldn't scrub this sortie.", so a cancel that throws inside that `try` would show
  that message after the sortie was already scrubbed. Starting the cancels on `actionScope`, outside that
  `try`'s outcome, avoids this. A row that stays open is harmless: `storePlan` refuses a sortie that is SCRUBBED
  or IN_FLIGHT.
- Read the open rows before the write, the way `regenerateFlightPlan` does. `advance` keys its own lookup by the
  *new* current sortie, so its row cannot leak into the scrubbed sortie's list. Reading afterwards is still the
  trap that comment exists to warn about.
- `InFlightViewModel`'s new parameters default to a no-op and to the screen scope, so a call site that forgets
  them fails silently. `InFlightScreen` passes the real canceller and the shell scope, and the In-Flight test
  asserts the cancel was handed the id.
- View-model tests must not use the real `GenerationCanceller`, because it reaches WorkManager and the nabu
  daemon. They record the ids and mark the row cancelled in the fake, the way `LaunchpadRegenerateTest` does, so
  a read-back is real without a daemon.
- Launch dropping a Regenerate the user asked for is a visible behaviour change: the plan the sortie flies is the
  one it already held. That is the decision below and belongs in the PR body. A generation that stores its plan
  just before the launch commits is a race this change does not address; for the scrubbed case, `storePlan`'s
  refusal is the protection.
- Scope: `land`, `unlaunch`, `resetWeek` and the app-open sweep all touch generations too. The brief names `scrub`
  and `launch`, and the work does not extend the pattern to the others.
- Gate on this machine: this worktree's `gradlew.sh` is the committed Windows-only variant. Run the same tasks
  through `JAVA_HOME=~/android-toolchain/jdk ANDROID_HOME=~/android-toolchain/sdk
  ~/android-toolchain/gradle/bin/gradle :app:assembleDebug :app:testDebugUnitTest`. Do not edit `gradlew.sh`:
  it is not one of this issue's files.

## Decisions
**Added by review: the cancels run on the shell's scope, not the screen's, and Launch navigates without waiting
for them.** The plan as written cancelled inside each screen's own coroutine, so leaving the screen could cut
the cancels off. Leaving the screen happens right after an In-Flight scrub (the sortie row changes and
`watchLeave` fires) and right after Launch (it opens In-Flight). Someone who scrubs or launches expects the
superseded turn to actually stop, and expects Launch to open In-Flight at once, not after a daemon round trip to
give up a session. This follows Reset week's `actionScope`, and it adds `LiftoffShell.kt` and
`LaunchpadScreen.kt` to the files touched.

**Launch while a Regenerate is in flight: cancel the generation, or block the Launch?** The plan cancels the
in-flight generation and lets Launch proceed, as the brief directs. The alternative is to disable the Launch
button until the generation finishes, so the user always launches the freshest plan. Cancelling is what a user
would expect: they pressed Launch, the plan on screen is the one they start, and the regeneration has been
superseded. DESIGN.md §7.3 already says superseded generations are cancelled and their sessions stopped.
Blocking the button would turn a working action into one that silently does nothing.

**Scrub mid-generation: does anything new appear in the UI?** No new copy or state. The cancelled generation
lands in the same cancelled state a user-initiated cancel already produces, so any screen that shows generation
status shows it as cancelled, and the existing app-open sweep behaviour is unchanged. The alternative, a
"generation cancelled" message on the Launchpad or In-Flight screen, is out of scope for this issue and would
need copy the brief does not ask for.

**Scrub and Launch keep working exactly as they do today when nothing is open.** The change adds a return value
and a cancel; it does not add a guard. A PENDING sortie with no open generation scrubs as before, and a PLANNED
sortie with no open generation launches as before. The alternative, refusing to scrub or launch while a
generation is running, would be a visible behaviour change the brief does not ask for.

**Only the sortie's own open FLIGHT_PLAN generations are cancelled.** A scrub or a launch does not touch
generations belonging to other sorties, or generations of other kinds. The brief names FLIGHT_PLAN generations
of the sortie, and a user who scrubs one sortie should not see another sortie's coach status change.
