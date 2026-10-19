# Map — Export and Import actions in Mission Control

Run `export-and-import-actions-in-mission-t9r9`. Branch base `1099fed` (plan outline) on top of
`c183c1c Database export and import: bring in the finished data layer (#27)`. **The data layer is
already merged** — this run is UI + wiring only. Plan HEAD is `8376717 run: plan revised by claude review`;
verify.sh is committed at `cb49a9c`; the work is split into six tasks in
`.rho/runs/export-and-import-actions-in-mission-t9r9/tasks.md`.

## Gate / build facts

- Gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` from the repo root (`gradlew.sh`
  pins JDK/SDK under `~/android-toolchain`; bare `gradle`/`java`/`adb` are not on PATH).
  Compile early and often with `bash gradlew.sh :app:compileDebugKotlin`.
- Test counts come from the XML in `app/build/test-results/testDebugUnitTest/*.xml`
  (`tests=`, `failures=`, `errors=`), not from `BUILD SUCCESSFUL`.
- `androidx.activity:activity-compose:1.9.3` is already a dependency (`app/build.gradle.kts:58`), so
  `rememberLauncherForActivityResult` needs **no build change**. Robolectric 4.14.1 (:84),
  `kotlinx-coroutines-test` (:80), compose ui-test-junit4 (:87).
- Scratch files for a detached run go under `app/build/` (`mkdir -p app/build` first).

## Main code

| File | What it holds / why it matters |
|---|---|
| `app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt` (543 lines) | `MissionControlScreen(settingsStore, scope, equipmentDao, onBack)` at :70. Body: back `OutlinedButton` + `TitleBlock(eyebrow="Settings", title="Mission Control")` (:108), then `MissionControlContent(vm, state)` (:111), then `SectionCard("EQUIPMENT")` (:112). Section heads in order: CONNECTION :123, COACH :149, MISSION :157, UNITS :175, RUNS :190, OBJECTIVES AND CONSTRAINTS :194, EQUIPMENT :112. `internal fun SectionCard(title, content)` :209 = `Paper` fill + 2 dp `Ink` border + `LiftoffType.sectionHead()` label + 16 dp spacer. `internal fun LabeledTextField` :231, `internal fun LabeledMultiLineTextField` (used :195). View models are built with `remember(settingsStore)` / `remember(equipmentDao)` and read with `collectAsState()`. Previews :470-543 are `LocalInspectionMode`-guarded, so tests never run them. |
| `app/src/main/java/com/liftoff/app/ui/control/MissionControlViewModel.kt` (211) | The pattern to copy: `data class MissionControlState(...)` with defaults + derived `canAddChip`/`canRemoveChip`, then "Plain Kotlin state holder … No Android imports, no ViewModel superclass" — `MutableStateFlow` exposed as `StateFlow state`, plain setter functions that validate then `scope.launch` the write. `EquipmentViewModel` is the same shape. |
| `app/src/main/java/com/liftoff/app/ui/control/EquipmentSection.kt` | Mission Control's own example of ink-ruled rows + text buttons: `InkRuledListRow(index = "${index + 1}", …)` :99 and :153, with `UnderlinedTextButton(..., modifier = Modifier.weight(1f))` in an actions row; `UnderlinedTextButton("Add equipment")` :115. |
| `app/src/main/java/com/liftoff/app/ui/theme/Buttons.kt` | `PrimaryButton(text, onClick, modifier, icon?, iconAtEnd, height = 72.dp, labelStyle)` :28, `InkButton(…, height = 64.dp)` :92, `UnderlinedTextButton(text, onClick, modifier)` :155. **All three uppercase their label** (`text.uppercase()`), and **none has an `enabled` parameter**. Both big buttons build on the public `OffsetShadowBox(color, offset, modifier)` + `background(Ink/RedPressed…, ButtonShape)` + `border(2.dp, Ink)` + `clickable(interactionSource, indication = null, role = Role.Button)`. `UnderlinedTextButton` is nothing like them: a `Box` (`fillMaxWidth`, `heightIn(min = 44.dp)`, `padding(horizontal = 8.dp)`, `contentAlignment = CenterStart`) around `Text(text.uppercase(), LiftoffType.textButton().copy(color = Red when pressed else Ink))`, the underline in `Modifier.drawBehind` at `getLineBaseline(last line) + 4.dp` with `strokeWidth = 1.dp` and the same colour as the label, and `clickable(interactionSource, indication = null, role = Role.Button)` on the Box. That is the shape a disabled-capable copy in `ui.control` must mirror. |
| `app/src/main/java/com/liftoff/app/ui/theme/InkRuledListRow.kt` | `InkRuledListRow(index: String, title: String, detail: String? = null, modifier, actions: (@Composable RowScope.() -> Unit)? = null)` — 30 dp `Red` index, baseline-aligned title/detail, optional actions row, ink rule underneath. |
| `app/src/main/java/com/liftoff/app/AppContainer.kt` (26) | Already exposes everything the new UI needs: `settingsStore`, `database: LiftoffDatabase` (`liftoff.db`), `missionManager = MissionManager(database, settingsStore)`. **No change needed here.** |
| `app/src/main/java/com/liftoff/app/LiftoffApplication.kt` | Owns `container`; the shell reaches it through `LocalContext`. |
| `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt` (261) | Mission Control call site :111, inside `if (nav.missionControlOpen)`, where `val container = (LocalContext.current.applicationContext as LiftoffApplication).container` is already in scope at :110 — so passing `database` + `missionManager` is two lines there. Shell's own `BackHandler` :81; cold-start hook :88-93 reads `coldStartContainer.missionManager` / `database`. |
| `app/src/main/java/com/liftoff/app/data/DatabaseExport.kt` (152) | `suspend fun exportDatabase(db: LiftoffDatabase, out: OutputStream, clock: Clock = Clock.systemUTC())` :15 — one `withTransaction`, pretty-printed JSON + trailing newline, written straight to the caller's stream. `internal fun roomSchemaVersion(db)` :51 = `db.openHelper.readableDatabase.version` (the `@Database` annotation is CLASS-retained and unreadable by reflection). |
| `app/src/main/java/com/liftoff/app/data/DatabaseImport.kt` (257) | `sealed interface ImportResult { Refused(message) :15, Restored(missions, sorties) :18 }`; `suspend fun importDatabase(db, input: InputStream, manager: MissionManager): ImportResult` :34. Accepts an empty database **or one holding only the fresh DRAFT Mission** (:52-57, deletes it via `missionDao().deleteDraft`). Refusal messages are plain sentences at :76-89 — "Import needs an empty database. This phone already has training data.", "That file isn't a Liftoff backup…", "Made by a newer version of Liftoff. Update the app first.", "This backup is incomplete — …". Writes through Room DAO `insertAll*` inside `db.withTransaction` (`insertInForeignKeyOrder` :146-156), then `manager.onAppOpen()` :69 — so **Room's invalidation tracker re-emits every tab's flows**; no extra wiring is needed for "the other tabs show the restored data", as long as the screen is handed the *same* `LiftoffDatabase` instance the tabs observe. |
| `app/src/main/java/com/liftoff/app/data/BackupDocument.kt` (153) | The JSON shape: `BackupDocument(header, missions, sorties, flightPlans, plannedExercises, plannedSets, runSegments, exercises, generations, equipment)`, snake_case `@SerialName`s. Equipment is in the file; settings are not. |

## Tests that exist

| Test | What it does today |
|---|---|
| `app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt` (440) | Robolectric `AndroidJUnit4`, `@Config(qualifiers = "w411dp-h2000dp")` (tall window so everything is on-screen). Field `database: LiftoffDatabase?` :38 built in-memory by `launchScreen(store)` :417-439, which calls `MissionControlScreen(settingsStore, scope, equipmentDao, onBack = {})` inside `LiftoffTheme` with `rememberCoroutineScope()`. `tearDown` :49-54 cancels the store job then `database?.close()` **while the content is still mounted** — the known flake. `showsEverySectionHeadInOrder` :198 lists the 7 heads. `showsNoUnbuiltPlaceholders` :219 asserts `assertDoesNotExist("Export", ignoreCase = false)` :228 — written to prove Export was unimplemented, so it now fails on purpose-built UI. Helper `assertDoesNotExist(text, ignoreCase)` :232. `neverSaysSession` :378 scans a fixed list of on-screen texts against `\bsession\b`. |
| `MissionControlViewModelTest.kt`, `EquipmentViewModelTest.kt` | Settings validation and equipment CRUD. Must not break. |
| `app/src/test/java/com/liftoff/app/ui/LiftoffShellTest.kt` | Composes the real shell through `LiftoffApplication`'s container (:47). Mission Control nav tests at :120 (opens without bottom bar), :161 (system back returns to the tab), :175 (nav-bar icon tint). |
| `data/DatabaseExportTest.kt`, `data/DatabaseImportTest.kt`, `data/BackupRoundTripTest.kt`, `data/SchemaExportTest.kt` | The merged data layer's tests (PR #27). Must not break. `DatabaseImportTest` is the fixture to copy for anything needing two databases: `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`, `TemporaryFolder` rule, `newDatabase()` = `Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)` (:71-73, `context` from `ApplicationProvider`), a fixed `Clock` (`Clock.fixed(Instant.parse("2026-01-12T00:00:00Z"), ZoneOffset.UTC)` — a Monday, so the current week matches the seeded Missions), `ByteArrayOutputStream`/`ByteArrayInputStream` as the file. |
| — | No view-model test exists for backup work yet. |

## Docs that set rules

- `DESIGN.md` §8 "Data model", bullet **Export and import** (:547-549): "Mission Control can export the
  whole database to a single JSON file through the system file picker, and import it into an empty
  database. This is the backup story, since the phone is the only copy." Settings (daemon host/port/**token**,
  coach workspace path, pattern, session length, units, history window, run toggle, objectives/constraints)
  live in **DataStore**, not Room (:538-546) — which is why the exclusion line is true.
  The brief's section titles are loose: §8 is "Data model", §10 is "Failure handling".
- `DESIGN.md` §10 "Failure handling", row :593: "Lost or reset phone | Restore from the last JSON export."
  A reset phone's database is empty, which is exactly the case `importDatabase` accepts.
- `design/README.md`: no M3 tonal surfaces (:24); direction B is "ink-ruled lists, `paper` cards with ink
  borders" (:137); 2 dp ink borders and 4 dp corners on buttons/cards (:76-77).
- `CLAUDE.md`: gate command, `area: lowercase summary` commit messages, stage named files only, LF endings,
  §2 vocabulary — **never "session"** for a sortie.
- `ARCHITECTURE.md` rows this run must edit: M1 :80 `Done — test connection comes later; the export and
  import data layer is built`; M5 :84 `Partial: … ; the export and import data layer is built; its Mission
  Control actions come next`. The `com.liftoff.app.data` row :32 already credits the data layer. Runner
  `verify.sh` scripts here grep these cells clause-by-clause (split on `;` and `,` and dashes), so the
  clause that names export/import must carry a built word and no deferral word.

## Gaps between the plan and verify.sh (found at the tasks step)

- **The dated default file name has no plain-Kotlin home in the plan.** verify.sh's
  `theDefaultExportFileNameCarriesTodaysLocalDate` must show the name Export suggests ends in `.json` and
  carries the device's local date, not UTC, and it forbids opening a picker to show it. plan.md puts the dated
  name only inside the screen's `CreateDocument` call, which no plain test can reach. So the function that
  produces the name lives in `BackupViewModel.kt` (plain Kotlin, task 1) and `MissionControlScreen.kt` reads it
  for the launcher's default name (task 4). The data layer already takes a `java.time.Clock`
  (`exportDatabase(db, out, clock = Clock.systemUTC())`), and `DatabaseImportTest` shows the fixed-Clock
  fixture, so a clock parameter is the natural seam.
- **`anImportThroughTheBackupActionReachesRoomsFlows` is in verify.sh but in no plan.md Order step.** It is the
  brief's "make sure the import goes through Room, not around it" clause. tasks.md gives it its own task (2),
  after the view model exists and before any Compose: export from a seeded in-memory database, import through
  the backup action into a fresh one while a Room `Flow` is collected, assert the flow emits.
- **Two tests the plan asks for are not named by the rewritten verify.sh**: the busy-guard test
  (`aSecondActionWhileWorkIsRunningChangesNothing`) and the disabled-buttons test
  (`bothBackupButtonsAreDisabledWhileWorkIsRunning`). They are still written, because the plan's Testing and
  Order steps 1 and 5 ask for them, but nothing in the gate depends on them.
- **Adding required parameters to `MissionControlScreen` cannot break its previews**: the `@Preview` at :471
  calls `MissionControlContentPreview(state)`, never `MissionControlScreen(...)`, so `compileDebugKotlin` is
  safe. The shell's call site is `MissionControlScreen(settingsStore = container.settingsStore, scope =
  shellScope, equipmentDao = container.database.equipmentDao(), onBack = { navigated = true; … })` at
  `LiftoffShell.kt:111`, inside `if (nav.missionControlOpen)`, with `container` bound on the line above — so
  `database` and `missionManager` are two more arguments there, and `container.database` is the same instance
  the tabs observe.

## verify.sh (committed definition of done)

`.rho/runs/export-and-import-actions-in-mission-t9r9/verify.sh`, committed at `cb49a9c run: verify.sh
rewritten by claude review` (after `ccdaa83 run: verify`). Only the runner may change it now. It runs the CI
gate (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache`, stale
`app/build/test-results/testDebugUnitTest` cleared first, `timeout 2400`, one retry only for the known
`doneBodyweightSetStepperShowsBW` Room-pool flake or a build/daemon failure), then indexes every
`TEST-*.xml` in one awk pass (`index_reports` → `$WORK/methods`, `$WORK/classes`) and checks 44 names.

**The 10 test names it looks for that do not exist yet** (re-confirmed against `app/src/test` at the tasks
step: all absent, so the script fails today). Names are matched by method across every report, so class and
package are the implementer's call:
- "the backup work" (7, plain JUnit, no Compose, no Android): `backupWorkRunsOffTheCallingThread`,
  `exportShowsBusyThenThatItWasSaved`, `importShowsBusyThenHowMuchWasRestored`,
  `refusedImportShowsTheDataLayersMessageUnchanged`, `workThatThrowsBecomesAFailureRowNotACrash`,
  `aCancelledPickerChangesNothing`, `theDefaultExportFileNameCarriesTodaysLocalDate`.
- "the import reaches Room" (1, Room but no Compose): `anImportThroughTheBackupActionReachesRoomsFlows`.
- "Mission Control's BACKUP section" (2, Robolectric Compose):
  `backupShowsExportAndImportEnabledAndTheExclusionLine`, `successAndFailureRowsAreDrawnFromTheState`.

Plus the existing `showsEverySectionHeadInOrder`, which must gain `BACKUP`, and the "must not break" 8
classes / 24 methods (all confirmed present). The two ARCHITECTURE.md rows are checked clause by clause.

The review rewrite **dropped** two names the earlier version had — `aSecondActionWhileWorkIsRunningChangesNothing`
and `bothBackupButtonsAreDisabledWhileWorkIsRunning` — and **added** `anImportThroughTheBackupActionReachesRoomsFlows`.
The two dropped ones are still what the plan's Testing/Order steps 1 and 5 ask for, so tasks.md keeps them as
unpinned extras; nothing in the gate depends on them.

What verify.sh deliberately does NOT check (its header says why): that Export/Import open a system picker
(the brief rules a picker out of tests), the visual parts, `"wt"` truncate mode, and the exact wording of the
three result rows — the owner reviews those in the PR. Its evidence for the wiring is `LiftoffShellTest`,
which composes the real shell against `AppContainer`'s own database.

Verified earlier: the M1/M5 cells are at `ARCHITECTURE.md:80` and `:84`, both fail the clause check today,
pass with the plan's wording, and still fail a wording that defers the actions. Re-checked at the tasks step
against the real cells: `has_deferral` does **not** list "partial", so M5's leading `Partial:` is not a
deferral; M1 becomes `Done — test connection comes later; export and import are built` (its "comes later"
clause names neither export/import nor the actions, so it is not a deferral); M5 becomes
`Partial: … ; export and import are built` with its last two clauses
(`the export and import data layer is built; its Mission Control actions come next`) removed.

## Known hazards recorded from earlier runs

- Never `performClick()` a button whose handler launches SAF (`CreateDocument` / `OpenDocument`); assert
  presence + `assertIsEnabled()` and drive the behaviour through the view model.
- `Modifier.clickable(..., enabled = false, role = Role.Button)` really sets `SemanticsProperties.Enabled =
  false`, so `assertIsNotEnabled()` works — but wrapping `InkButton` and adding `semantics { disabled() }`
  does not, because its inner `clickable` is its own merged node.
- A Compose screen test that writes through Room must unmount the content before `tearDown` closes the
  database, or a live `observe()` fails with "connection pool has been closed".
- A new file in `ui.control` that needs a screen helper defined `private` in `MissionControlScreen.kt`
  must widen it to `internal` in the same commit (`SectionCard` and `LabeledTextField` are already `internal`).
- A `@Test` written as `fun x() = runBlocking { … }` becomes non-void when the block's last expression has
  a value; JUnit 4 then rejects the class. Write `runBlocking<Unit> { … }` and give every wait a timeout.
