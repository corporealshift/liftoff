# Map of the code

What a run's duration is in this app: the **duration the owner records when a run sortie lands**.
There is no stopwatch, no GPS and no per-run target duration the owner sets (`grep` for
`elapsed|stopwatch|timer|System.currentTimeMillis` in `app/src/main` finds nothing), and the only
place a person sets a run's duration is the Land dialog's Duration field. DESIGN.md §16 states the
scope: "Time-based logging of runs (live GPS, timers) is out of scope. Runs record an optional
distance and duration at landing." (Mission Control's `sortieLengthMinutes` is a length setting for
the whole sortie — see the last section.)

## Where a run duration is entered

- `app/src/main/java/com/liftoff/app/ui/inflight/RunLandDialog.kt` (123 lines) — the Land card for a
  run. `RunLandDialog` (19) wraps `RunLandDialogContent` (49, already `internal`, kept apart from the
  `Dialog` "so they can be tested outside a Dialog"). Two `remember`ed blank fields: `distance`,
  `minutes`. Fields are `LabeledTextField`s: `"Distance (mi|km)"` and `"Duration (min)"`, both
  `KeyboardType.Decimal`. `onLand: (distance: String, minutes: String) -> Unit`. `Land` is always
  enabled; blank lands with both null.
- `app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt:306` — `LabeledTextField`
  lives here, not in its own file, and is `internal`. Its parameters are `label, value, onChange,
  keyboardType, error` — **no `Modifier`** — and its root is `Column(Modifier.fillMaxWidth())`. So two
  of them side by side in a `Row` each ask for the whole row width and the second is squeezed out;
  putting Duration and Seconds on one row needs a weighted wrapper around each (or a local variant of
  the field). `LabeledTextFieldWithIcon` (342, `private`) is the same shape.
- `app/src/main/java/com/liftoff/app/ui/inflight/RunLanding.kt` (51 lines) — the pure parser.
  `sealed interface RunEntry { Valid(distance: Double?, minutes: Double?); Invalid(distanceError: String?, minutesError: String?) }`;
  `fun parseRunEntry(distanceText: String?, minutesText: String?): RunEntry` (23). Private `Amount`
  (Given/Blank/Rejected) + `parseAmount` (45): trim, blank → null, `,` → `.`, reject non-finite and
  **`<= 0`** — which is why a `0` in the minutes field is a rejection today and has to stop being one
  if the above-0 rule moves to the combined total. Error texts are file-level `private const val
  DISTANCE_ERROR = "Distance must be a number above 0"` and `MINUTES_ERROR = "Duration must be a
  number above 0"` (15-16).
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightScreen.kt:102-123` — `InFlightState.Run`
  branch; `onLand` opens the dialog (108-114). Line 222:
  `onLand = { distance, minutes -> if (vm.landRun(distance, minutes, onBack)) landingRun = false }`.
- `app/src/main/java/com/liftoff/app/ui/inflight/InFlightViewModel.kt:111-149` —
  `runErrors: MutableStateFlow<RunEntry.Invalid?>` (112), `clearRunErrors()` (114), and
  `landRun(distanceText, minutesText, onDone): Boolean` (120): `left`/`busy` guards, parse first (a
  rejected field lands nothing), then `missionManager.land(sortieId, entry.distance, entry.minutes)`
  (133) and `leaveOnce(onDone)`.

## The call sites the extra text reaches

- `parseRunEntry` in `app/src/main`: only `InFlightViewModel.kt:123` (besides its own definition at
  `RunLanding.kt:23`).
- `landRun` in `app/src/main`: only `InFlightScreen.kt:222`.
- `parseRunEntry` in `app/src/test`: **16** calls in `RunLandingTest.kt` (19, 24, 29, 35, 36, 43, 48,
  54, 60, 68, 76, 84, 93, 101, 109, 118) and **3** in `LandScrubDialogTest.kt` (179-181) — all two
  arguments.
- `.landRun(` in `app/src/test`: **10** calls — 8 in `InFlightRunTest.kt` (350, 378, 403, 432, 472,
  474, 513, 555) and 2 in `InFlightViewModelTest.kt` (615, 641) — all two strings plus a trailing
  lambda, so a defaulted third parameter keeps every one of them compiling; an undefaulted one means
  editing all 10 call sites (and 19 more for `parseRunEntry`). Either is allowed; no existing case may
  be deleted or renamed.
- `formatMinutes(` in `app/src/main`: `InFlightState.kt:146`, `LandedState.kt:240`,
  `LandedState.kt:245`, `FlightPlanSection.kt:101`, and its own definition at `PlanFormat.kt:59`. Only
  `LandedState.kt:245` is the owner's **recorded** duration; the other three are coach-authored plan
  segments.

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
  are `private`; no test renders `MissionScreen` at all (`grep MissionScreen app/src/test` is empty),
  and it is called only at `MissionScreen.kt:214` — a different function from `LandedScreen.kt:149`'s.
- `app/src/main/java/com/liftoff/app/ui/mission/MissionState.kt:86-97` —
  `SortieDetailState.Landed` carries the raw `runDistance: Double?` / `runMinutes: Double?` and the
  screen formats them; built around line 188-189.
- `app/src/main/java/com/liftoff/app/ui/landed/LandedState.kt:244-245` —
  `recordedDuration = sortie.runMinutes?.let { formatMinutes(it) }` (a `String?` on
  `LandedDetail.Run`, line 142).
- `app/src/main/java/com/liftoff/app/ui/landed/LandedScreen.kt:257` — draws it as
  `BigValue(label = "TIME", value = detail.recordedDuration ?: "—")`.
- `app/src/main/java/com/liftoff/app/ui/sortie/PlanFormat.kt` (119 lines) — the shared formatters:
  `trimTrailingZero` (line 36), `formatDistance` (47), `formatMinutes` (59, `"10 min"`, `"2.5 min"`,
  doc: "Minutes are shown as stored, not rounded"), `formatPlanHead` (89). `formatMinutes` is also
  used for **coach-authored plan segments** (`InFlightState.kt:146`, `LandedState.kt:240`) — those
  are planned values, not the owner's recorded duration.
- `app/src/main/java/com/liftoff/app/coach/CoachPromptBuilder.kt` (339 lines) — `runLine` (172-179)
  prints the recorded actuals into the History section: `"  run: " + parts.joinToString(" in ")`, with
  `sortie.runMinutes?.let { "${number(it)} min" }`. `number(Double?)` (line 311) writes whole
  numbers whole and keeps a fraction via `BigDecimal.stripTrailingZeros().toPlainString()`.
  `coach/` imports only `data` and `settings` — never `ui` (invariant 2: `domain/` and `coach/` are
  pure Kotlin), so the min+second reading is written a second time inside `coach/` rather than
  imported from `PlanFormat.kt`.
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

- `app/src/test/java/com/liftoff/app/ui/inflight/RunLandingTest.kt` (123 lines) — pure `parseRunEntry`
  cases, no Robolectric: blanks, `31,5`, trimming, and the rejection family (`0`, `-3`, `abc`, `NaN`,
  `Infinity`), each asserting which field's error is set. `zeroMinutesIsRejected` (53) and
  `bothFieldsBadReportBothErrors` (107, `parseRunEntry("abc", "0")`) are the two that lean on the
  above-0 rule for minutes.
- `app/src/test/java/com/liftoff/app/ui/inflight/LandScrubDialogTest.kt` (223 lines) — Compose
  (`@RunWith(AndroidJUnit4::class)` at 24, `createComposeRule()` at 29) on `RunLandDialogContent`:
  line 163 `onAllNodesWithText("Duration (min)").assertCountEquals(2)`, lines 169-171 count the
  editable fields (`hasSetTextAction()`, `assertCountEquals(4)` at 170 across two cards) and assert
  each starts empty; `runLandDialogShowsEachFieldErrorUnderItsOwnField` (178-221) calls
  `parseRunEntry` directly (179-181), feeds the resulting `RunEntry.Invalid` into the card's `errors`,
  and uses `getUnclippedBoundsInRoot` on `onAllNodes(hasSetTextAction()).assertCountEquals(2)` at 209
  to check each error sits **below** its own field by comparing `bottom`/`top` — which assumes the
  fields are stacked in a `Column`, so a Duration+Seconds `Row` needs that assertion rethought, not
  relaxed. Its `onLand = { _, _ -> }` lambdas (149, 153, 190) reach the signature change.
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightRunTest.kt` — view-model + DAO, no Compose:
  `runLandWithDistanceAndDurationStoresBoth` (338-363) reads `landRun("5.2", "31")` back out of
  `sortieDao` asserting 5.2 and 31.0; the rejection sweeps (403, 432), the busy guard (449), enqueue
  cases (503, 545).
- `app/src/test/java/com/liftoff/app/ui/inflight/InFlightViewModelTest.kt` — `landRun` refusals at
  610 (`landRunRejectsInvalidDistanceWithoutTouchingTheManager`) and 636
  (`landRunRejectsInvalidDurationWithoutTouchingTheManager`): both assert the false return, the single
  field error, no `message`, zero `doneCalls`, and the row still `IN_FLIGHT` with both run fields
  null.
- `app/src/test/java/com/liftoff/app/ui/mission/MissionStatesTest.kt:329-344` —
  `landedSortieDetailShowsRecord` seeds `runDistance = 3.2, runMinutes = 18.5` and asserts the raw
  state fields only, so it is unaffected by how the screen formats them.
- `app/src/test/java/com/liftoff/app/ui/landed/LandedStatesTest.kt` (555 lines) — asserts
  `recordedDuration == "32 min"` at 317, 332 and 349, all whole-minute. **No test anywhere asserts a
  fractional `recordedDuration`** (`grep -rn recordedDuration app/src/test` finds only those three), so
  changing what `recordedDuration` holds breaks no existing assertion.
- `app/src/test/java/com/liftoff/app/ui/sortie/PlanFormatTest.kt` (354 lines) — `formatMinutes(10.0)
  == "10 min"` (88) and `formatMinutes(2.5) == "2.5 min"` (94).
- `app/src/test/java/com/liftoff/app/coach/CoachPromptBuilderTest.kt` (721 lines) —
  `historySectionCarriesLandedRunActualsAndNoScrubbedRun` (255) and
  `historySectionOmitsALandedRunThatRecordedNeitherDistanceNorMinutes` (279) build history through the
  `landed(...)` / `scrubbed(...)` helpers (640-675, which take `runDistance`/`runMinutes`) and read the
  section back with `historyLines(prompt)`.
- `app/src/test/java/com/liftoff/app/data/BackupRoundTripTest.kt:202` — round-trips
  `runMinutes = 27.75`.
- `app/src/test/java/com/liftoff/app/ui/landed/` holds only `LandedStatesTest.kt`; there is no
  `LandedScreenTest`.

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

## The gate (verify.sh)

`verify.sh` is the harness from issue 78's committed `verify.sh` (`f4e85b4`, CI green on PR #87) kept
byte-for-byte — `set -euo pipefail`, the launcher order (`bash gradlew.sh` under MINGW/MSYS/CYGWIN,
else `$HOME/android-toolchain/gradle/bin/gradle`, else `./gradlew`, else `bash gradlew.sh`; toolchain
vars exported only when the dirs exist and only when unset), the one gate run
(`:app:assembleDebug :app:testDebugUnitTest --no-build-cache`, 3 attempts), the JUnit-XML index, the
FROM-CACHE guard, `transient_failure` (allowlist: `LiftoffShellTest`, `InFlightScreenTest`), `t`/`c`,
and the result block. Only the header, `WORK="app/build/verify86"` and the `t`/`c` lists differ.

**It was rewritten after the verify step**: `db31032` "run: verify.sh rewritten by claude review"
dropped the `MissionLandedDetailTest` `t` check and added the header line ruling out a UI render of
the Mission tab's landed detail. So the script now names **9 cases** and **15 classes** (24 checks),
not 10 and 15, and the untouched-tree baseline is **17 ok / 8 FAIL**, not 17/9. The 780-test-record /
92-class measurement below was taken against the earlier script.

Measured on the untouched tree (2026-11-25): attempt 1 failed on `LiftoffShellTest >
systemBackFromMissionControlReturnsToTabItWasOpenedFrom` (the recorded flake) and was retried;
attempt 2 green, `> Task :app:testDebugUnitTest` with no cache suffix, **780 test records across 92
classes**; 17 checks ok, 9 FAIL, exit 1. `InFlightRunTest.runLandWithDistanceAndDurationStoresBoth`
matched and passed, which is what shows the `t` matcher reads real report names.

Names the work steps must spell exactly (a case in a differently-named class is never found):

- `LandScrubDialogTest.runLandDialogShowsSecondsBesideDurationAndItsErrorUnderItsOwnField`
- `RunLandingTest.secondsOutsideZeroToFiftyNineAreRejectedWithTheSecondsError`
- `RunLandingTest.secondsWithNoMinutesIsValidAndZeroMinutesWithZeroSecondsIsRejected`
- `InFlightViewModelTest.landRunRejectsInvalidSecondsWithoutTouchingTheManager`
- `InFlightRunTest.runLandWithMinutesAndSecondsStoresTheCombinedDuration`
- `InFlightRunTest.runLandWithDistanceAndDurationStoresBoth` — the existing case, kept
- `PlanFormatTest.formatRunDurationMinutesAndSeconds`
- `LandedStatesTest.runDetailRecordedDurationReadsMinutesAndSeconds`
- `CoachPromptBuilderTest.historyLineWritesARunDurationInMinutesAndSeconds`
- `c CoachPromptGoldenFileTest`, plus `c` on `RunLandingTest`, `LandScrubDialogTest`,
  `InFlightRunTest`, `InFlightViewModelTest`, `InFlightStatesTest`, `PlanFormatTest`,
  `LandedStatesTest`, `MissionStatesTest`, `CoachPromptBuilderTest`, `SortieDaoTest`,
  `MissionManagerTest`, `DatabaseExportTest`, `DatabaseImportTest`, `BackupRoundTripTest`

`MissionLandedDetailTest.landedRunDetailShowsTheRecordedDurationInMinutesAndSeconds` is **not** a gate
check any more, but plan.md's step 2, its Files list (`LandedDetailView` becomes `internal` "so a test
can read the line") and its Testing section all ask for it, so tasks.md gives it to task 2 as a
plan-required test: a new file
`app/src/test/java/com/liftoff/app/ui/mission/MissionLandedDetailTest.kt`, a static Compose render of
`LandedDetailView` from a `SortieDetailState.Landed` built in the test — no Room, no `waitUntil`, no
view model, so it does not join the allowlisted flaky classes.

Not checked, with the reason in the script's header: the plan's Decisions, the Room schema / export /
backup format (no column changes — the existing data classes are required green instead), and any
DESIGN.md edit (§16 rules out timers/GPS; this is still manual entry at landing).

## Running the gate on this machine

`./gradlew` in this worktree **is** the standard POSIX wrapper and is executable; only `gradlew.sh` is
the Windows-only one. The toolchain dirs exist (`~/android-toolchain/jdk`, `~/android-toolchain/sdk`,
`~/android-toolchain/gradle/bin/gradle`) but `JAVA_HOME`/`ANDROID_HOME` are unset in a fresh shell, so
either export them or use the toolchain gradle directly:
`JAVA_HOME=$HOME/android-toolchain/jdk ANDROID_HOME=$HOME/android-toolchain/sdk ./gradlew
:app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain`.
`:app:compileDebugUnitTestKotlin` first and grep its `e:` lines before writing a test. Reports land in
`app/build/test-results/testDebugUnitTest/TEST-*.xml`; a `--tests` run leaves only those classes'
reports, so clear that directory before reading any run's reports, and run the whole gate once before
reading them. Scratch tests and gate logs go under `app/build/`, never `app/src/test`.
