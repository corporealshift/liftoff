#!/usr/bin/env bash
# Verify: coach workspace template and schema files (brief.md, DESIGN.md §7.5).
# This is the definition of done. Exit 0 only when the brief is met.
#
# Order: the three schema files checked against §7.5 verbatim (the brief's own done
# condition: the files are the design's text, byte for byte), then the CI gate
# (.github/workflows/ci.yml runs exactly :app:assembleDebug :app:testDebugUnitTest) and the
# named tests read out of the JUnit XML. Gradle exits 0 when a test never existed, so every
# named test is looked for by name in the report; a test that was not written cannot pass.
#
# The coach docs and the ARCHITECTURE.md rows are prose. The owner reviews them in the pull
# request, so this script does not grep them.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
SCHEMA_DIR="app/src/main/resources/schemas"

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# §7.5 code fence body: the lines of the ```json block under the given heading.
# DESIGN.md is normalised to LF first so the fence markers match whatever the checkout did.
fence() {
    local heading="$1"
    tr -d '\r' < DESIGN.md | awk -v h="$heading" '
        index($0, h) == 1 { want = 1 }
        want && $0 == "```json" { inblock = 1; next }
        inblock && $0 == "```" { exit }
        inblock { print }
    '
}

# verbatim <heading in DESIGN.md> <schema file>: the file is the §7.5 block, byte for byte.
verbatim() {
    local heading="$1" file="$2" want have
    want="$(fence "$heading")"
    if [ "$(printf '%s\n' "$want" | wc -l)" -lt 5 ]; then
        bad "$heading block could not be read from DESIGN.md"
        return
    fi
    if [ ! -f "$file" ]; then
        bad "$file missing, so it cannot match $heading"
        return
    fi
    have="$(tr -d '\r' < "$file")"
    if [ "$want" = "$have" ]; then
        ok "$file matches $heading verbatim"
    else
        bad "$file differs from $heading"
        diff <(printf '%s\n' "$want") <(printf '%s\n' "$have") | head -n 12 | sed 's/^/      /' || true
    fi
}

# check_test <fully.qualified.Class> <methodName>: that testcase ran and passed.
# Missing, skipped, failed or errored all count as failure. Attribute order and self-closing
# elements are both handled.
check_test() {
    local cls="$1" method="$2"
    local report="$RESULTS/TEST-$cls.xml"
    if [ ! -f "$report" ]; then
        bad "$cls.$method did not run (no report for $cls)"
        return
    fi
    local status
    status=$(awk -v m="$method" '
        BEGIN { RS = "<testcase[ \t\r\n]"; st = "missing" }
        NR > 1 {
            gt = index($0, ">")
            head = substr($0, 1, gt)
            if (head !~ ("(^|[ \t\r\n])name=\"" m "\"")) next
            if (substr(head, gt - 1, 1) == "/") body = ""
            else {
                e = index($0, "</testcase>")
                body = (e > 0) ? substr($0, gt + 1, e - gt - 1) : substr($0, gt + 1)
            }
            if (body ~ /<(failure|error)[ \t\r\n>\/]/) st = "failed"
            else if (body ~ /<skipped/) { if (st != "failed") st = "skipped" }
            else if (st == "missing") st = "passed"
        }
        END { print st }
    ' "$report")
    case "$status" in
        passed)  ok "$cls.$method" ;;
        missing) bad "$cls.$method did not run" ;;
        skipped) bad "$cls.$method was skipped" ;;
        *)       bad "$cls.$method ran but did not pass" ;;
    esac
}

# ─── 1. the three schema files are §7.5 verbatim ─────────────────────────────
# "Copied verbatim" is the brief's own wording, and these files become the single source of
# truth for the later schema checker, so they are compared against the DESIGN.md fences.
echo "=== schema files vs DESIGN.md §7.5 ==="
verbatim "#### 7.5.1 Outline"          "$SCHEMA_DIR/outline.json"
verbatim "#### 7.5.2 Lift Flight Plan" "$SCHEMA_DIR/lift-plan.json"
verbatim "#### 7.5.3 Run Flight Plan"  "$SCHEMA_DIR/run-plan.json"

# ─── 2. CI gate: the two tasks .github/workflows/ci.yml runs ─────────────────
# Stale reports are removed first so every named test below comes from this run.
echo ""
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (same as CI) ==="
rm -rf "$RESULTS" 2>/dev/null || true
if [ -d "$RESULTS" ]; then
    echo "  ✗ could not clear $RESULTS — a stranded Gradle daemon is holding it; run: bash gradlew.sh --stop" >&2
    exit 1
fi
if ! bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest; then
    bad "the build gate did not pass"
else
    ok "build gate passed"
fi
if [ ! -d "$RESULTS" ]; then
    bad "unit tests ran (no reports under $RESULTS)"
fi

# ─── 3. named tests ──────────────────────────────────────────────────────────
C=com.liftoff.app.coach.CoachSchemaFilesTest

echo ""
echo "=== the three schema files load from the unit-test classpath ==="
# Each test reads its own file off the test classpath, parses it with kotlinx.serialization's
# Json, and shows it is a JSON Schema object: a top-level object whose "type" is "object" and
# whose "required" list is non-empty.
# Decision: "One test that loops the three files, or three tests?": three, one per file.
check_test "$C" outlineSchemaLoadsFromTestClasspath
check_test "$C" liftPlanSchemaLoadsFromTestClasspath
check_test "$C" runPlanSchemaLoadsFromTestClasspath

echo ""
echo "=== must not break ==="
# The exercise-name normalizer the coach package already ships, unchanged.
check_test com.liftoff.app.coach.ExerciseNamesTest equivalentSpellingsNormalizeToBenchPress
# The coach package is still pure Kotlin.
check_test com.liftoff.app.coach.ExerciseNamesTest coachPackageHasNoAndroidImports
# Everything else already built (Room, settings, domain, UI) is covered by the gate above.

# ─── result ──────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
