#!/usr/bin/env bash
# Verify: "Draw the draft's coach status on the Launchpad"
# (brief.md; DESIGN.md §4.2 and §9 'Launchpad', design/README.md, design/screens/launchpad.html).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: first the CI gate (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks). Then the six rendered-draft cases, one for each thing the brief asks
# the draft to draw. Then the classes the brief's "Must not break" list names.
#
# Gradle exits 0 when a named test never existed, so each test below is looked up by name in this run's reports. A
# test that was never written cannot pass on its own, and that is what makes this script fail on untouched code. All
# six names in the rendered-draft section are new to this run and none is in the tree today. Today the draft draws
# the same chips, stepper, Confirm and always-visible 'Continue without outline' whatever the coach is doing.
#
# What is NOT checked here, and why:
# - The ARCHITECTURE.md M3 row. The brief asks for it, and the work step writes it. A regex over a doc row is a text
#   check, not a behaviour check, and it rejects correct work worded differently. Kyle reviews it in the pull request.
# - "Use the design's parts only, with no stock M3 visuals", and the failure reason being drawn in red. Compose
#   semantics carry no text colour and no component identity, so they are read off the diff in the pull request.
# - The wording of any copy the brief does not quote. The planning and offline lines are the brief's own strings and
#   are required below. The not-configured line is required by what it says, not by an exact sentence.
# - The plan's Decisions on the one status line, and on a Retry clearing the not-configured report. These are
#   decisions, not brief requirements. The owner reviews them in the pull request.
# - Which class the new cases sit in, and whether LaunchpadDraftControlsTest's cases built on the old drawing were
#   rewritten or deleted. The whole-class check below runs that class whatever its methods are called.
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

# t <method>: the named test must have run in this invocation and passed. Names are matched across every report, so
# the implementer decides which file and package a test sits in. The comment above each name says what it must show.
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
# Brief Done-when: "bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This is also the brief's
# "Must not break" check for the whole suite. Stale reports are moved aside first, so every named test below comes
# from this run. --no-build-cache stops the reports being restored from the cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

clear_results() {
    # Moved, not deleted: a stranded test executor can hold files in the results dir. No gradlew --stop, because a
    # stop is machine-wide and would take down another worktree's daemon.
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

# The gate is re-run only in these cases:
# - the known flake (doneBodyweightSetStepperShowsBW);
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - a hung suite that wrote no report (the hang the brief names).
# A compile error, or a real failed assertion other than the flake, is not retried.
transient_failure() {
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Unable to delete directory|exceeded.*timeout|Test Executor' "$WORK"/gate-*.log 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$WORK"/gate-*.log 2>/dev/null; then
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
    if transient_failure; then
        echo "  … attempt $attempt failed on the known flake or on the build itself; running the gate again"
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

# ─── 2. The draft drawn for each coach status ──────────────────────────────────
# Brief: "each status shows its line and exactly its buttons" and "the −/+ controls are absent while planning".
# Every case is render-only Robolectric Compose through the screen's internal LaunchpadContent(viewModel,
# onOpenInFlight) seam, or through a render seam the work adds. The view model is built on in-memory Room, with
# @Config(sdk = [34]) and a fixed test window. Every wait is bounded, no text field is typed into, and no WorkManager
# is used. The status comes from rows the way LaunchpadCoachStatusTest seeds them: host and token through the settings
# store, and OUTLINE Generation rows through db.generationDao() for the draft's own mission. Each case waits on a
# known string before it asserts anything.
#
# UnderlinedTextButton upper-cases its label, so the way out is asserted as CONTINUE WITHOUT OUTLINE. Confirm and
# Retry are asserted as CONFIRM and RETRY. "Exactly its buttons" is asserted both ways: each control the status
# offers is displayed and enabled, and each one it does not offer is asserted absent.
echo ""
echo "=== the draft drawn for each coach status ==="

# Brief "editable: today's pattern chips, −/+ controls and Confirm", and "Remove the always-visible button".
# Setup: a configured coach and no outline row. Must show: the chips drawn, and tapping one changes the pattern the
# draft shows. One −/+ pair, with both buttons enabled. CONFIRM drawn. No status line, no RETRY and no CONTINUE
# WITHOUT OUTLINE.
t editableDraftDrawsItsChipsStepperAndConfirmAndNoOtherAction

# Brief "planning: the line 'Coach is planning the week…' … Offer 'Continue without outline'", and the brief's test
# bullet "the −/+ controls are absent while planning". Setup: a QUEUED OUTLINE row for the draft's own mission. Must
# show: the planning line displayed, and CONTINUE WITHOUT OUTLINE displayed and enabled. '+' and '−' are not drawn at
# all, and neither are CONFIRM or RETRY.
t planningDraftDrawsItsStatusLineAndContinueWithoutOutlineAndNoStepperOrConfirm

# Brief "The pattern is shown read-only, with no −/+ and no chip toggling" (planning). Must show: the pattern's chips
# are still drawn, and tapping one leaves the pattern the draft shows unchanged.
t planningDraftDrawsItsPatternChipsReadOnly

# Brief "offline: the line 'Mission Control offline — retrying'. Otherwise the same as planning." Setup: an outline row
# that is still open and carries the coach-offline mark the worker writes. Must show: the offline line displayed, and
# CONTINUE WITHOUT OUTLINE displayed and enabled. CONFIRM, RETRY and the −/+ pair are not drawn.
t offlineDraftDrawsItsStatusLineAndTheSameActionsAsPlanning

# Brief "failed: the reason in red, with Retry and 'Continue without outline'". Setup: a FAILED OUTLINE row with a
# reason that has punctuation and inner spacing. Must show: the row's reason displayed word for word, RETRY and
# CONTINUE WITHOUT OUTLINE displayed and enabled, and no CONFIRM. With host and token set, tapping RETRY makes the
# draft show 'Coach is planning the week…'. That proves the button reaches retry(). The chips and the −/+ pair stay
# drawn and enabled (Plan Decision "The pattern stays editable whenever no generation is open").
t failedDraftDrawsItsReasonAndRetryAndContinueWithoutOutlineAndNoConfirm

# Brief "not configured: a line saying Mission Control isn't set up (host and token go in Mission Control), with
# 'Continue without outline'". Setup: no outline row worth reporting, and a blank host and token. Must show: a line
# saying Mission Control isn't set up and that the host and token go there. CONTINUE WITHOUT OUTLINE displayed and
# enabled, and no CONFIRM or RETRY. The chips and the −/+ pair stay drawn and enabled (same Plan Decision).
t notConfiguredDraftDrawsItsSetupLineAndContinueWithoutOutlineAndNoConfirmOrRetry

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the Launchpad tests. … Also keep Confirm's outline queueing, the worker tests, In-Flight,
# Landed, Mission Control, the shell tests, the domain tests and the build gate working." The gate runs the whole
# suite. These lines name the classes, so that one renamed away, skipped or deleted is caught. The brief says to update
# LaunchpadDraftControlsTest. It must pass whatever its methods are called.
echo ""
echo "=== the tests the brief says must not break ==="

c LaunchpadStatesTest
c LaunchpadConfirmTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest
c LaunchpadReFlyTest
c LaunchpadScrubTest

# The derivation the draft now draws, and the queueing the brief says to keep.
c LaunchpadCoachStatusTest
c CoachStatusTest
c MissionManagerOutlineTest
c MissionManagerTest

# The worker tests.
c GenerationWorkerTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c GenerationDaoTest
c CoachTurnRunnerTest

# In-Flight, Landed, Mission Control and the shell.
c InFlightStatesTest
c InFlightScreenTest
c InFlightViewModelTest
c LandedStatesTest
c MissionControlScreenTest
c MissionControlViewModelTest
c ConnectionViewModelTest
c LiftoffShellTest
c ShellNavTest
c MainActivityTest

# The domain tests.
c MissionsTest
c SortiesTest
c WeekTest
c RolloverTest
c SortiePlanningTest
c ReFlyTest

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
