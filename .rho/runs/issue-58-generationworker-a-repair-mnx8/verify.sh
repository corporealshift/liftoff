#!/usr/bin/env bash
# Definition of done for issue 58 (corporealshift/liftoff#58): a repair prompt sent to a coach session
# that already ended `completed` is refused by rho (INVALID_TRANSITION, a permanent RPC code), so the
# generation fails with the daemon's message instead of the validator's errors. The fix: a refused turn
# that ended `completed` runs its repair on a NEW session, with a prompt that carries the original
# prompt, the refused reply and the validator's errors; a turn that ended `idle` still repairs in the
# same session.
#
# Run from the repository root:  bash .rho/runs/issue-58-generationworker-a-repair-mnx8/verify.sh
#
# What it does:
#   1. the CI gate (.github/workflows/ci.yml): :app:assembleDebug :app:testDebugUnitTest;
#   2. reads the JUnit XML that gate wrote and requires each named test below to have run and passed.
#      A named test that is absent, skipped, failed or errored is a failure of this script.
#
# The tests are named here so the work is written to prove exactly these things. Each comment says what
# that test must show; give the test that name. The file the comment names is where the plan puts it.
#
# Why this fails on the untouched tree: the three new names below appear nowhere under app/src/test
# today, so the gate passes, every class check passes, and the script reports them as "did not run".
#
# What is NOT checked here, and why:
# - The cursor a fresh session is started with. `rho.session.send_prompt` carries no cursor; the cursor
#   goes into `rho.session.events_after`, and FakeDaemon keeps one shared log for every session, so it
#   answers a carried-over cursor happily instead of with CURSOR_UNKNOWN. The plan's Risks record this.
#   The first new test below asserts the read that follows the repair prompt asks for the new session's
#   events with no cursor carried from the refused turn, which is the part a test can actually see.
# - That the real daemon refuses a prompt on an ended session. FakeDaemon's `rho.session.send_prompt`
#   never refuses on session state, and `refuseMethod` refuses a whole method, not one call. The new
#   cases prove which session the worker sent the repair to, which is the half of the bug the fix moves;
#   before the fix there is one create frame and the repair names the session rho had already ended.
# - Whether the worker stops the ended session before opening the replacement. The plan's Decision is
#   that it does not (rho has already ended it, and `giveUpSession` swallows a refusal anyway); both
#   shapes look the same to the user. Kyle reviews that decision in the pull request.
# - A permanent refusal that lands during a fresh-session repair, and which session the worker's catch
#   gives up then. The plan's Risks say this is a one-line change with no new test beyond the existing
#   permanent-refusal case, which is named below.
# - The resume path: a row whose stored `nabuSessionId` completed while the worker was down. The copied
#   protocol has no call that reports a session's state and ARCHITECTURE.md invariant 3 forbids adding
#   one, so it cannot be pre-checked; the brief's fix is the repair path only. The plan names it for the
#   pull request, not for this script.
# - Any UI test. The brief names a GenerationWorkerTest case and the plan names no screen test; the
#   row's `nabuSessionId` is asserted in the worker test itself, which is where the Launchpad card's
#   link into rho and a user's cancel both read it from.
# - The DESIGN.md §7.3/§10 amendments and ARCHITECTURE.md invariant 8's wording. The plan calls them
#   part of the change, and they are reviewed in the pull request; a script that greps a document for a
#   sentence dictates the wording rather than checking behaviour.
# - Which class or file each new test sits in beyond the two the plan names, how the fake is made to
#   hand out a second session id, and where in GenerationWorker the session is chosen. The names below
#   are matched across every report this run wrote.
# - Line endings, commit style, CLAUDE.md's §2 vocabulary, comment length, and the pull-request text.

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
while [[ ! -f "$ROOT/settings.gradle.kts" && "$ROOT" != "/" ]]; do
    ROOT=$(dirname "$ROOT")
done
cd "$ROOT"

RESULTS=app/build/test-results/testDebugUnitTest
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
rm -rf app/build/verify-old-results-* 2>/dev/null || true

# ── the gradle the gate runs ─────────────────────────────────────────────────
# CI runs ./gradlew; the brief names bash gradlew.sh. The repo's committed gradlew.sh is the Windows
# variant (it pins C:/Users/corpo/android-toolchain), so it is used where it actually resolves — Git
# Bash on Windows. On this Linux box the same pinned toolchain under ~/android-toolchain is used
# directly, and the POSIX wrapper CI uses is the last resort. LIFTOFF_GATE_GRADLE overrides all of it.
if [[ -n "${LIFTOFF_GATE_GRADLE:-}" ]]; then
    read -r -a GRADLE <<< "$LIFTOFF_GATE_GRADLE"
elif bash gradlew.sh --version >/dev/null 2>&1; then
    GRADLE=(bash gradlew.sh)
elif [[ -x "$HOME/android-toolchain/gradle/bin/gradle" ]]; then
    export JAVA_HOME="${JAVA_HOME:-$HOME/android-toolchain/jdk}"
    export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-toolchain/sdk}"
    GRADLE=("$HOME/android-toolchain/gradle/bin/gradle")
elif [[ -x ./gradlew ]]; then
    GRADLE=(./gradlew)
else
    echo "verify: no gradle found (tried \$LIFTOFF_GATE_GRADLE, gradlew.sh, \$HOME/android-toolchain/gradle, ./gradlew)" >&2
    exit 1
fi

# ── 1. the gate: build the app and run the unit suite ────────────────────────
# What CI runs (:app:assembleDebug :app:testDebugUnitTest), and what the brief names as the gate to run
# before opening the pull request. It is also the check that nothing else moved: the Idle repair cases,
# the cancel-mid-turn repair case, the permanent-refusal case, the resume and supersede cases, the turn
# ends that hand the session back rather than fail the row, the store's own refusals, the DAOs, the
# validator, the prompt builder and its golden files, and every screen that reads a generation row.
#
# --no-build-cache and clearing the results directory first are what prove the suite ran now: with
# org.gradle.caching=true a deleted results directory is restored FROM-CACHE in about a second, and a
# green run would then be a cache hit rather than a run.
clear_results() {
    # Moved, not deleted: a stranded Gradle test executor can hold test-results/binary/output.bin, and
    # rm -rf on a directory it is holding fails. No gradle --stop here: a stop is machine-wide and would
    # take down the daemon of another run's worktree.
    local stamp
    stamp="$(date +%s)"
    mv "$RESULTS" "app/build/verify-old-results-$stamp" 2>/dev/null || true
    if [[ -d "$RESULTS" ]]; then return 1; fi
    return 0
}

# One line per test the class ran: "<folded name>\tPASS|FAIL\t<name as written>". A testcase carrying a
# failure, error or skipped child counts as not passing.
ran_tests() {
    LC_ALL=C awk -v RS='<testcase' '
        NR > 1 {
            name = ""
            if (match($0, /name="[^"]*"/)) name = substr($0, RSTART + 6, RLENGTH - 7)
            written = name
            sub(/\[.*$/, "", name)
            gsub(/[`_ ]/, "", name)
            bad = ($0 ~ /<(failure|error|skipped)[ >/]/)
            print tolower(name) "\t" (bad ? "FAIL" : "PASS") "\t" written
        }' "$1"
}

index_reports() {
    : > "$WORK/index"
    for f in "$RESULTS"/TEST-*.xml; do
        [[ -f "$f" ]] || continue
        cls=$(basename "$f" .xml); cls=${cls#TEST-}
        ran_tests "$f" | awk -v c="$cls" 'NF { print $0 "\t" c }' >> "$WORK/index"
    done
}

# Only these get another gate attempt:
# - the suite never finished (no reports at all), or the build died on a daemon, memory or executor
#   error: a hang is what app/build.gradle.kts's five-minute test-task timeout produces, and it writes
#   no TEST-*.xml;
# - a run whose only failures are in LiftoffShellTest, the class this repo has recorded expiring one
#   waitFor at random in a full-suite run and passing on a rerun of the identical tree (the plan's Risks
#   name it, and its 512m test heap is its own issue, not this one).
# A compile error, or any other reported assertion failure, is a real result and is not retried —
# including the new cases below, which are expected to be red until the fix exists.
transient_failure() {
    local log="$1"
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|exceeded its configured timeout|Requesting stop of task|Test Executor|PersistentEnumerator storage corrupted' "$log" 2>/dev/null; then
        return 0
    fi
    if [[ ! -e "$RESULTS" ]]; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$log" 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    # A build that ended with neither verdict was killed from outside (the timeout, a Ctrl-C, a daemon
    # that went away mid-run). Nothing it reported is a test result.
    if ! grep -q 'BUILD SUCCESSFUL\|BUILD FAILED' "$log" 2>/dev/null; then
        return 0
    fi
    local all broken
    all=$(awk -F'\t' '$2 == "FAIL"' "$WORK/index" | wc -l)
    # Nothing reported failing, yet the gate failed: the build died rather than a test.
    if [[ "$all" -eq 0 ]]; then return 0; fi
    broken=$(awk -F'\t' '$2 == "FAIL" && $4 != "com.liftoff.app.ui.LiftoffShellTest"' "$WORK/index" | wc -l)
    [[ "$broken" -eq 0 ]]
}

gate_ok=0
gate_log=""
for attempt in 1 2 3; do
    gate_log="$WORK/gate-$attempt.log"
    echo "==> attempt $attempt: ${GRADLE[*]} :app:assembleDebug :app:testDebugUnitTest --no-build-cache"
    if ! clear_results; then
        echo "verify: could not clear $RESULTS" >&2
        exit 1
    fi
    mkdir -p app/build
    if timeout 1800 "${GRADLE[@]}" :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 | tee "$gate_log"; then
        gate_ok=1
        break
    fi
    index_reports
    if transient_failure "$gate_log"; then
        echo "--- attempt $attempt failed on the build itself or on the recorded LiftoffShellTest flake; running the gate again"
    else
        echo "--- attempt $attempt failed on a reported test result; not running the gate again"
        break
    fi
done

if [[ "$gate_ok" -ne 1 ]]; then
    echo "verify: FAIL — the CI gate (:app:assembleDebug :app:testDebugUnitTest) did not pass." >&2
    exit 1
fi
# The log of the attempt that passed: with org.gradle.caching=true a deleted results directory is
# restored FROM-CACHE in about a second, and that is a cache hit rather than a run of the suite.
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE|NO-SOURCE)' "$gate_log" 2>/dev/null; then
    echo "verify: FAIL — the unit tests were not executed; :app:testDebugUnitTest came from the build cache or was up to date." >&2
    exit 1
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    echo "verify: FAIL — the gate wrote no unit-test results under $RESULTS." >&2
    exit 1
fi
index_reports
LC_ALL=C awk '
    /<testsuite / {
        for (i = 1; i <= NF; i++) {
            if ($i ~ /^tests=/)    { split($i, a, "\""); tests += a[2] }
            if ($i ~ /^failures=/) { split($i, a, "\""); failures += a[2] }
            if ($i ~ /^errors=/)   { split($i, a, "\""); errors += a[2] }
            if ($i ~ /^skipped=/)  { split($i, a, "\""); skipped += a[2] }
        }
    }
    END { printf "--- reports: %d tests, %d failures, %d errors, %d skipped\n", tests, failures, errors, skipped }
' "$RESULTS"/TEST-*.xml

# ── 2. the named tests ───────────────────────────────────────────────────────
# Names are compared with backticks, underscores and spaces removed and case folded, so a test named
# with backticks or underscores still counts. A name is matched across every report this run wrote, so
# which file it sits in is the implementer's call; what it must show is written above it.
normalize() { printf '%s' "$1" | LC_ALL=C tr -d '`_ ' | LC_ALL=C tr '[:upper:]' '[:lower:]'; }

MISSING=()
require_test() {
    local want line
    want=$(normalize "$1")
    line=$(awk -F'\t' -v want="$want" '$1 == want { print; exit }' "$WORK/index")
    if [[ -z "$line" ]]; then
        MISSING+=("$1 — did not run")
        echo "    MISSING  $1"
        return
    fi
    local status written cls
    status=$(printf '%s' "$line" | cut -f2)
    written=$(printf '%s' "$line" | cut -f3)
    cls=$(printf '%s' "$line" | cut -f4)
    if [[ "$status" == "PASS" ]]; then
        echo "    ok       $cls.$written"
    else
        MISSING+=("$1 — ran and did not pass")
        echo "    NOT PASS $cls.$written"
    fi
}

# ─── the repair on a turn that ended `completed` opens a new session ─────────
# Brief: "when the turn ended `Completed`, run the repair on a new session (null session id)" and
# "a GenerationWorkerTest case where the fake daemon ends the first turn `completed` with an invalid
# reply, asserting that a new session is created and the repair succeeds".
#
# What this test must show, on GenerationWorkerTest's scripted fake daemon and its runWorker() harness,
# with the first turn scripted to end State("completed") on an invalid reply (CoachFixtures.BAD_JSON)
# and the second to end with a valid reply:
# - TWO rho.session.create frames, with two different session ids. Today there is one, and the repair
#   prompt names the session rho had already ended — that is the assertion that is red before the fix.
#   (The fake hands out one id until a test hands it another, so the case has to make the second id
#   distinct; how it does that is its own business.)
# - The first prompt went to the first session, the repair prompt to the SECOND one, under the client_id
#   the attempt after the bump gives it (<generationId>-1), and the events_after read that follows it
#   asks for the new session's events with no cursor carried over from the refused turn.
# - The repair prompt is not the two-line "your reply was not valid" prompt the same-session repair
#   uses: it carries the original prompt's text, the refused reply, and every error line the validator
#   raised on that reply (§7.6 sends the error list verbatim).
# - The generation then ends the way a same-session repair does: Result.Success, attempt 1, row
#   SUCCEEDED with finishedAt, the outline stored (the mission ACTIVE with a current sortie), and the
#   row's nabuSessionId the SECOND session — the one that produced the accepted reply, which is what
#   the Launchpad card links into rho and what a user's cancel stops. (Decision: "Which session id the
#   generation row keeps when a repair succeeded on a new session".)
# - The session given up at the end of the store is that second session.
# - The second create carries the same workspace, labels and description as the first, so the
#   replacement conversation in rho's list still belongs to the sortie. (Decision: "Whether the
#   replacement session is created the same way as the first".)
#   app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt
require_test aRefusedTurnThatEndedCompletedRepairsOnANewSessionAndThenTheGenerationSucceeds

# ─── a `completed` turn still gets the whole repair budget ───────────────────
# The brief's title is "losing BOTH repairs", so the fix must not trade the RPC failure for fewer
# chances: every refused turn that ended `completed` opens its own session and gets the next round, up
# to the generation's existing cap of 2 repairs.
#
# What this test must show, with the fake ending each turn `completed` on an invalid reply:
# - one rho.session.create per turn, each with its own session id, and a prompt sent to each of them —
#   the same three prompts the existing idle case sees, and no fourth;
# - attempt 2 on the row, Result.Failure, and the row FAILED with "invalid output: " plus the LAST
#   turn's validator error lines joined, word for word — NOT the daemon's INVALID_TRANSITION text, not
#   "session has ended", and not the first refused reply's errors. (Decision: "What the generation
#   reports when the repair also fails": the RPC error is an artifact of the bug and tells the user
#   nothing about the reply.)
# - nothing stored: the mission still DRAFT with no sorties;
# - the session that produced the last refused reply is given up once.
#   app/src/test/java/com/liftoff/app/work/GenerationWorkerTest.kt
require_test aGenerationThatRunsOutOfRepairsOnCompletedTurnsFailsWithTheLastErrorsAndNotTheDaemonsRefusal

# ─── the prompt a fresh session is handed ────────────────────────────────────
# Brief: "Its prompt is the original prompt, the invalid reply and the validator errors." A session with
# no history cannot be pointed at "your reply was not valid" — it has no reply to look at — so the fresh
# session gets its own repair shape. The plan builds it in coach/CoachPromptBuilder beside repairPrompt
# (§7.4 puts prompts in coach/), and this is that shape's own test.
#
# What this test must show: the fresh-session repair prompt, given the original prompt text, the refused
# reply and the validator's error list, carries all three — the original prompt's text, the refused
# reply's text, and every error line verbatim and in order (§7.6 sends the error list back verbatim) —
# and asks for the corrected JSON object. Two calls with the same inputs give identical text. It is not
# the same-session repairPrompt: it is not the two-line prompt alone, and it is not the original prompt
# alone. It is a test of the shape itself — the text produced from given inputs — not a generation run
# through the worker; the worker test above is where the prompt actually sent is asserted.
#   app/src/test/java/com/liftoff/app/coach/CoachPromptBuilderTest.kt
require_test aFreshSessionRepairPromptCarriesTheOriginalPromptTheRefusedReplyAndEveryError

# ─── what the fix must not change ────────────────────────────────────────────
# Brief: "Keep same-session repair for `Idle`." These cases exist today and must keep passing with their
# exactly-one-create assertions intact: a repair on a turn that ended `idle` is a second turn on the
# session that opened, not a second session.
require_test anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds
require_test afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing

# The same-session repair prompt is unchanged: the validator's error lines and the ask for only the
# corrected JSON, with no original prompt and no refused reply in it.
require_test repairPromptCarriesEveryValidatorErrorAndAsksForOnlyTheCorrectedJson

# A refusal the daemon really does mean still ends the row with the daemon's own message and stops the
# session. The fix must not turn a permanent daemon refusal into "invalid output: …".
require_test aPermanentRefusalAtACallFailsTheGenerationWithTheDaemonsMessageAndStopsTheSession

# ── 3. the classes the change reaches through ────────────────────────────────
# The gate above ran the whole suite; these name the classes whose tests must all have run and passed,
# so one renamed away, skipped or deleted is caught and a failure is reported as the class it belongs to.
require_class() {
    local cls="$1" n bad
    n=$(awk -F'\t' -v c="$cls" '$4 == c' "$WORK/index" | wc -l)
    bad=$(awk -F'\t' -v c="$cls" '$4 == c && $2 == "FAIL"' "$WORK/index" | wc -l)
    if [[ "$n" -eq 0 ]]; then
        MISSING+=("$cls — did not run"); echo "    MISSING  $cls ran no tests"
    elif [[ "$bad" -ne 0 ]]; then
        MISSING+=("$cls — $bad of its $n tests did not pass"); echo "    NOT PASS $cls ($bad of $n failed)"
    else
        echo "    ok       $cls ($n tests, all passed)"
    fi
}

# The worker's own ends: the idle repair loop, the cancel-mid-turn repair loop, the resume and cursor
# cases, the blocked/error/paused/timed-out ends, the store's own refusals, the offline ladder and the
# supersede cases. The session a repair runs on is chosen inside this class's subject, so every one of
# these is a case the change can reach.
require_class com.liftoff.app.work.GenerationWorkerTest

# The turn the worker runs on whichever session it picks: create-vs-resume, the end it reports, the
# events_after read and its CURSOR_UNKNOWN recovery, and the rule that only events after the prompt
# count. A fresh session must not inherit the refused turn's cursor.
require_class com.liftoff.app.work.CoachTurnRunnerTest

# The prompt shapes: the new fresh-session repair beside repairPrompt and the eight §7.4 sections.
require_class com.liftoff.app.coach.CoachPromptBuilderTest

# The three §7.4 prompts byte for byte. The fresh-session repair reuses the original prompt's text, so
# the original must not have been edited to make it fit.
require_class com.liftoff.app.coach.CoachPromptGoldenFileTest

# The errors the repair prompt carries verbatim, and the reply the validator refused.
require_class com.liftoff.app.coach.CoachReplySemanticTest
require_class com.liftoff.app.coach.CoachReplyExtractionTest

# The row a repair writes to: nabuSessionId, lastEventId, attempt, and the FAILED write's reason.
require_class com.liftoff.app.data.GenerationDaoTest

# Where the original prompt text comes from, since a fresh-session repair has to carry it.
require_class com.liftoff.app.data.PromptInputLoaderTest

# The cancel that follows the row's nabuSessionId: after a repair moves the row to a new session, a
# user's cancel stops the session that is still live rather than the one rho already ended.
require_class com.liftoff.app.work.GenerationCancellerTest

# ── 4. nothing else broke ────────────────────────────────────────────────────
total=0
BROKEN=()
while IFS=$'\t' read -r name status written cls; do
    [[ -z "${name:-}" ]] && continue
    total=$((total + 1))
    [[ "$status" == "PASS" ]] || BROKEN+=("$cls.$written")
done < <(awk 'NF' "$WORK/index")

echo
if [[ "$total" -eq 0 ]]; then
    echo "verify: FAIL — no unit tests ran." >&2
    exit 1
fi
if [[ ${#BROKEN[@]} -ne 0 ]]; then
    echo "verify: FAIL — $total unit tests ran and these ${#BROKEN[@]} did not pass:" >&2
    for b in "${BROKEN[@]}"; do echo "  - $b" >&2; done
    exit 1
fi
if [[ ${#MISSING[@]} -ne 0 ]]; then
    echo "verify: FAIL — $total unit tests ran and passed, but these required tests are missing or did not pass:" >&2
    for m in "${MISSING[@]}"; do echo "  - $m" >&2; done
    exit 1
fi

echo "verify: $total unit tests ran and passed, including every test named above. Issue 58 done."
