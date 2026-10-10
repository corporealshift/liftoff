# In-Flight Checklist Screen — Implementation Plan

## Approach

Replace the placeholder UI in `InFlightScreen.kt` with the real checklist layout, building directly on the existing state machine (`InFlightState`, `InFlightStates`, `InFlightViewModel`) and theme components. No re-derivation of state in the UI — all rendering flows through `InFlightState.Sealed`. The stepper edit dialog is a plain `Dialog` with custom content, not Material components.

All dimensions use 1 dp = 1 px (matching the mockup). All colours come from theme tokens. Typography uses `LiftoffType`. Icons use `LiftoffIcons`.

## Involved Files

| File | Change |
|---|---|
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` | Rewrite: header, progress bar, mustard/red bands, done/skipped rows, active/upcoming cards, set rows, stepper dialog, footer. Replace placeholder Composable body entirely. |
| `app/src/test/java/com/liftoff/app/ui/inflight/InFlightScreenTest.kt` | Update assertions to match new UI text (placeholder → real content). No structural changes needed unless the new layout introduces new testable elements. |

**Read-only dependencies:** `InFlightState.kt`, `InFlightStates.kt`, `InFlightViewModel.kt`, `Color.kt`, `Buttons.kt`, `Headings.kt`, `Icons.kt`, `OffsetShadow.kt`, `Type.kt`, `Stripes.kt`, `Shape.kt`, `PlannedSet.kt`, `Enums.kt`, `PlanFormat.kt` (`formatWeight`).

## Work Order

### Step 1: Header with counter, progress bar, and bands
- **What:** Teal full-width header row containing:
  - Eyebrow in mustard (Work Sans 12/600, uppercase) — "IN FLIGHT · SORTIE {name}"
  - Title in cream (Big Shoulders 44/900, uppercase) — "SORTIE {id}" or similar
  - Counter on the right: done count in mustard (Big Shoulders, large), "/total" in cream (same size), "SETS" label below in muted (Work Sans, small)
  - When no checklist: header without counter, short muted line instead
- **Progress bar:** One segment per set. Mustard filled = done; outlined teal_light = open; filled teal_light = skipped. Segments fill the available width evenly.
- **Bands:** Mustard band (5 dp) then red band (5 dp) under the progress bar using existing `DuoStripe` or equivalent striped composable.

### Step 2: Card rendering logic
- **Done card:** Sand background row (sand colour, 2 dp ink border). Name struck through with `StrikethroughText`. "n/n LANDED" in teal at the right. Tapping expands into full card.
- **Skipped card:** Same layout as done but text says "SKIPPED" in muted/teal (use muted for SKIPPED label, matching mockup). Tapping expands into full card.
- **Active card:** Paper background, 2 dp ink border, ink header bar with mustard index and cream uppercase name, offset shadow (`OffsetShadow` composable), set rows below, footer row.
- **Upcoming card:** Same as active but without the offset shadow.

### Step 3: Set rows (58 dp)
Each `PlannedSet` renders as a row:
- "SET n" label — red for current set (Big Shoulders 16/800), muted otherwise
- Weight display using `formatWeight` from `PlanFormat.kt` — deviating value shown in red with small muted "of {planned}" beside it
- Reps or seconds display — same deviation logic
- Checkbox: 48 dp, 2 dp ink border, 4 dp corners. White = open; red + white check mark = done; muted dash = skipped

### Step 4: Stepper dialog (plain Dialog)
- Triggered by tapping weight/reps values or long-pressing the row
- Plain `Dialog` with custom content — no AlertDialog, no bottom sheet
- Controls: − and + buttons for weight (±5 lb / ±2.5 kg; 0 = bodyweight), reps (±1), or seconds (±5)
- Save button marks set done with entered values
- Skip set button
- Cancel button
- Styled in theme colours

### Step 5: Card footers
- Active and upcoming cards have a footer row: "+ Add set · Note · Skip" separated by 1 dp ink rules
- "Note" shown disabled (muted, non-clickable) — next brief makes it work
- Expanded skipped card shows "Unskip" instead of "Skip"

### Step 6: Footer with Back button
- Cream background, 3 dp ink rule at top
- Back text button (UnderlinedTextButton or similar theme component)
- No Land button — that belongs to the next brief

### Step 7: Update tests
- `InFlightScreenTest.kt`: update placeholder text assertions to match new UI. Add a Robolectric Compose test verifying checkbox tap marks set done in Room and updates counter.

## Testing Strategy

1. **Unit tests (existing):** `InFlightStatesTest.kt` and `InFlightViewModelTest.kt` must continue passing — no changes to state derivation or view model actions.
2. **UI test (`InFlightScreenTest.kt`):** Update assertions for removed placeholder text. Verify new composables render without errors in a `createComposeRule()`.
3. **New Robolectric Compose test:** Seed a test database with a lift plan, launch `InFlightScreen`, tap a checkbox, verify the set is marked done in Room and the counter updates.
4. **Build gate:** `bash gradlew.sh :app:assembleDebug :app:testDebugUnit` must pass.

## Risks and Uncertainties

| Risk | Mitigation |
|---|---|
| `formatWeight` is in `sortie` package, not `inflight` — may need import or relocation | Import as-is; if it causes package issues, move to a shared `ui/util` package (low risk) |
| Stepper dialog needs keyboard handling for accessibility | Start with tap-based controls; add keyboard support if needed in next brief |
| Expanding/collapsing done/skipped cards — state management complexity | Use simple `remember { mutableStateOf }` per card ID in the screen composable, not persisted state |
| Progress bar segment sizing on different screen widths | Use `Modifier.weight(1f)` for each segment; clip to prevent overflow |
| Long-press gesture conflict with other gestures | Use `PointerInputScope.detectTapGestures` with `onLongPress` and `onClick` separately |

## Decisions

### 1. Card expansion state: local `remember` vs ViewModel?
**Decision:** Local `remember { mutableStateOf }` in the screen composable. Expansion is purely visual (a mis-tap fix), not persisted to Room. The ViewModel already handles actual set state changes.

### 2. Stepper dialog — Dialog or AlertDialog?
**Decision:** Plain `Dialog` with custom content. Brief explicitly says "Use a plain Dialog with custom content, not AlertDialog or a bottom sheet." This gives full control over styling to match the Space Age theme.

### 3. Weight unit: lb or kg?
**Decision:** Use the existing `formatWeight` utility from `PlanFormat.kt` which already handles unit conversion. The stepper increments (±5 lb / ±2.5 kg) should follow the same unit as the planned value.

### 4. Progress bar skipped segment — filled teal_light or outlined?
**Decision:** Filled teal_light, matching the brief: "filled teal_light when skipped". This distinguishes it from open segments which are outlined teal_light.

### 5. Done/skipped card expansion target state
**Decision:** When a done/skipped card is tapped to expand, it expands into the full card layout (same as active). Tapping the header collapses it back. No intermediate "edit" state — just show/hide the set rows.

### 6. "SETS" label in counter — what text when only 1 set?
**Decision:** Always use "SETS" (plural) for consistency. The brief shows "SETS" and doesn't mention singular handling; this is an edge case unlikely in practice (sorties have multiple sets).

### 7. Checkbox tap on done/skipped — immediate state change or confirm?
**Decision:** Immediate toggle. Tapping a done box unchecks it (marks open); tapping a skipped box reopens it (marks active/open). No confirmation dialog — the brief says "One tap on the box checks the set."
