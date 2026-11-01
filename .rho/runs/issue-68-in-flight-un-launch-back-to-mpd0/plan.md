# Plan

## Approach

This is one new sortie transition plus one new In-Flight action, and the codebase already has a place
for each of them. The transition itself goes in `domain/Sorties.kt` — the `SortieTransitions` legal-pair
set and the `launch`/`land`/`scrub`/`markPlanned` family — and the checks and the write go in
`MissionManager` as `unlaunch(sortieId)`, sitting between `launch` and `land` and copying their shape:
one `withTransaction`, ACTIVE, current sortie, state, then a `copy` and `update`. The "nothing has been
recorded" gate is a pure predicate over the sortie's planned sets, read from `getPlan` inside that same
transaction, so the manager stays the authority and the UI cannot talk it out of a refusal.

On the UI side almost everything already exists. `InFlightStates.observe` re-runs `deriveInFlight` on
every Room change to the plan, so a "can un-launch" flag on the derived state appears and disappears
live as sets are checked and unchecked, with no new flow or subscription. The action itself is one more
line in the shared `Footer` composable that already draws Land and Scrub for all three content states,
using the same `UnderlinedTextButton` — which has no `enabled` parameter, so "absent when unavailable" is
the only thing it can do, matching the Launchpad's rule. The ViewModel follows `land`/`scrub` exactly
(guard, `busy`, message on refusal, `leaveOnce`), and the screen's existing `onBack` already returns to
the Launchpad tab. The Launchpad needs no change at all: an un-launched sortie is a PLANNED current
sortie, which `LaunchpadState` already draws with Launch, Regenerate and Scrub, and
`regenerateFlightPlan` already accepts a PLANNED current sortie.

## Files

No new files. What each file already holds is in the map.

- `domain/Sorties.kt` — add `IN_FLIGHT → PLANNED` to `SortieTransitions.legalTransitions` and an
  un-launch beside `launch`/`markPlanned` that clears `launchedAt`.
- `data/MissionManager.kt` — the un-launch action, beside `launch` and `scrub`: the ACTIVE / current /
  IN_FLIGHT / nothing-recorded checks, then the write.
- `ui/inflight/InFlightState.kt` — the "can un-launch" flag on the derived state, or a free predicate
  beside `needsLandConfirmation`, reading the sets the state already carries.
- `ui/inflight/InFlightViewModel.kt` — an un-launch action in the `land`/`scrub` shape, so the screen
  leaves once the row is no longer IN_FLIGHT.
- `ui/inflight/InFlightScreen.kt` — pass the new action through the `when (state)` arms into the shared
  `Footer`, which draws it as an underlined text button only when available.
- `ui/inflight/RunContent.kt` — it calls the same `Footer`, so it passes the new argument.
- `DESIGN.md` — a dated amendment at the top of "Amendments after approval", plus the inline marks in
  §5.1 (the new edge in the state diagram) and §9 (the In-Flight action list).
- `ARCHITECTURE.md` — the `com.liftoff.app.ui` row (line 37) names In-Flight's actions; add this one.
  There is no sortie-transition table to update, so nothing else here changes.
- Tests: `domain/SortiesTest.kt`, `data/MissionManagerTest.kt`, `ui/inflight/InFlightStatesTest.kt`,
  `ui/inflight/InFlightLandScrubTest.kt`, `ui/inflight/InFlightScreenTest.kt`,
  `ui/inflight/InFlightLeaveTest.kt`.

## Order

1. `domain/Sorties.kt`: the new legal pair and the un-launch transition; extend both lists in
   `SortiesTest.kt` so the new pair is legal and the illegal list does not claim it.
2. `MissionManager`: the action and its checks, with the manager tests, including the two that show
   nothing downstream broke — Launch works again on the same sortie, and `regenerateFlightPlan` accepts
   it.
3. `InFlightState.kt`: the availability flag, with the derive-level tests in `InFlightStatesTest.kt`
   (untouched lift, run, after one set is checked). Nothing is needed in `InFlightStates.kt` — the
   combine already re-derives on every plan change.
4. `InFlightViewModel.kt` + `Footer` + `RunContent.kt`: the action on screen, with the footer
   present/absent assertions and the leave-once case.
5. `DESIGN.md` and `ARCHITECTURE.md`, last, so the documents describe what actually ships.

## Testing

- `SortiesTest.kt` (pure): `IN_FLIGHT → PLANNED` is legal; the illegal list still rejects the pairs it
  always rejected, so LANDED/SCRUBBED cannot be un-launched.
- `MissionManagerTest.kt` (Room): an untouched in-flight lift returns to PLANNED with `launchedAt` null,
  and its plan, exercises and sets are byte-for-byte the same rows — same ids, same planned values, no
  set added or removed.
- `MissionManagerTest.kt`: refused for each way a set becomes recorded — checked off, edited, skipped —
  and the sortie stays IN_FLIGHT with `launchedAt` intact after a refusal.
- `MissionManagerTest.kt`: refused for a non-current sortie, for a DRAFT or CLOSED Mission, and for a
  sortie in any state other than IN_FLIGHT.
- `MissionManagerTest.kt`: a run sortie is un-launchable both with a run plan and with no plan at all.
- `MissionManagerTest.kt`: after un-launch, `launch` succeeds again on the same sortie and
  `regenerateFlightPlan` accepts it and queues a replacement.
- `InFlightStatesTest.kt` (derive level): available for an untouched lift and for a run; unavailable
  after one set is checked; available again after that set is unchecked, since unchecking restores the
  untouched shape.
- `InFlightLandScrubTest.kt` (view model on Room): the refusal path sets a message and does not throw,
  and a double tap writes once.
- `InFlightScreenTest.kt` (Robolectric Compose): the underlined action is present when available and
  absent when not; the existing Land/Scrub footer assertions still hold.
- `InFlightLeaveTest.kt`: un-launching leaves the In-Flight screen exactly once, and nothing written
  after the leave is accepted.

## Risks

- The nothing-recorded predicate must cover **every** set of the plan, including the sets of a skipped
  exercise: `deriveInFlight` marks such a card SKIPPED, so a predicate that reads only the active cards
  would call a sortie with a skipped exercise untouched. The manager reads the whole plan; the UI
  predicate must do the same.
- An **added** set has `status = OPEN` and null actuals, so it does not block un-launch. That is the
  brief's rule, not an oversight — adding a set records no result, and Regenerate replaces the plan
  anyway. The work should not "fix" this by counting `added`.
- `markPlanned` already exists and leaves `launchedAt` alone. Reusing it would produce a PLANNED row that
  still carries a launch time. The new transition nulls it, and a test asserts null.
- The screen already leaves the moment its row is not IN_FLIGHT, so un-launch triggers that leave. The
  ViewModel must use the existing `leaveOnce` gate rather than also calling back itself, or the shell
  navigates twice.
- `regenerateFlightPlan` additionally requires the Mission to be on the current week. The after-un-launch
  test must use the current-week fixture the existing generation tests use, or it fails for a reason
  unrelated to this change.
- `InFlightScreenTest` renders at a fixed `@Config` window height, so a taller footer can break an
  existing `assertIsDisplayed` head assertion. Run the whole In-Flight test package in the same step that
  adds the control, and keep the label short.
- The suite total on this branch is not yet measured; measure it before writing an expected count into
  the tasks.

## Decisions

Most of the behavior here is settled by the brief: the action is **absent**, not dimmed, when it is unavailable; no confirmation dialog; the Flight Plan, its exercises and its sets are untouched; `launchedAt` is cleared; the app navigates to the Launchpad; only the current sortie of an ACTIVE Mission can be un-launched. The open points:

**Do in-flight notes block un-launch, and do they survive it?** The brief's "nothing recorded" gate is written only about sets, and notes are the user's own writing, not training data. Choice: notes neither block un-launch nor are deleted by it — they stay attached to the sortie and are visible again if the sortie is re-launched. The alternatives were to count a note as "recorded" (hiding the action) or to clear notes on un-launch. Both would surprise: the first means a typed note strands a bad plan with no way out, which is the exact problem in the issue; the second contradicts the brief's own reason for skipping a dialog — "nothing is lost".

**Does un-launch queue anything with the coach?** Choice: no. It is a state change only; the plan already on the row stays, and the user gets a new one through the PLANNED card's existing Regenerate. The alternative — auto-queueing a regeneration on un-launch — would spend a daemon turn the user did not ask for, and the brief says no new generation path is needed.

**Which sortie is current afterwards?** Choice: the same one. Un-launch changes only the sortie's state and `launchedAt`, so the Launchpad opens on that sortie's PLANNED card with Launch, Regenerate and Scrub. The alternative — moving on to the next sortie — would make the Launchpad look like the sortie vanished, and would not answer "give me a different plan for *this* one".

**Is any trace of the launch kept?** Choice: no. Clearing `launchedAt` means the card and any history show the sortie as if it had never been launched. The alternative is to keep the first launch time somewhere. Nothing in the app displays `launchedAt` as history today, and keeping it would make the PLANNED card inconsistent with one that was never launched.

**Where the action sits on In-Flight.** Choice: with the secondary underlined text actions, ordered so Scrub stays last — Land as the primary action, then Back to Launchpad, then Scrub. The alternative is to put it beside Land at the top. Scrub is the one action that destroys the sortie, so keeping it last keeps the safe action easier to hit than the terminal one.
