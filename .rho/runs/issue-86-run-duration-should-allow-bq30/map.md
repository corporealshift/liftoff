# Map of the code

What a run's duration is in this app: the **duration the owner records when a run sortie lands**.
There is no stopwatch, no GPS and no per-run target duration the owner sets (`grep` for
`elapsed|stopwatch|timer|System.currentTimeMillis` in `app/src/main` finds nothing), and the only
place a person sets a run's duration is the Land dialog's Duration field. DESIGN.md §16 states the
scope: "Time-based logging of runs (live GPS, timers) is out of scope. Runs record an optional
distance and duration at landing." (Mission Control's `sortieLengthMinutes` is a length setting for
the whole sortie — see the last section.)

## Where a run duration is entered

- `app/src/main/java/com/liftoff/app/ui/inflight/RunLandDialog.kt` — the Land card for a run.
  `RunLandDialog` wraps `RunLandDialogContent` (kept apart from the `Dialog` "so they can be tested
  outside a Dialog"). Two `remember`ed blank fields: `distance`, `minutes`. Fields are
  `LabeledTextField`s: `"Distance (mi|km)"` and `"Duration (min)"`, both `KeyboardType.Decimal`.
  `onLand: (distance: String, minutes: String) -> Unit`. `Land` is always enabled; blank lands with
  both null.
- `app/src/main/java/com/liftoff/app/ui/inflight/RunLanding.kt` — the pure parser.
  `sealed interface RunEntry { Valid(distance: Double?, minutes: Double?); Invalid(distanceError: String?, minutesError: String?) }`;
  `fun parseRunEntry(distanceText: String?, minutesText: String?): RunEntry`. Private `Amount`
  (Given/Blank/Rejected) + `parseAmount`: trim, blank → null, `,` → `.`, reject non-finite and
  `<= 0`. Error texts are file-level `private const val DISTANCE_ERROR = "Distance must be a number
  above 0"` and `MINUTES_ERROR = "Duration must be a number above 0"`.
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt:102-123` — `InFlightState.Run`
  branch; `onLand` opens the dialog (line 108-114). Line 222:
  `onLand = { distance, minutes -> if (vm.landRun(distance, minutes, onBack)) landingRun = false }`.
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt:111-149` —
  `runErrors: MutableStateFlow<RunEntry.Invalid?>`, `clearRunErrors()`, and
  `landRun(distanceText, minutesText, onDone): Boolean`: parse first (a rejected field lands
  nothing), then `missionManager.land(sortieId, entry.distance, entry.minutes)`.

## Where a run duration is stored

- `app/src/main/java/com/liftoff/app/data/Sortie.kt:33-34` — `runDistance: Double?` and
  `runMinutes: Double?` (`@ColumnInfo(name = "runMinutes")`). Already a `Double`, so fractional
  minutes are storable today (`MissionStatesTest` seeds `runMinutes = 18.5`).
- `app/src/main/java/com/liftoff/app/data/MissionManager.kt:321-337` —
  `suspend fun land(sortieId, runDistance: Double? = null, runMinutes: Double? = null): Long?`;
  the actuals travel with the state change inside the transaction.
- Export/import/backup carry it as a number of minutes: `DatabaseExport.kt:74`,
  `DatabaseImport.kt:179`, `BackupDocument.kt:67` (`@SerialName("run_minutes")`).
- `app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json` — the Room schema; unchanged by this
  work, because no column changes.

## Where a run duration is shown

- `app/src/main/java/com/liftoff/app/ui/mission/MissionScreen.kt:251-259` — the landed run line:
  `val minStr = state.runMinutes?.let { "%.0f min".format(it) } ?: "—"`. **This rounds**: checked
  against the JDK, `18.5` prints `19 min`, `27.566666666666666` prints `28 min`, `27.75` prints
  `28 min`. `Math.round(minutes * 60)` gives 1110, 1654 and 1665 whole seconds for the same three.
  `SortieDetailView` (line 185) and `LandedDetailView` (line 227)
  are `private`; no test renders `MissionScreen` at all (`grep MissionScreen app/src/test` is empty).
- `app/src/main/java/com/liftoff/app/ui/mission/MissionState.kt:86-97` —
  `SortieDetailState.Landed` carries the raw `runDistance: Double?` / `runMinutes: Double?` and the
  screen formats them; built around line 188-189.
- `app/src/main/java/com/liftoff/app/ui/landed/LandedState.kt:244-245` —
  `recordedDuration = sortie.runMinutes?.let { formatMinutes(it) }` (a `String?` on
  `LandedDetail.Run`, line 142).
- `app/src/main/java/com/liftoff/app/ui/landed/LandedScreen.kt:257` — draws it as
  `BigValue(label = "TIME", value = detail.recordedDuration ?: "—")`.
- `app/src/main/java/com/liftoff/app/ui/sortie/PlanFormat.kt` — the shared formatters:
  `trimTrailingZero` (line 36), `formatDistance` (47), `formatMinutes` (59, `"10 min"`, `"2.5 min"`,
  doc: "Minutes are shown as stored, not rounded"), `formatPlanHead` (89). `formatMinutes` is also
  used for **coach-authored plan segments** (`InFlightState.kt:146`, `LandedState.kt:240`) — those
  are planned values, not the owner's recorded duration.
- `app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt:172-179` — `runLine` prints the
  recorded actuals into the History section: `"  run: " + parts.joinToString(" in ")`, with
  `sortie.runMinutes?.let { "${number(it)} min" }`. `number(Double?)` (line 311) writes whole
  numbers whole and keeps a fraction via `BigDecimal.stripTrailingZeros().toPlainString()`.
  `coach/` imports only `data` and `settings` — never `ui` (invariant 2: `domain/` and `coach/` are
  pure Kotlin).
- `app/src/test/resources/prompts/{outline,lift-flight-plan-with-outline,run-flight-plan}.txt:44`
  — the three golden prompts each contain `run: 3.1 mi in 28 min`, from `runMinutes = 28.0`
  (`CoachPromptBuilderTest.kt:271`, `CoachPromptGoldenFileTest.kt:186`). Whole minutes print
  unchanged under a min+second reading, so these files need no edit.

## Settings that are *not* this duration

- `Settings.kt:12` / `SettingsStore.kt:18,70` — `sortieLengthMinutes: Int` ("Sortie length
  (minutes)", `MissionControlScreen.kt:235`, validated whole-and-above-0 in
  `MissionControlViewModel.kt:126-130`). A sortie length, not a run's recorded duration;
  `CoachReplyValidator.kt:190-195` caps a plan's `estimated_minutes` at 1.25 × it.
- `app/src/main/resources/schemas/run-plan.json` — a generated run plan's `segments[].minutes`
  (`type: number`, so already fractional). Coach-authored; the owner never sets it.

## Tests that already touch this

- `app/src/test/java/com/liftoff/app/ui/inflight/RunLandingTest.kt` — pure `parseRunEntry` cases:
  blanks, `31,5`, trimming, and the rejection family (`0`, `-3`, `abc`, `NaN`, `Infinity`), each
  asserting which field's error is set.
- `app/src/test/java/com/liftoff/app/ui/inflight/LandScrubDialogTest.kt` — Compose (`createComposeRule`)
  on `RunLandDialogContent`: line 163 `onAllNodesWithText("Duration (min)").assertCountEquals(2)`,
  lines 169-171 count the editable fields (`hasSetTextAction()`, 4 across two cards) and assert each
  starts empty; lines 176-215 (`runLandDialogShowsEachFieldErrorUnderItsOwnField`) call
  `parseRunEntry` directly, feed the resulting `RunEntry.Invalid` into the card's `errors`, and use
  `getUnclippedBoundsInRoot` on `onAllNodes(hasSetTextAction()).assertCountEquals(2)` to check each
  error sits under its own field. A third field changes both counts and that field ordering, and the
  card's `onLand = { _, _ -> }` reaches the signature change.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt` — view-model + DAO, no Compose:
  `landRun("5.2", "31")` read back out of `sortieDao` (line 338+), the rejection sweeps (403, 432),
  the busy guard (471), enqueue cases (503, 545).
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightViewModelTest.kt:605-643` — `landRun`
  rejects an invalid distance/duration without touching the manager.
- `app/src/test/java/com/liftoff/app/ui/mission/MissionStatesTest.kt:329-344` —
  `landedSortieDetailShowsRecord` seeds `runDistance = 3.2, runMinutes = 18.5` and asserts the raw
  state fields.
- `app/src/test/java/com/liftoff/app/ui/landed/LandedStatesTest.kt:315-349` — asserts
  `recordedDuration == "32 min"` for a whole-minute run, in mi and km.
- `app/src/test/java/com/liftoff/app/ui/sortie/PlanFormatTest.kt:88-94` — `formatMinutes(10.0) ==
  "10 min"`, `formatMinutes(2.5) == "2.5 min"`.
- `app/src/test/java/com/liftoff/app/data/BackupRoundTripTest.kt:202` — round-trips
  `runMinutes = 27.75`.

## Docs that set rules

- `DESIGN.md:196-199` (§5.3) — a run's simple plan; "At landing the owner can enter a distance and
  duration; both are optional."
- `DESIGN.md:810-811` (§16) — time-based run logging is out of scope; runs record an optional
  distance and duration at landing.
- `DESIGN.md:182-183` (§5.1) — a LANDED sortie "cannot be edited after landing, apart from its
  notes", so the Land dialog is the only place a run duration can be set.
- `ARCHITECTURE.md:63` (invariant 2) — `domain/` and `coach/` are pure Kotlin with no Android
  imports; `ARCHITECTURE.md:75` — the gate is `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
- No design mock in `design/screens/` mentions Duration, and DESIGN.md has no Land-dialog field
  spec, so no design-doc edit is needed for the new field.
