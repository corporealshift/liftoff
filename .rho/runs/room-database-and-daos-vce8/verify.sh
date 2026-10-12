#!/usr/bin/env bash
# Verification for: Room database and DAOs (brief .nabu/runs/room-database-and-daos-vce8/brief.md)
# Runs the CI gate, then checks every named test below in the JUnit XML results:
# each must have run and passed. A missing, failed or skipped test fails the run.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if [ -f gradlew.sh ]; then
  GRADLE=(bash gradlew.sh)
else
  GRADLE=(./gradlew)
fi

RESULTS="app/build/test-results/testDebugUnitTest"
PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); }
fail() { FAIL=$((FAIL + 1)); echo "  FAIL: $1"; }

# check <TestClass> <testMethod>: passes only if that test ran and passed.
check() {
  local cls="$1" m="$2" line
  line="$(cat "$RESULTS"/TEST-*.xml 2>/dev/null \
    | grep -E "<testcase name=\"$m\" classname=\"([A-Za-z0-9_.]*\.)?$cls\"" \
    | head -1 || true)"
  if [ -z "$line" ]; then
    fail "$cls.$m did not run"
  elif [[ "$line" =~ /\>[[:space:]]*$ ]]; then
    pass
    echo "  ok: $cls.$m"
  else
    fail "$cls.$m failed or was skipped"
  fi
}

echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (as CI runs it) ==="
# Clear old results so every result read below comes from this run.
rm -rf "$RESULTS"
LOG="$(mktemp)"
if "${GRADLE[@]}" :app:assembleDebug :app:testDebugUnitTest >"$LOG" 2>&1; then
  pass
  tail -5 "$LOG"
else
  tail -60 "$LOG"
  fail "gate failed: assembleDebug or testDebugUnitTest"
fi
rm -f "$LOG"

echo ""
echo "=== Mission ==="
# Inserts a Mission and sorties with index 2, 0, 1; observeWeek(weekStart).first() lists them 0, 1, 2.
check MissionDaoTest observeWeekReturnsSortiesInIndexOrder
# observeWeek for a week with no Mission emits null.
check MissionDaoTest observeWeekIsNullForWeekWithoutMission
# update() on a Mission and on a Sortie shows on the next read.
check MissionDaoTest missionAndSortieUpdatesAreVisible
# Uniqueness: a second Mission with the same weekStart throws SQLiteConstraintException.
check MissionDaoTest duplicateWeekStartIsRejected
# Decision "no extra deletes": MissionDao and SortieDao declare no method whose name contains "delete" (checked by reflection).
check MissionDaoTest noDeleteForMissionsOrSorties

echo ""
echo "=== Sortie history ==="
# Decision "newest first history order": across two weeks, history is ordered by Mission weekStart DESC, then Sortie index DESC.
check SortieDaoTest historyIsNewestFirstByWeekThenIndex
# History holds only LANDED and SCRUBBED sorties; PENDING, PLANNED and IN_FLIGHT are left out.
check SortieDaoTest historyHasOnlyLandedAndScrubbed

echo ""
echo "=== Flight Plan ==="
# writePlan then getPlan returns exercises (with display names), sets and run segments in stored order.
check FlightPlanDaoTest writePlanThenGetPlanKeepsStoredOrder
# getPlan for a sortie with no plan returns null.
check FlightPlanDaoTest getPlanIsNullWithoutPlan
# Decision "plan replacement": a second writePlan for the same sortie leaves one FlightPlan row, and the old exercises, sets and segments are gone (row counts).
check FlightPlanDaoTest writePlanReplacesExistingPlan
# Decision "plan replacement": a writePlan that throws partway (for example an exercise named "!!!") leaves the old plan and its children intact.
check FlightPlanDaoTest failedWritePlanLeavesOldPlanIntact
# Uniqueness: a direct second insert of a FlightPlan with the same sortieId throws SQLiteConstraintException.
check FlightPlanDaoTest duplicateSortieIdIsRejected
# updateSetActuals changes actual reps, seconds, weight and status.
check FlightPlanDaoTest updateSetActualsChangesValuesAndStatus
# Decision "extra sets": addExtraSet adds a set after the exercise's last set (next order), with added = true and status OPEN.
check FlightPlanDaoTest addExtraSetAppendsAfterLastSetAsAdded
# updateExercise sets the skipped flag and the owner's notes.
check FlightPlanDaoTest updateExerciseSetsSkippedAndUserNotes

echo ""
echo "=== Exercise identity (7.7) ==="
# "Bench Press", "bench  press" and "BENCH PRESS." resolve to one row with the same id; displayName stays "Bench Press".
check ExerciseResolverTest spellingVariantsResolveToOneRow
# A Flight Plan written with "bench press" reuses the existing Exercise row.
check ExerciseResolverTest writePlanReusesResolvedExercise
# Decision "blank exercise names": a name that normalizes to empty (for example "!!!" or "   ") throws IllegalArgumentException and stores nothing.
check ExerciseResolverTest blankNameIsRejected
# Uniqueness: a direct insert with a duplicate normalizedName throws.
check ExerciseResolverTest duplicateNormalizedNameIsRejected

echo ""
echo "=== Generation ==="
# Insert, get, update (status, lastEventId, attempt, error, finishedAt), then delete, after which get returns null.
check GenerationDaoTest insertGetUpdateDelete

echo ""
echo "=== Equipment ==="
# add accepts the key "dumbbells_2", and the item can be read back.
check EquipmentDaoTest addAcceptsValidKey
# Decision "invalid equipment keys": "Bar", "pull up", "kb-24" and "" throw IllegalArgumentException, and nothing is written.
check EquipmentDaoTest invalidKeyIsRejectedBeforeWrite
# Decision "invalid equipment keys" / uniqueness: a duplicate key throws SQLiteConstraintException.
check EquipmentDaoTest duplicateKeyIsRejected
# Decision "equipment keys cannot be edited": edit changes the name and notes; the key is unchanged.
check EquipmentDaoTest editChangesNameAndNotesButNotKey
# Deactivate, don't delete: after deactivate the item is missing from observeActive, still in observeAll, and get still returns it.
check EquipmentDaoTest deactivateKeepsItemOutOfActiveList
# reactivate puts the item back in observeActive.
check EquipmentDaoTest reactivateRestoresItem
# No delete operation at all: EquipmentDao declares no method whose name contains "delete" (checked by reflection).
check EquipmentDaoTest hasNoDeleteOperation

echo ""
echo "=== AppContainer and database setup ==="
# app.container.database returns the same instance twice.
check AppContainerTest containerReturnsSameDatabase
# Brief: never drop data on migration. A database file at the container database's path (openHelper.databaseName),
# marked user_version 2 and holding a row, makes opening throw instead of wiping it; the row is still there afterwards.
check AppContainerTest databaseRefusesVersionMismatchInsteadOfWiping
# Decision "the exported schema is committed": the v1 schema JSON under app/schemas/ (read from the module dir) has
# the same identityHash as room_master_table in a freshly opened in-memory LiftoffDatabase.
check SchemaExportTest exportedV1SchemaMatchesDatabase

echo ""
echo "========================================="
echo "Results: $PASS passed, $FAIL failed"
echo "========================================="

if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
