# Plan

## Approach

Three files are copied across with only their package and import lines changed. Each gets a header naming its rho source path and commit `ffce07d38c0eba572f1b751701293ba0cf8b0fb6`: `net/DaemonClient.kt`, `protocol/Events.kt` and `protocol/RpcCodes.kt`. These three are the whole compile set, because `DaemonClient.kt` imports nothing else from rho (map). None of them imports `android.*`, so they compile in the unit-test source set and can be tested with plain JUnit 4 against a MockWebServer WebSocket, the way rho tests its own client.

The copied client cannot report the brief's outcomes by itself, for three reasons (map):
- `connect()` sends the hello and throws the reply away.
- A second `rho.hello` on the same connection is refused, because the daemon never registers that method.
- `onFailure` drops the HTTP response, so a 401 looks like any other dropped socket.

So the brief's five outcomes come from one new plain-Kotlin connection check in `com.liftoff.app.rho`. It opens its own WebSocket, sends the first `rho.hello` with client `liftoff`, and reads the reply. It reuses the copied `Rpc`, `RhoJson`, `PROTOCOL_VERSION` and `RpcCodes` rather than duplicating them (see Decisions: "The check reads the `rho.hello` reply itself").

`AppContainer` gives callers two things, both built from the settings it already stores, and both using rho's URL form `http://<host>:<port>/` with host and token trimmed:
- the check;
- a factory for a copied `DaemonClient` named `liftoff`, which the generation worker will connect and drive.

`AppContainer` stays hand-built, with no DI framework, no UI and no new dependency. All four dependencies the copied code needs are already declared (map). Nothing in rho changes, and nothing in `coach/`, `domain/`, Room or the export format moves.

## Files

New under `app/src/main/java/com/liftoff/app/rho/`:

- `net/DaemonClient.kt`: copy of rho's `clients/android/.../net/DaemonClient.kt` (363 lines). The package line and the four `com.rho.client.protocol` imports become `com.liftoff.app.rho.*`. Add the header comment with source path and commit. Nothing else changes.
- `protocol/Events.kt`: copy (216 lines). Package line only, plus the header.
- `protocol/RpcCodes.kt`: copy (44 lines). Package line only, plus the header.
- One new file holding the connection check and its outcomes. The work session names it; it is not part of the copied client. It is plain Kotlin with no Android imports. It builds its own `OkHttpClient` with the check's own timeouts, sends its own first `rho.hello` (`client`, `client_version`, `protocol_version`), applies a 10-second cap, and closes the socket before returning. It reports a major-version mismatch in two cases:
  - the daemon refuses the hello with `-32006`;
  - the daemon accepts the hello but its `protocol_version` has a different major than `PROTOCOL_VERSION`.

Changed:

- `app/src/main/java/com/liftoff/app/AppContainer.kt`: a hand-built way to make the check from the current `settingsStore` settings (host, port, token), and a factory that builds an unconnected `DaemonClient(baseUrl, token, clientName = "liftoff")` from the same settings. No DI framework, and no connection is held.
- `app/src/test/java/com/liftoff/app/AppContainerTest.kt`: tests that the container's check and client use the persisted settings.
- `DESIGN.md`: one amendment bullet in the newest-first position under "Amendments after approval" (map). It records the nabu → rho rename, the `rho.` method prefix and the new checkout path.
- `ARCHITECTURE.md`:
  - The line 31 row becomes `com.liftoff.app.rho`, with its wording updated for the rename. Its "Exists yet?" column becomes ✅, naming the three copied files and the source commit.
  - The topology line, the data-flow line, invariants 3, 4 and 7, and the M3 milestone row are renamed to rho (see Decisions: "The documents rename ARCHITECTURE.md's whole vocabulary").

New under `app/src/test/java/com/liftoff/app/rho/`:

- `FakeDaemon.kt`: a MockWebServer WebSocket fake modelled on rho's own (map). Its `Dispatcher` answers every request with an upgrade, records frames and request headers, and can refuse the handshake. It is scripted for:
  - a successful `rho.hello`;
  - a `-32006` refusal;
  - a success reply carrying a foreign major version;
  - a 401 at the upgrade.
- The check's test class.

Unchanged on purpose:
- `app/build.gradle.kts`, because all four dependencies are already declared at rho's versions (map).
- The `nabuSessionId` column in `data/Generation.kt`.
- `@SerialName("nabu_session_id")` in `BackupDocument.kt`.
- Everything in `coach/` and `domain/`.

## Order

1. Copy the three files into `com.liftoff.app.rho`, adding their headers and renaming their package and import lines. `bash gradlew.sh :app:assembleDebug` passes. Commit `rho: copy the daemon client`.
2. Write the connection check in the same package. `:app:assembleDebug` still passes. Commit.
3. Write `FakeDaemon.kt` and the check's tests, then run just that test class. Commit.
4. Wire `AppContainer` to build the check and the client from the stored settings, and add its tests. Run `AppContainerTest`. Commit.
5. Write the `DESIGN.md` amendment and make the `ARCHITECTURE.md` rename. Commit `docs: record the nabu to rho rename`.
6. Run the whole gate detached, as the map's hazards section describes, and read the counts from the XML. Commit only if it needed a fix.

Steps 1–4 each leave the build green on their own. Step 5 changes no code.

## Testing

All of the check's tests are plain JUnit 4 against `FakeDaemon`. There is no Robolectric and no real host, every wait has a timeout, and nothing reads the wall clock or depends on the local machine (CI is ubuntu-latest, UTC).

- **Success.** The fake answers `rho.hello` with a daemon version and a protocol version. The outcome is connected and carries both versions exactly as the daemon sent them.
- **What the check sends.** The fake received a `rho.hello` frame with client `liftoff` and the copied client's protocol version, and a `Bearer` header carrying the token.
- **Major-version mismatch.** The fake refuses the hello with `RpcCodes.PROTOCOL_MISMATCH` (-32006). The outcome is the mismatch one: it names the expected version and the daemon's version as stated in the refusal, and it is not reported as unreachable. A second case has the fake accept the hello but answer `protocol_version` `"2.0"`; that is also a mismatch.
- **Refused connection.** The test starts a MockWebServer, takes its port and shuts it down, so nothing is listening there. The outcome is unreachable, and it arrives within the check's own cap instead of hanging.
- **Empty settings.** A blank host, and separately a blank token, each give the not-configured outcome. Blank means empty or whitespace only. The fake records no request at all, which proves nothing was opened.
- **Authentication rejected.** The fake answers the upgrade with HTTP 401, as the real daemon does for a wrong token on a non-loopback connection. The outcome is authentication rejected, not unreachable. This is not one of the brief's four required cases, but the outcome exists and the fake can produce it, so it is tested.
- **The check closes its socket.** The fake's shutdown completes instead of hanging.
- **`AppContainer` wiring.** These tests go in the existing Robolectric `AppContainerTest`.
  - Persist a host, port and token pointing at the fake daemon. The container's check then reports connected, with the versions the fake gave.
  - Connecting the container's `DaemonClient` against the fake records a hello with `client_name` `liftoff` and the stored token as the bearer header. The test then closes both ends.

## Risks

- **MockWebServer will not shut down while either end of a WebSocket is open.** That hangs the test task until the 5-minute limit, and no `TEST-*.xml` gets written. The tests close both ends in teardown, as rho's own client tests do.
- **MockWebServer's default queue serves one enqueued response per connection**, so a second connection hangs. `FakeDaemon` answers through a `Dispatcher`, as rho's does.
- **A Robolectric + Room suite can hang here and die at that same timeout with no XML** (a known problem on this machine). Re-run it; do not "fix" it.
- **The full gate can outlast a shell command's time limit.** Run it detached using the map's recipe, and read the counts from the XML rather than from stdout.
- **The copied client's timeouts are wrong for an "is the coach there?" question**: 15 s connect, no read timeout, 20 s pings. The check builds its own `OkHttpClient` instead of reusing `defaultHttp()`. The copy is left as upstream wrote it, and the container's `DaemonClient` keeps `defaultHttp()`, which suits a long-running worker.
- **The daemon's mismatch refusal carries its version only in the message text** (`"… incompatible with daemon <v>"`). The check pulls the version out of that text when it can. Otherwise it reports the daemon's message as given, and never falls back to "unreachable".
- **The copy keeps sending `client_name` where spec §1 says `client`.** It is left alone (see Decisions: "The hello names the client `client`"), so `FakeDaemon` must not insist on either name.
- **The Room schema and the export format must not move.** `nabuSessionId` and `@SerialName("nabu_session_id")` stay as they are. There is no migration, and no export/import test is touched.
- **`coach/` and `domain/` must stay free of Android imports** (invariant 2). The copied files import no `android.*` and live in `rho`, so nothing moves. The check is plain Kotlin too.
- **The wiring tests are the only ones that mix Robolectric with a live socket.** They need their own timeout, and `AppContainer` must not hold a connection afterwards. If they prove fragile, the check's own tests already cover the outcomes, and these tests can fall back to asserting that the container reports not-configured for blank settings.
- **Line endings.** `.gitattributes` sets `* text=auto eol=lf`, so copied files land with LF. Check that no CR survives in the three copies (CLAUDE.md).
- **If the rho checkout stops being readable mid-run**, stop and report it rather than writing a client from scratch (the brief's own stop condition).

## Decisions

**Added by review: `AppContainer` also builds an unconnected copied `DaemonClient` from the settings, named `liftoff`, alongside the check.** The brief asks for "a way to build a client from the current settings" that both Test connection and the generation worker will use. The worker needs a real client it can connect and send prompts through, not just a yes/no answer. The plan only exposed the check. The factory uses rho's own URL form (`http://<host>:<port>/`) and passes `clientName = "liftoff"`, so the copy stays untouched. It holds no connection, so each caller owns and closes what it opens.

**Added by review: a major mismatch is also detected when the daemon accepts the hello but answers with a different major version.** rho's daemon refuses a mismatch with `-32006`, but a daemon that answers anyway with `protocol_version` `2.x` is still one this client doesn't speak. Reporting that as "connected" would let the generation worker fail later in a confusing way. The daemon's version in the refusal case comes from the refusal message, because the error carries no structured version.

**The check reads the `rho.hello` reply itself, rather than calling the copied `DaemonClient.connect()`.** The brief wants "connected, with the daemon version and protocol version". The copied client can't provide that, for four reasons, all verified in the map:
- `connect()` sends the hello and throws the reply away; it only tests `hello.error != null`.
- Its socket is private, so nothing else on that connection can ask.
- A second `rho.hello` is refused, because the daemon never registers that method.
- `onFailure` drops the HTTP response, so the daemon's 401 for a bad token can't be told apart from a dropped socket.

The plan has the check do the handshake in new Liftoff code, reusing the copied `Rpc`, `RhoJson`, `PROTOCOL_VERSION` and `RpcCodes`. There were two alternatives:
- Edit `connect()` to return the reply. The brief rules this out ("change only the package and import lines"), and DESIGN §11 and invariant 4 want the copy kept comparable with upstream.
- Report "connected" without the versions.

Someone reading a connection result expects the versions the daemon gave. A rejected token has to be told apart from an unreachable host because the fix is different. Keeping the copy byte-comparable is what makes the next copy cheap.

**A blank token means "not configured", not "try without a token".** rho's daemon admits loopback connections with no token, so a blank token would actually work against a daemon on the same machine. The plan still returns the not-configured outcome for a blank host *or* a blank token, without opening a socket, as the brief lists it. On the phone the daemon is always remote over Tailscale, where a missing token is refused outright. So a blank token means setup is unfinished, and saying so is more useful than a failure that looks like a network fault.

**The hello names the client `client`, per the spec, while the copied client keeps `client_name`.** rho's Kotlin `DaemonClient` sends `client_name`. Spec §1 and the Go daemon read `client`, and today the daemon ignores it either way. The new check sends `client: "liftoff"`, and the copy is left alone. The copy's job is to stay comparable with upstream; the new code's job is to match the spec. This is the field the daemon logs and may one day act on.

**A protocol major mismatch is its own outcome, naming both versions.** DESIGN §7.3 step 1 and §10 already make it a permanent failure with a clear message ("Liftoff speaks nabu protocol 1.x, the daemon speaks N.x"), not retried and not a generic "unreachable". The plan gives it a distinct result that carries the version the daemon answered with. The fix is to update the app or the daemon, and a person can only work out which if both versions are in the answer.

**The check answers within 10 seconds and closes the socket it opened.** The brief sets no wait. The plan uses one overall 10-second cap, shorter than the copied client's own 15-second connect timeout, and closes the socket before returning. This is an "is the coach there?" question. A phone waiting half a minute on a sleeping PC looks like a hang, nothing in this run keeps the connection, and a socket left open with 20-second pings costs a thread.

**The documents rename ARCHITECTURE.md's whole vocabulary, and add one amendment to DESIGN.md.** "nabu" appears in several places:
- in ARCHITECTURE.md: the topology line, the package table, the data-flow line, invariants 3, 4 and 7, and the M3 milestone row;
- in DESIGN.md: throughout §3, §7, §10 and §11.

The plan renames every mention in ARCHITECTURE.md. In DESIGN.md it adds one dated amendment at the end that declares the rename and names the sections it applies to, and leaves the body as written. ARCHITECTURE.md is the living map of the repository and the brief asks for its wording to match, so it can't call the same thing by two names. DESIGN.md is the approved design record, where the honest record of a change is an amendment, not a rewrite of text approved under the old name. The owner can trim ARCHITECTURE.md back to the single row if they prefer the smaller diff.
