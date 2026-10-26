# Plan

## Approach

Do to In-Flight what the Launchpad already does, with the same shape. `MissionManager.land` and `scrub`
already return the id of the generation their transaction queued for the sortie that just became current;
`InFlightViewModel` throws that id away. So the change is to carry it out of the view model as a defaulted
`(Long) -> Unit` lambda — the exact parameter type `LaunchpadViewModel.queueGeneration` uses — and to have
`InFlightScreen` pass `{ id -> container.generationEnqueuer().enqueue(id) }` from inside the `remember(sortieId)`
block that already builds the view model, so WorkManager is asked for only when a land or scrub actually queues
something and never while composing. Nothing new is added to the data or work layers: the QUEUED `Generation`
row is already written by `advance`, and `GenerationEnqueuer` already schedules it as unique kept work.


## Files

- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt` — one more constructor parameter,
  a defaulted no-op `(Long) -> Unit` placed after the defaulted `missionManager`; `land`, `landRun` and `scrub`
  capture the id the manager returns and call it once, after that call has returned and before the completion
  callback, only when the id is non-null, with the call shielded so nothing but a `CancellationException`
  escapes it.
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` — pass the container lambda at the existing
  construction site (line ~50), inside the same `remember(sortieId)`.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightLandScrubTest.kt` — a coach-configured variant of the
  existing fixture plus a recording enqueue lambda, and the three new behaviors: land records, scrub records,
  no host/token records nothing.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt` — the `landRun` case recording the next
  sortie's row id once.
- Not touched, deliberately: `MissionManager`, `GenerationEnqueuer`, `AppContainer`, `LaunchpadScreen` /
  `LaunchpadViewModel`, every other test file (the brief limits the change to these four), and `ARCHITECTURE.md`
  / `DESIGN.md` — see Risks. No Room schema change.


## Order

1. View model: add the defaulted enqueue parameter and call it from `land`, `landRun` and `scrub`, shielded
   from the existing message-setting `catch`. `bash gradlew.sh :app:compileDebugKotlin` passes, and every existing
   `InFlightViewModel(...)` call site still compiles untouched (they are all positional with 4 or 5 arguments).
2. Screen: pass the container lambda. `:app:assembleDebug` plus `InFlightScreenTest`, `LiftoffShellTest`,
   `ShellNavTest` and `MainActivityTest` stay green — nothing WorkManager-related happens at composition.
3. `InFlightLandScrubTest`: the coach-configured fixture variant, then the land-records-once test, then
   scrub-records-once, then no-host-token-records-nothing — each setting the host and token before
   `onAppOpen()` and the run-plan toggle after `landSortie0`, as Testing explains. One cold class-scoped
   `--tests` run.
4. `InFlightRunTest`: the `landRun` records-once test. One cold class-scoped `--tests` run.
5. The whole gate, detached: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.


## Testing

All in the two existing Robolectric classes (`@Config(sdk = [34])`, in-memory Room, fixed clock, the recording
`CoroutineExceptionHandler` scope), with a recording lambda in place of WorkManager and every wait bounded.
Nothing goes in `setUp`, so the shared fixture and the existing tests keep their current behavior: the host and
token are set **inside each new test before `onAppOpen()`**, and where the sortie that becomes current is a run,
`setGenerateRunPlans(true)` is set **after the fixture has landed sortie 0 and before the view model's land or
scrub** — the manager re-reads the settings on every call, so the toggle only has to be on for that one call.
Turning it on before seeding would leave sortie 0 PENDING awaiting a generated plan, and the fixture's
`landSortie0` could not launch it. See the map for the fixtures these reuse.

- Landing a lift from In-Flight hands the recording lambda exactly one id, and reading that id back from the
  generation DAO gives a `FLIGHT_PLAN` row in status `QUEUED` belonging to the sortie that became current —
  sortie 2 on the RLRLR fixture, not the row the fixture's own landing of sortie 0 queued for sortie 1.
- Scrubbing from In-Flight does the same, once.
- Landing a run through `landRun` with a distance and a duration records the next (lift) sortie's row id once;
  no toggle is needed there, because a lift is always left awaiting generation.
- With no host and no token set, and the toggle on at the same point, landing records nothing — the sortie still
  lands, and no message is raised.
- A lambda that throws is not turned into "Couldn't land this sortie." / "Couldn't scrub this sortie.": the
  sortie lands, the completion callback still runs, `message` stays null, and nothing reaches the scope's
  exception handler. (This is the brief's swallow rule; it belongs in the same two classes.)


## Risks

- **The id may not be a fresh row.** `advance` returns the id of an already-open QUEUED/RUNNING row instead of
  inserting a second one (`MissionManager.kt:405`). The tests assert the recorded id against the generation row
  of the sortie that became current, not against a row count or a "new row appeared". With a coach configured the
  fixture's own `landSortie0` already queues a row for sortie 1 — which `seedSortie1LiftPlan` plans by hand
  anyway — so the land/scrub assertions have to name **sortie 2's** open `FLIGHT_PLAN` row.
- **Landing a lift on the default RLRLR pattern makes a *run* current, and with the run-plan toggle off nothing
  is queued at all** (`DESIGN.md` §5.3, `SortiePlanning.kt:14`) — the existing test at
  `InFlightLandScrubTest.kt:237` asserts exactly that. So the new land/scrub tests must turn
  `setGenerateRunPlans(true)` on, and the no-host-token test must use the same settings so it fails for the
  right reason. The toggle goes on **after** the fixture has landed sortie 0: with it on, `advance` leaves a run
  sortie PENDING awaiting a generated plan, and `landSortie0`'s `manager.launch` refuses a PENDING sortie
  (`domain/Sorties.kt:10`, `:40`). Host and token before `onAppOpen()`, the toggle before the view model's
  land/scrub, never in `setUp`: turning them on for the whole class would change existing tests.
- **A failure from the lambda must not be caught by the existing `catch (Exception)`** in
  `land`/`landRun`/`scrub`, or a WorkManager failure becomes a bogus "Couldn't land this sortie." on a sortie
  that did land.
- **The enqueue must not be gated on `left`.** The `watchLeave` collector can see the sortie leave IN_FLIGHT,
  and set `left`, before the manager call returns, which makes `leaveOnce(onDone)` a no-op. The enqueue is
  keyed only on the returned id being non-null, never on `left` or on `leaveOnce` firing. Otherwise a plan
  goes unscheduled whenever the watcher wins that race.
- **Composition must not touch WorkManager.** `AppContainer.generationEnqueuer()` calls
  `WorkManager.getInstance`, which throws when it was never initialized; `InFlightScreenTest` composes the real
  container (`:45`) with no initialization and taps `LAND` through the screen (`:387`), and the shell tests do the
  same. Keep the lambda inside `remember(sortieId)`, and do not give any screen test a coach host and token.
- **`InFlightLandScrubTest` has no `generationDao` field**; read the rows through `db.generationDao()` or add the
  field in that same file.
- **Nothing re-enqueues a row left QUEUED at app open today** (see the map), so a swallowed enqueue failure means
  that plan is simply not generated until something else queues it. The brief accepts this; the plan does not add
  the sweep.
- **`ARCHITECTURE.md:82` still says In-Flight's enqueue "comes next".** The brief limits the change to four files,
  so the row is left as it is for a later doc pass.
- **The gate can hang** on `:app:testDebugUnitTest` for about five minutes, writing no report XML. Run it detached
  with the log-and-exit sentinel, and re-run the identical tree once before treating a hang as a failure.


## Decisions

**Does In-Flight show anything about the generation it just queued?** No. The enqueue is silent, with no new
line, chip or toast on the In-Flight screen. The alternative is a short "Coach plan queued" where the confirmation
used to be. In-Flight only ever shows the sortie you are on, and landing or scrubbing leaves it for the Launchpad.
The plan for that other sortie belongs to the Launchpad, and the Launchpad's display of the current sortie's
status is already listed as the next piece of work (`ARCHITECTURE.md:82`). Showing a status for a *different*
sortie on In-Flight would be the surprising thing.

**When does the enqueue happen?** Immediately after `land`/`landRun`/`scrub` returns a non-null id, in the same
call, before the screen leaves. The alternative is to hand it off after the navigation back settles. The QUEUED
row is already committed when the manager returns, so this is the earliest correct moment. It is also what the
Launchpad already does with the ids its own `scrub` and Confirm hand back.

**What does the user see if the enqueue itself fails?** Nothing, and no error. The brief settles the important
half: the failure must not become "Couldn't land/scrub this sortie.". The open part is whether to show a milder
warning instead, and the plan chooses silence. The sortie really did land, and the QUEUED row stays in Room as the
record that this sortie still wants a plan. The brief's rule is that a later run re-enqueues such rows at app
open. That is a promise about the later pass, not something this change adds (see Risks). A warning would blame
the owner for something the app is meant to recover from on its own.

**Does tapping Land or Scrub twice queue two generations?** No, and this plan adds no guard of its own. The view
model's existing `busy` and `left` guards already absorb the second tap (`doubleTapLandsOnce`,
`runLandDoubleTapLandsOnce`). `advance` never writes a second row for a sortie whose Flight Plan generation is
already QUEUED or RUNNING; it hands back the open row's id instead. `GenerationEnqueuer` keeps the work it
already scheduled under `gen-<generationId>`. One generation per sortie is what the Launchpad does and what users
would expect here.

**Added by review: Is the enqueue skipped when the screen has already left the sortie?** No. Once the manager
call returns a non-null id, the lambda is called whether or not the leave watcher has already set `left`. Only
the completion callback goes through `leaveOnce`. The row was committed for the sortie that became current. Not
scheduling it because the screen noticed the land first would quietly lose that sortie's plan.
