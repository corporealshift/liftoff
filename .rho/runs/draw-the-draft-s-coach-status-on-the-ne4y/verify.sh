#!/usr/bin/env bash
# Verify: "Draw the draft's coach status on the Launchpad"
# (brief.md; DESIGN.md §4.2 and §9 'Launchpad', design/README.md, design/screens/launchpad.html).
# Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's Done-when names the same two tasks), then the eight rendered-draft cases — one per thing the brief asks the
# draft to draw, plus the one-line rule it leaves open — then the classes the brief's "Must not break" list names.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports; a
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all
# eight names under the rendered-draft section are new to this run, and none of them is in the tree today — the draft
# draws the same chips, stepper, Confirm and always-visible 'Continue without outline' whatever the coach is doing,
# and its only status line is the one a Confirm or Retry leaves behind. Running this script against the untouched
# branch reports the suite passing and exactly those eight names as "did not run".
#
# What is NOT checked here, and why:
# - The ARCHITECTURE.md M3 row. The brief asks for it, and it is written in the work step; a regex over a doc row is
#   a text check, not a behaviour check, and it rejects correct work worded differently. Kyle reviews it in the pull
#   request.
# - "Use the design's parts only, with no stock M3 visuals", and the failure reason being drawn in red. Compose
#   semantics carry no text colour and no component identity, so no assertion here can tell a theme button from a
#   stock M3 one or a red line from a muted one. The tests assert which controls and which lines are drawn; the parts
#   they are drawn with are read off the diff in the pull request.
# - The wording of any copy the brief does not quote. The planning and offline lines are the brief's own strings and
#   are required below. The not-configured line is required by what it must say — Mission Control isn't set up, and
#   the host and token go in Mission Control — not by an exact sentence.
# - That the new cases are render-only Compose through LaunchpadContent, on in-memory Room, with @Config(sdk = [34]),
#   bounded waits, no text fields and no WorkManager. Each comment below says so; a case that broke one of those
#   rules would still pass its name, and the shape of a test file is the owner's call.
# - Which class the new cases sit in, or that LaunchpadDraftControlsTest's two cases built on the old drawing were
#   rewritten rather than deleted. The whole-class check below runs that class whatever its methods are called.
# - The plan's Decisions that only say which controls stay editable in a state the brief is silent about, and that
#   Retry takes Confirm's primary-button slot. The tests assert which controls are drawn and enabled in each status,
#   which is what those decisions land on; the size and style of a control is reviewed in the pull request.
# - That LaunchpadViewModel.retry() gained the onRetry wiring, or that ActionButtons/LaunchpadContent gained a
#   parameter. The Retry tap below proves the wiring reaches the view model; how it is threaded is not checked.
# - The Room schema, the entities and the derivation, which this brief does not touch. The gate runs the whole suite,
#   including the exported-schema test and the derivation tests, and the class list below names the ones this run
#   reads through.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request description the brief
#   asks for.
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
# "<name>\t<pass|fail|skipped>" lines, one per name, keeping the worst outcome across every report. Tab-separated so
# a name is one field whatever it contains. Fail is worse than skipped, and skipped is worse than pass, so one
# ignored test marks its whole class.
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
# was never written, so a name missing from this run's reports is a failure, and one that ran and did not pass is a
# failure too. Names are matched across every report, so which file and package a test sits in is the implementer's
# call; what it must show is written in the comment above it.
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
# Brief Done-when: "these tests pass, the M3 row is updated, and bash gradlew.sh :app:assembleDebug
# :app:testDebugUnitTest passes". This gate is also:
# - the compile check for the redrawn draft, for the onRetry callback threading through ActionButtons and
#   LaunchpadContent, and for every construction site of LaunchpadContent — a caller left unupdated does not compile;
# - the brief's "Must not break" check for the whole suite: the Launchpad classes, Confirm's outline queueing, the
#   worker tests, In-Flight, Landed, Mission Control, the shell tests, the domain tests, and the rest;
# - the check that no test hangs and nothing depends on the local clock (CI is ubuntu-latest, UTC), since
#   app/build.gradle.kts kills a test task that goes five minutes without finishing — the hang the brief warns about
#   for this run, which it says to re-run rather than fix, and which is also what bounds every wait in the new
#   Robolectric cases the brief asks for.
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
# - the known flake (doneBodyweightSetStepperShowsBW, a Room pool closed under a concurrent observe());
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - a hung suite that wrote no report, which the build kills at five minutes — the hang the brief names.
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
# Every case below is render-only Robolectric Compose through the screen's internal LaunchpadContent(viewModel,
# onOpenInFlight) seam, on a view model built on in-memory Room, @Config(sdk = [34]) with a fixed test window, every
# wait bounded, no text field typed into and no WorkManager. The status is reached the way LaunchpadCoachStatusTest
# already reaches it — host and token through the settings store, and OUTLINE Generation rows through
# db.generationDao() for the draft's own mission, each with its own later createdAt — so no case hands the screen a
# status it did not derive itself. The draft arrives through Room's flow, so each case waits on a known string before
# it asserts anything, and it stops the view model's collecting job before closing the database.
#
# Buttons are named by what the screen draws for them: UnderlinedTextButton upper-cases its label, so the way out is
# asserted as CONTINUE WITHOUT OUTLINE; Confirm and Retry are PrimaryButton labels, asserted as CONFIRM and RETRY.
# "Exactly its buttons" is asserted both ways: each control the status offers is displayed and enabled, and each one
# it does not is asserted absent.
echo ""
echo "=== the draft drawn for each coach status ==="

# Brief "editable: today's pattern chips, −/+ controls and Confirm", and "Remove the always-visible button, so
# 'Continue without outline' appears only in the states above". A configured coach and no outline row: the chips are
# drawn and tapping one changes the pattern the draft shows, one −/+ pair is drawn and both its buttons are enabled,
# CONFIRM is drawn, and the draft draws no status line, no RETRY and no CONTINUE WITHOUT OUTLINE. The absent way out
# is what shows the button under Confirm is gone.
t editableDraftDrawsItsChipsStepperAndConfirmAndNoOtherAction

# Brief "planning: the line 'Coach is planning the week…' … Offer 'Continue without outline'", and the brief's own
# test bullet "the −/+ controls are absent while planning". A QUEUED OUTLINE row for the draft's own mission: the
# planning line is displayed; CONTINUE WITHOUT OUTLINE is displayed and enabled; '+' and '−' are not drawn at all;
# CONFIRM and RETRY are not drawn.
t planningDraftDrawsItsStatusLineAndContinueWithoutOutlineAndNoStepperOrConfirm

# Brief "The pattern is shown read-only, with no −/+ and no chip toggling" (planning). The chips are still drawn, but
# each one says it is not available, so a tap cannot change the pattern. Drawn the way the draft's own stepper button
# is drawn — clickable(enabled = false, role = Role.Button) — because a node with no clickable at all has no Enabled
# semantics and the assertion would prove nothing.
t planningDraftDrawsItsPatternChipsReadOnly

# Brief "offline: the line 'Mission Control offline — retrying'. Otherwise the same as planning." An outline row that
# is still open and carries the coach-offline mark the worker writes: the offline line is displayed; CONTINUE WITHOUT
# OUTLINE is displayed and enabled; CONFIRM, RETRY and the −/+ pair are not drawn. No RETRY, because the worker is
# already retrying on its own and the status does not offer it.
t offlineDraftDrawsItsStatusLineAndTheSameActionsAsPlanning

# Brief "failed: the reason in red, with Retry and 'Continue without outline'". A FAILED OUTLINE row marked with a
# reason of the kind the worker or a validator writes — punctuation and inner spacing, so a rewrite, a label or a
# trim is caught: the row's reason is displayed word for word, with no label added in front of it; RETRY and CONTINUE
# WITHOUT OUTLINE are displayed and enabled; CONFIRM is not drawn. The chips and the −/+ pair are still drawn, and
# both stepper buttons are enabled (Plan Decision "The pattern stays editable whenever no generation is open"). The
# reason's red cannot be asserted through Compose semantics and is read off the diff.
t failedDraftDrawsItsReasonAndRetryAndContinueWithoutOutlineAndNoConfirm

# Brief "not configured: a line saying Mission Control isn't set up (host and token go in Mission Control), with
# 'Continue without outline'". No outline row worth reporting and a blank host and token: a line is displayed that
# says Mission Control isn't set up and where the host and token go; CONTINUE WITHOUT OUTLINE is displayed and
# enabled; CONFIRM and RETRY are not drawn. The chips and the −/+ pair are still drawn and enabled here too (same
# Plan Decision), because a week whose coach was never set up must still be reshaped.
t notConfiguredDraftDrawsItsSetupLineAndContinueWithoutOutlineAndNoConfirmOrRetry

# ─── 3. The one status line ────────────────────────────────────────────────────
# Brief: "Remove the separate notConfiguredMessage line too, unless a Confirm tapped with nothing configured would
# otherwise show nothing. In that case keep whichever single line reads best and record the decision." That case is
# real, and it arrives through Retry rather than Confirm: a FAILED outline row outranks blank settings, so a draft
# with cleared settings shows its failure reason and Retry, and the Retry it offers is the one that finds nothing
# configured. Plan Decision "One status line, and a Retry that finds nothing configured replaces it rather than
# adding a second line", and the decision that the report shows only in the failed status and that every Retry clears
# it first. Both cases below drive the button, not the view model, so they also prove the Retry path reaches the view
# model from the draft's bottom bar.
echo ""
echo "=== one status line, and the report a Retry leaves in it ───────────────"

# A FAILED draft with no host and token: the reason is displayed and no not-configured line is. Tapping RETRY — the
# control the failed status offers — reports that nothing is configured, and the draft then draws that sentence in
# the same single slot: exactly one such line on screen, and the failure reason no longer drawn. Two lines saying the
# same thing, or a Retry tap with nothing drawn, both fail this.
t aRetryThatFindsMissionControlNotSetUpReplacesTheFailedDraftsLineWithOneNotConfiguredLine

# The same draft, after that report: fill in a host and a token and tap RETRY again. The draft draws 'Coach is
# planning the week…' — the new QUEUED row Retry wrote — and the not-configured sentence is gone. A report left by an
# earlier Retry surviving into a successful one would replace the planning line with "isn't set up", which is now
# false, and fail this.
t theNotConfiguredReportClearsWhenTheNextRetryReachesMissionControl

# ─── 4. What the brief says must not break ─────────────────────────────────────
# Brief: "Must not break: the Launchpad tests. … Also keep Confirm's outline queueing, the worker tests, In-Flight,
# Landed, Mission Control, the shell tests, the domain tests and the build gate working." The gate above runs the
# whole suite; these name the classes this run reaches underneath, so that one of them being renamed away, skipped or
# deleted is caught rather than passing silently.
#
# The Launchpad classes are the brief's own list. LaunchpadDraftControlsTest is the one the brief says to update: two
# of its cases were built on the old drawing — the one that asserted the always-visible way out beside Confirm, and
# the one that drove a Confirm with blank settings to the not-configured line — and both are rewritten or moved by
# this run. Their names are the things being removed, so they are not named here; the class is, and it has to pass
# whatever its methods are called. Its stepper cases keep their assertions once its fixture sets a host and a token,
# which is what makes the draft it renders an editable one.
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

# The worker tests: the offline mark and the failure reason the draft's offline and failed lines are read from.
c GenerationWorkerTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c GenerationDaoTest
c CoachTurnRunnerTest

# In-Flight, Landed, Mission Control and the shell share the screen, the states and the theme parts the draft now
# draws with.
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

# The domain tests the brief names.
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
