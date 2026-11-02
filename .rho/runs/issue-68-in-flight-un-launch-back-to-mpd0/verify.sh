#!/usr/bin/env bash
# Verify: "In-Flight: un-launch back to PLANNED so the plan can be regenerated" (brief.md; issue
# corporealshift/liftoff#68).
# Definition of done for this run: exit 0 only when the brief is met.
#
# What is checked, in one line: a sortie that is IN_FLIGHT can be put back to PLANNED by an action In-Flight offers
# only while nothing on that sortie has been recorded — MissionManager.unlaunch clears launchedAt, sets the state back
# to PLANNED and leaves the Flight Plan, its exercises and its sets exactly as they were; it is refused once any set of
# the plan is checked off, edited or skipped, refused for a sortie that is not IN_FLIGHT, and refused for a sortie that
# is not the current one or a Mission that is not ACTIVE; a run sortie and a sortie with no checklist can always be
# un-launched; afterwards Launch works again on the same sortie and regenerateFlightPlan accepts it; the In-Flight
# state says the action is available in exactly those cases; and taking it through the view model leaves the In-Flight
# screen once.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's last line names the same two tasks), then the manager's un-launch, then the derived availability, then the
# leave, then the classes this change must not disturb.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports. A
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: every
# name below is new to this branch, and today nothing named unlaunch/un-launch exists anywhere in the repository.
#
# What is NOT checked here, and why:
# - DESIGN.md and ARCHITECTURE.md. The brief asks for a dated amendment at the end of DESIGN.md with inline marks in
#   §5.1 and §9, and ARCHITECTURE.md's com.liftoff.app.ui row names In-Flight's actions. Doc wording is reviewed by
#   Kyle in the pull request; a script that greps a document for a sentence only dictates how it is phrased.
# - A Compose test of the footer. The brief's Tests section asks for the availability rule at the In-Flight state
#   level, not for a test driven through the UI; the state tests below are what decides whether the action is drawn,
#   and the footer itself is read off the diff. The existing In-Flight Compose classes still run (section 5), so a
#   footer that breaks them fails here. One fact the implementer needs: the existing
#   landFooterOffersLandAndScrubAndNoBack asserts no node whose text is exactly "BACK" (UnderlinedTextButton
#   upper-cases its label), and Loading's BackFooter already draws "Back", so the loaded footers cannot use that bare
#   word. Plan Decision "Where the action sits on In-Flight" (Land, Back to Launchpad, Scrub) is likewise reviewed in
#   the pull request.
# - That the refusal path sets a message on the view model, or that a double tap writes once. The brief's rule is that
#   the action is absent when it is unavailable, so a refusal is the defensive path a user should not reach.
# - That the availability flag is a field on InFlightState or a free predicate beside needsLandConfirmation. The tests
#   below read whatever the derived state exposes.
# - That the transition goes through SortieTransitions.requireTransition, or that a new free function sits beside
#   launch in domain/Sorties.kt. SortiesTest is run below as a class, so the legal-pair table still rejects every pair
#   it always rejected.
# - Which file a test sits in: names are matched across every report.
# - Whether the Launchpad needed any change. It does not: an un-launched sortie is a PLANNED current sortie, which
#   LaunchpadState already draws with Launch, Regenerate and Scrub. The Launchpad classes below are run unchanged.
# - Which other tests in the suite break. The gate runs every test in the repo, so a break anywhere else already fails
#   the gate. The class names below only catch a named class that was renamed away, skipped or deleted.
# - Line endings, commit style, CLAUDE.md conventions, comment length, and the pull-request description.
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
            pend && /<skipped/ { if (st == "skipped") st = "skipped"; if (st == "pass") st = "skipped" }
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

# t <method>: the named test must have run in this invocation and passed. Names are matched across every report, so
# which file and package a test sits in is the implementer's call. What it must show is written in the comment above.
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
# Brief: "Run the gate before pushing: bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest" — the same two
# tasks .github/workflows/ci.yml runs. Stale reports are moved aside first, so every named test below comes from this
# run. --no-build-cache stops the reports being restored from the cache.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

clear_results() {
    # Moved, not deleted: a stranded Gradle test executor can hold files under test-results. No gradlew --stop here:
    # a stop is machine-wide and would take down another worktree's daemon.
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
# - the hang this repo records: a run that wrote no report at all, or a build task that failed with no test failing;
# - a build killed by a daemon, memory or executor error;
# - doneBodyweightSetStepperShowsBW alone, the one In-Flight screen name this repo has recorded failing at random.
# A compile error, or any other reported assertion failure, is a real result and is not retried.
transient_failure() {
    local log="$WORK/gate-$1.log"
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|exceeded its configured timeout|Requesting stop of task|Test Executor|PersistentEnumerator storage corrupted|Unable to delete directory' "$log" 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$log" 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    local any other
    [ -f "$WORK/methods" ] || return 0
    any="$(awk -F '\t' '$2 == "fail"' "$WORK/methods" | wc -l || true)"
    other="$(awk -F '\t' '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l || true)"
    any="${any:-0}"; other="${other:-0}"
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

# ─── 2. MissionManager.unlaunch: the state change and its guards ───────────────
# Brief: "suspend fun unlaunch(sortieId: Long). In a transaction, check: Mission ACTIVE, sortie current, state
# IN_FLIGHT, nothing recorded on its sets. Then update to PLANNED with launchedAt = null." and "Un-launching clears
# launchedAt and sets the state back to PLANNED. The Flight Plan, its exercises and its sets are unchanged."
#
# These are the manager's own tests, on in-memory Room, in the harness MissionManagerTest already uses for launch,
# land and scrub. A refusal is asserted the way that file already asserts one, and the rows are compared against a
# snapshot taken before it.

echo ""
echo "=== un-launch through the manager ==="

# Brief test bullet "un-launch of an untouched in-flight lift returns it to PLANNED with launchedAt null and the plan
# intact". Must show: an IN_FLIGHT current lift sortie of an ACTIVE Mission, its plan's sets all SetStatus.OPEN with
# every actual null, goes to PLANNED with launchedAt == null (markPlanned exists and does not clear it, so reusing it
# fails this). The plan is intact: same plan row, same exercise ids, same set ids, same planned reps/seconds/weight, no
# set added or removed. This same test also shows these plan Decisions: "Which sortie is current afterwards? the same
# one" (currentSortie still names this sortie); "Does un-launch queue anything with the coach? no" (no Generation row
# is written); "Do in-flight notes block un-launch, and do they survive it?" (a Sortie.notes and an exercise userNotes
# set beforehand read the same afterwards); "Does an added set block un-launch, and does it survive? no, and yes" (one
# set written by flightPlanDao.addSet is in the plan, the un-launch succeeds, and that set is still there).
t unlaunchReturnsAnUntouchedInFlightLiftToPlannedWithLaunchedAtNull

# Brief test bullet "It is refused once any set is recorded", where recorded means "checked off, edited or skipped".
# Must show: the call refused, and the sortie left IN_FLIGHT with launchedAt unchanged and its plan the same, for
# checkSet, saveSetEdit and skipSet — and for a set recorded on a later exercise of the plan, and for a whole exercise
# skipped with skipExercise while the first card is still all OPEN (the plan's named risk: a rule that reads only the
# active card would call that sortie untouched).
t unlaunchIsRefusedOnceAnySetIsRecorded

# Brief test bullet "It is refused for a non-current sortie or a non-ACTIVE Mission". Must show: refused for a sortie of
# an ACTIVE Mission that is not the current one, and for a DRAFT and a CLOSED Mission — each time with the targeted
# sortie row and its plan exactly as they were. (launch's requireNoneInFlight cannot be copied here: the sortie being
# un-launched is itself the one in flight.)
t unlaunchIsRefusedForANonCurrentSortieOrANonActiveMission

# Brief test bullet "It is refused for a sortie that isn't IN_FLIGHT". Must show: refused for a PLANNED current sortie
# of an ACTIVE Mission (only the state check can refuse that one), and for a LANDED and a SCRUBBED sortie. Each row
# left exactly as it was.
t unlaunchIsRefusedForASortieThatIsNotInFlight

# Brief test bullet "An in-flight run can always be un-launched", plus plan Decision "Is un-launch offered on a sortie
# with no checklist? yes". Must show: un-launch succeeds for a RUN sortie that holds a plan, for a RUN sortie with no
# Flight Plan row, and for a LIFT sortie whose plan holds no exercises — each returning to PLANNED with launchedAt null.
t anInFlightRunOrASortieWithNoChecklistIsUnLaunchable

# Brief test bullet "After un-launch: Launch works again". Must show: on the same sortie id, manager.launch succeeds
# after the un-launch and the row is IN_FLIGHT again with a non-null launchedAt.
t launchWorksAgainAfterUnlaunch

# Brief test bullet "After un-launch: Regenerate (regenerateFlightPlan) is accepted". Must show:
# manager.regenerateFlightPlan on the un-launched sortie does not throw and queues a FLIGHT_PLAN Generation for this
# sortie; the sortie is still PLANNED. The fixture has to be the one the existing regenerate tests use (coach
# configured, Mission on the current week of the test clock), or it is refused for an unrelated reason.
t regenerateFlightPlanIsAcceptedAfterUnlaunch

# ─── 3. The In-Flight state's availability rule ────────────────────────────────
# Brief: "ui/inflight/: the state (InFlightState/InFlightStates) exposes whether un-launch is available" and its test
# bullets "the action is available for an untouched lift and for a run. It is unavailable after one set is checked."
# This rule is what decides whether the screen draws the action (the brief: absent, not dimmed, when unavailable).

echo ""
echo "=== the derived availability ==="

# Brief test bullet "the action is available for an untouched lift and for a run". Must show: the state derived from
# an untouched lift plan says un-launch is available; the same for a RUN sortie holding a plan, and — plan Decision
# "Is un-launch offered on a sortie with no checklist? yes" — for the NoChecklist state.
t unlaunchIsAvailableForAnUntouchedLiftAndForARun

# Brief test bullet "It is unavailable after one set is checked", and the brief's rule that edited or skipped sets
# count as recorded too. Must show: not available after checkSet on one set, after saveSetEdit, after skipSet, and
# after skipExercise on a later exercise while the first card is still all OPEN. After uncheckSet / reopenSet /
# unskipExercise, which restore status OPEN with null actuals, it is available again — by the brief's own definition
# nothing is recorded then.
t unlaunchIsUnavailableOnceAnySetOfThePlanIsRecorded

# ─── 4. Leaving to the Launchpad ───────────────────────────────────────────────
# Brief: "The ViewModel calls the manager." and "After un-launching, the app goes to the Launchpad."

echo ""
echo "=== the view model's un-launch and the leave ==="

# Must show, on the view-model harness InFlightLeaveTest already uses (no Compose): driving the un-launch through the
# view model leaves the In-Flight screen exactly once — the leave counter reaches 1 and stays 1 — and the row is
# PLANNED with launchedAt null. The shell already routes that leave to the Launchpad (onBack = nav.back()), so no
# shell change is tested here.
t unlaunchOnThisScreenLeavesOnlyOnce

# ─── 5. What this change must not disturb ──────────────────────────────────────
# The gate runs the whole suite; these names only catch one of them renamed away, skipped or deleted.
# SortiesTest enumerates every legal and illegal pair, so adding IN_FLIGHT → PLANNED must not make a LANDED or
# SCRUBBED sortie re-openable.
echo ""
echo "=== the domain table and the manager's other sortie actions ==="

c SortiesTest
c MissionManagerTest
c MissionManagerPlanTest
c MissionManagerReFlyTest
c SortieDaoTest
c FlightPlanDaoTest

# InFlightScreenTest, LandScrubDialogTest and NoteDialogTest render the In-Flight screen at a fixed window height, so a
# footer that grows can break an existing assertion in them.
echo ""
echo "=== the rest of In-Flight ==="

c InFlightStatesTest
c InFlightViewModelTest
c InFlightLandScrubTest
c InFlightScreenTest
c InFlightLeaveTest
c InFlightRunTest
c LandScrubDialogTest
c NoteDialogTest

# The brief: "The PLANNED card then offers its existing Launch, Regenerate and Scrub. No new generation path is
# needed." Nothing here changes, so the Launchpad's own tests have to read exactly as they do today.
echo ""
echo "=== the Launchpad card an un-launched sortie returns to ==="

c LaunchpadStatesTest
c LaunchpadPlannedActionsTest
c LaunchpadRegenerateTest
c LiftoffShellTest
c ShellNavTest

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
