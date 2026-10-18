# Plan

## Approach

Three pieces, matching the brief's three headings, each built on the shape the code already uses. A pure chooser in `domain` picks the source. It takes the current PENDING sortie and the finished sorties to choose among, each carrying whether it has a plan. It returns the most recent LANDED sortie of the same type that has a plan, preferring a matching focus and otherwise any of the same type, or nothing. `MissionManager` gains one operation, `refly`, in a single `withTransaction`. It is guarded exactly like `scrub` and `launch`: the mission is ACTIVE, the sortie is `currentSortie`, and the sortie is PENDING. It reads the source through `FlightPlanDao.getPlan`, rebuilds it as a `FlightPlanDraft` with `source = REFLY`, writes it with the existing `writePlan`, and moves the sortie with the existing `markPlanned` transition. `writePlan` already deletes any plan for the target and resolves exercise names. It writes exercises with `skipped = false`/`userNotes = null`, and sets with null actuals, `status = OPEN` and `added = false`. So "planned values only, no added sets, cleared skips and notes" is mostly a property of the draft it is handed: the builder drops sets flagged `added` and copies `reps`/`seconds`/`weight`, not the `actual*` columns. Nothing touches the coach or nabu packages, so the copy is offline by construction. The Launchpad gets availability from the derivation, since `LaunchpadStates.derive` is already `suspend` and already queries the plan DAO. `PendingActions` renders `UnderlinedTextButton("Re-fly")` beside Scrub when a source exists, and a muted line when it does not.

## Files

- `app/src/main/java/com/liftoff/app/domain/ReFly.kt` (new): the pure chooser, and the candidate it weighs (a finished sortie plus whether it has a plan). No Android imports. Ordering and the focus preference live here, not in the caller.
- `app/src/main/java/com/liftoff/app/data/ReFlyCopy.kt` (new): turns a source `FlightPlanDetail` into a `FlightPlanDraft`.
  - `source = REFLY`.
  - Plan fields (title, estimated minutes, warmup, notes, run kind/distance/pace) are copied unchanged.
  - Exercises keep their display name, equipment keys, rest seconds and coach notes.
  - Sets take the planned `reps`/`seconds`/`weight` only, and `added` sets are dropped.
  - Segments are copied.
  - `rawJson` is `"{}"`, as in `simpleRunPlan`: a re-flown plan has no coach output of its own, and the source row keeps its JSON.
  - It lives in `data` next to the Room read models it consumes, keeping `domain` free of them.
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt`: one new operation. It does the guards, then the candidate read (`SortieDao.getFinishedNewestFirst` + `FlightPlanDao.getTitlesForSortieIds`, so one read covers the whole list), the chooser call, `writePlan` and `markPlanned`. It refuses with `IllegalStateException` (via `check`), like the other operations, and does not call `advance()`. The candidate read is a small shared helper that the Launchpad derivation calls too, so both ask the same question the same way.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt`: `LaunchpadState.Pending` gains whether a source plan exists. `deriveActive` computes it in the PENDING branch only. The two fallback branches that also build `Pending` (PLANNED with no plan row, and `else`) pass false, because `refly` would refuse those sorties.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt`: a `refly()` action with the same guard-and-catch shape as `confirmScrub`.
- `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt`: `PendingActions` shows Re-fly next to Scrub, or the muted line. `ActionButtons` passes the new callback.
- `ARCHITECTURE.md`: the M5 row marks Re-fly built, with export/import still to come. The `domain` and `ui` package rows name the chooser and the Launchpad action.
- Tests: `app/src/test/java/com/liftoff/app/domain/ReFlyTest.kt`, `app/src/test/java/com/liftoff/app/data/MissionManagerReFlyTest.kt`, `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadReFlyTest.kt`, and new cases in `LaunchpadStatesTest.kt`.

## Order

Each step ends with its own test classes passing and `:app:assembleDebug` compiling. Then commit that step's named files only, with an `area: lowercase summary` message.

1. `domain/ReFly.kt` + `ReFlyTest`: the chooser and its five §13 cases. Pure JUnit, no Room.
2. `data/ReFlyCopy.kt` + `MissionManager.refly` + `MissionManagerReFlyTest`: the copy end to end through the manager.
3. `LaunchpadState.Pending` + `LaunchpadStates.derive` + new `LaunchpadStatesTest` cases: availability derived from the chooser against seeded history.
4. `LaunchpadViewModel.refly` + `PendingActions` + `LaunchpadReFlyTest`: the button, the muted line, and a tap that writes the plan and lands the Launchpad in `Planned`.
5. `ARCHITECTURE.md` rows.
6. One cold whole-suite gate at the end of the run, run detached, waiting for its exit status. It catches what the per-class gates miss: `MissionManagerTest`, `LaunchpadStatesTest`, `LaunchpadScrubTest` and `LiftoffShellTest` in particular.

## Testing

Chooser: `domain/ReFlyTest`, plain JUnit (the §13 cases).
- Same type and same focus wins over a more recent plan of the same type with a different focus.
- With no focus match, the most recent plan of the same type is used. A plan of the other type is never used.
- Among focus-matching candidates, the most recently landed one is chosen.
- A candidate that was scrubbed, or that has no plan row, is never chosen.
- No landed plan of that type: nothing is returned.

The copy: `data/MissionManagerReFlyTest`, Robolectric + in-memory Room (fixture shape as in `MissionManagerTest`).
- A source whose sets are all DONE with actuals yields a copy carrying `reps`/`seconds`/`weight`, null actuals and `status = OPEN`.
- A set added mid-flight in the source is absent from the copy.
- The copy's `source` is `REFLY`. Its plan fields, exercises (the same exercise ids), equipment, rest seconds, coach notes, and run fields and segments match the source's planned ones.
- A skipped exercise in the source arrives unskipped, with no user note.
- The sortie is `PLANNED` afterwards, through the legal PENDING → PLANNED transition. The source plan is unchanged.
- The copy needs no network: it completes with no daemon configured and writes no `Generation` row.
- Refused cases: a sortie that is not the current one; a sortie that is PLANNED, IN_FLIGHT or LANDED; a PENDING sortie with no source plan. Each leaves the sortie in its state with no plan written or changed.

Launchpad: Robolectric.
- `LaunchpadStatesTest`: the PENDING state reports a source as available when a landed plan of the same type exists. It reports none when there is no such plan, when the only same-type sortie is scrubbed, or when the only plan is of the other type.
- `LaunchpadReFlyTest`: the PENDING screen shows the Re-fly action beside Scrub when a source exists. When none exists it shows the muted line and no Re-fly control. Tapping Re-fly writes the `REFLY` plan, and the screen moves to the Planned state showing the copied title.

All waits are bounded by `withTimeout`, following `LaunchpadScrubTest`'s poll helper. Call `job.cancel()` before `db.close()`.

## Risks

- Exercise names round-trip. `getPlan` returns the exercise's `displayName`, and `writePlan` re-resolves names through the coach normalizer. A display name that normalizes to a different exercise would swap it silently, so a test asserts that the copy points at the same exercise ids as the source.
- `land()` leaves a landed source's sets DONE or SKIPPED, and exercises may be skipped with user notes. The copy reads planned values whatever the status, and never carries a skip or a note.
- `writePlan` deletes the target's existing plan first. The whole re-fly stays inside the manager's `withTransaction`, so a refusal or a failure rolls back and leaves the sortie PENDING with its plan untouched.
- `LaunchpadStates.derive` runs on every emission. The history read therefore happens only in the PENDING branch, and uses the two cheap queries rather than a `getPlan` per candidate.
- `getTitlesForSortieIds` binds one SQL variable per id, and SQLite caps bound variables. Filter the candidates to LANDED sorties of the same type in Kotlin before the title lookup, and chunk the ids, so years of history can't overflow the query.
- A LANDED row with a null `landedAt` (a hand-seeded fixture) sorts as oldest. The chooser treats null as oldest instead of guessing.
- `refly` must not call `advance()`. The sortie is PLANNED by the time it returns, so `advance` would at best do nothing and at worst write a second plan.
- Coach generation is not wired, so Re-fly shows on every PENDING sortie. Keep the availability decision in the derivation alone, so the later limit lands in one place.
- The known flake `doneBodyweightSetStepperShowsBW` (Room closed pool) can fail a whole-suite run. Re-run the same tree before treating it as broken.

## Decisions

**Which landed plan is "the most recent".** The one whose sortie landed last: the sortie's landed time, not the moment its plan row was last written. A user asking "what did I do last time?" means the last sortie they trained. A plan edited afterwards (a scrub of a set, a note added from the Landed detail view) would otherwise jump the queue. The alternative, the most recently written plan, is what the data makes easiest. But it would re-fly a plan the user touched yesterday over one they actually ran last week.

**Focus matching when either side has no focus.** It counts as no focus match, so the chooser falls back to the most recent plan of the same type. Matching is exact text after trimming and case-folding, so "5k" and "5K" match. The alternative, treating a missing focus as a wildcard that matches any focused plan, would let a plan built for a specific focus be chosen for a sortie that has none. That reads as a wrong pick.

**Re-fly is one tap, with no confirmation.** Tapping Re-fly copies the plan and moves the sortie to PLANNED immediately. Scrub has a confirmation dialog because it throws a sortie away. Re-fly throws nothing away: the source plan stays in history untouched, and the sortie it fills had no plan at all. The alternative is a confirm dialog on every use, which the brief's "underlined action next to Scrub" does not describe.

**No record of which sortie was copied.** The copy carries `source = REFLY` and nothing else, so the app does not show "copied from the 12 Oct run". Recording the source sortie id would need a Room schema change, which the brief forbids. A user can still find the original in Landed history.

**When there is no source plan, the Launchpad says so instead of showing nothing.** A short muted line appears in the action row where the Re-fly button would have been ("No earlier plan to re-fly."). The brief asks for a muted line. The alternative, a disabled Re-fly button, is a dead control, which this UI avoids elsewhere.

**Re-fly replaces whatever plan the current sortie already has.** A PENDING sortie has no plan in practice. If a row does exist, the copy overwrites it rather than merging or refusing. The alternative, refusing, would surface an internal state that the user never sees and cannot act on.
