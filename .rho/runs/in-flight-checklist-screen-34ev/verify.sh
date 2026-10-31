#!/usr/bin/env bash
# Verify that the In-Flight checklist screen brief is done.
# Run from the repository root: bash .nabu/runs/in-flight-checklist-screen-34ev/verify.sh
set -uo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

XML_DIR="app/build/test-results/testDebugUnitTest"

# Clear old results so a stale XML can never stand in for a test that did not run.
rm -rf "$XML_DIR"

echo "=== Build and test (same gate as CI) ==="
if ! bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest; then
    echo "FAIL: build gate (:app:assembleDebug :app:testDebugUnitTest) did not pass" >&2
    exit 1
fi

# require_test <fully.qualified.Class> <methodName>
# Passes only if the test ran and has no failure, error or skipped result.
require_test() {
    local cls="$1" name="$2"
    local xml="$XML_DIR/TEST-$cls.xml"
    if [ ! -f "$xml" ]; then
        echo "FAIL: $cls did not run (no result file)" >&2
        return 1
    fi
    awk -v n="$name" '
        /<testcase / {
            inside = 0
            if (index($0, " name=\"" n "\"")) {
                found = 1; inside = 1
                if ($0 ~ /\/>[[:space:]]*$/) inside = 0
            }
        }
        inside && /<(failure|error|skipped)/ { bad = 1 }
        inside && /<\/testcase>/ { inside = 0 }
        END { if (!found) exit 2; if (bad) exit 3; exit 0 }
    ' "$xml"
    case $? in
        0) echo "PASS: $cls.$name" ;;
        2) echo "FAIL: $cls.$name did not run" >&2; return 1 ;;
        3) echo "FAIL: $cls.$name failed, errored or was skipped" >&2; return 1 ;;
        *) echo "FAIL: could not read results for $cls.$name" >&2; return 1 ;;
    esac
}

SCREEN=com.liftoff.app.ui.inflight.InFlightScreenTest
status=0

# Brief, Must not break: the existing InFlightScreenTest tests must still run and pass
# (only their placeholder-dependent waits may change, per plan Step 8).
require_test "$SCREEN" inFlightShowsSortieNumberNotDatabaseId || status=1
require_test "$SCREEN" inFlightFallbackTitleUsesSortieNumber || status=1

# Brief, Done when 1: for a seeded lift plan (Robolectric Compose test on the test database),
# with the first exercise's sets checked, the screen shows the header with the counter
# (e.g. "03/07"), a collapsed done row reading "3/3 LANDED", the active card and an
# upcoming card (their uppercase names).
require_test "$SCREEN" seededLiftPlanShowsHeaderProgressAndCards || status=1

# Brief, Done when 2: on a seeded test database, tapping an open set's check box
# ("Check off Bench Press SET 1") updates the counter ("00/07" -> "01/07") and Room then
# holds that set as DONE with actuals equal to the planned values.
require_test "$SCREEN" checkingASetMarksItDoneInRoomAndUpdatesCounter || status=1

if [ "$status" -ne 0 ]; then
    echo "=== FAIL: brief not done ===" >&2
    exit 1
fi
echo "=== All brief requirements verified ==="
