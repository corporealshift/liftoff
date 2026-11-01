#!/usr/bin/env bash
# Verify: "CoachReplyValidator accepts an exercise name that normalizes to empty, then the store throws"
# (brief.md; issue corporealshift/liftoff#56, found reviewing #52 at 9454f0f).
# Definition of done for this run: exit 0 only when the brief is met.
#
# What is checked, in one line: a lift-plan reply naming an exercise with no letter and no digit in it — "!!!",
# "---", "   " — is rejected by CoachReplyValidator with its own
# `exercises[<position>].name: '<name>' has no letters or digits` line, so it goes down the same in-session repair
# path every other reply the phone cannot store goes down; a name whose letters survive the symbols around them is
# still accepted; and the store's own guard is untouched.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest, and the
# brief's last line names the same two tasks), then the four new tests, then the classes the plan says stay green
# unchanged.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this run's reports. A
# test that was never written cannot pass on its own. That is what makes this script fail on untouched code: all four
# names below are new to this branch, and today CoachReplyValidator.liftErrors reads "!!!" as a name, puts its
# normalized form straight into firstByName (CoachReplyValidator.kt:170) and returns no error for it — so such a
# reply validates clean, and the IllegalArgumentException only appears later, inside ExerciseDao.resolve, where
# nothing catches it.
#
# What is NOT checked here, and why:
# - The repair path itself. The brief's fix routes the new line into §7.3's existing two-repair path, and the plan's
#   Testing section says no new test is needed for it: GenerationWorkerTest already has
#   anInvalidReplyIsRepairedInTheSameSessionAndThenTheGenerationSucceeds and
#   afterTwoFailedRepairsTheGenerationFailsWithTheLastErrorsAndStoresNothing, and
#   CoachPromptBuilderTest.repairPromptCarriesEveryValidatorErrorAndAsksForOnlyTheCorrectedJson runs a real
#   CoachReplyValidator over an invalid reply and asserts every error line it produced reaches the repair prompt. Both
#   classes are checked below as whole classes; no new test is named for the path, and no test is named just to show
#   that a validator error list is a List<String>.
# - The store's guard. The plan's Decision "The DAO's guard stays" keeps ExerciseDao.resolve throwing for a blank or
#   symbols-only name, because FlightPlanDao.writePlan — the only main-source caller — is also reached from
#   MissionManager.refly on a plan that is already stored. ExerciseResolverTest.blankNameIsRejected already asserts
#   it; it is checked as a class below, not as a new test, and the plan says the fix is at the validator, not at the
#   store.
# - The name of the new fixture file, or which class each new test sits in. The brief asks for a fixture under
#   app/src/test/resources/fixtures/coach/ and the plan names symbols-only-exercise-name.txt; the fixture test below
#   only has to read a reply that is not one of the fourteen already in that directory, and
#   CoachReplyFixtureTest's own everyFixtureFileIsCoveredByATest (checked as a class below) fails a fixture that no
#   test reads. Where a test lives is the implementer's call, so names are looked up across every report.
# - How the guard is written, or exactly where in liftErrors it sits. Only its observable results are checked: the
#   two-names test fails if a rejected name is still put into the duplicate map, and the schema-violation fixture and
#   aTypeThatIsNeitherLiftNorRunIsReportedOnceByTheChecker — both in the classes below — fail if the guard reports a
#   name the schema checker already rejected, or reports it out of order with the other exercise lines.
# - The 0-based index and the raw quoting are pinned in the test comments because every other error line in this file
#   has them and the line is pasted into a repair prompt unchanged (§7.3 step 5). The alternative wording the plan
#   considered — the DAO's "Exercise name cannot be blank or symbols-only" — is a storage sentence the coach has no
#   part in; the plan chose the brief's wording as written, and the fixture test asserts the exact list.
# - ARCHITECTURE.md and DESIGN.md. The plan adds no main-source file and the coach row already names the validator
#   and the reply fixtures, so no doc row changes; invariant 8 ("the phone validates every coach reply, with up to 2
#   in-session repairs") already states what this fix restores. Doc wording is reviewed by Kyle in the pull request.
# - Whether ExerciseNames.normalize changes. It is not meant to; ExerciseNamesTest is checked below and already
#   asserts symbolsOnlyNameNormalizesToEmpty. A change there is the fix landing in the wrong place.
# - Which other tests in the suite break. The gate runs every test in the repo, so a break anywhere else already
#   fails the gate. The class names below only catch a named class that was renamed away, skipped or deleted.
# - Line endings, commit style, CLAUDE.md conventions, comment length, and the pull-request description.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

# Scratch lives under app/build, which is gitignored.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
rm -rf app/build/verify-old-results-* 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# index_reports: one awk pass over every report this run wrote. It writes $WORK/methods and $WORK/classes as
# "<name>\t<pass|fail|skipped>" lines, one per name, keeping the worst outcome across every report.
index_reports() {
    local file
    : > "$WORK/raw"
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        awk '
            function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
            function rec(n, s) { if (!(n in best) || rank(s) > best[n]) best[n] = rank(s) }
            /<testcase[ \t]/ {
                head = index($0, "<testcase")
                rest = substr($0, head)
                at = index(rest, " name=\"")
                if (at == 0) next
                tail = substr(rest, at + 7)
                q = index(tail, "\"")
                if (q == 0) next
                name = substr(tail, 1, q - 1)
                br = index(name, "[")
                if (br) name = substr(name, 1, br - 1)
                cn = ""
                at = index(rest, " classname=\"")
                if (at) {
                    tail = substr(rest, at + 12)
                    q = index(tail, "\"")
                    if (q) cn = substr(tail, 1, q - 1)
                }
                cur = name; pend = 1; st = "pass"
                if ($0 ~ /<(failure|error)[ \t\r\n>\/]/) st = "fail"
                else if ($0 ~ /<skipped/) st = "skipped"
                if ($0 ~ /\/>[ \t]*$/ || $0 ~ /<\/testcase>/) {
                    rec(cur, st); if (cn != "") rec("C:" cn, st); pend = 0; cur = ""
                }
                next
            }
            pend && /<(failure|error)[ \t\r\n>\/]/ { st = "fail" }
            pend && /<skipped/ { if (st == "pass") st = "skipped" }
            pend && /<\/testcase>/ { rec(cur, st); if (cn != "") rec("C:" cn, st); pend = 0; cur = "" }
            END { for (n in best) printf "%s\t%s\n", n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
        ' "$file" >> "$WORK/raw"
    done
    awk '
        BEGIN { FS = "\t" }
        function rank(s) { return (s == "fail") ? 3 : (s == "skipped") ? 2 : 1 }
        { if (!($1 in best) || rank($2) > best[$1]) best[$1] = rank($2) }
        END { for (n in best) printf "%s\t%s\n", n, (best[n] == 3 ? "fail" : best[n] == 2 ? "skipped" : "pass") }
    ' "$WORK/raw" > "$WORK/all"
    grep '^C:' "$WORK/all" | sed 's/^C://' > "$WORK/classes" || true
    grep -v '^C:' "$WORK/all" > "$WORK/methods" || true
}

# t <method>: the named test must have run in this invocation and passed. Names are matched across every report, so
# which file and package a test sits in is the implementer's call. What it must show is written in the comment above.
t() {
    local name="$1" st
    st="$(awk -F '\t' -v m="$name" '$1 == m { print $2; exit }' "$WORK/methods" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$name" ;;
        fail)    bad "$name ran but did not pass" ;;
        skipped) bad "$name was skipped" ;;
        none)    bad "$name did not run" ;;
    esac
}

# c <ClassName>: the class has to have run, and every test in it has to have passed.
c() {
    local cls="$1" st
    st="$(awk -F '\t' -v m="$cls" 'index($1, "." m) == length($1) - length(m) { print $2; exit }' "$WORK/classes" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "$cls ran and every test in it passed" ;;
        fail)    bad "$cls ran but something in it did not pass" ;;
        skipped) bad "$cls ran but a test in it was skipped" ;;
        none)    bad "$cls did not run" ;;
    esac
}

# ─── 1. CI gate: the two tasks ci.yml runs ─────────────────────────────────────
# Brief: "run the gate (bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest) before opening the PR". This is
# also the "must not break" check for the whole suite. Stale reports are moved aside first, so every named test below
# comes from this run. --no-build-cache stops the reports being restored from the cache.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

clear_results() {
    # Moved, not deleted: a stranded Gradle test executor can hold files under test-results. No gradlew --stop here:
    # a stop is machine-wide and would take down another worktree's daemon.
    local stamp
    stamp="$(date +%s)"
    mv "$RESULTS" "app/build/verify-old-results-$stamp" 2>/dev/null || true
    [ -d "$RESULTS" ] && return 1
    return 0
}

run_gate() {
    local n="$1"
    clear_results || { echo "could not clear $RESULTS" | tee "$WORK/gate-$n.log"; return 2; }
    mkdir -p app/build
    timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# Only these get another gate:
# - the hang the brief names ("The gate can hang once for about 5 minutes: run it detached and re-run it once if it
#   hangs"): a run that wrote no report at all, or a build task that failed with no test failing;
# - a build killed by a daemon, memory or executor error;
# - doneBodyweightSetStepperShowsBW alone, the one name this repo has recorded failing at random.
# A compile error, or any other reported assertion failure, is a real result and is not retried.
transient_failure() {
    local log="$WORK/gate-$1.log"
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|exceeded its configured timeout|Requesting stop of task|Test Executor|PersistentEnumerator storage corrupted|Unable to delete directory' "$log" 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$log" 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    local any other
    [ -f "$WORK/methods" ] || return 0
    any="$(awk -F '\t' '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk -F '\t' '$2 == "fail" && $1 != "doneBodyweightSetStepperShowsBW"' "$WORK/methods" | wc -l)"
    if [ "$any" -gt 0 ] && [ "$other" -eq 0 ]; then return 0; fi
    if [ "$any" -eq 0 ]; then return 0; fi
    return 1
}

gate_ok=0
gate_log=""
for attempt in 1 2 3; do
    gate_log="$WORK/gate-$attempt.log"
    if run_gate "$attempt"; then
        gate_ok=1
        echo "  … gate attempt $attempt passed"
        break
    fi
    index_reports
    if transient_failure "$attempt"; then
        echo "  … attempt $attempt failed on the hang or on the build itself; running the gate again"
    else
        echo "  … attempt $attempt failed on a reported test result; not running the gate again"
        break
    fi
done

if [ "$gate_ok" -eq 1 ]; then
    ok "the CI gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "the CI gate: the app does not assemble, the unit tests do not pass, or the suite did not finish within 30 minutes"
fi
if [ -n "$gate_log" ] && grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$gate_log" 2>/dev/null; then
    bad "unit tests executed in the gate run (the task came from the build cache or was up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
    : > "$WORK/methods"; : > "$WORK/classes"
else
    index_reports
    awk '
        /<testsuite / {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^tests=/) { split($i, a, "\""); tests += a[2] }
                if ($i ~ /^failures=/) { split($i, a, "\""); failures += a[2] }
                if ($i ~ /^errors=/) { split($i, a, "\""); errors += a[2] }
                if ($i ~ /^skipped=/) { split($i, a, "\""); skipped += a[2] }
            }
        }
        END { printf "  … reports: %d tests, %d failures, %d errors, %d skipped\n", tests, failures, errors, skipped }
    ' "$RESULTS"/TEST-*.xml
fi
# ─── 2. A name the phone cannot store is the validator's to reject ─────────────
# Brief: "A name such as "!!!" passes the schema (minLength: 1) and the duplicate check.
# ExerciseNames.normalize keeps only letters, digits, spaces and -, so it normalizes to "". ExerciseDao.resolve then
# throws IllegalArgumentException("Exercise name cannot be blank or symbols-only") inside the store transaction.
# Nothing catches it, so the generation never gets a repair prompt." And its Fix: "add a validator error when
# ExerciseNames.normalize(name) is empty, e.g. exercises[$position].name: '$name' has no letters or digits. That sends
# it down the normal in-session repair path."
#
# The lift-plan schema declares exercise name as { "type": "string", "minLength": 1, "maxLength": 60 }, so "!!!" is a
# perfectly well-formed string and only a semantic check can name it. Every other "the coach said something the phone
# cannot store" case in liftErrors — an unknown equipment id, a set with no target, a plan over the sortie budget — is
# an error line, so this one is too. The line is repair-prompt text the coach reads, so it names the path and quotes
# the value raw, exactly as the reply sent it, and the position is the same 0-based index every other exercise line
# uses.
echo ""
echo "=== the symbols-only exercise name ==="

# Brief "Test: add a fixture under app/src/test/resources/fixtures/coach/ and a validator test that expects that
# error." Must show: a new fixture — a full lift-plan reply, everything else valid, with one exercise whose name is
# the three characters !!! — read through CoachFixtures.errorsOf against CoachFixtures.LIFT_CONTEXT, gives exactly one
# error line and nothing else: exercises[<position>].name: '!!!' has no letters or digits, where <position> is that
# exercise's 0-based index in the fixture and the name is quoted raw as the reply sent it. A CoachReplyResult.Valid, a
# second line, the normalized (empty) name in the line instead of the raw one, or the store's own sentence all fail
# it. It is a file the test reads, so it has to be a new .txt in that directory and this class's
# everyFixtureFileIsCoveredByATest — run below as a class — has to know it is covered.
t symbolsOnlyExerciseNameFixtureIsRejectedWithItsOwnErrorLine

# Plan Decision "Two symbols-only names each get their own line, not a duplicate line". Must show: one lift-plan
# reply holding two exercises whose names hold no letter and no digit — for example "!!!" at one position and "???" at
# another — is Invalid with exactly two lines: each position's own exercises[<n>].name: '<name>' has no letters or
# digits line, in exercise order, and no exercises[<n>].name: '…' duplicates '…' at exercises[<m>] line pairing them.
# A value the validator has already rejected is named once, the way a type word the enum does not accept is not also
# compared with the pattern, and a duplicate line about two names that are not exercises at all tells the coach
# nothing about what to fix. Asserting the exact list is what catches the guard leaving a rejected name in
# firstByName, which is the plan's stated risk.
t twoSymbolsOnlyExerciseNamesAreEachRejectedAndNotReportedAsDuplicates

# Plan Decision "What counts as 'no letters or digits'", as changed by review: the check is that the normalized name
# holds no letter and no digit, not that it came back empty. Must show: a lift-plan exercise named "---" — hyphens
# only, which normalize keeps, so the normalized name is not empty — is Invalid with exactly the one line
# exercises[<position>].name: '---' has no letters or digits, and an exercise named with spaces only reads the same
# way. Under the plan's original "normalized name is empty" check the hyphens-only name validates clean and the store
# quietly creates an exercise row named ---, which nobody using the app would expect; the blank name is the case the
# brief's own title names, and the store throws on it today.
t aNameWithNoLettersOrDigitsIsRejected

# The other half of the same Decision, and the guard's boundary. Must show: a lift-plan reply whose exercise name is
# "!!! squat !!!" — symbols around letters, which normalize to "squat" — is CoachReplyResult.Valid, and the plan it
# parses into carries that exercise with the name as the coach sent it. A garbled name that normalizes to something
# real stays accepted because ExerciseDao.resolve creates a new exercise by design (§7.7) and there is no catalogue of
# known exercise names to validate against; a coach naming a new drill is a normal reply. This is the case that stops
# the guard drifting into "reject anything holding punctuation", so a test that only rejects is not enough.
t aNameWhoseLettersSurviveSurroundingSymbolsIsStillAccepted

# ─── 3. What the plan says stays green unchanged ───────────────────────────────
# The fix is one guard in liftErrors, so every other coach reply case has to read exactly as it does today.
# CoachReplyFixtureTest: the fourteen fixtures already in the directory and their exact error lists — including
# duplicateExerciseNamesFixtureGivesItsError, the nearest neighbour to the new one, and
# schemaViolationFixtureGivesTheCheckersErrors, which fails if the guard adds a line about a name the checker already
# rejected — plus everyFixtureFileIsCoveredByATest, which is why the new fixture, its CoachFixtures constant, its test
# and its COVERED entry have to land in one commit. CoachReplySemanticTest: the inline cases, including
# aTypeThatIsNeitherLiftNorRunIsReportedOnceByTheChecker and
# schemaAndSemanticViolationsInOneDocumentAreReportedInThatOrder, which fail if the new line is reported out of order
# with the other exercise lines or twice. CoachReplyExtractionTest: step 1, which this run does not touch.
echo ""
echo "=== the coach reply classes the fix must not change ==="

c CoachReplyFixtureTest
c CoachReplySemanticTest
c CoachReplyExtractionTest

# The normalize the guard reads, and the store's backstop the plan keeps. ExerciseNamesTest already asserts
# symbolsOnlyNameNormalizesToEmpty and unicodeLettersAndDigitsAreKept; ExerciseResolverTest already asserts
# blankNameIsRejected — resolve("!!!") and resolve("   ") throw and store nothing — and
# duplicateNormalizedNameIsRejected. Neither is rewritten here: a change to either is the fix landing in the wrong
# place, and the plan's Decision "The DAO's guard stays" is what those two classes hold.
echo ""
echo "=== ExerciseNames and the store's own guard ==="

c ExerciseNamesTest
c ExerciseResolverTest

# The path the new error line joins. The plan's Testing section says no new test is needed for it, so nothing new is
# named here; these two classes are the existing proof that a CoachReplyResult.Invalid reaches a repair prompt in the
# same session under the same generation row (§7.3's two repairs) and that CoachPromptBuilder.repairPrompt carries
# every validator error line it is handed, which is the whole reason a validator error line fixes what the brief
# reports.
echo ""
echo "=== the in-session repair path the new line joins ==="

c CoachPromptBuilderTest
c GenerationWorkerTest
# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
