#!/usr/bin/env bash
# Verify: "GenerationWorker: a coach turn that ends in `error` retries forever" (brief.md; issue #53).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief names the same two tasks as the gate to run before the PR), then the three tests that prove what the brief
# asks for, read out of the reports that gate produced, then the classes whose existing tests the brief's fix must
# not disturb.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports; a
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all
# three names under section 2 are new to this branch — none appears anywhere under app/src/test today — and on the
# untouched branch the gate passes, every class check passes, and the three names report "did not run": exit 1.
#
# What is NOT checked here, and why:
# - That only `paused` and a dropped connection are handed back and retried. The brief states that as the other half
#   of the fix, and the plan keeps Liftoff's own 20-minute `TimedOut` on that arm too (its Risks section: a slow
#   coach must not become a failed generation). No new test is planned for the narrowed arm, and the existing cases
#   that prove it — the paused turn that sends one resume and leaves the row RUNNING at its cursor, the resume the
#   daemon refuses for good that is still a retry, the two-run resume that finishes the same turn, and the turn with
#   no end inside its limit — all run in the gate above and must stay green. They are not named below because a name
#   that already exists and already passes cannot make this script fail, and the whole-class check is what keeps them
#   green whatever they are called.
# - That the row's failure text reaches the Launchpad as a status line with Retry beside it. The plan changes no UI:
#   a FAILED row is already drawn as a failure carrying its own error text, and Retry already refuses anything but a
#   FAILED row and inserts a fresh QUEUED row with no session id and no cursor, so the next attempt opens a new rho
#   session. Those are existing behaviour, gated by the whole suite and by the classes named in section 3.
# - The runner carrying the ending state_change's `reason` as its own field. That is the plan's chosen shape; the
#   alternative — the worker reading the reason itself — is shown by the same assertion on the row. What is checked
#   is that the daemon's own words land on the row, not which object carried them.
# - The `rho.session.stop` the daemon may refuse on a session already in `error`. The plan's Risks note says the
#   row's outcome is decided before the stop is attempted and every refusal is swallowed, so the worker test below
#   proves the frame was sent, not that the fake accepted it.
# - That a FAILED row is drawn as a failure carrying its own reason, with Retry beside it, on the Launchpad's cards
#   and in the sortie status lines. The plan changes no UI and asks for no UI test; those classes are existing and
#   the gate above runs them. No new test is named for them, because a test that already exists and already passes
#   cannot make this script fail today.
# - The attempt ceiling on the offline path, the repair loop, the row schema, the queue and the app-open sweep. The
#   brief changes none of them; the gate runs their tests.
# - DESIGN.md §7.3 step 5 and its amendment entry, and ARCHITECTURE.md. This run writes code, so no document row is
#   grepped here: Kyle reviews the wording of a document in the pull request. The plan says ARCHITECTURE.md needs no
#   change at all.
# - Which class each new test sits in, and the shape of the fix (which arm of `storeFinishedTurn` decides, what
#   `alreadyActedOn` keeps). The plan puts the two worker cases in GenerationWorkerTest and the stuck-row case in
#   CoachTurnRunnerTest; the names below are matched across every report this run wrote.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description —
#   including the plan's Risk that the diff shows one existing test whose expectation flipped.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# Scratch lives under app/build, which is gitignored.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
rm -rf app/build/verify-old-results-* 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# index_reports: one awk pass over every report this run wrote. It writes $WORK/methods and $WORK/classes as
# "<name>\t<pass|fail|skipped>" lines, one per name, keeping the worst outcome across every report.
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
            END { for (n in best) printf "%s\t%s\n", n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
        ' "$file" >> "$WORK/raw"
    done
    awk '
        BEGIN { FS = "\t" }
        function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
        { if (!($1 in best) || rank($2) > best[$1]) best[$1] = rank($2) }
        END { for (n in best) printf "%s\t%s\n", n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
    ' "$WORK/raw" > "$WORK/all"
    grep '^C:' "$WORK/all" | sed 's/^C://' > "$WORK/classes" || true
    grep -v '^C:' "$WORK/all" > "$WORK/methods" || true
}

# t <method>: the named test must have run in this invocation and passed. Gradle reports nothing for a test that
# was never written, so a name missing from this run's reports is a failure, and one that ran and did not pass is
# a failure too. Names are matched across every report, so which file and package a test sits in is the
# implementer's call; what it must show is written in the comment above it.
t() {
    local name="$1" st
    st="$(awk -F '\t' -v m="$name" '$1 == m { print $2; exit }' "$WORK/methods" 2>/dev/null || true)"
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
    st="$(awk -F '\t' -v m="$cls" 'index($1, "." m) == length($1) - length(m) { print $2; exit }' "$WORK/classes" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$cls ran and every test in it passed" ;;
        fail)    bad "$cls ran but something in it did not pass" ;;
        skipped) bad "$cls ran but a test in it was skipped" ;;
        none)    bad "$cls did not run" ;;
    esac
}

# ─── 1. CI gate: the two tasks ci.yml runs ─────────────────────────────────────
# Brief: "run the gate (bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest) before opening the PR".
# This gate is also:
# - the check that the fix does not break anything else in the app, since it runs the whole unit-test suite;
# - the check that the ends which ARE still retryable stay retryable: the paused turn that hands its session back
#   with exactly one resume and leaves the row RUNNING at its cursor, the resume refused for good that is still a
#   retry, the paused turn resumed by a second run that then stores its answer, the turn with no end inside its
#   limit, and the dropped connection that throws rather than waiting out the limit — every one of them reaches the
#   arm this change narrows, and each must land where it lands today;
# - the check that the other permanent ends still fail a row for their own reason: the blocked turn, the two refused
#   repairs, the permanent refusal at a call, the protocol mismatch, the 8th unreachable attempt;
# - the compile check for every caller of the turn runner and the row helpers, since the plan changes the shape of
#   what a turn reports: the worker, the canceller, the app-open sweep and the tests that drive them;
# - the check that no test hangs and nothing depends on this machine's clock (CI is ubuntu-latest, UTC, and slower),
#   since app/build.gradle.kts kills a test task that goes five minutes without finishing — which is exactly how a
#   stuck row would show up here, if the unstuck half of the fix were left out.
# Stale reports are moved aside first, so every named test below comes from this run. --no-build-cache stops the
# reports being restored from cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

clear_results() {
    # Moved, not deleted: a stranded Gradle test executor holds test-results/binary/output.bin, and rm -rf on a
    # directory it is holding fails. No gradlew --stop here: a stop is machine-wide and would take down the daemon
    # of another run's worktree. If the move fails the gate is not run, and the caller retries.
    local stamp
    stamp="$(date +%s)"
    mv "$RESULTS" "app/build/verify-old-results-$stamp" 2>/dev/null || true
    [ -d "$RESULTS" ] && return 1
    return 0
}

run_gate() {
    local n="$1"
    clear_results || { echo "could not clear $RESULTS" | tee "$WORK/gate-$n.log"; return 2; }
    mkdir -p app/build
    timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# Only these get another gate:
# - the known hang: the build kills the test task at five minutes and writes no TEST-*.xml at all;
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - doneBodyweightSetStepperShowsBW alone, the one name this repo has recorded failing at random on a Room pool
#   closed under a still-collecting observe() and passing on a re-run of the identical tree.
# A compile error, or any other reported assertion failure, is a real result and is not retried. That includes a
# mid-turn cancel case that lost its race: a case that cannot get its row cancelled before the turn ends is a real
# result about how the case was written, not a machine fault.
# Only the attempt's own log is read: a hang on an earlier attempt must not turn a later attempt's reported
# assertion failure into a retry.
transient_failure() {
    local log="$WORK/gate-$1.log"
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|exceeded its configured timeout|Requesting stop of task|Test Executor|PersistentEnumerator storage corrupted' "$log" 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$log" 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    local any other
    any="$(awk -F '\t' '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk -F '\t' '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l)"
    if [ "$any" -gt 0 ] && [ "$other" -eq 0 ]; then return 0; fi
    if [ "$any" -eq 0 ]; then return 0; fi
    return 1
}

gate_ok=0
gate_log=""
for attempt in 1 2 3; do
    gate_log="$WORK/gate-$attempt.log"
    if run_gate "$attempt"; then
        gate_ok=1
        echo "  … gate attempt $attempt passed"
        break
    fi
    index_reports
    if transient_failure "$attempt"; then
        echo "  … attempt $attempt failed on the hang or on the build itself; running the gate again"
    else
        echo "  … attempt $attempt failed on a reported test result; not running the gate again"
        break
    fi
done

if [ "$gate_ok" -eq 1 ]; then
    ok "the CI gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "the CI gate: the app does not assemble, the unit tests do not pass, or the suite did not finish within 30 minutes"
fi
if [ -n "$gate_log" ] && grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$gate_log" 2>/dev/null; then
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

# ─── 2. The three things the brief asks for ────────────────────────────────────
# Brief: "treat TurnEnd.Error as permanent by calling failGeneration with the session's error text. Only Paused
# (and a dropped connection) should be handed back and retried."
#
# The two worker cases run on GenerationWorkerTest's existing harness: the application's own container, the fake rho
# daemon, and runWorker() under the class's own bounded wait. The turn is scripted the way the existing blocked and
# error cases script a turn — an assistant line, then DaemonScript.State with the end and the daemon's own reason —
# so the reason asserted below is a string the test itself put on the state_change, not one the app invented.
echo ""
echo "=== a coach turn that ends in error ends the generation for good ==="

# The brief's own test. A turn whose state_change is `error` must show: doWork() ends as failure, not retry; the row
# is FAILED with finishedAt stamped and its session id kept, exactly as a blocked turn's row is; the row's error is
# the daemon's own words for that error, not a sentence the app wrote and not null; nothing is stored — the mission
# is still DRAFT with no sorties; the session the turn opened is given up exactly once; and no rho.session.resume
# frame was sent at all, which is the half that stops the endless retry. On the untouched tree this test cannot
# pass: today the same scripted turn returns Result.retry() with the row RUNNING, error null and its session left
# open. This is also where the brief's "the session's error text" is proven end to end, whatever object carried it.
t aTurnThatEndsInErrorFailsTheGenerationWithTheDaemonsReason

# A row the user cancelled part-way through a turn that then meets an `error`. The cancel lands while the turn is
# still open, through the scripted Act step that calls GenerationDao.markCancelled, exactly as the existing
# cancelled-then-blocked case does, and the row is cancelled through the DAO rather than through the container's
# canceller so the one stop frame the fake sees is the worker's own. It must show: doWork() ends as done; the row is
# still CANCELLED with the error it held when it was cancelled, not the error end's reason and not FAILED; nothing
# is stored. This is the case that keeps the new rule narrow — a generation nobody asked to fail must not be swept
# into it (the plan's Decision that a cancelled or superseded generation keeps the outcome it already has). On the
# untouched tree it fails too, because today this turn is handed back and retried.
t aGenerationCancelledMidTurnThatEndsInErrorStaysCancelledAndKeepsItsError

# The row that is already stuck, which is what the issue is about: a card that says "Coach is planning…" forever.
# The turn is handed the session and the cursor an earlier attempt stored — a cursor already past this turn's own
# prompt and past the `error` state_change that turn reached. It must report that error as the end, carrying the
# daemon's reason for it, rather than waiting out its limit and reporting timed out: today the runner treats an
# error behind the cursor as one an earlier attempt already acted on, finds no end, and hands the row back for
# another 20-minute attempt. The limit handed in is a fraction of a second, so a regression fails this test fast
# instead of hanging it. (This is the plan's Decision that rows already stuck under the old rule are unstuck by
# this change.) The paused case stays as it is: a paused end behind that same cursor is still acted on.
t aCursorHandedInPastAnErrorReportsThatErrorInsteadOfTimingOut

# ─── 3. The tests this fix must not disturb ────────────────────────────────────
# The gate above runs the whole suite; these name the classes so that one renamed away, skipped or deleted is
# caught, and a failure is reported as the class it belongs to rather than only as a red gate. Each one holds tests
# that reach the arm this change narrows, or read the outcome it changes.
echo ""
echo "=== the tests this fix must not disturb ==="

# The turn runner and the worker: the ends that stay retryable (paused, the worker's own limit, a dropped
# connection), the ends that already failed for good (blocked, exhausted repairs), the offline path and its attempt
# ceiling, the mid-turn cancels, and the two-run resume that finishes a paused turn's same session.
c GenerationWorkerTest
c CoachTurnRunnerTest

# The rows the worker writes, the helper that queues them, and the two things that cancel them.
c GenerationDaoTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c AppOpenSweepTest

# Queueing and Retry: retryOutline accepts only a FAILED row and inserts a fresh QUEUED one with no session id and
# no cursor, which is what makes a failed generation recoverable at all — the outcome this change now produces, on
# both the outline and the flight-plan kinds of row the worker drives.
c MissionManagerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c FlightPlanDaoTest

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
