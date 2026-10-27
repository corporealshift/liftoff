#!/usr/bin/env bash
# Verify: "The worker leaves a cancelled generation cancelled" (brief.md; DESIGN.md §7.3, ARCHITECTURE.md invariant 6).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the four tests that prove what the brief asks for, read out of the
# reports that gate produced, then the classes the brief's "Must not break" list names, then the exported Room schema.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports; a
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all four
# names under section 2 are new to this branch — none of them appears anywhere under app/src/test today — and on the
# untouched branch the gate passes, every class check and the schema check pass, and the four names report "did not
# run": exit 1.
#
# What is NOT checked here, and why:
# - The other ends that reach the same failure write — a permanent DaemonException, a Mission or Sortie that has gone
#   away, the 8th unreachable attempt. They all go through the one write the brief names, so the two mid-turn cases
#   plus the DAO case below cover the rule; the brief's own test list names three cases and the plan's Testing section
#   plans no fourth. The existing permanent-refusal and unreachable cases in GenerationWorkerTest are still run, by
#   the class check, and they must still end FAILED: the guard must refuse a cancelled row, not every row.
# - That the repair loop stops early when a row is cancelled mid-turn. The plan's Decision leaves the loop running and
#   treats an early stop as a follow-up, so the second case below asserts only what the brief asserts: the row's status
#   and error, what was stored, and how the work ended.
# - The race the brief describes for the mark-running step — a cancel landing between the worker's read of the row and
#   its mark-running write. That needs SQLite lock timing a test cannot control. The pre-cancelled worker case shows
#   the half a test can show (no turn is opened for a row that has already decided its outcome); the DAO case is the
#   one that proves the guard itself, which is why it is named below.
# - The plan's other Decisions: that a refusal on either path is reported to WorkManager as done rather than failed;
#   that the mark-running step accepts only QUEUED and RUNNING rows, so a late or duplicate attempt cannot drag a
#   SUCCEEDED or FAILED row back to RUNNING; that a cancelled row keeps the error text it already held rather than
#   having it blanked. Each is shown by the tests below as a consequence of what they assert, and the decisions
#   themselves are reviewed by Kyle in the pull request.
# - The FakeDaemon script step the plan adds so a test can act part-way through a scripted turn. It is test-only
#   scaffolding; what matters is that each mid-turn case gets its row cancelled before the event that ends the turn,
#   which is what the assertions below can only pass if that happened.
# - ARCHITECTURE.md's com.liftoff.app.work row and any other doc wording. Doc rows are reviewed by Kyle in the pull
#   request, so no doc row is checked here.
# - Which class each new test sits in, and the shape of the fix (a status-filtered @Query, a transaction, a return
#   value, where the worker checks). The plan puts the three worker cases in GenerationWorkerTest and the guard case
#   in GenerationDaoTest; the names below are matched across every report this run wrote.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/the-worker-leaves-a-cancelled-n8q3"

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
# Brief Done-when: "these tests pass and bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes".
# This gate is also:
# - the brief's "Must not break" check for the whole suite, and the check that the existing failure paths still fail
#   a row that was never cancelled: every GenerationWorkerTest case that expects FAILED — the blocked turn, the two
#   refused repairs, the permanent refusal, the protocol mismatches, the 8th unreachable attempt, the store that
#   found its Mission or sortie gone — runs here and must still land FAILED;
# - the compile check for every caller of the two row helpers: the worker, the canceller, the app-open sweep,
#   MissionManager and the Launchpad and In-Flight seeding in the tests all call markRunning and markFailed today,
#   and they have to keep compiling and passing as they stand;
# - the check that no test hangs and nothing depends on this machine's clock (CI is ubuntu-latest, UTC, and slower),
#   since app/build.gradle.kts kills a test task that goes five minutes without finishing — the brief's "every wait
#   bounded", and the hang the brief says to re-run rather than fix.
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
# - the known hang: the build kills the test task at five minutes and writes no TEST-*.xml at all (the brief's
#   "can hang once for about 5 minutes on this machine ... re-run it if that happens");
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

# ─── 2. The four things the brief asks for ─────────────────────────────────────
# Brief: "A coach generation that was cancelled while its turn was running must stay CANCELLED. Today
# GenerationWorker can overwrite it with FAILED."
#
# The three worker cases run on GenerationWorkerTest's existing harness: the application's own container, the fake
# rho daemon, and runWorker() under the class's own bounded wait. Each one gets its row CANCELLED while the turn is
# still open — before the event that ends the turn — so the worker reaches its failure write holding a row that has
# already decided its outcome. A case that cancels only after the turn has ended asserts FAILED today and fails here.
#
# In each case the row is cancelled through the DAO, not through the container's cancel helper: the helper sends its
# own rho.session.stop, so a stop frame the fake saw would not be proof the worker gave its session up. The one stop
# the fake sees is the worker's own.
echo ""
echo "=== a cancelled row survives the failure path, and the turn that never opened ==="

# Cancelled during a turn that then ends blocked. The turn is scripted the way
# aTurnThatEndsBlockedFailsTheGenerationWithCoachNeededInput scripts it — assistant lines, then the blocked state —
# with the cancel landing before that blocked state. The case must show: doWork() ends as done, not failure and not
# retry; the row is CANCELLED, never FAILED, and its error is the one it held before the cancel rather than
# 'coach needed input' (the plan's decision that a cancelled row keeps its own record, so nothing is written to it at
# all); nothing is stored on the Mission or its sorties; and the session the turn opened is given up exactly once,
# which is the brief's "it still stops the rho session if one was started".
t aGenerationCancelledMidTurnThatEndsBlockedStaysCancelledAndKeepsItsError

# Cancelled during a turn that then exhausts its repairs. The turn is scripted the way
# afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing scripts it — a refused reply, then a second
# one, with the cancel landing inside the first turn before the event that ends it — and the repairs then play out as
# they do today. The case must show: doWork() ends as done; the row is CANCELLED with its error unchanged, not
# 'invalid output: …'; nothing is stored on the Mission or its sorties; and the session is given up exactly once.
t aGenerationCancelledMidTurnThatRunsOutOfRepairsStaysCancelledAndStoresNothing

# Cancelled before the worker starts. The row is already CANCELLED when doWork() is asked for it. The case must show:
# doWork() ends as done; the row still reads CANCELLED with nothing written over it; and the fake daemon received no
# frame at all — no session created, no prompt sent — which is the brief's "the work ends quietly without opening a
# turn". The plan notes this case is green on the current code too, because doWork() already reports a finished row
# as done; the guard itself is what the DAO case below proves.
t aGenerationCancelledBeforeTheWorkerStartsOpensNoTurnAndEndsAsDone

# The two row helpers, at the row itself. Against in-memory Room, a row that has already decided its outcome — a
# CANCELLED row, and for the mark-running step also a SUCCEEDED and a FAILED one — is left exactly as it was by each
# write: markRunning does not pull it back to RUNNING, and markFailed writes no status, no error and no finishedAt to
# a CANCELLED row. Each write has to report that it changed no row, so the worker can tell a refusal from a write and
# end quietly instead of opening a turn or stamping a reason onto a row the user cancelled. A QUEUED and a RUNNING row
# are still marked, so the guard refuses a decided row rather than every row. This is the case that proves the
# mark-running guard bites: the plan's decision that a late or duplicate WorkManager attempt cannot drag a finished
# row back to RUNNING is only observable here, because a worker test cannot land a cancel between the worker's read
# of the row and its write.
t markRunningAndMarkFailedRefuseAGenerationThatAlreadyDecidedItsOutcome

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the existing worker, enqueue, cancel and turn-runner tests, Flight Plan and outline
# queueing, the Launchpad's draft status and the build gate." The gate above runs the whole suite; these name the
# classes so that one renamed away, skipped or deleted is caught, and a failure is reported as the class it belongs
# to rather than only as a red gate.
echo ""
echo "=== the tests the brief says must not break ==="

# The worker and the turn it runs, the enqueue helper, the canceller, and the generation rows both the worker and
# the app-open sweep act on. These are where the guard must not change an outcome that was never cancelled.
c GenerationWorkerTest
c CoachTurnRunnerTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c GenerationDaoTest
c AppOpenSweepTest

# Flight Plan and outline queueing, which seeds QUEUED rows and marks them through these same helpers.
c MissionManagerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c MissionManagerReFlyTest
c LaunchpadConfirmTest
c InFlightLandScrubTest
c InFlightRunTest
c FlightPlanDaoTest
c SortiePlanningTest
c ReFlyTest

# The Launchpad's draft status, which reads the Mission's outline generation live off the row the worker marks.
c LaunchpadDraftStatusTest
c LaunchpadCoachStatusTest
c LaunchpadStatesTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest
c LaunchpadScrubTest
c LaunchpadReFlyTest

# The app's own wiring around all of it, which the gate compiles and runs.
c AppContainerTest
c LiftoffShellTest
c MainActivityTest
c ShellNavTest

# ─── 4. What the brief says not to change ──────────────────────────────────────
# Brief: "No Room schema change." LiftoffDatabase exports its schema to app/schemas at build time, so the exported
# schema is the record of what the entities are. It is compared against the tree this run started from, both as
# committed and as it stands after the gate run above — a rewritten 1.json or a new version file beside it is a
# schema change. A status filter added to an existing @Query touches no entity and leaves these files
# byte-identical, so a schema that moved means the DAO edit went further than the brief asked.
echo ""
echo "=== the Room schema ==="

# The baseline is the commit that ADDED this run's brief, not the newest commit that touched it, so the comparison
# does not move if the brief is ever edited during the run.
brief_commit="$(git log --diff-filter=A --format=%H -- "$RUN_DIR/brief.md" | tail -1 || true)"
if [ -z "$brief_commit" ]; then
    bad "the Room schema is unchanged (this run's brief.md is not in the history to compare against)"
    base_commit=""
else
    base_commit="$(git rev-parse "$brief_commit^" || true)"
fi
if [ -n "$base_commit" ]; then
    if ! git diff --quiet "$base_commit" HEAD -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the committed tree)"
    elif ! git diff --quiet "$base_commit" -- app/schemas 2>/dev/null; then
        bad "the Room schema is unchanged (the exported schema under app/schemas differs from $(git rev-parse --short "$base_commit") in the working tree after the gate run)"
    elif [ "$(git ls-tree -r --name-only "$base_commit" -- app/schemas | sort)" != "$(find app/schemas -type f 2>/dev/null | sort)" ]; then
        # git diff cannot see an untracked file, and a Room entity change exports a NEW version file next to 1.json.
        bad "the Room schema is unchanged (the set of files under app/schemas is not the set this run started from)"
    else
        ok "the exported Room schema is byte-identical to the one this run started from"
    fi
fi

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
