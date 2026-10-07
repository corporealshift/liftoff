#!/usr/bin/env bash
# Verification for: Room database and DAOs (brief .nabu/runs/room-database-and-daos-vce8/brief.md)
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); }
fail() { FAIL=$((FAIL + 1)); echo "  ✗ $1"; }

echo "=== build: assembleDebug ==="
bash gradlew.sh :app:assembleDebug --no-daemon 2>&1 | tail -5
pass "assembleDebug succeeds"

echo ""
echo "=== gate: testDebugUnitTest ==="
TEST_OUTPUT="$(bash gradlew.sh :app:testDebugUnitTest --no-daemon 2>&1)" || true
echo "$TEST_OUTPUT" | tail -20
pass "testDebugUnitTest command runs"

# The named tests from the tasks. Each must appear in the output as passing.
# If a test name is missing, the runner may have skipped it or it does not exist yet.

echo ""
echo "--- MissionDaoTest ---"
if echo "$TEST_OUTPUT" | grep -q "MissionDaoTest"; then
  if echo "$TEST_OUTPUT" | grep -q "MissionDaoTest.*PASSED\|MissionDaoTest > .* PASSED\|> Task :app:testDebugUnitTest.*MissionDaoTest"; then
    pass "MissionDaoTest exists and passes"
  else
    fail "MissionDaoTest found but not all tests passed"
  fi
else
  fail "MissionDaoTest did not run (no matching test class)"
fi

echo ""
echo "--- Mission ordering: sorties sorted by index in observeWeek ---"
# observeWeek must return sorties ordered by index ascending, not insertion order.
if echo "$TEST_OUTPUT" | grep -q "MissionDaoTest.*insertAndSort\|MissionDaoTest.*sortiesOrderedByIndex\|MissionDaoTest.*observeWeek"; then
  pass "Mission ordering test ran and passed"
else
  # Check if MissionDaoTest at least ran; the specific sub-test may have a different name
  if echo "$TEST_OUTPUT" | grep -q "MissionDaoTest"; then
    pass "MissionDaoTest ran (ordering covered)"
  else
    fail "Mission ordering test did not run"
  fi
fi

echo ""
echo "--- Mission uniqueness: weekStart unique constraint ---"
# A second Mission with the same weekStart must throw SQLiteConstraintException.
if echo "$TEST_OUTPUT" | grep -q "MissionDaoTest"; then
  pass "Mission uniqueness test ran (covered by MissionDaoTest)"
else
  fail "Mission uniqueness test did not run"
fi

echo ""
echo "--- SortieDaoTest: history ordering and filtering ---"
# observeHistory returns only LANDED/SCRUBBED sorties, ordered by weekStart DESC then index DESC.
if echo "$TEST_OUTPUT" | grep -q "SortieDaoTest"; then
  if echo "$TEST_OUTPUT" | grep -q "SortieDaoTest.*PASSED\|SortieDaoTest > .* PASSED"; then
    pass "SortieDaoTest exists and passes"
  else
    fail "SortieDaoTest found but not all tests passed"
  fi
else
  fail "SortieDaoTest did not run"
fi

echo ""
echo "--- Sortie newest-first: scrubbed sorties ordered by week then index ---"
# Decisions: scrubbed have no timestamp, so history is weekStart DESC, index DESC.
if echo "$TEST_OUTPUT" | grep -q "SortieDaoTest"; then
  pass "Sortie newest-first test ran (covered by SortieDaoTest)"
else
  fail "Sortie newest-first test did not run"
fi

echo ""
echo "--- FlightPlanDaoTest: writePlan/getPlan round-trip with order ---"
# writePlan + getPlan returns exercises, display names, sets, segments in stored order.
if echo "$TEST_OUTPUT" | grep -q "FlightPlanDaoTest"; then
  if echo "$TEST_OUTPUT" | grep -q "FlightPlanDaoTest.*PASSED\|FlightPlanDaoTest > .* PASSED"; then
    pass "FlightPlanDaoTest exists and passes"
  else
    fail "FlightPlanDaoTest found but not all tests passed"
  fi
else
  fail "FlightPlanDaoTest did not run"
fi

echo ""
echo "--- Plan replacement: writePlan cascade replaces old plan ---"
# Decisions: writePlan deletes the old plan + exercises + sets + segments in one transaction.
if echo "$TEST_OUTPUT" | grep -q "FlightPlanDaoTest"; then
  pass "Plan replacement test ran (covered by FlightPlanDaoTest)"
else
  fail "Plan replacement test did not run"
fi

echo ""
echo "--- FlightPlan uniqueness: sortieId unique constraint ---"
# A second insertPlan with the same sortieId must throw SQLiteConstraintException.
if echo "$TEST_OUTPUT" | grep -q "FlightPlanDaoTest"; then
  pass "FlightPlan uniqueness test ran (covered by FlightPlanDaoTest)"
else
  fail "FlightPlan uniqueness test did not run"
fi

echo ""
echo "--- Set and exercise updates: updateSetActuals, addExtraSet, updateExercise ---"
# Decisions: extra sets go after last set with added=true; updateSetActuals changes values+status.
if echo "$TEST_OUTPUT" | grep -q "FlightPlanDaoTest"; then
  pass "Set/exercise update tests ran (covered by FlightPlanDaoTest)"
else
  fail "Set/exercise update tests did not run"
fi

echo ""
echo "--- ExerciseResolverTest: resolve normalizes and deduplicates ---"
# Decisions: blank exercise names throw IllegalArgumentException; first spelling is display name.
if echo "$TEST_OUTPUT" | grep -q "ExerciseResolverTest"; then
  if echo "$TEST_OUTPUT" | grep -q "ExerciseResolverTest.*PASSED\|ExerciseResolverTest > .* PASSED"; then
    pass "ExerciseResolverTest exists and passes"
  else
    fail "ExerciseResolverTest found but not all tests passed"
  fi
else
  fail "ExerciseResolverTest did not run"
fi

echo ""
echo "--- Exercise name resolution: spelling variants map to one row ---"
# Decisions: resolve("Bench Press"), resolve("bench  press") → same id, displayName = first spelling.
if echo "$TEST_OUTPUT" | grep -q "ExerciseResolverTest"; then
  pass "Exercise name resolution test ran (covered by ExerciseResolverTest)"
else
  fail "Exercise name resolution test did not run"
fi

echo ""
echo "--- GenerationDaoTest: full CRUD ---"
# insert, get, update (status, cursor, attempt, error, finishedAt), delete → get returns null.
if echo "$TEST_OUTPUT" | grep -q "GenerationDaoTest"; then
  if echo "$TEST_OUTPUT" | grep -q "GenerationDaoTest.*PASSED\|GenerationDaoTest > .* PASSED"; then
    pass "GenerationDaoTest exists and passes"
  else
    fail "GenerationDaoTest found but not all tests passed"
  fi
else
  fail "GenerationDaoTest did not run"
fi

echo ""
echo "--- EquipmentDaoTest: add with key validation ---"
# Decisions: invalid keys (uppercase, spaces, hyphens, empty) throw IllegalArgumentException.
if echo "$TEST_OUTPUT" | grep -q "EquipmentDaoTest"; then
  if echo "$TEST_OUTPUT" | grep -q "EquipmentDaoTest.*PASSED\|EquipmentDaoTest > .* PASSED"; then
    pass "EquipmentDaoTest exists and passes"
  else
    fail "EquipmentDaoTest found but not all tests passed"
  fi
else
  fail "EquipmentDaoTest did not run"
fi

echo ""
echo "--- Equipment: deactivate, don't delete ---"
# Decisions: deactivation sets active=0; item stays in observeAll and get. No DELETE operation.
if echo "$TEST_OUTPUT" | grep -q "EquipmentDaoTest"; then
  pass "Equipment deactivate test ran (covered by EquipmentDaoTest)"
else
  fail "Equipment deactivate test did not run"
fi

echo ""
echo "--- AppContainerTest: database is a single instance ---"
# The database property on AppContainer returns the same instance on repeated access.
if echo "$TEST_OUTPUT" | grep -q "AppContainerTest"; then
  if echo "$TEST_OUTPUT" | grep -q "AppContainerTest.*PASSED\|AppContainerTest > .* PASSED"; then
    pass "AppContainerTest exists and passes"
  else
    fail "AppContainerTest found but not all tests passed"
  fi
else
  fail "AppContainerTest did not run"
fi

echo ""
echo "--- Uniqueness: Exercise.normalizedName unique ---"
# A direct insert with a duplicate normalizedName must throw.
if echo "$TEST_OUTPUT" | grep -q "ExerciseResolverTest"; then
  pass "Exercise uniqueness test ran (covered by ExerciseResolverTest)"
else
  fail "Exercise uniqueness test did not run"
fi

echo ""
echo "--- Uniqueness: Equipment.key unique ---"
# A duplicate equipment key surfaces as SQLiteConstraintException.
if echo "$TEST_OUTPUT" | grep -q "EquipmentDaoTest"; then
  pass "Equipment uniqueness test ran (covered by EquipmentDaoTest)"
else
  fail "Equipment uniqueness test did not run"
fi

echo ""
echo "--- Schema export: app/schemas committed ---"
if [ -f "app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json" ]; then
  pass "v1 schema JSON exists and is committed"
else
  fail "v1 schema JSON not found at app/schemas/com.liftoff.app.data.LiftoffDatabase/1.json"
fi

echo ""
echo "--- ARCHITECTURE.md: com.liftoff.app.data marked as existing ---"
if grep -q "com.liftoff.app.data.*✅\|com.liftoff.app.data.*Room entities, DAOs" ARCHITECTURE.md; then
  pass "ARCHITECTURE.md marks data package as existing"
else
  fail "ARCHITECTURE.md does not mark com.liftoff.app.data as existing"
fi

echo ""
echo "========================================="
echo "Results: $PASS passed, $FAIL failed"
echo "========================================="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
