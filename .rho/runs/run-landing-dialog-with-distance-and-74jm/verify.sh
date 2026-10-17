#!/usr/bin/env bash
# Verify: "Run landing dialog with distance and duration" (brief.md; DESIGN.md §5.3, the run paragraph
# of §6, §8 Sortie, design/README.md). Definition of done for this run: exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug
# :app:testDebugUnitTest), then every named test read out of the JUnit XML reports this run produced.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's
# reports; a test that was not written cannot pass on its own.
#
# Named tests are matched by method name across every test report, so which class and package each one
# sits in is the implementer's call; the behaviour it must show is written above it. Every name under
# "the dialog" and "landing a run" is a test this run has to write. The names under "a run with no plan"
# and "must not break" are tests the repo already has, which this run has to keep passing.
#
# The brief's Done-when names Robolectric tests, so the behaviour is carried by tests over the view model
# and in-memory Room, plus render-only checks of the dialog's content. The brief says a Compose test may
# only check what is rendered: no Compose test here types into, focuses or clicks anything, and none opens
# the Dialog shell (under Robolectric a text field inside a Dialog never lets Compose go idle). The cost,
# which the plan states and the PR should say: the lines that wire the dialog to landRun and to clearing
# the errors on dismiss are not covered at screen level. The view-model tests and the card tests cover
# both sides of them.
#
# The plan's "## Decisions" are not pinned here: which Land taps open the dialog, the exact field labels,
# what a failed write does to the dialog, how long a field error stays, the dialog's title, whether Land
# can be disabled, and whether both errors show at once. The owner reviews those in the pull request.
# Where a named test also shows a decision, the comment says which.
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
# This is also the "must not break" check for everything the brief lists — the run In-Flight screen, the
# lift In-Flight flow with its notes, Land, the unchecked-sets confirmation and Scrub, the Launchpad and
# Mission screens, MissionManager and the domain tests, Mission Control, the coach package, the build
# gate — since CI runs the whole unit-test suite.
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test executed,
# which would read as a suite that passed.
# The timeout is the check on "every wait in a test needs a timeout": a wait that never ends hangs the
# suite, and a hung suite is a failure, not a run that is still going.
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

# ─── 2. The dialog the owner is shown when a run lands ────────────────────────
# Brief: "Land on a run that has a plan opens a themed dialog … two optional fields … distance, labelled
# with the configured unit, and duration in minutes. Both start blank each time the dialog opens … A field
# that isn't a positive number shows its error inline under that field". Rendered content only: the Dialog
# shell is not opened and nothing is typed into, focused or clicked, as the brief requires.
echo ""
echo "=== the run landing dialog ==="

# The card offers both fields and each is labelled the way the rest of the screen reads it: the distance
# field carries the unit the owner configured — the mile setting gives the mile label, the kilometre
# setting gives the kilometre label — and the duration field carries minutes. Both fields are blank when
# the card is shown. A card built for the other unit carries that unit's label, so the label comes from
# the setting rather than from one fixed word.
# Decision (plan): field labels.
t runLandDialogLabelsDistanceWithTheConfiguredUnit

# A distance that isn't a positive number puts its error under the distance field and nothing under the
# duration field; a bad duration does the mirror; both bad shows both. Nothing is typed here: the errors
# are the ones the view model raises, handed to the card, which is how the screen hands them over.
# Decision (plan): both errors at once.
t runLandDialogShowsEachFieldErrorUnderItsOwnField

# ─── 3. Landing a run with a distance and a duration ──────────────────────────
# Brief's first Done-when line: "Launch → Land of a simple run with a distance and a duration works",
# proven on a launched run sortie through the view model's landRun against in-memory Room, with the sortie
# row read back from the DAO. Every wait carries a timeout.
echo ""
echo "=== landing a run through landRun ==="

# Launch a run, then land it with a distance and a duration typed in: landRun accepts them, the completion
# callback (what returns to the Launchpad) runs exactly once, and the sortie row is LANDED carrying that
# distance and that duration, with a landed time recorded and no failure message raised. This is the
# brief's headline line: landing a run records the run's actuals.
t runLandWithDistanceAndDurationStoresBoth

# The same Land on a launched run with both fields left blank: landRun accepts it, the completion callback
# runs, the sortie is LANDED, and both run fields are null rather than a distance or a duration invented
# for it.
t runLandWithBlankFieldsStoresNulls

# On a launched run, a distance that is text, negative or zero is refused on the distance field alone:
# landRun returns false, the error names the distance field and nothing else, the completion callback
# never runs, no failure message is raised, and the sortie row is still IN_FLIGHT with both run fields
# null — nothing was landed.
t runLandRejectsBadDistanceOnItsField

# The mirror on the same run: a duration that is text, negative or zero is refused on the duration field
# alone, with a good distance typed beside it — the error names the duration field only, and the sortie
# stays IN_FLIGHT with nothing stored.
t runLandRejectsBadDurationOnItsField

# A second Land while the first landing is still being written is ignored, as on lifts: the second landRun
# is refused while the write is in progress (the write has to be held, or the second call would merely
# queue behind the first and prove nothing), the completion callback runs once, the sortie is landed
# once, and nothing escapes the view model.
t runLandDoubleTapLandsOnce

# ─── 4. A run with no plan still lands without the dialog ─────────────────────
# Brief: "A sortie with no plan still lands without the dialog." The screen test is the only one of these
# that can see a dialog at all, and it is the test the brief's own flow reaches: it taps Land on a plan-less
# RUN sortie. It exists already and this run must leave it working.
echo ""
echo "=== a run with no plan ==="

# Tapping Land on a RUN sortie that has no Flight Plan lands it and calls the screen's onBack callback on
# the first tap, with no dialog in between: the sortie row is LANDED and both run fields are null. If a
# dialog were opened for a plan-less run, Land would no longer land on the first tap and this would fail.
# Decision (plan): which Land taps open the dialog.
t noChecklistLandLandsAndCallsOnBack

# That sortie keeps today's no-checklist state, which is why it takes the path that has no dialog: the run
# state needs a plan, and landing that state needs no confirmation.
t runWithNoPlanStillShowsNoChecklist
t noChecklistNeedsNoConfirmation

# ─── 5. must not break ────────────────────────────────────────────────────────
# The whole existing suite runs in the gate above. These name the brief's own "must not" clauses that
# this work is the one touching: the run In-Flight screen and its state, the lift flow with its notes,
# Land, the unchecked-sets confirmation, Scrub, the finished landRun / parseRunEntry / MissionManager
# pieces this dialog now calls, and the Room schema.
echo ""
echo "=== must not break ==="

# "the run In-Flight screen": the run screen still shows its plan and offers Note, Land and Scrub — Land
# present and enabled, which is what the owner sees before the dialog opens.
t runScreenShowsPlanAndOffersNoteLandAndScrub
t runStateFromSeededPlanWithSegments
t runStateFromSimpleRunPlan
t runDistanceUnitChangeUpdatesStateLive
t sortieNoteSavesOnRunWithAPlan
t scrubFromRunStateStoresReason
# The view model's plain Land on a run, unchanged: it lands, needs no unchecked-sets confirmation, and
# stores no run actuals.
t launchAndLandSimpleRunFromRunState
t landingARunNeedsNoChecklist
# The finished pieces this dialog now calls, unchanged: landRun's refusals, and the manager storing — or
# not storing — the run actuals inside the landing transaction.
t landRunRejectsInvalidDistanceWithoutTouchingTheManager
t landRunRejectsInvalidDurationWithoutTouchingTheManager
t landStoresRunDistanceAndRunMinutes
t landWithoutRunActualsLeavesThemNull
t decimalDistanceIsAccepted
t bothFieldsBadReportBothErrors
# "the lift In-Flight flow with its notes, Land, the unchecked-sets confirmation and Scrub": the lift
# Land and its confirmation dialog, the lift double tap, the note lines and note dialogs, and the two
# confirmation dialogs this dialog is styled after.
t landWithEverySetChecked
t landRecordsUncheckedSetsAsNotDone
t landStoresLandedTime
t landMakesNextRunSortieCurrentWithSimpleRunPlan
t landingLastSortieFromInFlightClosesMission
t doubleTapLandsOnce
t secondLandIsRejectedWithoutCrash
t confirmationNeededOnlyWhenSetsAreOpen
t openCountCountsOpenSets
t landConfirmStatesTheOpenCountAndSingularForOneSet
t landConfirmButtonsCallConfirmOrDismiss
t scrubDialogSendsTheTypedReasonOnConfirm
t scrubDialogCancelDismissesWithoutConfirming
t scrubFromInFlightKeepsCheckedSetsAndStoresReason
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
# "Don't change the Room schema": the exported Room schema JSON still matches the database.
t exportedV1SchemaMatchesDatabase
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
