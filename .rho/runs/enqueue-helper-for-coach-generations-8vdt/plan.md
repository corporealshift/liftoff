# Plan

## Approach

DESIGN §7.3 already says how a generation is scheduled. What is missing is the code that does it. The
plan adds one small helper in `com.liftoff.app.work`, the package ARCHITECTURE assigns to WorkManager and
Android-side workers. The helper builds a single one-time request for `GenerationWorker`. The request carries
the generation id under the worker's own input key, the network constraint and the 1-minute exponential
backoff. The helper enqueues it as unique work named `gen-<generationId>` with `KEEP`. `AppContainer` exposes
the helper, because the container is where every other dependency in this app is wired (invariant 5: no DI
framework). `WorkManager.getInstance(appContext)` is called only when the helper is called, never held in a
field or a `lazy`. That way, building an `AppContainer`, which every screen test and `AppContainerTest`
already do, still touches nothing WorkManager-related. The helper only schedules: it never writes a
`Generation` row, and nothing in the UI calls it yet.

## Files

Names are the work session's to keep or adjust; what matters is what changes in each.

- `app/src/main/java/com/liftoff/app/work/GenerationEnqueuer.kt` (new): the helper. It builds the one-time
  request for `GenerationWorker` carrying the generation id under `GenerationWorker.GENERATION_ID`, with
  the network constraint and the 1-minute exponential backoff. It enqueues the request uniquely as
  `gen-<generationId>` with `KEEP` and hands the built request back to the caller. It only schedules and
  writes no rows.
- `app/src/main/java/com/liftoff/app/AppContainer.kt`: gains the member that exposes the helper. The member
  calls `WorkManager.getInstance(appContext)` inside the call, so no field or `lazy` touches WorkManager.
- `app/src/test/java/com/liftoff/app/work/GenerationEnqueuerTest.kt` (new): the brief's Robolectric test,
  `@Config(sdk = [34])`, with WorkManager set up through `WorkManagerTestInitHelper.initializeTestWorkManager`.
- `ARCHITECTURE.md:36`: the `com.liftoff.app.work` row. Name the helper and drop "nothing enqueues it
  yet".
- `ARCHITECTURE.md:82`: the M3 row. Its "still to come" clause lists "WorkManager's retry policy and
  backoff". After this work the backoff is set by the helper, so add the enqueue helper to the built
  list and take backoff out of "still to come". Leave app wiring there, because nothing calls the helper
  yet. Change nothing else in the row.

## Order

1. Write the helper in `com.liftoff.app.work`; `bash gradlew.sh :app:compileDebugKotlin` passes.
2. Expose it from `AppContainer`; compile again.
3. Write the test class and run only that class, cold (`:app:testDebugUnitTest --tests
   'com.liftoff.app.work.GenerationEnqueuerTest' --no-build-cache`). Read `tests=`/`failures=` from
   the XML. Then prove each assertion catches a fault: make one mutation at a time (drop the constraint,
   change the policy, change the delay, change the name), expect the test to fail, then restore the code.
4. Update the `work` row and the M3 row in `ARCHITECTURE.md`.
5. Run the whole gate detached (`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`), a second
   time if the first run hangs. Confirm in the XML that `GenerationWorkerTest`, `AppContainerTest` and the
   screen tests still pass.

## Testing

All new tests are Robolectric `@Config(sdk = [34])` in one new class.

- **The request's shape.** For generation `N`, the request the helper builds requires a connected
  network, backs off exponentially from 60 000 ms, and carries `N` under
  `GenerationWorker.GENERATION_ID`. All four can be read off the built request's `workSpec`: its
  `constraints`, `backoffPolicy`, `backoffDelayDuration` and `input`. They cannot be read off `WorkInfo`,
  which holds neither constraints nor backoff.
- **The name, and `KEEP` by its effect.** With `WorkManagerTestInitHelper.initializeTestWorkManager`,
  enqueue for `N` twice. Exactly one work item comes back for `gen-N`, and its id is the id of the
  request the first call scheduled: the earlier request was kept, not replaced. WorkManager does not
  report `KEEP` itself anywhere. `REPLACE` would also leave exactly one item under the name, so only the
  retained id shows that the policy is `KEEP`. That is why the helper has to hand back the request it
  scheduled.
- **Two generations are two items.** `gen-N` and `gen-M` do not collide.
- **Nothing else moved.** `AppContainerTest`, `GenerationWorkerTest` and the screen tests pass unchanged,
  with no WorkManager setup; the whole gate confirms it.

## Risks

- **Constraints and backoff are not on `WorkInfo`.** They live on the built request's `workSpec`. That
  property is `@RestrictTo`, which is a lint rule rather than a compile error, so unit tests can read it.
  Reading WorkManager's own database instead would be harder still. So the helper builds the request in its
  own function and returns it from the enqueue call. The test reads the shape off that request and never
  touches WorkManager's internals.
- **Do not use `TestDriver` to satisfy constraints.** `setAllConstraintsMet` would let the worker
  actually run, with no daemon and through a container the test does not control. Leaving the constraint
  unmet keeps the work `ENQUEUED`, which is the state the one-item check needs.
- **`WorkManager.getInstance` must run only when the helper is called.** Under Robolectric it throws when
  WorkManager was never initialized. If it were held in a field or a `lazy` on `AppContainer`, it would
  break `AppContainerTest` and every screen test, which is what the brief is guarding against.
- **A bare `ListenableFuture.get()` has no timeout.** Every wait must take `(timeout, unit)`, or use `await`
  inside `withTimeout`. Otherwise a stuck wait uses up the build's 5-minute test-task timeout and no XML
  gets written.
- **The full gate can hang for about 5 minutes on this machine.** Run it detached, read the log and the
  XML totals, and if it hangs, re-run the same tree rather than change code.
- **The run's verify script greps `ARCHITECTURE.md`'s rows.** Edit both table rows in place, one clause
  per change, and leave the rest of each row's wording alone.

## Decisions

**Added by review: the M3 milestone row is updated too.** The brief only names the `work` row. But the M3
row says WorkManager's backoff is "still to come", which would be false once this helper sets it. The
review chose to add the helper to the built list and take backoff out of "still to come". "App wiring"
stays there, because nothing calls the helper yet. The rest of the row is left as it is.

The brief's four scheduling behaviors are already decided in DESIGN §7.3, so they are not reopened here:
the `gen-<generationId>` name, `KEEP`, the network constraint, and exponential backoff starting at 1
minute. Two things §7.3 does not say are left open.

**Expedited or not.** The plan builds a plain one-time request with the network constraint and the
backoff, and nothing else. The alternative is `setExpedited`, which asks the system to run the work soon
even when it would normally defer it. The design describes a plain request, and that is what a generation
needs: the work is not time-critical and waits for the daemon on the owner's PC anyway. Also, an expedited
request that cannot meet its constraints is cancelled rather than deferred. A queued generation should do
the opposite when the phone is offline.

**Calling the helper again for a generation that has already run.** `KEEP` protects work that is still
pending. Once a run has finished, a second call enqueues a fresh run. The plan accepts that and does not
add a "has this already run" check. This is what the design's own wording produces, and the fresh run is
harmless: `GenerationWorker` reads the row and returns at once when the generation has already finished,
so no second coach turn is requested. The alternative is to refuse to enqueue a finished generation. That
is a rule about generations the brief never asks for. It would also get in the way of the regeneration
path (§7.3's supersede), which later work has to build on this helper.
