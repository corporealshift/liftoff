# In-Flight Checklist Screen — Implementation Plan

## Approach

Replace the placeholder body of `InFlightScreen.kt` with the real checklist UI. It renders only from `InFlightViewModel.state` (an `InFlightState` derived from Room by `InFlightStates`/`deriveInFlight`), and every tap calls one of the holder's actions: `check`, `uncheck`, `saveEdit`, `skipSet`, `reopenSet`, `skipExercise`, `unskipExercise` and `addSet`. The UI holds no copy of set state. The only local state is which done or skipped cards are expanded and which set's stepper is open. Room re-emits through `observeChanges()`, so the screen re-renders after every write and comes back exactly as it was left after a process kill. Nothing uses the network.

Sizes follow the mockup with 1 px = 1 dp. Colours come from the theme tokens, type from `LiftoffType`, and the check glyph from `LiftoffIcons.check()`.

## Involved Files

| File | Change |
|---|---|
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt` | Rewrite the body; keep the signature `InFlightScreen(sortieId: Long, container: AppContainer, onBack: () -> Unit)`. Drop the `LaunchedEffect` that reads the DAOs directly. |
| `app/src/main/java/com/liftoff/app/ui/inflight/InFlightState.kt` | Small additive change: add `weightUnit: WeightUnit` to `InFlightState.Ready`, and add `plannedWeightLabel: String` and `plannedCountLabel: String` to `SetRow`, all filled in `deriveInFlight`. No existing test constructs `Ready` or `SetRow`, so this cannot break them. |
| `app/src/test/java/com/liftoff/app/ui/inflight/InFlightScreenTest.kt` | Keep both existing tests; their eyebrow assertions are still valid. Add the new Compose test (see Step 8). |

**Read-only dependencies:** `InFlightStates.kt`, `InFlightViewModel.kt`, `FlightPlanDao.kt` (action semantics), `AppContainer.kt`, theme `Color.kt`, `Buttons.kt` (`UnderlinedTextButton`), `Stripes.kt` (`DuoStripe`), `OffsetShadow.kt` (`Modifier.offsetShadow`), `Shape.kt` (`CardShape`, `ButtonShape`), `Type.kt` (`LiftoffType`), `Icons.kt` (`LiftoffIcons.check()`), and `ui/sortie/PlanFormat.kt` (`formatWeight`, `internal`, same module).

Things that do **not** exist and must not be invented: `StrikethroughText`. For strike-through, use `TextStyle(textDecoration = TextDecoration.LineThrough)`. Do not use `OffsetShadowBox` either: it paints a solid colour rect behind its content. Use `Modifier.offsetShadow(color = Ink, shape = CardShape)` instead, because the default shape has 4 dp corners and cards are square.

## Work Order

### Step 0: State additions (`InFlightState.kt`)
- `Ready.weightUnit`: the `unit` that `deriveInFlight` already receives. The stepper uses it to pick ±5 (LB) or ±2.5 (KG).
- `SetRow.plannedWeightLabel`: the planned weight formatted like `weightLabel` (`"35 LB"` / `"BW"`).
- `SetRow.plannedCountLabel`: the planned count, `"45 s"` for seconds or the bare reps such as `"10"` (the mockup shows "of 10"); empty if both are null.
- With these, the UI never formats planned values itself. Existing `InFlightStatesTest` and `InFlightViewModelTest` must pass unchanged.

### Step 1: Screen scaffold and holder wiring
- `val scope = rememberCoroutineScope()` and `val vm = remember(sortieId) { InFlightViewModel(container.database, container.settingsStore, sortieId, scope) }`, as `LaunchpadScreen` does. Then `val state by vm.state.collectAsState()`.
- Layout: a `Column(fillMaxSize, background Cream)` holding:
  1. a fixed header
  2. `DuoStripe()`
  3. a `LazyColumn(Modifier.weight(1f))` of cards, with `contentPadding` 16 dp at the sides and top and 16 dp at the bottom, 14 dp spacing between cards, and room for the 4 dp shadow
  4. the footer
- `Loading`: draw the teal header with no text, then the bands, an empty body and the footer. There is no placeholder text and no spinner, because Room answers almost at once.

### Step 2: Header (teal)
- Padding 22/20/18 dp (top/sides/bottom). Content is 14 dp apart.
- Top row (`verticalAlignment = Bottom`):
  - **Left column** (`weight(1f)`, 2 dp apart):
    - The eyebrow `state.eyebrow`, rendered as one `Text` with exactly that string, in `LiftoffType.eyebrow()` in Mustard. The existing tests find it by exact text.
    - The title `state.title.uppercase()` in `LiftoffType.headerTitle()` in Cream, line height about 0.95 em. It may wrap.
  - **Counter on the right**, only for `Ready`. It is a single `Text` built from an `AnnotatedString`: the done count padded to two digits (`"05"`, 44 sp, Mustard) followed by `"/"` and the total padded to two digits (`"/14"`, 22 sp, Cream), both in Big Shoulders 900. Under it is `"SETS"` (Big Shoulders 900, 13 sp, 0.14 em, Cream). One `Text` means a test can find `"01/07"`.
- **Progress bar** (`Ready` only): a `Row` with 3 dp gaps and one `Box(Modifier.weight(1f).height(10.dp))` per entry of `state.progress`:
  - DONE: filled Mustard.
  - OPEN: a 1.5 dp TealLight border with no fill.
  - SKIPPED: filled TealLight.
- **`NoChecklist`**: the eyebrow and title only, with no counter and no progress bar. The body below the bands shows a short muted line, `"No checklist for this sortie."` in `LiftoffType.note()` in Muted, padded 16 dp.

### Step 3: Collapsed done and skipped rows
- Used when `card.status` is DONE or SKIPPED and the card's `plannedExerciseId` is not in the expanded set.
- Style: a Sand background, 2 dp Ink border, square corners and padding 10/14 dp.
  - Left: the index `"%02d"` (`LiftoffType.index()`, Muted), 10 dp gap, then the name in `LiftoffType.exerciseName()` in Muted with `TextDecoration.LineThrough`.
  - Right, for DONE: `"${doneCount}/${setCount} LANDED"` in Big Shoulders 800, 18 sp, 0.06 em, Teal.
  - Right, for SKIPPED: `"SKIPPED"` in the same style, but Muted.
- The whole row is clickable and adds the id to the expanded set.

### Step 4: Full cards (active, upcoming, expanded done/skipped)
- Paper background, 2 dp Ink border and `CardShape`.
  - ACTIVE also gets `Modifier.offsetShadow(Ink, CardShape)`, applied before the border and background.
  - UPCOMING and expanded cards get no shadow.
- **Header bar:** Ink background, padding 10/14 dp. The index `"%02d"` in `LiftoffType.index()` in Mustard, 10 dp gap, then `name.uppercase()` in `LiftoffType.cardTitle()` in Cream.
  - On an expanded done or skipped card, the header bar is clickable and removes the id from the expanded set (collapse).
  - On active and upcoming cards it is not clickable.
- **Set rows** (Step 5) sit inside 4/14 dp padding, with a 1 dp Rule divider between rows but not after the last one.
- **Footer row:** a 2 dp Ink top border, then three equal 44 dp cells split by 1 dp Ink rules. The text is Work Sans 14/600 in Ink.
  - `"+ Add set"` calls `vm.addSet(card.plannedExerciseId)`.
  - `"Note"` is disabled: Muted text, not clickable, with `Modifier.semantics { disabled() }`.
  - `"Skip"` calls `vm.skipExercise(id)`. On a SKIPPED card it reads `"Unskip"` and calls `vm.unskipExercise(id)`.
  - Expanded done cards show the same footer as active cards.
- An expanded card keeps the status the state reports. If an unskip or uncheck turns it ACTIVE or UPCOMING, it renders as that full card, and its id staying in the expanded set does no harm.

### Step 5: Set rows (58 dp)
- A `Row`, 58 dp high and vertically centred, with columns 52 dp | weight(1f) | weight(1f) | 48 dp and 8 dp gaps.
- **Label:** `row.label`, Work Sans 12/600 with 0.1 em spacing. It is Red when `row.setId == state.currentSetId`, otherwise Muted.
- **Weight and count:** `row.weightLabel` and `row.countLabel` in `LiftoffType.setValue()`.
  - Normally Ink.
  - When `weightDeviates` or `countDeviates`, that value is Red, followed by a small `"of ${plannedWeightLabel}"` or `"of ${plannedCountLabel}"` in Work Sans 11/600 in Muted, aligned on the baseline 6 dp after it.
  - Each value is clickable and opens the stepper for this set.
- **Long press:** `Modifier.combinedClickable(onClick = {}, onLongClick = { open stepper })` on the row. Alternatively, `pointerInput` with `detectTapGestures(onLongPress = …)` on the row, with the child clickables consuming their own taps.
- **Check box:** 48 dp, `ButtonShape` (4 dp corners), 2 dp Ink border.
  - OPEN: White. Tap calls `vm.check(setId)`. contentDescription `"Check off ${card.name} ${row.label}"`.
  - DONE: Red with `Icon(LiftoffIcons.check(), tint = White)` at 24 dp. Tap calls `vm.uncheck(setId)`. contentDescription `"${card.name} ${row.label} done"`.
  - SKIPPED: White with a short Muted dash (a 16×3 dp box). Tap calls `vm.reopenSet(setId)`. contentDescription `"${card.name} ${row.label} skipped"`.

### Step 6: Stepper dialog
- `androidx.compose.ui.window.Dialog` with custom content. Do not use AlertDialog or a bottom sheet.
- The content is a Paper surface with a 2 dp Ink border and the ink offset shadow. Its title is `"${card.name.uppercase()} · ${row.label}"` in `LiftoffType.cardTitle()`.
- **Local edit values** are seeded once per opened set (`remember(setId)`):
  - For a DONE set, from `actualWeight`, `actualReps` and `actualSeconds`.
  - Otherwise, from the planned values.
  - Null weight is treated as 0. These are the only values held locally: a draft for the dialog, not cached state.
- **Weight line:** a "−" button, the value, and a "+" button.
  - The step is 5.0 for `WeightUnit.LB` and 2.5 for `WeightUnit.KG`, taken from `state.weightUnit`.
  - The floor is 0, and 0 displays as `"BW"`. Otherwise it shows `formatWeight(w)` followed by the unit name.
- **Count line:**
  - Seconds (±5, floor 0) when `plannedSeconds != null`. If not, and `plannedReps` is null but `actualSeconds` is set, seconds are also used.
  - Otherwise reps (±1, floor 0), shown as `"× n"` or `"n s"`.
- The ± controls are 44 dp squares with a 2 dp Ink border and `ButtonShape`, in `LiftoffType.setValue()`.
- **Buttons:**
  - **Save** (red, `PrimaryButton` or the same red style) calls `vm.saveEdit(setId, reps, seconds, weight)` and closes the dialog. It passes `reps` or `seconds` for whichever field is edited and null for the other, and `weight = null` when the value is 0.
  - **Skip set** (`UnderlinedTextButton`) calls `vm.skipSet(setId)` and closes.
  - **Cancel** (`UnderlinedTextButton`) closes without writing. Dismissing with back or by tapping outside does the same.
- The open dialog is held as `var editing by remember { mutableStateOf<Long?>(null) }` (a set id). Each render looks the row up from the current `state`. If the id is no longer found, the dialog closes.

### Step 7: Bottom area
- A 3 dp Ink rule on top of a Cream footer, padded 14/20/20 dp. The footer sits outside the `LazyColumn` so it always stays in view.
- It holds `UnderlinedTextButton(text = "Back", onClick = onBack)`, centred.
- There is no Land button.
- Any `@Preview` added must be on a `private fun` (`PreviewsTest` enforces this).

### Step 8: Tests (`InFlightScreenTest.kt`)
- **Existing two tests:** keep them as they are. The placeholder line was never asserted, and the eyebrow is still one `Text`.
  - Because state now arrives through a Room flow on `Dispatchers.IO`, `waitForIdle()` alone may not wait for it.
  - If either test fails for that reason, the only permitted change is to replace `waitForIdle()` with `composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("IN FLIGHT · SORTIE n").fetchSemanticsNodes().isNotEmpty() }` before the same assertion. Call this out in the PR.
- **New test, `checkingASetMarksItDoneInRoomAndUpdatesCounter`:**
  - Seed `container.database`, the per-test file database that Robolectric isolates for each test.
  - The seed is a mission, a LIFT sortie (`index = 1`, IN_FLIGHT), and `flightPlanDao().writePlan(...)` with two or three exercises and 5–7 sets in total, the same shape as `InFlightViewModelTest.buildFixture`.
  - Set the content to `LiftoffTheme { InFlightScreen(sortieId, container, onBack = {}) }`.
  - Wait until `"00/07"` is shown, then click the node with contentDescription `"Check off Bench Press SET 1"`.
  - Wait until `"01/07"` is shown.
  - Assert in Room: `flightPlanDao().getPlan(sortieId)` has the first set with `status == SetStatus.DONE` and actuals equal to the planned values.
- **Optional:** a render test on the same fixture after `checkSet` on all three sets of the first exercise. It asserts that `"3/3 LANDED"`, the active card's uppercase name, and an upcoming card's uppercase name are displayed.

## Testing Strategy

1. `InFlightStatesTest` and `InFlightViewModelTest` stay unchanged and must pass. The `InFlightState.kt` changes only add fields.
2. `InFlightScreenTest` covers the existing eyebrow tests and the new check-box test that goes through Room.
3. Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` must pass. `LiftoffShellTest`, the Launchpad, Mission and Mission Control tests, and `PreviewsTest` are included.

## Risks and Uncertainties

| Risk | Mitigation |
|---|---|
| State arrives asynchronously (Room flow, `flowOn(IO)`), so screen tests read too early | Use `waitUntil` on visible text in the new test. The fallback for the existing tests is in Step 8. |
| The offset shadow is clipped by the `LazyColumn` | Add horizontal and bottom `contentPadding` of at least 4 dp beyond the card edge; 16 dp is used. |
| The long press and the value/check-box clicks conflict | Children use their own `clickable` and consume taps. The row's long-press handler only fires a long press. |
| Two DataStores open on the same file | The screen uses `container.settingsStore`, and the existing test's own store uses a different file in `cacheDir`. Robolectric gives each test fresh app directories. |
| `formatWeight` is `internal` in `ui.sortie` | It is in the same module, so it is already importable (`InFlightState.kt` uses it). |

## Decisions

### 3. Weight unit for the stepper
**Decision:** Use the existing `formatWeight` utility from `PlanFormat.kt`, which already handles unit conversion. The stepper increments (±5 lb / ±2.5 kg) should follow the same unit as the planned value.
Changed by review: the plan chose to rely on `formatWeight` for unit handling and follow "the unit of the planned value". The review chose to add `weightUnit` to `InFlightState.Ready` (from the user's setting, which `deriveInFlight` already receives) and step by 5 for LB or 2.5 for KG. The reason: `formatWeight` only formats a number and does no conversion, planned weights carry no unit, and the screen must not read settings separately from the derived state.

### 5. Done/skipped card expansion target state
**Decision:** When a done/skipped card is tapped to expand, it expands into the full card layout (same as active). Tapping the header collapses it back. No intermediate "edit" state — just show/hide the set rows.
Changed by review: the plan chose to only show or hide the set rows. The review chose to render the full card: the ink header bar (tap to collapse), the set rows, and the footer ("+ Add set · Note · Skip", or "Unskip" on a skipped card), without the shadow. The reason: the brief says it "expands into a full card", and the footer is what allows a mis-tapped skip to be undone.

### 8. Planned value labels for "of <planned>" (Added by review)
**Decision:** Add `plannedWeightLabel` and `plannedCountLabel` to `SetRow` in `deriveInFlight`, so the UI does not format or re-derive planned values. The count shows bare reps ("of 10", as in the mockup) or "45 s".

### 9. Counter format (Added by review)
**Decision:** Both numbers are padded to two digits ("05/14", and "03/09" for a short plan), matching the mockup. It is drawn as one `Text` so tests can find it.

### 10. Loading and no-checklist rendering (Added by review)
**Decision:** While loading, show the empty teal header, the bands and the footer, with no placeholder text. For `NoChecklist`, the header shows the eyebrow and title with no counter and no progress bar. The muted line "No checklist for this sortie." sits in the cream body, because muted text on teal is unreadable.

### 11. SKIPPED label colour (Added by review)
**Decision:** "SKIPPED" on a collapsed skipped row is Muted, not Teal. Teal means "LANDED" in the design tokens, and the name is still struck through, as on a done row.

### 1. Card expansion state: local `remember` vs ViewModel?
**Decision:** Local `remember { mutableStateOf }` in the screen composable. Expansion is purely visual (a mis-tap fix), not persisted to Room. The ViewModel already handles actual set state changes.

### 2. Stepper dialog — Dialog or AlertDialog?
**Decision:** Plain `Dialog` with custom content. Brief explicitly says "Use a plain Dialog with custom content, not AlertDialog or a bottom sheet." This gives full control over styling to match the Space Age theme.

### 4. Progress bar skipped segment — filled teal_light or outlined?
**Decision:** Filled teal_light, matching the brief: "filled teal_light when skipped". This distinguishes it from open segments which are outlined teal_light.

### 6. "SETS" label in counter — what text when only 1 set?
**Decision:** Always use "SETS" (plural) for consistency. The brief shows "SETS" and doesn't mention singular handling; this is an edge case unlikely in practice (sorties have multiple sets).

### 7. Checkbox tap on done/skipped — immediate state change or confirm?
**Decision:** Immediate toggle. Tapping a done box unchecks it (marks open); tapping a skipped box reopens it (marks active/open). No confirmation dialog — the brief says "One tap on the box checks the set."
