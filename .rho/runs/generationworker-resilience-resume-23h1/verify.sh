#!/usr/bin/env bash
# Verify: "GenerationWorker resilience: resume, reconnect and offline" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and
# the brief's Done-when names the same two tasks), then every named test read out of the JUnit XML reports that
# run produced, then the test classes the brief's "Must not break" list names, then the Room schema the brief says
# not to change.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all
# eight named tests are new to this run, and none of them exists in the tree today. Names are matched across every
# report, so which file each test sits in is the implementer's call; what it must show is written in the comment
# above it.
#
# The brief's test list has five bullets and they map one-to-one onto the first seven names: paused is one; the
# resume bullet is two (the stored session and cursor the brief names, and the case where the cursor is past our
# own prompt); the re-sent prompt is one; the offline bullet is two (the mark and the retry, then the mark cleared
# by a later run); the 8th attempt is one. The brief's "Add a small public helper" ask is the eighth name, run
# against a real database rather than through the worker.
#
# What is NOT checked here, and why:
# - "a row that already has nabuSessionId and lastEventId reuses that session, never creates another." That is
#   what the existing aGenerationRowWithASessionIdAlreadyStoredIsResumedWithoutCreatingAnotherSession already
#   shows, and the class check below requires it to still pass. The new name it sits next to is the half this run
#   adds: the fake's log already holding this row's own prompt.
# - A daemon that is not configured, or one that rejects the token, keeping today's plain retry with no offline
#   mark. The plan's Decisions says so, but the brief names only Unreachable and a failed connect, and its test
#   list names no case for those two outcomes. Decisions are reviewed in the pull request.
# - A daemon that refuses the resume a paused turn sends. The plan's Decisions says the refusal is swallowed and
#   the worker still retries, which is the same call the worker already makes about a refused stop. The brief's
#   test list names no case for it, and the paused name below already requires the resume to be sent.
# - The DAO query that writes the mark on its own, which is how the mark is written with no schema change. That is
#   how it is built; the two closed-port names below show the mark appear on a waiting row and clear on a row that
#   reached the daemon, and the schema check shows nothing about the table changed.
# - A client.connect() that throws after the connection check answered Connected. The brief's own test list gives
#   the way to make the daemon unreachable — a stopped fake or a closed port — and the plan's Risks arranges
#   unreachability before the worker runs rather than mid-turn. Nothing on the wire or in the row tells the two
#   apart; the closed-port names below are the proof of the mark and the retry.
# - Whether the runner's second catch-up read is a second call, a loop, or a rewind of the same one, and whether
#   the resume frame goes out before or after the row is written. Those are how it is built. What the named tests
#   assert is the row's status, error, cursor and attempt, and the frames the daemon received.
# - "The second read must not move the cursor backwards" as a rule of its own. It is visible in the resume names
#   as the cursor ending past where the row stored it, which is asserted there.
# - The 20-minute turn limit, WorkManager's own retry policy and backoff, and WorkManager's stop of a worker. A
#   limit that long cannot be waited out in a test; the plan's Risks keeps every case inside the class's own
#   30-second wait. A WorkManager stop is a retry that resumes the same row, which the re-sent-prompt name covers.
# - Any UI. The brief says the helper is for a later Launchpad run, and names no screen, so no UI test is named.
#   Generation.isCoachOffline is called directly, as that later run would call it, in the name below.
# - "Every wait has a timeout and nothing depends on timing (CI is ubuntu-latest, UTC)". CI runs exactly the gate
#   this script runs, and the build kills a test task that goes five minutes without finishing, writing no
#   reports — which this script treats as a failure. Forcing UTC on this machine was tried in an earlier run and
#   does not work: the pinned JDK ignores TZ.
# - The Room schema's shape beyond "unchanged": no entity change and no migration are checked by comparing the
#   exported schema under app/schemas against the tree this run started from.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, and the wording of the ARCHITECTURE.md rows the plan's
#   Files list names. Doc wording is reviewed by the owner in the pull request; nothing here reads a document.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/generationworker-resilience-resume-23h1"

# Scratch files live under app/build, which is gitignored.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# index_reports: one awk pass over every report this run wrote. It writes $WORK/methods and $WORK/classes as
# "<name> <pass|fail|skipped>" lines, one per name, keeping the worst outcome across every report.
# Fail is worse than skipped, and skipped is worse than pass, so one ignored test marks its whole class.
index_reports() {
    local file
    : > "$WORK/raw"
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        awk '
            function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
            function rec(n, s) { if (!(n in best) || rank(s) > best[n]) best[n] = rank(s) }
            /<testcase[ \t]/ {
                head = index($0, "<testcase")
                rest = substr($0, head)
                at = index(rest, " name=\"")
                if (at == 0) next
                tail = substr(rest, at + 7)
                q = index(tail, "\"")
                if (q == 0) next
                name = substr(tail, 1, q - 1)
                br = index(name, "[")
                if (br) name = substr(name, 1, br - 1)
                cn = ""
                at = index(rest, " classname=\"")
                if (at) {
                    tail = substr(rest, at + 12)
                    q = index(tail, "\"")
                    if (q) cn = substr(tail, 1, q - 1)
                }
                cur = name; pend = 1; st = "pass"
                if ($0 ~ /<(failure|error)[ \t\r\n>\/]/) st = "fail"
                else if ($0 ~ /<skipped/) st = "skipped"
                if ($0 ~ /\/>[ \t]*$/ || $0 ~ /<\/testcase>/) {
                    rec(cur, st); if (cn != "") rec("C:" cn, st); pend = 0; cur = ""
                }
                next
            }
            pend && /<(failure|error)[ \t\r\n>\/]/ { st = "fail" }
            pend && /<skipped/ { if (st == "pass") st = "skipped" }
            pend && /<\/testcase>/ { rec(cur, st); if (cn != "") rec("C:" cn, st); pend = 0; cur = "" }
            END { for (n in best) print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
        ' "$file" >> "$WORK/raw"
    done
    awk '
        function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
        { if (!($1 in best) || rank($2) > best[$1]) best[$1] = rank($2) }
        END { for (n in best) print n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
    ' "$WORK/raw" > "$WORK/all"
    grep '^C:' "$WORK/all" | sed 's/^C://' > "$WORK/classes" || true
    grep -v '^C:' "$WORK/all" > "$WORK/methods" || true
}

# t <method>: the named test must have run in this invocation and passed. Gradle reports nothing for a test that
# was never written, so a name missing from this run's reports is a failure, and one that ran and did not pass is
# a failure too.
t() {
    local name="$1" st
    st="$(awk -v m="$name" '$1 == m { print $2; exit }' "$WORK/methods" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$name" ;;
        fail)    bad "$name ran but did not pass" ;;
        skipped) bad "$name was skipped" ;;
        none)    bad "$name did not run" ;;
    esac
}

# c <ClassName>: the class has to have run, and every test in it has to have passed.
c() {
    local cls="$1" st
    st="$(awk -v m="$cls" 'index($1, "." m) == length($1) - length(m) { print $2; exit }' "$WORK/classes" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$cls ran and every test in it passed" ;;
        fail)    bad "$cls ran but something in it did not pass" ;;
        skipped) bad "$cls ran but a test in it was skipped" ;;
        none)    bad "$cls did not run" ;;
    esac
}

# ─── 1. CI gate: the two tasks ci.yml runs ─────────────────────────────────────
# Brief Done-when: "these tests pass and bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This
# gate is also:
# - the compile check for the worker's new offline path, the runner's second read, the DAO query and the helper;
# - the brief's "Must not break" check for the whole suite: the worker's 18 existing tests, the enqueue helper's
#   test, the turn runner's, the coach, data and domain tests, and every other test the app already has;
# - the check that no test hangs and nothing depends on the local clock, since the build kills a test task that
#   goes five minutes without finishing — the hang the brief warns about for this run.
# Stale reports are removed first, so every named test below comes from this run. --no-build-cache stops the
# reports being restored from cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

# No `gradlew --stop` here: other runs' worktrees on this machine have their own live daemons, and a stop would
# break a build that is not this one. A daemon that dies mid-gate is caught by the retry path below.

run_gate() {
    local n="$1"
    rm -rf "$RESULTS" 2>/dev/null || true
    if [ -d "$RESULTS" ]; then
        echo "  … could not clear $RESULTS — a stranded Gradle test executor is holding it; running: bash gradlew.sh --stop"
        bash gradlew.sh --stop >/dev/null 2>&1 || true
        rm -rf "$RESULTS" 2>/dev/null || true
        [ -d "$RESULTS" ] && { echo "could not clear $RESULTS" | tee "$WORK/gate-$n.log"; return 2; }
    fi
    mkdir -p app/build
    timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# Only these failures get another gate:
# - the known flake (doneBodyweightSetStepperShowsBW, a Room pool closed under a concurrent observe());
# - a build killed by a daemon or memory error;
# - a hung suite that wrote no report, which app/build.gradle.kts kills at 5 minutes — the brief warns about this
#   one for this run, and says to re-run it.
# A compile error, or a real failed assertion, is not retried.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|exceeded.*timeout' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$WORK"/gate-*.log 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    local any other
    any="$(awk '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l)"
    if [ "$any" -gt 0 ] && [ "$other" -eq 0 ]; then return 0; fi
    return 1
}

gate_ok=0
for attempt in 1 2 3; do
    if run_gate "$attempt"; then
        gate_ok=1
        echo "  … gate attempt $attempt passed"
        break
    fi
    index_reports
    if transient_failure; then
        echo "  … attempt $attempt failed on the known flake or on the build itself; stopping daemons and running the gate again"
        bash gradlew.sh --stop >/dev/null 2>&1 || true
    else
        echo "  … attempt $attempt failed on something real; not running the gate again"
        break
    fi
done

if [ "$gate_ok" -eq 1 ]; then
    ok "the CI gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "the CI gate: the app does not assemble, the unit tests do not pass, or the suite did not finish within 30 minutes"
fi
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK"/gate-*.log 2>/dev/null; then
    bad "unit tests executed in the gate run (the task came from the build cache or was up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
    : > "$WORK/methods"; : > "$WORK/classes"
else
    index_reports
    awk '
        /<testsuite / {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^tests=/) { split($i, a, "\""); tests += a[2] }
                if ($i ~ /^failures=/) { split($i, a, "\""); failures += a[2] }
                if ($i ~ /^errors=/) { split($i, a, "\""); errors += a[2] }
                if ($i ~ /^skipped=/) { split($i, a, "\""); skipped += a[2] }
            }
        }
        END { printf "  … reports: %d tests, %d failures, %d errors, %d skipped\n", tests, failures, errors, skipped }
    ' "$RESULTS"/TEST-*.xml
fi

# ─── 2. The worker: picking a generation back up ───────────────────────────────
# Every test below drives the worker's doWork() over a real socket to the scripted fake daemon, in the existing
# GenerationWorkerTest harness — the brief's own shape, and the setup the brief says to reuse rather than rebuild:
# TestListenableWorkerBuilder<GenerationWorker>(context).setInputData(workDataOf(GenerationWorker.GENERATION_ID
# to id)), doWork() inside runBlocking { withTimeout(...) }, Robolectric @Config(sdk = [34]), the application's own
# container ((ApplicationProvider.getApplicationContext<Context>() as LiftoffApplication).container — never a second
# AppContainer, which would open a second DataStore and a second Room database on the same files), the fake's
# host()/port(), a non-blank token and a coach workspace path written into settingsStore, and CoachFixtures.KEYS
# inserted through equipmentDao().insertAll so the lift fixture is not refused as unknown equipment. Replies come
# from CoachFixtures.fixture(...); daemon behaviour is scripted with DaemonScript and FakeDaemon.refuseMethod. One
# scripted turn per prompt: the fake repeats its last turn when its list runs out. The fake's own log is seeded
# with daemon.logEvent(...), which is how an earlier attempt's session is left behind for a later one to pick up.
# A resumed turn can send more than one events_after frame and a row run twice sends two prompts, so those cases
# read daemon.framesFor(...) or daemon.log directly rather than the class's one-frame frameFor helper.
echo ""
echo "=== the worker: a paused turn hands its session back to the daemon ==="

# "paused: send rho.session.resume {session_id}, then return Result.retry()". Script a turn that ends paused
# (DaemonScript.State("paused")). It must show: exactly one rho.session.resume frame, and the session_id it names
# is the session the turn stopped in — the same id the row still holds afterwards; doWork() returned
# ListenableWorker.Result.Retry; the row is still RUNNING (not FAILED) with that session id and the cursor the
# paused turn reported still on it, error null, attempt unchanged, finishedAt null, and nothing stored on the
# mission; and no rho.session.stop frame — the session is left open for the turn that resumes it. Today the
# worker sends no resume frame at all, so this cannot pass on untouched code.
# Plan Decision "While a generation is paused, what does the row look like?": the row keeps the status it already
# had and no error text, because this attempt did reach the daemon.
t aPausedTurnSendsAResumeForTheSessionItStoppedIn

echo ""
echo "=== the worker: resuming a generation that already started ==="

# "a row that already has nabuSessionId and lastEventId reuses that session, never creates another, and catches
# up with events_after from the stored cursor" + "A re-sent prompt reuses the same client_id … so the daemon
# delivers it once". Seed the fake's log the way an earlier attempt left it: this row's own prompt as a user
# message carrying client_id "<generationId>-0", then the assistant reply, then an idle end; the row is QUEUED
# with nabuSessionId and a lastEventId at or before the prompt. It must show: no rho.session.create frame at all;
# the first rho.session.events_after frame carries the cursor the row stored, not an empty one; the reply was
# stored (the mission left DRAFT for ACTIVE with a current sortie) and the row is SUCCEEDED; the row's cursor
# moved forward from the stored one, not back; and the fake's log holds exactly one user message for that
# client_id — the prompt the daemon already has is not written a second time. Today a seeded user message is not
# registered for dedup, so the re-send appends a second one.
# Plan Decision "Does a resumed run send the prompt again, or only read what already happened?": it sends, under
# the client_id the row already holds, and the daemon answers with the original prompt's id.
t aGenerationWhosePromptAlreadyLandedResumesFromTheStoredCursorAndStoresTheReply

# "If the cursor is past our own prompt, still find the turn's reply" — the cursor is at the turn's end, so the
# read from the stored cursor finds nothing after it. Seed prompt, reply, idle end, and put the row's lastEventId
# at the idle end. It must show doWork() returning Result.Success, the row SUCCEEDED with the reply stored on the
# mission, exactly one prompt frame for this row and no repair (attempt 0, error null), and the cursor not moved
# back behind the reply it already read. On untouched code the single catch-up read finds nothing, the runner
# waits for a push that never comes, and the test's own 30-second wait ends it as a failure.
# Plan Decision (changed by review): the runner reads again from the prompt's own id when the read from the cursor
# lacks the end or the reply, and decides the turn as if the cursor had never passed them. The other positions the
# plan's Testing lists for that cursor are shapes of the same ask, and are not required here.
t aCursorAtTheEndOfTheTurnStillFindsTheReplyTheWorkerAlreadyRead

# "A re-sent prompt reuses the same client_id (<generationId>-<attempt>, with the attempt the row holds), so the
# daemon delivers it once." Run the same row through doWork() twice — the first run scripted to end paused or in
# error so the row is left RUNNING with its session id and cursor, the second reaching idle and storing its
# reply. It must show: both rho.session.send_prompt frames carry the same client_id "<generationId>-<attempt>",
# built from the attempt the row holds rather than from WorkManager's attempt count; the fake's log holds exactly
# one user message for that client_id; and no second rho.session.create frame — the second run is the same
# session, not a new generation of the same prompt.
t aRetriedGenerationSendsItsPromptAgainUnderTheSameClientIdAndTheDaemonDeliversItOnce

echo ""
echo "=== the worker: the coach is offline ==="

# "Unreachable daemon (ConnectionOutcome.Unreachable, or a connect that fails): return a retry, and mark the row
# offline by setting error to exactly 'Mission Control offline' while it stays QUEUED or RUNNING." Point the
# settings the container reads at a host and port nothing listens on — the plan takes a port opened and closed by
# the test itself, so the connection check is refused at once and well inside the class's 30-second wait. It must
# show: doWork() returned ListenableWorker.Result.Retry; the row is still RUNNING and not FAILED, with finishedAt
# still null and its session id and cursor as they were; error is exactly 'Mission Control offline';
# Generation.isCoachOffline is true for that row; and nothing reached the scripted daemon — no frames, no prompt.
# Today an Unreachable outcome is a silent retry that leaves no mark on the row, so this cannot pass on
# untouched code.
t anUnreachableDaemonMarksTheGenerationOfflineAndRetries

# "Clear the mark (back to null) as soon as a connection succeeds." Take the row that carries the offline mark —
# the plan's shape is the row the name above left behind, with the settings pointed back at the live fake — and
# run it again. It must show: error is null and Generation.isCoachOffline is false for that row afterwards, and
# the row is not FAILED and carries no 'coach unreachable' text; the generation reaches the daemon and finishes
# (the plan's shape runs it on to SUCCEEDED with its reply stored). The mark is cleared by the attempt that
# reached the daemon, not only by a generation that succeeds.
# Plan Decision "Is the offline mark cleared by any successful connection?": yes, even if that attempt then fails
# for another reason.
t aLaterRunThatReachesTheDaemonClearsTheOfflineMark

# "Once WorkManager's runAttemptCount shows the 8th attempt has also failed to connect, mark the row FAILED with
# 'coach unreachable' and return failure." Build the worker with setRunAttemptCount(7) — WorkManager counts
# attempts from 0, so 7 is the 8th — against the same host nothing listens on. It must show: doWork() returned
# ListenableWorker.Result.Failure; the row is FAILED with error exactly 'coach unreachable' and finishedAt
# stamped; Generation.isCoachOffline is false for it; and the row is not left RUNNING with the offline mark.
# Today the 8th attempt looks exactly like the 7th: a retry with no failure.
# Plan Decision "Which attempt makes the row fail for good?": the 8th, and only when that attempt also could not
# connect. Plan Decision "What does Generation.isCoachOffline mean?": a row that has failed this way is not
# offline, so Launchpad can say "will keep trying" separately from "this one failed".
t theEighthAttemptThatCannotConnectFailsTheGenerationAsCoachUnreachable

echo ""
echo "=== the row: the helper a later screen calls ==="

# "Add a small public helper, e.g. Generation.isCoachOffline, that a later Launchpad run can call instead of
# comparing strings." Call it on Generation rows read back from the DAO. It must show: true for a QUEUED row and
# for a RUNNING row carrying exactly 'Mission Control offline'; false for a QUEUED or RUNNING row with no error
# text, and for one carrying any other error text; and false for a FAILED row carrying 'coach unreachable'.
# Plan Decision "What does Generation.isCoachOffline mean?": only the offline mark — the two states must not
# overlap.
t isCoachOfflineIsTrueOnlyForARowStillWaitingOnTheCoach

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the worker's existing tests, the enqueue helper's test, the coach, data and domain
# tests, and the build gate." The gate above runs the whole suite; these are the named proof for the classes the
# brief calls out, plus the two the plan's Risks says to run rather than assume because this run changes
# FakeDaemon underneath them. GenerationWorkerTest as a class is how the worker's 18 existing tests are covered:
# this run changes what the worker does on a paused turn and on an unreachable daemon, so the cases written
# against the old behaviour have to have been brought along, not dropped.
echo ""
echo "=== the tests the brief says must not break ==="

c GenerationWorkerTest
c GenerationEnqueuerTest
c GenerationDaoTest
c CoachTurnRunnerTest
c DaemonConnectionCheckTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c CoachPromptGoldenFileTest

# ─── 4. The Room schema the brief says not to change ───────────────────────────
# Brief: "No Room schema change." LiftoffDatabase exports its schema to app/schemas at build time, so the
# exported schema is the record of what the entities are. It is compared against the tree this run started from,
# both as committed and as it stands after the gate run above — a new version file or a rewritten one is a schema
# change, and the plan's Files treats one as a mistake.
echo ""
echo "=== the Room schema ==="

brief_commit="$(git rev-list -1 HEAD -- "$RUN_DIR/brief.md" || true)"
if [ -z "$brief_commit" ]; then
    bad "the Room schema is unchanged (this run's brief.md is not in the history to compare against)"
else
    base_commit="$(git rev-parse "$brief_commit^")"
    if ! git diff --quiet "$base_commit" HEAD -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the committed tree)"
    elif ! git diff --quiet "$base_commit" -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the working tree after the gate run)"
    else
        ok "the exported Room schema is byte-identical to the one this run started from"
    fi
fi

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
