#!/usr/bin/env bash
# Verify: issue #60 — the Mission tab says "Not confirmed yet" while the coach is generating the outline.
#
# What this checks, in the order it checks it:
#   1. the CI gate — `:app:assembleDebug` then `:app:testDebugUnitTest`, the two tasks
#      .github/workflows/ci.yml runs, in that order, with --no-build-cache so a cached test result can
#      never stand in for this tree's code;
#   2. the six named tests that carry the brief's two rules — the Mission tab's draft week reports the
#      coach status the Launchpad draft reports while an outline generation is open, and keeps the
#      "Not confirmed yet" status when none is — plus the follow-live and don't-churn rules the plan's
#      Testing section names for the one-subscription join;
#   3. that nothing else broke: the Mission tab's own state class, the tests that read its Week shape,
#      the Launchpad draft classes whose wording the tab now shares, and the domain rule the tab now
#      reuses — every test in each of those classes, not just some.
#
# Why it fails on the untouched tree: none of the six names exists yet. MissionStatesTest has twelve
# tests today and not one of them asserts anything about a coach status — the only draft case is
# `draftMissionHasNoRows`, and `MissionTabState.Week` has no coach status to assert on
# (MissionState.kt:19-26: the Week carries missionId, weekStart, status, chips, outlineNotes and rows,
# and nothing derived from the coach). The screen's draft block draws the sentence for every DRAFT
# whatever the generation table holds (MissionScreen.kt:127-134), and `MissionStates.observe()` reads
# only the mission table (MissionState.kt:108-116). So the six names are what make this red now, and
# they stay red while the tab still says the sentence for a draft whose outline generation is open, or
# says it for a draft whose only row was cancelled, or needs a second subscription to
# MissionStates.observe() to find out.
#
# What is NOT checked, and why:
#   - No Compose test of the Mission tab's drawn line. The brief's own test line asks for a MissionState
#     test and the plan adds no screen test; the state tests below are the cheapest way to see the rule.
#     LaunchpadDraftStatusTest is named below because it pins the wording the tab now shares, not because
#     a Mission-tab screen test is wanted. If the owner wants the drawn line pinned, that file is the model.
#   - Whether the wording is shared by lifting the private consts out of LaunchpadScreen.kt or restated in
#     MissionScreen.kt. The plan allows either; the pull request names it if restated.
#   - That the field is called `coachStatus`, or that `Week` carries a `CoachStatus` rather than something
#     the tab maps it into. The plan's shape is `MissionTabState.Week` carrying the derived status; the
#     tests read it off the Week state whatever it is named.
#   - Whether `deriveCoachStatus` is reused or copied for the mission tab, and whether `observe()` is
#     restructured to a flatMapLatest/flowOf shape. Both are plan approaches, not requirements.
#   - The muted pointer the tab draws under a failed draft ("Retry or continue on the Launchpad."). It is
#     copy, it is a plan Decision the owner reviews in the pull request, and no state test can see it.
#   - That a confirmed week does no new reading (no settings read, no generation table read). Nothing
#     observable changes for an ACTIVE or CLOSED week, so there is nothing to assert; the existing cases
#     in MissionStatesTest and MissionTabStateTest are the check the plan names for them.
#   - Which file the new tests sit in. Names are matched across every report, so a new file is fine.
#
# Run it from anywhere; it finds the repository root. Invoke it as `bash verify.sh` — the executable bit
# is not preserved in git.

set -euo pipefail

if [ -f ".rho/runs/issue-60-mission-tab-says-not-confirmed-9c27/verify.sh" ]; then
  ROOT="$PWD"
elif [ -f "$0" ]; then
  ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
else
  echo "verify: could not find the repository root from $0" >&2
  exit 1
fi
cd "$ROOT"

# The gate is the CI's own two tasks, in the CI's order. On Windows Git Bash the committed gradlew.sh
# works as-is. On this Linux machine it hard-codes C:/Users/corpo toolchain paths that do not exist, so
# the same Gradle is invoked with the local toolchain instead — same version, same tasks, same flags.
# gradlew.sh is never edited: it is the Windows developer's script.
if [ -x "C:/Users/corpo/android-toolchain/gradle/bin/gradle" ]; then
  GRADLE_RUN=(bash gradlew.sh)
elif [ -x "$HOME/android-toolchain/gradle/bin/gradle" ]; then
  export JAVA_HOME="$HOME/android-toolchain/jdk"
  export ANDROID_HOME="$HOME/android-toolchain/sdk"
  GRADLE_RUN=("$HOME/android-toolchain/gradle/bin/gradle")
else
  echo "verify: no Gradle found — neither C:/Users/corpo/android-toolchain nor \$HOME/android-toolchain" >&2
  exit 1
fi

RESULTS="app/build/test-results/testDebugUnitTest"
WORK="app/build/verify-issue60"
INDEX="$WORK/index"

# The reports are read as evidence of THIS run, so the directory the gate task writes is moved aside
# first: results left by an earlier run, or restored from the build cache, would otherwise satisfy a name.
clear_results() {
  local stamp
  stamp="$(date +%s)-$ATTEMPT"
  mv "$RESULTS" "app/build/verify-old-results-$stamp" 2>/dev/null || true
  if [ -d "$RESULTS" ]; then return 1; fi
  return 0
}

# One record per test the class ran, tab-separated: "<folded name>\tPASS|FAIL\t<name as written>\t<class>\t
# SKIP-if-it-was-skipped". A testcase carrying a failure, error or skipped child counts as not passing.
# The name is read from the testcase tag, so a backtick name with spaces survives; the class comes from
# the report file's own name.
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

# Only these get another gate attempt:
# - the suite never finished (no reports at all), or the build died on a daemon, memory or executor
#   error: a hang is what app/build.gradle.kts's test-task timeout produces, and it writes no TEST-*.xml;
# - a run whose only failures are in the two Compose screen classes this repo has recorded expiring a
#   waitFor at random in a full-suite run on this machine — LiftoffShellTest (three consecutive attempts
#   on the untouched tree, all at its waitFor in the same run, and green on the attempt before and after)
#   and InFlightScreenTest (sortieNoteActionOnNoChecklistOpensDialogAndSaves, on an attempt where
#   LiftoffShellTest also flaked). Gradle's default 512m test heap against a ~780-test Robolectric+Compose
#   suite is the recorded cause; the recorded fix is the heap, in its own issue, not a change to a test.
#   Neither class asserts anything about a draft's status line, so a real breakage from this brief would
#   fail it on every attempt, not on one.
# A compile error, or any other reported assertion failure, is a real result and is not retried —
# including the six cases below, which are expected to be red until the fix exists.
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
  echo "  are the two tasks .github/workflows/ci.yml runs, in that order." >&2
  echo "--- last 60 lines of the build output ($GATE_LOG):" >&2
  tail -n 60 "$GATE_LOG" 2>/dev/null || true
  echo "verify: FAIL" >&2
  exit 1
fi
echo "--- gate passed on attempt $ATTEMPT"

# A test task that was up to date or restored from the cache wrote no reports for this tree.
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

# t <name> — the test ran, and passed. Names are compared with backticks, underscores and spaces removed
# and case folded, so a test may be written with backticks and spaces instead. A name that no report
# contains is a test that was never written, or never ran: Gradle exits 0 when nothing matches.
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

# require_class <fully.qualified.Class> — the class ran at all, and every test in it passed.
require_class() {
  local cls="$1" n bad
  n=$(awk -F'\t' -v c="$cls" '$4 == c' "$INDEX" | wc -l | tr -d '[:space:]')
  bad=$(awk -F'\t' -v c="$cls" '$4 == c && $2 == "FAIL"' "$INDEX" | wc -l | tr -d '[:space:]')
  if [ "$n" -gt 0 ] && [ "$bad" -eq 0 ]; then
    echo "  ok   $cls  ($n tests, all passed)"
  else
    echo "  FAIL $cls  ($n ran, $bad failed)"
    MISSING="$MISSING class $cls"
  fi
}


# ── the brief's two rules, as named tests ──────────────────────────────────────────────────────────
# These six names do not exist on the untouched tree, which is what makes this script red before the
# work is done. They are the definition of done; how each is set up is the work session's choice. The
# harness already in MissionStatesTest.kt (Robolectric + in-memory Room + the real MissionManager + a
# fixed clock + a fake SettingsStore) is the one that can see this, and LaunchpadCoachStatusTest.kt is
# the shape of the live cases: one subscription opened before the writes, read forward from there.

# The brief's case: a draft whose outline generation is open must not read as "Not confirmed yet".
# A DRAFT mission for the current week, the coach configured, and one open OUTLINE generation for that
# mission — the row MissionManager.confirm() writes (QUEUED, or RUNNING once the worker has started it).
# The Week state the Mission tab draws from must report the planning status the Launchpad's draft reports
# for the same row, and must not report the status the "Not confirmed yet" sentence is drawn from. The
# mission is still a DRAFT, and the status still offers no Confirm and no Retry while the coach works.
# Both open statuses are the same answer, so one test covers both.
t theMissionTabReportsPlanningWhileItsDraftHasAnOpenOutlineGeneration

# The offline variant of the same line: the draft's newest non-cancelled OUTLINE row is still QUEUED but
# carries Generation.COACH_OFFLINE in its reason — the mark GenerationWorker.giveUpSession leaves when the
# daemon is unreachable. The tab's status is the offline one, not planning and not the unconfirmed sentence.
t theMissionTabReportsOfflineWhenItsOpenOutlineRowCarriesTheCoachOfflineMark

# The failed variant: the draft's newest non-cancelled OUTLINE row is FAILED, with a reason that carries
# punctuation and inner spacing. The tab's status is the failed one and carries that row's reason word for
# word — Decision "What wording the Mission tab uses": the Mission tab has no view-model report to replace
# it with, so the row's own reason is the whole line. The status's own action flags are the Launchpad's
# (Confirm not offered, Retry and "Continue without outline" offered); Decision "The Mission tab reports
# the status; the actions stay on the Launchpad" is what the tab does with them — it gains no button, so
# the Week state's canReset stays false for a DRAFT.
t theMissionTabReportsFailedWithTheReasonItsOutlineRowCarriesAndOffersRetryAndContinue

# The brief's keep half, and Decision "Which drafts keep 'Not confirmed yet'". Three drafts, each answering
# with the status the sentence is drawn from rather than any of the three the brief names: a draft with the
# coach configured and no OUTLINE generation at all; a draft whose only OUTLINE row is CANCELLED — inserted
# already cancelled, the way AppOpenSweep leaves a row that 'Continue without outline' cancelled — which
# must not read as planning, offline or failed; and a draft with no row and no host or token, whose
# unconfigured status the tab still draws as the sentence rather than the Launchpad's setup message.
t theMissionTabKeepsTheUnconfirmedDraftStatusWhenNoOutlineGenerationIsOpen

# The tab follows its own row live. Open ONE subscription to MissionStates.observe() before anything is
# written and take the draft Week it answers first (no row yet: the unconfirmed status); then insert a
# QUEUED OUTLINE row for that draft and read the next Week out of the same collection — planning; then
# mark that row FAILED with a reason and read the next — failed, carrying that reason. The mission stays a
# DRAFT throughout. The point is the same collection: opening a second observe() per write would also
# "work" and is what the plan rules out.
t theMissionTabFollowsItsOwnOutlineRowFromQueuedToFailedWithoutReSubscribing

# The wake rule the plan names for the join. On one open subscription, with the draft's Week already seen,
# write a QUEUED OUTLINE row for a DIFFERENT mission, a FLIGHT_PLAN row for this draft, and a CANCELLED
# OUTLINE row for this draft: none of them can change the draft's answer, so the collector must emit no new
# Week for any of them. Then write a QUEUED OUTLINE row for this draft and require the planning Week —
# proof the collector was still open and the silence above was not a closed flow. This is the
# distinctUntilChanged the plan's Risks section names; without it the tab re-derives and re-draws on every
# generation write in the app.
t theMissionTabDoesNotReEmitForAnotherMissionsRowAFlightPlanRowOrACancelledRow

# ── nothing else broke ───────────────────────────────────────────────────────────────────────────────
# The Mission tab's own state class: the six names above plus the twelve cases that already exist in it,
# which the plan says keep passing unchanged — including the ACTIVE and CLOSED arms, which the plan leaves
# alone.
require_class com.liftoff.app.ui.mission.MissionStatesTest

# The tests that read `MissionTabState.Week` directly: MissionTabStateTest builds one by hand and asserts
# the canReset rule — a DRAFT week is never resettable, which is what keeps a failed draft from gaining a
# Reset week on the tab — and MissionViewModelResetTest waits for a Week out of the tab's own view model.
# Either breaks the moment Week's shape changes without its call sites being updated; the plan's Risks
# section names them for exactly that.
require_class com.liftoff.app.ui.mission.MissionTabStateTest
require_class com.liftoff.app.ui.mission.MissionViewModelResetTest

# The Reset dialog's own copy test, named because the plan's Decision keeps Reset on the Launchpad: the
# Mission tab gains no button, so nothing here should change.
require_class com.liftoff.app.ui.mission.ResetWeekDialogTest

# The domain rule the tab now reuses, unchanged: deriveCoachStatus's ranking of a generation row over the
# settings, a CANCELLED or SUCCEEDED row counting as no row, and a FAILED row's reason kept word for word.
# The plan adds no new domain rule, so nothing here should move.
require_class com.liftoff.app.domain.CoachStatusTest

# The Launchpad draft card keeps its own status line, its own wording and its own actions. The plan lifts
# the wording out of LaunchpadScreen.kt (or restates it) and joins the generation row into LaunchpadStates
# the same way; these are the tests that say the card itself did not change.
require_class com.liftoff.app.ui.launchpad.LaunchpadCoachStatusTest
require_class com.liftoff.app.ui.launchpad.LaunchpadDraftStatusTest
require_class com.liftoff.app.ui.launchpad.LaunchpadStatesTest
require_class com.liftoff.app.ui.launchpad.LaunchpadDraftControlsTest
require_class com.liftoff.app.ui.launchpad.LaunchpadDraftGuardsTest
require_class com.liftoff.app.ui.launchpad.LaunchpadConfirmTest

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
