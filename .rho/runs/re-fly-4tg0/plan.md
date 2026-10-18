# Plan

## Approach

Three pieces, matching the brief's three headings, each built on the shape the code already uses. A pure chooser in `domain` picks the source: given the current PENDING sortie and the finished sorties to choose among (each carrying whether it has a plan), it returns the most recent LANDED sortie of the same type that has a plan — preferring a matching focus, else any of the same type — or nothing. `MissionManager` gains one operation, `refly`, in a single `withTransaction`, guarded exactly like `scrub` and `launch` (mission ACTIVE, sortie is `currentSortie`, sortie is PENDING): it reads the source through `FlightPlanDao.getPlan`, rebuilds it as a `FlightPlanDraft` with `source = REFLY`, writes it with the existing `writePlan`, and moves the sortie with the existing `markPlanned` transition. `writePlan` already deletes any plan for the target, resolves exercise names, and writes exercises with `skipped = false`/`userNotes = null` and sets with null actuals, `status = OPEN`, `added = false`, so "planned values only, no added sets, cleared skips and notes" is mostly a property of the draft it is handed — the builder simply drops sets flagged `added` and copies `reps`/`seconds`/`weight` rather than the `actual*` columns. Nothing touches the coach or nabu packages, so the copy is offline by construction. The Launchpad gets the availability from the derivation (`LaunchpadStates.derive` is already `suspend` and already queries the plan DAO), and `PendingActions` renders `UnderlinedTextButton("Re-fly")` beside Scrub when it is true, or a muted line when it is not.

## Files

- `app/src/main/java/com/liftoff/app/domain/ReFly.kt` (new) — the pure chooser and the candidate it weighs (a finished sortie plus whether it has a plan). No Android imports; ordering and the focus preference live here, not in the caller.
- `app/src/main/java/com/liftoff/app/data/ReFlyCopy.kt` (new) — source `FlightPlanDetail` → `FlightPlanDraft`: `source = REFLY`, plan fields (title, estimated minutes, warmup, notes, run kind/distance/pace) copied unchanged, exercises with their display name, equipment keys, rest seconds and coach notes, sets from the planned `reps`/`seconds`/`weight` only, `added` sets dropped, segments copied. `rawJson` is `"{}"`, as in `simpleRunPlan`: a re-flown plan has no coach output of its own, and the source row keeps its JSON. Lives in `data` because `FlightPlanDetail` is a Room read model that invariant 2 keeps out of `domain`.
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt` — one new operation: the guards, the candidate read (`SortieDao.getFinishedNewestFirst` + `FlightPlanDao.getTitlesForSortieIds`, so one read for the whole list), the chooser call, `writePlan`, `markPlanned`. It does not call `advance()`.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` — `LaunchpadState.Pending` gains whether a source plan exists; `deriveActive` computes it for the PENDING branch only.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` — a `refly()` action with the same guard-and-catch shape as `confirmScrub`.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` — `PendingActions` shows Re-fly next to Scrub, or the muted line; `ActionButtons` passes the new callback.
- `ARCHITECTURE.md` — the M5 row marks Re-fly built with export/import still to come; the `domain` and `ui` package rows name the chooser and the Launchpad action.
- Tests: `app/src/test/java/com/liftoff/app/domain/ReFlyTest.kt`, `app/src/test/java/com/liftoff/app/data/MissionManagerReFlyTest.kt`, `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadReFlyTest.kt`, and new cases in `LaunchpadStatesTest.kt`.

## Order

1. `domain/ReFly.kt` + `ReFlyTest` — the chooser and its five §13 cases. Pure JUnit, no Room.
2. `data/ReFlyCopy.kt` + `MissionManager.refly` + `MissionManagerReFlyTest` — the copy end to end through the manager.
3. `LaunchpadState.Pending` + `LaunchpadStates.derive` + new `LaunchpadStatesTest` cases — availability derived from the chooser against seeded history.
4. `LaunchpadViewModel.refly` + `PendingActions` + `LaunchpadReFlyTest` — the button, the muted line, and a tap that writes the plan and lands the Launchpad in `Planned`.
5. `ARCHITECTURE.md` rows.
6. One cold whole-suite gate at the end of the run, detached, to catch what the per-class gates miss — `MissionManagerTest`, `LaunchpadStatesTest` and `LiftoffShellTest` in particular.

## Testing

Chooser — `domain/ReFlyTest`, plain JUnit (the §13 cases):
- Same type and same focus wins over a more recent plan of the same type with a different focus.
- With no focus match, the most recent plan of the same type is used; a plan of the other type is never used.
- Among focus-matching candidates the most recently landed one is chosen.
- A candidate that was scrubbed, or that has no plan row, is never chosen.
- No landed plan of that type → nothing returned.

The copy — `data/MissionManagerReFlyTest`, Robolectric + in-memory Room (fixture shape as in `MissionManagerTest`):
- A source whose sets are all DONE with actuals yields a copy carrying `reps`/`seconds`/`weight`, null actuals and `status = OPEN`.
- A set added mid-flight in the source is absent from the copy.
- The copy's `source` is `REFLY`; its plan fields, exercises, equipment, rest seconds, coach notes and run segments match the source's planned ones.
- A skipped exercise in the source arrives unskipped with no user note.
- The sortie is `PLANNED` afterwards, through the legal PENDING → PLANNED transition.
- The copy needs no network: it completes with no daemon configured and writes no `Generation` row.
- Refused: a sortie that is not the current one; a sortie that is PLANNED, IN_FLIGHT or LANDED; a PENDING sortie with no source plan. Each leaves the sortie PENDING with no plan written.

Launchpad — Robolectric:
- `LaunchpadStatesTest`: the PENDING state reports a source available when a landed plan of the same type exists, and unavailable when there is none, when the only same-type sortie is scrubbed, or when the only plan is of the other type.
- `LaunchpadReFlyTest`: the PENDING screen shows the Re-fly action beside Scrub when a source exists, and the muted line with no Re-fly control when it does not; tapping Re-fly writes the `REFLY` plan and the screen moves to the Planned state showing the copied title.

All waits bounded by `withTimeout`, following `LaunchpadScrubTest`'s poll helper; `job.cancel()` before `db.close()`.

## Risks

- Exercise names round-trip: `getPlan` returns the exercise's `displayName` and `writePlan` re-resolves names through the coach normalizer. A display name that normalizes to a different exercise would swap it silently, so a test asserts the copy names the same exercises as the source.
- `land()` leaves a landed source's sets DONE or SKIPPED, and exercises may be skipped with user notes. The copy reads planned values whatever the status, and never carries a skip or a note.
- `writePlan` deletes the target's existing plan first. The whole re-fly stays inside the manager's `withTransaction` so a refusal or a failure rolls back and the sortie is left PENDING with its plan untouched.
- `LaunchpadStates.derive` runs on every emission, so the history read happens only in the PENDING branch and uses the two cheap queries (`getFinishedNewestFirst`, `getTitlesForSortieIds`) rather than a `getPlan` per candidate.
- A LANDED row with a null `landedAt` (a hand-seeded fixture) sorts oldest; the chooser treats null as oldest instead of guessing.
- `refly` must not call `advance()`: the sortie is PLANNED by the time it returns, so `advance` would at best do nothing and at worst write a second plan.
- Coach generation is not wired, so Re-fly shows on every PENDING sortie. Keep the availability decision in the derivation alone, so the later limit lands in one place.
- The known flake `doneBodyweightSetStepperShowsBW` (Room closed pool) can fail a whole-suite run; re-run the same tree before treating it as broken.

## Decisions

**Which landed plan is "the most recent".** The one whose sortie landed last — the sortie's landed time, not the moment its plan row was last written. A user asking "what did I do last time?" means the last one they trained; a plan edited afterwards (a scrub of a set, a note added from the Landed detail view) would otherwise jump the queue. The alternative — most recently written plan — is what the data makes easiest, but it would re-fly a plan the user touched yesterday over one they actually ran last week.

**Focus matching when either side has no focus.** No focus match: the chooser falls back to the most recent plan of the same type. Matching is exact text after trimming and case-folding, so "5k" and "5K" match. The alternative — treating a missing focus as a wildcard that matches any focused plan — would let a plan built for a specific focus be chosen for a sortie that has none, which reads as a wrong pick.

**Re-fly is one tap, with no confirmation.** Tapping Re-fly copies the plan and moves the sortie to PLANNED immediately. Scrub has a confirmation dialog because it throws a sortie away; Re-fly throws nothing away — the source plan stays in history untouched and the sortie it fills had no plan at all. The alternative is a confirm dialog on every use, which the brief's "underlined action next to Scrub" does not describe.

**No record of which sortie was copied.** The copy carries `source = REFLY` and nothing else; the app does not show "copied from the 12 Oct run". Recording the source sortie id would need a Room schema change, which the brief forbids. A user can still find the original in Landed history.

**When there is no source plan, the Launchpad says so instead of showing nothing.** A short muted line in the action row, where the Re-fly button would have been ("No earlier plan to re-fly."), rather than hiding it from view. The brief asks for a muted line; the alternative — a disabled Re-fly button — is a dead control, which this UI avoids elsewhere.

**Re-fly replaces whatever plan the current sortie already has.** A PENDING sortie has no plan in practice, but if a row exists the copy overwrites it rather than merging or refusing. The alternative (refusing) would surface an internal state the user never sees and cannot act on.
