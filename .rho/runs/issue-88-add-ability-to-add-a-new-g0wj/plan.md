# Issue #88 — add a new sortie to a week

## What the brief asks

"I want to be able to add a workout to a week, sometimes I'm able to get some in I didn't expect."

A week is planned up front as a pattern of at most seven sorties (DESIGN.md §4.1). The owner sometimes gets a workout in that the week did not plan for. There is currently no way to record one: the only way to get an extra sortie into a week is to reset the whole week and re-plan it. The brief asks for a way to add a single sortie to a week so it can be launched, logged, and seen by Mission Control.

## How this fits this codebase

- **Mission = one week.** `Mission.pattern` is one letter per sortie (`R`/`L`), and `Sortie.index` is that sortie's position in the pattern (ARCHITECTURE.md §3, §4). `SortieDao.insert` and `MissionDao.update` already exist; nothing new is needed at the storage layer.
- **The pattern↔sortie alignment rule already lives in the domain.** `domain/Sorties.kt` holds `swapSortieType`, which rewrites one letter of the pattern and the matching sortie's type together. Adding a sortie is the same alignment problem — a new letter and a new row, kept in step — so it belongs in the same file.
- **`MissionManager` is the only writer of missions and sorties** (ARCHITECTURE.md §5). `MissionManager.swapSortieType` (line 484) is the exact template this change should follow: current-week gate → `db.withTransaction` → domain call → persist both rows → `advance()` → return the generation ids it superseded → the caller enqueues after the commit.
- **`advance(missionId, settings)` (line 612) already prepares the current sortie**: a simple plan for a run when run generation is off, otherwise a coach generation. Reusing it means a newly added sortie is prepared exactly the way a swapped one is, with no new planning code.
- **PR #78 (swap a sortie's type) is the precedent for the whole shape** of this change: a domain function with refusal codes, a manager transaction, a UI dialog, tests at each layer, and a DESIGN.md amendment after approval.
- **Where the UI goes.** DESIGN.md §9 gives the Mission tab exactly one action, "Reset week", and gives the Launchpad the launch actions. Adding a sortie is a week-level edit, not a launch action, so it sits next to Reset week on the Mission tab and follows that tab's existing request → confirm/cancel pattern.

## Design

**1. Domain — `domain/Sorties.kt`**

```kotlin
fun addSortie(mission: Mission, type: SortieType): Pair<Mission, Sortie>
```

Refusals come back as `require` failures, the way `swapSortieType` (same file), `overridePattern` and `confirmWithoutOutline` all refuse: the caller's gates are `check`s and the UI already catches `IllegalStateException` and re-reads the week. A sealed outcome with refusal codes was the first shape considered and dropped — nothing in the UI needs to name a reason, because the button is hidden when an add is impossible, and `domain/` holds no outcome types today (`ConfirmOutcome` lives in `data/MissionManager.kt`).

The mission must be `ACTIVE` or `CLOSED` (a `DRAFT` week is still being planned with Mission Control, and its pattern is edited through `overridePattern`/`confirm`). The pattern must be shorter than seven, because `Pattern.isValidPattern` caps a pattern at seven letters; that cap is now the named `MAX_PATTERN_LENGTH` so the add and the validator can't drift. On success the returned mission has `pattern + letter` and status `ACTIVE`, and the returned sortie is `Sortie(missionId = mission.id, index = mission.pattern.length, type = type, focus = DEFAULT_RUN_FOCUS | DEFAULT_LIFT_FOCUS, state = PENDING)` — the same default focus "Continue without outline" uses.

`domain/Missions.kt` gains `MissionStatus.CLOSED to MissionStatus.ACTIVE` in `MissionLifecycle.legalTransitions`. Without that edge a week whose planned sorties are all finished is `CLOSED`, and the most common reading of the brief — "I got a workout in that the week didn't plan" — would be refused.

**2. Manager — `data/MissionManager.kt`**

```kotlin
suspend fun addSortie(missionId: Long, type: SortieType, onQueued: (Long) -> Unit = {}): List<Long>
```

Mirrors `swapSortieType`: refuse when the mission is missing or its `weekStart != currentWeekOf()`; inside one transaction run the domain call, `db.missionDao().update(updated)`, `db.sortieDao().insert(newSortie)`, then `advance(mission.id, settings)`. `advance` prepares the current sortie, so the new sortie is planned only when it is the current one — which is what happens on a reopened closed week, or when every earlier sortie has landed. Nothing is deleted, so no open generation goes stale and there is nothing for the caller to cancel; `onQueued` is called for the generation `advance` queued, after the transaction commits.

**3. Mission tab UI**

- `MissionState.kt`: `MissionTabState.Week` gains `canAdd: Boolean = status != DRAFT && pattern.length < 7`.
- `MissionViewModel.kt`: `requestAdd()`, `cancelAdd()`, `confirmAdd(type)`. `confirmAdd` runs on the shell's scope (the same scope Reset week uses, because the work must outlive the tab) and passes an `onQueued` to the generation enqueuer, the way `LaunchpadViewModel` wires swap.
- `AddSortieDialog.kt`: Lift and Run, modelled on `ResetWeekDialog`/`launchpad/SwapDialog`.
- `MissionScreen.kt` `WeekContent`: an `Add sortie` underlined button next to `Reset week`, and the dialog.

**4. Docs** — a DESIGN.md amendment covering §4.1 (a sortie can be added to a confirmed week, up to the seven cap), §4.2 (a closed week reopens), and §9 (the Mission tab now has Add sortie alongside Reset week), dated 2026-11-26, in the same style as the 2026-11-23 swap amendment.

## Files to change

| File | Change |
| --- | --- |
| `app/src/main/java/com/liftoff/app/domain/Sorties.kt` | `addSortie`, `AddOutcome`, `AddSortieRefusal` |
| `app/src/main/java/com/liftoff/app/domain/Missions.kt` | `CLOSED → ACTIVE` edge in `MissionLifecycle` |
| `app/src/main/java/com/liftoff/app/data/MissionManager.kt` | `addSortie` |
| `app/src/main/java/com/liftoff/app/ui/mission/MissionState.kt` | `Week.canAdd` |
| `app/src/main/java/com/liftoff/app/ui/mission/MissionViewModel.kt` | add request/confirm/cancel |
| `app/src/main/java/com/liftoff/app/ui/mission/AddSortieDialog.kt` | new |
| `app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt` | `Add sortie` button + dialog |
| `DESIGN.md` | amendment |
| tests | `SortieAddTest`, `MissionManagerAddSortieTest`, `MissionTabStateTest`, `MissionViewModelAddTest`, plus `MissionsTest`'s lifecycle table |

## Order

1. Domain: `addSortie` + the lifecycle edge + tests.
2. Manager: `addSortie` + tests.
3. Mission tab: state, view model, dialog, screen + tests.
4. DESIGN.md amendment.
5. Full CI gate.

## Behaviors the tests must show

- Adding appends one letter to the pattern and one `Sortie` at the end index, with `PENDING` state and the type's default focus; the pattern and the sortie list stay the same length.
- Adding to a `CLOSED` week moves it back to `ACTIVE`.
- Adding to a `DRAFT` week is refused; adding a seventh letter to a seven-sortie week is refused.
- The manager refuses a mission that is not the current week, and refuses a `DRAFT` mission, without writing anything.
- Adding does not touch the current sortie: its state, flight plan and generation rows are unchanged.
- On a reopened closed week the new sortie becomes the current one and is prepared: a simple plan for a run when run generation is off, a queued generation for a lift (and for a run when run generation is on).
- The Mission tab offers `Add sortie` on a confirmed week and hides it on a `DRAFT` week and on a week that already has seven sorties.
- Confirming in the dialog calls the manager on the shell scope and hands the queued generation id to the enqueuer; cancelling writes nothing.

## Risks

- **`CLOSED → ACTIVE` is a new lifecycle edge.** Any code that assumes a closed week stays closed could now see it reopen. Mitigation: only `addSortie` performs the edge; `MissionLifecycle.canTransition` still gates every status write, and `MissionsTest` keeps asserting the rest of the table.
- **Pattern/sortie misalignment.** A letter without a matching row (or the reverse) breaks `currentSortie`, the Launchpad's "sortie n of m", and the coach prompt. Mitigation: the domain function returns both halves and the manager writes both in one transaction; a test asserts the two lengths agree after an add.
- **Generation churn.** `advance` may queue a generation for the new sortie while other generations are open. Mitigation: the same supersede-and-cancel contract swap already uses, tested with a fake enqueuer.
- **Observed state must refresh.** The Mission tab reads `observeWeek` + `observeSortiesForMission`; a new row has to appear without a manual reload. Mitigation: the state test builds from a real in-memory database.
- **Known full-suite flakes** (`LiftoffShellTest` expired `waitFor`, a `CalledFromWrongThreadException` family). Mitigation: re-run the whole gate before reading any report.

## Decisions

- **Where the new sortie goes: appended as the last sortie.** Inserting it before the current sortie would renumber every later sortie, its flight plan and its history row; appending leaves all of them alone and matches how the pattern already reads left to right. If the appended sortie ends up being the current one, it is planned at once.
- **A closed week reopens.** Adding to a `CLOSED` week moves it back to `ACTIVE` so the new sortie can be launched and logged. The alternative — refusing on a closed week — would block the case the brief actually describes ("I got some in I didn't expect", which is usually after the planned sorties are done).
- **Only the current week.** Same rule as swap and reset: `MissionManager` refuses a mission whose `weekStart` is not `currentWeekOf()`. A week started early still counts as the current week.
- **A maximum of seven sorties.** `Pattern.isValidPattern` only accepts one to seven letters, so a week that already has seven refuses an add and the Mission tab hides the button rather than showing a failure.
- **The owner picks the type.** The dialog offers Lift and Run, and the new sortie gets that type's default focus (`"full body"` / `"easy"`), the same focus "Continue without outline" uses. Nothing is sent to Mission Control to choose a focus; the owner can regenerate the sortie from the Launchpad afterwards.
- **The Mission tab, not the Launchpad.** The button sits next to Reset week. The Launchpad's card is about the sortie in front of you, and adding a sortie is a decision about the week; keeping it on the Mission tab also keeps this change to one screen.
- **A `DRAFT` week cannot take an added sortie.** Its pattern is edited through the draft editor and confirmed as a whole; adding mid-draft would fight `overridePattern`'s own gate.
