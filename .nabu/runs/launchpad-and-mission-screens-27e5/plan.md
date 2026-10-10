# Plan: Launchpad and Mission screens

## Approach

The brief asks to replace the two placeholder composables (`LaunchpadScreen`, `MissionScreen`) with real screens that read from Room and react to state changes. The domain logic (state machines, rollover, next-sortie selection) is already written in pure Kotlin under `domain/` and exercised through `MissionManager`. The theme composables (`TitleBlock`, `PatternTrack`, `InkRuledListRow`, `PrimaryButton`, `UnderlinedTextButton`, offset-shadow helpers, `LiftoffIcons`) are already built.

The plan follows the existing architecture pattern: each screen gets a **ViewModel** (state-holder + coroutine scope) that queries Room via DAOs and exposes a `State` sealed interface describing which Launchpad state is current. The ViewModel uses `Flow` from the DAOs so the UI updates live when the database changes. A simple In-Flight placeholder route is added to `ShellNav`.

This fits the codebase because:
- It mirrors how `MissionControlScreen` already works (reads `settingsStore` and `equipmentDao` through `AppContainer`).
- Domain logic stays pure; only data access and UI wiring go in the ViewModel.
- No new dependencies or architectural shifts — just Compose + Room + coroutines, which the project already uses.

## Files involved

### New files

| File | Purpose |
|---|---|
| `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadViewModel.kt` | Reads current week's mission/sorties/flight-plan from Room; exposes `LaunchpadState`; handles launch, scrub (with confirmation), confirm, pattern editing. |
| `app/src/main/java/com/liftoff/app/ui/mission/MissionViewModel.kt` | Reads current week's mission/sorties/flight-plan; exposes `MissionScreenState`. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` | Simple placeholder: shows sortie title, back button. |

### Modified files

| File | Change |
|---|---|
| `app/src/main/java/com/liftoff/app/ui/launchpad/LaunchpadScreen.kt` | Replace placeholder with real screen composable that dispatches to state-specific sub-composables (draft, planned, pending, in-flight-resume, closed). |
| `app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt` | Replace placeholder with real screen: pattern track, outline notes, sortie list. Tapping a sortie shows plan or record. |
| `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt` | Wire ViewModel factories through `LiftoffApplication.container`; pass to `LaunchpadScreen` and `MissionScreen`. Add In-Flight route to navigation. |
| `app/src/main/java/com/liftoff/app/ui/ShellNav.kt` | Add `inFlightSortieId: Long?` field, a route for in-flight, and back-navigation handling. |
| `app/src/main/java/com/liftoff/app/LiftoffApplication.kt` | Ensure container is accessible to ViewModels (already done via `container` property). |
| `app/src/main/java/com/liftoff/app/data/MissionDao.kt` | Add `getByWeekStartFlow(weekStart)` returning `Flow<MissionWithSorties>` (the existing `observeWeek` already does this, but make it public). |
| `app/src/main/java/com/liftoff/app/data/FlightPlanDao.kt` | Ensure `getPlan(sortieId)` is accessible; verify it returns null when no plan exists. |
| `app/src/main/java/com/liftoff/app/data/SortieDao.kt` | Add `getInFlight()` already exists; add `observeCurrentWeekSorties(missionId: Long): Flow<List<Sortie>>`. |

### Tests (new)

| File | Purpose |
|---|---|
| `app/src/test/java/com/liftoff/app/ui/launchpad/LaunchpadViewModelTest.kt` | Robolectric tests for each Launchpad state derived from DB: draft, PLANNED lift with seeded exercises, PLANNED run, PENDING, IN_FLIGHT, closed. |
| `app/src/test/java/com/liftoff/app/ui/mission/MissionViewModelTest.kt` | Tests for Mission tab data derivation: pattern track display, outline notes, sortie list with states. |

## Order of work

### Step 1: Navigation support for In-Flight route

Add `inFlightSortieId` to `ShellNav`, a `goInFlight(sortieId)` method, and back-navigation handling. Update `LiftoffShell` to render the In-Flight placeholder when `shellNav.inFlightSortieId != null`. This is a small, safe change that the Launchpad will depend on.

### Step 2: Launchpad screen and ViewModel

Build the real `LaunchpadScreen` and `LaunchpadViewModel`:

**State model (sealed interface in ViewModel):**
- `Draft` — no confirmed mission this week; shows editable pattern chips + Confirm button.
- `Planned(LiftPlanData)` — sortie is PLANNED, type LIFT; shows eyebrow, title, pattern track, flight plan list, coach note (if any), Launch button, Scrub button.
- `Planned(RunPlanData)` — same but for RUN type; shows run summary instead of exercise list.
- `Pending` — sortie is PENDING; status line "Flight Plan not ready yet"; Scrub where Launch would be.
- `InFlightResume` — an IN_FLIGHT sortie exists across all missions; shows Resume button.
- `Closed` — mission is CLOSED; short completion state.

**Key composable pieces (top to bottom, matching PLANNED mockup):**
1. Eyebrow: "WEEK OF {DATE} · SORTIE {N} OF {TOTAL} · {TYPE}" + title in 68sp display type.
2. Pattern track with chips showing landed/current/upcoming states.
3. "FLIGHT PLAN" section head with estimated minutes and set count, over ruled list of exercises (red index, name, load like "3×8 · 135"). For runs: show title, focus, target distance/segments.
4. Coach note with "Coach:" in teal when plan has notes.
5. PrimaryButton Launch (72dp red, rocket glyph, offset shadow).
6. UnderlinedTextButton Scrub.

**Draft state:** same parts but pattern chips are editable (tap to toggle R/L, add/remove, 1-7 sorties), Confirm button instead of Launch.

### Step 3: Mission screen and ViewModel

Build `MissionScreen` and `MissionViewModel`:
- Week's pattern track at top.
- Outline notes if present.
- Each sortie with index, type (run/lift), focus, state.
- Tapping a sortie shows its Flight Plan detail or a short record for landed/scrubbed sorties (including scrub reason).

### Step 4: In-Flight placeholder

Simple screen showing the sortie's title and a back button. A later brief will build the real checklist here.

### Step 5: Wire everything into LiftoffShell

Pass `AppContainer` from `LiftoffApplication` to each screen so ViewModels can access DAOs. Use `ViewModelProvider` or manual instantiation (project has no DI framework).

### Step 6: Tests

Robolectric tests against an in-memory Room database for:
- Each Launchpad state and how it derives from DB state.
- Mission tab data derivation.
- State transitions (launch → IN_FLIGHT, scrub → SCRUBBED, confirm → ACTIVE with sorties).

### Step 7: Update ARCHITECTURE.md

Mark milestone M2 as done in the milestone table.

## Testing

**Unit tests (Robolectric):**
- `LaunchpadViewModelTest`: seed an in-memory DB with each state configuration; verify ViewModel exposes the correct `LaunchpadState`; test launch, scrub, confirm actions.
- `MissionViewModelTest`: seed a mission with sorties in various states; verify sortie list, pattern track data, outline notes display.

**Manual verification:**
- Fresh install → opens to draft for current week with default RLRLR pattern.
- Confirm → sortie 1 (a run under default pattern) shows as PLANNED with Launch available.
- Scrub → advances to next sortie.
- Launch → sets sortie IN_FLIGHT, opens In-Flight placeholder.

**Project gate:** `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTests` passes.

## Risk and uncertainty

1. **ViewModel lifecycle with Flow.** The ViewModel must cancel DAO flows on dispose to avoid leaks. Robolectric's coroutine support needs the correct rule (`MainDispatcherRule` or similar).
2. **Pattern editing UX.** The brief says "editable R/L pattern chips (tap to toggle, add, remove; 1-7 sorties)". This is a small interaction surface but needs careful Compose state management. The plan uses a `List<Char>` state that the ViewModel exposes and updates through commands.
3. **Run vs Lift display.** For run sorties, the brief says "show the run summary (title, focus, any target distance or segments)". Since M2 doesn't have generated run plans yet (only SIMPLE_RUN), the summary will be minimal: title "Run", focus text, and no distance/segments. This is correct per the design — generated run plans come in M6.
4. **Coach note for simple run plans.** The `simpleRunPlan` sets `notes = sortie.focus`. The brief says show coach note "when the plan has notes". A simple run's focus as a coach note is acceptable; if it looks odd, we can gate it on non-null and non-empty notes.

## Decisions

**1. What does the draft state show for pattern editing?**
- **Choice:** Pattern chips are rendered as circles (same visual as upcoming state) that toggle R↔L on tap. A "+" chip at the end adds a sortie (up to 7); tapping an existing chip's edge removes it. The Confirm button is disabled until the pattern changes from default or the user explicitly confirms.
- **Alternative:** A text input for the pattern string.
- **Why:** Chips match the visual language of the rest of the screen and are immediately understandable as toggleable items.

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
