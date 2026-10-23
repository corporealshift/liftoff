# Plan

## Approach

One rule, enforced from both sides: a generation that is no longer the one its target is waiting for may not
write. The cancel helper is DESIGN §7.3's mechanism for the regeneration path — cancel the unique work, cancel
the row, stop the rho session — built in `com.liftoff.app.work` next to `GenerationEnqueuer` and exposed from
`AppContainer` the way the enqueue helper is, so the app wiring that comes next has something to call. The
worker's guard is the same rule at the moment of writing: `storeFinishedTurn` already decides, in one place,
whether to store and mark SUCCEEDED, so the currency check goes there and nothing about the turn, the repairs,
the resume or the retry paths moves. The DAO reads the brief names (`markCancelled`,
`getNewestNonCancelledForSortie`, `getNewestNonCancelledOutlineForMission`) already exist and are already tested,
and `GenerationStatus.CANCELLED` already exists, so no Room schema change appears anywhere. See the map for what
each file holds today.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationCanceller.kt` (new) — the cancel helper: for a generation id,
  cancel `gen-<generationId>`, mark the row CANCELLED, then stop the row's session when the row has one and a
  client can be opened; a row already SUCCEEDED, FAILED or CANCELLED is left alone.
- `app/src/main/java/com/liftoff/app/work/GenerationEnqueuer.kt` — the `gen-<generationId>` name stops being
  private to this file, so the enqueuer and the canceller cannot spell the same work differently.
- `app/src/main/java/com/liftoff/app/AppContainer.kt` — a cancel accessor beside `generationEnqueuer()`, built
  per call from the container's own WorkManager, database and `daemonClient()`.
- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — the store guard in `storeFinishedTurn`: re-read
  the row and the newest non-cancelled generation for its sortie (Flight Plan) or Mission's outline (outline)
  inside one `database.withTransaction` together with the store and `markSucceeded`; the not-current path marks
  the row CANCELLED when it is not already, stops its session outside the transaction, and returns success.
- `app/src/test/java/com/liftoff/app/work/GenerationCancellerTest.kt` (new) — the cancel behaviour, on the
  application's container and `FakeDaemon`, with WorkManager initialised the way `GenerationEnqueuerTest` does.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — the three store-guard cases added to the
  existing harness.
- `ARCHITECTURE.md` — the M3 row (line 82), the only document the brief asks to change: the worker including
  resilience, cancel and supersede is built, app wiring comes next.

## Order

1. Share the unique work name, write `GenerationCanceller.kt` and the container accessor. Gate:
   `GenerationCancellerTest` — cancel with a session, cancel with the daemon unreachable, a finished row
   untouched.
2. Add the guard to `storeFinishedTurn` and the transaction around check + store + `markSucceeded`. Gate:
   `GenerationWorkerTest` — cancelled mid-turn, superseded Flight Plan (older then newer), superseded outline.
3. Run the existing worker and enqueuer tests unchanged, to show the guard did not move the happy path, the
   repair path or any retry path.
4. Update the M3 row of `ARCHITECTURE.md`, then run the whole gate
   (`:app:assembleDebug :app:testDebugUnitTest`) once, detached.

## Testing

Robolectric throughout, every wait bounded, on the application's own container plus `FakeDaemon` (the worker
test's harness; the map records its helpers).

- Cancel on a row with a session: the row reads CANCELLED with `finishedAt` stamped, exactly one
  `rho.session.stop` frame naming the session the row carried, and the work under `gen-<id>` reads CANCELLED
  rather than ENQUEUED.
- Cancel with the daemon unreachable (settings pointed at a loopback port that was opened and closed, as
  `pointSettingsAtNothing` does): the row still ends CANCELLED, the call does not throw, and the work is still
  cancelled.
- Cancel on a row that already SUCCEEDED or FAILED: the row reads exactly as it was — status, `finishedAt`,
  `error`, session id — with no stop frame and its work still scheduled. This is the brief's "does nothing to a
  row that is already …" clause.
- A worker whose row was cancelled before its turn ended stores nothing: script an assistant reply with **no end
  state**, wait for the prompt frame to reach the fake, then cancel through the helper; the fake's own
  `completed` state_change ends the turn, so the worker arrives at the store holding a valid reply and a
  CANCELLED row. Assert no outline or plan was written, the row still CANCELLED, and `Result.success()`.
- A superseded Flight Plan stores nothing, and the newer row still works: seed an older row and a newer row for
  the same sortie (`createdAt` differing), run the older worker → no plan on the sortie, older row CANCELLED;
  then run the newer worker → the plan is stored, its row SUCCEEDED. Two scripted turns, one per prompt.
- The same for an outline superseded by a newer outline for the same Mission: the older outline stores nothing
  and is CANCELLED, the newer one stores the outline.
- The guard's negative control is already in the file: the happy-path tests store, and must stay green.

## Risks

- **Nested Room transactions.** `MissionManager.storeOutline`/`storePlan` already open their own
  `db.withTransaction`, so the worker's new transaction nests with them, and Room joins a nested transaction only
  when it runs on the thread already holding it. The work puts check + store + `markSucceeded` in one
  `database.withTransaction` block and confirms the nesting joins; if Room rejects it, the currency check moves
  inside `MissionManager`'s own store transaction rather than the store being pulled out of it.
- **A Flow read inside that transaction.** `storeFinishedTurn` reads `equipmentDao().observeActive().first()`
  today, and a Room `Flow.first()` under Robolectric has deadlocked this suite before. Keep that read — and any
  other Flow read — outside the new transaction block.
- **The mid-turn cancel could lose the race.** The fake plays a scripted turn immediately, so a test that cancels
  from a racing coroutine can land after the store. Scripting the turn with no end state makes the cancel's own
  stop the only thing that can end it, so the ordering is forced rather than probable. That stop ends the turn as
  `completed` (map: `rho.session.stop` pushes that state_change), which is the case the guard exists for: the
  worker reaches the store with a reply in hand, and the test asserts nothing was stored rather than treating the
  ended turn as a failure.
- **Which row is "newest".** `getNewestNonCancelledForSortie` orders by `createdAt DESC, id DESC`; seed rows with
  `createdAt` values that differ rather than relying on the id tie-break, or the test proves nothing about
  supersede.
- **WorkManager in Robolectric.** The worker test's harness never initialises WorkManager; a test that cancels
  work through the container needs `WorkManagerTestInitHelper.initializeTestWorkManager` per test and
  `closeWorkDatabase()` in teardown, as `GenerationEnqueuerTest` does.
- **Existing behaviour must not move.** The guard sits after validation and after the repair loop, so the repair
  cap, the resume path, the offline mark and the 8th-attempt give-up are untouched; the existing worker tests are
  the check that they stayed.
- **The gate can hang for about 5 minutes** on this machine (the brief says so, and it has happened). Run it
  detached with a log and an `EXIT=$?` sentinel, and re-run when the build times out with no test XML written.

## Decisions

**Does queueing a newer generation cancel the older one in this run?**
No. The enqueue helper still only schedules work for the row it is given; the older row becomes CANCELLED when
its own worker reaches the store check. The alternative is to have the enqueue helper cancel every older row for
the sortie as it queues, which would show the older row as cancelled the moment a newer one is asked for.
DESIGN §7.3 does say a regeneration cancels the old generation — but it puts that cancel in the regeneration
request, which is app wiring this run does not build; what it asks the worker for is "results are stored only if
the generation is still the sortie's current one". So this run ships the guard, which holds whatever calls it,
plus the cancel helper that the regeneration path will call. A user who queued a generation and then queued a
newer one sees the older row turn CANCELLED when its worker finishes rather than immediately.

**What status does a superseded row get?**
CANCELLED, the status that already exists. A superseded row is given no new status and no new
column: the brief says "mark it CANCELLED if it isn't already", and there is no Room schema
change anywhere in this run. A user who queued a generation and then queued a newer one sees
the older row as CANCELLED, which is what they would expect from a row that will never produce
a result.

**Does a cancel still work when Mission Control is offline?**
Yes, and it is local-first: the row is marked CANCELLED and the unique work is cancelled
before any daemon call, and the session stop is attempted last with its failure swallowed.
The alternative is to ask the coach to stop the session first and only cancel the row if that
succeeds, which would make a local cancel depend on the network and leave the row open. The
row being CANCELLED is the part the app acts on, so it must not wait on the daemon.

**What does cancelling a generation that already finished do?**
Nothing at all — no work cancel, no session stop, and the stored result stays exactly as it
is. The brief says the helper "does nothing to a row that is already SUCCEEDED, FAILED or
CANCELLED". Cancelling a result that already exists would otherwise be a way to lose a
generation the user can still read.

**When the worker finds its generation is no longer current, what does the row and the retry
look like?**
The worker stores nothing, marks the row CANCELLED if it is not already, and returns success,
so WorkManager does not retry it. It also attempts to stop the rho session, and a stop the
daemon refuses (because the cancel helper already stopped it) is swallowed the way the worker
already swallows a refused session hand-back, so it can never flip an outcome that is already
decided. The alternative — returning a retry — would have a superseded worker keep coming back
for a row it is never allowed to write.
