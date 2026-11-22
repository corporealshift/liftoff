#!/usr/bin/env bash
# Verify: "Theme tests can't fail: replace the tautological assertions"
# (brief.md; corporealshift/liftoff#63).
# Definition of done for this run: exit 0 only when the brief is met.
#
# What is checked, in one line:
# the CI gate still passes, and every assertion the brief names as one that cannot fail is
# replaced by a test that can — a number read out of the production theme file it claims to
# describe, or a real measurement of a rendered composable — and MainActivity is exercised by a
# test that actually launches it.
#
# What is NOT checked here, and why:
# - No test source is read. This gate runs the suite and reads the JUnit XML reports Gradle
#   writes; it never greps a .kt file for a name, a string or a pattern, and never checks that a
#   file exists. A test of the new behavior is the proof. The only counting here is of the cases
#   Gradle recorded under com.liftoff.app.MainActivityTest, which the plan deletes.
# - Whether each new test can actually fail is not probed by mutating production code: a
#   mutation would have to edit tracked files mid-gate and restore them, and it would pin
#   constant names the work steps have not chosen yet. The named cases below are each written so
#   that restating a literal cannot pass them: they name the production value to read, or the
#   measurement to take.
# - Which file a named test sits in: a name is matched against the classname attribute of every
#   report, so a case may move file or package as long as it lands in the class named beside it.
#   The plan puts them in the four theme test files; nothing here requires that.
# - The mechanism of PreviewsTest's fix. Kotlin's assert is gated on the JVM's -ea flag
#   (kotlinlang.org kotlin/assert.html) and Gradle's Test task enables assertions, so today's
#   assert(found) already throws when found is false: swapping it for check(found) changes
#   nothing observable from outside the JVM. What this gate can require is that PreviewsTest
#   still runs and still passes with its nine private @Preview composables; the swap itself is
#   reviewed in the pull request, where the brief's bullet about it lives.
# - The cases the brief lists as fine as they are: ColorTokensTest, FontResourcesTest,
#   FontLicenseTest, ColorSchemeTest, TypographyTest, IconsTest. They must still run and pass;
#   they are not required to change.
# - The design numbers themselves, beyond the named cases that read them. The owner reviews the
#   values in the pull request; this gate only requires that the cases reading them pass.
# - Whether the old vacuous cases were renamed or deleted. Only what replaces them is required.
# - The theme color tokens, Type, Shapes, Headings, InkRuledListRow, and the screens that render
#   these composables: the CI gate runs all of them.
# - Whether the plan's fallbacks were taken (deleting the icon-order case, deleting the Big
#   Shoulders case). The fallbacks are recorded in plan.md and reviewed in the pull request.
#
# How to run it: bash .rho/runs/issue-63-theme-tests-can-t-fail-replace-nn6t/verify.sh
# It runs the CI pair (:app:assembleDebug :app:testDebugUnitTest) over the whole suite, so it
# takes minutes, not seconds. Run it after the theme tests are rewritten, not between edits.
#
# It fails on the untouched tree, and for the right reason: the ten named cases below do not
# exist yet, and com.liftoff.app.MainActivityTest still contributes tests.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

RESULTS="app/build/test-results/testDebugUnitTest"
WORK="app/build/verify63"
rm -rf "$WORK" 2>/dev/null || true
rm -rf app/build/verify-old-results-* 2>/dev/null || true
mkdir -p app/build "$WORK"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# The Gradle command, chosen the way CI chooses it.
# ci.yml runs `./gradlew :app:assembleDebug :app:testDebugUnitTest` on Ubuntu with Java 17.
# gradlew.sh is the owner's own shortcut and is Windows-only: it execs
# C:/Users/corpo/android-toolchain/gradle/bin/gradle whatever the platform, so it is used only
# where it works. On a machine with no cached wrapper distribution the pinned toolchain is used
# instead — the same Gradle 8.11.1 that gradle/wrapper/gradle-wrapper.properties asks for — with
# JAVA_HOME and ANDROID_HOME pointed at it. Nothing here edits gradlew.sh: it is not part of an
# issue's files. LIFTOFF_TOOLCHAIN overrides the toolchain root; it exists only so this script
# can be tested against synthetic report sets, and is unset in every real run.
# ---------------------------------------------------------------------------
RUNNER=()
case "$(uname -s 2>/dev/null || echo unknown)" in
    MINGW*|MSYS*|CYGWIN*)
        RUNNER=(bash gradlew.sh)
        ;;
    *)
        TOOLCHAIN="${LIFTOFF_TOOLCHAIN:-$HOME/android-toolchain}"
        if [ -z "${JAVA_HOME:-}" ] && [ -d "$TOOLCHAIN/jdk" ]; then
            export JAVA_HOME="$TOOLCHAIN/jdk"
        fi
        if [ -z "${ANDROID_HOME:-}" ] && [ -d "$TOOLCHAIN/sdk" ]; then
            export ANDROID_HOME="$TOOLCHAIN/sdk"
            export ANDROID_SDK_ROOT="$TOOLCHAIN/sdk"
        fi
        if [ -x "$TOOLCHAIN/gradle/bin/gradle" ]; then
            RUNNER=("$TOOLCHAIN/gradle/bin/gradle")
        elif [ -x ./gradlew ]; then
            RUNNER=(./gradlew)
        else
            RUNNER=(bash gradlew.sh)
        fi
        ;;
esac

log() {
    printf '%s\n' "$*"
}

# ---------------------------------------------------------------------------
# The CI gate, run over the whole suite.
# ---------------------------------------------------------------------------
log "=== gate: ${RUNNER[*]} :app:assembleDebug :app:testDebugUnitTest ==="

clear_results() {
    if [ -d "$RESULTS" ]; then
        local aside="app/build/verify-old-results-$1"
        rm -rf "$aside" 2>/dev/null || true
        mv "$RESULTS" "$aside" 2>/dev/null || true
    fi
}

# Gradle's JUnit XML, indexed three ways: by method name, by class.method, and by class. Each
# name keeps the worst outcome it produced anywhere in the suite, so a case that is skipped in
# one report and passed in another is not counted as passed.
index_reports() {
    : > "$WORK/raw"
    for file in "$RESULTS"/TEST-*.xml; do
        [ -f "$file" ] || continue
        awk '
            function flush() {
                if (intest) {
                    name = substr(name, 1, 100)
                    gsub(/`/, "", name)
                    sub(/\(.*/, "", name)
                    if (name != "") {
                        print "M\t" name "\t" status
                        print "Q\t" class "." name "\t" status
                        print "C\t" class "\t" status
                    }
                }
            }
            function rank(s) {
                if (s == "fail") return 3
                if (s == "skip") return 2
                return 1
            }
            function worse(a, b) { if (rank(a) > rank(b)) return a; return b }
            { gsub(/\r/, "") }
            /<testsuite / {
                match($0, /name="[^"]*"/)
                class = substr($0, RSTART + 6, RLENGTH - 7)
                next
            }
            /<testcase/ {
                flush()
                intest = 1
                status = "pass"
                match($0, /name="[^"]*"/)
                name = substr($0, RSTART + 6, RLENGTH - 7)
                # Gradle closes a passing testcase on the line it opens it ("<testcase ... />"), so a
                # skipped or failed one can carry its <skipped/> or <failure/> on that same line.
                if ($0 ~ /<skipped[ \/>]/) status = worse(status, "skip")
                if ($0 ~ /<failure|<error/) status = worse(status, "fail")
                if ($0 ~ /<\/testcase>/ || $0 ~ /\/>[ \t]*$/) { flush(); intest = 0 }
                next
            }
            /<skipped[ \/>]/ { if (intest) status = worse(status, "skip"); next }
            /<failure|<error/ { if (intest) status = worse(status, "fail"); next }
            /<\/testcase>/ { flush(); intest = 0; next }
            END { flush() }
        ' "$file" >> "$WORK/raw"
    done

    : > "$WORK/all"
    : > "$WORK/methods"
    : > "$WORK/qualified"
    : > "$WORK/classes"
    if [ -s "$WORK/raw" ]; then
        awk -F '\t' '
            function rank(s) { if (s == "fail") return 3; if (s == "skip") return 2; return 1 }
            { key = $1 SUBSEP $2
              if (!(key in seen) || rank($3) > rank(seen[key])) seen[key] = $3 }
            END { for (k in seen) { split(k, p, SUBSEP); print p[1] "\t" p[2] "\t" seen[k] } }
        ' "$WORK/raw" > "$WORK/all"
        awk -F '\t' '$1 == "M" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/methods"
        awk -F '\t' '$1 == "Q" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/qualified"
        awk -F '\t' '$1 == "C" { print $2 "\t" $3 }' "$WORK/all" > "$WORK/classes"
    fi
}

# A run that failed only because Gradle itself broke, or because of the flakes this repository
# has recorded: com.liftoff.app.ui.LiftoffShellTest (a waitFor that expires under full-suite
# load, a different case each time) and com.liftoff.app.ui.inflight.InFlightScreenTest (a
# CalledFromWrongThreadException from a DefaultDispatcher worker reaching a node tree being torn
# down). Both are green when their class runs alone. A failure in any other class is a real
# failure and stops the retry, so a case this run is supposed to create can never be retried
# into green.
transient_failure() {
    local attempt="$1"
    local gate_log="$WORK/gate-$attempt.log"

    if grep -qiE 'daemon disappeared|may have been killed|out of memory|GC overhead|Could not connect to the daemon|Build terminated|could not clear|Timeout has been exceeded|exceeded its configured timeout|Test Executor|PersistentEnumerator storage corrupted|Unable to delete directory' "$gate_log"; then
        log "verify: the build itself broke; retrying"
        return 0
    fi

    if ! ls "$RESULTS"/TEST-*.xml >/dev/null 2>&1; then
        if grep -qiE 'compileDebugKotlin FAILED|compileDebugUnitTestKotlin FAILED|compilation error|^e: |unresolved reference' "$gate_log"; then
            return 1
        fi
        log "verify: Gradle wrote no test reports at all; retrying"
        return 0
    fi

    local any other
    any="$(awk -F '\t' '$2 == "fail" { n++ } END { print n + 0 }' "$WORK/qualified")"
    if [ "$any" -eq 0 ]; then
        log "verify: the build failed without failing a test; retrying"
        return 0
    fi
    other="$(awk -F '\t' '$2 == "fail" && !($1 ~ /(^|\.)LiftoffShellTest\./) && !($1 ~ /(^|\.)InFlightScreenTest\./) { n++ } END { print n + 0 }' "$WORK/qualified")"
    if [ "$other" -eq 0 ]; then
        log "verify: only the recorded flaky classes failed; retrying"
        return 0
    fi
    return 1
}

clear_results 0
gate_status="red"
last_log="$WORK/gate-1.log"
for attempt in 1 2 3; do
    clear_results "$attempt"
    log "=== :app:assembleDebug :app:testDebugUnitTest (attempt $attempt) ==="
    rc=0
    last_log="$WORK/gate-$attempt.log"
    timeout 1800 "${RUNNER[@]}" :app:assembleDebug :app:testDebugUnitTest --no-build-cache --console=plain > "$last_log" 2>&1 || rc=$?
    index_reports
    if [ "$rc" -ne 0 ] && transient_failure "$attempt"; then
        continue
    fi
    if [ "$rc" -eq 0 ]; then
        # clear_results moved the reports aside, so the test task cannot be UP-TO-DATE; if it says
        # it did not run, the reports on disk are not this run's and nothing below means anything.
        # assembleDebug is not checked: it is a lifecycle task and is normally UP-TO-DATE.
        if grep -E '^> Task :app:testDebugUnitTest (FROM-CACHE|UP-TO-DATE|NO-SOURCE)' "$last_log" >/dev/null 2>&1; then
            log "verify: the test task was served from cache or skipped; that is not a run of the current tree"
            gate_status="red"
            break
        fi
        gate_status="green"
    else
        gate_status="red"
    fi
    break
done

passed=0
failed=0
ok() { passed=$((passed + 1)); log "  ok $*"; }
bad() { failed=$((failed + 1)); log "  FAIL $*"; }

if [ "$gate_status" = "green" ]; then
    ok "gate: :app:assembleDebug :app:testDebugUnitTest"
else
    bad "gate: :app:assembleDebug :app:testDebugUnitTest"
    log "--- last gate output ---"
    tail -120 "$last_log" 2>/dev/null || true
    for f in "$WORK"/gate-*.log; do
        if [ "$f" = "$last_log" ]; then continue; fi
        log "--- $f (tail) ---"
        tail -40 "$f" 2>/dev/null || true
    done
fi

log "=== $(wc -l < "$WORK/methods") test names, $(wc -l < "$WORK/classes") classes indexed ==="

# ---------------------------------------------------------------------------
# t <class> <method> — the case must exist, must have run, and must have passed.
# <class> may be given simple or fully qualified; the match is on the classname attribute
# Gradle writes, so a package change does not break it.
# ---------------------------------------------------------------------------
t() {
    local cls="$1" method="$2" want status
    want="$(printf '%s' "$method" | tr -d '`')"
    status="$(awk -F '\t' -v k="$cls.$want" '{ p = index($1, "." k); if ($1 == k || (p > 0 && p + length(k) == length($1))) { print $2; exit } }' "$WORK/qualified")"
    case "$status" in
        pass) ok "$cls.$want" ;;
        fail) bad "$cls.$want — ran and FAILED" ;;
        skip) bad "$cls.$want — ran but was SKIPPED" ;;
        *)
            if awk -F '\t' -v k="$cls.$want" '{ p = index($1, "." k); if ($1 == k || (p > 0 && p + length(k) == length($1))) { found = 1 } } END { exit !found }' "$WORK/qualified"; then
                bad "$cls.$want — ran with an unreadable result"
            else
                bad "$cls.$want — did not run at all"
            fi
            ;;
    esac
}

# c <class> — every test Gradle recorded for this class must have run and passed.
c() {
    local cls="$1" status
    status="$(awk -F '\t' -v c="$cls" '{ p = index($1, "." c); if ($1 == c || (p > 0 && p + length(c) == length($1))) { print $2; exit } }' "$WORK/classes")"
    case "$status" in
        pass) ok "class $cls — every test in it passed" ;;
        fail) bad "class $cls — a test in it FAILED" ;;
        skip) bad "class $cls — a test in it was SKIPPED" ;;
        *) bad "class $cls — ran no tests at all" ;;
    esac
}

# absent <class> — no test case may run under this class. The count is of cases, not of class
# records, so a deleted class and a class with no cases both read as zero. The qualified key is
# "<class>.<method>", so the class is what sits before the first dot that follows it.
absent() {
    local cls="$1" n
    n="$(awk -F '\t' -v c="$cls" 'index($1, c ".") == 1 { n++ } END { print n + 0 }' "$WORK/qualified")"
    if [ "$n" -eq 0 ]; then
        ok "$cls contributes no tests"
    else
        bad "$cls still contributes $n test(s)"
    fi
}

# ===========================================================================
# 1. StripesTest — the brief's first example: assertEquals(Mustard.value, Mustard.value), and
#    locals asserted against literals.
# ===========================================================================

# The band heights the brief says are "hard-coded inside the composables and never named" must
# now be values declared in Stripes.kt that TriStripe and DuoStripe themselves read, and the
# test must read those same values: 6 dp for a tri stripe's band, 5 dp for a duo stripe's.
# Decision: "Do the composables switch to the new constants, or do the constants exist only for
# the tests?" — a constant no composable reads proves nothing about what is drawn.
# Decision: "Do the rewritten tests pin the design values, or only the relationships between
# them?" — the design sheet's numbers, not only tri = 3 x band.
t StripesTest stripeBandHeightsAreTheValuesTheStripesDraw

# The same claim measured from the other side, through the semantics tree: a rendered TriStripe
# is 18 dp tall (3 x 6) and a rendered DuoStripe is 10 dp (2 x 5). Density is 1.0 on this
# machine, so dp and px agree here.
# Decision: "Which claims get a rendered Compose test, and which are pinned as numbers?" — the
# first of the four claims this plan renders.
t StripesTest stripesRenderAtTheirBandHeights

# ===========================================================================
# 2. PatternTrackTest — the brief's second example: assertEquals(44f, 44f) and
#    assertEquals(900, 900).
# ===========================================================================

# The chip diameter must be a function of the chip's state that the composables call, not a
# literal in a test: 52 dp for a Current chip, 44 dp for Upcoming, Landed and Scrubbed.
# Decision: "Do the composables switch to the new constants, or do the constants exist only for
# the tests?" — PatternTrack.kt already calls chipSizeDp, so the production call site is what
# makes this a claim about the app rather than about the test.
t PatternTrackTest chipSizeFollowsChipState

# The track's height measured from the rendered composable: a track holding two non-landed
# letters — "R" current and "L" upcoming — is 52 dp tall, and both letters appear as text nodes,
# which is the one-chip-per-letter claim the drawBehind loop cannot be checked through.
# Decision: "Which claims get a rendered Compose test, and which are pinned as numbers?" — the
# second of the four rendered claims.
t PatternTrackTest trackDrawsOneChipPerLetterAndMeasuresItsHeight

# Everything else about the track is drawn, so it has no semantics node and is pinned as values
# the composables read: the chip ring widths 3 dp current, 2 dp upcoming, 2 dp scrubbed; the
# connector 2 dp wide and inset 22 dp from each end; the check mark 20 dp.
# Decision: "Do the rewritten tests pin the design values, or only the relationships between
# them?" — the design sheet's numbers, so a restyle fails the gate.
t PatternTrackTest chipRingsAndConnectorAreTheDesignWidths

# ===========================================================================
# 3. ButtonsTest — the brief's third example: val iconAtEnd = true; assertEquals(true,
#    iconAtEnd), and the same shape for the pressed color and the underline.
# ===========================================================================

# The dimensions the brief says are "hard-coded inside the composables and never named" must be
# values in Buttons.kt that PrimaryButton and InkButton read: 72 dp primary height, 64 dp ink
# height, 2 dp border, 26 dp icon, 12 dp gap, a 44 dp minimum touch height, and a 1 dp underline
# 4 dp below the baseline.
# Decision: "Do the composables switch to the new constants, or do the constants exist only for
# the tests?"
# Decision: "Do the rewritten tests pin the design values, or only the relationships between
# them?"
t ButtonsTest buttonDimensionsAreTheDesignValues

# The pressed fill must be computed from the tokens it claims to use — Red, RedPressed and Ink
# are already asserted against their hex literals in ColorTokensTest — and the test must show
# the relationship the vacuous pressed == pressed was standing in for: the pressed fill differs
# from the normal fill, and is darker than it.
t ButtonsTest pressedFillIsComputedFromTheTokens

# The brief names iconAtEnd specifically, so the flip is measured rather than restated: render
# PrimaryButton once with the icon leading and once with iconAtEnd, and the label's left edge
# moves by 38 dp (26 dp icon + 12 dp gap). PrimaryButton and InkButton set contentDescription =
# null on their Icon, so the label's position is the only handle the semantics tree gives. The
# plan's fallback for this case is deletion if the measurement cannot be made; it stays required
# here because the brief flags this exact vacuous test by name.
# Decision: "Which claims get a rendered Compose test, and which are pinned as numbers?" — the
# third of the four rendered claims.
t ButtonsTest labelShiftsWhenTheIconMoves

# ===========================================================================
# 4. OffsetShadowTest — the brief's fourth example: modifier != null on a non-nullable type,
#    and locals asserted against literals for the offset and the corner.
# ===========================================================================

# The offset and the corner must be values in OffsetShadow.kt that both offsetShadow and
# OffsetShadowBox read: 4 dp each, which is design/README.md's box-shadow: 4px 4px 0.
# Decision: "Do the composables switch to the new constants, or do the constants exist only for
# the tests?" — OffsetShadow.kt's private Modifier.offset(offset: Dp) is a no-op, and
# design/README.md says the content is not shifted, so this pins the default the modifier is
# called with. The test must NOT claim the content moves.
# Decision: "Do the rewritten tests pin the design values, or only the relationships between
# them?"
t OffsetShadowTest offsetShadowDefaultsAreFourDp

# The claim the vacuous modifier != null was standing in for, measured instead: a tagged box of
# a fixed size that applies offsetShadow keeps exactly that size, because the shadow is drawn
# behind and takes no layout space.
# Decision: "Which claims get a rendered Compose test, and which are pinned as numbers?" — the
# fourth of the four rendered claims.
t OffsetShadowTest aShadowedBoxKeepsTheSizeItAskedFor

# ===========================================================================
# 5. MainActivityTest — the brief's fifth example: a file that "never touches MainActivity",
#    with a comment claiming it does.
# ===========================================================================

# Decision: "MainActivityTest: rewrite or delete?" — deleted. Its two cases are vacuous, and
# MainActivity runs the open sweep inside lifecycleScope.launch { repeatOnLifecycle(STARTED) }
# on the application's own container with no injectable seam, so a rewrite would be an
# integration test duplicating LiftoffShellTest. What the brief wants is MainActivity exercised
# by a test that touches it, and that is the class below.
absent com.liftoff.app.MainActivityTest

# MainActivity is launched here, through createAndroidComposeRule<MainActivity>(). This is the
# coverage the deleted file's comment claimed to provide.
c com.liftoff.app.ui.LiftoffShellTest

# ===========================================================================
# 6. PreviewsTest — the brief's sixth example: Kotlin assert(), which only throws under -ea.
# ===========================================================================

# The brief says this file's checks are "correct, but they depend on a JVM flag". The nine
# private @Preview composables in ui/theme/ must still be found and must still pass, so the swap
# to check() cannot quietly drop a case or widen the search. The mechanism itself is not
# observable from outside the JVM — see the header.
c com.liftoff.app.ui.theme.PreviewsTest

# ===========================================================================
# 7. What the brief says is fine as it is, and what the new constants must not disturb.
# ===========================================================================

# The brief lists these as correct already: they must still run and still pass, unchanged.
# Decision: "FontLicenseTest uses the same Kotlin assert() the brief flags in PreviewsTest, yet
# the brief lists it among the files that are fine. Fix it too?" — the review reversed the
# plan's first answer: FontLicenseTest is left untouched, so it is required here only as a class
# that must keep passing.
c com.liftoff.app.ui.theme.ColorTokensTest
c com.liftoff.app.ui.theme.FontResourcesTest
c com.liftoff.app.ui.theme.FontLicenseTest
c com.liftoff.app.ui.theme.ColorSchemeTest
c com.liftoff.app.ui.theme.TypographyTest
c com.liftoff.app.ui.theme.IconsTest
c com.liftoff.app.ui.theme.ShapesTest

# ThemePackageTest pins app/src/main/java/com/liftoff/app/ui/theme/ at exactly 11 files, so the
# new constants must go into the four production files rather than into a new one.
c com.liftoff.app.ui.theme.ThemePackageTest

# The cases the plan deletes are not required here, and nothing may be renamed away to dodge a
# check: every case Gradle recorded in the four rewritten classes must have passed.
for cls in StripesTest PatternTrackTest ButtonsTest OffsetShadowTest; do
    c "com.liftoff.app.ui.theme.$cls"
done

log ""
log "=== $passed ok, $failed failed ==="
if [ "$failed" -gt 0 ]; then
    log "=== VERDICT: not done ==="
    exit 1
fi
log "=== VERDICT: done ==="
