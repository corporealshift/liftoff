#!/usr/bin/env bash
# Verify: issue #60 — the Mission tab says "Not confirmed yet" while the coach is generating the outline.
#
# What this checks:
#   1. the CI gate — `:app:assembleDebug` then `:app:testDebugUnitTest`, the two tasks
#      .github/workflows/ci.yml runs, in that order, with --no-build-cache so a cached test result can
#      never stand in for this tree's code. Every test the suite runs must pass;
#   2. five named tests that carry the brief's rules — the Mission tab's draft week reports the coach
#      status the Launchpad draft reports (planning, offline, failed) while an outline generation is
#      open, keeps the "Not confirmed yet" status when none is, and follows the row live as the coach
#      works, the way a user watching the tab after pressing Confirm would expect.
#
# Why it fails on the untouched tree: none of the five names exists yet, and `MissionTabState.Week`
# carries nothing derived from the coach, so no such test can be written against it as it is.
#
# What is NOT checked, and why:
#   - No Compose test of the Mission tab's drawn line. The brief asks for a MissionState test.
#   - How the wording is shared between the tabs, what the new field is called, whether
#     deriveCoachStatus is reused, or how observe() is restructured. Those are the work's choice.
#   - The muted pointer under a failed draft. It is copy, and a plan Decision the owner reviews.
#   - Whether the tab re-emits for unrelated generation writes. That is an efficiency concern from the
#     plan's Risks, not a behavior the brief asks for.
#   - Which file the new tests sit in. Names are matched across every report.
#
# Invoke it as `bash verify.sh` from the repository root (or anywhere; it finds the root).

set -euo pipefail

if [ -f ".rho/runs/issue-60-mission-tab-says-not-confirmed-9c27/verify.sh" ]; then
  ROOT="$PWD"
elif [ -f "$0" ]; then
  ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
else
  echo "verify: could not find the repository root from $0" >&2
  exit 1
fi
cd "$ROOT"

# The gate is the CI's own two tasks. The Windows developer's gradlew.sh is used where its toolchain
# exists; this Linux machine's toolchain otherwise; else the committed wrapper, which is what CI runs.
if [ -x "C:/Users/corpo/android-toolchain/gradle/bin/gradle" ]; then
  GRADLE_RUN=(bash gradlew.sh)
elif [ -x "$HOME/android-toolchain/gradle/bin/gradle" ]; then
  export JAVA_HOME="$HOME/android-toolchain/jdk"
  export ANDROID_HOME="$HOME/android-toolchain/sdk"
  GRADLE_RUN=("$HOME/android-toolchain/gradle/bin/gradle")
else
  GRADLE_RUN=(bash ./gradlew)
fi

RESULTS="app/build/test-results/testDebugUnitTest"
WORK="app/build/verify-issue60"
INDEX="$WORK/index"

# Reports are read as evidence of THIS run, so earlier results are moved aside first.
clear_results() {
  local stamp
  stamp="$(date +%s)-$ATTEMPT"
  mv "$RESULTS" "app/build/verify-old-results-$stamp" 2>/dev/null || true
  if [ -d "$RESULTS" ]; then return 1; fi
  return 0
}

# One record per testcase: "<folded name>\tPASS|FAIL\t<name as written>\t<class>\tSKIP-if-skipped".
index_reports() {
  : > "$INDEX"
  local f cls
  for f in "$RESULTS"/TEST-*.xml; do
    [ -f "$f" ] || continue
    cls=$(basename "$f" .xml); cls=${cls#TEST-}
    LC_ALL=C awk -v RS='<testcase' -v c="$cls" '
        NR > 1 {
            name = ""
            if (match($0, /name="[^"]*"/)) name = substr($0, RSTART + 6, RLENGTH - 7)
            written = name
            sub(/\[.*$/, "", name)
            gsub(/[`_ ]/, "", name)
            bad = ($0 ~ /<(failure|error|skipped)[ >/]/)
            skip = ($0 ~ /<skipped[ >/]/)
            print tolower(name) "\t" (bad ? "FAIL" : "PASS") "\t" written "\t" c "\t" (skip ? "SKIP" : "")
        }' "$f" >> "$INDEX"
  done
}

# Retried only: a build that died on the daemon/memory/executor or never wrote reports, or a run whose
# only failures are in the two Compose screen classes recorded flaking in full-suite runs on this machine.
FLAKY_CLASSES="com.liftoff.app.ui.LiftoffShellTest com.liftoff.app.ui.inflight.InFlightScreenTest"

transient_failure() {
  local log="$1" all broken
  if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|exceeded its configured timeout|Requesting stop of task|Test Executor|PersistentEnumerator storage corrupted' "$log" 2>/dev/null; then
    return 0
  fi
  if [ ! -e "$RESULTS" ]; then
    if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$log" 2>/dev/null; then
      return 1
    fi
    return 0
  fi
  if ! grep -q 'BUILD SUCCESSFUL\|BUILD FAILED' "$log" 2>/dev/null; then
    return 0
  fi
  index_reports
  all=$(awk -F'\t' '$2 == "FAIL"' "$INDEX" | wc -l | tr -d '[:space:]')
  [ "$all" -gt 0 ] || return 0
  broken=$(awk -F'\t' -v list="$FLAKY_CLASSES" '
      BEGIN { n = split(list, flaky, " ") }
      $2 == "FAIL" {
        is_flaky = 0
        for (i = 1; i <= n; i++) { if ($4 == flaky[i]) { is_flaky = 1 } }
        if (!is_flaky) { others++ }
      }
      END { print others + 0 }' "$INDEX")
  [ "$broken" -eq 0 ]
}

GATE_OK=0
GATE_LOG=""
ATTEMPT=0
mkdir -p app/build
for ATTEMPT in 1 2 3 4 5; do
  GATE_LOG="$WORK/gate-$ATTEMPT.log"
  mkdir -p "$WORK"
  echo "--- gate attempt $ATTEMPT: ${GRADLE_RUN[*]} :app:assembleDebug :app:testDebugUnitTest --no-build-cache"
  if ! clear_results; then
    echo "verify: could not clear $RESULTS" >&2
    exit 1
  fi
  if "${GRADLE_RUN[@]}" :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 | tee "$GATE_LOG"; then
    GATE_OK=1
    break
  fi
  if transient_failure "$GATE_LOG"; then
    echo "--- attempt $ATTEMPT failed on the build itself or on the recorded flaky screen classes; pausing, then running the gate again" >&2
    sleep 20
  else
    echo "--- attempt $ATTEMPT failed on a reported test result; not running the gate again" >&2
    break
  fi
done

if [ "$GATE_OK" -ne 1 ]; then
  echo "verify: FAIL — the CI gate does not pass:" >&2
  echo "  ${GRADLE_RUN[*]} :app:assembleDebug :app:testDebugUnitTest --no-build-cache" >&2
  echo "--- last 60 lines of the build output ($GATE_LOG):" >&2
  tail -n 60 "$GATE_LOG" 2>/dev/null || true
  echo "verify: FAIL" >&2
  exit 1
fi
echo "--- gate passed on attempt $ATTEMPT"

if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE|NO-SOURCE)' "$GATE_LOG" 2>/dev/null; then
  echo "verify: FAIL — the unit tests were not executed; :app:testDebugUnitTest came from the build cache or was up to date." >&2
  exit 1
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
  echo "verify: FAIL — the gate wrote no unit-test results under $RESULTS." >&2
  exit 1
fi
index_reports

TOTAL_TESTS=$(wc -l < "$INDEX" | tr -d '[:space:]')
TOTAL_FAILED=$(awk -F'\t' '$2 == "FAIL"' "$INDEX" | wc -l | tr -d '[:space:]')
TOTAL_SKIPPED=$(awk -F'\t' '$5 == "SKIP"' "$INDEX" | wc -l | tr -d '[:space:]')

normalize() { echo "$1" | sed -E 's/[`_ ]//g' | tr '[:upper:]' '[:lower:]'; }

MISSING=""

# t <name> — the test ran, and passed. Names compare with backticks, underscores and spaces removed and
# case folded. A name no report contains is a test never written or never run.
t() {
  local want state
  want=$(normalize "$1")
  state=$(awk -F'\t' -v w="$want" '
      $1 == w { if ($2 == "FAIL") { found = 2 } else if (found != 2) { found = 1 } }
      END { print found + 0 }' "$INDEX")
  if [ "$state" = 1 ]; then
    echo "  ok   $1"
  else
    echo "  FAIL $1  (no report says it ran and passed)"
    MISSING="$MISSING $1"
  fi
}

# ── the brief's rules, as named tests ──────────────────────────────────────────────────────────────
# State tests read from MissionStates.observe(), as the existing MissionStatesTest cases do.

# The brief's case: a DRAFT mission for the current week, coach configured, one open (QUEUED or
# RUNNING) OUTLINE generation for it — the row MissionManager.confirm() writes. The Week state reports
# the planning status the Launchpad draft reports, not the one "Not confirmed yet" is drawn from.
t theMissionTabReportsPlanningWhileItsDraftHasAnOpenOutlineGeneration

# Offline: the draft's open OUTLINE row carries Generation.COACH_OFFLINE in its reason. The status is
# offline, not planning and not unconfirmed.
t theMissionTabReportsOfflineWhenItsOpenOutlineRowCarriesTheCoachOfflineMark

# Failed: the draft's newest non-cancelled OUTLINE row is FAILED with a reason. The status is failed and
# carries that reason word for word (Decision "What wording the Mission tab uses"), with Retry and
# Continue offered as the Launchpad's status offers them. Decision "The Mission tab reports the status;
# the actions stay on the Launchpad": the tab gains no button, so a DRAFT week's canReset stays false.
t theMissionTabReportsFailedWithTheReasonItsOutlineRowCarriesAndOffersRetryAndContinue

# The brief's keep half (Decision "Which drafts keep 'Not confirmed yet'"): a draft with no OUTLINE row,
# and one whose only OUTLINE row is CANCELLED, report the status the sentence is drawn from — not
# planning, offline or failed.
t theMissionTabKeepsTheUnconfirmedDraftStatusWhenNoOutlineGenerationIsOpen

# The tab updates live, as a user watching it after Confirm expects: on one open subscription, the draft
# Week reads unconfirmed, then planning once a QUEUED OUTLINE row is written, then failed with the reason
# once that row is marked FAILED.
t theMissionTabFollowsItsOwnOutlineRowFromQueuedToFailedWithoutReSubscribing

# ── result ───────────────────────────────────────────────────────────────────────────────────────────
echo "--- index: $TOTAL_TESTS test records over $(ls "$RESULTS"/TEST-*.xml | wc -l | tr -d '[:space:]') reports; $TOTAL_FAILED not passing, of which $TOTAL_SKIPPED were skipped"
if [ "$TOTAL_FAILED" -ne 0 ]; then
  echo "verify: FAIL — the gate passed but a report records a failure or a skip:"
  awk -F'\t' '$2 == "FAIL" { print "  " $3 "  (" $4 ")" }' "$INDEX"
  echo "verify: FAIL"
  exit 1
fi
if [ -n "$MISSING" ]; then
  echo "verify: FAIL — did not run or did not pass:$MISSING"
  echo "verify: FAIL"
  exit 1
fi

echo "verify: $TOTAL_TESTS unit tests ran and passed, including every test named above. Issue 60 done."
