#!/usr/bin/env bash
# Verify: In-Flight checklist state and actions (no UI) — definition of done
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

# ── Gate: build + full test suite ────────────────────────────────────────
# This must fail now, before the work is done. The named tests below are what
# make it pass once they exist and succeed.
echo "=== Gate: assembleDebug + testDebugUnitTest ==="
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --info > /tmp/liftoff-test.log 2>&1 || { echo "GATE FAILED"; cat /tmp/liftoff-test.log; exit 1; }

# ── Check that each required test ran and passed ─────────────────────────
# The brief lists these behaviors. Each test name below must appear in the
# output as a PASSED test. Gradle passes when a name matches no test, so we
# verify every named test actually ran.
PASS=0
FAIL=0

check_test() {
    local label="$1"
    local pattern="$2"
    if grep -q "$pattern" /tmp/liftoff-test.log; then
        echo "  ✓ $label"
        PASS=$((PASS + 1))
    else
        echo "  ✗ $label — NOT RUN"
        FAIL=$((FAIL + 1))
    fi
}

echo ""
echo "=== Required tests ==="

# Initial card states, counter, labels and current set
# (Plan §Step 1: eyebrow IN FLIGHT·SORTIE 2, title=plan title, counter 0/7, cards [ACTIVE,UPCOMING,UPCOMING], labels include 35 LB, ×10, BW, 45s)
check_test "Initial state" \
    'InFlightStatesTest.*initialState'

# KG weight unit — with WeightUnit.KG the label is 20 KG
# (Plan §Step 1: KG decision)
check_test "KG unit" \
    'InFlightStatesTest.*kg'

# Title fallbacks — blank plan title falls back to focus; blank title + null focus gives Sortie n
# (Plan §Step 1: title fallback)
check_test "Title fallbacks" \
    'InFlightStatesTest.*titleFallback'

# No checklist — sortie with no plan, and RUN sortie with exercises-only plan
# (Plan §Step 1: NoChecklist carries eyebrow and title)
check_test "No checklist" \
    'InFlightStatesTest.*noChecklist'

# Check and uncheck — one tap gives DONE with actuals=planned, counter 1/7; uncheck gives OPEN, null actuals, counter 0/7
# (Plan §Step 3: check/uncheck behavior)
check_test "Check and uncheck" \
    'InFlightViewModelTest.*checkAndUncheck\|InFlightViewModelTest.*checkSet\|InFlightViewModelTest.*uncheckSet'

# Edit with deviation — saveEdit(8, null, 40.0): DONE with actuals 8/40.0, planned stays 10/35.0, both deviation flags true
# (Plan §Step 3: edit with deviation)
check_test "Edit with deviation" \
    'InFlightViewModelTest.*edit\|InFlightStatesTest.*edit'

# Finish an exercise — check all bench sets; bench DONE (3/3), push-up ACTIVE, currentSetId=push-up set 1
# (Plan §Step 3: finishing transitions card status)
check_test "Finish exercise" \
    'InFlightViewModelTest.*finish\|InFlightStatesTest.*finish'

# Skip and reopen a set — set becomes SKIPPED, counter unchanged; reopening makes it OPEN again
# (Plan §Step 3: skip/reopen set)
check_test "Skip and reopen set" \
    'InFlightViewModelTest.*skipSet\|InFlightStatesTest.*skipSet'

# Skip and unskip an exercise — bench SKIPPED, sets 2-3 SKIPPED, set 1 stays DONE; unskip reopens sets 2-3, bench ACTIVE again
# (Plan §Step 3: skip/unskip exercise)
check_test "Skip and unskip exercise" \
    'InFlightViewModelTest.*skipExercise\|InFlightStatesTest.*skipExercise'

# Add a set — pre-filled from last set's actuals if DONE, planned otherwise; turns done card active again
# (Plan §Step 3: addSet pre-fill logic)
check_test "Add set" \
    'InFlightViewModelTest.*addSet\|InFlightStatesTest.*addSet'

# Fresh read equals the flow — after mix of actions, deriveInFlight(getPlan(sortieId)) == state.value
# (Plan §Step 3: fresh-read equality)
check_test "Fresh read equals flow" \
    'InFlightViewModelTest.*freshRead\|InFlightStatesTest.*freshRead'

# No plan gives no checklist state — holder emits NoChecklist for a sortie with no plan
# (Plan §Step 3: NoChecklist emission)
check_test "No plan → NoChecklist" \
    'InFlightViewModelTest.*noPlan\|InFlightStatesTest.*noPlan'

# ── DAO action tests ─────────────────────────────────────────────────────
echo ""
echo "=== DAO action tests ==="

# checkSet copies planned values into actuals
# (Plan §Step 2: checkSet)
check_test "DAO: checkSet" \
    'FlightPlanDaoTest.*checkSet'

# skipExercise keeps a DONE set's actuals and the exercise's userNotes
# (Plan §Step 2: skipExercise preserves actuals + notes)
check_test "DAO: skipExercise" \
    'FlightPlanDaoTest.*skipExercise'

# unskipExercise reopens the SKIPPED sets
# (Plan §Step 2: unskipExercise)
check_test "DAO: unskipExercise" \
    'FlightPlanDaoTest.*unskipExercise'

# addSet pre-fills from last set's actuals when DONE, planned values otherwise
# (Plan §Step 2: addSet pre-fill)
check_test "DAO: addSet" \
    'FlightPlanDaoTest.*addSet'

# ── Summary ───────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
    echo "FAILED — not all required tests ran successfully."
    exit 1
fi

echo "ALL REQUIRED TESTS RAN AND PASSED."
exit 0
