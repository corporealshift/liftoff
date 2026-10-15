#!/usr/bin/env bash
# Verify: "Coach prompt builder with golden files" (brief.md; DESIGN.md §7.2/§7.4/§7.6/§7.7/§13).
# Definition of done for this run. Exit 0 only when the brief is met.
#
# Order: the CI gate first (.github/workflows/ci.yml runs exactly :app:assembleDebug
# :app:testDebugUnitTest), then every named test read out of the JUnit XML, then the two things the
# brief states outside the tests: the golden files' LF endings, and ARCHITECTURE.md's coach row.
#
# Gradle exits 0 when a named test never existed, so each test below is looked for by name in this
# run's reports; a test that was not written cannot pass.
#
# Named tests are matched by method name across every test report of this run, so how the tests are
# grouped into classes — and which package they sit in — is the implementer's call; the behaviour each
# named test must show is written above it. Only the two "must not break" tests repeat a name the repo
# already has; every other name is a test this run has to write.
#
# No UI tests: the brief says "No UI changes". The builder is pure Kotlin, so every behaviour is
# checked by calling it, not through a screen.
#
# The plan's "## Decisions" are not pinned here: which date a scrub is filed under, how sorties are
# numbered, set-grouping, whose notes appear, the run line, empty sections. The owner reviews those in
# the pull request. Where a named test below also shows a decision, the comment says which one.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# status <method>: pass | fail | skipped | missing | none, for one test method across every report of
# this run. Every report is read, so the new tests can sit in any package and any class; none of the
# method names below repeats a test the repo already has. Self-closing testcases and
# name="method[params]" are handled.
status() {
    local method="$1" file out
    out=""
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        out+="$(awk -v m="$method" '
            /<testcase[ \t]/ {
                inside = 0
                if ($0 ~ ("name=\"" m "\"") || $0 ~ ("name=\"" m "\\[")) {
                    found = 1
                    if ($0 ~ /<(failure|error)[ \t\r\n>\/]/) bad = 1
                    if ($0 ~ /<skipped/) skipped = 1
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
        none)    bad "$method did not run (no test reports under $RESULTS)" ;;
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

# ─── 2. §7.4: the three prompts, byte for byte against golden files ────────────
# Each test builds one prompt from fixed inputs and compares it with the expected text under
# app/src/test/resources/ as bytes, so a wrong word, a missing section, a section out of the §7.4
# order or a stray CR all fail. The golden files are what fix the eight sections, their wording and
# the §7.4 order for each generation kind.
echo ""
echo "=== golden files: outline, lift Flight Plan with an outline, run Flight Plan ==="

# The OUTLINE prompt: Task "Write the outline for this week", no sortie being planned, and the
# outline schema at the end.
t outlinePromptMatchesGoldenFile
# The FLIGHT_PLAN prompt for a lift sortie, with an outline stored: Task names the sortie, the week
# list says which sortie is being planned, history carries lift sets, and the lift-plan schema ends
# the prompt.
t liftFlightPlanPromptWithOutlineMatchesGoldenFile
# The FLIGHT_PLAN prompt for a run sortie, ending with the run-plan schema.
t runFlightPlanPromptMatchesGoldenFile

# §7.4: "the same inputs MUST produce the same text". Build one prompt twice and compare the bytes.
echo ""
echo "=== purity: the same inputs give byte-identical text ==="
t buildingTheSamePromptTwiceGivesIdenticalText

# §7.4 item 8: the Output schema section is the schema file for this kind, verbatim — the same bytes
# the validator will check the reply against, read from app/src/main/resources/schemas/.
t outputSchemaSectionIsTheResourceFileVerbatim

# ─── 3. §7.4 item 6: the history block ────────────────────────────────────────
# Each test builds a prompt whose history holds the one case named, and asserts the history block
# says it. Skipped and not-done sets must be recognisable, and a deviation must show what was
# planned, because §7.1.1 tells the coach to plan around both.
echo ""
echo "=== history formatting: deviation, skipped, not done, seconds, bodyweight, notes, scrub ==="

# A set done with different reps (or weight) from its plan reads as a deviation and names the
# planned value, e.g. `1×6 @135 lb (planned 8)`; a set done as planned carries no such marker.
t historyLineMarksASetThatDifferedFromItsPlan
# SetStatus.SKIPPED is recognisable as skipped.
t skippedSetsAreMarkedSkipped
# A set left open on a sortie scrubbed mid-flight is recognisable as not done, in a different word
# from skipped. Decision (plan 5): the two states get different words, because they are different rows.
t openSetsOnAScrubbedSortieReadNotDone
# A timed set is written in seconds, e.g. `3×45 s`, with the same planned-value rule as reps.
t timedSetsAreWrittenAsSeconds
# A reps set with no recorded weight says it was bodyweight, so the coach can progress it.
t aRepSetWithNoWeightIsMarkedBodyweight
# The owner's note on an exercise and the note on the sortie both appear in that sortie's block.
t exerciseAndSortieNotesAppearInTheirBlocks
# A scrubbed sortie is marked as scrubbed and carries the reason it was scrubbed.
t scrubbedSortieShowsItsReason

# ─── 4. §7.4 item 6: the window, the order, and the equipment list ────────────
# These are the loader's reads: the window is counted back from the injected date, the order is the
# one the prompts are built in, and the equipment list is what the coach may use ids from.
echo ""
echo "=== history window, newest-first order, active equipment only ==="

# The 28-day window's edge: a sortie dated exactly 28 days back is in the history, one dated 29
# days back is not.
t day28IsInsideTheWindowAndDay29IsOutside
# The window is configurable: an injected window other than 28 days changes which sorties appear.
t customWindowOverridesTheConfiguredHistoryWindow
# History is newest first, whatever order the rows came back in.
t historyIsNewestFirst
# An inactive equipment item is not in the prompt's equipment list; the active ones are.
t inactiveEquipmentIsLeftOutOfTheEquipmentList

# ─── 5. §7.4 item 7 / §7.7: exercises used before ─────────────────────────────
echo ""
echo "=== exercises used before ==="

# Every exercise name in the history, sorted alphabetically, listed once. Decision (plan 12): two
# spellings of one exercise (§7.7 identity is the normalized name) give one line.
t exercisesUsedBeforeAreSortedAndDeDuplicated

# ─── 6. §7.3 step 5 / §7.6: the repair prompt ─────────────────────────────────
# Built from the errors a real CoachReplyValidator run produced for a reply, not invented ones: every
# one of them appears in the repair prompt, and the prompt says to reply with only the corrected JSON
# object. How the errors are laid out — one per line, or joined into §7.3's sentence — is the plan's
# decision and is not pinned here.
echo ""
echo "=== repair prompt ==="
t repairPromptCarriesEveryValidatorErrorAndAsksForOnlyTheCorrectedJson

# ─── 7. gathering inputs: the loader over an in-memory database and settings ──
# Robolectric against Room.inMemoryDatabaseBuilder, with the date, the zone and the window injected.
# Each test asserts the input values the builder is then handed, so the prompt's content comes from
# the database rather than from a hand-written fixture.
echo ""
echo "=== loader: inputs built from Room and settings ==="

# The OUTLINE input: the profile (objectives, constraints, sortie length, units), the active
# equipment with its notes, and this week's pattern with each sortie's index, type and state.
t outlineInputReadsProfileEquipmentAndWeekFromTheDatabase
# The FLIGHT_PLAN input names which sortie of the week is being planned.
t flightPlanInputNamesTheSortieBeingPlanned
# The history block for a landed sortie carries what was actually done rather than what was planned,
# and the owner's exercise notes.
t historyCarriesActualSetsAndExerciseNotes

# A landed sortie is dated from landedAt, read in the injected zone — the date the window counts back
# from and the date the history block prints.
t landedSortieDateComesFromLandedAtInTheInjectedZone
# A scrubbed sortie is in the history even though a scrub records no timestamp, so it is dated by its
# week. Decision (plan 1): the Mission's weekStart, because that sortie is the one §7.1.1 says the
# coach must plan around.
t scrubbedSortieWithoutATimestampFallsBackToItsWeekStart


# ─── 8. must not break ────────────────────────────────────────────────────────
# The whole existing suite — schema checker, validator and fixtures, data layer, every screen — runs
# in the gate above. These two name the brief's own "must not" clauses.
echo ""
echo "=== must not break ==="

# "Don't change the Room schema": the exported Room schema JSON still matches the database.
t exportedV1SchemaMatchesDatabase
# ARCHITECTURE.md invariant 2: coach/ is pure Kotlin with no Android imports. This test scans every
# .kt in com.liftoff.app.coach, so it covers the new builder files without a new test.
t coachPackageHasNoAndroidImports

# ─── 9. the brief's "Keep LF endings" ─────────────────────────────────────────
# The golden comparison is byte for byte, so a CR in a golden file is a failing prompt. Every file
# under app/src/test/resources is text and must hold no CR byte, whatever the golden files are named
# or where they sit under that directory. Counted as bytes, because Git Bash's grep reads CRLF as a
# line ending and reports no match for it.
echo ""
echo "=== golden files: LF endings ==="
GOLDEN=0
GOLDEN_BAD=0
while IFS= read -r -d '' f; do
    GOLDEN=$((GOLDEN + 1))
    if [ "$(LC_ALL=C tr -d -c '\r' <"$f" | wc -c)" -gt 0 ]; then
        bad "$(basename "$f") under app/src/test/resources contains a CR byte"
        GOLDEN_BAD=$((GOLDEN_BAD + 1))
    fi
done < <(find app/src/test/resources -type f -print0 2>/dev/null)
if [ "$GOLDEN" -gt 0 ] && [ "$GOLDEN_BAD" -eq 0 ]; then
    ok "every file under app/src/test/resources is LF-only ($GOLDEN files)"
elif [ "$GOLDEN" -eq 0 ]; then
    bad "no files under app/src/test/resources to check the golden files' line endings against"
fi

# ─── 10. ARCHITECTURE.md's coach row, which the brief's done-when names ───────
# The row's "Exists yet?" cell, read clause by clause: the cell's em dashes, semicolons and commas
# count as clause breaks, so a clause that says the prompt builder is still to come cannot count as
# listing it, and a clause calling something else unfinished does not fail a row that lists the
# prompt builder as built.
echo ""
echo "=== ARCHITECTURE.md ==="
coachrow="$(grep -m1 'com\.liftoff\.app\.coach' ARCHITECTURE.md | tr -d '\r' || true)"
coachcell="$(awk -F'|' '{ print $4; exit }' <<<"$coachrow" | sed 's/—/;/g')"
if [ -z "$coachrow" ]; then
    bad "ARCHITECTURE.md has no com.liftoff.app.coach row"
elif [ -z "$coachcell" ]; then
    bad "ARCHITECTURE.md's com.liftoff.app.coach row has no status cell to read"
else
    listed="$(awk '
        { n = split($0, parts, /[;,]/) }
        END {
            for (i = 1; i <= n; i++) {
                c = tolower(parts[i])
                if (c ~ /prompt[ \t]*builder/ &&
                    c !~ /prompt[ \t]*builder.*(to[ \t]+come|yet[ \t]+to|to[ \t]+be[ \t]+(built|added|written|implemented|landed|done|finished|completed)|not[ \t]+(yet|been|started|built|written|implemented|landed|available|present|done|complete|run)|still[ \t]+(to|not|un|pending|missing)|remains?[ \t]+(to|unbuilt|unimplemented|pending)|no[ \t]+prompt[ \t]+builder|upcoming)/) { found = 1 }
            }
            print found + 0
        }' <<<"$coachcell")"
    if [ "$listed" = 1 ]; then
        ok "the com.liftoff.app.coach row lists the prompt builder"
    else
        bad "ARCHITECTURE.md's com.liftoff.app.coach row does not list the prompt builder as built"
        echo "      coach row, status cell: $coachcell"
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
