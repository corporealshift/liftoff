# Copy the rho client into Liftoff

Bring the harness's Android client code into Liftoff, so a later run can talk to the coach daemon. This run adds no UI. Read DESIGN.md §3, §7.3 step 1, §10 and §11 ('Reused from nabu'), and ARCHITECTURE.md invariants 3 and 4.

**The harness was renamed.** DESIGN.md and ARCHITECTURE.md call it 'nabu'. It is now 'rho'. Its protocol methods are `rho.hello`, `rho.session.create`, `rho.session.send_prompt` and so on, and the checkout is at `C:/Users/corpo/Documents/projects/rho`. Use the rho names and paths in new code.
- Add a short amendment at the end of DESIGN.md recording the rename.
- In ARCHITECTURE.md, change the `com.liftoff.app.nabu` row to `com.liftoff.app.rho` and update the wording to match.
- Don't rename the Room column `nabuSessionId`, because that would change the schema.

**Copy.** rho's Android client is at `C:/Users/corpo/Documents/projects/rho/clients/android/app/src/main/java/com/rho/client/`, and the protocol spec is at `C:/Users/corpo/Documents/projects/rho/protocol/spec.md`.
- Copy `net/DaemonClient.kt` and the protocol files it imports (such as `protocol/Events.kt` and `protocol/RpcCodes.kt`) into `app/src/main/java/com/liftoff/app/rho/`.
- Change only the package and import lines, so the copy stays comparable with upstream.
- Copy the smallest set of files that compiles. Leave out rho's UI, outbox, push and repository code. If DaemonClient needs a small type from a file you otherwise don't need (for example settings), copy only that file. Don't rewrite it.
- Each copied file starts with a short header comment giving its source path and the rho commit it came from (the output of `git -C C:/Users/corpo/Documents/projects/rho rev-parse HEAD`).
- Add a dependency only if the copied code needs one that isn't already declared, at rho's version (see rho's `clients/android` Gradle files).
- Nothing in rho changes.
- If the rho checkout can't be read, stop and report it. Don't write a client from scratch.

**AppContainer.** Give `AppContainer` a way to build a client from the current settings (daemon host, port, token), connect it and run `rho.hello` with client `liftoff`. The result says one of:
- connected, with the daemon version and protocol version
- the host or token is empty
- unreachable or timed out
- authentication rejected
- the protocol major version doesn't match the one the copied client expects

Put this in plain Kotlin that a test can drive with any host and port. Keep `AppContainer` hand-built, with no DI framework. Test connection (a later run) and the generation worker will both use it.

If something is unclear, make the choice a user would expect, record it in the plan's decisions, and carry on. Don't stop to ask, except in the missing-checkout case above. Every wait in a test needs a timeout. Tests must also pass on the GitHub CI runner (`.github/workflows/ci.yml`, ubuntu-latest, UTC), so don't depend on timing or the local machine. A full Gradle gate can outlast a shell command's time limit on this machine. If it does, run it detached and wait for its exit status.

Must not break: Mission Control (including export/import), the coach package (which stays free of Android imports), the other screens, and the build gate. Follow CLAUDE.md conventions: LF endings, `area: lowercase summary` commit messages, staging named files only.

Done when:
- The copied files compile under `com.liftoff.app.rho`, each with its source header.
- Tests against a fake daemon, an OkHttp MockWebServer WebSocket that scripts `rho.hello` in the style of rho's own client tests, cover:
  - success with both versions
  - a major-version mismatch
  - a refused connection
  - empty settings
- ARCHITECTURE.md lists `com.liftoff.app.rho` as existing, with the source commit.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes (use `bash gradlew.sh ...` on this machine).
