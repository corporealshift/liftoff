#!/usr/bin/env bash
# Verify: "Theme tests can't fail: replace the tautological assertions"
# (brief.md; corporealshift/liftoff#63).
# Definition of done for this run: exit 0 only when the brief is met.
#
# What is checked, in one line:
# the CI gate still passes, and every assertion the brief names as one that cannot fail is
# replaced by a test that can — a number read out of the production theme file it claims to
# describe, or a real measurement of a rendered composable — and MainActivity is still launched
# by a test that passes.
#
# What is NOT checked here, and why:
# - No test source is read. This gate runs the suite and reads the JUnit XML reports Gradle
#   writes; it never greps a .kt file for a name, a string or a pattern, never counts tests, and
#   never checks that a file exists. A test of the new behavior is the proof.
# - Whether MainActivityTest was rewritten or deleted. The brief allows either ("Rewrite or
#   delete MainActivityTest"); the plan deletes it (Decision: "MainActivityTest: rewrite or
#   delete?"), and the owner reviews that in the pull request. Whatever remains must pass, which
#   the gate already requires.
# - Whether each new test can actually fail is not probed by mutating production code: a
#   mutation would have to edit tracked files mid-gate and restore them, and it would pin
#   constant names the work steps have not chosen yet. The named cases below are each written so
#   that restating a literal cannot pass them: they name the production value to read, or the
#   measurement to take.
# - Which file a named test sits in: a name is matched against the classname attribute of every
#   report, so a case may move file or package as long as it lands in the class named beside it.
# - The mechanism of PreviewsTest's fix. Gradle's Test task enables assertions, so today's
#   assert(found) already throws when found is false: swapping it for check(found) changes
#   nothing observable from outside the JVM. What this gate can require is that PreviewsTest
#   still runs and still passes; the swap itself is reviewed in the pull request.
# - The cases the brief lists as fine as they are: ColorTokensTest, FontResourcesTest,
#   FontLicenseTest, ColorSchemeTest, TypographyTest, IconsTest. They must still run and pass;
#   they are not required to change.
# - The design numbers themselves, beyond the named cases that read them.
# - Whether the old vacuous cases were renamed or deleted. Only what replaces them is required.
#
# How to run it: bash .rho/runs/issue-63-theme-tests-can-t-fail-replace-nn6t/verify.sh
# It runs the CI pair (:app:assembleDebug :app:testDebugUnitTest) over the whole suite, so it
# takes minutes, not seconds.
#
# It fails on the untouched tree, and for the right reason: the ten named cases below do not
# exist yet.

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
# gradlew.sh is the owner's own shortcut and is Windows-only, so it is used only where it works.
# Elsewhere the pinned toolchain is used — the same Gradle the wrapper asks for — with
# JAVA_HOME and ANDROID_HOME pointed at it. LIFTOFF_TOOLCHAIN overrides the toolchain root.
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
# name keeps the worst outcome it produced anywhere in the suite.
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

# A run that failed only because Gradle itself broke, or because of the recorded flakes in
# LiftoffShellTest and InFlightScreenTest. A failure in any other class is a real failure and
# stops the retry, so a case this run is supposed to create can never be retried into green.
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
        # The reports were moved aside, so the test task cannot legitimately be UP-TO-DATE.
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

# t <class> <method> — the case must exist, must have run, and must have passed.
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

# ===========================================================================
# 1. StripesTest
# ===========================================================================

# Tri band 6 dp, duo band 5 dp, read from the values Stripes.kt's composables draw with.
# Decision: "Do the composables switch to the new constants, or do the constants exist only for
# the tests?"
# Decision: "Do the rewritten tests pin the design values, or only the relationships between
# them?"
t StripesTest stripeBandHeightsAreTheValuesTheStripesDraw

# A rendered TriStripe measures 18 dp tall and a rendered DuoStripe 10 dp.
# Decision: "Which claims get a rendered Compose test, and which are pinned as numbers?"
t StripesTest stripesRenderAtTheirBandHeights

# ===========================================================================
# 2. PatternTrackTest
# ===========================================================================

# chipSizeDp: 52 dp for a Current chip, 44 dp for Upcoming, Landed and Scrubbed.
t PatternTrackTest chipSizeFollowsChipState

# A rendered track of "R" (current) and "L" (upcoming) shows both letters as text nodes and
# measures 52 dp tall.
# Decision: "Which claims get a rendered Compose test, and which are pinned as numbers?"
t PatternTrackTest trackDrawsOneChipPerLetterAndMeasuresItsHeight

# Ring widths 3/2/2 dp, connector 2 dp wide and inset 22 dp, check mark 20 dp, read from the
# values PatternTrack.kt draws with.
# Decision: "Do the rewritten tests pin the design values, or only the relationships between
# them?"
t PatternTrackTest chipRingsAndConnectorAreTheDesignWidths

# ===========================================================================
# 3. ButtonsTest
# ===========================================================================

# 72 dp primary height, 64 dp ink height, 2 dp border, 26 dp icon, 12 dp gap, 44 dp minimum
# touch height, 1 dp underline 4 dp below the baseline, read from Buttons.kt's values.
# Decision: "Do the composables switch to the new constants, or do the constants exist only for
# the tests?"
# Decision: "Do the rewritten tests pin the design values, or only the relationships between
# them?"
t ButtonsTest buttonDimensionsAreTheDesignValues

# The pressed fill, computed from Red, RedPressed and Ink, differs from and is darker than the
# normal fill.
t ButtonsTest pressedFillIsComputedFromTheTokens

# Rendering PrimaryButton with and without iconAtEnd moves the label's left edge by icon + gap.
# Decision: "Which claims get a rendered Compose test, and which are pinned as numbers?"
t ButtonsTest labelShiftsWhenTheIconMoves

# ===========================================================================
# 4. OffsetShadowTest
# ===========================================================================

# Default offset 4 dp and corner 4 dp, read from the values offsetShadow and OffsetShadowBox use.
# Decision: "Do the composables switch to the new constants, or do the constants exist only for
# the tests?"
t OffsetShadowTest offsetShadowDefaultsAreFourDp

# A fixed-size tagged box with offsetShadow keeps exactly that size: the shadow takes no layout
# space. It must not claim the content moves.
# Decision: "Which claims get a rendered Compose test, and which are pinned as numbers?"
t OffsetShadowTest aShadowedBoxKeepsTheSizeItAskedFor

# ===========================================================================
# 5. MainActivity — still launched by a passing test.
# ===========================================================================

# Decision: "MainActivityTest: rewrite or delete?" — LiftoffShellTest launches MainActivity
# through createAndroidComposeRule<MainActivity>(), so it must keep passing.
c com.liftoff.app.ui.LiftoffShellTest

# ===========================================================================
# 6. PreviewsTest — still runs and passes after assert() becomes check().
# ===========================================================================
c com.liftoff.app.ui.theme.PreviewsTest

# ===========================================================================
# 7. What the brief says is fine as it is, and the rewritten classes as a whole.
# ===========================================================================

# Decision: "FontLicenseTest uses the same Kotlin assert() ... Fix it too?" — left untouched;
# required only to keep passing.
c com.liftoff.app.ui.theme.ColorTokensTest
c com.liftoff.app.ui.theme.FontResourcesTest
c com.liftoff.app.ui.theme.FontLicenseTest
c com.liftoff.app.ui.theme.ColorSchemeTest
c com.liftoff.app.ui.theme.TypographyTest
c com.liftoff.app.ui.theme.IconsTest
c com.liftoff.app.ui.theme.ShapesTest
c com.liftoff.app.ui.theme.ThemePackageTest

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
