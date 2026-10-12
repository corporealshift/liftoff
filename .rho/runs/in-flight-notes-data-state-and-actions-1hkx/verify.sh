#!/usr/bin/env bash
# Verify: In-Flight notes — data, state and actions (no UI). This is the definition of done.
#
# Runs the CI gate (.github/workflows/ci.yml: :app:assembleDebug :app:testDebugUnitTest),
# then reads the JUnit XML to confirm each named test actually ran and passed: Gradle
# exits 0 when a --tests filter matches nothing, and a deleted test cannot fail.
#
# The test names below are the contract. Each comment says what that test must show.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

# Stale XML from an earlier run must never stand in for a test that did not run.
rm -rf "$RESULTS"

echo "=== Gate: assembleDebug + testDebugUnitTest (same as CI) ==="
if ! bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest > "$LOG" 2>&1; then
    tail -n 80 "$LOG"
    echo "FAIL: the build gate did not pass" >&2
    exit 1
fi
echo "  gate passed"

PASS=0
FAIL=0

# require_test <fully.qualified.Class> <methodName>
# Passes only when that testcase element is present and carries no failure, error or skipped.
require_test() {
    local cls="$1" name="$2"
    local xml="$RESULTS/TEST-$cls.xml"
    if [ ! -f "$xml" ]; then
        echo "  ✗ $cls did not run (no result file)" >&2
        FAIL=$((FAIL + 1))
        return 0
    fi
    local rc=0
    awk -v n="$name" '
        /<testcase / {
            inside = 0
            if (index($0, " name=\"" n "\" classname=\"")) {
                found = 1; inside = 1
                if ($0 ~ /\/>[[:space:]]*$/) inside = 0
            }
        }
        inside && /<(failure|error|skipped)/ { bad = 1 }
        inside && /<\/testcase>/ { inside = 0 }
        END { if (!found) exit 2; if (bad) exit 3; exit 0 }
    ' "$xml" || rc=$?
    case "$rc" in
        0) echo "  ✓ $cls.$name"; PASS=$((PASS + 1)) ;;
        2) echo "  ✗ $cls.$name did not run" >&2; FAIL=$((FAIL + 1)) ;;
        3) echo "  ✗ $cls.$name failed, errored or was skipped" >&2; FAIL=$((FAIL + 1)) ;;
        *) echo "  ✗ could not read results for $cls.$name" >&2; FAIL=$((FAIL + 1)) ;;
    esac
}

V=com.liftoff.app.ui.inflight.InFlightViewModelTest

echo ""
echo "=== Saving an exercise note ==="

# An action saves a note on one exercise card, and the note is there both in the state the
# holder emits and in the state rebuilt from a fresh Room read — so it survives a process kill.
require_test "$V" exerciseNoteSavesAndSurvivesFreshRead

# The text a user types is kept whole, with only the leading and trailing whitespace dropped:
# padding around an exercise note and around a sortie note comes back trimmed, and a line break
# inside a note survives the save.
# Decision: 'How much of the text is kept?' — the whole note, trimmed at the ends only.
require_test "$V" notesAreStoredTrimmed

echo ""
echo "=== A note changes only the note ==="

# On an exercise that is already skipped, saving a note leaves the skipped flag true, the card
# still SKIPPED, and every set's status and actuals identical to before the note was saved.
# Decision: 'Does saving a note change what the exercise looks like?' — no, a note is inert.
require_test "$V" noteOnSkippedExerciseKeepsSkipAndSets

# Saving blank/whitespace-only text over an existing note stores null: the card's note is null
# in the emitted state and the plannedExercise row holds null, not "   ".
require_test "$V" blankExerciseNoteStoresNull

echo ""
echo "=== Saving the sortie note ==="

# On a sortie that has a Flight Plan, saving the sortie note re-emits the state with the note
# (the plan tables did not change, so this also proves the state reacts to a sortie-row-only
# change) and the note is on the sortie row. Saving blank then stores null in both.
require_test "$V" sortieNoteSavesOnPlannedSortie

# A sortie with no Flight Plan carries a sortie note too: the no-checklist state starts with a
# null note, saving one re-derives the state through the state holder, and the note is on the
# sortie row and in the state derived from a fresh read. Nothing here touches the plan tables.
# Decision: 'Can a sortie with no Flight Plan carry a note?' — yes.
require_test "$V" sortieNoteSavesOnSortieWithNoPlan

echo ""
echo "=== Must not break ==="

# The Room schema is unchanged: only new @Query methods, no entity or column changes.
require_test com.liftoff.app.data.SchemaExportTest exportedV1SchemaMatchesDatabase

# The checklist derivation and its actions still behave as before: initial cards, counter,
# labels and current set; one-tap check/uncheck; and the emitted state still equals a fresh
# deriveInFlight read (which now has to include the note fields).
require_test com.liftoff.app.ui.inflight.InFlightStatesTest initialState
require_test "$V" checkAndUncheck
require_test "$V" freshReadEqualsFlow

# The In-Flight screen still renders and writes through Room: tapping an open set's check box
# updates the counter and marks the set DONE in Room.
require_test com.liftoff.app.ui.inflight.InFlightScreenTest checkingASetMarksItDoneInRoomAndUpdatesCounter

echo ""
echo "=== Result: $PASS ran and passed, $FAIL missing or failed ==="

if [ "$FAIL" -gt 0 ]; then
    echo "FAILED: the brief is not done." >&2
    exit 1
fi

echo "BRIEF DONE: all required tests ran and passed."
