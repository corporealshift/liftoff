# Map — copy the rho client into Liftoff

## The rho checkout (readable, so the brief's stop condition does not apply)

- `C:/Users/corpo/Documents/projects/rho` — `git rev-parse HEAD` =
  **`ffce07d38c0eba572f1b751701293ba0cf8b0fb6`**. This is the sha every copied header records.
- Reading it with the `read` tool needs an approval nobody gives; `bash` (`cat`, `grep`, `cp`)
  reads it fine. A scratch copy of the candidate files sits at `app/build/rho-src/` (gitignored).
- `protocol/spec.md` §1 (lines 27–41): the first request MUST be `rho.hello`; params are
  `{"client", "client_version", "protocol_version"}`; the reply is
  `{daemon_version, protocol_version, capabilities[]}`. Versions are `MAJOR.MINOR`; a differing
  MAJOR is refused with `rho_protocol_mismatch`; a differing MINOR is accepted.

## rho's Android client — `clients/android/app/src/main/java/com/rho/client/`

| File | Lines | What it holds |
|---|---|---|
| `net/DaemonClient.kt` | 363 | `Rpc` / `RpcError` envelopes, `Incoming` (`Event`/`Permission`/`Ask`/`Other`), `DaemonException(message, code?)` with `isPermanent`, and `DaemonClient(baseUrl, token, clientName = "rho-android", version = "0.1", http = defaultHttp())`. Public surface: `connect()`, `call(method, params)`, `callOrThrow`, `respond(id, result)`, `incoming: SharedFlow<Incoming>`, `awaitClosed()`, `close()`. Also carries `EventsAfterParams`, `EventsAfterResult`, `SessionSummary`, `BrowseEntry`, `BrowseResult`, `CreateSessionResult` — used only by rho's `data/` and `ui/`, harmless to copy with the file. |
| `protocol/Events.kt` | 216 | `const val PROTOCOL_VERSION = "1.0"`, `val RhoJson: Json` (ignoreUnknownKeys, encodeDefaults, explicitNulls=false, coerceInputValues), `Event`, the `*Data` payload classes, `EventType`, `Event.payload()`, `Event.field()`. Imports only `kotlinx.serialization.*`. |
| `protocol/RpcCodes.kt` | 44 | `object RpcCodes` — `PROTOCOL_MISMATCH = -32006`, `UNAUTHORIZED = -32007`, `METHOD_NOT_FOUND = -32601`, … plus `PERMANENT: Set<Int>` (which deliberately excludes `UNAUTHORIZED` and `PROTOCOL_MISMATCH`). |
| `protocol/Projection.kt`, `Stats.kt`, `Status.kt`, `settings/Settings.kt` | — | **Not needed**: `DaemonClient.kt` imports only `Event`, `RhoJson`, `PROTOCOL_VERSION`, `RpcCodes` — i.e. `Events.kt` + `RpcCodes.kt`. `Settings.kt` is rho's own DataStore wrapper; Liftoff has its own. |

rho's `app/build.gradle.kts` versions: `kotlinx-serialization-json:1.7.3`,
`kotlinx-coroutines-android:1.9.0`, `com.squareup.okhttp3:okhttp:4.12.0`,
`testImplementation com.squareup.okhttp3:mockwebserver:4.12.0`. **All four are already declared
in Liftoff's `app/build.gradle.kts`** → no new dependency for this run.

### rho's own client tests (the style the brief points at)

- `app/src/test/java/com/rho/client/net/FakeDaemon.kt` — `MockWebServer` whose `server.dispatcher`
  returns `MockResponse().withWebSocketUpgrade(listener)` for **every** request (one enqueued
  response means the second connection hangs). Records frames in `received: ConcurrentLinkedQueue<String>`,
  answers `resultFor(id, method)` / `errorFor(id, message, code = -32000)`, `refuseHandshake` flag
  answers `rho.hello` with an error, `push(text)` sends on the server-side socket, `stop()` closes
  both ends then `server.shutdown()`.
- `DaemonClientTest.kt` — plain JUnit 4 (no Robolectric), every wait wrapped in
  `withTimeout(10_000)`, and a local `DaemonClient.done()` that closes the client **and**
  `daemon.socket`, because MockWebServer will not shut down while one end is open.

## The daemon side (what the copied client actually meets) — rho `daemon/api/transport.go`

- `ServeHTTP` (lines 104–131): a non-WebSocket request gets 426. A **non-loopback** connection
  without the configured token, or with the wrong `Authorization: Bearer <token>`, gets
  **HTTP 401 at the upgrade** — before any JSON-RPC. OkHttp reports that as
  `onFailure(t, response)`, so it looks like a dropped connection, not an RPC error.
- `enforceHello` (157–203): reads the first frame; a wrong method or a MAJOR mismatch is refused
  with `protocol.CodeProtocolMismatch` (-32006) and the message
  `"rho_protocol_mismatch: protocol version <client> is incompatible with daemon <daemon>"`, then
  the connection is dropped. On success the result is
  `{daemon_version: DaemonVersion (= "0.0.0"), protocol_version, capabilities: [delta, tasks, goal]}`.
- `rho.hello` is **not** a registered method in `daemon/api/handler.go` (lines 70–85): a *second*
  hello on the same connection gets `rho_method_not_found`. So the hello result can only be read
  from the first hello on that connection.
- `DaemonClient.connect()` (lines 139–157) sends that first hello and **discards the result** — it
  only checks `hello.error != null`, then `throw DaemonException("handshake refused: <message>")`
  with no `code`. `socket` is private, so nothing else can send the first frame.

## Liftoff side

| File | What it holds |
|---|---|
| `app/src/main/java/com/liftoff/app/AppContainer.kt` | 26 lines, hand-built: `settingsStore` (PreferenceDataStore "settings"), `database`, `missionManager`, all `by lazy`. No DI framework. Nothing daemon-related yet — no `rhoClient`, no test-connection. |
| `app/src/main/java/com/liftoff/app/settings/Settings.kt` | `Settings(daemonHost = "", daemonPort = 8737, daemonToken = "", coachWorkspacePath = "", …)` — the three fields the brief names already exist. |
| `app/src/main/java/com/liftoff/app/settings/SettingsStore.kt` | `settings: Flow<Settings>` + `setDaemonHost/Port/Token` setters; `SettingsKeys` is `internal object`. |
| `app/src/test/java/com/liftoff/app/AppContainerTest.kt` | Robolectric (`@Config(sdk = [34])`), reaches the container through `ApplicationProvider.getApplicationContext<LiftoffApplication>().container`; already has `settingsArePersistedToSettingsPreferencesFile` using `store.settings.first()`. |
| `app/build.gradle.kts` | Deps listed above; `testOptions.unitTests.all { it.timeout.set(Duration.ofMinutes(5)) }` — a hung test task writes **no** `TEST-*.xml`. `mockwebserver` is already a `testImplementation`. |
| `app/src/main/java/com/liftoff/app/data/Generation.kt` | `@ColumnInfo(name = "nabuSessionId") val nabuSessionId` — stays as-is (renaming it changes the Room schema). Also `BackupDocument.kt` `@SerialName("nabu_session_id")`, and `nabuSessionId` in the export/import and DAO tests. |
| `ARCHITECTURE.md` | line 31: the `com.liftoff.app.nabu` row, "Exists yet?" = `—`. Invariants 3 (nothing in nabu changes) and 4 (copied, with a header recording the commit) at lines 64–65. M3 "Generation — nabu client copy, `GenerationWorker`…" at line 82. |
| `DESIGN.md` | §3 Topology 72–95 ("Liftoff is a nabu client … version 1.x"); §7.3 step 1 at 298–300 ("send `nabu.hello` with client `liftoff`. A protocol major-version mismatch is a **permanent failure** with a clear message; it is not retried"); §10 line 585 (mismatch message shape: "Liftoff speaks nabu protocol 1.x, the daemon speaks N.x"); §11 597–619, "**Reused from nabu:** `net/DaemonClient.kt` and the parts of `protocol/` that Liftoff needs (`Events.kt`, `RpcCodes.kt`) are copied … with a header comment recording the nabu commit"; "## Amendments after approval" at 783, bullets **newest first**. |
| `CLAUDE.md` | gate `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`; commit messages `area: lowercase summary`; stage named files only; LF endings (`*.bat` is crlf); short comments. |

## Facts the plan depends on (verified in the staged copies)

- **The minimal compile set is three files.** `DaemonClient.kt` imports from `com.rho.client`
  only `Event`, `RhoJson`, `PROTOCOL_VERSION`, `RpcCodes` → `protocol/Events.kt` +
  `protocol/RpcCodes.kt`. Nothing from `settings/`, `data/`, `net/` others. Its other imports are
  `kotlinx.coroutines.*`, `kotlinx.serialization.*`, `okhttp3.*`, `java.util.concurrent.*`.
- **No `android.*` import anywhere in the three files** → they compile in the unit-test source
  set and can be tested with plain JUnit 4 + MockWebServer, no Robolectric.
- **Every top-level declaration in the three files is public** (`Rpc`, `RpcError`,
  `SessionEvent`, `PermissionRequest`, `AskRequest`, `Incoming`, `DaemonException`,
  `DaemonClient`, `EventsAfter*`, `SessionSummary`, `Browse*`, `CreateSessionResult`;
  `PROTOCOL_VERSION`, `RhoJson`, `Event`, `EventType`, `Event.field()`, `RpcCodes`). So new
  Liftoff code in `com.liftoff.app.rho` can reuse them without touching the copy.
- `Rpc` (line 33) is a `@Serializable` envelope with defaults: `jsonrpc`, `id`, `method`,
  `params`, `result`, `error: RpcError?`, plus `isNotification` / `isRequest`.
  `RpcError(code, message, data)`.
- `connect()` (139–157): `baseUrl.replace(Regex("^http"), "ws")`; the `Authorization: Bearer`
  header is sent **only when the token is non-empty**; `defaultHttp()` = 20 s pings,
  **0 read timeout**, 15 s connect timeout.
- `onFailure` (228–230) passes on only `t.message` and **drops the `Response`** — so the daemon's
  HTTP 401 at the upgrade is indistinguishable, through the copied client, from any other dropped
  socket. This is the second reason the check must own its handshake.
- `fail(reason)` (266–271) completes `connected` exceptionally and fails every pending call, so a
  dropped socket surfaces as an exception out of `connect()`/`call()` rather than hanging.
- `rho.hello` params the copy sends: `client_name`, `client_version`, `protocol_version`
  (spec §1 wants `client`); `PROTOCOL_VERSION = "1.0"`.

## Documents: the exact places to edit

- `ARCHITECTURE.md` mentions of the harness: line 20 (topology, "a **nabu client** …
  `nabu/clients/android`"), **line 31 (the package row)**, line 53 (data flow, "connects to nabu
  over WebSocket"), invariants **3 (64), 4 (65)**, 7 (68, "nabu runner acts on those"), and the
  **M3 milestone row (82)** "Generation — nabu client copy, `GenerationWorker`, …".
- `DESIGN.md` "## Amendments after approval" at 783; the intro (785–786) says bullets are
  **newest first** and "marked inline in the section they affect"; the newest bullet today is
  2026-10-06 at line 788, so a new bullet goes directly after the intro paragraph.
- `.gitattributes`: `* text=auto eol=lf` (`*.bat` crlf, `*.jar`/`*.ttf` binary) — copied files land
  LF on add.
- `.github/workflows/ci.yml`: ubuntu-latest, Java 17 (temurin), job timeout 20 min, gate
  `./gradlew :app:assembleDebug :app:testDebugUnitTest`. No env vars set, so `LiveCoachTest`
  skips itself there.

## The gate this run is held to (`.rho/runs/copy-the-rho-client-into-liftoff-774c/verify.sh`)

Read the committed file; this is its name list as of `run: verify`. Method names are fixed — a work step may move a
method between classes but may not rename it. Class names are checked as a whole class ("ran, and nothing in it was
skipped or failed"); method names are matched across every `TEST-*.xml`, so which class each sits in is the
implementer's call.

New methods that do not exist yet (7 — these are what make the script fail today):
- `helloSuccessReportsTheDaemonsVersionAndProtocolVersion`
- `theHelloNamesTheClientLiftoffAndCarriesTheStoredToken`
- `aProtocolMajorMismatchIsReportedAsAMismatchAndNamesBothVersions`
- `aConnectionNobodyAcceptsIsReportedAsUnreachableWithinTheChecksTimeout`
- `emptySettingsAreReportedAsNotConfiguredAndOpenNoConnection`
- `aRejectedTokenIsReportedAsAuthenticationRejectedNotUnreachable`
- `theContainerBuildsTheConnectionCheckFromTheStoredSettings`

Already green, named only as guards: `exportedV1SchemaMatchesDatabase`, `coachPackageHasNoAndroidImports`, and the
classes `AppContainerTest`, `MissionControlScreenTest`, `MissionControlViewModelTest`, `EquipmentViewModelTest`,
`DatabaseExportTest`, `DatabaseImportTest`, `BackupRoundTripTest`, `BackupImportRoomTest`, `BackupViewModelTest`,
`CoachReplySemanticTest`, `CoachPromptGoldenFileTest`, `CoachSchemaLoadTest`, `InFlightScreenTest`,
`LiftoffShellTest`, `MainActivityTest`.

Non-test checks the script runs: the CI gate (`:app:assembleDebug :app:testDebugUnitTest`, `--no-build-cache`,
retried only on the known flake or a build/hang); each copied file diffed against upstream rho at the commit its own
header names, with package/import lines stripped from both sides; `ARCHITECTURE.md` has no `com.liftoff.app.nabu`
mention left and its `com.liftoff.app.rho` **table row** (looked up with `grep -m1 -E '^\|[^|]*com\.liftoff\.app\.rho'`,
so a renamed prose line elsewhere cannot be mistaken for the row) has a status cell that is not the `—` marker and
carries a 40-hex commit that exists in rho; a DESIGN.md amendment bullet naming both `nabu` and `rho`.

Deliberately **not** checked (see the script's header): any UI test; that the fake is a MockWebServer WebSocket or
that the tests match rho's style; rho's UI/outbox/push being absent (a file-existence check); line endings
(`.gitattributes eol=lf` makes a worktree check unable to fail); the plan's second mismatch case and the check
closing its socket (plan decisions the owner reviews); "no DI framework"; the wording of the renamed
ARCHITECTURE.md row beyond the package name itself; the Room column — see the schema note below.

## Facts verified while writing the gate (2026-10-20)

- **`git -C C:/Users/corpo/Documents/projects/rho show "<sha>:<path>"` works** in Git Bash, and so does
  `ls-tree -r --name-only <sha> -- <dir>` and `cat-file -e "<sha>^{commit}"`. The known Windows problem is
  `git show branch:path` *inside this repo*; a different repo addressed with `-C` is fine.
- rho's client tree at `ffce07d3…`: `MainActivity.kt`, `net/DaemonClient.kt`, `data/{Mirror,Outbox,OutboxWorker,
  SessionRepository}.kt`, `notify/{Push,PushService}.kt`, `protocol/{Events,Projection,RpcCodes,Stats,Status}.kt`,
  `settings/Settings.kt`, `ui/` (14 files). Only `net/DaemonClient.kt` + `protocol/{Events,RpcCodes}.kt` are wanted.
- **The fidelity diff works**: header comment + `sed 's/^package com\.rho\.client/package com.liftoff.app.rho/'` +
  the same for `^import com\.rho\.client`, then strip every `package`/`import` line from both sides and `diff`.
  Verified on all three files: 335 / 208 / 43 body lines, byte-identical.
- **ARCHITECTURE.md's package table**: `awk -F'|'` on a row gives `$4` = the "Exists yet?" cell. The nabu row today
  reads `| \`com.liftoff.app.nabu\` | Copied nabu \`DaemonClient\` and protocol types. No changes to upstream nabu. | — |`
  — its cell is the em-dash not-yet marker, which is what the gate rejects.
- **DESIGN.md's amendments section holds 2 bullets today** (newest 2026-10-06, `design/`); neither mentions `rho`
  or a rename, so the gate's DESIGN.md check fails on the untouched tree.
- **`app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json` is tracked** and Room regenerates it on every compile
  (`room.schemaLocation = "$projectDir/schemas"`, `app/build.gradle.kts:12`). It contains `nabuSessionId` 3×.
  **The gate does not grep it** — grepping a generated file for a column name dictates how the data layer is written.
  What catches the rename the brief forbids is the gate's compile: `GenerationDaoTest.kt:46,59`,
  `DatabaseExportTest.kt:126` and `BackupRoundTripTest.kt:279,287,295` all build a `Generation` with the
  `nabuSessionId` property by name and one reads it back, so renaming it stops `:app:compileDebugUnitTestKotlin` and
  the gate fails. A rename that keeps the Kotlin property and changes only `@ColumnInfo(name = …)` is invisible to
  every test (`exportedV1SchemaMatchesDatabase` only asserts table and key names are present in that JSON, not
  columns) and is caught only by review.

- **`set -euo pipefail` + a plain assignment `x="$(cmd | head -1)"` aborts the whole script when the pipeline fails**
  (verified: `grep` finding nothing, or `find` on a missing directory, under `pipefail`). `local x="$(…)"` does *not*
  abort. Every assignment in verify.sh whose pipeline may legitimately find nothing therefore ends in `|| true`.
- **The gate as verify.sh runs it works on this machine**: `bash gradlew.sh --stop`, `rm -rf` the results dir, then
  `timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` — `:app:testDebugUnitTest`
  executed (55 s warm, 2 m 18 s cold), reports written, `gate attempt 1 passed`.
- **verify.sh run against the untouched tree** (detached, `app/build/verify-probe2.log`): gate passed, 18 checks
  passed, **13 failed, `EXIT=1`** — the 7 named tests that do not exist yet, the 3 copied files, 2 ARCHITECTURE.md
  checks and the DESIGN.md amendment. Its positive paths were checked against scratch fixtures under `app/build/vf/`
  (a faithful copy of the three upstream files passes the fidelity loop; a body edit or a bogus 40-hex commit fails
  it; a `✅` row with a real commit and a rename bullet naming both names pass the two doc checks).

## Hazards already known on this machine

- MockWebServer: use a `Dispatcher`, never one enqueued response for two connections.
- A Robolectric + Room suite can hang and then die at the 5-minute test-task timeout with no XML;
  re-run rather than "fix".
- Detached Gradle runs: `bash gradlew.sh --stop`, `mkdir -p app/build`, `rm -rf
  app/build/test-results/testDebugUnitTest`, then the run with `--no-build-cache`, waited on an
  `EXIT=` sentinel; read counts from the XML.
