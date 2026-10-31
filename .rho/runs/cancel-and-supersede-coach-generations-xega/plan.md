# Plan

## Approach

One rule, enforced from both sides: a generation that is no longer the one its target is waiting for may not
write. The cancel helper is DESIGN §7.3's mechanism for the regeneration path: cancel the unique work, cancel
the row, stop the rho session. It is built in `com.liftoff.app.work` next to `GenerationEnqueuer` and exposed from
`AppContainer` the way the enqueue helper is, so the app wiring that comes next has something to call. The
worker's guard applies the same rule at the moment of writing. `storeFinishedTurn` already decides, in one place,
whether to store and mark SUCCEEDED, so the currency check goes there and nothing about the turn, the repairs,
the resume or the retry paths moves. The DAO reads the brief names (`markCancelled`,
`getNewestNonCancelledForSortie`, `getNewestNonCancelledOutlineForMission`) already exist and are already tested,
and `GenerationStatus.CANCELLED` already exists, so no Room schema change appears anywhere. Both sides decide
inside a Room transaction: the worker checks and stores in one, and the canceller reads the status and marks the
row in one. Either the store wins and the cancel does nothing, or the cancel wins and nothing is stored.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationCanceller.kt` (new): the cancel helper, a suspend call taking
  a generation id. It cancels `gen-<generationId>`. Then, in one `database.withTransaction`, it re-reads the row
  and marks it CANCELLED only if the row is QUEUED or RUNNING. After the transaction, if it cancelled the row and
  the row carries a session id, it opens a client, calls the stop helper `stopCoachSession`, and closes the
  client. A connect that fails or a stop that is refused is swallowed (a `CancellationException` is rethrown, as
  the worker's `giveUpSession` does). A row already SUCCEEDED, FAILED or CANCELLED is left alone.
- `app/src/main/java/com/liftoff/app/work/GenerationEnqueuer.kt`: the `gen-<generationId>` name stops being
  private to this file, so the enqueuer and the canceller cannot spell the same work differently.
- `app/src/main/java/com/liftoff/app/AppContainer.kt`: a cancel accessor beside `generationEnqueuer()`, built
  per call from the container's own WorkManager and database. `daemonClient()` is suspend, so the canceller gets
  a way to ask for a client at stop time (the container, or a suspend factory), not a client built up front.
- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt`: the store guard in `storeFinishedTurn`. Inside
  one `database.withTransaction`, together with the store and `markSucceeded`, it re-reads the row and the newest
  non-cancelled generation for its sortie (Flight Plan) or its Mission's outline (outline). The not-current path
  marks the row CANCELLED if it is not already, stops `turn.sessionId` outside the transaction through
  `giveUpSession`, and returns success.
- `app/src/test/java/com/liftoff/app/work/GenerationCancellerTest.kt` (new): the cancel behaviour, on the
  application's container and `FakeDaemon`, with WorkManager initialised the way `GenerationEnqueuerTest` does.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt`: the three store-guard cases added to the
  existing harness.
- `ARCHITECTURE.md`: the M3 row (line 82), the only document the brief asks to change. It will say the worker,
  including resilience, cancel and supersede, is built, and app wiring comes next.

## Order

1. Share the unique work name, write `GenerationCanceller.kt` and add the container accessor. Gate:
   `GenerationCancellerTest`: cancel with a session, cancel with the daemon unreachable, a finished row
   untouched.
2. Add the guard to `storeFinishedTurn` and the transaction around check + store + `markSucceeded`. Gate:
   `GenerationWorkerTest`: cancelled mid-turn, superseded Flight Plan (older then newer), superseded outline.
3. Run the existing worker and enqueuer tests unchanged, to show the guard did not move the happy path, the
   repair path or any retry path.
4. Update the M3 row of `ARCHITECTURE.md`, then run the whole gate
   (`:app:assembleDebug :app:testDebugUnitTest`) once, detached.

## Testing

Robolectric throughout, with every wait bounded. Tests run on the application's own container plus `FakeDaemon`
(the worker test's harness; the map records its helpers).

- Cancel on a row with a session: the row reads CANCELLED with `finishedAt` stamped. Exactly one
  `rho.session.stop` frame names the session the row carried, and the work under `gen-<id>` reads CANCELLED, not
  ENQUEUED.
- Cancel with the daemon unreachable (settings pointed at a loopback port that was opened and closed, as
  `pointSettingsAtNothing` does): the row still ends CANCELLED, the call does not throw, and the work is still
  cancelled.
- Cancel on a row that already SUCCEEDED or FAILED: the row reads exactly as it was (status, `finishedAt`,
  `error`, session id), with no stop frame. This is the brief's "does nothing to a row that is already …" clause.
- A worker whose row was cancelled before its turn ended stores nothing. Script an assistant reply with **no end
  state** and start the worker on its own coroutine, off the test thread, under the usual timeout. Wait (bounded)
  for the prompt frame to reach the fake, then cancel through the helper. This test needs WorkManager
  initialised, because the helper cancels the work too. The helper marks the row first and then stops the
  session; the fake's `completed` state_change from that stop ends the turn. The worker therefore reaches the
  store with a valid reply and a CANCELLED row. Assert that no outline or plan was written, the row is still
  CANCELLED, and the result is `Result.success()`. Do not assert a single stop frame here: the worker's own stop
  after the guard is a second one, and it is harmless.
- A superseded Flight Plan stores nothing, and the newer row still works. Seed an older row and a newer row for
  the same sortie, with different `createdAt` values. Run the older worker: no plan on the sortie, and the older
  row is CANCELLED. Then run the newer worker: the plan is stored and its row is SUCCEEDED. Script two turns, one
  per prompt.
- The same for an outline superseded by a newer outline for the same Mission: the older outline stores nothing
  and is CANCELLED, and the newer one stores the outline.
- The guard's negative control is already in the file: the happy-path tests store, and must stay green.

## Risks

- **Nested Room transactions.** `MissionManager.storeOutline`/`storePlan` already open their own
  `db.withTransaction`, so the worker's new transaction nests with them. Room's `withTransaction` joins an outer
  one called from the same coroutine. `storeOutline` reads DataStore settings before its own transaction; that
  read is not Room, so it is safe inside. If nesting fails anyway, move the currency check inside
  `MissionManager`'s own store transaction, rather than pulling the store out of it.
- **A Flow read inside that transaction.** `storeFinishedTurn` reads `equipmentDao().observeActive().first()`
  today, and a Room `Flow.first()` under Robolectric has deadlocked this suite before. Keep that read, and any
  other Room Flow read, outside the new transaction block.
- **Cancel racing a store.** A canceller that reads the status and then writes CANCELLED unconditionally
  (`markCancelled` has no status guard) could stamp CANCELLED over a row the worker just made SUCCEEDED. The
  canceller's read and mark share one transaction, and the stop happens only after that transaction commits.
- **The mid-turn cancel could lose the race.** The fake plays a scripted turn immediately, so a test that cancels
  from a racing coroutine can land after the store. A turn scripted with no end state can only be ended by the
  cancel's own stop, so the ordering is forced rather than probable. That stop ends the turn as `completed` (the
  fake's `rho.session.stop` pushes that state_change). This is the case the guard exists for: the worker reaches
  the store with a reply in hand, and the test asserts nothing was stored rather than treating the ended turn as a
  failure.
- **Which row is "newest".** `getNewestNonCancelledForSortie` orders by `createdAt DESC, id DESC`. Seed rows with
  different `createdAt` values rather than relying on the id tie-break, or the test proves nothing about
  supersede.
- **WorkManager in Robolectric.** The worker test's harness runs `doWork()` through `TestListenableWorkerBuilder`
  and never initialises WorkManager. A test that cancels work through the container needs
  `WorkManagerTestInitHelper.initializeTestWorkManager` per test and `closeWorkDatabase()` in teardown, as
  `GenerationEnqueuerTest` does.
- **Existing behaviour must not move.** The guard sits after validation and after the repair loop, so the repair
  cap, the resume path, the offline mark and the 8th-attempt give-up are untouched. The existing worker tests are
  the check that they stayed.
- **The gate can hang for about 5 minutes** on this machine (the brief says so, and it has happened). Run it
  detached with a log and an `EXIT=$?` sentinel, and re-run if the build times out with no test XML written.

## Decisions

**Does queueing a newer generation cancel the older one in this run?**
No. The enqueue helper still only schedules work for the row it is given. The older row becomes CANCELLED when
its own worker reaches the store check. The alternative is to have the enqueue helper cancel every older row for
the sortie as it queues, which would show the older row as cancelled the moment a newer one is asked for.
DESIGN §7.3 does say a regeneration cancels the old generation, but it puts that cancel in the regeneration
request, which is app wiring this run does not build. What §7.3 asks of the worker is "results are stored only if
the generation is still the sortie's current one". So this run ships the guard, which holds whatever calls it,
plus the cancel helper that the regeneration path will call. A user who queued a generation and then a newer one
sees the older row turn CANCELLED when its worker finishes, not immediately.

**What status does a superseded row get?**
CANCELLED, the status that already exists. A superseded row gets no new status and no new column: the brief says
"mark it CANCELLED if it isn't already", and there is no Room schema change anywhere in this run. A user who
queued a generation and then a newer one sees the older row as CANCELLED, which is what they would expect from a
row that will never produce a result.

**Does a cancel still work when Mission Control is offline?**
Yes, and it is local-first. The unique work is cancelled and the row is marked CANCELLED before any daemon call.
The session stop is attempted last, and its failure is swallowed. The alternative is to ask the coach to stop the
session first and cancel the row only if that succeeds. That would make a local cancel depend on the network and
leave the row open. The row being CANCELLED is the part the app acts on, so it must not wait on the daemon.

**What does cancelling a generation that already finished do?**
Nothing to the row: no session stop, and the stored result stays exactly as it is. The brief says the helper
"does nothing to a row that is already SUCCEEDED, FAILED or CANCELLED". Cancelling a result that already exists
would otherwise be a way to lose a generation the user can still read. The status check and the mark share one
transaction, so a worker that stores at the same moment also counts as "already finished".

**When the worker finds its generation is no longer current, what do the row and the retry look like?**
The worker stores nothing, marks the row CANCELLED if it is not already, and returns success, so WorkManager does
not retry it. It also tries to stop the rho session. A stop the daemon refuses (because the cancel helper already
stopped it) is swallowed, the same way the worker already swallows a refused session hand-back, so it can never
flip an outcome that is already decided. The alternative, returning a retry, would have a superseded worker keep
coming back for a row it is never allowed to write.

**Added by review: what does cancelling an id with no row do?**
It cancels `gen-<generationId>` and returns without throwing. With no row there is nothing to mark and no session
to stop. A caller cancelling a generation that was already deleted wants any work still queued for it gone, not
an error.
