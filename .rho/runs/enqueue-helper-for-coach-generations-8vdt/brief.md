# Enqueue helper for coach generations

Add the helper that schedules a `GenerationWorker` run for an existing `Generation` row, and expose it from `AppContainer`. `GenerationWorker` is already in `com.liftoff.app.work`, taking the generation id as input data; read its input key there. The helper only schedules. It never creates rows.

Requirements:
- unique work named `gen-<generationId>`, with `ExistingWorkPolicy.KEEP`
- a network-connected constraint
- exponential backoff with a 1-minute first delay
- `AppContainer` exposes it. Get `WorkManager` only when the helper is called, so `AppContainerTest` and the screen tests need no WorkManager setup.

`androidx.work:work-runtime-ktx` and `work-testing` are already dependencies. WorkManager is not initialized under Robolectric, so the test sets it up with `WorkManagerTestInitHelper.initializeTestWorkManager`.

Test (Robolectric `@Config(sdk = [34])`): the request carries the name, the KEEP policy, the network constraint and the 1-minute exponential backoff. Calling the helper twice for one generation leaves exactly one work item under that name. Every wait has a timeout; CI is ubuntu-latest, UTC.

Must not break: the worker's tests, `AppContainerTest`, every screen and the build gate. Follow CLAUDE.md conventions. The full gate can hang once for about 5 minutes on this machine. Run it detached, and re-run it if that happens.

Done when: the test passes, ARCHITECTURE.md's `work` row names the helper, and `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes.
