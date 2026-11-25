#!/usr/bin/env bash
# Verify: "GenerationWorker: any non-daemon exception after markRunning strands the row" (brief.md; DESIGN.md §7.3 step 6, §10).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief names the same two tasks), then the one new test that proves the behaviour change, read out of the reports that
# gate produced, then the classes the change reaches through.
#
# Gradle exits 0 when a named test never existed, so the test below is looked for by name in this run's reports; a test
# that was never written cannot pass on its own. That is what makes this script fail on untouched code: the name under
# section 2 appears nowhere under app/src/test today, so the gate passes, every class check passes, and the name
# reports "did not run": exit 1.
#
# What the brief is: after markRunning claims the row, only DaemonException is caught, so a throw from the validator,
# the store transaction, PromptInputLoader, Room or a require ends the WorkManager work with the row still RUNNING and
# no worker behind it, and the app-open sweep re-enqueues that RUNNING row at every app open. The fix hands any such
# throw to the worker's existing end for a generation that is over: FAILED carrying the exception's own message.
#
# What is NOT checked here, and why:
# - The CancellationException rethrow. No test can tell it apart from its absence: a coroutine in the cancelling state
#   throws at its next suspension point, so failGeneration's own row write never runs either way — the row stays
#   RUNNING and doWork throws either way. The plan's Testing section and the map both record this; the rethrow is
#   reviewed by Kyle in the pull request, in the shape giveUpSession and handSessionBack already use.
# - A throw in the half of doWork that runs after the claim but before a client exists — the Mission and Sortie reads,
#   the connection check. The plan puts them under the same handler; there is no reachable trigger for a throw there
#   today, and the arms that answer that half already end through failGeneration ("Mission … no longer exists", a
#   protocol mismatch). Those ends are existing cases and run under the class check below.
# - A throw before the row is claimed, which the plan's Decision leaves as it is: the row stays QUEUED, which the card,
#   the sweep and the cancel helper all already handle. aGenerationRowThatDoesNotExist… and the row guards in
#   GenerationDaoTest are the existing proof, and they must stay green.
# - The card wording a FAILED row now produces ("Planning" becomes the row's reason, or the fallback sentence when the
#   exception had no message). SortieGenerationStatusTest and CoachStatusTest already cover a FAILED row with and
#   without a reason; the brief and the plan name no UI test, so none is required here.
# - The daemon's own ends, which the new catch must not swallow: a dropped connection still retries with the row left
#   RUNNING, a refusal the daemon will answer the same way every time still ends FAILED with the daemon's message, an
#   unreachable coach still walks the attempt ladder, a turn that ran out its limit still hands the session back and
#   retries. They are existing cases in GenerationWorkerTest and CoachTurnRunnerTest, run by the class checks below;
#   the plan's Order lands the catch with no new test on purpose, so those cases are what prove the arms still match.
# - That a generation which died this way still gives up its rho session, that WorkManager reports failure rather than
#   retrying, and that the row keeps the session id its turn ran on. Each is an assertion inside the one new test
#   below; the decisions themselves are reviewed by Kyle in the pull request.
# - ARCHITECTURE.md's com.liftoff.app.work row and any other doc wording. The plan changes no document, and doc rows
#   are reviewed by Kyle in the pull request, so no doc row is checked here.
# - Which class the new test sits in, what reply or seam it uses to make the store throw, and where the catch is
#   written. The plan puts it in GenerationWorkerTest on a valid reply whose store throws; the name below is matched
#   across every report this run wrote, and the assertions are what matter, not the route to the throw.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description.
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
# - the check that the whole suite still passes with the new catch in place: every existing end in
#   GenerationWorkerTest runs here — the retryable arms that must still return retry with the row left RUNNING (a
#   refused resume, a turn that ended in error, a paused turn's resume, the offline ladder before the 8th attempt),
#   the permanent ends that must still land FAILED with their own reason (a blocked turn, two refused repairs, a
#   permanent refusal, each protocol mismatch, the 8th unreachable attempt, a store that refused its target), the
#   resume and supersede cases, and the mid-turn cancels that must still end as done with the row CANCELLED. A generic
#   catch placed before the DaemonException arm, or one that retries where a daemon end retries, reddens those cases
#   here;
# - the compile check for everything the worker reaches through: MissionManager's store transaction, the DAOs, the
#   validator, the prompt loader, the app-open sweep and the cancel helper all keep compiling and passing as they are;
# - the check that no test hangs and nothing depends on this machine's clock (CI is ubuntu-latest, UTC, and slower),
#   since app/build.gradle.kts kills a test task that goes five minutes without finishing.
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
# - only the two cases this repo has recorded failing at random and passing on a re-run of the identical tree:
#   doneBodyweightSetStepperShowsBW (a Room pool closed under a still-collecting observe()) and
#   aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt (compose-timeout timing). The plan's Risks name both.
# A compile error, or any other reported assertion failure, is a real result and is not retried — including the new
# case below, which is expected to be red until the catch exists.
# Only the attempt's own log is read: a hang on an earlier attempt must not turn a later attempt's reported assertion
# failure into a retry.
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
    any="$(awk -F '\t' '$2 == "fail"' "$WORK/methods" | wc -l || true)"
    other="$(awk -F '\t' '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW" && $1 != "aPlannedSortieShowsRePlanningWhenANewRowIsQueuedForIt"' "$WORK/methods" | wc -l || true)"
    if [ "${any:-0}" -gt 0 ] && [ "${other:-0}" -eq 0 ]; then return 0; fi
    if [ "${any:-0}" -eq 0 ]; then return 0; fi
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

# ─── 2. The behaviour the brief asks for ───────────────────────────────────────
# Brief: "wrap the work after markRunning in one catch (e: Exception) that rethrows CancellationException and
# otherwise calls failGeneration with e.message" / "make the store throw (a fake or a bad reply that reaches
# writePlan) and assert the row ends FAILED with the message".
#
# One new case, on GenerationWorkerTest's existing harness: the application's own container and the real store, the
# scripted fake rho daemon, and runWorker() under the class's own bounded wait — the same seeding as the lift-plan
# happy path (onAppOpen() → continueWithoutOutline → scrub the index-0 run so index 1 is a current PENDING LIFT).
# The turn ends with a reply that passes validation and whose store throws on the way in. The plan's route is the
# real trigger the issue names: the lift-plan fixture with one exercise name replaced by a punctuation-only string,
# which the schema accepts (minLength 1) and the validator's only name check (duplicates) accepts, so the throw comes
# from ExerciseDao.resolve inside FlightPlanDao.writePlan inside the store transaction. If issue #56 lands first and
# that reply is rejected before the store, the case keeps every assertion below and changes only how the store is made
# to throw — the assertions are what this script checks, not the route.
#
# Today this case is red in two ways: doWork() throws the IllegalArgumentException out of the worker instead of
# returning a Result, and the row is left RUNNING with no finishedAt — which is the stranded row the issue is about,
# and the state the app-open sweep re-enqueues at every app open.
echo ""
echo "=== a store that throws ends the generation FAILED with its own message ==="

# The row ends through the worker's existing end for a generation that is over, carrying the exception's own sentence
# word for word — not a generic "generation failed", not the exception's class name, and not blank. On the plan's
# route that sentence is ExerciseDao.resolve's "Exercise name cannot be blank or symbols-only: '<name>'", and the
# assertion is against the message the throw carried, whatever made it.
#
# The work reports failure to WorkManager rather than retry: the throw came from Liftoff's own data path, so a retry
# would run the same failing path again, which is the loop the issue is about (the plan's Decision). A row that ends
# FAILED is not an open row, so the sweep leaves it alone and nothing re-runs it.
#
# Nothing was stored: the transaction rolled back, so the sortie has no Flight Plan, no planned exercises or sets, and
# is still PENDING rather than PLANNED.
#
# The rho session the turn ran on was given up exactly once — the plan's Decision, and DESIGN §7.3 step 6's stop on a
# permanent FAILED, which this path was missing — and the row still carries that session id, so the failure and the
# session it ran on point at each other.
t aStoreThatThrowsFailsTheGenerationWithItsOwnMessageAndStoresNothing

# ─── 3. What the change must not break ─────────────────────────────────────────
# The gate above runs the whole suite; these name the classes the change reaches through, so that one renamed away,
# skipped or deleted is caught, and a failure is reported as the class it belongs to rather than only as a red gate.
echo ""
echo "=== the tests the change must not break ==="

# The worker's own ends. Every case already in this class must land exactly where it lands today: the retryable
# daemon ends still retry with the row left RUNNING and their session and cursor kept, the permanent ends still fail
# the row with their own reason, the mid-turn cancels still end as done with the row CANCELLED and its error
# unchanged, the resume and supersede cases still store once, and the offline ladder still gives up on the 8th
# attempt. The new catch sits after the DaemonException arm and must not reach any of them.
c GenerationWorkerTest

# The turn the worker runs, whose own ends (blocked, error, timed out, paused) are handed back rather than failed,
# and the row writes the failure end makes.
c CoachTurnRunnerTest
c GenerationDaoTest

# The row's other owners: the enqueue helper, the cancel helper, and the sweep that today re-runs the failing path.
c GenerationEnqueuerTest
c GenerationCancellerTest
c AppOpenSweepTest

# The path the throw travels out of: the prompt load, the validator that accepted the reply, and the store
# transaction that threw. None of them changes in this run, and the plan's Risks note the case is coupled to issue
# #56 — if that fix moves, it moves here, not into the worker.
c PromptInputLoaderTest
c CoachReplyFixtureTest
c CoachReplySemanticTest
c SchemaCheckerValidDocumentTest
c MissionManagerPlanTest
c MissionManagerOutlineTest
c FlightPlanDaoTest
c ExerciseResolverTest

# The card wording a FAILED row produces, with a reason and without one.
c SortieGenerationStatusTest
c CoachStatusTest

# The sweep half of the issue: a row that has decided its outcome is not re-enqueued, so the failing path is not run
# again at the next app open. Existing case, named because the brief's second sentence is about it.
t decidedGenerationsAreLeftAlone

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
