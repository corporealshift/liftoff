#!/usr/bin/env bash
# Verify that the In-Flight Checklist Screen brief is done.
# Run from repository root with: bash .nabu/runs/in-flight-checklist-screen-34ev/verify.sh

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

echo "=== Build and test ==="
OUTPUT="$(bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest 2>&1 || true)"
echo "$OUTPUT"

# The gate must pass — assemble + all unit tests.
if ! echo "$OUTPUT" | grep -q 'BUILD SUCCESSFUL'; then
    echo "FAIL: build or tests did not succeed" >&2
    exit 1
fi

XML_DIR="app/build/test-results/testDebugUnitTest"

# ── Brief requirement: screen renders header, progress and cards for a seeded lift plan ---
# Existing InFlightScreenTest exercises (eyebrow assertions) cover this.
# We require both existing tests to pass — at least one proves the screen renders correctly.

if ! [ -f "$XML_DIR/TEST-com.liftoff.app.ui.inflight.InFlightScreenTest.xml" ]; then
    echo "FAIL: InFlightScreenTest did not run (no XML result)" >&2
    exit 1
fi

# Check that InFlightScreenTest has no failures or errors.
TEST_COUNT=$(grep -oP 'tests="\K[0-9]+' "$XML_DIR/TEST-com.liftoff.app.ui.inflight.InFlightScreenTest.xml" | head -1)
FAIL_COUNT=$(grep -oP 'failures="\K[0-9]+' "$XML_DIR/TEST-com.liftoff.app.ui.inflight.InFlightScreenTest.xml" | head -1)
ERROR_COUNT=$(grep -oP 'errors="\K[0-9]+' "$XML_DIR/TEST-com.liftoff.app.ui.inflight.InFlightScreenTest.xml" | head -1)

echo "InFlightScreenTest: $TEST_COUNT tests, $FAIL_COUNT failures, $ERROR_COUNT errors"

if [ "$FAIL_COUNT" != "0" ] || [ "$ERROR_COUNT" != "0" ]; then
    echo "FAIL: InFlightScreenTest has failures or errors — screen rendering unverified" >&2
    exit 1
fi

# ── Brief requirement: tapping a checkbox marks the set done in Room and updates the counter ---
# Plan Step 8 names this test: checkingASetMarksItDoneInRoomAndUpdatesCounter
# It seeds a mission + LIFT sortie, clicks "Check off Bench Press SET 1", waits for counter to update,
# then asserts the set is DONE in Room with actuals equal to planned values.

if ! grep -q 'checkingASetMarksItDoneInRoomAndUpdatesCounter' "$XML_DIR/TEST-com.liftoff.app.ui.inflight.InFlightScreenTest.xml"; then
    echo "FAIL: checkingASetMarksItDoneInRoomAndUpdatesCounter did not run — checkbox → Room behaviour unverified" >&2
    exit 1
fi

# Check that this specific test has no failures.
# Extract the testcase element for this test and check it has no failure/error child.
if grep -A3 'name="checkingASetMarksItDoneInRoomAndUpdatesCounter"' "$XML_DIR/TEST-com.liftoff.app.ui.inflight.InFlightScreenTest.xml" | grep -q '<failure\|<error'; then
    echo "FAIL: checkingASetMarksItDoneInRoomAndUpdatesCounter failed" >&2
    exit 1
fi

echo "=== All brief requirements verified ==="
