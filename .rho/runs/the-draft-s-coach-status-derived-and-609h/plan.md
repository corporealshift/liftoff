# Plan

## Approach

The brief's derivation is a pure function, so it lives in `com.liftoff.app.domain` beside `coachIsConfigured`. It takes the row's status, error and offline flag as plain values (or none) plus a single "coach is configured" Boolean, not the `Generation` entity. That is the shape `domain/` already uses for `MissionStatus` and `SortieState`. `GenerationStatus` is a plain enum in `data/Enums.kt` and is fine in the signature. `LaunchpadState.Draft` carries the result.

`LaunchpadStates.observe()` already flatMapLatests over the week and combines the mission with `flightPlanDao().observeChanges()`. The DRAFT arm gains one more join: the DAO's existing `observeNewestNonCancelledOutlineForMission(missionId)` plus a configured-ness flow. Every other arm stays exactly as it is.

The configured-ness flow comes from `MissionManager`. It already holds the `SettingsStore` and already asks this exact question in `confirm` and `retryOutline`. Exposing it as a `coachConfigured` Flow (settings mapped through `coachIsConfigured`, then `distinctUntilChanged`) leaves the constructors of both `LaunchpadStates` and `LaunchpadViewModel` untouched, so none of the six `LaunchpadViewModel(...)` test call sites move.

The draft branch ends in `distinctUntilChanged`, so a write that leaves the derived status equal emits nothing (for example, another mission's plan generation or an unrelated setting). There is no Room schema change. `LaunchpadScreen` needs nothing: it reads only `weekStart` and `pattern` off the draft and never constructs `Draft`. The only construction is in `LaunchpadState.kt`'s `derive`.

## Files

- `app/src/main/java/com/liftoff/app/domain/CoachStatus.kt` (new):
  - The sealed status the draft carries: editable, planning, offline, failed-with-reason, not-configured. Each variant records whether it offers Confirm, Retry or "Continue without outline".
  - The plain derivation over the row's status, error and offline flag (or no row), plus the configured Boolean.
  - No Android or Room types in its signature. It does not call `coachIsConfigured` itself; the caller hands it the answer.
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt`: one new public Flow saying whether the coach is configured. It is the `SettingsStore` it already holds, mapped through `coachIsConfigured`. Nothing else in the file changes.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt`: `Draft` gains the derived status. The DRAFT arm of `observe()`/`derive()` joins the outline Flow and the configured-ness Flow, reads `isCoachOffline` off the row, and ends in `distinctUntilChanged`. `Planned`, `Pending`, `InFlight`, `Closed` and `Loading` keep today's behaviour.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`: no change expected. Any edit here is a compile-only fix, not a drawing change.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`: no change expected. Its actions read only `pattern` and `missionId`.
- `app/src/test/java/com/liftoff/app/domain/CoachStatusTest.kt` (new): plain JUnit.
- `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadCoachStatusTest.kt` (new): Robolectric, using the same harness as `LaunchpadStatesTest` (in-memory Room, real `SettingsStore` over a test DataStore, `MissionManager` with a fixed UTC clock).

## Order

1. Write the derivation and its plain JUnit cases in `domain`. Nothing reads it yet, so the build and the domain tests pass on their own.
2. Add `MissionManager`'s configured-ness Flow. It has no caller yet, so nothing can break.
3. Make `Draft` carry the status and have `LaunchpadStates` join the two Flows on the DRAFT arm. This is the step that could break something: the six existing Launchpad test classes must still compile and pass, and `LaunchpadScreen` and `LaunchpadViewModel` stay as they are.
4. Write the Robolectric live-status test: one subscription, three writes, three emissions.
5. Run the whole gate once: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, detached. Run it again if it hangs.

## Testing

Plain JUnit, in `CoachStatusTest` (no Robolectric, no Room):

- No row and configured → editable, Confirm offered.
- Newest row SUCCEEDED and configured → editable.
- QUEUED and RUNNING, not offline → planning. "Continue without outline" is offered; Confirm is not.
- QUEUED and RUNNING with the offline flag set → offline. "Continue without outline" is offered.
- FAILED → failed, carrying the row's error **word for word**. Assert the exact string. Use one reason of the kind the worker writes and one of the kind the validator writes (with punctuation and inner spacing), so a rewrite or a trim is caught. Retry and "Continue without outline" are offered.
- No row and not configured → not configured. SUCCEEDED and not configured → not configured (see Decisions).
- Precedence, per the Decisions:
  - FAILED and not configured → failed, with the reason.
  - An open row and not configured → planning, or offline if flagged.
- A CANCELLED status handed in anyway is treated as no row.
- Each variant states which actions it offers: Confirm only on editable; "Continue without outline" on planning, offline, failed and not configured; Retry only on failed.

Robolectric (`@Config(sdk = [34])`, in-memory Room, fixed UTC clock, every wait under `withTimeout`, no WorkManager), in `LaunchpadCoachStatusTest`:

- From **one** subscription to `LaunchpadStates.observe()` for the current-week draft, with host and token set:
  - The first Draft is editable.
  - Inserting a QUEUED OUTLINE row for that mission emits planning.
  - `GenerationDao.setCoachOfflineMark(id, Generation.COACH_OFFLINE)` on that row emits offline.
  - `markFailed(id, "reason", t)` emits failed carrying that exact reason.
- These writes leave the draft's status untouched and emit no new Draft:
  - a row for a different mission;
  - a FLIGHT_PLAN row;
  - a CANCELLED row.
- With a blank host and token, the draft is not configured. Changing an unrelated setting (weight unit) emits nothing. Filling in a host and token turns the draft editable without re-subscribing.

Regression: the six Launchpad test classes, the worker tests, In-Flight, Landed, Mission Control and the domain tests all pass untouched. The gate is `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

## Risks

- `combine` produces nothing until every joined Flow has emitted once, so the draft now waits on a DataStore read it never needed before. Map the settings down to the single configured Boolean, and keep every wait in the new test bounded, as the existing states tests already do.
- Room's invalidation tracker fires on any write to the `generation` table, including another mission's plan generation, so the draft arm re-derives often. Without `distinctUntilChanged` on the derived state, the "unrelated change emits nothing" cases would see duplicate emissions.
- A pattern edit re-emits the mission, which can restart the inner outline and settings Flows. Do not give the inner join a starting placeholder (no `onStart` default status). Otherwise the draft flickers to editable between two real values while an outline is open.
- `Draft` is a data class and the StateFlow compares by equality, so the new field is what makes a status change visible. That is intended. A later run that wants the status to change without re-emitting the draft has to change this.
- The derivation must be handed the row's values, not the `Generation` entity, which is a Room type the brief rules out of the signature. `isCoachOffline` is a getter on that entity, so the flag has to be read out and passed.
- `MissionManager` gaining a public Flow is new surface. Injecting `SettingsStore` into `LaunchpadStates` instead would behave identically, but `LaunchpadViewModel` would gain a parameter and six test call sites would have to be edited. The plan picks the smaller change and records it here.
- The full gate can hang once for about five minutes on this machine, writing no reports. Run it detached, move the results directory aside before a re-run, and re-run rather than debug a hang.

## Decisions

**A SUCCEEDED row with a blank host or token shows "not configured", the same as no row.** Changed by review: the plan chose editable, so that "a SUCCEEDED outline already sitting on the draft" would stay visible. The review chose not configured, because that premise doesn't hold. `storeOutline` activates the mission in the same transaction the worker marks the row SUCCEEDED, so a draft whose newest outline succeeded carries no outline to show. The brief groups "SUCCEEDED" with "no row" under editable, and its not-configured clause only requires that no row be *open*. Treating the two alike means Confirm is never offered when it would only come back as "Mission Control isn't set up".

**A CANCELLED status is treated as no row.** Added by review. The DAO never hands one over, but the status enum includes CANCELLED, so the derivation's `when` has to answer it. "As if nothing was queued" matches what cancelling means everywhere else.

**When a FAILED or open row exists, the row decides; the settings decide only when there is no row worth reporting.** The brief's not-configured clause says "no row is *open*". Read strictly, a blank host or token would hide a FAILED row, and the user would lose the reason and the Retry button. The plan ranks a FAILED or open row first. Retry with blank settings still works out: `retryOutline` answers `CoachNotConfigured` and the view model shows its "Mission Control isn't set up" message.

**An open row marked offline outranks the settings too**, by the same rule. A QUEUED or RUNNING row carrying the offline mark shows "offline" even if the host or token is blank. The mark is what actually happened to that generation; "not configured" would be a guess about why. An open row with no mark and blank settings shows planning.

**Only the newest non-cancelled row is ever visible.** After a Retry, the older FAILED row and its reason disappear and the status becomes planning. A CANCELLED row is invisible, so cancelling an outline leaves the draft as if no generation had been queued. Showing a history of attempts is more than a status line can carry, and the brief names one row as the input.

**A FAILED row that carries no reason text gets a fixed neutral line.** `markFailed` always writes a reason, so only an imported or hand-written row reaches this case. The status type is built now, and the run that draws it should not have to invent copy for a case it cannot test.

**"Unrelated" means a change that cannot change the answer.** These all leave the draft's emission untouched:
- a settings edit that leaves the coach exactly as configured as before;
- a generation row belonging to another mission;
- a flight-plan generation;
- a cancelled row.

Combining the whole `Settings` object and the whole generation table would instead re-emit an identical draft.

**This run changes nothing visible on the Launchpad.** The screen keeps drawing Confirm and "Continue without outline" on every draft, and the view model's guards stay as they are. The status is state only. The run that draws it decides where the reason, the offline line and the Retry button appear.
