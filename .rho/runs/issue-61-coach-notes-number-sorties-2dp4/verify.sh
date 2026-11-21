#!/usr/bin/env bash
# Verify: issue #61 — Coach notes number sorties from 0, while the app numbers them from 1.
#
# What this checks:
#   1. the CI gate — `:app:assembleDebug` then `:app:testDebugUnitTest`, the two tasks
#      .github/workflows/ci.yml runs, in that order, with --no-build-cache so a cached test result can never
#      stand in for this tree's code. Every test the suite runs must pass;
#   2. three named tests for the numbering the coach is shown — each sortie in a prompt carries the display
#      number the app prints for it beside its 0-based index, every prompt tells the coach to name a sortie in
#      its notes by that number (or by its type and focus) and not by its index, and a Flight Plan's Task line
#      calls the sortie it is writing by the same number its own week list does;
#   3. the classes that hold the rest of the prompt contract, which this change must not disturb — the three
#      golden prompts compared byte for byte, the builder's other rules, the 0-based index on the coach's side
#      of the wire, and the loader and worker that feed and repair the prompt.
#
# Why it fails on the untouched tree: none of the three names exists yet. Today `CoachPromptBuilder.thisWeek()`
# writes `index 0 — run — landed` (CoachPromptBuilder.kt:101) and `task()` writes
# `Write the Flight Plan for sortie 1 of this week` (CoachPromptBuilder.kt:60) for the sortie the Mission tab
# calls 02 (ui/mission/MissionScreen.kt:139 prints `"%02d".format(row.index + 1)`), and no line in any prompt
# tells the coach how to number a sortie in its notes. The three files under `app/src/test/resources/prompts/`
# hold that same text, so every coach test in the repo is green on it today.
#
# How the golden files are proven updated without reading them: the named tests build prompts out of the builder
# and require the new numbering in them; `CoachPromptGoldenFileTest` compares the builder's bytes with the three
# files. Both green means the files carry the new text — a file left at the old text fails the byte comparison,
# a builder left at the old text fails the named tests. Nothing here opens a golden file or a source file.
#
# What is NOT checked, and why:
#   - No Compose test, and no test that reads the Mission tab's `01`–`05`. The app's numbering is not what this
#     issue changes: `MissionScreen`, `MissionState`, `LandedState` and `InFlightState` already print
#     `index + 1` for that sortie, and the brief's fix is in the text the coach is sent. The plan's Testing
#     section names no UI test, and ARCHITECTURE.md invariant 2 keeps `coach/` off the UI, so no single test can
#     read both numbers; the prompt tests assert `index + 1`, which is the rule those screens use.
#   - The wording of the numbering line, and where in the `This week` section it sits. The plan chose one line
#     as that section's last line; the sentence itself is Kyle's to review in the pull request.
#   - `coach/COACH.md`. Plan Decision "Where the instruction lives" leaves it as it is: `coach/` is a template
#     and the coach runs in a hand-made copy on the PC (coach/README.md), so a rule added only there never
#     reaches the coach writing the notes. No check reads that file, and a check that it stayed unchanged would
#     only pin the Decision.
#   - Notes already stored from earlier weeks. Plan Decision "Existing coach notes that already use 0-based
#     numbers" leaves them; rewriting prose the athlete has read is not this fix.
#   - Whether the coach actually complies. Nothing in the app reads the coach's prose, so these tests prove what
#     the coach is asked. `LiveCoachTest` (ARCHITECTURE.md M0) is not written yet and needs the local model
#     server, so it is out of scope.
#   - The Output schema's 0-based `index`, the repair path, the validator's error lines and the loader. The
#     brief says the schema keeps its 0-based index and the plan says all four stay green unchanged, so they are
#     checked as classes below and no new test is named for them.
#   - Line endings and the em dash separator in the golden files. The golden comparison is on UTF-8 bytes, so a
#     CRLF or a typed `-` where the builder writes `—` fails it as loudly as a wrong word would; a separate
#     check would only restate that.
#   - DESIGN.md's §7.4 marks and its amendment entry, commit style, and the pull-request text. Doc wording is
#     reviewed by Kyle; the plan lists it as part of the work.
#   - Which file each new test sits in. Names are matched across every report.
#
# Invoke it as `bash .rho/runs/issue-61-coach-notes-number-sorties-2dp4/verify.sh` from the repository root
# (or from anywhere; it finds the root).

set -euo pipefail

if [ -f ".rho/runs/issue-61-coach-notes-number-sorties-2dp4/verify.sh" ]; then
  ROOT="$PWD"
elif [ -f "$0" ]; then
  ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
else
  echo "verify: could not find the repository root from $0" >&2
  exit 1
fi
cd "$ROOT"

# The gate is the CI's own two tasks. The Windows developer's gradlew.sh is used where its toolchain exists;
# this Linux machine's toolchain otherwise; else the committed wrapper, which is what CI runs.
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
WORK="app/build/verify-issue61"
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

# Retried only: a build that died on the daemon/memory/executor or never wrote reports, or a run whose only
# failures are in the two Compose screen classes recorded flaking in full-suite runs on this machine. This
# issue touches prompt text only, so a failure confined to those classes is not this change's.
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

# t <name> — the test ran, and passed. Names compare with backticks, underscores and spaces removed and case
# folded. A name no report contains is a test never written or never run.
t() {
  local want state
  want=$(normalize "$1")
  state=$(awk -F'\t' -v w="$want" '
      $1 == w { if ($2 == "FAIL") { found = 2 } else if (found != 2) { found = 1 } }
      END { print found + 0 }' "$INDEX")
  case "$state" in
    1) echo "  ok   $1" ;;
    2) echo "  FAIL $1  (a report says it ran and did not pass)"; MISSING="$MISSING $1" ;;
    *) echo "  FAIL $1  (no report says it ran)"; MISSING="$MISSING $1" ;;
  esac
}

# c <ClassName> — the class ran, and every test in it passed. Catches a class that was renamed away, deleted or
# left half-updated; it says nothing about how the tests inside it are written.
c() {
  local cls="$1" n bad
  n=$(awk -F'\t' -v c=".$cls" 'index($4, c) == length($4) - length(c) + 1' "$INDEX" | wc -l | tr -d '[:space:]')
  bad=$(awk -F'\t' -v c=".$cls" 'index($4, c) == length($4) - length(c) + 1 && $2 == "FAIL"' "$INDEX" | wc -l | tr -d '[:space:]')
  if [ "$n" -eq 0 ]; then
    echo "  FAIL $cls  (no report for it — it did not run)"
    MISSING="$MISSING $cls"
  elif [ "$bad" -ne 0 ]; then
    echo "  FAIL $cls  ($bad of $n tests did not pass)"
    MISSING="$MISSING $cls"
  else
    echo "  ok   $cls  ($n tests, all passed)"
  fi
}

# ── the brief's rules, as named tests ────────────────────────────────────────────────────────────────────
# All three build a prompt with CoachPromptBuilder.prompt(...) and read the text back, the way
# CoachPromptBuilderTest's cases do today: a five-position week (indices 0–4, the golden inputs' RLRLR week)
# for an OUTLINE, for a FLIGHT_PLAN whose planned sortie is at index 1, and for one at index 2.

# Brief: "keep the schema's 0-based index, but give each sortie its display number in the prompt too, e.g.
# `sortie 1 (index 0) — run — pending`". Must show: in the `This week` section of each of those three prompts,
# the line for the sortie at index 0 names it 1, index 1 names it 2, and so on to index 4 naming it 5, and the
# line still carries that 0-based index beside the number. It fails on today's text, which prints only
# `index 0 — run — landed`; it also fails a line that drops the index — the coach would be given no number to
# echo back into the schema's `sorties[n].index`, which the brief says stays 0-based — and a number that is not
# that sortie's position + 1, which is the number the Mission tab, the sortie screen and the in-flight and
# landed screens print for that same sortie. The `planning:` line that names the sortie being planned carries
# the same two numbers for that same sortie: a prompt that calls a sortie 2 in its list and 1 in its planning
# line is the same disagreement the issue is about, inside one section.
# Plan Decisions "What the sortie line looks like in the prompt" (the brief's shape, both numbers, because the
# coach needs the number it writes and the number it replies with) and "How the display number is derived, and
# how it is written" (index + 1, plain, not the screens' zero-padded 02).
# One test with this name, the three prompts checked in it.
t promptNumbersEachSortieByItsDisplayNumberAndKeepsItsIndex

# Brief: "Add a line to the prompt (or coach/COACH.md) asking the coach to refer to sorties in notes by that
# number or by type and focus, never by index." Must show: each of the same three prompts carries, in its
# `This week` section, a line that tells the coach to name a sortie in its notes by the number printed beside
# it — or by its type and focus — and not by its index. The same line in all three kinds: a rule the coach is
# told in one prompt and not in another is a rule the coach does not have. The words are the implementer's,
# with two constraints the tests around it already impose: the line uses neither the substring `focus:` nor
# `planning:`, because the outline prompt's own assertions forbid either substring anywhere in that prompt, and
# it does not tell the coach to use the index. Fails if the line is missing, if it appears only in the Flight
# Plan prompts, or if it only restates the numbering without saying what the coach should write in its notes.
# Plan Decision "Where the instruction lives": the prompt, next to the sortie list, not coach/COACH.md.
# One test with this name, the three prompts checked in it.
t everyCoachPromptTellsTheCoachToNameSortiesByTheirNumberNotTheirIndex

# The line that names the session the coach is writing, which the brief's example does not show. Must show: in
# a Flight Plan prompt, the number the Task line gives for the sortie being planned is the number that same
# prompt's week list gives for that same sortie — 2 for the planned sortie at index 1, 3 for the one at index 2
# — with the 0-based index named alongside it. Today the Task line reads `Write the Flight Plan for sortie 1 of
# this week` for the sortie its own list calls 2 and the Mission tab calls 02, so the prompt disagrees with
# itself in the one line the coach acts on; a prompt that numbers the same sortie two ways is the bug the issue
# is about, in a place the brief's quoted note does not show.
# Plan Decision "The Flight Plan's Task line, which the brief's example does not show".
# One test with this name, the two planned positions checked in it.
t flightPlanTaskLineNamesTheSortieByTheNumberItsWeekLinePrints

# ── the rest of the prompt contract, which this change must not disturb ──────────────────────────────────
# The brief's own line is "Test: the golden files cover it". This is the class that compares the three built
# prompts with app/src/test/resources/prompts/ byte for byte — UTF-8 bytes, so a stray CR or a typed `-` in
# place of the builder's em dash fails it the way a wrong word does — and the two assertions that ride along in
# it: the Output schema section is the schema resource verbatim, which is what keeps the reply schema's 0-based
# `index` the brief says to keep, and building the same input twice gives identical bytes.
echo ""
echo "=== the three golden prompts ==="
c CoachPromptGoldenFileTest

# The builder's other rules, including the week-line and Task-line assertions the plan moves to the new text,
# the outline prompt's "no focus: and no planning:" assertions, the eight-sections-in-order test, and
# repairPrompt's §7.3 text. A builder changed without the golden files, or golden files changed without the
# builder, fails here as well as above.
echo ""
echo "=== the builder's other rules ==="
c CoachPromptBuilderTest

# The 0-based index on the coach's side of the wire: the schema files the prompt embeds, and the validator's
# `sorties[n].index: must be n` lines the coach is repaired against. The brief keeps them speaking in indices
# while the notes speak in display numbers; a change here would move the contract instead of annotating it.
echo ""
echo "=== the 0-based index the coach replies with ==="
c CoachSchemaFilesTest
c CoachReplySemanticTest
c CoachReplyFixtureTest

# The loader that hands the builder each sortie's index, type, state and focus, and the worker that carries
# every validator error line into the repair prompt. The plan's "unchanged and still green" list.
echo ""
echo "=== the loader and the worker around the prompt ==="
c PromptInputLoaderTest
c GenerationWorkerTest

# ── result ───────────────────────────────────────────────────────────────────────────────────────────────
echo ""
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

echo "verify: $TOTAL_TESTS unit tests ran and passed, including every test named above. Issue 61 done."
