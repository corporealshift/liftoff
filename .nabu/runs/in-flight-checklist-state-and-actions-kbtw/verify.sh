#!/usr/bin/env bash
# Verify: In-Flight checklist state and actions (no UI). This is the definition of done.
# Runs the CI gate, then checks the JUnit XML to confirm that each named test
# ran and passed. Gradle exits 0 even when a test it was meant to run doesn't exist.
set -uo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
LOG="$(mktemp)"

# Delete old results so a stale XML from an earlier run can't count as a pass.
rm -rf "$RESULTS"

echo "=== Gate: assembleDebug + testDebugUnitTest ==="
if ! bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest > "$LOG" 2>&1; then
    tail -n 80 "$LOG"
    echo "GATE FAILED"
    exit 1
fi
echo "  gate passed"

PASS=0
FAIL=0

# check_test <fully.qualified.Class> <methodName> <label>
# A test passes when its <testcase> element is self-closing: no <failure>, <error> or <skipped>.
check_test() {
    local cls="$1" name="$2" label="$3"
    local xml="$RESULTS/TEST-$cls.xml"
    if [ -f "$xml" ] && grep -F "<testcase name=\"$name\" classname=\"$cls\"" "$xml" | grep -q '/>[[:space:]]*$'; then
        echo "  ✓ $label ($name)"
        PASS=$((PASS + 1))
    else
        echo "  ✗ $label ($cls.$name): did not run or did not pass"
        FAIL=$((FAIL + 1))
    fi
}

S="com.liftoff.app.ui.inflight.InFlightStatesTest"
V="com.liftoff.app.ui.inflight.InFlightViewModelTest"

echo ""
echo "=== Derivation on a fresh getPlan read ==="

# Eyebrow 'IN FLIGHT · SORTIE 2', plan title, counter 0/7, 7 open segments,
# cards [ACTIVE, UPCOMING, UPCOMING] with 0/3, 0/2, 0/2, labels 35 LB / × 10 / BW / 45 s,
# current set = first bench set, nothing added, no deviations.
check_test "$S" initialState "Initial card states, counter, labels and current set"

# Weight label in the configured unit: KG gives '20 KG'.
check_test "$S" kgUnitLabel "Weight label in KG"

# A blank plan title falls back to the focus, and then to 'Sortie n'.
check_test "$S" titleFallbacks "Title fallbacks"

# No plan, and a run plan with no exercises, both give NoChecklist.
# Decision: 'A no checklist state still carries the eyebrow and title'.
check_test "$S" noChecklist "No checklist for a sortie with no plan or no exercises"

echo ""
echo "=== State holder actions through the StateFlow ==="

# Check gives DONE with actuals = planned, 1/7 and a DONE segment. Uncheck gives OPEN, null actuals, 0/7.
check_test "$V" checkAndUncheck "One-tap check and uncheck"

# Edit gives DONE with actual 8 reps / 40.0. Planned stays 10 / 35.0, and both deviation flags are set.
# Decision: 'Weight and count labels on a DONE set' (labels show the actuals).
check_test "$V" editWithDeviation "Edit with a deviation keeps the planned values"

# Checking every bench set makes bench DONE (3/3), push-up ACTIVE and current set = push-up set 1.
check_test "$V" finishExercise "Finishing an exercise activates the next one"

# Skipped set: counter unchanged, SKIPPED segment. Reopen makes it OPEN again.
check_test "$V" skipAndReopenSet "Skip and reopen a set"

# Skip bench after checking set 1: card SKIPPED, sets 2-3 SKIPPED, set 1 keeps its actuals, push-up ACTIVE.
# Unskip reopens sets 2-3 and bench is ACTIVE again.
# Decision: 'Unskip an exercise' (reopens every SKIPPED set).
check_test "$V" skipAndUnskipExercise "Skip and unskip an exercise"

# The added set copies the last set's actuals when it is DONE and its planned values otherwise.
# It is OPEN, added and labelled 'SET 4'. A done card turns ACTIVE again and the counter becomes x/8.
check_test "$V" addSet "Add a set"

# After a mix of actions, the state the flow emitted equals deriveInFlight on a fresh getPlan read.
check_test "$V" freshReadEqualsFlow "Fresh database read equals the emitted state"

# The holder emits NoChecklist for a sortie with no plan.
check_test "$V" noPlan "No plan gives the no checklist state"

echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
rm -f "$LOG"

if [ "$FAIL" -gt 0 ]; then
    echo "FAILED: not every required test ran and passed."
    exit 1
fi

echo "ALL REQUIRED TESTS RAN AND PASSED."
exit 0
