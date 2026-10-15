#!/usr/bin/env bash
# Verify: "In-Flight notes on screen, reusing the finished note dialog" (brief.md; DESIGN.md §6,
# design/README.md 'In-Flight', design/screens/in-flight.html). Definition of done for this run.
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
# These are screen tests, because the brief is UI-only and asks for them: the note line, the Note
# cell and the 'Sortie note' action are only observable on the rendered screen. The data side is
# already built and tested, so it is not re-tested here — it is named only as "must not break".
#
# The plan's "## Decisions" are not pinned here: where the note line sits on the card, whether the
# Note cell is enabled on a DONE/SKIPPED card, whether the line is tappable or labelled, notes on
# skipped exercises, whether the action scrolls, the dialog titles, saving an empty field, Cancel.
# The owner reviews those in the pull request. Where a named test also shows a decision, the comment
# says which one.
#
# Tests 3 and 4 keep their names whichever shape lands — opening the dialog and saving, or, if the
# known BasicTextField-in-Dialog Robolectric hang reproduces (plan Decision 13, authorised by the
# brief), the same names asserting the action is present and enabled with the dialog's own behaviour
# covered by NoteDialogTest. The script asks for the behaviour the brief names; how it is proven is
# the implementer's call.
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
# This is also the "must not break" check for everything the brief lists — the Launchpad and Mission
# screens, MissionManager and the domain tests, Mission Control, the coach package, the shell — since
# CI runs the whole unit-test suite.
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test
# executed, which would read as a suite that passed.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="
rm -rf "$RESULTS" 2>/dev/null || true
if [ -d "$RESULTS" ]; then
    echo "  ✗ could not clear $RESULTS — a stranded Gradle test executor is holding it; run: bash gradlew.sh --stop" >&2
    exit 1
fi
if bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 | tee "$WORK/gate.log"; then
    ok "gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "gate failed: the app does not assemble or the unit tests do not pass"
fi
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK/gate.log"; then
    bad "unit tests executed (the task came from the build cache or was up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
fi

# ─── 2. Exercise note: the muted line on the card ─────────────────────────────
# Brief: "When a card's note isn't null, the card shows it as a short muted line … so the owner can
# see a note is there." Seeded straight through the DAO, so the line comes from the state the screen
# derives from Room, not from a draft held in the UI.
echo ""
echo "=== exercise note on the card ==="

# A note stored on one exercise of a lift sortie appears on that exercise's card as a display line,
# and nothing is opened to make it appear: no dialog window is on screen. A card whose exercise has
# no note shows no such line.
t seededExerciseNoteShowsMutedLineOnCard

# ─── 3. Exercise note: the Note cell edits it ─────────────────────────────────
# Brief: "The Note cell on each card becomes enabled. It opens the dialog for that exercise,
# pre-filled, and Save goes through saveExerciseNote."
echo ""
echo "=== exercise note: the Note cell ==="

# On a card, the Note cell is enabled (it was disabled before this run). Opening it shows the dialog
# holding the note that is stored — the stored text, not a trimmed or empty field — and saving it
# writes through the view model's note rule, so the value that comes back from Room is the trimmed
# text. The proof is the stored value changing from what was seeded, not a screenshot of the dialog.
# Decision (plan 10): the dialog is the one the previous run built and tested, not a new editor.
t noteCellOnActiveCardOpensDialogAndSaves

# ─── 4. Sortie note: the action and the saved line, on both states ────────────
# Brief: an underlined 'Sortie note' action "always visible, on both the checklist and the
# no-checklist states", opening the same dialog with Save going through saveSortieNote, and "both
# states show a saved sortie note as a short muted line near the action".
echo ""
echo "=== sortie note ==="

# On a lift sortie that has a Flight Plan: the 'Sortie note' action is on screen and enabled, and a
# sortie note stored on the sortie row shows as a muted line near it.
t sortieNoteActionAndLineShowOnChecklistState

# On a sortie with no Flight Plan — the state that today shows only 'No checklist for this sortie.':
# the 'Sortie note' action is on screen and enabled there too, and a note stored on the sortie row
# shows as a muted line, alongside the placeholder, which stays.
t seededSortieNoteShowsOnNoChecklistState

# On a sortie with no checklist, tapping 'Sortie note' opens the dialog holding the stored note, and
# saving it writes through the view model's note rule: the value that comes back from the sortie row
# is the trimmed text. This is the state that had no way to reach a note at all before this run.
t sortieNoteActionOnNoChecklistOpensDialogAndSaves

# ─── 5. The dialog this run reuses ────────────────────────────────────────────
# Brief: "Reuse the finished dialog" from commit 2cb0ed6 — a themed Dialog with a paper card, a
# multi-line field pre-filled with the current note, and Save and Cancel. These two are the dialog's
# own behaviour, which the screen tests lean on for pre-fill, Save and Cancel.
echo ""
echo "=== the note dialog ==="

# The field starts holding the note being edited; replacing its text and pressing SAVE hands the new
# text back through the save callback, exactly once.
t prefilledFieldSaveHandsBackEditedText
# CANCEL is hittable next to SAVE, dismisses and saves nothing.
# Decision (plan 12): Cancel discards the draft rather than keeping it.
t cancelDismissesWithoutSaving

# ─── 6. must not break ────────────────────────────────────────────────────────
# The whole existing suite runs in the gate above. These name the brief's own "must not" clauses that
# this screen is the one touching.
echo ""
echo "=== must not break ==="

# "Don't change the Room schema": the exported Room schema JSON still matches the database.
t exportedV1SchemaMatchesDatabase
# "Don't change … the view model's note rules": trimming, blank → null, and the note surviving a fresh
# read are already covered by InFlightViewModelTest, which the gate runs.
t notesAreStoredTrimmed
t blankExerciseNoteStoresNull
# The checklist derivation and its actions still behave as before, notes now riding along.
t initialState
t checkAndUncheck
# The checklist screen still drives Room: an open set's check box updates the counter and the set's
# status in Room; the footer's Add set / Skip / Unskip still work now that the footer's Note cell is
# enabled; and the StepperDialog — the shell this run's dialog is built on — still opens and shows
# the planned weight.
t checkingASetMarksItDoneInRoomAndUpdatesCounter
t footerAddSetAndSkipUnskip
t openSetStepperShowsPlannedWeight
# The no-checklist state still renders and still reports its back action.
t noChecklistBackButtonTriggersCallback
# The Launchpad still reaches the In-Flight screen for a launched sortie.
t inFlightAfterLaunch

# ─── result ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
