#!/usr/bin/env bash
# Verify the settings store, exercise names and AppContainer brief is done.
# Run from the repository root with bash (Git Bash on Windows).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT" || exit 1

RESULTS="$ROOT/app/build/test-results/testDebugUnitTest"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()   { echo "✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "✗ $1"; FAIL=$((FAIL + 1)); }
check() {
  local name="$1"; shift
  if "$@"; then ok "$name"; else fail "$name"; fi
}

# Prints pass, fail, skipped or missing for one test method in the JUnit XML.
case_status() {
  local f="$RESULTS/TEST-$1.xml"
  [ -f "$f" ] || { echo missing; return; }
  awk -v n="$2" '
    !open && (index($0, "<testcase name=\"" n "\"") || index($0, "<testcase name=\"" n "[")) {
      found = 1
      if ($0 !~ /\/>[[:space:]]*$/) open = 1
      next
    }
    open && /<(failure|error)/ { bad = 1 }
    open && /<skipped/ { skipped = 1 }
    open && /<\/testcase>/ { open = 0 }
    END {
      if (!found) print "missing"
      else if (bad) print "fail"
      else if (skipped) print "skipped"
      else print "pass"
    }
  ' "$f"
}

# A named test must have run and passed; missing or skipped counts as failure.
t() {
  local cls="$1" method="$2" status
  status="$(case_status "$cls" "$method")"
  if [ "$status" = pass ]; then ok "test: ${cls##*.}.$method"; else fail "test: ${cls##*.}.$method ($status)"; fi
}

# ─── 1. Build gate (what CI runs) ────────────────────────────────────
# Old results are removed so every named test below comes from this run.

echo "─── Build gate ─────────────────────────────────────────────────────"
rm -rf "$RESULTS"
bash "$ROOT/gradlew.sh" :app:assembleDebug :app:testDebugUnitTest 2>&1 | tee "$WORK/gate.log"
GATE_RC=${PIPESTATUS[0]}
check "gate: :app:assembleDebug :app:testDebugUnitTest succeeds" [ "$GATE_RC" -eq 0 ]

echo ""
echo "─── Named tests ────────────────────────────────────────────────────"

# ─── 2. Settings store ───────────────────────────────────────────────
# Plain JVM tests, each on its own DataStore in a temporary file.
S=com.liftoff.app.settings.SettingsStoreTest

# Brief: a fresh store reports every default: host "", port 8737, token "",
# workspace "", pattern RLRLR, sortie length 60, lb, mi, history 28 days,
# run plans off, objectives "", constraints "".
t $S freshStoreHasAllDefaults

# Brief: a set-then-read round trip of every field, one test per field.
# Port includes 1 and 65535; pattern includes "R" and "RLRLRLR";
# sortie length and history window include 1; units cover both values.
t $S roundTripsDaemonHost
t $S roundTripsDaemonPort
t $S roundTripsDaemonToken
t $S roundTripsCoachWorkspacePath
t $S roundTripsDefaultPattern
t $S roundTripsSortieLengthMinutes
t $S roundTripsWeightUnit
t $S roundTripsDistanceUnit
t $S roundTripsHistoryWindowDays
t $S roundTripsGenerateRunPlans
t $S roundTripsObjectives
t $S roundTripsConstraints

# Brief: one update function per setting. Setting one field leaves every
# other field as it was.
t $S settingOneFieldLeavesOthersUnchanged

# Brief: an observable flow of all settings. A collector already subscribed
# to the flow sees a later update.
t $S settingsFlowEmitsUpdates

# Brief: the store is persistent. A new SettingsStore on the same file,
# opened after the first one is closed, reads the values written before.
t $S valuesPersistAcrossStoreInstances

# Brief: invalid values are rejected and the earlier valid value is still stored.
# Pattern "", 8 chars, "RLX", "R L"; port 0, 65536, -1; sortie length 0, -5;
# history window 0, -1.
t $S rejectsInvalidPatternAndKeepsPrevious
t $S rejectsPortOutOfRangeAndKeepsPrevious
t $S rejectsNonPositiveSortieLengthAndKeepsPrevious
t $S rejectsNonPositiveHistoryWindowAndKeepsPrevious

# Decision: units are typed enums. A stored weight or distance unit that
# can't be read ("stone", "furlong") reads as LB and MI, without throwing.
t $S unreadableStoredUnitsFallBackToDefaults

# Decision: pattern case. Only uppercase R and L are accepted; "rlr" and
# "Rl" are rejected (not uppercased) and the earlier pattern is still stored.
t $S rejectsLowercasePatternAndKeepsPrevious

# Decision: free-text fields are stored verbatim. Host, token, workspace path,
# objectives and constraints with leading/trailing spaces and newlines read
# back exactly as written.
t $S freeTextFieldsAreStoredVerbatim

# Decision: rejection exception. Every rejected value throws
# IllegalArgumentException, and nothing is written.
t $S invalidValueThrowsIllegalArgumentException

# ─── 3. Exercise names ───────────────────────────────────────────────
E=com.liftoff.app.coach.ExerciseNamesTest

# Brief: 'Bench Press', 'bench  press', 'BENCH PRESS' and ' Bench Press. '
# all normalize to 'bench press'.
t $E equivalentSpellingsNormalizeToBenchPress

# Brief: 'Bench-Press' keeps its hyphen ("bench-press"); spaces never become hyphens.
t $E hyphenIsKept

# Brief: symbols such as '&', '(' , ')' and an apostrophe are removed
# ("Farmer's Walk" gives "farmers walk").
t $E symbolsAreRemoved

# Decision: Unicode in exercise names. Unicode letters and digits are kept
# ("Café Curl" gives "café curl").
t $E unicodeLettersAndDigitsAreKept
# Any Unicode whitespace (tab, newline, NBSP) collapses to one space.
t $E unicodeWhitespaceCollapsesToOneSpace
# Lower-casing ignores the default locale ("INCLINE" under a Turkish default
# locale gives "incline").
t $E lowercasingIgnoresDefaultLocale

# Decision: a name with only symbols ("!!!") normalizes to "" and does not throw.
t $E symbolsOnlyNameNormalizesToEmpty

# ─── 4. AppContainer ─────────────────────────────────────────────────
# Robolectric, pinned to an SDK that runs on CI's Java 17.
A=com.liftoff.app.AppContainerTest

# Brief: the app's Application is the registered subclass (manifest wiring).
t $A applicationIsLiftoffApplication

# Brief: the Application owns one container (same instance on repeated access).
t $A applicationOwnsOneContainer

# Brief: the container returns the same settings store on repeated access.
t $A containerReturnsSameSettingsStore

# Decision: when the store is created. The container builds the DataStore-backed
# store from the application context on first use; a value written through it
# lands in the app's datastore/settings.preferences_pb file.
t $A settingsArePersistedToSettingsPreferencesFile

# ─── Summary ─────────────────────────────────────────────────────────
echo ""
echo "Passed: $PASS  Failed: $FAIL"
[ "$FAIL" -eq 0 ]
