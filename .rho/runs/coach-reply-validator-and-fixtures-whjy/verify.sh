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
FIXTURE_DIR="app/src/test/resources/fixtures"

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
# that the document was extracted and carried on. Every extraction failure is a single error at
# path `$`, and nothing downstream is reported for it.
echo ""
echo "=== extraction: trim, one fence, exactly one JSON object ==="

# Trim, then unwrap a single surrounding Markdown fence: a valid plan wrapped in ``` … ``` is a
# valid reply, and the fence's info string (```json) does not stop it being unwrapped.
t fencedReplyIsUnwrappedToTheJsonObject
t fenceWithAnInfoStringIsUnwrappedToTheJsonObject
# Decision: only the outermost fence pair is removed. A reply holding two separate fenced blocks is
# not unwrapped, and reads as `unexpected text after the JSON object`.
t twoSeparateFencedBlocksAreNotUnwrapped

# Prose before / after the one object is an error, each with its own line:
#   unexpected text before the JSON object / unexpected text after the JSON object
t proseBeforeTheJsonObjectIsAnError
t proseAfterTheJsonObjectIsAnError

# JSON that does not parse is reported as `not valid JSON: <reason>`, not as prose or as an
# unclosed object.
t malformedJsonIsReportedAsNotValidJson
# An object whose braces never close is `the JSON object is not closed`.
t unclosedJsonObjectIsReportedAsNotClosed
# An empty (or whitespace-only) reply is `the reply is empty`.
t emptyReplyIsAnError
# Decision: a reply that is valid JSON but not an object names what it was, from its first
# character: `expected a JSON object, got an array`.
t nonObjectReplyNamesWhatItActuallyWas

# The object scanner counts depth while respecting strings: a brace inside a string literal does
# not close the object, and neither does the character after an escaped quote.
t braceInsideAStringDoesNotEndTheObject
t escapedQuoteInsideAStringDoesNotEndTheObject

# ─── 3. §7.6 step 3: the semantic checks ──────────────────────────────────────
# Each test runs the validator over one document and asserts the exact `path: problem` line,
# because these lines are sent back verbatim in repair prompts (§7.3 step 5). The context is the
# generation kind, the Mission pattern, the sortie type being planned, the active equipment keys
# and the configured sortie length.
echo ""
echo "=== semantic checks ==="

# Outline: exactly one entry per pattern position. A size mismatch names the pattern and both
# counts, e.g. `sorties: expected 5 sorties for the pattern RLRLR, got 4`.
t outlineEntryCountNamesThePatternAndBothCounts
# An entry whose index is not its position: `sorties[3].index: must be 3`.
t outlineIndexMustMatchItsPosition
# An entry whose type contradicts the pattern at that position:
#   `sorties[2].type: must be 'run' at position 2 of the pattern RLRLR`.
t outlineTypeMustMatchThePatternAtItsPosition
# These two run over the entries that exist whether or not the count matched.
t outlineIndexAndTypeAreCheckedEvenWhenTheCountIsWrong

# Lift: each set has exactly one of reps or seconds.
#   `exercises[0].sets[1]: reps and seconds cannot both be set`
t setWithBothRepsAndSecondsIsRejected
# Decision: a set with neither is an error too — `set must have either reps or seconds`.
t setWithNeitherRepsNorSecondsIsRejected

# Every equipment id is in the active equipment list, one line per bad id, naming the id and its
# path — the brief's own example: `exercises[2].equipment: barbell is not in the equipment list`.
t equipmentIdNotInTheListNamesTheIdAndItsPath
# Decision: a key the context does not carry because it is deactivated gets the same line, not a
# separate "is deactivated" message.
t deactivatedEquipmentKeyIsRejectedAsNotInTheList

# estimated_minutes is at most 1.25 × the configured sortie length, and the line names the value,
# the limit and where the limit came from: `80 is more than 75 (1.25 × the 60-minute sortie
# length)`. This is the validator's own ceiling, separate from the schema's maximum.
t estimatedMinutesAboveTheSortieBudgetIsRejected

# Decision: names are compared normalized (ExerciseNames.normalize), and the error sits at the
# later index and quotes both spellings and both indexes:
#   `exercises[3].name: 'bench  press' duplicates 'Bench Press' at exercises[0]`
t duplicateExerciseNameNamesBothSpellingsAndBothIndexes

# The plan's type matches the sortie being planned. A run plan offered for a lift sortie:
#   `type: 'run' does not match the lift sortie being planned`
t planTypeNotMatchingTheSortieIsRejected

# Decision: a well-formed plan of the wrong kind gets that one error and nothing else — the schema
# pass is not run against it, so the coach is not sent five derivative errors (missing
# exercises, missing estimated_minutes, unexpected segments, …).
t wrongKindPlanReportsOnlyTheTypeError
# Decision: a type that is neither lift nor run (missing, "swim", not a string) is not short
# circuited; it goes through the schema pass and is named once, in the checker's wording.
t typeValueOutsideTheSchemaIsReportedOnceByTheChecker

# Decision: the schema pass and the semantic pass both run, and both are reported — schema errors
# first, then semantic — so one repair can fix two unrelated mistakes. A node whose type already
# failed the schema is not re-checked inside.
t schemaAndSemanticErrorsAreBothReportedWithSchemaFirst

# ─── 4. the fixtures: the validator run over recorded-style replies ────────────
# §13: "Validator: run against fixtures/". Each test takes one fixture file — the raw reply text
# as the coach would have sent it — runs it through the validator against a fixed context
# (pattern RLRLR, active keys barbell/dumbbell/bench/pullup-bar, 60-minute sortie), and asserts the
# result. A valid fixture asserts the parsed values field by field, not merely "no errors"; an
# invalid fixture asserts the exact list of lines, so an extra unexpected error fails it.
echo ""
echo "=== fixtures: valid replies parse into the expected values ==="
t outlineFixtureParsesIntoTheExpectedOutline
t liftPlanFixtureParsesIntoTheExpectedLiftPlan
t runPlanFixtureParsesIntoTheExpectedRunPlan
# A valid plan wrapped in one code fence parses to the same plan as the unwrapped one.
t fencedFixtureParsesIntoTheExpectedPlan

echo ""
echo "=== fixtures: each invalid reply gives its specific message ==="
# Bad JSON: one line starting `not valid JSON: ` with a non-empty reason, and nothing else.
t badJsonFixtureGivesOneNotValidJsonLine
t proseAroundJsonFixtureGivesItsOwnError
# A schema violation reports the checker's own paths and wording, unchanged.
t schemaViolationFixtureGivesTheCheckersErrors
t unknownEquipmentFixtureNamesTheIdAndItsPath
t inactiveEquipmentFixtureIsRejectedAsNotInTheEquipmentList
# An outline that does not match the pattern: the count line and the type line for the position
# where the pattern says run and the reply says lift.
t outlinePatternMismatchFixtureGivesItsErrors
t repsAndSecondsFixtureGivesItsError
t overLengthSortieFixtureGivesItsError
t duplicateExerciseNamesFixtureGivesItsError
t planTypeMismatchFixtureGivesItsError

# A fixture no test reads is invisible coverage, so the fixture set and the tests that read it are
# held together.
t everyFixtureFileIsCoveredByATest

# ─── 5. the fixtures are where the brief puts them ─────────────────────────────
# The brief lists 14 recorded-style replies under app/src/test/resources/fixtures/. The named
# fixture tests above only prove behaviour if the documents they name are there to read.
echo ""
echo "=== the fixture files the brief lists ==="
count=0
if [ -d "$FIXTURE_DIR" ]; then
    count="$(find "$FIXTURE_DIR" -type f | wc -l)"
fi
if [ "$count" -ge 14 ]; then
    ok "$FIXTURE_DIR holds the coach reply fixtures ($count files)"
else
    bad "$FIXTURE_DIR holds $count file(s); the brief lists 14 coach replies"
fi

# ─── 6. must not break ─────────────────────────────────────────────────────────
# The validator reuses the checker's error lines verbatim, so its wording is still the checker's.
echo ""
echo "=== must not break ==="
t constViolationNamesTheRequiredValue
t unexpectedPropertyIsReportedOnItsObject
# The three schema files still load off the test classpath — the validator reads the same files.
t outlineSchemaLoadsFromTestClasspath
# ARCHITECTURE invariant 2: this test scans every .kt in com.liftoff.app.coach, so it covers the
# new validator files without a new test.
t coachPackageHasNoAndroidImports
# Everything else already built (Room, settings, domain, UI) is covered by the gate in §1.

# ─── 7. ARCHITECTURE.md: the two rows the brief's done-when names ──────────────
# Doc rows, not code: the brief says the coach row lists the validator and fixtures, and M0 says
# the template, schemas and validator exist while the live reliability measurement has not run.
# Any wording that says those two things passes.
echo ""
echo "=== ARCHITECTURE.md ==="
row="$(awk -F'|' '/com\.liftoff\.app\.coach/ { print; exit }' ARCHITECTURE.md || true)"
if [ -z "$row" ]; then
    bad "ARCHITECTURE.md has no com.liftoff.app.coach row"
elif grep -qi 'validator' <<<"$row" && grep -qi 'fixture' <<<"$row"; then
    ok "the com.liftoff.app.coach row lists the validator and the fixtures"
else
    bad "ARCHITECTURE.md's com.liftoff.app.coach row does not list both the validator and the fixtures"
fi

# M0's status cell: the validator named as something that exists, and the live measurement named
# as something that has not happened. The cell is read clause by clause — split on ';' and the em
# dash — so a row that says the validator is "still to come" cannot count as saying it exists.
m0cell="$(awk '/^\| M0 \|/ { print; exit }' ARCHITECTURE.md | awk -F'|' '{ print $4 }' | tr -d '\r' | sed 's/—/;/g')"
if [ -z "$m0cell" ]; then
    bad "ARCHITECTURE.md has no M0 row"
else
    m0validator="$(awk '
        { n = split($0, parts, /;/) }
        END {
            for (i = 1; i <= n; i++) {
                c = parts[i]
                if (c ~ /[Vv]alidator/ && c ~ /exist|landed|present|built|shipped|in place|complete/) { found = 1 }
            }
            print found
        }' <<<"$m0cell")"
    m0live="$(awk '
        { n = split($0, parts, /;/) }
        END {
            for (i = 1; i <= n; i++) {
                c = parts[i]
                if (c ~ /[Ll]ive|[Rr]eliability|LiveCoachTest/ &&
                    c ~ /not run|not been run|not yet run|has not run|hasn|no measurement|not measured|to come|pending|outstanding|remain|still/) { found = 1 }
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
