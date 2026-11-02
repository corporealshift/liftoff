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
# state says the action is available in exactly those cases; the screen draws the underlined action only then, and
# taking it leaves the In-Flight screen once.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's last line names the same two tasks), then the manager's un-launch, then the derived availability, then the
# action on screen and the leave, then the classes this change must not disturb.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports. A
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: every
# name below is new to this branch, and today nothing named unlaunch/un-launch exists anywhere in the repository —
# MissionManager has launch (MissionManager.kt:272), land (287) and scrub (257) and no way back,
# SortieTransitions.legalTransitions has no IN_FLIGHT → PLANNED pair, and InFlightScreen's Footer draws Land and Scrub
# and nothing else.
#
# What is NOT checked here, and why:
# - DESIGN.md and ARCHITECTURE.md. The brief asks for a dated amendment at the end of DESIGN.md with inline marks in
#   §5.1 and §9, and ARCHITECTURE.md's com.liftoff.app.ui row names In-Flight's actions. Doc wording is reviewed by
#   Kyle in the pull request; a script that greps a document for a sentence only dictates how it is phrased.
# - The exact label of the new action, its colour, its size, and where in the footer it sits. The brief names it
#   "Back to Launchpad" and says it is styled like Scrub; plan Decision "Where the action sits on In-Flight" puts it
#   between Land and Scrub. UnderlinedTextButton (ui/theme/Buttons.kt:155) upper-cases what it is given, so the node
#   text is the label upper-cased. Only presence and absence are asserted below. One fact the implementer needs: the
#   existing landFooterOffersLandAndScrubAndNoBack asserts no node whose text is exactly "BACK", and Loading's
#   BackFooter already draws "Back", so the loaded footers cannot use that word.
# - That the refusal path sets a message on the view model, or that a double tap writes once. The brief's rule is that
#   the action is absent when it is unavailable, so a refusal is the defensive path a user should not reach; the plan
#   lists those cases for InFlightLandScrubTest and the gate runs that class either way.
# - That the availability flag is a field on InFlightState or a free predicate beside needsLandConfirmation, which
#   InFlightState.kt already does for the Land confirmation. The tests below read whatever the derived state exposes.
# - That the transition goes through SortieTransitions.requireTransition, or that a new free function sits beside
#   launch in domain/Sorties.kt. SortiesTest is run below as a class, so the legal-pair table still rejects every pair
#   it always rejected; how the manager reaches PLANNED is read off the diff.
# - That the action reaches the footer through the shared Footer composable rather than being drawn per state, or that
#   InFlightStates.kt needed no change. Which file a test sits in is the implementer's call too: names are matched
#   across every report.
# - Whether in-flight notes block un-launch or survive it, beyond what the first test below asserts. Plan Decision
#   "notes, both the sortie note and exercise notes, neither block un-launch nor are deleted by it" is asserted there
#   because the manager rewrites the sortie row anyway; no separate test is added just to pin it.
# - Whether un-launch queues anything with the coach, and which sortie is current afterwards. Both are plan Decisions
#   ("no new generation path", "the same one") and both are asserted inside the first test below rather than as tests
#   of their own.
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
# tasks .github/workflows/ci.yml runs. This is also the "must not break" check for the whole suite. Stale reports are
# moved aside first, so every named test below comes from this run. --no-build-cache stops the reports being restored
# from the cache.
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
# - the hang this repo records ("a hung Liftoff gate dies at the build's 5-minute test-task timeout writing no
#   TEST-*.xml"): a run that wrote no report at all, or a build task that failed with no test failing;
# - a build killed by a daemon, memory or executor error;
# - doneBodyweightSetStepperShowsBW alone, the one In-Flight screen name this repo has recorded failing at random with
#   a closed Room pool — and this run edits that very class, so a lone failure there is retried, not believed.
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
# land and scrub: a Mission inserted with weekStart 2026-01-12 under Clock.fixed(2026-01-12T00:00:00Z, UTC) so it is
# the current week, sorties inserted directly in the state the case needs, and a lift plan written with
# flightPlanDao.writePlan. A refusal is asserted the way this file already asserts one — the call throws
# IllegalStateException, and the rows are compared against a snapshot taken before it.

echo ""
echo "=== un-launch through the manager ==="

# Brief test bullet "un-launch of an untouched in-flight lift returns it to PLANNED with launchedAt null and the plan
# intact", plus the Decisions that are visible in that same write. Must show: an IN_FLIGHT current lift sortie of
# an ACTIVE Mission, its plan's sets all SetStatus.OPEN with every actual null, is IN_FLIGHT → PLANNED with
# launchedAt == null (not merely a smaller value, and not the launch time left in place — markPlanned already exists
# and does not clear it, so reusing it fails this). The plan is intact: same plan row, same exercise rows and ids, same
# set ids, same planned reps/seconds/weight, no set added and none removed. Plan Decision "Which sortie is current
# afterwards: the same one" — domain currentSortie still names this sortie. Plan Decision "no new generation path" —
# no Generation row of any kind is written by the un-launch. Plan Decision "notes … neither block un-launch nor are
# deleted by it" — the sortie carries a Sortie.notes and one exercise a userNotes before the call and both read the
# same afterwards. Plan Decision "Does an added set block un-launch, and does it survive? no, and yes" — the plan holds
# one set written by flightPlanDao.addSet, which is OPEN with null actuals, and the un-launch both succeeds and leaves
# that added set in the plan.
t unlaunchReturnsAnUntouchedInFlightLiftToPlannedWithLaunchedAtNull

# Brief test bullet "It is refused once any set is recorded", which the brief defines as "no set has been checked off,
# edited or skipped: every planned set is still SetStatus.OPEN with no actual reps, seconds or weight". Must show: the
# call refused, and the sortie left IN_FLIGHT with its launchedAt unchanged and its plan byte-for-byte the same, for
# each way a set becomes recorded — checkSet (DONE, actuals := the planned values), saveSetEdit (DONE, actuals := the
# edited values), and skipSet (SKIPPED). The recorded set must not have to be on the active card: the same refusal has
# to hold for a set on a later exercise of the plan, and for a whole exercise skipped with skipExercise, which marks
# that exercise's OPEN sets SKIPPED while the first card is still all OPEN. That last case is the plan's named risk —
# deriveInFlight marks such a card CardStatus.SKIPPED, so a rule that reads only the active card calls this sortie
# untouched and offers an action the manager then refuses.
t unlaunchIsRefusedOnceAnySetIsRecorded

# Brief test bullet "It is refused for a non-current sortie or a non-ACTIVE Mission", i.e. "the same checks
# MissionManager.launch makes" — which are Mission ACTIVE and this sortie is the current one. launch's third check,
# requireNoneInFlight, is launch's own and cannot be copied here: the sortie being un-launched is itself the one in
# flight, so a manager that reuses that guard refuses the only case this action exists for. Must show: refused for a
# sortie of an ACTIVE Mission that is not the current one (a lower index is still open), and refused for a Mission
# that is DRAFT and for one that is CLOSED — each time with the targeted sortie row and its plan exactly as they
# were, so a refusal cannot half-do the write.
t unlaunchIsRefusedForANonCurrentSortieOrANonActiveMission

# Brief test bullet "It is refused for a sortie that isn't IN_FLIGHT". Must show: refused for a PLANNED and for a
# PENDING current sortie of an ACTIVE Mission — those two pass the ACTIVE and current checks, so only the state check
# can refuse them, and that is the case that isolates it — and refused for a LANDED and for a SCRUBBED sortie, which
# the app has already finished and cannot pull back into the week. Each row left exactly as it was.
t unlaunchIsRefusedForASortieThatIsNotInFlight

# Brief test bullet "An in-flight run can always be un-launched", because "the distance and minutes are only entered at
# Land", and plan Decision "Is un-launch offered on a sortie with no checklist? yes". Must show: un-launch succeeds for
# a RUN sortie that holds a plan (the simple 'Run' plan the app writes for a run sortie is the realistic one), for a
# RUN sortie with no Flight Plan row at all, and for a LIFT sortie whose plan holds no exercises — each returning to
# PLANNED with launchedAt null. A plan with no sets has nothing recorded by the brief's own definition, and an empty or
# missing plan is exactly the case where someone wants a different plan.
t anInFlightRunOrASortieWithNoChecklistIsUnLaunchable

# Brief test bullet "After un-launch: Launch works again". Must show: on the same sortie id, manager.launch succeeds
# after the un-launch and the row is IN_FLIGHT again with a launchedAt that is not null — so the un-launch really did
# clear the launch, and it did not leave a row the launch guard refuses. The plan's stated trap is that markPlanned
# leaves launchedAt alone; this is the case that notices.
t launchWorksAgainAfterUnlaunch

# Brief test bullet "After un-launch: Regenerate (regenerateFlightPlan) is accepted", and the brief's reason for the
# whole action: "The PLANNED card then offers its existing Launch, Regenerate and Scrub." Must show:
# manager.regenerateFlightPlan on the un-launched sortie does not throw, hands one id to its onQueued callback, and
# that id is a QUEUED FLIGHT_PLAN Generation row for this sortie when read back from GenerationDao; the sortie is still
# PLANNED and still holds the plan it had. regenerateFlightPlan already accepts a PLANNED current sortie, so this is
# the downstream half of the brief — the reason the action exists — and it is what fails if un-launch lands the sortie
# in a state the regenerate checks refuse. The fixture has to be the one the existing regenerate tests use: coach host
# and token set, and a Mission whose weekStart is the current week of the test clock, or it is refused for a reason
# that has nothing to do with this change.
t regenerateFlightPlanIsAcceptedAfterUnlaunch

# ─── 3. The In-Flight state's availability rule ────────────────────────────────
# Brief: "ui/inflight/: the state (InFlightState/InFlightStates) exposes whether un-launch is available" and its test
# bullets "the action is available for an untouched lift and for a run. It is unavailable after one set is checked."
#
# The derive-level tests, on the fixture InFlightStatesTest already builds (a LIFT sortie with a 3-exercise, 7-set
# plan) plus a RUN sortie, reading whatever the derived state exposes for this — a field on the state or a free
# predicate beside needsLandConfirmation, which is what InFlightState.kt already does for the Land confirmation.
# deriveInFlight never reads sortie.state, so the rule has to be a rule over the sets.

echo ""
echo "=== the derived availability ==="

# Brief test bullet "the action is available for an untouched lift and for a run". Must show: the state derived from
# the untouched fixture plan — every set OPEN, every actual null — says un-launch is available; the same for a RUN
# sortie holding a plan (the Run state) and for a sortie with no plan or no exercises (the NoChecklist state), which is
# where plan Decision "Is un-launch offered on a sortie with no checklist? yes" lands. Only the Loading state, which
# has no sortie loaded yet, is allowed to say it is not available.
t unlaunchIsAvailableForAnUntouchedLiftAndForARun

# Brief test bullet "It is unavailable after one set is checked", and the same rule for the other ways a set is
# recorded. Must show: the state says un-launch is not available after flightPlanDao.checkSet on one set, after
# saveSetEdit, after skipSet, and after skipExercise on an exercise whose sets are all SKIPPED while the first card is
# still all OPEN — the plan's named risk, that a rule reading only the active card calls such a plan untouched. Then
# the way back: after uncheckSet / reopenSet / unskipExercise, which restore status OPEN with null actuals, the state
# says it is available again. A user who mis-tapped and undid it is back where they were, and should get the action
# back.
t unlaunchIsUnavailableOnceAnySetOfThePlanIsRecorded

# ─── 4. The action on the In-Flight screen, and leaving to the Launchpad ───────
# Brief: "The screen shows an underlined text action, styled like Scrub, only when available", "When anything has been
# recorded, the action is absent rather than dimmed", and "After un-launching, the app goes to the Launchpad."

echo ""
echo "=== the action on screen ==="

# Brief "the action is absent rather than dimmed, which matches the Launchpad's rule for unavailable actions", and
# "The screen shows an underlined text action, styled like Scrub, only when available". Must show, on the real
# InFlightScreen over the AppContainer InFlightScreenTest already uses, that the un-launch action is a node that
# exists and is enabled on each of the three loaded states and on none of the recorded ones: it is there for an
# untouched in-flight LIFT sortie (the Ready state), with LAND and SCRUB still there beside it; it is there for an
# in-flight RUN sortie that holds a plan (the Run state, whose footer RunContent.kt draws) — the sortie the issue is
# actually about; and it is there for an in-flight sortie with no plan at all (the NoChecklist state), which is plan
# Decision "Is un-launch offered on a sortie with no checklist? yes". And for an in-flight LIFT sortie with one set
# already recorded that node does not exist at all — assertIsNotFound, not assertIsNotEnabled, because
# UnderlinedTextButton has no enabled parameter and an unavailable action here is simply not drawn. All three states
# are already rendered by this class (landFooterOffersLandAndScrubAndNoBack for Ready and NoChecklist,
# runScreenShowsPlanAndOffersNoteLandAndScrub for Run), and one composition can switch between sorties the way
# landFooterOffersLandAndScrubAndNoBack does, with a mutableStateOf id and key(): each recorded set is written to Room
# before the composition that shows that sortie, not by a tap mid-test.
# Plan Decision "Where the action sits on In-Flight" (Land, then Back to Launchpad, then Scrub) is what the footer
# draws; only presence and absence are asserted here. The label is the one the brief names, upper-cased by
# UnderlinedTextButton, and it cannot be the bare word BACK — the existing footer assertion in this class rejects a
# node whose text is exactly that, and Loading's BackFooter already uses it.
t inFlightFooterOffersBackToLaunchpadOnlyWhileNothingIsRecorded

# Brief "After un-launching, the app goes to the Launchpad." The cheapest proof is the screen's own leave, on the
# harness InFlightLeaveTest already uses (continue an RLRLR week without an outline, land sortie 0, seed a lift plan on
# sortie 1, launch it, watch its own row): driving the un-launch through the view model leaves the In-Flight screen
# exactly once — the leave counter reaches 1 and stays 1, because the screen's own action and the row re-emission both
# reach the same once-only gate — and the row is PLANNED with launchedAt null. The shell already routes that leave to
# the tab the screen was opened from (LiftoffShell hosts InFlightScreen with onBack = nav.back()), so no shell change
# is needed and none is tested here.
t unlaunchOnThisScreenLeavesOnlyOnce

# ─── 5. What this change must not disturb ──────────────────────────────────────
# The domain transition table, the manager's existing sortie actions, the rest of the In-Flight screen, and the
# Launchpad card the brief says an un-launched sortie lands on. The gate runs the whole suite; these names only catch
# one of them renamed away, skipped or deleted.
#
# SortiesTest is the legal-pair table the brief says the transition belongs in: legalTransitionsSucceed and
# illegalTransitionsAreRejected enumerate every pair, so adding IN_FLIGHT → PLANNED must not quietly make a LANDED or
# SCRUBBED sortie re-openable.
echo ""
echo "=== the domain table and the manager's other sortie actions ==="

c SortiesTest
c MissionManagerTest
c MissionManagerPlanTest
c MissionManagerReFlyTest
c SortieDaoTest
c FlightPlanDaoTest

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

# The two Compose classes above, with InFlightScreenTest, render the whole In-Flight screen at a fixed @Config window
# height. A footer that grows can break an existing assertIsDisplayed in them, so they are named here rather than left
# to the gate's total.

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
