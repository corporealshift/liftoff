# Plan

## Approach
The handshake already exists and is already tested. What's missing is a button to run it and a row to show the answer. So the run adds two things in `ui/control`:
- a plain-Kotlin state holder for the action, modelled on `BackupViewModel`: a sealed state with `Idle`, busy and result, plus the message the card draws; a busy guard; and work launched on an injected worker dispatcher;
- a `ConnectionSection` composable that sits inside the existing CONNECTION `SectionCard` and draws the button and the ink-ruled result row the way `BackupSection` does.

The real check comes from `AppContainer.daemonConnectionCheck()`. That helper returns a `DaemonConnectionCheck`, not an outcome, so the shell passes the screen a suspend call, `{ container.daemonConnectionCheck().check() }`. The screen wraps that seam in its remembered view model, the way it builds `backupViewModel`. Nothing in `com.liftoff.app.rho` changes. Every message the brief asks for maps onto an existing `ConnectionOutcome`, and the client already caps the check at 10 seconds and always closes the socket.


## Files
- `app/src/main/java/com/liftoff/app/ui/control/ConnectionViewModel.kt` (new): the plain-Kotlin state holder. It is built from the `SettingsStore`, the check seam (`suspend () -> ConnectionOutcome`), the screen's scope, and a dispatcher that defaults to `Dispatchers.IO`. Its one action takes the screen's current draft (host, port text, port error, token). Then it:
  1. applies the busy guard;
  2. stops on an invalid port or a blank host or token (blank after trimming, as the container trims);
  3. otherwise writes host, port and token through the store and waits for the write to finish;
  4. calls the seam and maps the `ConnectionOutcome` to a row.

  It mirrors `BackupViewModel`: no Android imports, no ViewModel superclass. The client's own major comes from `PROTOCOL_VERSION`'s major, not a hard-coded "1".
- `app/src/main/java/com/liftoff/app/ui/control/ConnectionSection.kt` (new): the button labelled "Test connection" (rendered "TEST CONNECTION") and the ink-ruled busy/result row, drawn the way `BackupSection` draws its row. A muted note under the row holds the `Unreachable` reason.
- `app/src/main/java/com/liftoff/app/ui/control/BackupSection.kt`: `BackupTextButton` goes from `private` to `internal` so the CONNECTION card reuses it: `ui.theme`'s `UnderlinedTextButton` with the `enabled` flag it lacks. Nothing else changes. This replaces the plan's second private copy of the same 50 lines. `ui.theme` is not edited, so its tests stay untouched.
- `app/src/main/java/com/liftoff/app/ui/control/MissionControlScreen.kt`:
  - the CONNECTION `SectionCard` gains `ConnectionSection` below the token field;
  - the screen gains one required parameter, the check seam, which it wraps in the remembered view model. The tap passes `state` (the draft) to the action;
  - the CONNECTION preview copy gains the same row, so the preview does not drift.
- `app/src/main/java/com/liftoff/app/ui/LiftoffShell.kt`: passes `{ container.daemonConnectionCheck().check() }` into the screen. This is the only app caller.
- `app/src/test/java/com/liftoff/app/ui/control/ConnectionViewModelTest.kt` (new): plain JUnit 4, no Robolectric. It covers every message, the busy guard, the save before the check, and the worker thread.
- `app/src/test/java/com/liftoff/app/ui/control/MissionControlScreenTest.kt`: `showsNoUnbuiltPlaceholders` is updated (see Risks), `launchScreen` gains the seam with a default canned answer, and new Robolectric tests cover the button and the row.
- `ARCHITECTURE.md`: the M1 row (line 80) drops "test connection comes later" and reads "Done — test connection, export and import are built".


## Order
1. `ConnectionViewModel` and `ConnectionViewModelTest`: the state, the outcome-to-message mapping, the busy guard, the awaited save and the worker dispatcher. No Compose yet; the new class is the gate.
2. `ConnectionSection`, `BackupTextButton` made internal, and the CONNECTION card in `MissionControlScreen`. Also the shell wiring, the preview row, and the update to `showsNoUnbuiltPlaceholders` that adding the button forces. `assembleDebug` and the existing screen tests pass.
3. The new Robolectric tests in `MissionControlScreenTest`: the button in the CONNECTION card, a result row drawn from a given state, and the busy look.
4. `ARCHITECTURE.md`'s M1 row.
5. The whole gate: `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`. Run it detached if it outlasts a shell command, then read its log. A per-class gate doesn't cover the cross-cutting shell and settings tests.


## Testing
Every message string the tests assert is in Decisions.

- Success (view-model test, fake check): the row names both versions the check reported.
- Success against a fake daemon (`rho/FakeDaemon.kt`, MockWebServer WebSocket): the seam builds a `DaemonConnectionCheck` from the store's saved settings the way the container's helper does. The container itself needs Robolectric, and `AppContainerTest` already covers it. So the versions arrive in a real `rho.hello` frame, not a canned outcome.
- Major-version mismatch: the row is "Liftoff speaks rho protocol 1.x, the daemon speaks 2.x". It carries the daemon's **major**; the client's own major comes from `PROTOCOL_VERSION`. A mismatch whose daemon version is empty shows the unknown-version row.
- Auth rejected: the refused-token sentence, from a 401 at the upgrade (`FakeDaemon.refuseUpgrade`) or from the canned outcome.
- Offline: the brief's sentence, from `Unreachable`.
- A host that cannot build a request (typed as a URL, holding a space, or carrying its own port): the offline sentence plus the client's reason naming that host. A token holding characters a header can't carry gets a reason that names the token instead.
- Timed out: the same sentence. `DaemonConnectionCheckTest` already tests the client's own 10 s cap, so the view-model test hands in the `Unreachable` outcome rather than waiting 10 seconds.
- Missing settings: a blank host, a blank token, both blank, and whitespace-only values each name what is missing, and the seam is never called. A `FakeDaemon` the store points at records no request.
- Invalid port: the port row shows, and the seam is never called.
- Second tap while busy: a latch holds the worker thread so the second call must hit the guard. The check runs once and the state stays busy.
- Off the main thread: the check runs on the injected worker dispatcher, not the caller's thread. This is the pattern the BACKUP tests already use.
- The fields are saved before the check: the store holds the draft host when the fake check runs.
- Robolectric: the button is displayed inside the CONNECTION card.
- Robolectric: while the test runs, the row says it is working and the button is not enabled. The seam holds on a `CompletableDeferred`, which the test completes before it unmounts the screen.
- Robolectric: a result row drawn from a given state shows that state's text over its ink rule. This is render-only: the state sits in `mutableStateOf`, with no typing.
- Robolectric: `showsNoUnbuiltPlaceholders` now asserts the button is present instead of absent.
- Unchanged: the settings, equipment and BACKUP screen tests, `MissionControlViewModelTest`, the rho client tests and `AppContainerTest`.
- Screen tests that write through the store or Room unmount the screen before `tearDown` closes the database, and every wait is a `waitUntil` with a timeout.


## Risks
- The theme renders button labels in uppercase, so the existing `assertDoesNotExist("Test connection", ignoreCase = true)` will match "TEST CONNECTION" and fail as soon as the button exists. Step 2 updates that test, and the PR says so.
- The fields save asynchronously (`scope.launch { store.set… }`), so a tap right after typing could check a stale host. The action writes the draft host, port and token itself and waits for that write before it runs the check.
- An invalid port is deliberately never stored, so the check would aim at the last valid port while the field shows garbage. The action reports the port error and opens no socket.
- `ConnectionOutcome.NotConfigured` does not say *which* field is blank. The view model names it from the trimmed draft fields, so blanking only the token must produce the token message.
- `DaemonConnectionCheck` is a concrete class, so the seam is the suspend call, not a fake of the class. A test hands in a call that returns a chosen outcome, or a real check aimed at `FakeDaemon`.
- Making `BackupTextButton` internal changes only its visibility. The BACKUP tests must still pass unchanged.
- A new row in the CONNECTION card can push content past the fixed `@Config(qualifiers = "w411dp-h2800dp")` window and break an existing section-head `assertIsDisplayed`. Keep the tall qualifiers and re-run the head test.
- MockWebServer gives up shutting down while either end of a socket is open. The check already closes the socket in its `finally`, `FakeDaemon.stop()` closes both ends, and every test wraps the check in a timeout.
- CI runs on ubuntu-latest in UTC. So: no sleeps, no dependence on the local machine, latches and `waitUntil` with timeouts, and no test waits out the client's real 10-second cap.
- A cold gate can outlast a shell command on this machine. Run it detached (`mkdir -p app/build` first) with a log and an `EXIT=` sentinel, and read the verdict before editing again.


## Decisions
**Added by review: a version the daemon didn't name.** `ProtocolMismatch.daemonVersion` is empty when a refusal message names no version, or when the daemon's answer has no `protocol_version`. In that case the row reads "Liftoff speaks rho protocol 1.x, the daemon did not say which version it speaks". It does not show "the daemon speaks .x" or a guessed number. Likewise, a `Connected` answer with an empty `daemon_version` shows "unknown" in that place. The alternative is to print whatever string arrived. An empty or garbled version in the owner's row reads as a bug in Liftoff, not as a fact about the daemon.

**How long the test waits before calling it offline.** The plan reuses the cap the copied check already sets, `DaemonConnectionCheck.TIMEOUT_MS`: 10 seconds for the WebSocket connect plus the `rho.hello` round trip. It adds no timer of its own. The alternative is a shorter cap (3 s) or a longer one (30 s). Ten seconds is what the owner expects from a phone reaching a PC over Tailscale. A LAN handshake takes under a second, but a sleeping peer takes a moment to wake, and 3 s would report "offline" for a daemon that is actually fine. 30 s just feels like a hang.

**What the button looks like while the test runs.** The plan keeps the label, dims the button to `Muted` and makes it untappable, while the ruled row underneath says "Testing the daemon…". This is how the BACKUP card dims its buttons and puts "Exporting…" in the row. The alternatives are to relabel the button "Testing…" and leave the row empty, or to leave the button looking normal and swallow the extra tap. A busy control should look busy, and the busy words belong in the row that every other result already uses.

**How long the result stays on screen.** The plan keeps the ink-ruled result row in the Connection section until the next test or until the owner leaves Mission Control. There is no auto-dismiss and no timer. The alternative is a snackbar that fades. The owner has to read a version number, or decide what to do about a refused token, at their own pace.

**The exact wording of the rows.** The brief fixes the offline and mismatch sentences and leaves the rest open. The plan uses:
- success: "Mission Control answered — daemon *v*, protocol *p*", with the two versions the daemon actually gave;
- auth rejected: "Mission Control refused this token — check it matches the PC";
- missing settings: "No daemon host — set the PC's address", "No daemon token", and "No daemon host and no daemon token";
- busy: "Testing the daemon…".

The alternative is to reuse the client's own reason strings, which are written for a log ("the socket would not take the hello"). The design's voice is the one the rest of Mission Control speaks, and these rows are read on a phone, not in a log.

**A setting the daemon cannot be reached through says so.** Some settings already arrive from the client as `Unreachable`, with a reason that names the setting at fault: a host typed as a URL, holding a space, or carrying its own port, and a token holding characters a header can't carry. The plan shows the brief's offline sentence for every `Unreachable`, with the client's own reason as a smaller muted note under it. The alternative is to sort the reasons into "genuinely offline" and "you mistyped a setting", and show the brief's sentence only for the first. But the reasons are free-form strings, so sorting them would make the copied client's wording a contract this run must not break. The owner still sees the sentence the design uses, and the line under it says which setting to fix.

**An invalid port stops the test.** If the port field holds something that is not a number, the row says the port is not a valid number and no socket is opened. The alternative is to check the last port that *was* valid, which is what the store actually holds. The owner would read a good result as "this address works" while the field in front of them is garbage.

**Whether the test uses edits the owner has not saved.** Mission Control already writes every edit to the store as it is typed, so there are no unsaved edits and no Save button to add. What the plan adds is that the action **waits for** that write before it checks, so a tap straight after typing cannot check the previous value. The alternative is to read the store as it happens to be at the moment of the tap. Tapping "Test connection" after typing a new address should test the address you just typed.

**No automatic check when Mission Control opens.** The plan only runs the check on a tap. The alternative is a check on entry. The brief asks for an action, and a network call every time the tab opens would surprise the owner and fail noisily when the PC is off.

**Nothing is stored about the check.** The plan shows the result and writes nothing: no "last checked" timestamp, no saved status. The brief says this run adds only the action and its display, and a stale "last checked" on a phone that has since moved networks is worse than none.
