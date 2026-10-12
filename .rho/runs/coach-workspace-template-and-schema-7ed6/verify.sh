#!/usr/bin/env bash
# Verify: coach workspace template and schema files (brief.md, DESIGN.md §7.1 / §7.1.1 / §7.5).
# This is the definition of done. Exit 0 only when the brief is met.
#
# Order: the two coach docs, the three schema files checked against §7.5 verbatim,
# ARCHITECTURE.md's two rows, then the CI gate (.github/workflows/ci.yml runs exactly
# :app:assembleDebug :app:testDebugUnitTest) and the named tests read out of the JUnit XML.
# Gradle exits 0 when a test never existed, so every named test is looked for by name in the
# report — a test that was not written cannot pass on its own.
#
# Doc checks look for the FACT each bullet of the brief requires, not for a sentence: the
# wording is the author's, the coverage is not.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
README="coach/README.md"
COACH="coach/COACH.md"
SCHEMA_DIR="app/src/main/resources/schemas"

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# covers <file> <what the doc must state> <ERE, matched case-insensitively anywhere in the file>
covers() {
    local file="$1" what="$2" re="$3"
    if [ -f "$file" ] && grep -Eiq -- "$re" "$file"; then
        ok "$what"
    else
        bad "$what  [not found in $file]"
    fi
}

# doc <file>: the file must exist and hold real text.
doc() {
    local file="$1"
    if [ -s "$file" ]; then ok "$file exists and is not empty"; else bad "$file missing or empty"; fi
}

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
        diff <(printf '%s\n' "$want") <(printf '%s\n' "$have") | head -n 12 | sed 's/^/      /'
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

# ─── 1. coach/README.md — the one-time PC setup from §7.1 ────────────────────
echo "=== coach/README.md ==="
doc "$README"

# The owner is told which folder to copy out, and where it goes: its own path, not a subfolder
# of this repo.
covers "$README" "names the coach/ folder it is documenting" '(^|[^[:alnum:]_])coach/'
covers "$README" "says to run git init in the copied workspace" 'git[[:space:]]+init'
# §7.1: the workspace is its own git repository and never this one — the coach must not be able
# to edit the app's source.
covers "$README" "states the copy is its own repo and never part of this one" \
    'own( git)? repo|separate( git)? repo|independent repo|never part of|not part of this repo'
# Step 2 of §7.1 names nabu's config file.
covers "$README" "names ~/.nabu/config.json as the file to edit" '\.nabu/config\.json'

# Step 2's snippet, verbatim from §7.1 — read out of DESIGN.md rather than hardcoded here, so
# the README is held to the same text the design carries. A filled-in example alongside it is
# fine; the snippet itself must be present.
SNIPPET=$(tr -d '\r' < DESIGN.md | grep -oF '"modules": {"verify": {"commands": {"<workspace path>": ""}}}' | head -n 1)
if [ -z "$SNIPPET" ]; then
    bad "the §7.1 verify-gate snippet could not be read from DESIGN.md"
elif [ -f "$README" ] && grep -qF -- "$SNIPPET" "$README"; then
    ok "gives §7.1's verify-gate snippet verbatim"
else
    bad "gives §7.1's verify-gate snippet verbatim  [missing from $README]"
fi

# Step 3 of §7.1: what the owner writes down for Mission Control.
# Decision: "Does the README tell the owner to write down the coach workspace path as well as
# host, port and token?" — yes, because Mission Control has a Coach workspace path field that
# §7.3 sends to nabu.session.create, so a README listing three of the four fields leads the
# owner to a generation failure.
covers "$README" "records the daemon host for Mission Control" 'host'
covers "$README" "records the daemon port and its 8737 default" '8737'
covers "$README" "records the daemon token" 'token'
covers "$README" "records the coach workspace path" 'workspace.{0,25}path|path.{0,25}workspace'
# §7.1 step 3 is "reachable from the phone over Tailscale"; without it the owner has no way to
# make the host they wrote down actually reachable.
covers "$README" "says the daemon must be reachable from the phone over Tailscale" 'tailscale'

# ─── 2. coach/COACH.md — every point §7.1.1 says it MUST contain ─────────────
echo ""
echo "=== coach/COACH.md ==="
doc "$COACH"

# Role: it says what the coach is and that it plans the week and the individual sessions.
covers "$COACH" "states its role as the coach who plans weeks and sessions" \
    'you are the coach|coach for|role of the coach|your role|^#+[[:space:]]*role'
covers "$COACH" "says it plans the week and the individual sessions" \
    'plan[^.]{0,60}(week|sortie|session)|(week|sortie)[^.]{0,60}session'
# Never ask questions: there is nobody to answer, so it makes the call and states the assumption.
covers "$COACH" "tells the coach never to ask questions" \
    'never ask|do not ask|don.t ask|no questions|nobody to answer'
# The output contract: exactly one JSON object, no prose around it, no code fence.
covers "$COACH" "says the final message is exactly one JSON object" \
    'exactly one json|one json object|single json object'
covers "$COACH" "says no prose before or after it" 'no prose|without prose|no text before|prose'
covers "$COACH" "says no code fence" 'fence'
# The programming principles, which §7.1.1 says are the owner's to edit.
covers "$COACH" "carries the programming principles" 'principle'
# Reuse exercise names so history lines up; new names only for new movements.
covers "$COACH" "tells the coach to reuse exercise names" \
    'reuse[^.]{0,80}exercise|exercise[^.]{0,80}reuse|same movement'
# Memory and the liftoff-progress note for working observations.
covers "$COACH" "describes using memory for durable facts about the athlete" 'memory'
covers "$COACH" "names the liftoff-progress note" 'liftoff-progress'
# No writes outside notes and memory: no files, no commands.
covers "$COACH" "forbids writes outside notes and memory" \
    'no writes|only notes and memory|notes and memory|do not (create|write|edit|modify)|never (create|write|edit)|no files'

# ─── 3. the three schema files are §7.5 verbatim ─────────────────────────────
# "Copied verbatim" is the brief's own wording, and these files become the single source of
# truth for the later schema checker, so they are compared against the DESIGN.md fences
# byte for byte — including the odd inline spacing the design uses.
echo ""
echo "=== schema files vs DESIGN.md §7.5 ==="
verbatim "#### 7.5.1 Outline"          "$SCHEMA_DIR/outline.json"
verbatim "#### 7.5.2 Lift Flight Plan" "$SCHEMA_DIR/lift-plan.json"
verbatim "#### 7.5.3 Run Flight Plan"  "$SCHEMA_DIR/run-plan.json"

# ─── 4. ARCHITECTURE.md's two rows ───────────────────────────────────────────
echo ""
echo "=== ARCHITECTURE.md ==="

# The coach package row names the schema files as something that exists, not just as a job the
# package will one day do. The row already promises "JSON schemas", so what has to be new is a
# reference to the files themselves — their directory or their names.
coach_row=$(awk -F'|' '$2 ~ /com\.liftoff\.app\.coach/ { l = $0 } END { print l }' ARCHITECTURE.md 2>/dev/null || true)
if [ -z "$coach_row" ]; then
    bad "coach package row found in ARCHITECTURE.md"
elif grep -Eqi 'schemas/|outline|lift[ -]plan|run[ -]plan' <<<"$coach_row"; then
    ok "coach package row names the schema files as existing"
else
    bad "coach package row names the schema files as existing"
    echo "      row reads: $coach_row"
fi

# M0: the workspace template and the schemas exist; the validator, prompt builder and live
# measurement are still to come. So the status is no longer a flat "Not started", and the row
# says both what is there and what is not.
m0_row=$(awk -F'|' '$2 ~ /^[[:space:]]*M0[[:space:]]*$/ { l = $0 } END { print l }' ARCHITECTURE.md 2>/dev/null || true)
m0_status=$(awk -F'|' '$2 ~ /^[[:space:]]*M0[[:space:]]*$/ { s = $4 } END { gsub(/^[[:space:]]+|[[:space:]]+$/, "", s); print s }' ARCHITECTURE.md 2>/dev/null || true)
if [ -z "$m0_row" ]; then
    bad "M0 row found in ARCHITECTURE.md"
else
    m0_ok=1
    grep -Eqi '^[[:space:]]*(not started|—|-)[[:space:]]*$' <<<"$m0_status" && m0_ok=0
    grep -Eqi 'exist|done|added|complete|in place' <<<"$m0_row" || m0_ok=0
    grep -Eqi 'to come|later|remaining|pending|not yet|still' <<<"$m0_row" || m0_ok=0
    grep -Eqi 'validator' <<<"$m0_row" || m0_ok=0
    grep -Eqi 'prompt builder' <<<"$m0_row" || m0_ok=0
    grep -Eqi 'live' <<<"$m0_row" || m0_ok=0
    if [ "$m0_ok" = 1 ]; then
        ok "M0 row says the template and schemas exist and the rest is still to come"
    else
        bad "M0 row says the template and schemas exist and the rest is still to come"
        echo "      status reads: ${m0_status:-<empty>}"
    fi
fi

# CLAUDE.md convention: LF endings for the files this brief adds.
eol=$(git ls-files --eol -- "$README" "$COACH" "$SCHEMA_DIR" 2>/dev/null || true)
if [ -z "$eol" ]; then
    bad "the coach docs and schema files are tracked by git"
elif grep -Eq '^i/(crlf|mixed|lf-crlf)' <<<"$eol"; then
    bad "coach docs and schema files are stored with LF endings"
    echo "$eol" | sed 's/^/      /'
else
    ok "coach docs and schema files are stored with LF endings"
fi

# ─── 5. CI gate: the two tasks .github/workflows/ci.yml runs ─────────────────
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

# ─── 6. named tests ──────────────────────────────────────────────────────────
C=com.liftoff.app.coach.CoachSchemaFilesTest

echo ""
echo "=== the three schema files load from the unit-test classpath ==="
# Each test reads its own file off the test classpath — no filesystem path, no build-script
# change — parses it with kotlinx.serialization's Json, and shows it is a JSON Schema object:
# a top-level object whose "type" is "object" and whose "required" list is non-empty.
# A missing file must read as that file being missing, not as a null dereference.
check_test "$C" outlineSchemaLoadsFromTestClasspath
check_test "$C" liftPlanSchemaLoadsFromTestClasspath
check_test "$C" runPlanSchemaLoadsFromTestClasspath

echo ""
echo "=== must not break ==="
# The exercise-name normalizer the coach package already ships, unchanged.
check_test com.liftoff.app.coach.ExerciseNamesTest equivalentSpellingsNormalizeToBenchPress
# The coach package is still pure Kotlin: the new test file must not drag an Android import
# into com.liftoff.app.coach.
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
