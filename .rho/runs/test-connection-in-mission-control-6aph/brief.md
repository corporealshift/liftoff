# Test connection in Mission Control

Add a 'Test connection' action to Mission Control's Connection section, so the owner can check that the phone reaches the coach daemon on their PC. Read DESIGN.md §9 ('Mission Control') and §10, and `design/README.md`. The harness DESIGN.md calls 'nabu' is now 'rho'.

Build on what's already in the repo:
- the copied rho client in `com.liftoff.app.rho`
- `AppContainer`'s helper that connects with the stored host, port and token, runs `rho.hello` and returns connected (with versions), empty settings, unreachable, auth rejected, or a version mismatch
- the Mission Control screen and view models in `com.liftoff.app.ui.control`, including the BACKUP card

This run adds only the action and its display. Don't change the client copy.

What to build:
- A 'Test connection' button in the Connection section. It uses the settings as currently stored.
- If the owner has unsaved edits in the connection fields, they are saved first. This is what a user expects, but if Mission Control already saves on every edit, there's nothing to add.
- While the test runs, show that it's working and ignore a second tap. Give up after a reasonable timeout and always close the connection.
- The result shows as an ink-ruled row:
  - on success, the daemon version and the protocol version
  - unreachable or timed out: 'Mission Control offline — check the PC and Tailscale'
  - auth rejected: a clear message saying the token was refused
  - empty host or token: a clear message saying which is missing
  - major-version mismatch: 'Liftoff speaks rho protocol 1.x, the daemon speaks N.x', using the major version the copied client expects
- Run it off the main thread. Use buttons from `ui.theme` and the design's rows, not stock M3 visuals. Keep the state logic in plain Kotlin outside Compose.

If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask. In Compose tests, unmount the screen before closing its database. Every wait in a test needs a timeout. Tests must also pass on the GitHub CI runner (`.github/workflows/ci.yml`, ubuntu-latest, UTC), so don't depend on timing or the local machine. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status.

Must not break: settings, equipment and export/import in Mission Control and their tests, the shell, the rho client tests, and the build gate. An existing test asserting there is no 'Test connection' placeholder may be updated. Say so in the PR. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- Tests drive the action against a fake daemon (OkHttp MockWebServer WebSocket) or a fake of the container helper, and show each message: success with both versions, mismatch, refused or offline, and missing settings. They also show that a second tap while busy does nothing.
- A Robolectric Compose test shows the button in the Connection section and a result row drawn from the state.
- ARCHITECTURE.md: M1's note no longer lists test connection as missing (M1 is done).
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
