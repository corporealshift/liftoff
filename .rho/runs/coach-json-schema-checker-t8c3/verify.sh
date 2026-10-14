#!/usr/bin/env bash
# Verify: "Coach JSON Schema checker" (brief.md, DESIGN.md §7.5/§7.6).
# Definition of done for this run. Exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly
# :app:assembleDebug :app:testDebugUnitTest), then every named test read out of the JUnit XML.
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in
# this run's reports; a test that was not written cannot pass.
#
# Named tests are matched by method name across the reports for com.liftoff.app.coach, which is
# where the brief puts the checker. How the tests are grouped into classes is the implementer's
# call; the behaviour each named test must show is written above it.
#
# The only prose check is the ARCHITECTURE.md coach row, because the brief's done-when names it.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

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
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="
rm -rf "$RESULTS" 2>/dev/null || true
if [ -d "$RESULTS" ]; then
    echo "  ✗ could not clear $RESULTS — a stranded Gradle test executor is holding it; run: bash gradlew.sh --stop" >&2
    exit 1
fi
if bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest; then
    ok "gate passed (the app assembles and the whole unit-test suite ran)"
else
    bad "gate failed: the app does not assemble or the unit tests do not pass"
fi
if [ ! -d "$RESULTS" ]; then
    bad "unit tests ran (no reports under $RESULTS)"
fi

# ─── 2. the checker on each of the three schemas: a valid document passes ─────
# Each test takes one of the three real schemas — loaded from its resource file, not a
# hand-written copy — and a document that satisfies it (§7.5.1 outline, §7.5.2 lift plan,
# §7.5.3 run plan), and shows the checker returns an empty error list for it.
# The lift-plan document covers the optional shapes too: a set with seconds instead of reps,
# a weight, warmup/rest_seconds/notes absent or present.
echo ""
echo "=== valid documents, one per schema ==="
t validOutlineDocumentPasses
t validLiftPlanDocumentPasses
t validRunPlanDocumentPasses

# ─── 3. one violation of each keyword the three schema files use ──────────────
# Each test runs the checker on a real schema with one rule broken, and asserts the error list
# is exactly the line below — path first, then the exact problem, because a later brief sends
# these lines back verbatim in repair prompts (§7.6).
echo ""
echo "=== one violation per supported keyword ==="

# type — outline schema, document with "notes": 42 → exactly one error:
#   notes: expected a string, got a number
# Decision: a value of the wrong type gets the type error only, not its bounds as well.
t typeViolationNamesPathAndExpectedAndActualType

# const — lift-plan schema, document with "type": "run" → exactly one error:
#   type: must be 'lift'
t constViolationNamesTheRequiredValue

# enum — run-plan schema, document with "kind": "jog" → exactly one error:
#   kind: 'jog' is not one of easy, tempo, intervals, long, recovery
t enumViolationNamesTheValueAndTheAllowedValues

# Decision: a missing required property is reported on the object that lacks it, not on the
# missing child, which has no value at that path.
# required — outline schema, a document whose sorties[0] has index and type but no focus →
# exactly one error:
#   sorties[0]: missing required property 'focus'
t missingRequiredPropertyIsReportedOnItsObject

# additionalProperties — outline schema, a document whose sorties[0] has an extra "day": 1 →
# exactly one error (the brief's own example line):
#   sorties[0]: unexpected property 'day'
t unexpectedPropertyIsReportedOnItsObject

# minimum — lift-plan schema, document with "estimated_minutes": 5 → exactly one error:
#   estimated_minutes: must be at least 10
t numberBelowMinimumIsRejected

# maximum — lift-plan schema, document with "estimated_minutes": 200 → exactly one error:
#   estimated_minutes: must be at most 180
t numberAboveMaximumIsRejected

# Decision: singular when the limit is 1, plural otherwise.
# minLength — outline schema, a document whose sorties[0] has "focus": "" → exactly one error:
#   sorties[0].focus: must be at least 1 character
t stringBelowMinLengthIsRejected

# maxLength — outline schema, document with a 1001-character notes → exactly one error:
#   notes: must be at most 1000 characters
t stringAboveMaxLengthIsRejected

# minItems — lift-plan schema, document with 2 exercises → exactly one error:
#   exercises: must have at least 3 items
t arrayBelowMinItemsIsRejected

# maxItems — lift-plan schema, a document whose exercises[0] has 9 sets → exactly one error:
#   exercises[0].sets: must have at most 8 items
t arrayAboveMaxItemsIsRejected

# properties (nested) — lift-plan schema, a document whose exercises[0].sets[0] has "reps": 0 →
# exactly one error. The path is the whole way down, so the reader knows which set to fix:
#   exercises[0].sets[0].reps: must be at least 1
t nestedPropertyViolationNamesTheFullPath

# items — outline schema, document with "sorties": ["lift"] → exactly one error, naming the
# index of the offending item:
#   sorties[0]: expected an object, got a string
t arrayItemViolationNamesItsIndex

# ─── 4. every violation in one pass, in a stable order ────────────────────────
# Decision: every violation in the document is reported in one pass, not stopped at the first.
# Decision: the order is the order the document and schema put them in — a value's own keywords,
# then required in schema order, then unexpected properties in document order, then declared
# properties in schema order, then array items by index.
# Decision: an error about the document itself carries the path `$`.
# One lift-plan document that breaks several rules at once:
#   {"type":"run","title":"","estimated_minutes":5,
#    "exercises":[{"name":"Bench Press","equipment":["barbell"],"sets":[{"reps":0}]}],
#    "notes":"ok","extra":true}
# must give exactly this list, in this order:
#   $: unexpected property 'extra'
#   type: must be 'lift'
#   title: must be at least 1 character
#   estimated_minutes: must be at least 10
#   exercises: must have at least 3 items
#   exercises[0].sets[0].reps: must be at least 1
echo ""
echo "=== error order and stability ==="
t allViolationsAreReportedInDocumentOrder

# Brief: "Errors come out in a stable order, so the same input always gives the same list."
# The same broken document checked twice (each time from its own parse of the text) gives the
# same list, in the same order.
t sameInputGivesTheSameErrorList

# ─── 5. must not break ────────────────────────────────────────────────────────
# The three schema files and their existing loading test, unchanged.
echo ""
echo "=== must not break ==="
t outlineSchemaLoadsFromTestClasspath
t liftPlanSchemaLoadsFromTestClasspath
t runPlanSchemaLoadsFromTestClasspath
# The exercise-name normalizer the coach package already ships.
t equivalentSpellingsNormalizeToBenchPress
# ARCHITECTURE.md invariant 2: this test scans every .kt in com.liftoff.app.coach, so it covers
# the new files without a new test.
t coachPackageHasNoAndroidImports
# Everything else already built (Room, settings, domain, UI) is covered by the gate in §1.

# ─── 6. ARCHITECTURE.md names the checker in the coach row ────────────────────
# The brief's own done-when names this line of the map, so it is checked in the doc itself.
echo ""
echo "=== ARCHITECTURE.md coach row ==="
row="$(awk -F'|' '/com\.liftoff\.app\.coach/ { print; exit }' ARCHITECTURE.md || true)"
if [ -n "$row" ] && grep -qi 'checker' <<<"$row"; then
    ok "the com.liftoff.app.coach row lists the schema checker"
else
    bad "ARCHITECTURE.md's com.liftoff.app.coach row does not list the schema checker"
fi

# ─── result ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
