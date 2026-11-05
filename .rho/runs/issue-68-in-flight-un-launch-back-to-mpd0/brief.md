In-Flight: un-launch back to PLANNED so the plan can be regenerated

## Problem

Once a sortie is launched, In-Flight offers only **Land** and **Scrub**. Regenerate exists only on the Launchpad's PLANNED card (DESIGN.md §5.1, §9), so after Launch there is no way to get a different plan for the same sortie. Scrubbing ends the sortie, and the app moves on to the next one.

This came up in real use. The owner launched today's run, then wanted the coach to redo it. The plan had been written before any equipment or objectives were entered. The only way out was Scrub, which lost that sortie.

## What to build: un-launch

Add a **Back to Launchpad** action on In-Flight. It returns the sortie from IN_FLIGHT to **PLANNED**. The PLANNED card then offers its existing Launch, Regenerate and Scrub. No new generation path is needed.

Rules:

- **Allowed only while nothing has been recorded.** For a lift, that means no set has been checked off, edited or skipped: every planned set is still `SetStatus.OPEN` with no actual reps, seconds or weight. For a run (no checklist), it is always allowed, because the distance and minutes are only entered at Land.
- When anything has been recorded, the action is **absent** rather than dimmed, which matches the Launchpad's rule for unavailable actions (`LaunchpadScreen.kt`, the comment above `planned.regenerateAvailable`).
- Un-launching clears `launchedAt` and sets the state back to PLANNED. The Flight Plan, its exercises and its sets are unchanged.
- It is allowed only for the current sortie of an ACTIVE Mission, with the same checks `MissionManager.launch` makes. `MissionManager.land` and `scrub` are its neighbours.
- No confirmation dialog is needed, since nothing is lost. After un-launching, the app goes to the Launchpad.

## Where

- **`DESIGN.md`:** add an amendment at the end (newest first, dated). Mark it inline in §5.1 (add `IN_FLIGHT ──un-launch──► PLANNED` to the state diagram, with the "nothing recorded" condition) and in §9 (In-Flight lists the new action). CLAUDE.md requires decisions to change by amendment.
- **`data/MissionManager.kt`:** `suspend fun unlaunch(sortieId: Long)`. In a transaction, check: Mission ACTIVE, sortie current, state IN_FLIGHT, nothing recorded on its sets. Then update to PLANNED with `launchedAt = null`. Put the pure state transition in `domain/Sorties.kt` beside `launch(sortie, at)`.
- **`ui/inflight/`:** the state (`InFlightState`/`InFlightStates`) exposes whether un-launch is available. The ViewModel calls the manager. The screen shows an underlined text action, styled like Scrub, only when available.
- **`ARCHITECTURE.md`:** update it if it lists sortie transitions.

## Tests

- **MissionManager:**
  - un-launch of an untouched in-flight lift returns it to PLANNED with `launchedAt` null and the plan intact.
  - It is refused once any set is recorded.
  - It is refused for a non-current sortie or a non-ACTIVE Mission.
  - It is refused for a sortie that isn't IN_FLIGHT.
  - An in-flight run can always be un-launched.
- **After un-launch:** Launch works again, and Regenerate (`regenerateFlightPlan`) is accepted.
- **In-Flight state:**
  - the action is available for an untouched lift and for a run.
  - It is unavailable after one set is checked.

Run the gate before pushing: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`.

(From issue corporealshift/liftoff#68: https://github.com/corporealshift/liftoff/issues/68)
