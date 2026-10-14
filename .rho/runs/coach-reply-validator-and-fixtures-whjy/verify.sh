#!/usr/bin/env bash
# Verify: "Coach reply validator and fixtures" (brief.md, DESIGN.md §7.5/§7.6/§13).
# Definition of done for this run. Exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly
# :app:assembleDebug :app:testDebugUnitTest), then every named test read out of the JUnit XML.
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in
# this run's reports; a test that was not written cannot pass.
#
# Named tests are matched by method name across the reports under com.liftoff.app.coach, which is
# where the brief puts the validator. How the tests are grouped into classes is the implementer's
# call; the behaviour each named test must show is written above it.
#
# No UI tests: the brief says "No UI changes".
# The only prose checks are the two ARCHITECTURE.md rows, because the brief's done-when names them.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# status <method>: pass | fail | skipped | missing | none, for one test method across every
# com.liftoff.app.coach report. Self-closing testcases and name="method[params]" are handled.
status() {
    local method="$1" file out
    out=""
    for file in "$RESULTS"/TEST-com.liftoff.app.coach*.xml; do
        [ -f "$file" ] || continue
        out+="$(awk -v m="$method" '
            /<testcase[ \t]/ {
                inside = 0
                if ($0 ~ ("name=\"" m "\"") || $0 ~ ("name=\"" m "\\[")) {
                    found = 1
                    if ($0 !~ /\/>[ \t]*$/) inside = 1
                }
                next
            }
            inside && /<(failure|error)[ \t\r\n>\/]/ { bad = 1 }
            inside && /<skipped/ { skipped = 1 }
            inside && /<\/testcase>/ { inside = 0 }
            END {
                if (!found) print "missing"
                else if (bad) print "fail"
                else if (skipped) print "skipped"
                else print "pass"
            }
        ' "$file")"$'\n'
    done
    if grep -q '^fail$' <<<"$out"; then echo fail
    elif grep -q '^pass$' <<<"$out"; then echo pass
    elif grep -q '^skipped$' <<<"$out"; then echo skipped
    elif grep -q '^missing$' <<<"$out"; then echo missing
    else echo none
    fi
}

# t <method>: the named test must have run in this invocation and passed.
t() {
    local method="$1" st
    st="$(status "$method")"
    case "$st" in
        pass)    ok "$method" ;;
        fail)    bad "$method ran but did not pass" ;;
        skipped) bad "$method was skipped" ;;
        missing) bad "$method did not run" ;;
        none)    bad "$method did not run (no com.liftoff.app.coach test reports)" ;;
    esac
}

# ─── 1. CI gate: the two tasks .github/workflows/ci.yml runs ──────────────────
# Stale reports are removed first so every named test below comes from this run.
# --no-build-cache: without it the reports can be restored FROM-CACHE in seconds with no test
# executed, which would read as a suite that passed.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="
rm -rf "$RESULTS" 2>/dev/null || true
if [ -d "$RESULTS" ]; then
    echo "  ✗ could not clear $RESULTS — a stranded Gradle test executor is holding it; run: bash gradlew.sh --stop" >&2
    exit 1
fi
if bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 | tee "$WORK/gate.log"; then
    ok "gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "gate failed: the app does not assemble or the unit tests do not pass"
fi
if grep -Eq ':app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE)' "$WORK/gate.log"; then
    bad "unit tests executed (the task came from the build cache or was up to date)"
fi
if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
    bad "unit tests ran (no reports under $RESULTS)"
fi

# ─── 2. §7.6 step 1: raw text → exactly one JSON object ───────────────────────
# Each test feeds the validator raw reply text and asserts the one error line it must produce, or
# that the document was extracted and carried on.
echo ""
echo "=== extraction: trim, one fence, exactly one JSON object ==="

# Trim, then unwrap a single surrounding Markdown fence: a valid plan wrapped in ``` … ``` is a
# valid reply, and the fence's info string (```json) does not stop it being unwrapped.
t fencedReplyIsUnwrappedToTheJsonObject
t fenceWithAnInfoStringIsUnwrappedToTheJsonObject

# Prose before / after the one object is an error, each with its own line naming path `$`.
t proseBeforeTheJsonObjectIsAnError
t proseAfterTheJsonObjectIsAnError

# JSON that does not parse is reported as not valid JSON, not as prose.
t malformedJsonIsReportedAsNotValidJson
# An object whose braces never close (a truncated reply) is reported as not closed.
t unclosedJsonObjectIsReportedAsNotClosed
# An empty (or whitespace-only) reply is an error.
t emptyReplyIsAnError
# A reply that is valid JSON but not an object is an error. Decision (plan): the line names what
# it was, e.g. `expected a JSON object, got an array`.
t nonObjectReplyNamesWhatItActuallyWas

# A valid reply whose strings hold a brace or an escaped quote is still one valid object.
t braceInsideAStringDoesNotEndTheObject
t escapedQuoteInsideAStringDoesNotEndTheObject

# ─── 3. §7.6: the semantic checks ─────────────────────────────────────────────
# Each test runs the validator over one document and asserts the `path: problem` line, because
# these lines are sent back in repair prompts (§7.3 step 5).
echo ""
echo "=== semantic checks ==="

# Outline: exactly one entry per pattern position, indexes 0..n-1, each type matching the pattern.
t outlineEntryCountNamesThePatternAndBothCounts
t outlineIndexMustMatchItsPosition
t outlineTypeMustMatchThePatternAtItsPosition

# Lift: each set has exactly one of reps or seconds — both is an error.
t setWithBothRepsAndSecondsIsRejected
# Decision (plan): a set with neither is an error too.
t setWithNeitherRepsNorSecondsIsRejected

# Every equipment id is in the active equipment list, naming the id and its path — the brief's
# example: `exercises[2].equipment: barbell is not in the equipment list`.
t equipmentIdNotInTheListNamesTheIdAndItsPath

# estimated_minutes is at most 1.25 × the configured sortie length.
t estimatedMinutesAboveTheSortieBudgetIsRejected

# Exercise names are unique within the plan. Decision (plan): compared normalized, and the line
# quotes both spellings and both indexes.
t duplicateExerciseNameNamesBothSpellingsAndBothIndexes

# The plan's type matches the sortie being planned (a run plan offered for a lift sortie).
t planTypeNotMatchingTheSortieIsRejected

# ─── 4. the fixtures: the validator run over recorded-style replies ────────────
# §13: "Validator: run against fixtures/". Each test reads one fixture file under
# app/src/test/resources/fixtures/ — the raw reply text as the coach would have sent it — runs it
# through the validator against a fixed context, and asserts the result. A valid fixture asserts
# the parsed values, not merely "no errors"; an invalid fixture asserts its specific message with
# the path in it.
echo ""
echo "=== fixtures: valid replies parse into the expected values ==="
t outlineFixtureParsesIntoTheExpectedOutline
t liftPlanFixtureParsesIntoTheExpectedLiftPlan
t runPlanFixtureParsesIntoTheExpectedRunPlan
t fencedFixtureParsesIntoTheExpectedPlan

echo ""
echo "=== fixtures: each invalid reply gives its specific message ==="
t badJsonFixtureGivesOneNotValidJsonLine
t proseAroundJsonFixtureGivesItsOwnError
# A schema violation reports the existing checker's paths and wording.
t schemaViolationFixtureGivesTheCheckersErrors
t unknownEquipmentFixtureNamesTheIdAndItsPath
# Decision (plan): a deactivated key gets the same "not in the equipment list" line.
t inactiveEquipmentFixtureIsRejectedAsNotInTheEquipmentList
t outlinePatternMismatchFixtureGivesItsErrors
t repsAndSecondsFixtureGivesItsError
t overLengthSortieFixtureGivesItsError
t duplicateExerciseNamesFixtureGivesItsError
t planTypeMismatchFixtureGivesItsError

# ─── 5. must not break ─────────────────────────────────────────────────────────
# The whole existing suite runs in the gate; these name the checker and package-invariant tests
# the brief singles out.
echo ""
echo "=== must not break ==="
t constViolationNamesTheRequiredValue
t unexpectedPropertyIsReportedOnItsObject
t outlineSchemaLoadsFromTestClasspath
t coachPackageHasNoAndroidImports

# ─── 6. ARCHITECTURE.md: the two rows the brief's done-when names ──────────────
# Doc rows, not code: the coach row lists the validator and fixtures, and M0 says the template,
# schemas and validator exist while the live reliability measurement has not run.
echo ""
echo "=== ARCHITECTURE.md ==="
row="$(awk '/^\|[ \t]*`?com\.liftoff\.app\.coach`?[ \t]*\|/ { print; exit }' ARCHITECTURE.md || true)"
if [ -z "$row" ]; then
    bad "ARCHITECTURE.md has no com.liftoff.app.coach row"
elif grep -qi 'validator' <<<"$row" && grep -qi 'fixture' <<<"$row"; then
    ok "the com.liftoff.app.coach row lists the validator and the fixtures"
else
    bad "ARCHITECTURE.md's com.liftoff.app.coach row does not list both the validator and the fixtures"
fi

# M0's status cell, read clause by clause (split on ';' and the em dash), so a clause saying the
# validator is "still to come" cannot count as saying it exists.
m0cell="$(awk '/^\|[ \t]*M0[ \t]*\|/ { print; exit }' ARCHITECTURE.md | awk -F'|' '{ print $4 }' | tr -d '\r' | sed 's/—/;/g')"
if [ -z "$m0cell" ]; then
    bad "ARCHITECTURE.md has no M0 row"
else
    m0validator="$(awk '
        { n = split($0, parts, /;/) }
        END {
            for (i = 1; i <= n; i++) {
                c = parts[i]
                if (c ~ /[Vv]alidator/ && c !~ /to come|not yet|pending|outstanding/ &&
                    c ~ /exist|landed|present|built|shipped|in place|complete|done|added|written|implemented/) { found = 1 }
            }
            print found
        }' <<<"$m0cell")"
    m0live="$(awk '
        { n = split($0, parts, /;/) }
        END {
            for (i = 1; i <= n; i++) {
                c = parts[i]
                if (c ~ /[Ll]ive|[Rr]eliability|LiveCoachTest/ &&
                    c ~ /not run|not been|not yet|has not|hasn|have not|haven|no measurement|not measured|to come|yet to|pending|outstanding|remain|still/) { found = 1 }
            }
            print found
        }' <<<"$m0cell")"
    if [ "$m0validator" = 1 ] && [ "$m0live" = 1 ]; then
        ok "the M0 row says the validator exists and the live measurement has not been run"
    else
        bad "ARCHITECTURE.md's M0 row does not say both that the validator exists and that the live measurement has not been run"
        echo "      M0 status cell: $m0cell" | sed 's/^/      /'
    fi
fi

# ─── result ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
