# Plan

## Approach

The worker already has one permanent end — `failGeneration` — and every failure it can hit before a turn starts
already goes through it: the protocol mismatch, the Mission that no longer exists, the Sortie that no longer
exists, each with `client = null, sessionId = null` because none of them has opened a session to give up.

The fix is to make the two settings faults join them: replace the `else -> Result.retry()` arm at `GenerationWorker.kt:100` with named arms for `NotConfigured` and
`AuthenticationRejected`, each calling `failGeneration` with the sentence the brief names. Naming both arms
also makes the `when` exhaustive over the sealed `ConnectionOutcome`, so a future outcome is a compile error
rather than another silent retry. Nothing else is needed downstream: a FAILED row already carries its reason in
`error`, `CoachStatus`/`SortieGenerationStatus` already show that reason word for word and offer Retry, and the
sortie card's Re-fly rule is unchanged. No new DAO query, no new state, no attempt counter.

## Files

- `app/src/main/java/com/liftoff/app/work/GenerationWorker.kt` — the `else -> Result.retry()` arm at :100
  becomes named `NotConfigured` and `AuthenticationRejected` arms, each ending through `failGeneration`; the
  two reason sentences join the file's other row-wording constants next to `COACH_UNREACHABLE`.
- `app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt` — two new cases, one per outcome, beside the
  existing offline and mismatch cases; reuses that class's existing helpers (see the map).
- `app/src/main/java/com/liftoff/app/work/AppOpenSweep.kt` — one KDoc sentence ("a row asked for while Mission
  Control was configured is still resumed under the worker's offline handling when it isn't") is now wrong for
  a blank host or token and gets corrected. No behavior change in this file.
- `ARCHITECTURE.md` needs nothing: its `com.liftoff.app.work` row already says the worker ends the row FAILED
  with its reason. `DESIGN.md` is not touched — worker behavior changes here have amended ARCHITECTURE.md rows,
  not §7.3 (see the map), and the brief asks for code and tests.

## Order

1. The worker's two arms and the two reason constants. Build green; the existing suite still passes because
   nothing in it asserts the old `Result.retry()` for these two outcomes.
2. The two test cases, each proved to bite by one mutation of the arm it covers (revert that arm to
   `Result.retry()` and read the failing test's name), then the worker file restored.
3. `AppOpenSweep.kt`'s stale KDoc sentence.
4. The whole suite once, cold, and read its log — the offline and mismatch cases are the ones this change could
   disturb.

## Testing

Two worker cases in `GenerationWorkerTest`, both on the application's own container and both at WorkManager's
first attempt (`runAttemptCount = 0`), since the point is that these outcomes carry no counter at all:

- **Blank settings.** Host or token blanked in `settingsStore`, then `doWork()`: `Result.Failure`; the row
  FAILED with `error` exactly "Mission Control isn't set up", `finishedAt` stamped, `isCoachOffline` false,
  `nabuSessionId` null; nothing at all reached the fake daemon — not even the check's hello, because no socket
  was opened; nothing stored. Seed the row the way the unreachable path leaves it — RUNNING, with
  `Generation.COACH_OFFLINE` in `error` — so the case also shows the new reason taking the place of that text
  rather than sitting under it.
- **Refused token.** `FakeDaemon.refuseUpgrade = true` (the 401-at-the-upgrade recipe
  `DaemonConnectionCheckTest` already uses), then `doWork()`: `Result.Failure`; the row FAILED with `error`
  exactly "Mission Control refused the token", `finishedAt` stamped, nothing stored, and no `rho.session.stop`
  frame reached the daemon — the upgrade never completed, so there was no session to give up.
- Both cases reuse the class's existing permanent-end row assertion (the one the mismatch cases use) rather than
  restating it.
- No new UI or domain test: `CoachStatusTest` and `SortieGenerationStatusTest` already cover what a FAILED row's
  reason does to the card and to Retry/Re-fly, and this change only feeds a different sentence into that path.

## Risks

- **The `else` arm disappearing.** Naming both outcomes makes the `when` exhaustive; the work must not keep an
  `else` as a safety net, because that is what hid these two in the first place.
- **Disturbing the offline path.** `Unreachable` and its 8-attempt counter are untouched. The mutation check in
  step 2 reverts only the arm under test, and step 4 reads the three existing offline cases' results in the
  whole-suite log.
- **A row that already carries a rho session.** A token rotated mid-generation leaves the session open on the PC
  and this fix does not stop it — the daemon that refused the token would refuse the stop too, and rho archives
  untouched sessions on its own (DESIGN §7.3 step 6). The new cases assert nothing at all reached the daemon,
  because a refused upgrade never opened a session to stop.
- **Two similar sentences in two places.** The row's "Mission Control isn't set up" and Mission Control's own
  longer "…Add the host and token in Mission Control." are different messages for different screens; the work
  should not harmonise them.
- **Retry while the settings are still blank.** The card offers Retry, `MissionManager` refuses to queue, and the
  draft says which setting to fix. That is existing behavior with an existing test; the work must not "help" by
  queueing anyway.
- **The fake's refused upgrade must stay the 401 + `DISCONNECT_AT_END` response** `FakeDaemon` already returns;
  a different refusal mechanism risks the server-shutdown hang that comment exists to avoid.

## Decisions

**Give up on the first attempt, not after N.** The brief says these two outcomes must fail the generation, but
not whether the worker should retry a couple of times first. It chooses: a `NotConfigured` or
`AuthenticationRejected` connection check ends the generation immediately, with no counter and no offline mark.
The alternative is a small allowance, on the grounds that the user may be mid-edit in Mission Control's settings
while a generation is queued. Immediate failure is what a user would expect, because neither condition fixes
itself — a blanked host or a wrong token stays wrong until the settings screen is edited — and the card's Retry
button is the natural way to try again once it is fixed. Retrying only keeps the card on "Coach is planning…"
longer before it says the same thing.

**The card shows the brief's sentence, and it replaces whatever the row was saying.** "Mission Control isn't set
up" and "Mission Control refused the token" are stored as the generation's reason and shown as-is, the way any
other FAILED generation's reason is shown, in place of a "Mission Control offline" mark the row may have carried
from earlier attempts. The alternative is a generic "generation failed" with the specific text only in a log. The
specific text is what tells the user to go and look at their Mission Control settings, which is the point of the
fix.

**A generation that fails this way is finished, not paused.** It lands FAILED with its reason, so the card offers
Retry like any other failed generation, and WorkManager is not asked to run it again. The brief settles this, but
it is worth stating because it is the visible difference from today: today the row stays RUNNING and the card
never stops saying "Coach is planning…".

**The rho session the row may still carry is left alone.** A row that had a session from an earlier attempt and
then hit a refused token is failed without any `rho.session.stop` being attempted. The alternative is to try the
stop anyway. It is left alone because the daemon that refused the token would refuse the stop over the same
token, and rho archives sessions nobody touches on its own (DESIGN §7.3 step 6) — so the session is the daemon's
to clean up, which is how the worker already treats a stop the daemon refuses.
