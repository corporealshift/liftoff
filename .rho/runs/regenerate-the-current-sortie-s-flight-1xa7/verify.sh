#!/usr/bin/env bash
# Verify: "Regenerate the current sortie's Flight Plan (no UI)" (brief.md; DESIGN.md §5.1, §5.3, §7.2, §7.3).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the tests that prove what the brief asks for, read out of the
# reports that gate produced, then the classes the brief's "Must not break" list names, then the exported Room schema.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports; a
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: every
# name under section 2 is new to this branch — no method containing "regenerate" exists anywhere under app/src/test
# today — and on the untouched branch the gate passes, every class check and the schema check pass, and the names
# under section 2 report "did not run": exit 1.
#
# What is checked, in one line: the manager operation refuses every condition the brief names and, when it accepts,
# queues exactly one new QUEUED FLIGHT_PLAN row and hands back the sortie's other open rows; the view-model action
# enqueues the new id and cancels those, is available only on Pending and Planned, and a refusal leaves the Launchpad
# exactly as it was; the existing plan and the PLANNED state survive the ask until a new result replaces them.
#
# What is NOT checked here, and why:
# - Any UI. The brief says "No UI change" and a later run adds the button; the plan's Testing section rules Compose
#   out of this run's tests ("no Compose, no WorkManager and no daemon"). So no screen test is named, and no check
#   asks what a card looks like. The shell tests below are named only as the brief's "must not break" list.
# - That the new row is ever sent to Mission Control, or that a superseded row's result is dropped. The enqueue and
#   cancel helpers, the worker's currency guard and the app-open sweep already exist and are unchanged; the worker
#   cases named by the previous run are still run by the gate and by the class checks below. What this run adds is
#   only the asking side, so the named tests stop at the ids handed to the two lambdas and the rows in the table.
# - That a daemon answered the cancel. The plan's Risks say a failed cancel leaves two open rows and the worker and
#   the sweep already handle that, so the tests assert the ids handed to the cancel lambda, not a rho frame.
# - The plan's Decisions. The owner reviews them in the pull request, so none gets a named test of its own: the
#   "Generate run plans" toggle refusal on a run sortie (changed by review), that nothing on screen says a
#   regeneration is underway, that a refusal says nothing, and that only configuration (not reachability) is
#   checked. Where a named test below happens to show a decision, its comment names it.
# - Which class each new test sits in, and the shape of the manager operation (which guards, in what order, whether
#   it reuses a helper). The names below are matched across every report this run wrote, so a test may sit wherever
#   the implementer likes as long as it shows what its comment says.
# - ARCHITECTURE.md's M3 row and any other doc wording. Doc rows are reviewed by Kyle in the pull request, so no
#   doc row is checked here.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
RUN_DIR=".rho/runs/regenerate-the-current-sortie-s-flight-1xa7"

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
# - the brief's "Must not break" check for the whole suite: the Launchpad, Re-fly, scrub, Flight Plan and outline
#   queueing, the worker, In-Flight, the shell and the domain tests all run here against the new manager operation
#   and the new view-model action;
# - the compile check for every caller: MissionManager, LaunchpadViewModel, MainActivity's two lambdas, the worker,
#   the canceller and the app-open sweep have to keep compiling and passing as they stand;
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
#   "The full gate can hang once for about 5 minutes on this machine. Run it detached and re-run it if that
#   happens");
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - doneBodyweightSetStepperShowsBW alone, the one name this repo has recorded failing at random on a Room pool
#   closed under a still-collecting observe() and passing on a re-run of the identical tree.
# A compile error, or any other reported assertion failure, is a real result and is not retried. That includes a
# new regenerate case that found a row it did not expect: a regenerate case that cannot get its fixture into the
# state its comment describes is a real result about how the case was written, not a machine fault.
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

# ─── 2. The things the brief asks for ──────────────────────────────────────────
# Brief: a MissionManager regenerate operation for a sortie, and LaunchpadViewModel.regenerate(): Job?, available
# on Pending and Planned. On success the manager inserts one new QUEUED FLIGHT_PLAN row and returns that id plus
# the ids of the sortie's other open FLIGHT_PLAN rows; the view model enqueues the new id and cancels the others.
# Every refusal is an IllegalStateException and writes nothing.
#
# Each case below is a Robolectric test on in-memory Room with recording queueGeneration / cancelGeneration lambdas
# and no WorkManager and no daemon, on the harness LaunchpadConfirmTest already uses: a fixed UTC clock, a real
# SettingsStore with host and token set before onAppOpen(), and a scope whose exception handler is read at the end.
# In every case the action's Job is joined before anything is read back, and a null Job is an assertion failure, not
# a pass — that guard is what stops a case from passing on an action that did nothing at all.
#
# "Writes nothing" means, in every refusal case: the generation table holds exactly the rows it held before, with
# the same statuses; no id was handed to the enqueue lambda and none to the cancel lambda; the Mission and its
# sorties are unchanged; and nothing escaped into the scope's exception handler.
echo ""
echo "=== asking for a fresh Flight Plan: supersede, retry, and the plan that stays ==="

# Brief bullet 1, and it shows the plan's Decision "Asking twice supersedes the first request; it isn't refused". On
# a PENDING current sortie of the current week's ACTIVE Mission that already holds an open FLIGHT_PLAN row — the one
# activating the week queues when host and token are set — one call of the view-model action must show: exactly one
# id handed to the enqueue lambda and exactly one to the cancel lambda, and they are different ids; the enqueued id
# reads back as a QUEUED FLIGHT_PLAN row on this Mission and this sortie; the cancelled id is the older row and
# reads back CANCELLED; and no other row of this Mission was touched. The ids handed to the two lambdas are the
# manager's return value, so this case is also the proof that the manager hands back the open rows it read before
# its own insert: getOpenFlightPlanForSortie is newest first, so a manager that read them after the insert would
# hand the row it just queued to the cancel lambda, and this case would fail.
t regenerateOnAPendingSortieCancelsItsOpenRowAndEnqueuesTheNewQueuedRow

# Brief bullet 2: the same action is the Retry a failed Flight Plan needs (it also shows the plan's Decision "Retry
# after a failed Flight Plan starts a fresh conversation"). The sortie's newest FLIGHT_PLAN row is FAILED, with its
# own error and finishedAt. The action must show: a new QUEUED FLIGHT_PLAN row appears on this sortie and is the id
# enqueued; the FAILED row still reads FAILED with the same error and the same finishedAt, is neither cancelled nor
# enqueued, and the cancel lambda recorded nothing; and the Launchpad state is what it was. The FAILED row is seeded
# straight through the generation DAO with its error and its finishedAt — no worker and no daemon is involved.
t regenerateAfterAFailedFlightPlanQueuesAFreshRowAndLeavesTheFailedRowAlone

# Brief bullet 3, and the brief's "It never touches the sortie's existing plan, so a PLANNED sortie stays PLANNED
# and launchable until the new result replaces it". On a PLANNED current LIFT sortie the action must show: one id
# enqueued, and a new QUEUED FLIGHT_PLAN row on that sortie; the sortie's FlightPlan row is the plan it had — same
# title, same exercises — and the sortie is still PLANNED; and the derived Launchpad state is still Planned and
# carries that same plan, which is what keeps Launch on the card. A PLANNED sortie whose plan row was cleared
# derives as Pending, so the state assertion is what catches an operation that wiped the plan out before the new
# result arrived.
t regenerateOnAPlannedSortieKeepsItsPlanAndStaysPlanned

echo ""
echo "=== the refusals, each of which writes nothing ==="

# Brief bullet 4, the coach not configured: no host or no token. Through the view model, on a card the action is
# available on: the action returns a Job, the Job finishes, and the manager's IllegalStateException was caught
# inside it rather than escaping — the derived state is exactly what it was, no id was enqueued or cancelled, the
# generation table holds the rows it held before, and the scope's exception handler holds nothing.
t regenerateWithoutAHostOrTokenIsRefusedAndWritesNothing

# Brief bullet 4, an IN_FLIGHT sortie. The view model returns null on an InFlight card, so this is the manager
# asked directly with an IN_FLIGHT current sortie's id: it throws IllegalStateException, and the sortie is still
# IN_FLIGHT with its plan and its exercises as they were, and the generation table holds the rows it held before.
t regenerateOnAnInFlightSortieIsRefusedAndWritesNothing

# Brief bullet 4, a sortie that is not the current one. The manager is asked directly with the id of a PENDING
# sortie of the current ACTIVE Mission that is not currentSortie. It throws IllegalStateException and writes
# nothing: no row for that sortie, no row for the current one.
t regenerateOnASortieThatIsNotCurrentIsRefusedAndWritesNothing

# The brief's refusal condition "the current week's ACTIVE Mission": an ACTIVE Mission whose weekStart is not the
# current week, with a PENDING current sortie, is refused by the manager with IllegalStateException and writes
# nothing.
t regenerateOnASortieOfAWeekThatIsNotCurrentIsRefusedAndWritesNothing

echo ""
echo "=== where the action is available ==="

# Brief: "LaunchpadViewModel.regenerate(): Job?, available on Pending and Planned (null otherwise)". The action
# must return a Job on a PENDING current sortie and on a PLANNED one, and null on a Draft week, on an IN_FLIGHT
# current sortie, on a Closed Mission or an ACTIVE Mission with no current sortie, and before the week has settled.
t regenerateIsAvailableOnPendingAndPlannedAndNullOnEveryOtherState

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the Launchpad tests, Re-fly, scrub, Flight Plan and outline queueing, the worker tests,
# In-Flight, the shell tests, the domain tests and the build gate." The gate above runs the whole suite; these name
# the classes so that one renamed away, skipped or deleted is caught, and a failure is reported as the class it
# belongs to rather than only as a red gate.
echo ""
echo "=== the tests the brief says must not break ==="

# The Launchpad itself: the same view model, the same two lambdas, the same derived states.
c LaunchpadConfirmTest
c LaunchpadReFlyTest
c LaunchpadScrubTest
c LaunchpadStatesTest
c LaunchpadCoachStatusTest
c LaunchpadDraftStatusTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest

# Flight Plan and outline queueing, and the manager operations a regenerate is described beside.
c MissionManagerTest
c MissionManagerOutlineTest
c MissionManagerPlanTest
c MissionManagerReFlyTest
c FlightPlanDaoTest

# The generation rows a regenerate writes and cancels, and everything that acts on them afterwards.
c GenerationDaoTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c GenerationWorkerTest
c CoachTurnRunnerTest
c AppOpenSweepTest

# Re-fly and scrub, at the domain and at In-Flight.
c ReFlyTest
c SortiePlanningTest
c SortiesTest
c MissionsTest
c RolloverTest
c WeekTest
c InFlightStatesTest
c InFlightViewModelTest
c InFlightLandScrubTest
c InFlightRunTest

# The app's own wiring, and the brief's "the shell tests".
c AppContainerTest
c MainActivityTest
c LiftoffShellTest
c ShellNavTest

# ─── 4. What the brief says not to change ──────────────────────────────────────
# Brief: "No Room schema change." LiftoffDatabase exports its schema to app/schemas at build time, so the exported
# schema is the record of what the entities are. It is compared against the tree this run started from, both as
# committed and as it stands after the gate run above — a rewritten 1.json or a new version file beside it is a
# schema change.
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
