#!/usr/bin/env bash
# Verify: "View in rho on the Pending card" (brief.md; DESIGN.md §9 'PENDING: … plus "View in nabu", which shows the
# nabu session id so it can be opened in nabu's app' — the brief renames it rho; the doc itself is not changed).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the PENDING card, then the classes the brief's "Must not break"
# list names.
#
# What is checked, in one line: the Launchpad's PENDING card offers VIEW IN RHO when this sortie's newest
# non-cancelled FLIGHT_PLAN generation row carries a session id, and tapping it prints 'rho session <id>' as a line on
# the card that was not there before the tap; the card offers no such control when the row carries no id, or when
# Mission Control was never set up so there is no row at all.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports. A
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: the three
# names below are new to this branch, and PendingActions today draws Retry, Re-fly (or the 'No earlier plan to
# re-fly.' line) and Scrub, with nothing keyed on the card's rhoSessionId anywhere in the file.
#
# What is NOT checked here, and why:
# - ARCHITECTURE.md and DESIGN.md. The brief says not to change ARCHITECTURE.md in this run, and the design doc keeps
#   its old 'nabu' wording. Doc wording is reviewed by Kyle in the pull request.
# - "Use theme parts only": that the control is an UnderlinedTextButton and the id line is a LiftoffType.note() in
#   Muted. Compose semantics carry no component identity and no text colour, so both are read off the diff.
# - The position of the button relative to the id line. The bar is the Scaffold's bottomBar slot and the lines are its
#   content Column, so node bounds across the two prove nothing (plan Risk). Both positions the brief does state — the
#   button above SCRUB, the id line under the card's status line — are inside one slot each, and case 1 asserts them
#   there.
# - That the revealed line is drawn inline rather than in a Dialog. A dialog's text is found and reported as displayed
#   by the same semantics node, so nothing here can tell the two apart; it is read off the diff.
# - That the revealed line stays until the sortie changes, and that a new sortie starts with it hidden. The plan says
#   this is carried by the reveal state being keyed on the PENDING card's sortieId rather than by a test, and no case
#   the brief lists changes the sortie. Not checked.
# - That the line always shows the current id when a Retry or Re-fly queues a fresh row (plan Decision "the line shows
#   the current rhoSessionId"), and that the reveal is one-way (plan Decision "a second tap does nothing"). No test is
#   named just to pin either; the first is read off the diff, and the second is visible in case 1's own tap.
# - That the reveal resets when the owner leaves the Launchpad tab and comes back (plan Decision "no"), or survives an
#   activity recreation. Both are navigation/lifecycle behaviour the brief's cases do not reach.
# - That Generation.nabuSessionId keeps its column and property name. The brief says leave it alone; a rename would
#   still pass every test here, so it is a diff-review item.
# - Which class the three new cases sit in, whether it is the class the brief names, or whether an existing Launchpad
#   class had to be given the tall-window qualifier. The brief allows that, and the whole-class checks below run those
#   classes whatever their methods are called.
# - That the new tests avoid WorkManager, hand both generation lambdas as no-ops, render through LaunchpadContent in
#   LiftoffTheme, use the fixed 2026-01-12 clock, and cap every wait at 5 s. Those are the brief's rules for how the
#   tests are written, reviewed in the pull request.
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

# ─── 2. The PENDING card: View in rho, and the revealed session line ───────────
# Brief: "When rhoSessionId is not null, the PENDING bottom bar gets an UnderlinedTextButton(text = "View in rho",
# …) above Scrub. Tapping it reveals the id as a LiftoffType.note() line under the card's status lines, reading
# 'rho session <id>'." The id is already on the card's state — LaunchpadState.Pending.rhoSessionId, read from the
# newest non-cancelled FLIGHT_PLAN generation row's nabuSessionId — so these cases only ask what the card draws.
#
# The harness the brief prescribes, which is LaunchpadDraftStatusTest's and LaunchpadPendingStatusTest's: Robolectric
# Compose, @Config(sdk = [34], qualifiers = "w411dp-h2000dp"), in-memory Room, a real SettingsStore, MissionManager on
# Clock.fixed(2026-01-12T00:00:00Z, UTC), LaunchpadContent(viewModel, onOpenInFlight = {}) inside LiftoffTheme, no-op
# queueGeneration/cancelGeneration lambdas, no WorkManager, 5 s bounded waits, and the teardown that cancels the
# scope's Job before db.close() so the screen's live observe() stops first.
#
# Reaching a real PENDING card: setDaemonHost("127.0.0.1") + setDaemonToken, setDefaultPattern("LRL") so the current
# sortie is a LIFT, manager.onAppOpen(), then manager.continueWithoutOutline(missionId). That leaves exactly one
# QUEUED FLIGHT_PLAN row for sortie index 0 — read it back with generationDao().getOpenFlightPlanForSortie(sortieId)
# .single() — and the card's status line is 'Coach is planning…'. The id goes on that row with
# generationDao().update(row.copy(nabuSessionId = "sess-7f3a-liftoff")) before setContent.
#
# UnderlinedTextButton upper-cases its label, so the control is looked for as VIEW IN RHO. The id line is a plain Text
# and is not upper-cased, so it is asserted as the whole line 'rho session sess-7f3a-liftoff' — a line with the wrong
# wording must not pass.
echo ""
echo "=== the PENDING card's View in rho ==="

# Brief test bullet "with the id: VIEW IN RHO is shown, the id isn't on screen until the tap, and after the tap the
# text containing 'sess-7f3a-liftoff' is displayed". Must show: on the PENDING card built above, VIEW IN RHO is
# displayed and enabled (wait for it, bounded at 5 s); no node whose text contains 'sess-7f3a-liftoff' exists before
# the tap; after tapping VIEW IN RHO the line 'rho session sess-7f3a-liftoff' is displayed, under the card's status
# line in the same content Column (brief: 'under the card's status lines' — their bounds, two siblings in one slot);
# VIEW IN RHO's bounds sit above SCRUB's in the bar's Column (brief: 'above Scrub' — two siblings in the other slot,
# and nothing is compared between the two slots); and the card still shows
# every line it showed before — 'Coach is planning…' and SCRUB — so the id arrived as an extra line rather than
# replacing the status line or the bar. The tap must have changed nothing else: the no-op enqueuer was handed no id,
# and the row is still QUEUED with the same session id. Plan Decision "the tap only reveals the id — it does not open
# rho or copy it" is what those last two assertions show.
t pendingCardWithASessionIdShowsViewInRhoAndTappingItRevealsTheSessionLine

# Brief test bullet "with the row but no id: no VIEW IN RHO". Must show: the same card, with the QUEUED FLIGHT_PLAN
# row left carrying no nabuSessionId, first reaches the PENDING card the test is asserting on — wait for its status
# line 'Coach is planning…' (or SCRUB) to be displayed, because during Loading there is no button either and an
# absence asserted then proves nothing — then there is no VIEW IN RHO and no text containing 'rho session' anywhere on
# screen. SCRUB is still displayed, so the bar itself rendered.
t pendingCardWithAGenerationRowThatHasNoSessionIdShowsNoViewInRho

# Brief test bullet "not configured (no host or token, so no row): no VIEW IN RHO". Must show: with the host and token
# left blank, continueWithoutOutline queued no generation row for the sortie at all (assert the row count is zero, or
# that no open FLIGHT_PLAN row exists for this sortie), the card is still the PENDING card — wait for its
# not-configured status line, NOT_CONFIGURED_MESSAGE, to be displayed — and there is no VIEW IN RHO and no text
# containing 'rho session' on screen.
t pendingCardWithNoGenerationRowShowsNoViewInRho

# ─── 3. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the other Launchpad tests (including LaunchpadPendingStatusTest and LaunchpadPlannedActionsTest
# if present), LiftoffShellTest, or the build gate", and "The PENDING card already draws its coach status lines and a
# Retry, and its bottom bar already holds Retry, Re-fly (or 'No earlier plan to re-fly.') and Scrub. Don't change any
# of those." The gate runs the whole suite, so these names only catch one of them renamed away, skipped or deleted.
# LaunchpadPendingStatusTest is the PENDING card's status lines and Retry; LaunchpadPlannedActionsTest,
# LaunchpadReFlyTest and LaunchpadScrubTest are the bar's other controls on the cards that already had them;
# LaunchpadSortieStatusTest and LaunchpadStatesTest are the derivation that hands the card its rhoSessionId, which
# this run must not touch. LiftoffShellTest's Launchpad has no host or token, so it queues no generation row and never
# sees the new control — it is here because a control added to the bar can push a node out of the fixed window.
echo ""
echo "=== the tests the brief says must not break ==="

c LaunchpadPendingStatusTest
c LaunchpadPlannedActionsTest
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
