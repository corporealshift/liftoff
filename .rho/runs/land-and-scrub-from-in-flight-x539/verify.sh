#!/usr/bin/env bash
# Verify: "Land and scrub from In-Flight" (brief.md; DESIGN.md §5.1 and §6, design/README.md
# 'In-Flight', design/screens/in-flight.html). Definition of done for this run.
# Exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug
# :app:testDebugUnitTest), then every named test read out of the JUnit XML reports this run produced.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this
# run's reports; a test that was not written cannot pass on its own.
#
# Named tests are matched by method name across every test report, so which class and package each
# one sits in is the implementer's call; the behaviour it must show is written above it. Every name
# below is a test this run has to write, except the ones under "must not break", which are names the
# repo already has and which the change must keep passing.
#
# No test below opens a Dialog. The brief says Compose tests that open a Dialog are optional, and the
# confirmation logic is explicitly asked for as something testable "without Compose" — so the
# open-set count and the decision to confirm are named as plain Kotlin over the derived state, and
# nothing here clicks a control that opens a dialog (this repo also hangs under Robolectric when a
# text field sits inside a Dialog).
#
# The plan's "## Decisions" are not pinned here: where the Scrub action sits in the footer, what the
# Loading footer shows, the confirmation wording and its singular/plural, whether scrub ever skips
# the confirmation, what the scrub dialog says, how a rejection is presented, what the buttons look
# like mid-write, whether the Launchpad's own scrub dialog is replaced, and whether a reason with
# text in it is trimmed. The owner reviews those in the pull request. Where a named test also shows
# a decision, the comment says which one.
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
# This is also the "must not break" check for everything the brief lists — the checklist, the notes,
# the Launchpad and Mission screens, MissionManager and the domain tests, Mission Control, the coach
# package, the shell — since CI runs the whole unit-test suite.
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test
# executed, which would read as a suite that passed.
# The timeout is the check on "every wait in a test needs a timeout": a wait that never ends hangs
# the suite, and a hung suite is a failure, not a run that is still going.
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

# ─── 2. The number of open sets, and when landing needs confirmation ──────────
# Brief: "Expose the number of open sets from the state, so the confirmation logic can be tested
# without Compose." Plain Kotlin over the derived In-Flight state — no Compose, no view model.
echo ""
echo "=== open-set count and the decision to confirm ==="

# The derived state for a lift sortie reports how many of its sets are still OPEN: it is the full
# count before anything is checked, it drops as sets are checked, and sets belonging to a skipped
# exercise are not counted as open.
t openCountCountsOpenSets

# The confirmation decision reads that count: no confirmation when nothing is open, confirmation as
# soon as one set is open.
t confirmationNeededOnlyWhenSetsAreOpen

# A sortie with no checklist — a run, or a plan with no exercises — is never confirmed, however it
# was reached.
t noChecklistNeedsNoConfirmation

# ─── 3. Landing a sortie from In-Flight ───────────────────────────────────────
# Brief: "Landing goes through MissionManager.land and then returns to the Launchpad." Driven
# through the In-Flight view model against in-memory Room, on a seeded lift plan that was launched
# the way the app launches one.
echo ""
echo "=== landing ==="

# Every set checked: the sortie becomes LANDED, the sets stay DONE (nothing is rewritten as not
# done), the completion callback runs exactly once, and no failure message is raised.
t landWithEverySetChecked

# Some sets left unchecked: the unchecked ones are recorded as not done, and the checked ones stay
# done. This is what the confirmation dialog promises.
t landRecordsUncheckedSetsAsNotDone

# The sortie is LANDED and carries the time it was landed.
t landStoresLandedTime

# Landing advances the Mission: the sortie after the landed lift is now the current one, and the run
# that follows it under RLRLR has been prepared with its simple 'Run' plan rather than left empty.
t landMakesNextRunSortieCurrentWithSimpleRunPlan

# A run with no checklist can be landed from In-Flight too: it lands, and the completion callback
# runs.
t landingARunNeedsNoChecklist

# Landing the last sortie of the Mission closes it, so the Launchpad has a closed state to show.
# (Named apart from MissionManagerTest's own closing test: this one is reached from In-Flight.)
t landingLastSortieFromInFlightClosesMission

# ─── 4. Scrubbing a sortie from In-Flight ─────────────────────────────────────
# Brief: "Scrubbing goes through MissionManager.scrub, keeps the sets already checked, then returns
# to the Launchpad." and "A blank or whitespace-only reason is stored as null."
echo ""
echo "=== scrubbing ==="

# The domain rule the brief asks to fix: a reason that is only whitespace becomes null, while a
# reason with text in it is still stored on the scrubbed sortie.
t scrubTreatsWhitespaceReasonAsNull

# Scrubbing from In-Flight: the sortie is SCRUBBED, the sets already checked are still checked, the
# reason the owner gave is stored, and the Mission has advanced to the next sortie.
t scrubFromInFlightKeepsCheckedSetsAndStoresReason

# A reason of only spaces typed at In-Flight leaves nothing stored on the sortie.
t scrubStoresWhitespaceReasonAsNull

# ─── 5. Robustness ────────────────────────────────────────────────────────────
# Brief: "A double tap must not land twice or crash." and "If MissionManager rejects an operation
# … the screen shows a short message instead of crashing."
echo ""
echo "=== robustness ==="

# Two land calls back to back: the sortie is landed once, the completion callback runs once, the
# next sortie has not been advanced past, and nothing is thrown.
t doubleTapLandsOnce

# A land call that MissionManager rejects (the sortie is no longer in flight) leaves the sortie as
# it was, does not run the completion callback, raises a short message the screen can show, and
# throws nothing out of the view model.
# Decision (plan 7): the second call is ignored by a guard rather than by disabling the button.
t secondLandIsRejectedWithoutCrash

# ─── 6. The footer on the In-Flight screen ────────────────────────────────────
# Brief: "A 64 dp ink Land button … full width in the bottom area as in the mockup. It replaces the
# Back text button." and "An underlined Scrub text action in the bottom area." Nodes are asserted,
# not clicked, except where the brief's own end-to-end path is being walked.
echo ""
echo "=== the footer ==="

# On both loaded states — the checklist and the no-checklist one — the bottom area offers LAND and
# SCRUB, and no longer offers BACK.
# Decision (plan 2): the Loading footer keeps Back; this is about the loaded states only.
t landFooterOffersLandAndScrubAndNoBack

# The brief's "Launch → log → Land works for a seeded lift plan" path, walked on the screen that has
# no checklist so no confirmation is asked for: tapping LAND lands the in-flight sortie in Room and
# hands the screen back (the Launchpad is where the owner lands).
# This retargets InFlightScreenTest.noChecklistBackButtonTriggersCallback, which taps the Back
# button this brief removes; that name is not required here because the button it taps is gone.
t noChecklistLandLandsAndCallsOnBack

# ─── 7. must not break ────────────────────────────────────────────────────────
# The whole existing suite runs in the gate above. These name the brief's own "must not" clauses
# that this screen is the one touching.
echo ""
echo "=== must not break ==="

# "Don't change the Room schema": the exported Room schema JSON still matches the database.
t exportedV1SchemaMatchesDatabase
# "the checklist … its tests": the derivation, the check actions, the card footer's Add set / Skip /
# Unskip, and the StepperDialog that sets a weight through.
t initialState
t checkAndUncheck
t checkingASetMarksItDoneInRoomAndUpdatesCounter
t footerAddSetAndSkipUnskip
t openSetStepperShowsPlannedWeight
# "the notes and their tests": the note lines on the card and near the 'Sortie note' action, on both
# states, and the dialog's save path through the view model's note rules.
t seededExerciseNoteShowsMutedLineOnCard
t sortieNoteActionAndLineShowOnChecklistState
t seededSortieNoteShowsOnNoChecklistState
t noteCellOnActiveCardOpensDialogAndSaves
t sortieNoteActionOnNoChecklistOpensDialogAndSaves
t notesAreStoredTrimmed
t blankExerciseNoteStoresNull
# "MissionManager and the domain tests": the lifecycle this brief drives, unchanged.
t landAdvancesToNextSortie
t landMarksUncheckedSetsNotDone
t scrubInFlightKeepsCheckedSets
t scrubbingLastSortieClosesMission
t scrubStoresBlankReasonAsNull
t legalTransitionsSucceed
t illegalTransitionsAreRejected
# "the Launchpad … screens": it still reaches the in-flight sortie, still shows a scrubbed one as
# pending, and still reaches the closed state.
t inFlightAfterLaunch
t pendingAfterScrub
t closedAfterAllSortiesLanded
# "the Mission screens": what the owner sees after landing or scrubbing.
t landedSortieDetailShowsRecord
t scrubbedSortieDetailShowsReason
# "the shell": system back still leaves In-Flight without landing anything.
t backFromInFlightClearsTheSortieId
# "Mission Control" and the §2 vocabulary: never 'session' for a sortie.
t neverSaysSession

# ─── result ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
