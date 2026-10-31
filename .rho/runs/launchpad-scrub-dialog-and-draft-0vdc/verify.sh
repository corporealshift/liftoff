#!/usr/bin/env bash
# Verify: "Launchpad scrub dialog and draft controls cleanup" (brief.md; DESIGN.md §5.3 the Launchpad,
# §8 Sortie/Scrub; CLAUDE.md §2 vocabulary). Definition of done for this run: exit 0 only when the brief
# is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug
# :app:testDebugUnitTest), then every named test read out of the JUnit XML reports this run produced.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's
# reports; a test that was not written cannot pass on its own. Names are matched across every report, so
# which class and package each one sits in is the implementer's call; what it must show is written above
# it. The eight names under "scrubbing from the Launchpad" and "the draft's −/+ controls" are tests this
# run has to write. The names under "must not break" already exist and this run has to keep them passing.
#
# No test here opens a Dialog, types into or focuses a text field: the brief forbids it, and under
# Robolectric a text field inside a Dialog window never lets Compose go idle. The reason rule is proven
# through the view model and in-memory Room — which is also the shape the shared dialog's onConfirm(reason)
# hands it over. The draft controls are proven by what the draft state renders and by the view model's own
# guards. Every wait in a test needs a timeout, and nothing here touches the network.
#
# The brief's first Done-when line — no stock OutlinedTextField left in com.liftoff.app.ui.launchpad, and
# the Launchpad's private ScrubDialog gone — is not checked here. It is the absence of a widget, and the
# only Compose test that could see it would have to open that dialog with its text field, which the brief
# rules out. The owner reviews that line in the pull request. The trim tests carry it as far as behaviour
# can: the reason now arrives from the dialog's Confirm as a parameter, and the Launchpad trims it.
#
# The plan's "## Decisions" are not pinned here: what Back or a tap outside the dialog does, whether the
# bar reads "1 sorties" at one, and what the field shows when the dialog is reopened. Where a named test
# also shows a decision, the comment says which.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# status <method>: pass | fail | skipped | missing | none, for one test method across every report of
# this run. Every report is read, so the new tests can sit in any package and any class. Self-closing
# testcases and name="method[params]" are handled.
status() {
    local method="$1" file out
    out=""
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        out+="$(awk -v m="$method" '
            /<testcase[ \t]/ {
                inside = 0
                if ($0 ~ ("name=\"" m "\"") || $0 ~ ("name=\"" m "\\[")) {
                    found = 1
                    if ($0 ~ /<(failure|error)[ \t\r\n>\/]/) bad = 1
                    if ($0 ~ /<skipped/) skipped = 1
                    if ($0 !~ /\/>[ \t]*$/) inside = 1
                }
                next
            }
            inside && /<(failure|error)[ \t\r\n>\/]/ { bad = 1 }
            inside && /<skipped/ { skipped = 1 }
            inside && /<\/testcase>/ { inside = 0 }
            END {
                if (!found) print "missing"
                else if (bad) print "fail"
                else if (skipped) print "skipped"
                else print "pass"
            }
        ' "$file")"$'\n'
    done
    if grep -q '^fail$' <<<"$out"; then echo fail
    elif grep -q '^pass$' <<<"$out"; then echo pass
    elif grep -q '^skipped$' <<<"$out"; then echo skipped
    elif grep -q '^missing$' <<<"$out"; then echo missing
    else echo none
    fi
}

# t <method>: the named test must have run in this invocation and passed.
t() {
    local method="$1" st
    st="$(status "$method")"
    case "$st" in
        pass)    ok "$method" ;;
        fail)    bad "$method ran but did not pass" ;;
        skipped) bad "$method was skipped" ;;
        missing) bad "$method did not run" ;;
        none)    bad "$method did not run (no test reports under $RESULTS)" ;;
    esac
}

# ─── 1. CI gate: the two tasks .github/workflows/ci.yml runs ──────────────────
# This is also the "must not break" check for everything the brief lists — In-Flight and its ScrubDialog,
# the Launchpad and its other states, the Mission screen, MissionManager and the domain tests, Mission
# Control, the coach package, the build gate — since CI runs the whole unit-test suite.
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test executed,
# which would read as a suite that passed.
# The brief warns a full gate can outlast a shell command's limit; the timeout is how this script waits
# for it. A suite that never finishes is a failure, not a run that is still going.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="
rm -rf "$RESULTS" 2>/dev/null || true
if [ -d "$RESULTS" ]; then
    echo "  ✗ could not clear $RESULTS — a stranded Gradle test executor is holding it; run: bash gradlew.sh --stop" >&2
    exit 1
fi
if timeout 2400 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 | tee "$WORK/gate.log"; then
    ok "gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "gate failed: the app does not assemble, the unit tests do not pass, or the suite did not finish in 40 minutes"
fi
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK/gate.log"; then
    bad "unit tests executed (the task came from the build cache or was up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
fi

# ─── 2. Scrubbing from the Launchpad ───────────────────────────────────────────
# Brief part 1: the Launchpad shows the themed In-Flight ScrubDialog, and its view model trims the reason
# and stores blank text as null the way In-Flight does. Proven on a Planned sortie through the Launchpad's
# view model against in-memory Room, with the sortie row read back from the DAO — no Compose, no dialog,
# nothing typed. The mission has to be ACTIVE (onAppOpen, then confirm) or MissionManager.scrub refuses
# it, and the state flow has to be collected before the scrub because confirmScrub reads the current
# state. Each test carries its own timeout.
echo ""
echo "=== scrubbing from the Launchpad ==="

# Scrub with '  sore knee  ': the sortie is SCRUBBED and its stored reason is 'sore knee' — the leading
# and trailing spaces are gone, which is what the Launchpad does today only by accident, and does not do
# for text that is padded. The view model's scrubbing flag is false afterwards, so the dialog closes once
# the write is done and scrubbing otherwise works as it does today.
t launchpadScrubTrimsTheReason

# Scrub with '   ': the sortie is still SCRUBBED and its reason is null, not a run of spaces. A blank
# reason stores nothing, the same rule In-Flight uses.
t launchpadScrubStoresBlankReasonAsNull

# Scrub with '  sore\nknee  ': the stored reason is 'sore\nknee'. Only the ends are trimmed, so a reason
# typed over two lines keeps its interior line break.
t launchpadScrubKeepsInteriorLineBreaks

# ─── 3. The draft's −/+ controls ───────────────────────────────────────────────
# Brief part 2: the draft draws the pattern stepper once — the bottom-bar set with its 'N sorties' label —
# and that set is disabled at 1 and at 7 sorties. Proven by rendering the draft state (no Dialog, no text
# field anywhere) and by the view model's own guards. The draft arrives from Room asynchronously, so a
# Compose test waits for the expected 'N sorties' text with a timeout before it asserts.
echo ""
echo "=== the draft's −/+ controls ==="

# The draft state renders exactly one stepper: one '+' node and one '−' node — the characters the bottom
# bar uses today — and no leftover ASCII '-' node from the row that used to sit under the chips. Today the
# draft draws two '+', so counting '+' is what shows the duplicate pair is gone; counting '−' alone would
# pass on the untouched screen. The 'N sorties' label is still there beside them, and the chips still
# render.
t draftDrawsOneStepperPair

# A one-sortie draft (pattern R): '−' is present but not enabled, '+' is present and enabled. Taking away
# the last sortie is not something the owner can do, and the control now says so instead of quietly doing
# nothing.
# Decision (plan): at the limits the pair is dimmed and not tappable, not hidden and not left looking live.
t draftMinusDisabledAtOneSortie

# The mirror on a seven-sortie draft (pattern RLRLRLR): '+' is present but not enabled, '−' is enabled.
# Decision (plan): the same dimmed, untappable look at the upper limit.
t draftPlusDisabledAtSevenSorties

# The view model keeps its own limit checks, as the brief requires, so a call that reaches it anyway
# changes nothing: addChip on a seven-sortie draft leaves the stored pattern 7 long, and removeChip on a
# one-sortie draft leaves it 1 long. View model and DAO only, no Compose. The state flow has to be
# collected first — otherwise the state is still Loading and the guard is never exercised.
t addChipAtSevenLeavesPatternUnchanged
t removeChipAtOneLeavesPatternUnchanged

# ─── 4. must not break ─────────────────────────────────────────────────────────
# The whole existing suite runs in the gate above. These name the brief's own "must not" clauses that this
# work is the one touching: In-Flight's use of ScrubDialog and its tests, the other Launchpad states and
# LaunchpadStatesTest, the Mission screen, MissionManager and the domain tests, and the Room schema.
echo ""
echo "=== must not break ==="

# "In-Flight's own use of ScrubDialog and its tests": the shared dialog's content, rendered in the main
# window rather than in its Dialog shell, hands the typed reason to onConfirm and dismisses on Cancel
# without confirming. The Launchpad now calls that same pair of callbacks, so this is the contract the swap
# has to keep intact.
t scrubDialogSendsTheTypedReasonOnConfirm
t scrubDialogCancelDismissesWithoutConfirming
t landConfirmStatesTheOpenCountAndSingularForOneSet
t landConfirmButtonsCallConfirmOrDismiss
# In-Flight's scrub path, unchanged: the reason In-Flight stores is already trimmed and blank→null, and a
# scrub still keeps the checked sets behind it.
t scrubFromInFlightKeepsCheckedSetsAndStoresReason
t scrubStoresWhitespaceReasonAsNull
t doubleTapLandsOnce
# "the other Launchpad states and LaunchpadStatesTest": the Launchpad's own lifecycle, which the dialog
# swap and the stepper change must leave alone.
t loadingBeforeOnAppOpen
t draftAfterOnAppOpen
t plannedLiftShowsPlan
t plannedRunAfterConfirm
t pendingAfterScrub
t inFlightAfterLaunch
t closedAfterAllSortiesLanded
t liveUpdateThroughStates
# "the Mission screen": the pattern chips and the scrubbed sortie's detail. Mission Control's own stepper,
# already disabled at the same 1 and 7 limits, is the shape the Launchpad's surviving pair now matches.
t patternChipsFromMixedSortieStates
t scrubbedSortieDetailShowsReason
t patternButtonsDisabledAtLimits
# "MissionManager and the domain tests": the scrub the Launchpad drives, unchanged.
t scrubStoresReasonAndPreparesNextSortie
t scrubInFlightKeepsCheckedSets
t scrubbingLastSortieClosesMission
t scrubStoresBlankReasonAsNull
t scrubTreatsWhitespaceReasonAsNull
# "Don't change the Room schema": the exported schema JSON still matches the database.
t exportedV1SchemaMatchesDatabase
# CLAUDE.md §2: never 'session' for a sortie.
t neverSaysSession

# ─── result ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
