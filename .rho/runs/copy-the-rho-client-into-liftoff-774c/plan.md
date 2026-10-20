# Plan

## Approach

Three files move across, unchanged but for their package and import lines, each with a header
naming its rho source path and commit `ffce07d38c0eba572f1b751701293ba0cf8b0fb6`:
`net/DaemonClient.kt`, `protocol/Events.kt` and `protocol/RpcCodes.kt`. Those three are the whole
compile set — `DaemonClient.kt` imports nothing else from rho (map) — and none of them imports
`android.*`, so they compile in the unit-test source set and can be tested with plain JUnit 4
against a MockWebServer WebSocket, the way rho tests its own client.

The copied client cannot answer the brief's question on its own: `connect()` sends the hello and
throws the reply away, a second `rho.hello` on the same connection is refused because the daemon
never registers it, and `onFailure` drops the HTTP response so a 401 looks like any other dropped
socket (map). So the brief's five outcomes come from one new plain-Kotlin connection check in
`com.liftoff.app.rho`, which opens its own WebSocket, sends the first `rho.hello` with client
`liftoff`, and reads the reply — reusing the copied `Rpc`, `RhoJson`, `PROTOCOL_VERSION` and
`RpcCodes` rather than duplicating them (Decision 1). `AppContainer` builds that check from the
settings it already stores and exposes it; hand-built, no DI framework, no UI, no new dependency
(all four the copied code needs are already declared — map). Nothing in rho changes, and nothing
in `coach/`, `domain/`, Room or the export format moves.

## Files

New under `app/src/main/java/com/liftoff/app/rho/`:

- `net/DaemonClient.kt` — copy of rho's `clients/android/.../net/DaemonClient.kt` (363 lines).
  Package line and the four `com.rho.client.protocol` imports become `com.liftoff.app.rho.*`;
  header comment with source path + commit. Nothing else changes.
- `protocol/Events.kt` — copy (216 lines). Package line only; header.
- `protocol/RpcCodes.kt` — copy (44 lines). Package line only; header.
- one new file holding the connection check and its outcomes (the work session names it; it is
  not the copied client). Plain Kotlin, no Android imports, own `OkHttpClient` with the check's
  own timeouts, its own first `rho.hello`, a 10-second cap, and a close before returning.

Changed:

- `app/src/main/java/com/liftoff/app/AppContainer.kt` — a hand-built way to make the check from
  the current `settingsStore` settings (host, port, token). No DI framework.
- `app/src/test/java/com/liftoff/app/AppContainerTest.kt` — one test that the container's check
  uses the persisted settings.
- `DESIGN.md` — one amendment bullet, newest-first position under "Amendments after approval"
  (map), recording the nabu → rho rename, the `rho.` method prefix, and the new checkout path.
- `ARCHITECTURE.md` — the line 31 row becomes `com.liftoff.app.rho`, its wording reworded for the
  rename, and "Exists yet?" becomes ✅ naming the three copied files and the source commit; the
  topology line, the data-flow line, invariants 3, 4 and 7 and the M3 milestone row are renamed to
  rho (Decision 6).

New under `app/src/test/java/com/liftoff/app/rho/`:

- `FakeDaemon.kt` — a MockWebServer WebSocket fake modelled on rho's own (map: its `Dispatcher`
  answers every request with an upgrade, records frames, can refuse the handshake), scripted for
  `rho.hello`, for a `-32006` mismatch, and for a 401 at the upgrade.
- the check's test class.

Unchanged on purpose: `app/build.gradle.kts` (all four dependencies are already declared at rho's
versions — map), `data/Generation.kt`'s `nabuSessionId` column, `BackupDocument.kt`'s
`@SerialName("nabu_session_id")`, and everything in `coach/` and `domain/`.

## Order

1. Copy the three files into `com.liftoff.app.rho` with their headers, package and import lines
   renamed. `bash gradlew.sh :app:assembleDebug` passes. Commit `rho: copy the daemon client`.
2. Write the connection check in the same package. `:app:assembleDebug` still passes. Commit.
3. Write `FakeDaemon.kt` and the check's tests; run just that test class. Commit.
4. Wire `AppContainer` to build the check from the stored settings and add its one test; run
   `AppContainerTest`. Commit.
5. The `DESIGN.md` amendment and the `ARCHITECTURE.md` rename. Commit
   `docs: record the nabu → rho rename`.
6. Run the whole gate detached, as the map's hazards section describes, and read the counts from
   the XML. Commit only if it needed a fix.

Steps 1–4 each leave the build green on their own; 5 changes no code.

## Testing

All of the check's tests are plain JUnit 4 against `FakeDaemon` — no Robolectric, no real host,
every wait inside a timeout, nothing reading the wall clock or the local machine (CI is
ubuntu-latest, UTC).

- **Success.** The fake answers `rho.hello` with a daemon version and a protocol version; the
  outcome is connected and carries both, exactly as the daemon answered them.
- **What the check sends.** The frame the fake received is `rho.hello` with client `liftoff` and
  the copied client's protocol version.
- **Major-version mismatch.** The fake refuses the hello with `RpcCodes.PROTOCOL_MISMATCH`
  (-32006); the outcome is the mismatch one, naming both versions, and is not reported as
  unreachable.
- **Refused connection.** Nothing answers the upgrade — the fake closes the socket, or the port has
  nothing listening on it; the outcome is unreachable, and it arrives inside the check's own cap
  rather than hanging.
- **Empty settings.** A blank host, and separately a blank token, each give the not-configured
  outcome — and the fake records no request at all, proving nothing was opened.
- **Authentication rejected.** The fake answers the upgrade with HTTP 401, the way the real daemon
  does for a wrong token on a non-loopback connection; the outcome is authentication rejected, not
  unreachable. This is not one of the brief's four cases, but the outcome exists and the fake can
  produce it, so it is tested.
- **The check closes its socket.** The fake's shutdown completes instead of hanging.
- **`AppContainer` wiring.** In the existing Robolectric `AppContainerTest`: persist a host, port
  and token pointing at the fake daemon, then the container's check reports connected with the
  versions the fake gave.

## Risks

- **MockWebServer will not shut down while one end of a WebSocket is open**, hanging the test task
  to the 5-minute limit and writing no `TEST-*.xml`. The tests close both ends in teardown, as
  rho's own client tests do.
- **MockWebServer's default queue serves one enqueued response per connection**, so a second
  connection hangs. `FakeDaemon` answers through a `Dispatcher`, like rho's.
- **A Robolectric + Room suite can hang here and die at that same timeout with no XML** (known on
  this machine). Re-run it; do not "fix" it.
- **The full gate can outlast a shell command's limit.** Run it detached by the map's recipe and
  read the counts from the XML, not from stdout.
- **The copied client's own timeouts are wrong for a "is the coach there?" question** — 15 s
  connect, no read timeout, 20 s pings. The check builds its own `OkHttpClient` instead of reusing
  `defaultHttp()`; the copy is left as upstream wrote it.
- **The copy keeps sending `client_name` where spec §1 says `client`.** Left alone (Decision 3), so
  `FakeDaemon` must not insist on either name.
- **The Room schema and the export format must not move.** `nabuSessionId` and
  `@SerialName("nabu_session_id")` stay as they are; no migration, no touched export/import test.
- **`coach/` and `domain/` must stay free of Android imports** (invariant 2). The copied files
  import no `android.*` and go in `rho`, so nothing moves; the check is plain Kotlin too.
- **The wiring test is the only one mixing Robolectric with a live socket.** It needs its own
  timeout, and `AppContainer` must not hold the connection afterwards. If it proves fragile, the
  check's own tests already cover the outcomes and this test can drop back to asserting the
  container reports not-configured for blank settings.
- **Endings.** `.gitattributes` sets `* text=auto eol=lf`, so a copied file lands LF; check no CR
  survives in the three copies (CLAUDE.md).
- **If the rho checkout stops being readable mid-run**, stop and report it rather than writing a
  client from scratch (the brief's own stop condition).

## Decisions

**The check reads the `rho.hello` reply itself, rather than calling the copied
`DaemonClient.connect()`.** The brief wants "connected, with the daemon version and protocol
version", but `connect()` sends the hello and throws the reply away — it only tests
`hello.error != null` — its socket is private, so nothing else on that connection can ask, a second
`rho.hello` is refused because the daemon never registers that method, and `onFailure` drops the
HTTP response, so the daemon's 401 for a bad token is indistinguishable from a dropped socket (all
four verified in the map). The plan has the check do the handshake in new Liftoff code, reusing the
copied `Rpc`, `RhoJson`, `PROTOCOL_VERSION` and `RpcCodes`. The alternatives were editing
`connect()` to return the reply, which the brief rules out ("change only the package and import
lines") and which DESIGN §11 and invariant 4 want kept comparable with upstream, or reporting
"connected" without the versions. Someone reading a connection result expects the versions the
daemon gave, and a rejected token has to be told apart from an unreachable host because the fix is
different; keeping the copy byte-comparable is what makes the next copy cheap.

**A blank token means "not configured", not "try without a token".** rho's daemon admits
loopback connections with no token, so a blank token would really work against a daemon on the
same machine. The plan returns the not-configured outcome for a blank host *or* a blank token,
without opening a socket, as the brief lists it. On the phone the daemon is always remote over
Tailscale, where a missing token is refused outright, so a blank token means setup is unfinished
— saying that is more useful than a failure that looks like a network fault.

**The hello names the client `client`, per the spec, while the copied client keeps `client_name`.**
rho's Kotlin `DaemonClient` sends `client_name`; spec §1 and the Go daemon read `client`, and
ignore it today. The new check sends `client: "liftoff"`; the copy is left alone. The copy's job
is to stay comparable with upstream, the new code's job is to match the spec, and this is the
field the daemon logs and may one day act on.

**A protocol major mismatch is its own outcome, naming both versions.** DESIGN §7.3 step 1 and
§10 already make it a permanent failure with a clear message — "Liftoff speaks nabu protocol
1.x, the daemon speaks N.x" — not retried and not a generic "unreachable". The plan gives it a
distinct result carrying the version the daemon answered with. The fix is to update the app or
the daemon, which a person can only work out if both versions are in the answer.

**The check answers within 10 seconds and closes the socket it opened.** The brief sets no wait.
One overall 10-second cap — shorter than the copied client's own 15-second connect timeout — and
a close before returning. This is a "is the coach there?" question; a phone waiting half a minute
on a sleeping PC reads as a hang, nothing in this run keeps the connection, and a socket left
open with 20-second pings costs a thread.

**The documents rename ARCHITECTURE.md's whole vocabulary, and add one amendment to DESIGN.md.**
"nabu" appears in ARCHITECTURE.md's topology line, package table, data-flow line, invariants 3, 4
and 7 and the M3 milestone row, and throughout DESIGN §3, §7, §10 and §11. The plan renames every
mention in ARCHITECTURE.md and adds one dated amendment at the end of DESIGN.md declaring the
rename and naming the sections it applies to, leaving DESIGN's body as written. ARCHITECTURE.md is
the living map of the repository and the brief asks its wording to match, so it cannot call the
same thing by two names; DESIGN.md is the approved design record, where the honest record of a
change is an amendment, not a rewrite of text that was approved under the old name. The owner can
trim ARCHITECTURE.md back to the single row if they prefer the smaller diff.
