#!/usr/bin/env bash
# .rho/runs/issue-62-pending-launchpad-card-puts-5t73/verify.sh
#
# Done means: the PENDING Launchpad card's title — the sortie's focus, uppercased, drawn by DisplayTitle at
# LiftoffType.screenTitle() (Type.kt: Big Shoulders W900, 68 sp, lineHeight 0.9 em, no letterSpacing) through
# TitleBlock, which today passes no maxLines, no overflow and no width cap — no longer runs down the card when the
# focus is long. A long focus keeps the card's layout the same as the PLANNED card's: the title is at most two lines
# tall, the status line stays near the top of the card, a short focus still renders one line at the full 68 sp, and
# the PLANNED card's title is untouched.
#
# Run from the repository root:   bash .rho/runs/issue-62-pending-launchpad-card-puts-5t73/verify.sh
#
# The gate is what CI runs (.github/workflows/ci.yml): :app:assembleDebug then :app:testDebugUnitTest.
# gradlew.sh at HEAD is Windows-only — it exports JAVA_HOME=C:/Users/corpo/... and execs
# C:/Users/corpo/android-toolchain/gradle/bin/gradle — so on a non-Windows box the gate runs the same Gradle from
# ~/android-toolchain with JAVA_HOME and ANDROID_HOME pointed at it, which is what the run's map records as the
# local equivalent. Both paths are the CI task list, nothing else.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1

RESULTS="app/build/test-results/testDebugUnitTest"
WORK="app/build/verify-issue62"
mkdir -p "$WORK"

FAILED=0
ok()  { echo "  OK   $*"; }
bad() { echo "  FAIL $*"; FAILED=$((FAILED + 1)); }

# ─── 0. The Gradle the gate runs ───────────────────────────────────────────────
# Windows (Git Bash/MSYS/Cygwin) runs the committed gradlew.sh, which is written for that box. Anywhere else it
# execs a Windows path that does not exist, so the same Gradle is run from the local toolchain the map names.
case "$(uname -s)" in
    Linux|Darwin)
        if [ -x "$HOME/android-toolchain/gradle/bin/gradle" ] && [ -d "$HOME/android-toolchain/jdk" ]; then
            export JAVA_HOME="$HOME/android-toolchain/jdk"
            export ANDROID_HOME="$HOME/android-toolchain/sdk"
            GRADLE=("$HOME/android-toolchain/gradle/bin/gradle")
        else
            GRADLE=(bash gradlew.sh)
        fi
        ;;
    *)
        GRADLE=(bash gradlew.sh)
        ;;
esac
echo "gate command: ${GRADLE[*]} :app:assembleDebug :app:testDebugUnitTest --no-build-cache"

# ─── 1. The CI gate ────────────────────────────────────────────────────────────
# The whole unit-test suite has to pass, not only the tests named below: the brief's fix is a change to a shared
# composable, and the plan's Testing section says TypographyTest (which pins ScreenTitle at 68 sp/900 with a 0.9 em
# line height, and LaunchLabel at 34 sp/900 with 0.16 em tracking) and the Launchpad screen tests that already exist
# pass untouched.
#
# Stale reports are moved aside first, so every named test below comes from this run. --no-build-cache stops the
# reports being restored from the build cache without any test running.
echo "=== gate: :app:assembleDebug :app:testDebugUnitTest (what CI runs) ==="

clear_results() {
    # Moved, not deleted: a stranded Gradle test executor holds test-results/binary/output.bin, and rm -rf on a
    # directory it is holding fails. No daemon stop here: a stop is machine-wide and would take down the daemon of
    # another run's worktree. If the move fails the gate is not run, and the caller retries.
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
    timeout 1800 "${GRADLE[@]}" :app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain 2>&1 \
        | tee "$WORK/gate-$n.log"
}

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

# Only these get another gate:
# - the known flake: com.liftoff.app.ui.LiftoffShellTest intermittently expires one or two waitFor calls in a
#   full-suite run on this machine (ComposeTimeoutException at LiftoffShellTest.kt:43), and passes on a re-run of
#   the identical tree. No test named below lives in that class, so retrying it cannot hide a failed case.
# - a build killed by a daemon, memory or executor error, or a build task that failed with no test failing;
# - the known hang: the build kills the test task at five minutes and writes no TEST-*.xml at all.
# A compile error, or any other reported assertion failure, is a real result and is not retried.
transient_failure() {
    local log="$WORK/gate-$1.log"
    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|LowMemory|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|exceeded its configured timeout|Requesting stop of task|Test Executor|PersistentEnumerator storage corrupted' "$log" 2>/dev/null; then
        return 0
    fi
    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$log" 2>/dev/null; then
            return 1
        fi
        return 0
    fi
    local any other
    any="$(awk -F '\t' '$2 == "fail"' "$WORK/methods" | wc -l)"
    other="$(awk -F '\t' '$2 == "fail" && $1 !~ /(^|\.)LiftoffShellTest$/' "$WORK/classes" | wc -l)"
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

# t <method>: the named test must have run in this invocation and passed. Gradle reports nothing for a test that
# was never written, so a name missing from this run's reports is a failure, and one that ran and did not pass is
# a failure too. Names are matched across every report, so which file and package a test sits in is the
# implementer's call; what it must show is written in the comment above it.
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
    local name="$1" st
    st="$(awk -F '\t' -v m="$name" '$1 == m { print $2; exit }' "$WORK/classes" 2>/dev/null || true)"
    case "${st:-none}" in
        pass)    ok "class $name ran and passed" ;;
        fail)    bad "class $name ran and did not pass" ;;
        skipped) bad "class $name was skipped" ;;
        none)    bad "class $name did not run" ;;
    esac
}

# ─── 2. What the cases have to measure, and what the harness gives them ────────
# Every case below is a Launchpad screen case, the way LaunchpadPendingStatusTest renders the LaunchpadContent
# body against in-memory Room and a real MissionManager: @Config(sdk = [34], qualifiers = "w411dp-h2000dp"), so
# the content Column is 411 dp wide and 2000 dp tall and the card's inner Column is 371 dp wide. The long focus is
# put on the sortie row itself (SortieDao.update on the row continueWithoutOutline created), the way
# LaunchpadStatesTest and LaunchpadRegenerateTest write a sortie row.
#
# The measurement is the title node's boundsInRoot height and the status line node's boundsInRoot top, in dp.
#
# Robolectric's default graphics mode on this project measures text as one unit per character and every line as a
# fixed 35 dp, whatever the font size — a 41-character focus measures 41 dp wide and 35 dp tall, so no line count
# can be read out of it and a five-line title looks identical to a one-line title. The cases that must show a line
# count therefore need real text metrics (Robolectric's native graphics mode). The short-focus cases below pin one
# line at 68 sp at 81 dp, which the default mode cannot produce; a case that measures 35 dp is not measuring the
# card and does not prove anything.
#
# Measured on the untouched tree, in real text metrics, with the focus the issue reports written on the sortie row
# and a QUEUED generation row so the status line reads "Coach is planning…". Density is 1.0 in this harness, so the
# boundsInRoot pixels below are already dp. screenTitle sets no letter spacing, so these are the font's own widths.
#   eyebrow "WEEK OF JAN 12 · SORTIE 1 OF 3 · LIFT"     top 0,   height 14 dp
#   title   "LOWER STRENGTH – SQUAT & HINGE, PLUS CORE" top 18,  height 329 dp  (five lines, 371 dp wide)
#   pattern track                                       top 363, height 52 dp
#   status  "Coach is planning…"                        top 435
# With a short focus ("Bench Press" → "BENCH PRESS"): title top 18, height 81 dp (one line, 334 dp wide); status
# top 187. With an 112-character focus: title height 701 dp (eleven lines); status top 807.
#
# The same title in the card's own 371 dp column and the card's own style, capped by hand at n lines:
#   68 sp — 1 line 81 dp, 2 lines 143 dp, 3 lines 205 dp, 4 lines 267 dp (a line costs 62 dp)
#   44 sp — 2 lines 92 dp, 3 lines 132 dp (a line costs 40 dp)
#   34 sp — 2 lines 71 dp;  30 sp — 2 lines 63 dp;  22 sp — 2 lines 47 dp
# The title's own height is what moves everything under it: the 16 dp spacer, the 52 dp pattern track and the 20 dp
# spacer put the status line 106 dp below the title's top edge, i.e. at the title's height plus 106 dp — which is
# what the three untouched measurements above say (329 + 106 = 435, 81 + 106 = 187, 701 + 106 = 807).
#
# The bounds below sit between two lines and three lines at the largest size the title may use, with room on both
# sides. The title's height is capped at 175 dp: two lines at 68 sp is 143 dp, three is 205 dp, and today it is
# 329 dp. The status line's top is capped at 280 dp: a two-line title leaves it at 249 dp at 68 sp and 198 dp at
# 44 sp, three lines at 68 sp put it at 311 dp, and today it sits at 435 dp. A one-line title at the full display
# size is 81 dp, so the cases that must show one line at 68 sp accept 75 dp to 110 dp: 68 sp passes, a title shrunk
# to 60 sp or below does not, and the range still covers a case that measures the TitleBlock column (14 dp eyebrow
# + 4 dp spacer + 81 dp title) instead of the Text node.
#
# The plan's Decisions these cases rest on: "The cap is on the title text, not on the card's height" — the bound is
# the title node's height, not the card's; "A focus that already fits is untouched" — the short-focus case; "Only
# the PENDING title changes; PLANNED keeps its display type" — the PLANNED case; and "The stored focus is not
# shortened; this is a display rule" — a case has to find the title node whatever the card ends up drawing.
#
# Two things the plan's Risks say a case has to survive. Compose 1.7.6 registers no GetTextLayoutResult semantics
# action on a plain Text (checked: the card's title node carries only Text, and asking for that action fails with
# "Semantics action GetTextLayoutResult is not defined on node"), so a case cannot read a line count and has to
# read the node's height. And a title that measures the text and then shrinks it recomposes until it settles, so a
# case has to await the settled render with waitUntil rather than assert on the first frame.

# ─── 3. The reported focus, capped ─────────────────────────────────────────────
# Brief: "cap the PENDING title at two lines and shrink the type to fit (or ellipsize), so the PENDING card keeps
# the same layout as PLANNED".
#
# The PENDING card for the sortie continueWithoutOutline created, with the focus the issue reports written on the
# sortie row: "Lower Strength – Squat & Hinge, plus core" (the dash is an en dash; the card uppercases it). The
# title node — the card's title text, the text node between the eyebrow and the chips row — must be at most 175 dp
# tall. On the untouched tree it measures 329 dp, five lines of 68 sp running down past the chips row and the status
# line, which is the bug. Match the node by a substring of the focus, or by that position, not by the whole focus:
# the plan's Decision is that the stored focus is not shortened, but a fix that draws less of it still has to be
# found. Whether the title fits by shrinking, by ellipsizing, or by both is the implementer's call; the height is
# what the card shows.
echo ""
echo "=== the reported focus, capped ==="
t pendingTitleWithALongFocusIsAtMostTwoLinesTall

# Brief: the long focus "ran to five lines and pushed the status to mid-screen", and the fix is "so the PENDING
# card keeps the same layout as PLANNED" — the layout the card actually shows, not only the title.
#
# The same card and focus as the case above: the status line node (the one note line under the pattern track and
# the 20 dp spacer — "Coach is planning…" while a generation is queued or running, "Flight Plan not ready yet."
# when it is idle) must have its top edge at most 280 dp from the top of the render. On the untouched tree it sits
# at 435 dp, pushed down the card by the five-line title; with a short focus the same line sits at 187 dp. A title
# capped at two lines leaves it at 249 dp at 68 sp and at 198 dp at 44 sp; three lines at 68 sp put it at 311 dp.
# So a card whose title still runs on fails here even if the title node itself looks capped.
t pendingStatusLineStaysNearTheTopOfTheCardWithALongFocus

# ─── 4. What the cap must not do ───────────────────────────────────────────────
# Plan's Decision: "A focus that already fits is untouched" — the cap is a cap, not a new size for every title. The
# brief's own account is the same: the short title "which fits" is the shape the fix is being asked for.
#
# The PENDING card with a short focus on the sortie row ("Bench Press"): the title node (the card's title text, the
# node between the eyebrow and the chips row) must be one line at the full display size — its height between 75 dp
# and 110 dp (one line at 68 sp measures 81 dp in real text metrics, 35 dp in the default mode, which is why this
# case also pins that the harness is measuring real text). A cap that shrinks every title, or draws the focus at a
# smaller style, fails here.
t pendingTitleWithAShortFocusIsOneLineAtTheFullDisplaySize

# Brief: "cap the PENDING title at two lines" — a line cap, so the same rule has to hold for a focus no coach would
# ever write, not only for the one the issue happens to report. The plan's Decision "The stored focus is not
# shortened; this is a display rule" is what makes the row's own text the input.
#
# The PENDING card with an 112-character focus on the sortie row ("Lower Strength – Squat, Hinge, Core, Lunges,
# Calf Raises, Seated Cable Rows, Face Pulls, Hamstring Curls and Abs"): the title node, found the same way as in
# the first case, must be at most 175 dp tall, the same bound as the reported focus. On the untouched tree it
# measures 701 dp, eleven lines. A cap tuned to the length the issue happens to report, or a character count that
# lets a longer focus run on, fails here.
t pendingTitleWithAnUnreasonablyLongFocusIsAtMostTwoLinesTall

# Brief: the PLANNED card's title "was the plan's short 'LOWER — SQUAT & HINGE', which fits", and the fix is asked
# for "so the PENDING card keeps the same layout as PLANNED". The plan's Decision is that the PLANNED card is not
# touched.
#
# The PLANNED card for the same sortie — the long focus written on the row, and CoachFixtures.LIFT_PLAN stored
# through storePlan, the way LaunchpadPlannedActionsTest seeds a planned card: its title node is the plan's title
# ("Push + core" → "PUSH + CORE"), found by that text, and it must still be one line at the full display size — its
# height between 75 dp and 110 dp (81 dp on the untouched tree). A fix that reaches PlannedContent, or that changes
# DisplayTitle or screenTitle for every caller, fails here.
t plannedCardTitleIsStillOneLineAtTheFullDisplaySize

# ─── 5. The shared types and the cards that already worked ─────────────────────
# Plan's Testing section: "TypographyTest still passes, and the Launchpad tests that assert the card's structure
# still pass." The gate ran them; this checks they ran at all, so a green gate cannot come from deleting the tests
# that would have caught a change to the shared title composables or to the type.
echo ""
echo "=== the classes that must still pass, untouched ==="
c com.liftoff.app.ui.theme.TypographyTest
c com.liftoff.app.ui.launchpad.LaunchpadPendingStatusTest
c com.liftoff.app.ui.launchpad.LaunchpadPlannedActionsTest
c com.liftoff.app.ui.launchpad.LaunchpadViewInRhoTest
c com.liftoff.app.ui.launchpad.LaunchpadCoachStatusTest
c com.liftoff.app.ui.launchpad.LaunchpadStatesTest

# ─── 6. Summary ────────────────────────────────────────────────────────────────
echo ""
if [ "$FAILED" -eq 0 ]; then
    echo "VERDICT: done — the PENDING card's title is capped, the card keeps the PLANNED card's layout, and a short focus and the PLANNED card are unchanged."
    exit 0
else
    echo "VERDICT: not done — $FAILED check(s) failed."
    exit 1
fi
