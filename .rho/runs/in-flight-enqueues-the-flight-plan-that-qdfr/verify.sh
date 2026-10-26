#!/usr/bin/env bash
# Verify: "In-Flight enqueues the Flight Plan that land and scrub queued" (brief.md; DESIGN.md §5.3 and §7.2).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the seven tests that prove the enqueue, read out of the reports
# that gate run produced, then the classes the brief's "Must not break" list names, then the exported Room schema.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports; a
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all
# seven names under section 2 are new to this branch — none of them appears anywhere under app/src/test today, and
# InFlightViewModel discards the id its manager returns. Against the untouched branch the gate passes, every class
# check and the schema check pass, and the seven names report "did not run": exit 1.
#
# What is NOT checked here, and why:
# - That InFlightScreen passes a lambda calling container.generationEnqueuer().enqueue(id). No test can show that
#   without composing the real screen with WorkManager initialized and a coach configured, which the brief's own test
#   list rules out ("no WorkManager") and the plan rules out (no screen test gets a host and token). What is checked
#   is the half a test can show: InFlightScreenTest taps LAND through the real container, and the shell classes
#   compose it, all with no WorkManager initialized — so nothing WorkManager-related happens at composition or on a
#   land that queues nothing. The wiring itself is read off the diff in the pull request.
# - The shape of the new parameter (a defaulted (Long) -> Unit after missionManager) and that no existing test
#   construction site was edited. The gate compiles all fourteen existing InFlightViewModel(...) call sites as they
#   stand, so a site that had to be edited is a compile failure; the parameter's position is in the diff.
# - The plan's Decisions: that In-Flight shows nothing about the plan it queued; that a failed enqueue is met with
#   silence rather than a milder warning (the brief's own rule — that it is not the land/scrub failure message — is
#   checked below); that the enqueue is not gated on the leave watcher having set left; that no new double-tap guard
#   is added, the existing busy/left guards being what absorb a second tap (doubleTapLandsOnce and
#   runLandDoubleTapLandsOnce staying green is the check). Decisions are reviewed in the pull request.
# - "a later run re-enqueues lost rows at app open". That is a promise about a pass this brief does not add; nothing
#   today re-enqueues a row left QUEUED, and the plan records it as a risk the brief accepts.
# - ARCHITECTURE.md's M3 row, which still says In-Flight's enqueue "comes next". The brief limits the change to four
#   files and leaves that clause for a later doc pass, so no doc row is checked here.
# - Which class each new test sits in, and how the id travels out of the view model. The plan puts them in
#   InFlightLandScrubTest and InFlightRunTest; the names below are matched across every report this run wrote.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/in-flight-enqueues-the-flight-plan-that-qdfr"

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
# - the brief's "Must not break" check for the whole suite: every In-Flight test, the Launchpad tests, the worker
#   tests, the shell tests, the domain tests and the build gate;
# - the compile check for the new defaulted enqueue parameter: every existing InFlightViewModel(...) call site in
#   InFlightLandScrubTest, InFlightLeaveTest, InFlightRunTest, InFlightScreenTest and InFlightViewModelTest has to
#   keep compiling untouched, which is what a defaulted parameter after missionManager means in practice;
# - the check that nothing asks WorkManager for while composing: InFlightScreenTest composes the real AppContainer
#   with no WorkManager initialized and taps LAND, and LiftoffShellTest, ShellNavTest and MainActivityTest compose
#   the shell the same way. AppContainer.generationEnqueuer() throws when WorkManager was never initialized, so an
#   enqueue reached at composition fails these classes rather than passing quietly;
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
# A compile error, or any other reported assertion failure, is a real result and is not retried.
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

# ─── 2. In-Flight hands the id its land / landRun / scrub queued to its enqueue input ──
# Brief: "After land, landRun and scrub return a non-null id, call the lambda once with it. Call it only after the
# manager call has returned, and not at all when the id is null."
#
# Each case is the view model on in-memory Room against the mission the class's own fixture builds, with a recording
# lambda in place of WorkManager — no WorkManager anywhere, no screen, no daemon. Robolectric @Config(sdk = [34]),
# the class's fixed clock and its scope whose CoroutineExceptionHandler records anything that escapes, every wait
# bounded. The recording lambda is what the view model is given; the assertions are on the ids it was handed and on
# the Generation row that id names when it is read back out of the database after the action has returned.
#
# The coach has to be configured for a row to be queued at all (DESIGN.md §7.2, MissionManager.advance returns null
# when host or token is blank), so each case sets a daemon host and token in the SettingsStore before onAppOpen, as
# the brief says. Where the sortie that becomes current is a RUN, the Generate run plans toggle has to be on too —
# with it off a run gets the simple 'Run' plan and nothing is queued (DESIGN.md §5.3) — and it has to be turned on
# only after the fixture has landed its own first sortie, because with it on a run sortie is left PENDING awaiting a
# generated plan and the fixture could not launch it. Which class each case sits in is the plan's call; the names
# are matched across every report.
echo ""
echo "=== the ids In-Flight queues, and what it does with them ==="

# Landing a LIFT sortie from In-Flight, with Mission Control configured, must hand the recording lambda exactly one
# id and nothing else, and that id must name a Generation row read back from the database as kind FLIGHT_PLAN, status
# QUEUED, on the sortie that landing made current — the run after the landed lift, not the row the fixture's own
# landing of its first sortie already queued for the lift in between. The sortie is LANDED and the completion
# callback ran once.
t landFromInFlightEnqueuesTheNextSortiesQueuedFlightPlanIdOnce

# Scrubbing from In-Flight does the same: exactly one id handed over, naming the QUEUED FLIGHT_PLAN row of the
# sortie the scrub made current, the sortie SCRUBBED, the completion callback run once.
t scrubFromInFlightEnqueuesTheNextSortiesQueuedFlightPlanIdOnce

# Landing a RUN through landRun with a distance and a duration must hand the recording lambda exactly one id, naming
# the QUEUED FLIGHT_PLAN row of the lift sortie that landing the run made current — a lift is always left awaiting
# generation, so no run-plan toggle is needed here. The run is LANDED with the distance and duration stored on it,
# and the completion callback ran once.
t landRunFromInFlightEnqueuesTheNextSortiesQueuedFlightPlanIdOnce

# With no host and no token set — and the same run-plan settings the case above uses, so this fails for the missing
# Mission Control and not for the toggle — landing must hand the recording lambda nothing at all: no ids, not even a
# null or a zero. The sortie still lands, the completion callback still runs, and no failure message is raised.
t landWithMissionControlUnsetEnqueuesNothingAndStillLands

# A lambda that throws when it is called must not turn a sortie that did land into the failure message: the sortie is
# LANDED, the completion callback still ran once, the view model's message stayed null, and nothing reached the
# scope's exception handler. The QUEUED row the manager wrote is still in the database.
t landWithAFailingEnqueueStillLandsAndShowsNoMessage

# The same through scrub: the sortie is SCRUBBED, the completion callback ran once, message stayed null, nothing
# escaped into the scope, and the queued row is still there.
t scrubWithAFailingEnqueueStillScrubbsAndShowsNoMessage

# The same through landRun on a run sortie: the run landed with its distance and duration, the dialog's field errors
# stayed clear, message stayed null, nothing escaped into the scope.
t landRunWithAFailingEnqueueStillLandsTheRunAndShowsNoMessage

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: every In-Flight test, the Launchpad tests, the worker tests, the shell tests, the domain
# tests and the build gate." The gate above runs the whole suite; these name the classes so that one renamed away,
# skipped or deleted is caught, and a failure is reported as the class it belongs to rather than only as a red gate.
#
# The In-Flight classes are the ones this run touches and the ones the new parameter reaches: every existing
# InFlightViewModel(...) construction site has to compile untouched, and the view model's land / landRun / scrub
# guards and message flow have to keep working with the enqueue call sitting inside them. The Launchpad classes are
# the pattern this copies and must not disturb; the worker and generation classes are the WorkManager side this run
# does not change; the shell classes are the ones that break if anything asks WorkManager for while composing.
echo ""
echo "=== the tests the brief says must not break ==="

# Every In-Flight test.
c InFlightLandScrubTest
c InFlightRunTest
c InFlightStatesTest
c InFlightLeaveTest
c InFlightViewModelTest
c InFlightScreenTest
c LandScrubDialogTest
c RunLandingTest
c NoteDialogTest

# The Launchpad tests, including the Confirm/scrub queueing this copies and the draft status beside it.
c LaunchpadStatesTest
c LaunchpadConfirmTest
c LaunchpadScrubTest
c LaunchpadReFlyTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest
c LaunchpadDraftStatusTest
c LaunchpadCoachStatusTest

# The worker tests and the generation rows they run on.
c GenerationWorkerTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c GenerationDaoTest
c CoachTurnRunnerTest

# The shell tests, which compose the real container with no WorkManager initialized.
c LiftoffShellTest
c ShellNavTest
c MainActivityTest
c AppContainerTest

# The manager whose returned id this now carries, and the domain tests the brief names.
c MissionManagerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c MissionManagerReFlyTest
c MissionsTest
c SortiesTest
c WeekTest
c RolloverTest
c SortiePlanningTest
c ReFlyTest
c CoachStatusTest

# ─── 4. What the brief says not to change ──────────────────────────────────────
# Brief: "No Room schema change." LiftoffDatabase exports its schema to app/schemas at build time, so the exported
# schema is the record of what the entities are. It is compared against the tree this run started from, both as
# committed and as it stands after the gate run above — a rewritten 1.json or a new version file beside it is a
# schema change. SchemaExportTest, which the gate runs, only checks the file mentions each table; it does not notice
# a changed entity.
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
