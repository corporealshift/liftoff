# Plan: Launchpad and Mission screens

## Approach

Replace the placeholder `LaunchpadScreen` and `MissionScreen` with real screens. They read Room live and act through `MissionManager`, which `AppContainer` provides.

The work splits into three layers, matching the code that already exists:

1. **Derivation (plain Kotlin, testable with Robolectric).** For each screen, a class takes `LiftoffDatabase` and `MissionManager` and exposes `fun observe(): Flow<...State>`. It holds no Compose or Android UI code, so a test can seed an in-memory DB, call `manager.onAppOpen()` and read `observe().first { it !is Loading }`.
2. **State holders.** These are plain classes like `MissionControlViewModel` and `EquipmentViewModel`: a constructor that takes a `CoroutineScope`, no androidx `ViewModel` superclass, a `StateFlow` collected from the derivation, and action methods that call `MissionManager`.
3. **Composables.** They are built only from `com.liftoff.app.ui.theme` parts: `TitleBlock`, `PatternTrack`, `InkRuledListRow`, `PrimaryButton`, `UnderlinedTextButton`, `OffsetShadowBox` and `LiftoffIcons`.

"Ensure the current week" is `MissionManager.onAppOpen()`. `MainActivity` runs it every time the activity reaches STARTED, which covers both app open and return to the foreground.

## Files involved

### New files

| File | Purpose |
|---|---|
| `app/src/main/java/com/liftoff/app/ui/sortie/PlanFormat.kt` | Pure formatting functions: week eyebrow date (`OCT 5`), sortie label (`SORTIE 2 OF 5 · LIFT`), exercise load (`3×8 · 135`, `3×8 · BW`, `3×45 s`), Flight Plan head (`≈55 min · 14 sets`), and pattern chip states from a pattern plus sorties. |
| `app/src/main/java/com/liftoff/app/ui/sortie/FlightPlanSection.kt` | Shared composable used by both screens: the "FLIGHT PLAN" head over a 3 dp ink rule, then either the exercise rows or the run summary, then the coach note. It lives outside `ui/theme`, which keeps `PreviewsTest`'s count of 9 theme previews valid. |
| `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadState.kt` | The sealed `LaunchpadState` and the `LaunchpadStates(db, manager)` derivation class. |
| `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` | Plain state holder. Actions: `toggleChip(i)`, `addChip()`, `removeChip()`, `confirm()`, `launch(onLaunched)`, `requestScrub()`, `setScrubReason()`, `confirmScrub()`, `cancelScrub()`. |
| `app/src/main/java/com/liftoff/app/ui/mission/MissionState.kt` | `MissionTabState`, `SortieDetailState` and the `MissionStates(db, manager)` derivation, with `observe()` and `observeSortie(sortieId)`. |
| `app/src/main/java/com/liftoff/app/ui/mission/MissionViewModel.kt` | Plain state holder for the tab. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` | Placeholder that shows the sortie's Flight Plan title and a Back control. |

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/liftoff/app/data/MissionManager.kt` | Add `val currentWeekStart: StateFlow<LocalDate?>`. It starts null and is set at the end of `onAppOpen()`, after the transaction commits. The screens observe the week from it, so they move to the new week when rollover runs. No other behavior changes. |
| `app/src/main/java/com/liftoff/app/data/FlightPlanDao.kt` | Add `observeChanges(): Flow<Int>`, a `@Query` that selects from `flightPlan`, `plannedExercise`, `plannedSet` and `runSegment` (for example a sum of `COUNT(*)` subqueries). It exists only as a change signal, so the screens re-read the plan through `getPlan()` whenever a plan table changes, including the later Regenerate and generation writes. |
| `app/src/main/java/com/liftoff/app/MainActivity.kt` | In `onCreate`, add `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { container.missionManager.onAppOpen() } }`. `lifecycle-runtime-ktx` is already a dependency. |
| `app/src/main/java/com/liftoff/app/ui/ShellNav.kt` | Add `inFlightSortieId: Long? = null`, `openInFlight(id)`, and `back()` from In-Flight that clears it. `select()` also clears it. Extend `encode`/`decode` with an optional in-flight part so the route survives recreation. Existing encodings (`Mission`, `Landed|1`) must decode as before, and bad input still decodes to `ShellNav()`. |
| `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt` | Get `container` once at the top, the same way the Mission Control branch already does. Add a branch: `missionControlOpen` → Mission Control; else if `inFlightSortieId != null` → `InFlightScreen`, full height with no top bar or bottom bar, matching `in-flight.html`; else the tabs. Pass `onOpenInFlight = { nav = nav.openInFlight(it) }` to `LaunchpadScreen`. Light nav-bar icons apply when Mission Control or In-Flight is open, because both sit on cream. |
| `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` | The real screen. It takes `container` and `onOpenInFlight`. |
| `app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt` | The real screen. It takes `container`. Sortie detail is a `rememberSaveable` selected-id, with a `BackHandler` that returns to the list. |
| `app/src/main/java/com/liftoff/app/ui/theme/PatternTrack.kt` | Add `ChipState.Scrubbed` (see Decisions) and handle it in `chipSizeDp` and `PatternChipView`. Do not add a new `@Preview`. |
| `app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt` | Several tests assert the placeholder copy ("Your next Flight Plan will appear here.", "This week's pattern and sorties will appear here.", "THIS WEEK"), which this work removes. Replace those assertions with real screen content: the draft's `CONFIRM` button on Launchpad, and the Mission tab's title. Room loads off the main thread, so wait with `composeRule.waitUntil(5_000) { onAllNodesWithText(...).fetchSemanticsNodes().isNotEmpty() }`. Keep every behavior the tests check: tab switching, back handling, recreation, and nav-bar icon contrast. `placeholderScreensShowTheirCopy` keeps only the Landed placeholder check. |
| `app/src/test/java/com/liftoff/app/ui/ShellNavTest.kt` | Add cases for the in-flight route, its back behavior and its encode/decode round trip. |
| `ARCHITECTURE.md` | Mark M2 as done in the milestone table: "Done — Regenerate comes with generation (M3); In-Flight is a placeholder until M4". Update the `ui` row in the package table: Launchpad and Mission are real screens, Landed is still a placeholder. |

Nothing else changes. `MissionDao.observeWeek(weekStart)` already returns the mission with its sorties sorted by index, so no new mission or sortie DAO methods are needed. `LiftoffApplication` already exposes `container`.

### Tests (new)

| File | Purpose |
|---|---|
| `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadStatesTest.kt` | Robolectric tests against an in-memory DB and a `MissionManager` with a fixed clock, set up like `MissionManagerTest`. One test per state: Loading before `onAppOpen`; Draft with the default pattern on a fresh DB; PLANNED lift with seeded exercises (an ACTIVE mission, a LIFT sortie in PLANNED, and `writePlan` with exercises whose sets are mixed, bodyweight and timed), checking the eyebrow, title, rows, load strings, head and coach note; PLANNED run after confirming `RLRLR`, which is sortie 1 with Launch available; PENDING after scrubbing that run, which also shows that scrub advances to sortie 2; IN_FLIGHT after launch; Closed. It also checks that the state updates live, with no resubscription, after confirm, scrub and launch. |
| `app/src/test/java/com/liftoff/app/ui/mission/MissionStatesTest.kt` | Tests the Mission tab derivation: pattern chips from mixed sortie states (landed, scrubbed, current and upcoming); outline notes present and absent; sortie rows with index, type, focus and state; a draft mission; and the sortie detail for a planned sortie (the plan), a PENDING sortie ("No Flight Plan yet"), a landed sortie (the record) and a scrubbed sortie (the record with its reason). |
| `app/src/test/java/com/liftoff/app/ui/sortie/PlanFormatTest.kt` | Plain JUnit tests for every formatter, including weight `135.0` → `135`, `22.5` → `22.5`, null weight → `BW`, seconds → `45 s`, differing sets, a null estimate, and singular "1 set". |

## Order of work

### Step 1: Ensure the current week, and the change signal

- Add `currentWeekStart` to `MissionManager`. Add the `repeatOnLifecycle(STARTED)` call in `MainActivity`.
- Add `FlightPlanDao.observeChanges()`.
- Run the existing `MissionManagerTest` and DAO tests. They must still pass.

### Step 2: Formatting and shared parts

- Write `PlanFormat.kt` with its tests.
- Write `FlightPlanSection`. For a lift it shows one `InkRuledListRow` per exercise: index `01`, `02` and so on, then the name and the load. For a run it shows the run summary rows: focus, target distance and pace when present, and one row per segment with its description and distance or minutes. Under the list comes the coach note: "Coach:" in `Teal` with weight 600, then the text in `Muted` using `LiftoffType.note()`.
- Add `ChipState.Scrubbed` to `PatternTrack`.

### Step 3: Launchpad derivation and state

`LaunchpadState`:
- `Loading`: `currentWeekStart` is null, or the week's mission row isn't there yet.
- `Draft(weekStart, pattern, missionId)`: the mission is `DRAFT`.
- `Planned(header, chips, plan: FlightPlanDetail, sortieId)`: the current sortie is PLANNED. Lift and run are both handled by `FlightPlanSection`, based on whether the plan has exercises or the sortie's type.
- `Pending(header, chips, sortieId)`.
- `InFlight(header, chips, plan: FlightPlanDetail?, sortieId)`.
- `Closed(weekStart, chips, landedCount, total)`: the mission is `CLOSED`, or it is ACTIVE with no current sortie.

`header` holds the eyebrow (`WEEK OF OCT 5 · SORTIE 2 OF 5 · LIFT`) and the title. The sortie number is `index + 1`, the total is `pattern.length`, and the type is `RUN` or `LIFT`.

Derivation: `manager.currentWeekStart.filterNotNull().flatMapLatest { missionDao.observeWeek(it) }`, combined with `flightPlanDao.observeChanges()`, then `mapLatest`. The current sortie is `currentSortie(sorties)` from the domain package, and its plan is read with `flightPlanDao.getPlan(id)`. In-flight is the current sortie's own state. Rollover in `onAppOpen` already scrubs in-flight sorties from past weeks.

### Step 4: Launchpad screen

The layout follows `launchpad.html`. Content scrolls: `TitleBlock` (the eyebrow, then the title in `LiftoffType.screenTitle()`, 68 sp), then `PatternTrack`, then `FlightPlanSection`. The actions are pinned at the bottom, padded 20 dp at the sides and 14 dp at the bottom. Each state:

- **Planned:** `PrimaryButton("Launch", icon = LiftoffIcons.rocket(), iconAtEnd = true)` with the default 72 dp height, then `UnderlinedTextButton("Scrub")`. There is no Regenerate button.
- **Pending:** a status line, "Flight Plan not ready yet.", in place of the Flight Plan list, and `UnderlinedTextButton("Scrub")` in the Launch slot.
- **InFlight:** the same summary, with `PrimaryButton("Resume", rocket)` that calls `onOpenInFlight(sortieId)`.
- **Draft:** editable chips (see Decisions), then `PrimaryButton("Confirm")`. Each chip edit calls `manager.setPattern(missionId, newPattern)`, so the draft persists and the screen stays live.
- **Closed:** the completion state.
- **Scrub:** opens a confirmation dialog (see Decisions). Confirming calls `manager.scrub(sortieId, reason)`.
- **Launch:** calls `manager.launch(sortieId)`, then `onOpenInFlight(sortieId)`.

Every action catches `IllegalStateException`, which covers `IllegalTransitionException`. On a failure it stays put and re-runs `manager.onAppOpen()`, because the state is probably stale, for example after a week boundary. Actions must never crash the scope.

### Step 5: Mission tab

`MissionTabState`: `Loading`; then `Week(weekStart, status, chips, outlineNotes, rows)`, where each row is index, type, focus and state.

- Eyebrow: `WEEK OF OCT 5`. Title: `Mission`.
- Then the pattern track, then the outline notes (in `LiftoffType.note()`) if any.
- Then one `InkRuledListRow` per sortie: index `01`, the title `Lift · full body`, and the state label as the detail (`PLANNED`, `PENDING`, `IN FLIGHT`, `LANDED`, `SCRUBBED`). Each row is clickable.
- **Draft:** the chips are all upcoming, and a line reads "Not confirmed yet. Confirm this Mission on the Launchpad."
- **Sortie detail** (`observeSortie(id)`): a `TitleBlock` with eyebrow `SORTIE 2 · LIFT · LANDED` and the plan title, or the focus if there is no plan. Then:
  - PLANNED, IN_FLIGHT, or PENDING with a plan: `FlightPlanSection`.
  - PENDING with no plan: "No Flight Plan yet."
  - LANDED: a record. It shows the landed date, "N of M sets landed" when there is a lift plan, the run distance and minutes if recorded, and notes.
  - SCRUBBED: "Scrubbed", then the reason, or "No reason given". If any sets were checked before the scrub, it also shows "N of M sets landed".
  - An `UnderlinedTextButton("Back")` and the system back both return to the list.

### Step 6: In-Flight placeholder and shell wiring

`InFlightScreen(sortieId, container, onBack)` reads the plan title once, falling back to `Sortie N`. It shows `TitleBlock(eyebrow = "IN FLIGHT · SORTIE N", title = <title>)`, a line saying the In-Flight checklist comes later, and `UnderlinedTextButton("Back")`. Wire up the `ShellNav` and `LiftoffShell` changes listed above.

### Step 7: Tests, shell test update, docs

Write the tests listed above and update `LiftoffShellTest` and `ShellNavTest`. Update `ARCHITECTURE.md`.

## Testing

- Robolectric derivation tests for each Launchpad state (draft, PLANNED lift with seeded exercises, PLANNED run, PENDING, IN_FLIGHT, closed) and for the Mission tab, as listed above.
- Pure tests for the formatters, and `ShellNav` tests for the In-Flight route.
- The existing `LiftoffShellTest`, updated for the real screens, still covers the shell.
- **Project gate:** `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.

Manual check, if a device is available:
- A fresh install opens to a draft with `RLRLR`.
- Confirm shows sortie 1, Run, as PLANNED with Launch.
- Scrub, then confirm the dialog, shows sortie 2 as PENDING.
- On a planned sortie, Launch opens the In-Flight placeholder; Back returns to Launchpad showing Resume.

## Risk and uncertainty

1. **Room off the main thread in shell tests.** Compose idling doesn't track Room's executor, so the shell tests need `waitUntil`.
2. **The `observeChanges` signal.** Room re-runs a Flow query on every invalidation of the tables it reads, including tables in subqueries. If that doesn't hold, fall back to observing `observeWeek` alone: today every plan write happens in the same transaction as a sortie state change.
3. **`PreviewsTest` counts exactly 9 previews in `ui/theme`.** Don't add previews there. Previews in `ui/launchpad`, `ui/mission` or `ui/sortie` are fine.
4. **Week boundary while the app stays in the foreground.** Rollover runs only on open or return to the foreground, as the brief asks. If an action fails because the state is stale, it re-runs `onAppOpen()`.

## Decisions

**1. What does the draft state show for pattern editing?**
- **Choice:** Pattern chips are rendered as circles (same visual as upcoming state) that toggle R↔L on tap. A "+" chip at the end adds a sortie (up to 7); tapping an existing chip's edge removes it. The Confirm button is disabled until the pattern changes from default or the user explicitly confirms.
- **Alternative:** A text input for the pattern string.
- **Why:** Chips match the visual language of the rest of the screen and are immediately understandable as toggleable items.
- Changed by review: the plan chose edge-tap removal and a Confirm button that starts disabled. The review chose the interaction Mission Control already uses: tap a chip to toggle R↔L, a "−" button removes the last sortie and a "+" button appends an R (1–7, with each button disabled at its limit), and Confirm is always enabled. The reason: the brief's done criteria require confirming the unchanged default pattern, edge taps on a 44 dp circle are not discoverable, and the owner already knows the Mission Control control.

**2. What happens when Launch is pressed on a PLANNED sortie?**
- **Choice:** The ViewModel calls `MissionManager.launch(sortieId)`, then navigates to the In-Flight route with that sortie's ID.
- **Alternative:** Navigate first, then launch.
- **Why:** If launch fails (e.g., another process changed state), we'd be stuck on an invalid screen. Launch first ensures the DB is consistent before navigation.

**3. What does the Mission tab show for a sortie with no Flight Plan yet?**
- **Choice:** Shows the sortie's index, type badge (R/L), focus text, and state label. Tapping it shows "No flight plan yet" with the state.
- **Alternative:** Hide sorties without plans.
- **Why:** The user needs to see all sorties in the week's mission, not just the current one.

**4. What does the closed state display?**
- **Choice:** A short message: "Mission complete" with the week date. No further action buttons.
- **Alternative:** Show a "Start new week" button.
- **Why:** The brief says "a short completion state". New-week creation happens automatically on app open (via `onAppOpen`), so no explicit button is needed.

**5. How does the In-Flight placeholder handle back navigation?**
- **Choice:** A back button returns to the Launchpad tab, showing the resumed sortie state. The In-Flight route is cleared from `ShellNav`.
- **Alternative:** Back goes to Mission tab.
- **Why:** The Launchpad is the home screen and shows the current state of all sorties; it's the natural landing point.

**6. What text appears in the "Pending" status line?**
- **Choice:** "Flight Plan not ready yet."
- **Alternative:** "Coach is planning…" or "Waiting for generation".
- **Why:** The brief explicitly says "a status line saying no Flight Plan is ready yet". This matches that wording directly.

**7. Added by review: what does the Scrub confirmation look like?**
- **Choice:** A dialog built from theme parts: a `Paper` card with a 2 dp ink border and the offset shadow. It reads "Scrub sortie N?", has an optional reason field in the text-field style Mission Control uses, a "Scrub" `PrimaryButton`, and a "Cancel" `UnderlinedTextButton`. A blank reason is stored as null.
- **Alternative:** A stock Material `AlertDialog` with no reason field.
- **Why:** The Mission tab shows the scrub reason, so the owner needs a way to give one. Without the field, the only reason that could ever appear is "week ended". `design/README.md` says not to ship stock M3 visuals.

**8. Added by review: how does the pattern track show a scrubbed sortie?**
- **Choice:** A new `ChipState.Scrubbed`: a 44 dp `Sand` circle with a 2 dp ink border and the letter in `Muted`.
- **Alternative:** Draw scrubbed sorties as landed (a check mark) or as upcoming.
- **Why:** Both alternatives misstate what happened, and scrubbing is a core action in this milestone.

**9. Added by review: how is an exercise's load written?**
- **Choice:**
  - If every set has the same reps (or seconds) and weight: `N×reps · weight` (`3×8 · 135`). With no weight: `3×8 · BW`. For timed sets: `3×45 s`.
  - If the sets differ: the per-set values joined with `/`, then the weight, or a `min–max` weight range (`8/8/6 · 135`).
  - Weights drop a trailing `.0` and show no unit, as in the mockup.
- **Alternative:** Always show the first set only.
- **Why:** This matches the three forms in `launchpad.html` and doesn't misstate plans whose sets vary.

**10. Added by review: when is the coach note shown?**
- **Choice:** When the plan's `notes` is non-blank and differs from the sortie's focus.
- **Alternative:** Whenever `notes` is non-null.
- **Why:** A `SIMPLE_RUN` plan stores the focus ("easy") as its notes, and the run summary already shows the focus. Showing it again as "Coach: easy" would repeat it and credit the coach with a default.

**11. Added by review: titles for the states that have no plan title.**
- **Choice:**
  - Draft: eyebrow `WEEK OF OCT 5 · DRAFT`, title `New Mission`.
  - PENDING: the usual sortie eyebrow, with the sortie's focus (for example `Full body`) as the title, or `Lift`/`Run` if it has no focus.
  - Closed: eyebrow `WEEK OF OCT 5 · MISSION CLOSED`, title `Mission complete`, the pattern track, and "N of M sorties landed."
- **Alternative:** Reuse the placeholder heading ("This week / Launchpad").
- **Why:** Every state keeps the same 68 sp layout, and every title says what is on screen.

**12. Added by review: what does IN_FLIGHT show besides Resume?**
- **Choice:** The same eyebrow, title, pattern track and Flight Plan list as PLANNED, with Resume in the Launch slot and no Scrub.
- **Alternative:** Resume and Scrub together.
- **Why:** The brief lists only Resume for this state. Scrubbing a sortie that is in flight belongs to the In-Flight screen that a later brief builds.

**13. Added by review: what shows before the current week has been ensured?**
- **Choice:** A `Loading` state that draws only the background. Both screens read the week from `MissionManager.currentWeekStart`, which `onAppOpen()` sets.
- **Alternative:** Compute the week from the clock inside each screen.
- **Why:** One place decides the current week. The screens also never show a stale week between returning to the foreground and the rollover finishing.
