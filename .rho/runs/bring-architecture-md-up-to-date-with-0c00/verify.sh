#!/usr/bin/env bash
# Verify: "Bring ARCHITECTURE.md up to date with what is built" (brief.md). Definition of done for this run:
# exit 0 only when the brief is met.
#
# The brief is documentation-only and its Done-when has two halves: ARCHITECTURE.md matches the code, and
# `bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes. So this script runs the gate CI runs, checks
# that the shipped behaviour each rewritten cell asserts is still green, and then checks the document.
#
# Why the document is checked here at all: this brief's deliverable *is* the document, and the plan writes no test
# ("No test is written or changed"), so there is no new test name to demand. A script that only ran the gate would
# pass on untouched code, which proves nothing. Every document check below rejects wording ARCHITECTURE.md carries
# today, or measures a row against the version this run started from. None demands particular new wording: a row
# reading "Yes (…)" instead of "✅ (…)", or a milestone cell reading "Built —" instead of "Done —", passes. Kyle
# reviews the new sentences in the pull request.
#
# What fails today, on untouched ARCHITECTURE.md:
#   the `work` row's 'Exists yet?' cell reads "Partly";
#   the `ui` row calls Mission Control a "stub", in its responsibility cell and in its ✅ cell;
#   the `data`, `settings`, `domain`, `work` and `ui` rows are byte-identical to the version this run started from,
#   so the claims the brief asks them to make have not been written;
#   the `work` row (1782 bytes) and the M3 row (3230 bytes) are the run-ons the brief says to cut;
#   M3 reads "In progress", M5 "Partial", M6 "Not started";
#   M2 says "In-Flight is a placeholder until M4" and "Regenerate comes with generation (M3)";
#   M3 ends "…come next".
# M1 and M4 already read as done, so those two checks pass today. The rest do not.
#
# The named test classes all exist today. Each cell the brief wants rewritten is a claim about shipped behaviour,
# and the claim is only worth writing into the document while the test behind it passes, so the script fails if one
# of them fails, is skipped, or did not run at all. The gate runs the whole suite anyway; these names catch a class
# this run renamed away, skipped or deleted, which a docs run has no reason to do. One name per thing the brief asks
# a row to say, in the cheapest kind of test that shows it.
#
# What is NOT checked here, and why:
# - Whether a rewritten cell is the *complete* truth about its package — that the `settings` row really names all
#   twelve §8 fields, that the `data` row's list is exhaustive, that the `work` row names the five files the brief
#   lists. That is a reading judgement over the diff, and it is Kyle's review. What is checked instead is the
#   falsifiable half: no row still carries the wording the brief says is wrong, the rows the brief calls out were
#   rewritten at all and cut down, and no row names a file the source tree does not have.
# - The `rho` and `coach` rows' content. The brief says `coach` stays "unchanged unless it's wrong" and map.md
#   checked that every claim in it holds; the `rho` row already names the three copied files, the rho commit hash
#   and DaemonConnectionCheck.kt, which is what is really in rho/. Demanding an edit to a row that is already right
#   would only manufacture churn. Both rows are still held to the checks that apply to every row.
# - M0's status. The brief asks M0 to say the coach template, schemas, validator and prompt builder exist and that
#   the live measurement has not been run; ARCHITECTURE.md already says that, and plan Decision 1 keeps M0 short of
#   Done because LiveCoachTest does not exist under app/src/test. M0 is therefore exempt from the "reads as done"
#   check, and LiveCoachTest is exempt from the file-resolution check. Nothing here pins Decision 1: whether M0's
#   cell reads Partial or Done is Kyle's call in the pull request.
# - The brief's hedge ("if the Launchpad's Pending status lines, Planned Regenerate or View in rho are not on this
#   branch, describe only what is there, and say in the PR which ones are missing"). map.md checked each against the
#   source and all three are on this branch, which is plan Decision 2; the Launchpad classes named below are what
#   prove it. Nothing here can tell whether the pull request says so.
# - The rest of the document — topology, resource locations, data flow, invariants. The brief says keep them unless
#   a line is plainly wrong, and the plan leaves them alone. The data-flow queueing line is checked as true in
#   map.md, so there is nothing for a check to catch there.
# - The commit message (`docs: …`) and staging ARCHITECTURE.md by name. Those are diff-review items. Line endings
#   are checked, because a CRLF ARCHITECTURE.md is a defect in the file rather than in the commit.
# - Which class each named test sits in. Names are looked up across the reports this invocation wrote.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

DOC="ARCHITECTURE.md"
RUN_DIR=".rho/runs/bring-architecture-md-up-to-date-with-0c00"
RESULTS="app/build/test-results/testDebugUnitTest"
# Scratch lives under app/build, which is gitignored. Logs are left there for whoever reads the run.
WORK="app/build/verify-work"
rm -rf "$WORK" 2>/dev/null || true
rm -rf app/build/verify-old-results-* 2>/dev/null || true
mkdir -p app/build "$WORK" || exit 1

PASS=0
FAIL=0
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); }

# c <ClassName>: the class has to have run in this invocation, and every test in it has to have passed. Gradle
# writes one report per class, so the report's own totals are the evidence that the named class ran at all.
c() {
    local cls="$1" cand nm t s fl er
    for cand in "$RESULTS"/TEST-*."$cls".xml; do
        [ -f "$cand" ] || continue
        nm="" t=0 s=0 fl=0 er=0
        read -r nm t s fl er <<<"$(awk '
            /<testsuite/ {
                for (i = 1; i <= NF; i++) {
                    if ($i ~ /^name=/)     { split($i, a, "\""); nm = a[2] }
                    if ($i ~ /^tests=/)    { split($i, a, "\""); t  = a[2] + 0 }
                    if ($i ~ /^skipped=/)  { split($i, a, "\""); s  = a[2] + 0 }
                    if ($i ~ /^failures=/) { split($i, a, "\""); fl = a[2] + 0 }
                    if ($i ~ /^errors=/)   { split($i, a, "\""); er = a[2] + 0 }
                }
            }
            END { printf "%s %d %d %d %d", (nm == "" ? "-" : nm), t, s, fl, er }' "$cand")"
        case "$nm" in "$cls"|*."$cls") break ;; esac
        nm=""
    done
    if [ -z "$nm" ]; then bad "$cls did not run (no report named TEST-*.$cls.xml)"; return; fi
    if [ "$t" -eq 0 ]; then bad "$cls ran but its report names no tests"; return; fi
    if [ "$fl" -gt 0 ] || [ "$er" -gt 0 ]; then
        bad "$cls ran but $fl failure(s) and $er error(s) in it"; return
    fi
    if [ "$s" -gt 0 ]; then bad "$cls ran but $s of its $t tests were skipped"; return; fi
    ok "$cls: all $t tests passed"
}

# ─── 1. CI gate: the two tasks CI runs ─────────────────────────────────────────
# Brief Done-when: "`bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest` passes", which is exactly the one
# build-and-test step in .github/workflows/ci.yml. It is also the brief's "Must not break: the build gate", over
# the whole suite. Stale reports are moved aside first so every class named below comes from this invocation;
# --no-build-cache stops the reports being restored from the cache.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

clear_results() {
    # Moved, not deleted: a stranded Gradle test executor can hold files under test-results. No `gradlew --stop`
    # either: a stop is machine-wide and would take down another worktree's daemon.
    local stamp
    stamp="$(date +%s)"
    if [ -d "$RESULTS" ]; then
        mv "$RESULTS" "app/build/verify-old-results-$stamp" 2>/dev/null || return 1
    fi
    return 0
}

run_gate() {
    local n="$1"
    clear_results || { echo "could not clear $RESULTS" >"$WORK/gate-$n.log"; return 2; }
    timeout 1800 bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest --no-build-cache 2>&1 \
        | tee "$WORK/gate-$n.log"
}

# The brief allows one re-run, for the hang it names: "The gate can hang once for about 5 minutes: run it detached
# and re-run it once if it hangs." That signature is a gate that wrote no report at all, or a build task that failed
# with no test failing. InFlightScreenTest alone is also retried: it is the one test this repo has recorded failing
# at random (a closed Room connection pool) and passing on a re-run of the identical tree. Any other reported test
# result is a real result and is not retried.
failing_suites() {
    awk '/<testsuite/ {
            n = "-"; f = 0; e = 0
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^name=/)     { split($i, a, "\""); n = a[2] }
                if ($i ~ /^failures=/) { split($i, a, "\""); f = a[2] + 0 }
                if ($i ~ /^errors=/)   { split($i, a, "\""); e = a[2] + 0 }
            }
            if (f + e > 0) print n
        }' "$RESULTS"/TEST-*.xml 2>/dev/null || true
}

transient_failure() {
    local suites
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then return 0; fi
    suites="$(failing_suites | sort -u | tr '\n' ',')"
    if [ -z "$suites" ]; then return 0; fi
    if [ "$suites" = "com.liftoff.app.ui.inflight.InFlightScreenTest," ]; then return 0; fi
    return 1
}

gate_ok=0
gate_log=""
for attempt in 1 2; do
    gate_log="$WORK/gate-$attempt.log"
    if run_gate "$attempt"; then
        gate_ok=1
        echo "  … gate attempt $attempt passed"
        break
    fi
    if transient_failure; then
        echo "  … attempt $attempt failed on the hang or on the build itself; running the gate once more"
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
else
    awk '
        /<testsuite/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^tests=/)    { split($i, a, "\""); tests    += a[2] }
                if ($i ~ /^failures=/) { split($i, a, "\""); failures += a[2] }
                if ($i ~ /^errors=/)   { split($i, a, "\""); errors   += a[2] }
                if ($i ~ /^skipped=/)  { split($i, a, "\""); skipped  += a[2] }
            }
        }
        END { printf "  … reports: %d tests, %d failures, %d errors, %d skipped\n", tests, failures, errors, skipped }
    ' "$RESULTS"/TEST-*.xml
fi

# ─── 2. The shipped behaviour each rewritten cell asserts ──────────────────────
# Each name below is an existing class exercising the shipped behaviour one of the brief's cells asserts. A cell
# that says "Mission Control is a real screen in ui/control", or that the Launchpad draws the Pending card's
# generation status, or that AppOpenSweep re-enqueues open Generation rows at app open, is a claim about code, and
# it is only worth writing into the document while the test behind it passes. One class per thing the brief asks a
# row to say. This run writes no test of its own (plan: "No test is written or changed"), so these are names it
# must not break.
echo ""
echo "=== the shipped behaviour each rewritten cell asserts ==="

# The `rho` row: Liftoff's own connection check, built on the copied client rather than on the client itself.
c DaemonConnectionCheckTest

# The `data` row, one class for each thing the brief adds to it: MissionManager, export/import, the Re-fly copy.
c MissionManagerTest
c BackupRoundTripTest
c MissionManagerReFlyTest

# The `settings` row: SettingsStore holding the app-level configuration the §8 list describes.
c SettingsStoreTest

# The `domain` row, one class for each thing the brief adds to it: sortie planning (including the simple "Run" plan
# written when the run-generation toggle is off), the draft coach status, the sortie generation status.
c SortiePlanningTest
c CoachStatusTest
c SortieGenerationStatusTest

# The `work` row, which the brief wants read ✅ with these five files named in short phrases: one class per file.
c CoachTurnRunnerTest
c GenerationWorkerTest
c GenerationEnqueuerTest
c GenerationCancellerTest
c AppOpenSweepTest

# The `ui` row's first claim: Mission Control is a real screen in ui/control, not a stub. This is the one name that
# has to be a screen test, because the claim being rewritten is that the screen exists and hosts its sections.
c MissionControlScreenTest

# The `ui` row's Launchpad half, which the brief hedges on: the Pending card's status lines and Retry, the Planned
# card's Regenerate beside Scrub, and View in rho. Plan Decision 2 writes only what is on this branch; these three
# are what show all of them are, so the pull request has nothing to report as missing.
c LaunchpadPendingStatusTest
c LaunchpadPlannedActionsTest
c LaunchpadViewInRhoTest

# M6, which the brief wants rewritten to "the run-generation toggle is honoured": a run sortie rendered in In-Flight
# and landed from there.
c InFlightRunTest
c RunLandingTest

# ─── 3. ARCHITECTURE.md: the two tables ───────────────────────────────────────
# Each check here rejects wording the document carries today, or measures a row against the version this run
# started from. None demands a particular new sentence. Rows are found by the package name in their first cell and
# milestones by their label cell, so a rewrite that reorders rows or rewords a title cell is still found.
echo ""
echo "=== ARCHITECTURE.md: the two tables ==="

if [ ! -f "$DOC" ]; then
    bad "$DOC is missing"
    echo ""
    echo "=== Result: $PASS passed, $FAIL failed ==="
    exit 1
fi

# The document as this run started it, for the "was it rewritten at all" and "was it cut down" checks. This run's
# own brief commit marks where it began.
BASE="$(git log --diff-filter=A --format='%H' -1 -- "$RUN_DIR/brief.md" 2>/dev/null || true)"
if [ -z "$BASE" ]; then
    bad "$RUN_DIR/brief.md was never committed, so this run's starting point cannot be found"
    BASE_DOC=""
else
    BASE_DOC="$WORK/arch-base.md"
    if ! git show "$BASE^:$DOC" >"$BASE_DOC" 2>/dev/null; then
        bad "$DOC is not in the tree this run started from"
        BASE_DOC=""
    fi
fi

# pkg_rows <file> <out>: "<package>\t<row>" for every package-layout row, the package being the com.liftoff.app…
# name in the row's first cell. That finds the root row as well as the sub-package rows, and survives a rewrite
# that adds text around the package name.
pkg_rows() {
    awk '
        /^\|/ {
            line = $0
            n = index(substr(line, 2), "|")
            if (n) first = substr(line, 2, n - 1); else first = substr(line, 2)
            if (match(first, /com\.liftoff\.app(\.[A-Za-z]+)?/))
                print substr(first, RSTART, RLENGTH) "\t" line
        }
    ' "$1" >"$2"
}

# pkg_row <index-file> <package>: that row, or nothing.
pkg_row() {
    awk -F '\t' -v p="$2" '$1 == p { print $2; exit }' "$1" || true
}

# mile_row <file> <label>: the milestone row whose first cell is that label. Markdown emphasis around the label
# (**M3**) is stripped, so a row that bolds its label is still found.
mile_row() {
    awk -F '|' -v p="$2" '
        /^\|/ {
            c = $2
            gsub(/^[ \t]+|[ \t]+$/, "", c)
            gsub(/[*`]/, "", c)
            gsub(/^[ \t]+|[ \t]+$/, "", c)
            if (c == p) { print; exit }
        }
    ' "$1" || true
}

# last_cell <row>: the final cell of a markdown table row — the 'Exists yet?' cell or the milestone Status cell.
last_cell() {
    [ -n "${1:-}" ] || { echo ""; return 0; }
    printf '%s\n' "$1" | awk -F '|' '{
        if (NF < 2) { print ""; exit }
        s = $(NF - 1); gsub(/^[ \t]+|[ \t]+$/, "", s); print s
    }'
}

# negated <text>: the text lowercased with "not a stub"-style denials removed, so a row that writes "Mission
# Control is a real screen, not a stub" is not read as still calling Mission Control a stub.
negated() {
    printf '%s\n' "$1" | awk '{
        $0 = tolower($0)
        gsub(/(not|no|never)[ \t]+(longer[ \t]+)?(a|an|the)?[ \t]*(stub|placeholder)/, " ", $0)
        print
    }'
}

# reject <label> <text> <regex>: bad while the text still uses the old wording, ok once it does not.
reject() {
    local label="$1" text="$2" re="$3" flat
    if [ -z "$text" ]; then bad "$label could not be found in $DOC"; return; fi
    flat="$(negated "$text")"
    if grep -qiE "$re" <<<"$flat"; then
        bad "$label still carries wording the brief says is gone: $re"
    else
        ok "$label no longer carries the stale wording"
    fi
}

# row_len <row>: its length in bytes.
row_len() { printf '%s' "$1" | wc -c | tr -d ' \t'; }

PKGS="com.liftoff.app com.liftoff.app.rho com.liftoff.app.data com.liftoff.app.settings \
com.liftoff.app.domain com.liftoff.app.coach com.liftoff.app.work com.liftoff.app.ui"
CUR_ROWS="$WORK/pkg-current"
BASE_ROWS="$WORK/pkg-base"
pkg_rows "$DOC" "$CUR_ROWS"
if [ -n "$BASE_DOC" ]; then pkg_rows "$BASE_DOC" "$BASE_ROWS"; else : >"$BASE_ROWS"; fi

# 1. All eight package rows are still there.
missing=0
for p in $PKGS; do
    if [ -z "$(pkg_row "$CUR_ROWS" "$p")" ]; then
        bad "package row \`$p\` is not in the package table"
        missing=$((missing + 1))
    fi
done
if [ "$missing" -eq 0 ]; then ok "all eight package rows are present"; fi

# 2. Every package row reads as existing. Brief: "Every row should read ✅". Today only `work` does not: its
# 'Exists yet?' cell opens "Partly". Only the not-built words are rejected; ✅, "Yes" and "Built" all pass.
for p in $PKGS; do
    reject "package row \`$p\` 'Exists yet?' cell" "$(last_cell "$(pkg_row "$CUR_ROWS" "$p")")" \
        'partly|partial|not started|not begun|not yet|in progress'
done

# 3. Nothing in the package table is a stub any more. Brief: Mission Control "is a real screen in ui/control … not
# a stub". Today the `ui` row calls it a stub twice.
for p in $PKGS; do
    reject "package row \`$p\`" "$(pkg_row "$CUR_ROWS" "$p")" 'stub'
done

# 4. The five rows the brief says are out of date have been rewritten at all. `rho` and `coach` are exempt: the
# `rho` row already names the three copied files, the rho commit hash and DaemonConnectionCheck.kt, and the brief
# says `coach` stays unless it is wrong. This catches only a row left untouched; it judges no wording.
if [ -n "$BASE_DOC" ]; then
    for p in com.liftoff.app.data com.liftoff.app.settings com.liftoff.app.domain com.liftoff.app.work \
        com.liftoff.app.ui; do
        cur="$(pkg_row "$CUR_ROWS" "$p")"
        old="$(pkg_row "$BASE_ROWS" "$p")"
        if [ -z "$cur" ] || [ -z "$old" ]; then
            bad "package row \`$p\` cannot be compared with the version this run started from"
        elif [ "$cur" = "$old" ]; then
            bad "package row \`$p\` is unchanged from the version this run started from, so what the brief asks it to say has not been written"
        else
            ok "package row \`$p\` has been rewritten"
        fi
    done
fi

# 5. The two run-on cells the brief says to cut: the `work` row ("cut the current long run-on description down to
# one or two sentences") and M3, the longest cell in the document. Measured against this run's own starting row, so
# the bar is "at least half off what the brief calls a run-on", never a word count.
if [ -n "$BASE_DOC" ]; then
    for spec in "package com.liftoff.app.work" "milestone M3"; do
        kind="${spec%% *}" label="${spec#* }"
        if [ "$kind" = "package" ]; then
            cur="$(pkg_row "$CUR_ROWS" "$label")"; old="$(pkg_row "$BASE_ROWS" "$label")"
        else
            cur="$(mile_row "$DOC" "$label")"; old="$(mile_row "$BASE_DOC" "$label")"
        fi
        if [ -z "$cur" ] || [ -z "$old" ]; then
            bad "$kind \`$label\` row cannot be compared with the version this run started from"
            continue
        fi
        curlen="$(row_len "$cur")"; oldlen="$(row_len "$old")"
        half=$((oldlen / 2))
        if [ "$curlen" -le "$half" ]; then
            ok "$kind \`$label\` row has been cut down ($curlen bytes against the $oldlen it started as)"
        else
            bad "$kind \`$label\` row has not been cut down enough to read as one or two sentences ($curlen bytes against the $oldlen it started as; it has to be $half or fewer)"
        fi
    done
fi

# 6. All seven milestone rows are still there.
missing=0
for m in M0 M1 M2 M3 M4 M5 M6; do
    if [ -z "$(mile_row "$DOC" "$m")" ]; then bad "milestone row $m is not in the milestone table"; missing=$((missing + 1)); fi
done
if [ "$missing" -eq 0 ]; then ok "all seven milestone rows are present"; fi

# 7. M1–M6 read as done. Brief: "M1–M6 are Done." Today M3 reads "In progress", M5 "Partial", M6 "Not started".
# M0 is exempt: the brief and plan Decision 1 keep it short of Done because LiveCoachTest has not been run.
for m in M1 M2 M3 M4 M5 M6; do
    reject "milestone $m status cell" "$(last_cell "$(mile_row "$DOC" "$m")")" \
        'not started|not begun|not yet|in progress|partial|partly'
done

# 8. No milestone cell calls anything a placeholder. Brief: "no … 'placeholder' left over. M2 must no longer say
# In-Flight is a placeholder." Today M2 says "In-Flight is a placeholder until M4".
for m in M0 M1 M2 M3 M4 M5 M6; do
    reject "milestone $m" "$(mile_row "$DOC" "$m")" 'placeholder'
done

# 9. No milestone cell names what comes next. Brief: "no 'comes next' … left over". Today M2 says "Regenerate comes
# with generation (M3)" and "In-Flight is a placeholder until M4", and M3 ends "…come next". Only forward references
# are rejected — a plain "comes with" that names no later milestone is not caught, because it is not a forward
# reference and Kyle reads the sentence anyway.
for m in M0 M1 M2 M3 M4 M5 M6; do
    reject "milestone $m" "$(mile_row "$DOC" "$m")" \
        'comes next|come next|coming next|next milestone|next up|until m[0-9]|comes? with generation'
done

# 10. The tables are still tables: every row has as many cells as its header, so a stray pipe or a wrapped line
# that silently breaks a row is caught. A reader of the document is the user here.
check_table_shape() {
    local header="$1" name="$2" counts header_count rows ragged
    counts="$(awk -v h="$header" '
        !intab && index($0, h) == 1 { intab = 1 }
        intab { if ($0 ~ /^\|/) print gsub(/\|/, "|"); else exit }
    ' "$DOC" || true)"
    if [ -z "$counts" ]; then
        bad "$name table not found in $DOC (header: $header)"
        return
    fi
    header_count="$(printf '%s\n' "$counts" | head -1)"
    rows="$(printf '%s\n' "$counts" | grep -c . || true)"
    ragged="$(printf '%s\n' "$counts" | awk -v h="$header_count" 'NR > 2 && $0 != h { print NR }' | tr '\n' ' ' || true)"
    if [ -z "$ragged" ]; then
        ok "$name table has $rows rows, every one with $header_count pipes like its header"
    else
        bad "$name table has a row with the wrong number of cells (row $ragged of the table does not have $header_count pipes like its header)"
    fi
}
check_table_shape '| Package | Responsibility | Exists yet? |' 'package layout'
check_table_shape '| Milestone | Title | Status |' 'milestone'

# 11. Every file the package table names resolves to a tracked path in this repo. This is the brief's "Check each
# claim against the source tree" made checkable: a cell naming a file that is not there is a claim the source
# contradicts. It can only reject a false claim, never a wording choice — a row that names fewer files simply has
# fewer to resolve. The milestone table is not scanned, because M0 names LiveCoachTest, which the document says has
# not been run and which does not exist.
TRACKED="$WORK/tracked"
git ls-files >"$TRACKED" || true
names="$(awk '
    /^\|/ {
        line = $0
        if (line !~ /com\.liftoff\.app/) next
        while (match(line, /`[^`]+`/)) {
            tok = substr(line, RSTART + 1, RLENGTH - 2)
            if (tok ~ /\.(kt|json|md)$/) print tok
            line = substr(line, RSTART + RLENGTH)
        }
    }
' "$DOC" | sort -u || true)"
unresolved=""
named=0
for tok in $names; do
    named=$((named + 1))
    if ! grep -qE "(^|/)$tok\$" "$TRACKED"; then unresolved="$unresolved $tok"; fi
done
if [ -z "$unresolved" ]; then
    ok "every file the package table names in backticks resolves to a tracked path ($named named)"
else
    bad "every file the package table names resolves to a tracked path (these do not:$unresolved)"
fi

# 12. Line endings. CLAUDE.md and .gitattributes pin this file to LF, and editing on Windows is what breaks it.
if grep -q $'\r' "$DOC"; then
    bad "$DOC has no CRLF line endings (a CR byte was found)"
else
    ok "$DOC has LF line endings"
fi

# ─── 4. The brief's scope: documentation only ──────────────────────────────────
# Brief: "Change no Kotlin, resource or build files"; plan: "ARCHITECTURE.md is the only file changed". Checked
# over everything this run has done since its own brief commit, committed or not, tracked or not.
echo ""
echo "=== scope: only the document changed ==="
if [ -z "$BASE" ]; then
    bad "this run's starting commit could not be found, so the scope of its changes cannot be checked"
else
    changed="$( { git diff --name-only "$BASE^"; git ls-files --others --exclude-standard; } | sort -u || true)"
    offscope="$(printf '%s\n' "$changed" | grep -vE '^ARCHITECTURE\.md$|^\.rho/' || true)"
    if [ -z "$offscope" ]; then
        ok "nothing outside ARCHITECTURE.md and the run folder changed since $BASE"
    else
        bad "nothing outside ARCHITECTURE.md and the run folder changed (these did: $(printf '%s' "$offscope" | tr '\n' ' '))"
    fi
fi

# ─── result ────────────────────────────────────────────────────────────────────
echo ""
echo "=== Result: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    echo "NOT DONE: $FAIL check(s) failed." >&2
    exit 1
fi
echo "BRIEF DONE."
