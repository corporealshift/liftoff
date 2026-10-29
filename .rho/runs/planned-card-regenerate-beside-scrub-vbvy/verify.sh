#!/usr/bin/env bash
# Verify: "Planned card: Regenerate beside Scrub and the re-planning line" (brief.md; DESIGN.md §9 'PLANNED: the plan
# summary, with Launch, Regenerate and Scrub'; design/screens/launchpad.html).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the PLANNED card, then the classes the brief's "Must not break"
# list names.
#
# What is checked, in one line: the Launchpad's PLANNED card draws Regenerate and Scrub as two underlined buttons
# beside each other under Launch when Mission Control is set up, and Scrub alone when it is not; tapping Regenerate
# queues exactly one new generation for this sortie and the card then says 'Coach is re-planning…' while Launch and the
# plan stay on screen.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports. A
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: the three
# names below are new to this branch, PlannedActions draws Launch and a single full-width Scrub whatever
# regenerateAvailable says, and PlannedContent draws no status line at all.
#
# What is NOT checked here, and why:
# - ARCHITECTURE.md. The brief says not to change it in this run.
# - "Use theme parts only. No stock M3 visuals", and that the re-planning line is LiftoffType.note()/Muted. Compose
#   semantics carry no component identity and no text colour, so both are read off the diff in the pull request.
# - That each button gets equal weight, or the 24dp between them and the 12dp under Launch (plan Decision "24dp
#   between Regenerate and Scrub, and 12dp between Launch and the Row"). The width check below only requires that
#   neither button spans the row on its own.
# - Which class each new case sits in, whether it is the class the brief names, or whether an existing Launchpad class
#   had to be given the tall-window qualifier. The brief allows that, and the whole-class checks below run those
#   classes whatever their methods are called.
# - That the new tests avoid WorkManager and never click SCRUB, that they render through LaunchpadContent, or that
#   every wait is capped at 5 s. Those are the brief's rules for how the tests are written, reviewed in the pull
#   request.
# - The order of the two buttons is checked only as "Regenerate lies to the left of Scrub", which is plan Decision
#   "Order of the two buttons: Regenerate on the left, Scrub right". No test is added just for it.
# - Tapping Regenerate a second time while the coach is already re-planning (plan Decision "a second tap is allowed
#   and the existing supersede rule handles it"). It is the view model's rule, already tested by
#   LaunchpadRegenerateTest, which the gate runs; the brief's three cases do not include it.
# - The re-planning line showing on a card whose host or token was cleared mid-generation (plan Decision "the line
#   depends only on generationStatus"). No test is added just for it; it is read off the diff.
# - Which other classes in the suite break. The gate runs every test in the repo, so a break anywhere else already
#   fails the gate. The class names below only catch a named class that was renamed away, skipped or deleted.
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
# Brief Done-when: "the three tests pass and bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This
# is also the brief's "Must not break" check for the whole suite. Stale reports are moved aside first, so every named
# test below comes from this run. --no-build-cache stops the reports being restored from the cache.
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
# - the hang the brief names ("The gate can hang once for about 5 minutes: run it detached and re-run it once if it
#   hangs"): a run that wrote no report at all, or a build task that failed with no test failing;
# - a build killed by a daemon, memory or executor error;
# - doneBodyweightSetStepperShowsBW alone, the one name this repo has recorded failing at random.
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

# ─── 2. The PLANNED card: Regenerate beside Scrub, and the re-planning line ────
# Brief: "Give the Launchpad's PLANNED card the Regenerate action the mockup draws." design/screens/launchpad.html
# draws a flex row of two underlined text buttons, Regenerate then Scrub, under the big red Launch.
#
# The harness the brief prescribes: Robolectric Compose, @Config(sdk = [34], qualifiers = "w411dp-h2000dp"),
# LaunchpadContent(viewModel, onOpenInFlight = {}) inside LiftoffTheme, on a view model over in-memory Room, a real
# SettingsStore and MissionManager on Clock.fixed(2026-01-12T00:00:00Z, UTC), with a recording queueGeneration and a
# cancelGeneration that marks the id cancelled in GenerationDao. LaunchpadRegenerateTest reaches the PLANNED lift card
# and LaunchpadPendingStatusTest is the Compose harness.
#
# The lift card: host '127.0.0.1' and a token, pattern "LRL", onAppOpen, continueWithoutOutline, then storePlan with
# the lift fixture reply (StoreOutcome.Stored). continueWithoutOutline leaves a QUEUED FLIGHT_PLAN row open and
# storePlan does not close it, so the card is RePlanning until that row is closed with markSucceeded; without closing
# it the first case fails on the very line it is meant to show is absent. The plan's title from that fixture is
# 'Push + core'. The run card: pattern "RLL" with 'Generate run plans' left off — already PLANNED with a simple 'Run'
# plan, no generation row at all, and regenerateAvailable false.
#
# PrimaryButton and UnderlinedTextButton upper-case their labels, so the assertions are LAUNCH, REGENERATE, SCRUB.
echo ""
echo "=== the PLANNED card ==="

# Brief test bullet "a PLANNED lift shows REGENERATE and SCRUB and no re-planning line". Must show: REGENERATE and
# SCRUB both displayed and enabled, drawn beside each other rather than stacked — their bounds overlap vertically,
# neither spans the row on its own, and REGENERATE lies to the left of SCRUB (plan Decision "Regenerate on the left,
# Scrub on the right") — and no 'Coach is re-planning…' anywhere on the card. Render only.
t plannedLiftCardShowsRegenerateBesideScrubAndNoRePlanningLine

# Brief test bullet "tapping REGENERATE records one queued id, and the card then shows 'Coach is re-planning…' while
# LAUNCH and the plan's title stay on screen". Must show: the recording queueGeneration lambda was handed exactly one
# id, and that id is a real QUEUED FLIGHT_PLAN row for this sortie (not just some number); the card then draws
# 'Coach is re-planning…'; and LAUNCH, the plan's title 'Push + core' and REGENERATE are all still on screen, with the
# plan still in the table. Nothing is handed to cancelGeneration here, because the row continueWithoutOutline queued
# was closed first, so cancelledIds is empty — assert that, so the case cannot pass because of a cancel. The card is
# reached before the tap by waiting for REGENERATE on screen, since regenerate() reads state.value and returns null on
# a card that has not been derived yet. Plan Decision "the re-planning line depends only on generationStatus" and
# Decision "Regenerate stays available during re-planning" are both visible in this case.
t tappingRegenerateOnAPlannedCardQueuesOneGenerationAndShowsTheRePlanningLine

# Brief test bullet "the run-off PLANNED card shows SCRUB and no REGENERATE". Must show: SCRUB displayed and enabled,
# no REGENERATE, and no 'Coach is re-planning…'. Render only.
t plannedRunCardWithRunGenerationOffShowsScrubAndNoRegenerate

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the other Launchpad tests, LiftoffShellTest, or the build gate", and "The PENDING card's
# status lines and Retry were added by the previous run; leave them alone." The gate runs the whole suite, so these
# names only catch one of them renamed away, skipped or deleted. LaunchpadPendingStatusTest is the PENDING card the
# brief says to leave alone; LaunchpadReFlyTest and LaunchpadScrubTest show Scrub is unchanged on the cards that
# already had it; LaunchpadRegenerateTest and LaunchpadSortieStatusTest are the view-model and derivation tests for
# the state this card now draws.
echo ""
echo "=== the tests the brief says must not break ==="

c LaunchpadPendingStatusTest
c LaunchpadReFlyTest
c LaunchpadScrubTest
c LaunchpadRegenerateTest
c LaunchpadSortieStatusTest
c LaunchpadStatesTest
c LaunchpadCoachStatusTest
c LaunchpadConfirmTest
c LaunchpadDraftStatusTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest

c LiftoffShellTest

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
