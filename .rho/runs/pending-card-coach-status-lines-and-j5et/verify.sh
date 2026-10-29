#!/usr/bin/env bash
# Verify: "Pending card: coach status lines and Retry" (brief.md; DESIGN.md §9 'PENDING'; design/README.md).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the PENDING card, then the classes the brief's "Must not break"
# list names.
#
# What is checked, in one line: the PENDING card draws the coach status it already carries: "Coach is planning…",
# "Mission Control offline — retrying", the line saying Mission Control isn't set up, or a failure drawn as its
# reason with each validator error on its own line under it. It offers Retry after a failure, and tapping Retry
# moves the card back to planning. Re-fly and Scrub are still there and unchanged.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports. A
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: the five
# names under the PENDING card section are new to this branch, PendingContent draws the fixed line "Flight Plan not
# ready yet." whatever the status is, and PendingActions draws no Retry at all.
#
# What is NOT checked here, and why:
# - ARCHITECTURE.md. The brief says not to change it in this run.
# - "Use only the theme parts in com.liftoff.app.ui.theme. No stock M3 visuals", and which line is Red and which is
#   Muted. Compose semantics carry no component identity and no text colour, so both are read off the diff in the
#   pull request. The names below do require that the reason and each validator error are drawn as separate lines.
# - The exact wording of the not-set-up line. It is the existing NOT_CONFIGURED_MESSAGE constant.
# - That the fixed "Flight Plan not ready yet." line survives under Idle and RePlanning. Those two variants cannot
#   be reached on a PENDING card (derivePendingSortieGenerationStatus never returns either), so no test can draw
#   them through the screen. It is read off the diff.
# - Which class each new case sits in, or whether an existing Launchpad test had to be given the tall-window
#   qualifier. The brief allows that, and the whole-class checks below run those classes whatever their methods
#   are called.
# - Which other classes in the suite break. The gate runs every test in the repo, so a break anywhere else already
#   fails the gate. The class names below only catch a named class that was renamed away, skipped or deleted.
# - That the new tests avoid WorkManager and text fields, or that they render through LaunchpadContent. Those are
#   the brief's rules for how the tests are written, and they are reviewed in the pull request.
# - The commit that brings in the derivation. The brief's Step 1 names a commit and gives a hand-written fallback,
#   so the commit itself is not the requirement. What it contributes is regenerateAvailable. The failed case below
#   needs it true for RETRY to show on a LIFT sortie. The false half (a RUN sortie with 'Generate run plans' off)
#   is not one of the brief's five cases. The plan proves it with a mutation check, and the owner reviews it in the
#   diff.
# - The plan's Decisions. The owner reviews them in the pull request, so none gets a test of its own here. Where a
#   named test below happens to show one, its comment names it.
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
# Brief Done-when: "the five tests pass and bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest passes". This
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
# - the hang the brief names ("The full gate can hang once for about 5 minutes on this machine ... re-run it once if
#   it hangs"): a run that wrote no report at all, or a build task that failed with no test failing;
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

# ─── 2. The PENDING card: the status line and Retry ────────────────────────────
# Brief: "Make the Launchpad's PENDING card say what the coach is doing for this sortie, and offer Retry after a
# failure". The status replaces the fixed "Flight Plan not ready yet." in PendingContent, and a Retry goes into
# PendingActions above Re-fly and Scrub.
#
# The harness the brief prescribes: Robolectric Compose, @Config(sdk = [34], qualifiers = "w411dp-h2000dp"),
# LaunchpadContent(viewModel, onOpenInFlight = {}) inside LiftoffTheme, on a view model over in-memory Room, a real
# SettingsStore and MissionManager on Clock.fixed(2026-01-12T00:00:00Z, UTC). That is LaunchpadDraftStatusTest's
# harness, with no-op queueGeneration and cancelGeneration lambdas. The card is reached with host and token set,
# pattern "LRL", onAppOpen, then continueWithoutOutline. That leaves one QUEUED FLIGHT_PLAN row for sortie 0 on a
# LIFT sortie, so regenerateAvailable is true. The status is changed by writing to that row through GenerationDao.
# The not-configured case leaves host and token blank.
#
# PrimaryButton upper-cases its label, so Retry is found as RETRY.
echo ""
echo "=== the PENDING card ==="

# Brief test bullet "planning line shown, no RETRY". Setup: the QUEUED row continueWithoutOutline queued. Must show:
# 'Coach is planning…' in place of "Flight Plan not ready yet.", and no RETRY. Plan Decision "Retry appears only
# under a failure, not under Offline or NotConfigured".
t pendingCardShowsThePlanningLineAndNoRetry

# Brief test bullet "offline line shown, no RETRY". Setup: the same row carrying Generation.COACH_OFFLINE. Must show:
# 'Mission Control offline — retrying', and no RETRY. Plan Decision "Retry appears only under a failure, not under
# Offline or NotConfigured".
t pendingCardShowsTheOfflineLineAndNoRetry

# Brief test bullet "not-set-up line shown, no RETRY". Setup: host and token left blank. Must show:
# NOT_CONFIGURED_MESSAGE, and no RETRY.
t pendingCardShowsThatMissionControlIsNotSetUpAndNoRetry

# Brief test bullet "failed shows 'invalid output' and both error lines, plus RETRY". Setup: the row marked FAILED
# with "invalid output: sets must not be empty; reps must be at least 1". Must show: 'invalid output' as its own line,
# 'sets must not be empty' and 'reps must be at least 1' each as its own line, and RETRY. Plan Decision "The
# validator lines are Muted, not Red" covers their colour, which is read off the diff.
t pendingCardShowsAFailedGenerationsReasonAndEachValidatorErrorOnItsOwnLineAndRetry

# Brief test bullet "tapping RETRY moves the card to 'Coach is planning…'". Setup: the same FAILED row. Must show:
# after RETRY is tapped, the card draws 'Coach is planning…' where the failure was. That proves the button reaches
# LaunchpadViewModel.regenerate().
t tappingRetryOnAFailedPendingCardQueuesAFreshGenerationAndShowsThePlanningLine

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the other Launchpad tests (LaunchpadReFlyTest, LaunchpadDraftStatusTest,
# LaunchpadRegenerateTest, LaunchpadSortieStatusTest, LaunchpadStatesTest, LaunchpadScrubTest), LiftoffShellTest,
# or the build gate." The gate runs the whole suite, so these names only catch one of them renamed away, skipped or
# deleted. LaunchpadReFlyTest and LaunchpadScrubTest show "Leave Re-fly and Scrub as they are". The other four
# Launchpad classes are covered by the brief's "the other Launchpad tests".
echo ""
echo "=== the tests the brief says must not break ==="

c LaunchpadReFlyTest
c LaunchpadDraftStatusTest
c LaunchpadRegenerateTest
c LaunchpadSortieStatusTest
c LaunchpadStatesTest
c LaunchpadScrubTest
c LiftoffShellTest

c LaunchpadCoachStatusTest
c LaunchpadConfirmTest
c LaunchpadDraftControlsTest
c LaunchpadDraftGuardsTest

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
