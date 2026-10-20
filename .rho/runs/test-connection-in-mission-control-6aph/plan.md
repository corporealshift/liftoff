# Plan

## Approach
The handshake already exists and is already tested; what is missing is a place to press it and a row to read the answer. So the run adds a plain-Kotlin state holder for the action in `ui/control`, modelled on `BackupViewModel` (sealed state with `Idle`/busy/result and the message the card draws, a busy guard, work launched on an injected worker dispatcher), plus a `ConnectionSection` composable that sits inside the existing CONNECTION `SectionCard` and draws the button and the ink-ruled result row the way `BackupSection` does. The real check comes from `AppContainer.daemonConnectionCheck()` — the shell already holds the container and passes the helper down, and the screen exposes it as a seam the way it exposes `backupViewModel`. Nothing in `com.liftoff.app.rho` changes: every message the brief asks for maps onto an existing `ConnectionOutcome`, and the client already caps the check at 10 seconds and always closes the socket.


## Files
- `app/src/main/java/com/liftoff/app/ui/control/ConnectionViewModel.kt` (new) — the plain-Kotlin state holder: the state and its row text, the busy guard, the awaited save of host/port/token before the check, the `ConnectionOutcome` → message mapping, work on an injected worker dispatcher. Mirrors `BackupViewModel`; no Android imports, no ViewModel superclass.
- `app/src/main/java/com/liftoff/app/ui/control/ConnectionSection.kt` (new) — the button, labelled "Test connection" (the theme's style renders it "TEST CONNECTION"), and the ink-ruled busy/result row, drawn the way `BackupSection` draws them. `ui.theme`'s buttons have no `enabled` flag, so the button is a file-private copy of `UnderlinedTextButton` with that flag, mirroring `BackupTextButton`; `ui.theme` is not edited, so its tests stay untouched.
- `app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt` — the CONNECTION `SectionCard` gains `ConnectionSection`; the screen gains one required parameter carrying the check seam (the container's helper), which the screen wraps in the remembered view model; the CONNECTION preview copy gains the same row so the preview does not drift.
- `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt` — passes `container`'s connection helper into the screen. The only app caller.
- `app/src/test/java/com/liftoff/app/ui/control/ConnectionViewModelTest.kt` (new) — plain JUnit 4, no Robolectric: every message, the busy guard, the save-before-check, the worker thread.
- `app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt` — `showsNoUnbuiltPlaceholders` is updated (see Risks); `launchScreen` gains the seam; new Robolectric tests for the button and the row.
- `ARCHITECTURE.md` — the M1 row (line 80) drops "test connection comes later"; the row reads "Done — test connection, export and import are built".


## Order
1. `ConnectionViewModel` and `ConnectionViewModelTest` — the state, the outcome→message mapping, the busy guard, the awaited save, the worker dispatcher. No Compose yet; the new class is the gate.
2. `ConnectionSection` and the CONNECTION card in `MissionControlScreen`, plus the shell wiring, the preview row, and the update to `showsNoUnbuiltPlaceholders` that adding the button forces. `assembleDebug` and the existing screen tests pass.
3. The new Robolectric tests in `MissionControlScreenTest`: the button in the CONNECTION card, a result row drawn from a given state, and the busy look.
4. `ARCHITECTURE.md`'s M1 row.
5. The whole gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` (run detached if it outlasts a shell command), and a look at its log — the cross-cutting shell and settings tests are not covered by a per-class gate.


## Testing
Every message string the tests assert is in Decisions.

- Success (view-model test, fake check): the row names both versions the check reported.
- Success against a real fake daemon (`rho/FakeDaemon.kt`, MockWebServer WebSocket): the versions arrive from a real `rho.hello` frame rather than a canned outcome.
- Major-version mismatch: the row is "Liftoff speaks rho protocol 1.x, the daemon speaks 2.x", carrying the daemon's **major** (the client's own major is 1, from `PROTOCOL_VERSION = "1.0"`).
- Auth rejected: the refused-token sentence, from a 401 at the upgrade (`FakeDaemon.refuseUpgrade`) or the canned outcome.
- Offline: the brief's sentence, from `Unreachable`.
- A host that cannot build a request (typed as a URL, holding a space, or carrying its own port): the offline sentence plus the client's reason naming that host, and a token carrying characters a header can't carry names the token instead.
- Timed out: the same sentence. The client's own 10 s cap is already tested in `DaemonConnectionCheckTest`; the view-model test hands it the `Unreachable` outcome rather than waiting 10 seconds.
- Missing settings: blank host, blank token, and both blank each name what is missing, and nothing is opened — `NotConfigured` opens no socket, and a `FakeDaemon` aimed at by the container's helper records no request.
- Second tap while busy: hold the worker thread with a latch so the second call must hit the guard; the check runs once and the state stays busy.
- Off the main thread: the check runs on the injected worker dispatcher, not the caller's thread — the pattern the BACKUP tests already use.
- The fields are saved before the check: the store holds the draft host when the fake check runs.
- Robolectric: the button is displayed inside the CONNECTION card.
- Robolectric: while the test runs the row says it is working and the button is not enabled.
- Robolectric: a result row drawn from a given state shows that state's text over its ink rule (render-only: the state in `mutableStateOf`, no typing).
- Robolectric: `showsNoUnbuiltPlaceholders` now asserts the button is present instead of absent.
- Unchanged: the settings, equipment and BACKUP screen tests, `MissionControlViewModelTest`, the rho client tests and `AppContainerTest`.
- Screen tests that write through the store or Room unmount the screen before `tearDown` closes the database, and every wait is a `waitUntil` with a timeout.


## Risks
- The theme renders button labels uppercased, so the existing `assertDoesNotExist("Test connection", ignoreCase = true)` will match "TEST CONNECTION" and fail the moment the button exists. Step 2 updates that test; the PR says so.
- The fields save asynchronously (`scope.launch { store.set… }`), so a tap right after typing could check a stale host. The action awaits the save of host, port and token before it runs the check.
- An invalid port is deliberately never stored, so the check would aim at the last valid port while the field shows garbage. The action reports the port error and opens no socket.
- `ConnectionOutcome.NotConfigured` does not say *which* field is blank. The view model names it from the draft fields, so blanking only the token must produce the token message.
- `DaemonConnectionCheck` is a concrete class, so the seam is the container's helper call, not a fake of the class — a test hands a suspend call that returns a chosen outcome, or a real check aimed at `FakeDaemon`.
- A new row in the CONNECTION card can push content past the fixed `@Config(qualifiers = "w411dp-h2800dp")` window and break an existing section-head `assertIsDisplayed`. Keep the tall qualifiers and re-run the head test.
- MockWebServer gives up shutting down while either end of a socket is open. The check already closes in its `finally` and `FakeDaemon.stop()` closes both ends; every test wraps the check in a timeout.
- CI runs ubuntu-latest/UTC: no sleeps, no dependence on the local machine, latches and `waitUntil` with timeouts, and no test waits out the client's real 10-second cap.
- A cold gate can outlast a shell command on this machine: run it detached (`mkdir -p app/build` first), with a log and an `EXIT=` sentinel, and read the verdict before editing again.


## Decisions
**How long the test waits before calling it offline.** The plan reuses the cap the copied check already sets — `DaemonConnectionCheck.TIMEOUT_MS`, 10 seconds for the WebSocket connect plus the `rho.hello` round trip — and adds no timer of its own. The alternative is a shorter cap (3s) or a longer one (30s). Ten seconds is what the owner expects from a phone reaching a PC over Tailscale: a LAN handshake is under a second, but a sleeping peer takes a moment to wake, and 3s would report "offline" for a daemon that is actually fine. 30s just feels like a hang.

**What the button looks like while the test runs.** The plan keeps the label, dims the button to `Muted`, and makes it untappable, while the ruled row underneath says "Testing the daemon…" — the same way the BACKUP card dims its buttons and puts "Exporting…" in the row. The alternative is to relabel the button "Testing…" and leave the row empty, or to leave it looking normal and swallow the extra tap. A control that is busy should look busy, and the busy words belong in the row that every other result already uses.

**How long the result stays on screen.** The plan keeps the ink-ruled result row in the Connection section until the next test or until the owner leaves Mission Control — no auto-dismiss, no timer. The alternative is a snackbar that fades. The owner has to read a version number and decide what to do about a refused token, at their own pace.

**The exact wording of the rows.** The brief fixes the offline and mismatch sentences and leaves the rest open. The plan uses: success — "Mission Control answered — daemon *v*, protocol *p*", the two versions the daemon actually gave; auth rejected — "Mission Control refused this token — check it matches the PC"; missing settings — "No daemon host — set the PC's address", "No daemon token", and "No daemon host and no daemon token"; busy — "Testing the daemon…". The alternative is to reuse the client's own reason strings, which are written for a log ("the socket would not take the hello"). The design's voice is the one the rest of Mission Control speaks, and these are read on a phone, not in a log.

**A setting the daemon cannot be reached through says so.** A host typed as a URL, holding a space, or carrying its own port, and a token holding characters a header can't carry, already arrive from the client as `Unreachable` with a reason that names the setting at fault. The plan shows the brief's offline sentence for every `Unreachable`, with the client's own reason as a smaller muted note under it. The alternative is to sort the reasons into "genuinely offline" and "you mistyped a setting" and show the brief's sentence only for the first — but the reasons are free-form strings, so sorting them makes the copied client's wording a contract this run must not break. The owner still sees the sentence the design uses, and the line under it says which setting to fix.

**An invalid port stops the test.** If the port field holds something that is not a number, the row says the port is not a valid number and no socket is opened. The alternative is to check the last port that *was* valid, which is what the store actually holds. The owner would read a good result as "this address works" while the field in front of them is garbage.

**Whether the test uses edits the owner has not saved.** Mission Control already writes every edit to the store as it is typed, so there are no unsaved edits and no Save button to add. What the plan adds is that the action **awaits** that write before it checks, so a tap straight after typing cannot check the previous value. The alternative is to read the store as it happens to be at the moment of the tap. Tapping "Test connection" after typing a new address should test the address you just typed.

**No automatic check when Mission Control opens.** The plan only runs the check on a tap. The alternative is a check on entry. The brief asks for an action, and a network call every time the tab opens would surprise the owner and fail noisily when the PC is off.

**Nothing is stored about the check.** The plan shows the result and writes nothing — no "last checked" timestamp, no persisted status. The brief says this run adds only the action and its display, and a stale "last checked" on a phone that has since moved networks is worse than none.

