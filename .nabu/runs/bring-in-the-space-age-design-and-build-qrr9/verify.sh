#!/usr/bin/env bash
# Verify the Space Age design and theme brief is done.
# Run from the repository root with bash (Git Bash on Windows).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT" || exit 1

DESIGN_REF=origin/design/space-age-icon
RESULTS="$ROOT/app/build/test-results/testDebugUnitTest"
WORK="$(mktemp -d)"
README="$ROOT/design/README.md"
README_BACKUP="$WORK/README.md.orig"

restore_readme() {
  if [ -f "$README_BACKUP" ]; then cp "$README_BACKUP" "$README" && rm -f "$README_BACKUP"; fi
}
trap 'restore_readme; rm -rf "$WORK"' EXIT

PASS=0
FAIL=0
ok()   { echo "✓ $1"; PASS=$((PASS + 1)); }
fail() { echo "✗ $1"; FAIL=$((FAIL + 1)); }
check() {
  local name="$1"; shift
  if "$@"; then ok "$name"; else fail "$name"; fi
}

# Prints pass, fail, skipped or missing for one test method in the JUnit XML.
case_status() {
  local f="$RESULTS/TEST-$1.xml"
  [ -f "$f" ] || { echo missing; return; }
  awk -v n="$2" '
    !open && (index($0, "<testcase name=\"" n "\"") || index($0, "<testcase name=\"" n "[")) {
      found = 1
      if ($0 !~ /\/>[[:space:]]*$/) open = 1
      next
    }
    open && /<(failure|error)/ { bad = 1 }
    open && /<skipped/ { skipped = 1 }
    open && /<\/testcase>/ { open = 0 }
    END {
      if (!found) print "missing"
      else if (bad) print "fail"
      else if (skipped) print "skipped"
      else print "pass"
    }
  ' "$f"
}

# A named test must have run and passed; missing or skipped counts as failure.
t() {
  local cls="$1" method="$2" status
  status="$(case_status "$cls" "$method")"
  if [ "$status" = pass ]; then ok "test: ${cls##*.}.$method"; else fail "test: ${cls##*.}.$method ($status)"; fi
}

BASE="$(git merge-base HEAD origin/main)"

# ─── 1. Merge ────────────────────────────────────────────────────────
# design/, the launcher icon and the manifest wiring come from the design
# branch unchanged. DESIGN.md may only grow (amendments go at the end).

design_branch_merged() {
  git merge-base --is-ancestor "$DESIGN_REF" HEAD
}

design_files_unchanged() {
  local dbase paths
  dbase="$(git merge-base origin/main "$DESIGN_REF")" || return 1
  paths="$(git diff --name-only "$dbase" "$DESIGN_REF" | grep -vx 'DESIGN.md')"
  [ -n "$paths" ] || return 1
  echo "$paths" | while IFS= read -r p; do
    git cat-file -e "HEAD:$p" 2>/dev/null || exit 1
    git diff --quiet "$DESIGN_REF" HEAD -- "$p" || exit 1
  done
}

design_md_kept() {
  local want have
  want="$(git show "$DESIGN_REF:DESIGN.md")" || return 1
  have="$(git show HEAD:DESIGN.md)" || return 1
  [ "${have:0:${#want}}" = "$want" ]
}

check "merge: $DESIGN_REF is merged into HEAD" design_branch_merged
check "merge: design/, launcher icon and manifest match $DESIGN_REF exactly" design_files_unchanged
check "merge: DESIGN.md keeps the design branch text (amendments only appended)" design_md_kept

# ─── 2. Build gate (what CI runs) ────────────────────────────────────
# Old results are removed so every named test below comes from this run.

echo ""
echo "─── Build gate ─────────────────────────────────────────────────────"
rm -rf "$RESULTS"
bash "$ROOT/gradlew.sh" :app:assembleDebug :app:testDebugUnitTest 2>&1 | tee "$WORK/gate.log"
GATE_RC=${PIPESTATUS[0]}
check "gate: :app:assembleDebug :app:testDebugUnitTest succeeds" [ "$GATE_RC" -eq 0 ]

# ─── 3. Named tests of the new behavior ──────────────────────────────
P=com.liftoff.app.ui.theme

# Brief: a unit test pins all 12 color tokens to the hex values in the
# design/README.md color table (by token name).
t $P.ColorTokensTest tokensMatchDesignReadme

# Decision: the M3 color scheme maps every surface and container slot to a token.
# Every Color in the theme's ColorScheme is one of the 12 tokens (no M3 purple or tonal default).
t $P.ColorSchemeTest everySchemeSlotIsADesignToken

# Decision: typography is exposed as named roles and also mapped into M3 Typography.
# Each README type role has its README font, size, weight and letter spacing.
t $P.TypographyTest namedRolesMatchReadmeTypeTable
# Display, headline and title slots use Big Shoulders Display; body and label slots use Work Sans.
t $P.TypographyTest materialSlotsUseDesignFonts

# Brief: shapes are 4 dp corners on buttons and checkboxes, square cards.
t $P.ShapesTest buttonsAndCheckboxesHaveFourDpCornersAndCardsAreSquare

# Brief: Big Shoulders Display 700/800/900 and Work Sans 400/500/600 are bundled.
# Each of the six font resources loads as a real font and carries its weight.
t $P.FontResourcesTest bundlesStaticBigShouldersAndWorkSansWeights

# Decision: licenses ship in app/src/main/assets/licenses/, one file per family.
# The APK's assets hold an OFL text for Big Shoulders Display and one for Work Sans.
t $P.FontLicenseTest oflTextShipsInApkAssetsPerFamily

# Decision: composables live in com.liftoff.app.ui.theme.
# Fails if the theme or any building block is declared outside that package.
t $P.ThemePackageTest buildingBlocksAreDeclaredInUiTheme

# Decision: a check-mark drawable (ic_check) is added beside the four named icons.
# Rocket, ringed planet, flag, sliders and check each load as 24 dp vector drawables.
t $P.IconsTest strokeIconsAre24dpVectorDrawables

# Brief: a solid offset shadow, 4 dp right and down, no blur, color configurable.
t $P.OffsetShadowTest shadowIsSolidAndOffsetFourDp
# Decision: the offset shadow takes no layout space (like CSS box-shadow).
t $P.OffsetShadowTest shadowTakesNoLayoutSpace

# Brief: 3-band red/mustard/teal 6 dp stripe and 2-band mustard/red 5 dp stripe.
t $P.StripesTest triStripeIsRedMustardTealSixDpBands
t $P.StripesTest duoStripeIsMustardRedFiveDpBands

# Decision: the button icon can sit before or after the label, default before.
t $P.ButtonsTest primaryButtonIconLeadsLabelByDefault
t $P.ButtonsTest primaryButtonIconTrailsLabelWithIconAtEnd

# Decision: press feedback without ripples.
# The red button's fill is exactly red_pressed while pressed (no ripple overlay).
t $P.ButtonsTest primaryButtonFillsRedPressedWhilePressedWithNoRipple
# The ink button's red shadow becomes red_pressed while pressed.
t $P.ButtonsTest inkButtonShadowTurnsRedPressedWhilePressed
# The text button's label and underline turn red while pressed.
t $P.ButtonsTest textButtonTurnsRedWhilePressed

# Decision: the underline is drawn by hand, 1 dp thick, 4 dp below the baseline.
t $P.ButtonsTest textButtonUnderlineIsOneDpFourDpBelowBaseline

# Brief: the pattern track shows one chip per R/L in landed, current or upcoming
# style, joined by a 2 dp ink line.
t $P.PatternTrackTest drawsOneChipPerLetterInItsStateStyle

# Decision: the placeholder draws edge to edge with dark system-bar icons.
t com.liftoff.app.MainActivityTest drawsEdgeToEdgeWithDarkSystemBarIcons

# Decision: the placeholder follows the mockup's top bar.
# Cream screen, the 3-band stripe at the top, LIFTOFF at the left with 16 dp top and 20 dp side padding.
t com.liftoff.app.MainActivityTest showsStripeThenWordmarkAtTopLeftOnCream

# Decision: the color test reads design/README.md itself, not copied hex values.
# Changing cream's hex in the README must make ColorTokensTest fail.
readme_drives_color_test() {
  [ -f "$README" ] || return 1
  cp "$README" "$README_BACKUP" || return 1
  sed -i 's/^\(| `cream` | `\)#[0-9A-Fa-f]\{6\}`/\1#0A0B0C`/' "$README"
  if cmp -s "$README" "$README_BACKUP"; then restore_readme; return 1; fi
  bash "$ROOT/gradlew.sh" :app:testDebugUnitTest --tests "$P.ColorTokensTest" --rerun \
    > "$WORK/mutant.log" 2>&1
  local rc=$?
  restore_readme
  [ "$rc" -ne 0 ] && [ "$(case_status "$P.ColorTokensTest" tokensMatchDesignReadme)" = fail ]
}
check "decision: ColorTokensTest fails when the README hex changes" readme_drives_color_test

# ─── 4. Repository decisions ─────────────────────────────────────────

# Decision: no Gradle file changes (committed or uncommitted).
no_gradle_changes() {
  git diff --quiet "$BASE" -- build.gradle.kts settings.gradle.kts gradle.properties \
    app/build.gradle.kts gradle gradlew gradlew.bat
}
check "decision: no Gradle file changes since $(git rev-parse --short "$BASE")" no_gradle_changes

# Decision: *.ttf binary, so git never normalizes a font's line endings.
ttf_is_binary() {
  [ "$(git check-attr text -- app/src/main/res/font/probe.ttf)" = "app/src/main/res/font/probe.ttf: text: unset" ]
}
check "decision: git treats .ttf files as binary" ttf_is_binary

# CLAUDE.md: LF line endings except *.bat, for every file this branch changed.
lf_endings() {
  ! git diff --name-only -z "$BASE" HEAD | xargs -0 git ls-files --eol -- \
    | grep -v '\.bat$' | grep -Eq '^i/(crlf|mixed)'
}
check "convention: changed files are stored with LF endings" lf_endings

# ─── 5. Built APK carries the launcher icon ──────────────────────────

apk_uses_launcher_icon() {
  local sdk="${ANDROID_HOME:-C:/Users/corpo/android-toolchain/sdk}" aapt2="" d
  for d in "$sdk"/build-tools/*/; do
    [ -f "${d}aapt2" ] && aapt2="${d}aapt2"
    [ -f "${d}aapt2.exe" ] && aapt2="${d}aapt2.exe"
  done
  [ -n "$aapt2" ] || return 1
  "$aapt2" dump badging "$ROOT/app/build/outputs/apk/debug/app-debug.apk" 2>/dev/null \
    | grep -Eq "^application: .*icon='res/mipmap[^']*/ic_launcher\.xml'"
}
check "apk: application icon is the adaptive mipmap/ic_launcher" apk_uses_launcher_icon

# ─── Summary ─────────────────────────────────────────────────────────

echo ""
echo "═══════════════════════════════════════════════"
echo "  Results: $PASS passed, $FAIL failed"
echo "═══════════════════════════════════════════════"

[ "$FAIL" -eq 0 ]
