# Map of the code

Run: test-connection-in-mission-control-6aph. Branch off `842f3b7` ("Copy the rho client into Liftoff (#30)").

## Docs that set rules

- `DESIGN.md` §9 (line 553) — Mission Control lists "Test connection", which runs `rho.hello` and shows the daemon and protocol versions. §10 (line 580) — the failure table: unreachable → "Mission Control offline"; major mismatch → "Liftoff speaks nabu protocol 1.x, the daemon speaks N.x", not retried. The brief renames nabu → rho, so the strings say **rho**.
- `design/README.md` — direction B "Space Age". Tokens (Ink/Red/Muted/Rule…), type roles (`LiftoffType.textButton()`, `.exerciseName()`, `.note()`, `.index()`), 2 dp ink borders, 44 dp touch targets, "ink-ruled lists" for the screens that are not mocked up (Mission Control among them). No stock M3 visuals.
- `CLAUDE.md` — commit messages `area: lowercase summary`; LF endings.
- `ARCHITECTURE.md` line 80 — the M1 row: "Done — test connection comes later; export and import are built". This is the row the brief wants changed. Line 31 is the `com.liftoff.app.rho` area row; line 37 the `com.liftoff.app.ui` row.

## The client copy (do not change — the brief says so)

- `app/src/main/java/com/liftoff/app/rho/DaemonConnectionCheck.kt` (223 lines) — the whole handshake, already built.
  - `sealed interface ConnectionOutcome`: `Connected(daemonVersion, protocolVersion)`, `NotConfigured` (blank host or token, opens no socket), `Unreachable(reason: String)`, `AuthenticationRejected`, `ProtocolMismatch(expectedVersion, daemonVersion, message)`.
  - `class DaemonConnectionCheck(daemonHost, port, daemonToken, clientVersion = "0.1", http = checkHttp())`; `suspend fun check(): ConnectionOutcome`.
  - `TIMEOUT_MS = 10_000` already exists, with a 5 s connect timeout; `withTimeout` + a `finally` that always closes the socket (`cancel()` on timeout, `close(1000, null)` otherwise). Timeout arrives as `Unreachable("no answer within 10 seconds")`.
  - A host that can't build a request (written as a URL, holding a space, carrying its own port) and a token with characters a header can't carry both arrive as `Unreachable` with a reason naming that setting — never as an exception.
  - `PROTOCOL_VERSION = "1.0"` (`rho/protocol/Events.kt` line 15), so the client's major is **1**. Only the major must match.
- `rho/net/DaemonClient.kt`, `rho/protocol/{Events,RpcCodes}.kt` — the copied client; `RpcCodes.PROTOCOL_MISMATCH` / `UNAUTHORIZED`.
- `app/src/main/java/com/liftoff/app/AppContainer.kt` line 45 — `suspend fun daemonConnectionCheck()` builds a fresh check from the stored host/port/token (host and token trimmed there). Nothing is left connected.

## Mission Control today

- `ui/control/MissionControlScreen.kt` (597 lines)
  - line 79 `MissionControlScreen(settingsStore, scope, equipmentDao, database, missionManager, onBack, backupViewModel = null)` — the nullable view-model parameter is the test seam; the app gets the real data layer on the screen's own `scope`.
  - line 177 `SectionCard(title = "CONNECTION")` inside the private `MissionControlContent(vm, state)`: three `LabeledTextField`s (Daemon host, Port with `error = state.portError`, Token with a show/hide icon). No button, no result row.
  - line 263 `internal fun SectionCard(title, content)` — paper card, 2 dp ink border. `LabeledTextField` / `LabeledMultiLineTextField` are `internal`, the rest private.
  - line 526 `MissionControlScreenPreview` / line 553 preview copy of the CONNECTION card — previews duplicate the section, so a new row belongs in both or the preview drifts.
- `ui/control/MissionControlViewModel.kt` (211 lines) — plain Kotlin (no Android imports), `MissionControlState` data class with `host`, `portText`, `token`, `portError`. **Every setter writes the store immediately** (`setHost`, `setToken`, `setPortText` → `scope.launch { store.set… }`), so there are no unsaved edits; an *invalid* port is deliberately not stored, and the write is async.
- `ui/control/BackupViewModel.kt` — the shape to copy: `sealed interface …State` with `Idle / Busy / Succeeded / Failed` and a `message` the card draws; a busy guard `if (_state.value is Busy) return`; work on `scope.launch(dispatcher)` with an injected `CoroutineDispatcher` (default `Dispatchers.IO`); the messages are file-private constants.
- `ui/control/BackupSection.kt` — the shape to copy for the UI: `BackupStatusRow` (message + a 1 dp ink rule under it = the "ink-ruled row"), `statusColor` (Muted while busy, Red on failure), and `BackupTextButton` — `ui.theme`'s `UnderlinedTextButton` with the one thing it lacks: an `enabled` flag (Muted when disabled, `clickable(role = Role.Button, enabled = …)`). It is **file-private**, so it cannot be reused from another file.
- `ui/theme/Buttons.kt` — `PrimaryButton`, `InkButton`, `UnderlinedTextButton`; **none takes `enabled`**. Labels are uppercased by the style. `ui/theme/InkRuledListRow.kt` is the ruled-row composable for lists.
- `ui/LiftoffShell.kt` line 111 — the only app caller of `MissionControlScreen`; it already holds `container`, so it can pass the container's helper down.

## Tests that exist

- `app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt` — Robolectric (`AndroidJUnit4`, `@Config(qualifiers = "w411dp-h2800dp")` so every card is on-screen), `createComposeRule`.
  - `showsNoUnbuiltPlaceholders` (line 260) asserts `assertDoesNotExist("Test connection", ignoreCase = true)` — **this is the test the brief allows to change**, and it will fail once the button exists because the theme renders labels uppercased ("TEST CONNECTION").
  - `launchScreen(store, backupViewModel = null)` (line 557) is the composition helper; `mounted`/`contentMounted` unmount the screen in `@After` **before** `database.close()`.
  - The BACKUP tests own their view model's scope (`Dispatchers.Unconfined`) and a single-thread worker dispatcher, and `backupVm(...)` builds one with fake work.
- `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt` — MockWebServer WebSocket fake: `answerHelloWithProtocol(...)`, `refuseProtocolMismatch(...)`, `refuseUpgrade` (401 at the upgrade), `host()`, `port()`, `requestCount()`, `framesFor(...)`, `stop()` (closes both ends or `server.shutdown()` hangs).
- `app/src/test/java/com/liftoff/app/rho/DaemonConnectionCheckTest.kt` — 7 tests, plain JUnit 4, already covering Connected / mismatch / NotConfigured / bad host / 401 against `FakeDaemon`.
- `app/src/test/java/com/liftoff/app/AppContainerTest.kt` line 67 — `theContainerBuildsTheConnectionCheckFromTheStoredSettings` already drives `container.daemonConnectionCheck()` against `FakeDaemon` with padded stored host/token.
- `MissionControlViewModelTest.kt`, `EquipmentViewModelTest.kt`, `BackupViewModelTest.kt`, `BackupImportRoomTest.kt`, `settings/SettingsStoreTest.kt` — must keep passing.
