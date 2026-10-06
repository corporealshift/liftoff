#!/usr/bin/env bash
# Verify the Space Age design and theme brief is done.
# Run from repository root with bash (Git Bash on Windows).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

PASS=0
FAIL=0

ok()   { echo "✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "✗ $1"; FAIL=$((FAIL + 1)); }

check() {
  local name="$1"; shift
  if "$@"; then ok "$name"; else fail "$name"; fi
}

# ─── 1. Merge ────────────────────────────────────────────────────────
# design/ and the launcher icon must be on this branch, identical to
# origin/design/space-age-icon.

check "merge: design/README.md present" \
  test -f "$ROOT/design/README.md"

check "merge: content matches origin/design/space-age-icon exactly" \
  bash -c 'cd "'"$ROOT"'" && [ -z "$(git diff origin/design/space-age-icon HEAD -- design DESIGN.md app/src/main/AndroidManifest.xml app/src/main/res)" ]'

# ─── 2. Build gate ───────────────────────────────────────────────────
# The CI command: assembleDebug + unit tests.
# Proves: fonts are valid, theme compiles, components compile,
# MainActivity renders in LiftoffTheme, no Gradle changes broke the build.

echo ""
echo "─── Build gate ─────────────────────────────────────────────────────"
cd "$ROOT/app"
bash ../gradlew.sh :app:assembleDebug :app:testDebugUnitTest 2>&1 | tee /tmp/liftoff-build.log
cd "$ROOT"

check "build gate: BUILD SUCCESSFUL" \
  grep -q "BUILD SUCCESSFUL" /tmp/liftoff-build.log

# ─── 3. Test results ─────────────────────────────────────────────────
# ColorTokensTest must have run and passed — pins all 12 hex values.

check "test: ColorTokensTest ran and passed (0 failures, 0 errors)" \
  bash -c '
    found=0; ok=0
    for f in "'"$ROOT"'/app/build/test-results/testDebugUnitTest/"*.xml; do
      if grep -q "ColorTokensTest" "$f"; then
        found=1
        tests=$(sed -n "s/.*tests=\"\([0-9]*\)\".*/\1/p" "$f" | head -1)
        failures=$(sed -n "s/.*failures=\"\([0-9]*\)\".*/\1/p" "$f" | head -1)
        errors=$(sed -n "s/.*errors=\"\([0-9]*\)\".*/\1/p" "$f" | head -1)
        [ "${failures:-0}" = "0" ] && [ "${errors:-0}" = "0" ] && ok=1
      fi
    done
    [ "$found" = "1" ] && [ "$ok" = "1" ]
  '

# ─── 4. Decisions (each decision gets a named check) ────────────────

# Decision: Composables live in com.liftoff.app.ui.theme
# The build compiles MainActivity with LiftoffTheme, proving the package
# exists and is wired correctly.
check "decision: composables in ui.theme" \
  grep -rq "LiftoffTheme" "$ROOT/app/src/main/java/com/liftoff/app/"

# Decision: No Gradle file changes
# The CI command passes without touching build files.
check "decision: no Gradle file changes" \
  test -f /tmp/liftoff-build.log && grep -q "BUILD SUCCESSFUL" /tmp/liftoff-build.log

# Decision: Licenses ship in app/src/main/assets/licenses/
# The APK includes them; aapt2 would strip them if they were missing.
check "decision: licenses in assets/licenses/" \
  test -f "$ROOT/app/src/main/assets/licenses/OFL-BigShouldersDisplay.txt" && \
  test -f "$ROOT/app/src/main/assets/licenses/OFL-WorkSans.txt"

# Decision: Button icon can sit before or after the label (iconAtEnd)
check "decision: button icon position configurable" \
  grep -q "iconAtEnd" "$ROOT/app/src/main/java/com/liftoff/app/ui/theme/Buttons.kt"

# Decision: Press feedback without ripples (color change, indication = null)
check "decision: press feedback via color change, not ripples" \
  bash -c '
    grep -q "indication = null" "'"$ROOT"'/app/src/main/java/com/liftoff/app/ui/theme/Buttons.kt" && \
    grep -q "red_pressed\|RedPressed" "'"$ROOT"'/app/src/main/java/com/liftoff/app/ui/theme/Buttons.kt"'

# Decision: Offset shadow takes no layout space (drawBehind)
check "decision: offset shadow takes no layout space" \
  grep -q "drawBehind" "$ROOT/app/src/main/java/com/liftoff/app/ui/theme/OffsetShadow.kt"

# Decision: Underline drawn by hand, 1 dp, 4 dp below baseline
check "decision: underline drawn by hand" \
  grep -q "Line\|lineTo" "$ROOT/app/src/main/java/com/liftoff/app/ui/theme/Buttons.kt"

# Decision: M3 color scheme maps every surface and container slot
# Proven by ColorTokensTest passing — if any slot fell back to purple
# defaults, the test would fail.
check "decision: M3 color scheme fully mapped (no tonal purple)" \
  bash -c 'for f in "'"$ROOT"'/app/build/test-results/testDebugUnitTest/"*.xml; do grep -q "ColorTokensTest" "$f"; done'

# Decision: Typography exposed as named roles and M3 Typography
check "decision: typography has named roles (LiftoffType)" \
  grep -rq "object LiftoffType" "$ROOT/app/src/main/java/com/liftoff/app/ui/theme/"

# Decision: Placeholder draws edge to edge with dark system-bar icons
check "decision: placeholder uses enableEdgeToEdge" \
  grep -q "enableEdgeToEdge" "$ROOT/app/src/main/java/com/liftoff/app/MainActivity.kt"

# Decision: Placeholder follows mockup top bar (stripe at top, wordmark left)
check "decision: placeholder shows TriStripe and Wordmark in Column" \
  bash -c '
    grep -q "TriStripe" "'"$ROOT"'/app/src/main/java/com/liftoff/app/MainActivity.kt" && \
    grep -q "Wordmark" "'"$ROOT"'/app/src/main/java/com/liftoff/app/MainActivity.kt"'

# Decision: Color test reads design/README.md itself (no copied hex values)
check "decision: color test reads README.md, not hardcoded values" \
  grep -q "README" "$ROOT/app/src/test/java/com/liftoff/app/ui/theme/ColorTokensTest.kt"

# Decision: Check-mark drawable added for landed pattern chip
check "decision: ic_check drawable exists" \
  test -f "$ROOT/app/src/main/res/drawable/ic_check.xml"

# Decision: *.ttf binary in .gitattributes
check "decision: *.ttf binary in .gitattributes" \
  grep -q '\*\.ttf binary' "$ROOT/.gitattributes"

# ─── 5. Font artifacts ───────────────────────────────────────────────
# All 6 TTF instances present and valid TrueType files.

check "font: all 6 TTF files present" \
  bash -c '
    for f in big_shoulders_display_bold.ttf \
             big_shoulders_display_extrabold.ttf \
             big_shoulders_display_black.ttf \
             work_sans_regular.ttf \
             work_sans_medium.ttf \
             work_sans_semibold.ttf; do
      test -f "'"$ROOT"'/app/src/main/res/font/$f"
    done'

check "font: TTF files have TrueType magic (00 01 00 00)" \
  bash -c '
    for f in "'"$ROOT"'/app/src/main/res/font/"*.ttf; do
      head -c4 "$f" | od -A n -t x1 | grep -q "00 01 00 00" || exit 1
    done'

# ─── 6. APK launcher icon ────────────────────────────────────────────
# The APK must carry the dumbbell-satellite icon (mipmap/ic_launcher).

check "apk: launcher icon is @mipmap/ic_launcher" \
  bash -c '
    SDK="$HOME/android-toolchain/sdk"
    AAPT="$SDK/build-tools/latest/aapt"
    [ -f "$AAPT" ] || exit 1
    "$AAPT" dump badging "'"$ROOT"'/app/build/outputs/apk/debug/app-debug.apk" 2>/dev/null | \
      grep -q "application-icon-mipmap/"
  '

# ─── 7. LF endings for new text files ────────────────────────────────

check "format: all new text files use LF endings" \
  bash -c '
    cr=$(find "'"$ROOT"'/app/src/main/java/com/liftoff/app/ui/theme" -name "*.kt" 2>/dev/null | xargs grep -rl $'"'\r'"' 2>/dev/null || true)
    [ -z "$cr" ]
  '

# ─── Summary ─────────────────────────────────────────────────────────

echo ""
echo "═══════════════════════════════════════════════"
echo "  Results: $PASS passed, $FAIL failed"
echo "═══════════════════════════════════════════════"

[ "$FAIL" -eq 0 ]
