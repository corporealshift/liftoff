#!/usr/bin/env bash
# Verify that the Launchpad and Mission screens brief is done.
# Exit 0 only when every requirement in brief.md is met.
set -euo pipefail

cd "$(dirname "$0")/../.."

PASS=0
FAIL=0

pass() { echo "  ✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# ── Gate: build and run all unit tests ──────────────────────────────
# The project gate. If this fails, nothing else matters.
echo ""
echo "=== Build gate ==="
if bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --info > /dev/null 2>&1; then
    pass "Build and testDebugUnitTests pass"
else
    fail "Build or testDebugUnitTests failed"
fi

# ── Test: Launchpad state derivation from the database ──────────────
# Covers every LaunchpadState variant required by brief §4.5:
#   Loading, Draft (default pattern on fresh DB), PLANNED lift with seeded exercises,
#   PLANNED run after confirming RLRLR, PENDING after scrubbing that run,
#   IN_FLIGHT after launch, and Closed. Also checks live updates after confirm/scrub/launch.
echo ""
echo "=== Launchpad state tests ==="
if bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.launchpad.LaunchpadStatesTest" --info 2>&1 | tee /tmp/laun.txt; then
    # Confirm the test actually ran (not just matched nothing).
    if grep -q 'LaunchpadStatesTest' /tmp/laun.txt; then
        pass "LaunchpadStatesTest ran and passed"
    else
        fail "LaunchpadStatesTest did not run"
    fi
else
    fail "LaunchpadStatesTest failed or did not run"
fi

# ── Test: Mission tab derivation from the database ──────────────────
# Covers MissionTabState: pattern chips from mixed sortie states (landed, scrubbed, current, upcoming),
# outline notes present and absent, sortie rows with index/type/focus/state, draft mission,
# and sortie detail for PLANNED, PENDING, LANDED, and SCRUBBED sorties.
echo ""
echo "=== Mission tab tests ==="
if bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.mission.MissionStatesTest" --info 2>&1 | tee /tmp/miss.txt; then
    if grep -q 'MissionStatesTest' /tmp/miss.txt; then
        pass "MissionStatesTest ran and passed"
    else
        fail "MissionStatesTest did not run"
    fi
else
    fail "MissionStatesTest failed or did not run"
fi

# ── Test: Plan format functions ─────────────────────────────────────
# Plain JUnit tests for every formatter:
#   weight 135.0 → "135", 22.5 → "22.5", null weight → "BW", seconds → "45 s",
#   differing sets, null estimate, singular "1 set".
echo ""
echo "=== Format tests ==="
if bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.sortie.PlanFormatTest" --info 2>&1 | tee /tmp/format.txt; then
    if grep -q 'PlanFormatTest' /tmp/format.txt; then
        pass "PlanFormatTest ran and passed"
    else
        fail "PlanFormatTest did not run"
    fi
else
    fail "PlanFormatTest failed or did not run"
fi

# ── Test: In-Flight route in ShellNav ───────────────────────────────
# Covers the in-flight route encode/decode round trip and back behaviour.
# Decision 5: back returns to Launchpad, clearing the in-flight route from ShellNav.
echo ""
echo "=== ShellNav in-flight tests ==="
if bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.ShellNavTest" --info 2>&1 | tee /tmp/shellnav.txt; then
    if grep -q 'ShellNavTest' /tmp/shellnav.txt; then
        pass "ShellNavTest ran and passed"
    else
        fail "ShellNavTest did not run"
    fi
else
    fail "ShellNavTest failed or did not run"
fi

# ── Test: Shell integration with real screens ───────────────────────
# LiftoffShellTest updated for the real screens: draft's CONFIRM button on Launchpad,
# Mission tab title, tab switching, back handling, recreation, nav-bar icon contrast.
# Decision 13: Loading state shown before current week has been ensured.
echo ""
echo "=== Shell integration tests ==="
if bash gradlew.sh :app:testDebugUnitTest --tests "com.liftoff.app.ui.LiftoffShellTest" --info 2>&1 | tee /tmp/shell.txt; then
    if grep -q 'LiftoffShellTest' /tmp/shell.txt; then
        pass "LiftoffShellTest ran and passed"
    else
        fail "LiftoffShellTest did not run"
    fi
else
    fail "LiftoffShellTest failed or did not run"
fi

# ── Check: ARCHITECTURE.md marks M2 as done ─────────────────────────
# Brief requires: "ARCHITECTURE.md's milestone table marks M2 as done."
echo ""
echo "=== Architecture M2 marker ==="
if grep -q '| M2 |.*Done' ARCHITECTURE.md; then
    pass "M2 marked as Done in ARCHITECTURE.md"
else
    fail "M2 not marked as Done in ARCHITECTURE.md"
fi

# ── Summary ─────────────────────────────────────────────────────────
echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
    echo "Brief NOT done."
    exit 1
fi

echo "All checks passed. Brief is done."
exit 0
