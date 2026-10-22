# Tasks — Coach turn runner over the copied rho client

Five tasks, in `plan.md`'s order. Each ends in one commit, `area: lowercase summary`, named files only, LF endings.
`.rho/runs/coach-turn-runner-over-the-copied-rho-4bvb/map.md` records the fake's API, the copied client's shapes and the
current wording of the two ARCHITECTURE.md rows, so read it before hunting for them in the code.
Every test name below is spelled exactly as `.rho/runs/coach-turn-runner-over-the-copied-rho-4bvb/verify.sh` names it;
each is written by exactly one task. All nine live in the one new class
`app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt`, so tasks 3 and 4 must read what the earlier tasks put
there before adding to it.

- [x] 1. Bring in the finished scripted fake daemon
  Covers: `git cherry-pick 6b1063e` on this branch, keeping its message. The source branch
  `rho/generationworker-core-flow-j5ca` is checked out in another runner worktree, so do not check it out or merge it —
  pick by sha. No hand edits to `app/src/test/java/com/liftoff/app/rho/FakeDaemon.kt`, and no file from
  `.rho/runs/generationworker-core-flow-j5ca` (that branch also holds `0a2811c run: task 1 done`, which must not
  arrive). Nothing else changes in this commit.
  Done when: `git show --stat HEAD` names only `FakeDaemon.kt`; `git log --oneline --
  .rho/runs/generationworker-core-flow-j5ca` is empty and `git ls-tree -r --name-only HEAD --` that path is empty; and
  a cold run of the two classes the brief says the pick must leave green reports no failures:
  `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests 'com.liftoff.app.rho.DaemonConnectionCheckTest'
  --tests 'com.liftoff.app.AppContainerTest'` — read `tests=` / `failures=` / `errors=` from
  `app/build/test-results/testDebugUnitTest/TEST-*.xml` (7 and 8 tests), not from stdout.
  Tests written: none. `DaemonConnectionCheckTest` and `AppContainerTest` already exist and are checked as whole
  classes by verify.sh; this task only has to leave them green.

- [x] 2. The turn runner, and the two cases that prove it works end to end
  Covers: the new file `app/src/main/java/com/liftoff/app/work/CoachTurnRunner.kt`, which creates the
  `com.liftoff.app.work` package. It holds the runner as a suspend function over a `DaemonClient` the caller already
  connected, the value it returns (session id, final cursor, how the turn ended, last assistant text or null), the six
  end-reason values (idle, completed, blocked, paused, error, timed out), and the `rho.session.stop` helper — the
  helper is written here even though its test comes in task 4. No Android imports, no Room, no WorkManager, and it
  never closes the client it was handed. Order matters: start collecting `incoming` into a Channel before subscribing
  or prompting (the flow has `replay = 0`), then create the session only when none was handed in and report the id
  through the callback either way, subscribe, send the prompt, then judge pushed events and the one catch-up
  `events_after` against the prompt's `event_id` through the same handling, skipping ids already seen. The cursor moves
  only forward, by string comparison of ids, and the cursor callback fires only on a forward move. The turn ends at the
  first `state_change` with an id greater than the prompt's whose `to` is one of the five daemon targets; the whole
  limit is measured from the call and returns `timed out` rather than letting the wait escape. A `DaemonException`
  from any call goes out unchanged except `CURSOR_UNKNOWN` from `events_after`, which is handled by refetching from
  null. Also write the new test class `app/src/test/java/com/liftoff/app/work/CoachTurnRunnerTest.kt` — plain JUnit 4
  with `runBlocking` and `withTimeout`, against `FakeDaemon` on a real localhost socket, no Robolectric and no virtual
  time, every wait bounded far below the build's 5-minute test-task timeout.
  Tests: `aFreshTurnCreatesOneSessionAndReturnsItsReplyAndCursor`,
  `aSessionAndCursorHandedInIsUsedWithoutCreatingAnother`. For the second, the cursor handed in has to be one the
  fake's own log knows, so seed it with `logEvent` and use the id it returns; the runner then prompts on the session id
  it was given and asks only for what came after that cursor, with no `rho.session.create` frame in `frames` at all.
  Done when: `bash gradlew.sh :app:compileDebugKotlin` passes, and a cold run of just this class
  (`bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests 'com.liftoff.app.work.CoachTurnRunnerTest'`)
  writes a `TEST-*.xml` whose `tests=` counts both named methods and whose `failures=` and `errors=` are 0.

- [x] 3. The turn boundary, and the two kinds of daemon request the runner must answer
  Covers: three more tests in the same class, no new production file. A `state_change` to `idle` seeded with
  `logEvent` before the prompt must not end the turn — the runner still reaches the scripted end and reports its
  reply, because the end check compares against the prompt's own `event_id` and not against "the next event". Note how
  that seeded event reaches the runner at all: `logEvent` pushes nothing (only sockets that called
  `rho.session.subscribe` get pushes), so the old idle arrives through the catch-up `events_after` when the runner has
  no cursor to hand it. A scripted `DaemonScript.Ask` is answered with
  `{"answer": "Decide yourself; there is nobody to ask."}` and a scripted `DaemonScript.Permission` is answered
  `{"verdict": "deny"}`; both answers are read back out of the fake's `answers` map, keyed by the fake's own request id
  (`srv-1`, …), and each turn still ends `idle` with its reply — the fake holds the rest of the turn back until the
  client answers, so an unanswered question is a turn that never ends. Keep waiting after both answers.
  Tests: `aStateChangeLoggedBeforeThePromptDoesNotEndTheTurn`,
  `aDaemonQuestionIsAnsweredWithTheFixedSentenceAndTheTurnStillEndsIdle`,
  `aPermissionRequestIsAnsweredDenyAndTheTurnStillEndsIdle`.
  Done when: a cold run of `com.liftoff.app.work.CoachTurnRunnerTest` reports every test in it passing
  (`failures=` and `errors=` are 0) and its XML contains all five method names written so far.

- [x] 4. The ends the daemon reports, the limit, and the stop helper
  Covers: four more tests in the same class. A turn scripted to end `blocked`, and one scripted to end `paused`, each
  report that end and not `idle` or a timeout. A turn with no end inside a short limit (the limit is a parameter; the
  test sets it to a fraction of a second) reports `timed out`, and the cursor that comes back is the furthest event
  actually read, not the one handed in — script that turn with a `DaemonScript.Assistant` and no `DaemonScript.State`,
  so there is no end to find. The stop helper sends exactly one `rho.session.stop` frame carrying the session id it
  was given — read it back from the fake's `framesFor("rho.session.stop")`; the runner itself never stops a session,
  not even on a timeout. Touch `CoachTurnRunner.kt` only if the helper or an end reason is missing.
  Tests: `aTurnThatEndsBlockedReportsBlocked`, `aTurnThatEndsPausedReportsPaused`,
  `aTurnWithNoEndInsideItsLimitReportsTimedOut`, `theStopHelperSendsOneSessionStopFrame`.
  Done when: a cold run of `com.liftoff.app.work.CoachTurnRunnerTest` reports all nine named methods present with
  `failures=` and `errors=` 0, and `bash gradlew.sh :app:testDebugUnitTest --no-build-cache --tests
  'com.liftoff.app.rho.DaemonConnectionCheckTest' --tests 'com.liftoff.app.AppContainerTest'` is still green.

- [x] 5. Name it in the area map, then run the whole gate
  Covers: `ARCHITECTURE.md` line 36, the `com.liftoff.app.work` row — change only its "Exists yet?" cell (currently
  `—`) so it names the turn runner and says it uses neither Room nor WorkManager; the Responsibility cell and the M3
  milestone row stay byte-identical. Then run the whole gate detached, as the brief's Done-when and verify.sh both do:
  `mkdir -p app/build` first, `bash gradlew.sh --stop`, then
  `timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache` writing a log under
  `app/build/` and an `EXIT=$?` sentinel that is not behind a pipe. List every `TEST-*.xml` in one pass to show the
  whole suite ran, and record the report totals in the commit body. If it hits the known ~5-minute hang, re-run it
  rather than investigate; if a stranded executor is holding `app/build/test-results`, `bash gradlew.sh --stop` and
  delete that directory first. Change no test to make the suite pass.
  Tests written: none.
  Done when: `grep -m1 -E '^\|[^|]*com\.liftoff\.app\.work' ARCHITECTURE.md` has a third cell (`awk -F'|' '{print $4}'`)
  that is no longer `—` and matches both `turn` and `runner` case-insensitively; the `^\| M3 \|` row is byte-identical
  to the same row in `1976682` (read with `git cat-file blob 1976682:ARCHITECTURE.md`); and the detached gate log ends
  `EXIT=0` with a report for every test class, including `CoachTurnRunnerTest` with nine passing tests,
  `DaemonConnectionCheckTest` (7) and `AppContainerTest` (8).

## Blockers from the final review

- [x] Stop the turn at the first ending state_change, even inside the catch-up batch
  CoachTurnRunner.kt:124-128 and :159. `handle` sets `end = target` for every ending state_change after the prompt and never checks whether `end` is already set. The catch-up loop `for (event in eventsAfter(...)) handle(event)` also keeps going after an end has been found. So if one events_after page holds two ending state_changes after the prompt (say blocked and then idle, or error and then idle), the runner reports the last one. The brief says the turn ends at the *first* such state_change. The pushed path already stops at the first, because its `while (end == null)` loop exits. That means the same log gives a different end reason depending on whether the events arrived as pushes or through catch-up. Fix: only set `end` while it is still null, and stop handling catch-up events once an end is found. That also keeps `lastAssistantText` from picking up a message from after the end. Test in CoachTurnRunnerTest: use `logEvent` to put a prompt-later blocked and then an idle in the log so they come back in the catch-up read, and assert the outcome is `Blocked`. One way to set this up is to pass a handed-in session and cursor with a repeated client_id, so `send_prompt` returns the original event_id.
- [ ] A connection that drops mid-turn must throw, not wait out the limit and report TimedOut
  CoachTurnRunner.kt:165. After the catch-up read, the runner waits only on `pushed.receive()`. `DaemonClient.fail()` completes `closed` and fails pending calls, but `incoming` never completes. So if the socket dies while the runner waits for the turn to end (a phone losing wifi mid-turn), nothing notices. The runner sits out the whole turn limit and returns `TurnEnd.TimedOut`. The plan's Decisions say a call that never got an answer is the connection failing, not a turn ending: 'nothing comes back, and the caller resumes from what it already stored'. They also name the cost of getting this wrong: the worker would treat a dead daemon as a finished turn and spend its repair budget on it. This code produces exactly that outcome. Fix: race the wait against `client.awaitClosed()` and throw a `DaemonException` (no code) when the connection closes first. The cursor has already gone out through `onCursor`. Test in CoachTurnRunnerTest: script an Assistant with no end, give the runner a long limit, and stop the fake (or close the client) once the reply's cursor has been reported. Assert that `runCoachTurn` throws `DaemonException` well before the limit.
