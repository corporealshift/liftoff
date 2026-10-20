# Tasks — copy the rho client into Liftoff

Six tasks, in the plan's order. Each ends in a commit. Read `brief.md`, `plan.md` and `map.md`
before starting; the map already records the rho paths, the compile set, the daemon's behaviour and
the machine's Gradle recipe, so do not re-derive them.

- [x] Copy rho's daemon client and protocol files into `com.liftoff.app.rho`
  Copy exactly three files from the rho checkout at commit
  `ffce07d38c0eba572f1b751701293ba0cf8b0fb6` into `app/src/main/java/com/liftoff/app/rho/`:
  `net/DaemonClient.kt`, `protocol/Events.kt`, `protocol/RpcCodes.kt`. Change only the `package`
  line and the `com.rho.client.*` import lines; add a short header comment at the top of each file
  naming its rho source path and that commit. No other rho file (no `settings/`, `data/`, `notify/`,
  `ui/`, `Projection/Stats/Status`), no rewrite of any body line, no new dependency — all four
  dependencies are already in `app/build.gradle.kts` at rho's versions. Scratch copies of the
  upstream files are already in `app/build/rho-src/` (gitignored); `git -C <rho> show "<sha>:<path>"`
  also works. Do not touch `nabuSessionId` or anything in `coach/`, `domain/`, Room or the export
  format.
  Done when: `bash gradlew.sh :app:assembleDebug` passes; the three files exist under
  `app/src/main/java/com/liftoff/app/rho/` each with its header; the commit names only those three
  files. Commit `rho: copy the daemon client`.

- [x] Write the connection check in `com.liftoff.app.rho`
  One new plain-Kotlin file in the same package (the session names it; it is not part of the copied
  client and is not `DaemonClient.kt`). It owns its own handshake: its own `OkHttpClient` with the
  check's own timeouts and an overall 10-second cap, its own WebSocket, and the first `rho.hello`
  carrying `client: "liftoff"`, `client_version` and `protocol_version`; it reads the reply, closes
  the socket before returning, and reuses the copied `Rpc`, `RhoJson`, `PROTOCOL_VERSION` and
  `RpcCodes` rather than duplicating them. It reports the brief's five outcomes: connected with the
  daemon version and protocol version exactly as the daemon sent them; not-configured for a blank
  (empty or whitespace-only) host *or* token, opening no socket at all; unreachable or timed out;
  authentication rejected (the daemon answers HTTP 401 at the upgrade); and a protocol major
  mismatch naming both versions — both when the daemon refuses the hello with
  `RpcCodes.PROTOCOL_MISMATCH` (-32006, whose daemon version is only in the message text) and when
  the daemon accepts the hello but answers a `protocol_version` with a different major. A mismatch
  is never reported as unreachable. No `android.*` import, no UI, no DI framework, no connection
  held afterwards.
  Done when: `bash gradlew.sh :app:assembleDebug` still passes and the check's shape can express
  all six outcomes the next task tests. Commit `rho: add the connection check`.

- [x] Write the fake daemon and the check's tests
  `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`, modelled on rho's own
  (`app/build/rho-src/FakeDaemon.kt`): a `MockWebServer` whose `Dispatcher` answers **every**
  request with `withWebSocketUpgrade`, records every frame and the request headers, can answer an
  error frame for a method, and can refuse the upgrade with HTTP 401. Script it for a successful
  `rho.hello`, a -32006 refusal, a success reply carrying a foreign major version, and the 401.
  Plus the check's own test class in the same package: plain JUnit 4, no Robolectric, no real host,
  every wait bounded (`withTimeout`), and both ends closed in teardown because MockWebServer will
  not shut down while either end of a socket is open. Tests (exact names, one each):
  `helloSuccessReportsTheDaemonsVersionAndProtocolVersion`,
  `theHelloNamesTheClientLiftoffAndCarriesTheStoredToken`,
  `aProtocolMajorMismatchIsReportedAsAMismatchAndNamesBothVersions`,
  `aConnectionNobodyAcceptsIsReportedAsUnreachableWithinTheChecksTimeout` (start a server, take its
  port, shut it down so nothing listens),
  `emptySettingsAreReportedAsNotConfiguredAndOpenNoConnection` (blank host and blank token
  separately, and the fake records no request),
  `aRejectedTokenIsReportedAsAuthenticationRejectedNotUnreachable`.
  Done when: running just these classes writes `TEST-*.xml` with all six names present and passing,
  and the run finishes instead of hanging. Commit `rho: test the connection check against a fake daemon`.

- [x] Wire `AppContainer` to the settings it already stores
  `app/src/main/java/com/liftoff/app/AppContainer.kt` gains, still hand-built with no DI framework:
  a way to build the connection check from the current `settingsStore` settings (daemon host, port,
  token — host and token trimmed, rho's URL form `http://<host>:<port>/`), and a factory for an
  **unconnected** copied `DaemonClient` built from the same settings with `clientName = "liftoff"`
  for the later generation worker to connect and drive. Nothing is connected or held by the
  container. Add the test to the existing Robolectric `app/src/test/java/com/liftoff/app/AppContainerTest.kt`:
  `theContainerBuildsTheConnectionCheckFromTheStoredSettings` — persist a host, port and token
  pointing at the fake daemon, then the container's check reports connected with the versions the
  fake gave. The plan also lists a factory test (the container's `DaemonClient` hello carries
  `client_name` `liftoff` and the stored token as the bearer header); it is not named by the gate,
  and `verify.sh` requires **every** test in `AppContainerTest` to pass, so include it only if it is
  reliable, and close both ends.
  Done when: `bash gradlew.sh :app:testDebugUnitTest --tests "*AppContainerTest"` writes a report in
  which `theContainerBuildsTheConnectionCheckFromTheStoredSettings` passed and no test in
  `AppContainerTest` failed or was skipped. Commit `rho: build the connection check from the settings`.

- [x] Record the nabu → rho rename in the two documents
  `DESIGN.md`: one dated amendment bullet in the newest-first position directly after the
  "## Amendments after approval" intro (the newest today is 2026-10-06), naming both `nabu` and
  `rho`, the `rho.` method prefix and the new checkout path; leave the approved body text as
  written. `ARCHITECTURE.md`: the package row becomes `com.liftoff.app.rho` with its wording
  updated for the rename and its "Exists yet?" cell no longer the `—` marker — it names the three
  copied files and the 40-hex source commit; and every other `nabu` mention in that file (topology
  line, data-flow line, invariants 3, 4 and 7, the M3 milestone row) is renamed to rho. No code
  change, and `nabuSessionId` / `@SerialName("nabu_session_id")` stay as they are.
  Done when: `grep -n 'com\.liftoff\.app\.nabu\|nabu' ARCHITECTURE.md` finds nothing, a line matching
  `^\|[^|]*com\.liftoff\.app\.rho` exists whose status cell is not `—` and carries
  `ffce07d38c0eba572f1b751701293ba0cf8b0fb6`, and DESIGN.md has a new amendment bullet naming both
  names. Commit `docs: record the nabu to rho rename`.

- [x] Run the whole gate cold and fix anything it turns up
  Run `bash .rho/runs/copy-the-rho-client-into-liftoff-774c/verify.sh` detached with the map's
  recipe (stop daemons, `mkdir -p app/build`, clear the results dir, `timeout 1800 … --no-build-cache`,
  wait on an `EXIT=` sentinel) and read the counts out of the JUnit XML, not stdout. Check the log
  for all seven named tests present and passing, the guard classes all green, and
  `:app:testDebugUnitTest` not `FROM-CACHE`/`UP-TO-DATE`. A Robolectric + Room hang that writes no
  `TEST-*.xml` is a known problem on this machine: re-run rather than "fix" it.
  Done when: the gate reports `BRIEF DONE.` / `EXIT=0`. Commit a fix only if the gate turned one up
  (`area: lowercase summary`, named files staged only); if nothing needed fixing there is no commit
  for this task.

## Blockers from the final review

- [x] A malformed host (or token) makes the connection check throw instead of returning an outcome
  In app/src/main/java/com/liftoff/app/rho/DaemonConnectionCheck.kt, check() calls Request.Builder().url(...) and .header("Authorization", ...) outside any try block. OkHttp throws IllegalArgumentException when the URL won't parse, and when a header value holds characters it doesn't allow. Mission Control stores the host as typed, with no validation (MissionControlViewModel.setHost). A user who types a URL instead of a bare host, such as `http://100.64.0.5`, gets the URL `ws://http://100.64.0.5:8737/`. So do a host with a space in it, or a host with its own port (`100.64.0.5:8737`). Each of these makes check() throw instead of returning a result. The brief says the result is always one of its five outcomes, and Test connection and the generation worker will both call this. An uncaught exception there would crash the app or fail the worker. The check must turn a request it can't build into a returned outcome. Unreachable is the closest of the brief's five, and it should carry a reason naming the bad host. A case in DaemonConnectionCheckTest should show it: a host such as `http://example` or `bad host`, plus a token holding a control character, each return an outcome and open no connection.
