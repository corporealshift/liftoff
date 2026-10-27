# Plan

## Approach

One new class in `com.liftoff.app.work` reads every QUEUED or RUNNING `Generation` row, groups the rows by Mission, and asks of each row whether what it was asked for is still what the app is pointing at. An outline is wanted while its Mission is the current week's DRAFT. A Flight Plan is wanted while its Mission is ACTIVE and in the current week, and its sortie is still the Mission's `currentSortie` (the domain function in `domain/Sorties.kt`) and still PENDING or PLANNED. "Current week" is the `weekStart` of the Mission that `onAppOpen()` returns, and the caller passes it in. Wanted ids go to an enqueue lambda and unwanted ids to a cancel lambda. Both are constructor parameters, `(Long) -> Unit` and `suspend (Long) -> Unit`, the same shapes `LaunchpadViewModel` already takes. That way the whole decision can be tested against in-memory Room with recording lambdas and no WorkManager, as the brief asks. `MainActivity` calls the sweep after `onAppOpen()` returns, on a background dispatcher. It hands over the container's enqueue and cancel helpers, built inside the lambda bodies the way `LaunchpadScreen` and `InFlightScreen` do. The three conditions are the ones `MissionManager.storePlan` and `storeOutline` already enforce before they store a result. So the sweep re-checks the app's existing authority over what a generation may still write; it is not a new rulebook. The enqueuer uses unique work `gen-<id>` with `KEEP`, and the canceller only marks a row that is still open, so the sweep is safe to run again on every app open.

## Files

- `app/src/main/java/com/liftoff/app/work/AppOpenSweep.kt` (new): the sweep. It reads the open rows and their Missions and sorties, decides each row, calls the two lambdas, and swallows any failure per row except cancellation.
- `app/src/main/java/com/liftoff/app/data/GenerationDao.kt`: one added `@Query` for every QUEUED or RUNNING row in the table. Both existing open queries are keyed to one Mission or one sortie, so neither can find the rows the sweep has to look at. No schema change.
- `app/src/main/java/com/liftoff/app/MainActivity.kt`: call the sweep after `onAppOpen()` returns, off the main thread, with the returned Mission's `weekStart` and the container's enqueue and cancel lambdas.
- `app/src/test/java/com/liftoff/app/work/AppOpenSweepTest.kt` (new): the six behaviors below plus the throwing-lambda case, on the `MissionManagerTest` fixture (in-memory Room, fixed `Clock`, recording lambdas, no WorkManager).
- `ARCHITECTURE.md`: the `com.liftoff.app.work` row (line 36) names the sweep file and what it does.

## Order

1. Add the DAO query and the sweep class. `bash gradlew.sh :app:compileDebugKotlin` passes.
2. Write `AppOpenSweepTest` against in-memory Room with recording lambdas and run it class-scoped. The six behaviors pass with no WorkManager anywhere in the test.
3. Wire `MainActivity` to run the sweep after `onAppOpen()`, launched on `lifecycleScope` with a background dispatcher rather than inside the `repeatOnLifecycle` block (see Decisions). Run `LiftoffShellTest`, `ShellNavTest` and `MainActivityTest` to show the app-open path still works with no WorkManager set up.
4. Name the sweep in the ARCHITECTURE.md `work` row.
5. Run the whole gate, `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest`, detached. Re-run it if it hangs for about five minutes without writing any test XML.

## Testing

All six in `AppOpenSweepTest` (Robolectric `@Config(sdk = [34])`, in-memory Room, recording enqueue and cancel lambdas, no WorkManager, every wait bounded). Each one asserts the exact ids each lambda was handed, not just that a lambda fired.

- A QUEUED Flight Plan row for the current week's ACTIVE Mission's current PENDING sortie: enqueued, not cancelled.
- A QUEUED outline row for the current week's DRAFT Mission: enqueued, not cancelled.
- After `onAppOpen()` runs the week-ended rollover on a clock one week later: the scrubbed sortie's open Flight Plan row is cancelled, and so is a past DRAFT Mission's open outline row. Nothing is enqueued.
- Rows whose sortie is no longer `currentSortie`, one scrubbed and one landed, with a later sortie now current: both cancelled.
- SUCCEEDED, FAILED and CANCELLED rows in the same table are left alone: neither lambda is handed their ids.
- An empty generation table: neither lambda is called at all. This is what keeps the sweep away from WorkManager in the shell tests, which run `MainActivity.onCreate` on a database with no open rows.

## Risks

- `LiftoffShellTest` runs `MainActivity.onCreate` with no WorkManager initialized. The container builds WorkManager inside `generationEnqueuer()`/`generationCanceller()`. So the wiring must keep the helper construction inside the lambda bodies, and the sweep must not call a lambda when there is nothing to act on. The empty-table test pins this.
- A cancel against an unreachable daemon can block for the client's 15-second connect timeout per row (`rho/net/DaemonClient.kt:115`). The sweep runs off the main thread and nothing awaits it, so a slow Mission Control cannot stall the UI. The canceller already swallows a daemon that isn't there.
- `repeatOnLifecycle(STARTED)` re-runs on every return to the foreground, so sweeps can overlap or repeat. This is harmless by construction: `KEEP` means re-enqueuing live work creates nothing, and the canceller only marks a row that is still QUEUED or RUNNING. No single-flight guard is planned.
- The sweep must judge "current week" against the week the rollover just used. So the caller passes the `weekStart` of the Mission `onAppOpen()` returned, and the sweep does not work it out again. Otherwise a row could be judged against a different week from the one that scrubbed its sortie.
- Read the open rows, their Missions and their sorties in one Room read transaction, then call the lambdas outside it. That gives every decision one consistent snapshot, and no WorkManager call or daemon stop runs inside a transaction.
- A row whose Mission or sortie no longer exists, or a FLIGHT_PLAN row with a null `sortieId`, has to be decided, not skipped. See the Decisions section.
- One bad row must not stop the rest. Each row's enqueue or cancel is wrapped, swallowing everything except `CancellationException`, which is rethrown so a cancelled coroutine still cancels. The tests should prove this with a recording lambda that throws on the first id and still receives the second, because the six named tests cannot show this rule.
- The reads before the decisions can fail too. The whole sweep is wrapped the same way, so a database that is gone or a query that throws ends the sweep quietly instead of crashing the app. Per-row wrapping alone does not cover that.
- `onAppOpen()` has other callers: `LaunchpadViewModel` before nearly every action, and `DatabaseImport` after a restore. The brief asks for the app-open sweep, so it is wired only at `MainActivity`'s hook. An imported database is swept the next time the app comes to the foreground, not at the moment of the import.
- The full gate can hang about five minutes on this machine without writing any `TEST-*.xml`. Run it detached with the log-and-exit sentinel, and re-run the identical tree once if that happens.

## Decisions

**Added by review: The sweep runs on `lifecycleScope`, not inside the `STARTED` block.** `MainActivity` launches it on `lifecycleScope` with a background dispatcher once `onAppOpen()` has returned, instead of as a child of the `repeatOnLifecycle` block. The alternative ties it to `STARTED`, so leaving the app mid-sweep cancels it. Users would expect a repair that has started to finish even if they switch away straight after opening the app. Otherwise a cancel can be cut off between stopping its work and marking its row. Overlap with the next foreground's sweep is harmless (see Risks).

**Added by review: Wanted rows are enqueued even when Mission Control has no host or token now.** The sweep does not look at settings. A row the user asked for while the coach was configured is resumed under the worker's existing offline handling: the row is marked `Mission Control offline`, the mark clears once settings return, and the row fails `coach unreachable` after its attempts run out. The alternative is to skip or cancel such rows. Users would expect clearing a setting by mistake not to throw away a request, and the worker already shows the row's state to the user.

**The sweep is silent.** It resumes and cancels generations but tells the user nothing: no message, no snackbar, no count. The alternative is a line like "Resumed 2 coach generations". Users would expect silence because the enqueue and cancel paths already say nothing when a turn starts or stops, and the brief swallows every per-row failure, so a sweep that reported successes but not failures would read as arbitrary. The effect shows where it already does: the Launchpad draft status and the sortie's plan change.

**It runs on every return to the foreground, not once per process launch.** `onAppOpen()` is already inside `repeatOnLifecycle(STARTED)`, so it re-runs each time the app comes back, and the sweep follows it. The alternative is a once-per-process flag. Users would expect re-running because what is being repaired (a lost enqueue, a stale row) can happen at any time the app is in the background. Re-running is also harmless: unique work with KEEP makes a re-enqueue a no-op, and the canceller only touches rows that are still open.

**A row whose Mission or sortie has gone away is cancelled, not left alone.** If a row's `missionId` or `sortieId` matches nothing, for example after an import replaced the database or after a sortie row was removed, the sweep treats it as unwanted. The alternative, the narrower reading of the brief's rules, is to skip rows it cannot resolve. Leaving them is worse for the user: an unresolvable row stays QUEUED or RUNNING forever, keeps its unique WorkManager slot, and keeps showing a "generating" state that will never finish.

**A generation for a sortie that has left PENDING/PLANNED is cancelled even if it is mid-turn.** The brief's rule is explicit that a Flight Plan row is wanted only while its sortie is PENDING or PLANNED. So an open row for an IN_FLIGHT, LANDED or SCRUBBED sortie is cancelled, and the canceller also stops its rho session. The alternative would be to let a live turn finish. Cancelling is what the rule asks for and what the user would expect: the plan being written is for a sortie that is no longer the one being flown, so a finished result would be a plan nobody acts on.

**A wanted row is resumed under its own row, not cancelled and replaced.** A RUNNING row the sweep still wants is handed to the enqueuer, which keeps the row and the rho session it already holds. If its work is still scheduled, `KEEP` means nothing new is created and a turn in progress is not disturbed. The alternative is to cancel the row and queue a fresh generation, which is what a naive sweep does to a row it thinks is stale. Users would expect resuming because §7.3 says a worker that is killed and restarted continues the same session from the row's stored cursor, and a second generation would ask Mission Control for a second plan for the same sortie.

**The sweep only acts on generations that already exist.** It never queues a generation the app did not ask for. A run sortie whose Generate-run-plans toggle is off, or a week with no Mission Control host and token, gets no new row from an app open. The alternative is to re-run the week's preparation on every open, which would close the gap the brief names more broadly, but would have the app ask the coach for something nobody asked for. The user can tell the difference: opening the app after a kill resumes what they had already asked for, and nothing else appears.
