#!/usr/bin/env bash
# Verify: "GenerationWorker: NotConfigured and AuthenticationRejected retry with no limit" (brief.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and
# the brief names the same gate), then the two named tests read out of the JUnit XML reports that run produced,
# then the classes whose existing tests the brief's fix leans on and must not disturb.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports;
# a test that was never written cannot pass on its own. That is what makes this script fail on untouched code:
# both named tests are new to this run, and GenerationWorker.kt still has `else -> Result.retry()` covering
# NotConfigured and AuthenticationRejected, so neither outcome can fail a generation today.
#
# The brief asks for one case per outcome, so there are two names. What each must show is written in the comment
# above it; which file and which helpers each one uses is the implementer's call.
#
# What is NOT checked here, and why:
# - The card offering Retry and Re-fly once the row is FAILED with one of these reasons. The brief states it as a
#   consequence of landing FAILED, and the plan's Testing says no new UI or domain test is needed because
#   CoachStatusTest and SortieGenerationStatusTest already cover what a FAILED row's reason does to the card. Those
#   two classes are required below to still pass; no new UI or domain test is named.
# - The two reason sentences themselves as strings in the source. What is checked is the `error` a row carries
#   after doWork(), which is what the card reads.
# - "Mission Control isn't set up" vs Mission Control's own longer Test-connection wording. The plan's Risks says
#   not to harmonise them; both are existing text in existing screens with existing tests.
# - Retry tapped while the settings are still blank. The plan's Risks calls that existing behavior with an
#   existing test (MissionManager refuses to queue, and the draft says which setting to fix); nothing here changes
#   it, so nothing here names it.
# - The 8-attempt give-up for Unreachable, and the offline mark. Untouched by this fix; the class check on
#   GenerationWorkerTest below is what shows the offline cases still behave as they did.
# - Whether the two new arms are named arms or an exhaustive `when` with no `else`, whether the reason strings are
#   file-level constants or literals, and whether the cases reuse the class's existing permanent-end row assertion.
#   Those are how it is built. The named tests assert the row's status, error, finishedAt, session id and the
#   frames the daemon received.
# - AppOpenSweep.kt's KDoc sentence, and ARCHITECTURE.md / DESIGN.md. Doc wording is reviewed by the owner in the
#   pull request; nothing here reads a document, and the plan's Files says neither doc needs a change.
# - A rho session a row still carries when the token is refused: no `rho.session.stop` is attempted. That is the
#   plan's Decision "The rho session the row may still carry is left alone", and it is visible in the refused-token
#   name below as the frames the daemon received. Decisions are reviewed in the pull request.
# - A row the user cancelled mid-way, a row that already finished, and the missing-Mission / missing-Sortie ends.
#   Existing behavior with existing tests, all run by the gate.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

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
# Brief: "run the gate (bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest) before opening the PR"; CI runs
# the same two tasks. This gate is also:
# - the compile check for the worker's two new arms, including that the `when` over the sealed ConnectionOutcome
#   is still exhaustive without an `else`;
# - the whole-suite check that nothing the fix leans on broke: the worker's existing cases (the offline mark and
#   its 8-attempt give-up, the mismatch ends, the resume and cancelled-row cases), the connection check's own
#   tests, and the domain and UI tests that read a FAILED row's reason;
# - the check that no test hangs, since the build kills a test task that goes five minutes without finishing and
#   writes no reports.
# Stale reports are removed first, so every named test below comes from this run. --no-build-cache stops the
# reports being restored from cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

# No `gradlew --stop` before the gate: other runs' worktrees on this machine have their own live daemons, and a
# stop would break a build that is not this one. A daemon that dies mid-gate is caught by the retry path below.

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
# - the two known timing flakes in tests this run does not touch: doneBodyweightSetStepperShowsBW (a Room pool
#   closed under a concurrent observe()) and aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt (a subscription
#   emission that lands on a slower runner);
# - a build killed by a daemon or memory error;
# - a hung suite that wrote no report, which the build kills at five minutes.
# A compile error, or a real failed assertion in any other test, is not retried.
# Both log predicates read only the attempt being judged, so an earlier attempt's daemon error cannot colour a
# later attempt's real failure.
transient_failure() {
    local log="$WORK/gate-$1.log"
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|exceeded.*timeout' "$log" 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$log" 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    local any other
    any="$(awk '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW" && $1 != "aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt"' "$WORK/methods" | wc -l)"
    if [ "$any" -gt 0 ] && [ "$other" -eq 0 ]; then return 0; fi
    return 1
}

gate_ok=0
last_attempt=0
for attempt in 1 2 3; do
    last_attempt="$attempt"
    if run_gate "$attempt"; then
        gate_ok=1
        echo "  … gate attempt $attempt passed"
        break
    fi
    index_reports
    if transient_failure "$attempt"; then
        echo "  … attempt $attempt failed on a known flake or on the build itself; stopping daemons and running the gate again"
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
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK/gate-$last_attempt.log" 2>/dev/null; then
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

# ─── 2. The brief's two outcomes ───────────────────────────────────────────────
# Both names drive the worker's doWork() in the existing GenerationWorkerTest harness — the application's own
# container, TestListenableWorkerBuilder, a real socket to the scripted FakeDaemon, settings written into
# container.settingsStore (which the container re-reads per call), and WorkManager's run-attempt count at 0, which
# is the first attempt. Plan Decision "Give up on the first attempt, not after N": neither outcome carries a
# counter, so a first attempt that would today retry must land the row FAILED. Both cases assert the row's own
# `attempt` is still 0, since that counter counts the generation's repairs and not WorkManager's runs.
echo ""
echo "=== the settings the worker was asked with are gone ==="

# "fail the generation … with 'Mission Control isn't set up'" for ConnectionOutcome.NotConfigured. Blank the host
# or the token in the settings the container reads, then run the worker over a row that was queued while Mission
# Control was configured. It must show: doWork() returned ListenableWorker.Result.Failure — not Retry, and not a
# row left RUNNING; the row is FAILED with error exactly "Mission Control isn't set up" and finishedAt stamped;
# Generation.isCoachOffline is false for it, so the card is no longer saying the coach is still planning; and
# nothing at all reached the scripted daemon — no frames, not even the hello the connection check would have
# sent, because a blank setting opens no socket. Seed the row the way the unreachable path leaves it (RUNNING,
# carrying the offline mark in error) so the case also shows the new reason taking the place of that text rather
# than sitting under it, and that the row's session id and cursor it carried are not left as a live generation's.
# Today this outcome falls into `else -> Result.retry()`: Retry, the row still RUNNING, and the mark still on it.
t aBlankHostOrTokenFailsTheGenerationAsMissionControlIsntSetUp

echo ""
echo "=== the daemon will not take the token ==="

# "fail the generation … with 'Mission Control refused the token'" for ConnectionOutcome.AuthenticationRejected.
# Make the fake answer the WebSocket upgrade the way the real daemon does for a bearer token it doesn't accept —
# the 401 the fake already returns when its refused-upgrade flag is set, which is the recipe
# DaemonConnectionCheckTest uses to get AuthenticationRejected — then run the worker over a row that was queued
# with a token that used to work. It must show: doWork() returned ListenableWorker.Result.Failure; the row is
# FAILED with error exactly "Mission Control refused the token" and finishedAt stamped; Generation.isCoachOffline
# is false for it; nothing was stored on the mission or the sortie; and no rho.session.stop frame reached the
# daemon — the upgrade never completed, so there was no session to give up. Plan Decision "The rho session the row
# may still carry is left alone": no stop is attempted, so a row that carried a session id from an earlier attempt
# is failed without one. Today this outcome is the same silent Retry with no mark and no reason.
t aRefusedTokenFailsTheGenerationAsMissionControlRefusedTheToken

# ─── 3. What the fix must not disturb ──────────────────────────────────────────
# The gate above ran the whole suite; these are the named proof for the classes this change reaches into.
# GenerationWorkerTest as a class is how the worker's existing cases are covered: the fix changes what the worker
# does on two of the connection outcomes, so the offline mark and its 8-attempt give-up, the mismatch ends, the
# resume and cancelled-row cases and the happy paths have to have been brought along, not dropped.
# DaemonConnectionCheckTest is the class that decides which outcome the worker is answering, including the
# NotConfigured and AuthenticationRejected outcomes themselves and the 401-at-the-upgrade recipe the new
# refused-token case reuses.
# CoachStatusTest and SortieGenerationStatusTest are where the brief's "The card then offers Retry and Re-fly, as
# for any FAILED generation" already lives: a FAILED row's reason is kept word for word, offersRetry is true, and
# offersReFly is unchanged. The plan's Testing says no new UI or domain test is needed because these cover it, so
# nothing new is named there — only that these still pass.
# AppOpenSweepTest is the class over the file whose KDoc this run corrects: the sweep asks for rows without
# reading settings, so a row whose settings went blank is now ended rather than resumed.
echo ""
echo "=== the tests this fix must not break ==="

c GenerationWorkerTest
c DaemonConnectionCheckTest
c CoachStatusTest
c SortieGenerationStatusTest
c AppOpenSweepTest

# ─── result ─────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
