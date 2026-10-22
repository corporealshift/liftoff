# Plan

## Approach

DESIGN §7.3 already says how a generation is scheduled; what is missing is the code that does it. The
plan adds one small helper in `com.liftoff.app.work` — the package ARCHITECTURE assigns WorkManager and
Android-side workers to — that builds a single one-time request for `GenerationWorker` with the
generation id in the worker's own input key, the network constraint and the 1-minute exponential
backoff, and enqueues it as unique work named `gen-<generationId>` with `KEEP`. `AppContainer` exposes
it and passes its own `appContext`, which is where every other dependency in this app is wired
(invariant 5: no DI framework). `WorkManager.getInstance` is called inside the helper, not held in a
field, so building an `AppContainer` — which every screen test and `AppContainerTest` already do — still
touches nothing WorkManager-related. The helper schedules only: it never writes a `Generation` row, and
nothing in the UI calls it yet.

## Files

Names are the work session's to keep or adjust; what matters is what changes in each.

- `app/src/main/java/com/liftoff/app/work/GenerationEnqueuer.kt` (new) — the helper: build the one-time
  request for `GenerationWorker` carrying the generation id under `GenerationWorker.GENERATION_ID`, with
  the network constraint and the 1-minute exponential backoff, and enqueue it uniquely as
  `gen-<generationId>` with `KEEP`, handing the built request back to the caller. Schedules only; writes
  no rows.
- `app/src/main/java/com/liftoff/app/AppContainer.kt` — gains the member that exposes the helper, calling
  `WorkManager.getInstance(appContext)` inside the call so no field or `lazy` touches WorkManager.
- `app/src/test/java/com/liftoff/app/work/GenerationEnqueuerTest.kt` (new) — the brief's Robolectric test,
  `@Config(sdk = [34])`, WorkManager set up with `WorkManagerTestInitHelper.initializeTestWorkManager`.
- `ARCHITECTURE.md:36` — the `com.liftoff.app.work` row: name the helper and drop "nothing enqueues it
  yet". The M3 row (`ARCHITECTURE.md:82`) already lists retry policy and backoff as still to come and
  needs no change.

## Order

1. Write the helper in `com.liftoff.app.work`; `bash gradlew.sh :app:compileDebugKotlin` passes.
2. Expose it from `AppContainer`; compile again.
3. Write the test class and run only it, cold (`:app:testDebugUnitTest --tests
   'com.liftoff.app.work.GenerationEnqueuerTest' --no-build-cache`), reading `tests=`/`failures=` from
   the XML. Then prove each assertion bites: one mutation at a time (drop the constraint, change the
   policy, change the delay, change the name), expect red, restore.
4. Update the `work` row in `ARCHITECTURE.md`.
5. Run the whole gate detached (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`), twice if
   the first hangs, and confirm `GenerationWorkerTest`, `AppContainerTest` and the screen tests are still
   green in the XML.

## Testing

All new tests are Robolectric `@Config(sdk = [34])` in one new class.

- **The request's shape.** For generation `N`, the request the helper builds requires a connected
  network, backs off exponentially from 60 000 ms, and carries `N` under
  `GenerationWorker.GENERATION_ID`. All four are readable off the built request's `workSpec` — its
  `constraints`, `backoffPolicy`, `backoffDelayDuration` and `input` — not off `WorkInfo`, which holds
  neither constraints nor backoff (see map).
- **The name, and `KEEP` by its effect.** With `WorkManagerTestInitHelper.initializeTestWorkManager`,
  enqueue for `N` twice: exactly one work item comes back for `gen-N`, and its id is the id of the
  request the first call scheduled — the earlier request was kept, not replaced. `KEEP` itself is not
  stored anywhere WorkManager will report, and one item under the name is also what `REPLACE` and
  `EXCLUDE` would give, so the retained id is what makes the assertion mean `KEEP`. That needs the
  helper to hand back the request it scheduled.
- **Two generations are two items.** `gen-N` and `gen-M` do not collide.
- **Nothing else moved.** `AppContainerTest`, `GenerationWorkerTest` and the screen tests pass unchanged
  and with no WorkManager setup; the whole gate confirms it.

## Risks

- **Constraints and backoff are not on `WorkInfo`.** They live on the built request's `workSpec`
  (`@RestrictTo` — a lint rule, not a compile error, so unit tests can read it). Going through
  WorkManager's own database instead is more contorted still. So the helper builds the request in its own
  function and hands it back from the enqueue: the test reads the shape off that request and never touches
  WorkManager's internals.
- **Do not use `TestDriver` to satisfy constraints.** `setAllConstraintsMet` would let the worker actually
  run — against no daemon, through a container the test does not control. Leaving the constraint unmet
  keeps the work `ENQUEUED`, which is exactly the state the one-item check needs.
- **`WorkManager.getInstance` must be called inside the helper.** Under Robolectric it throws when
  WorkManager was never initialized; held in a field or a `lazy` on `AppContainer` it would break
  `AppContainerTest` and every screen test, which is what the brief is guarding against.
- **A bare `ListenableFuture.get()` has no timeout.** Every wait takes `(timeout, unit)` (or `await`
  inside `withTimeout`), or a stuck wait eats the build's 5-minute test-task timeout and writes no XML.
- **The full gate can hang for about 5 minutes on this machine** (known here). Run it detached, read the
  log and the XML totals, and re-run the same tree rather than change code.
- **`ARCHITECTURE.md`'s rows are grepped by the run's verify script.** Edit the `com.liftoff.app.work`
  table row in place, one clause per thing the brief asks for, and leave the rest of the row's wording
  alone.

## Decisions

The brief's four scheduling behaviors — the `gen-<generationId>` name, `KEEP`, the network
constraint, and exponential backoff starting at 1 minute — are already decided in DESIGN §7.3, so
they are not reopened here. Two things §7.3 does not say are left open.

**Expedited or not.** The plan builds a plain one-time request: the network constraint and the
backoff, and nothing else. The alternative is `setExpedited`, which asks the system to run the work
soon even when it would normally defer it. Plain is what the design describes, and it is what a
generation needs: the work is not time-critical, it waits for the daemon on the owner's PC anyway,
and an expedited request that cannot meet its constraints is cancelled rather than deferred — the
opposite of what a queued generation should do when the phone is offline.

**Calling the helper again for a generation that has already run.** `KEEP` protects work that is
still pending; once a run has finished, a second call enqueues a fresh run. The plan accepts that
and does not add a "has this already run" check. It is what the design's own words produce, and the
fresh run is harmless: `GenerationWorker` reads the row and returns at once when the generation has
already finished, so no second coach turn is asked for. The alternative — refusing to enqueue a
finished generation — is a rule about generations that the brief never asks for, and it would get in
the way of the regeneration path (§7.3's supersede) that later work has to build on this helper.
