# Plan

## Approach

One new class in `com.liftoff.app.work` that reads every QUEUED or RUNNING `Generation` row, groups the rows by
Mission, and for each row asks whether the thing it was asked for is still the thing the app is pointing at: an
outline while its Mission is the current week's DRAFT, a Flight Plan while its Mission is ACTIVE and in the
current week and its sortie is still the Mission's `currentSortie` and still PENDING or PLANNED. Wanted ids go
to an enqueue lambda, unwanted ids to a cancel lambda, both constructor parameters in the same shape
`LaunchpadViewModel` and `InFlightViewModel` already take — `(Long) -> Unit` and `suspend (Long) -> Unit` — so
the whole decision can be tested against in-memory Room with recording lambdas and no WorkManager, which is what
the brief asks for. `MainActivity` calls it after `onAppOpen()` returns, on a background dispatcher, handing it
the container's enqueue and cancel helpers built inside the lambda bodies the way `LaunchpadScreen` and
`InFlightScreen` do. The three conditions are
the ones `MissionManager.storePlan` already enforces before it will store a plan, so the sweep is a re-check of
the app's existing authority over what a generation may still write, not a new rulebook; and because the
enqueuer uses unique work `gen-<id>` with `KEEP` and the canceller only marks a row that is still open, the sweep
is safe to run again on every app open.

## Files

- `app/src/main/java/com/liftoff/app/work/AppOpenSweep.kt` (new) — the sweep: reads the open rows, decides each
  one against its Mission and sortie, calls the two lambdas, swallows any failure per row except cancellation.
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt` — one added `@Query` for every QUEUED or RUNNING row
  across the table; the two open queries that exist are both keyed to one Mission or one sortie, so neither can
  find the rows the sweep has to look at. No schema change.
- `app/src/main/java/com/liftoff/app/MainActivity.kt` — call the sweep after `onAppOpen()` returns, off the main
  thread, with the container's enqueue and cancel lambdas.
- `app/src/test/java/com/liftoff/app/work/AppOpenSweepTest.kt` (new) — the six behaviors below, on the
  `MissionManagerTest` fixture (in-memory Room, fixed `Clock`, recording lambdas, no WorkManager).
- `ARCHITECTURE.md` — the `com.liftoff.app.work` row (line 36) names the sweep file and what it does.

## Order

1. Add the DAO query and the sweep class; `bash gradlew.sh :app:compileDebugKotlin` passes.
2. Write `AppOpenSweepTest` against in-memory Room with recording lambdas and run it class-scoped; the six
   behaviors pass with no WorkManager anywhere in the test.
3. Wire `MainActivity` to run the sweep after `onAppOpen()` on a background dispatcher; run
   `LiftoffShellTest` and `MainActivityTest` to show the app-open path still works with no WorkManager set up.
4. Name the sweep in the ARCHITECTURE.md `work` row.
5. Run the whole gate — `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` — detached, and re-run it if
   it hangs for about five minutes without writing any test XML.

## Testing

All six in `AppOpenSweepTest` (Robolectric `@Config(sdk = [34])`, in-memory Room, recording enqueue and cancel
lambdas, no WorkManager, every wait bounded). Each one asserts the exact ids each lambda was handed, not just
that a lambda fired.

- A QUEUED Flight Plan row for the current week's ACTIVE Mission's current PENDING sortie: enqueued, not cancelled.
- A QUEUED outline row for the current week's DRAFT Mission: enqueued, not cancelled.
- After `onAppOpen()` runs the week-ended rollover on a clock one week later: the scrubbed sortie's open Flight
  Plan row is cancelled, and a past DRAFT Mission's open outline row is cancelled; nothing is enqueued.
- A row whose sortie is no longer `currentSortie` — one scrubbed and one landed, with a later sortie now current:
  both cancelled.
- SUCCEEDED, FAILED and CANCELLED rows in the same table are left alone: neither lambda is handed their ids.
- An empty generation table: neither lambda is called at all. That is the reason the sweep stays out of
  WorkManager in the shell tests, which run `MainActivity.onCreate` on a database with no open rows.

## Risks

- `LiftoffShellTest` runs `MainActivity.onCreate` with no WorkManager initialized. The container builds
  WorkManager inside `generationEnqueuer()`/`generationCanceller()`, so the wiring must keep the helper
  construction inside the lambda bodies and the sweep must not call a lambda when there is nothing to act on.
  The empty-table test is what pins that.
- A cancel against an unreachable daemon blocks for the client's 15-second connect timeout per row
  (`rho/net/DaemonClient.kt:115`). The sweep runs off the main thread and `MainActivity` does not await it, so a
  slow Mission Control cannot stall the UI; the canceller already swallows a daemon that isn't there.
- `repeatOnLifecycle(STARTED)` re-runs on every return to the foreground, so sweeps can overlap or repeat. That
  is harmless by construction: `KEEP` makes a re-enqueue of live work create nothing, and the canceller only
  marks a row that is still QUEUED or RUNNING. No single-flight guard is planned.
- The sweep must judge "current week" against the week the rollover just used, so the caller passes the week
  `onAppOpen()` opened rather than the sweep working it out again; otherwise a row could be judged against a
  different week from the one that scrubbed its sortie.
- A row whose Mission or sortie no longer exists has to be decided, not skipped — see the Decisions section.
- One bad row must not stop the rest: each row's enqueue and cancel is wrapped, swallowing everything except
  `CancellationException`, which is rethrown so a cancelled coroutine still cancels. The work should prove this
  with a recording lambda that throws, since the brief's rule is invisible from the six named tests.
- The reads before the deciding can fail too — the whole sweep is wrapped the same way, so a database that is
  gone or a query that throws ends the sweep quietly rather than crashing the app. Per-row wrapping alone does
  not cover that.
- `onAppOpen()` has other callers — `LaunchpadViewModel` before nearly every action, and `DatabaseImport` after a
  restore. The brief asks for the app-open sweep, so it is wired only at `MainActivity`'s hook; an imported
  database is swept the next time the app comes to the foreground, not at the moment of the import.
- The full gate can hang about five minutes on this machine without writing any `TEST-*.xml`. Run it detached
  with the log-and-exit sentinel, and re-run the identical tree once if that happens.

## Decisions

**The sweep is silent.** It resumes and cancels generations but tells the user nothing: no message, no snackbar, no count. The alternative is a line like "Resumed 2 coach generations". The choice is what users would expect because the enqueue and cancel paths already say nothing when a turn starts or stops, and the brief swallows every per-row failure — a sweep that reports successes but not failures would read as arbitrary. The effect is visible where it already is: the Launchpad draft status and the sortie's plan change.

**It runs on every return to the foreground, not once per process launch.** `onAppOpen()` is already inside `repeatOnLifecycle(STARTED)`, so it re-runs each time the app comes back; the sweep follows it. The alternative is a once-per-process flag. Re-running is what users would expect because the thing being repaired (a lost enqueue, a stale row) can happen at any time the app is not in the foreground, and re-running is harmless: unique work with KEEP makes a re-enqueue a no-op, and the canceller only touches rows that are still open.

**A row whose Mission or sortie has gone away is cancelled, not left alone.** If a row's `missionId` or `sortieId` matches nothing — after an import replaced the database, or after a sortie row was removed — the sweep treats it as unwanted. The alternative is to skip rows it cannot resolve, which is the narrower reading of the brief's rules. Leaving them is worse for the user: an unresolvable row stays QUEUED or RUNNING forever, keeps its unique WorkManager slot, and keeps drawing a "generating" state that will never finish.

**A generation for a sortie that has left PENDING/PLANNED is cancelled even if it is mid-turn.** The brief's rule is explicit that a Flight Plan row is wanted only while its sortie is PENDING or PLANNED, so an open row for an IN_FLIGHT, LANDED or SCRUBBED sortie is cancelled, and the canceller also stops its rho session. The alternative would be to let a live turn finish. Cancelling is what the rule asks for and what the user would expect: the plan it is writing is for a sortie that is no longer the one being flown, so a finished result would be a plan nobody acts on.

**A wanted row is resumed under its own row, not cancelled and replaced.** A RUNNING row the sweep still wants is handed to the enqueuer, which keeps the row and the rho session it already holds; if the work for it is still scheduled, `KEEP` means nothing new is created and a turn in progress is not disturbed. The alternative — cancel it and queue a fresh generation — is what a naive sweep does to a row it thinks is stale. Resuming is what the user would expect because §7.3 says a worker that is killed and restarted continues the same session from the row's stored cursor, and a second generation would ask Mission Control for a second plan for the same sortie.

**The sweep only acts on generations that already exist.** It never queues a generation the app did not ask for: a run sortie whose Generate-run-plans toggle is off, or a week with no Mission Control host and token, gets no new row from an app open. The alternative is to re-run the week's preparation on every open, which would close the gap the brief names more broadly but would have the app ask the coach for something nobody asked for. The user can tell the difference: opening the app after a kill resumes what they had already requested, and nothing else appears.
