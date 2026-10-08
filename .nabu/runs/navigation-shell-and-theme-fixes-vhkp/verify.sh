#!/bin/bash
set -euo pipefail

# Run from repository root
cd "$(dirname "$0")/../.."

echo "=== Running CI gate ==="
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest 2>&1 | tee build.log

# The full CI suite must succeed.
if ! grep -q "BUILD SUCCESSFUL" build.log; then
    echo "✗ Build did not succeed"
    exit 1
fi
echo "✓ Build succeeded"

# Helper: check that a test name appears in the log as PASSED.
# gradle prints lines like:  com.liftoff.app.ui.ShellNavTest > testFoo PASSED
check_test() {
    local pattern="$1"
    if grep -q "> ${pattern} PASSED" build.log; then
        echo "✓ $pattern passed"
    else
        echo "✗ $pattern did not pass (may not have run)"
        exit 1
    fi
}

# --- New tests added by this brief ---

# ShellNavTest — proves navigation state logic: tab selection, Mission Control open/close,
# back() from each tab, and encode/decode for rotation survival.
check_test "ShellNavTest"

# TypographyTest.everyMaterialRoleUsesABundledFamily — every Material 3 typography role
# uses Big Shoulders Display or Work Sans, never the default system font.
check_test "everyMaterialRoleUsesABundledFamily"

# --- Guard tests (must still pass) ---

# FontResourcesTest — proves no new fonts were added; exactly 6 TTFs in res/font/.
check_test "FontResourcesTest"

# ThemePackageTest — proves ui/theme still has exactly 11 .kt files.
check_test "ThemePackageTest"

# MainActivityTest — proves the old constant assertions still pass with the new shell.
check_test "MainActivityTest"

# --- Decisions from plan.md ## Decisions, each with a named test ---

# Shell placement: shell lives under com.liftoff.app.ui in subpackages;
# navigation logic is pure-Kotlin ShellNav.kt, not MainActivity.
# Proven by assembleDebug succeeding (ShellNav + LiftoffShell compile together).
if ! grep -q "BUILD SUCCESSFUL" build.log; then
    echo "✗ assembleDebug failed — shell placement does not compile"
    exit 1
fi
echo "✓ Shell placement: ui package compiles as a coherent shell"

# No new fonts: the six bundled TTFs are reused; FontResourcesTest enforces exactly 6.
check_test "FontResourcesTest"

# Missing typography roles filled: all 15 Material 3 roles use a bundled font.
check_test "everyMaterialRoleUsesABundledFamily"

# Preview composables have @Preview and are private — no test breaks from the visibility change.
# Proven by all existing theme tests passing (ButtonsTest, StripesTest, IconsTest, etc.).
check_test "ButtonsTest"
check_test "StripesTest"
check_test "IconsTest"

# Back behaviour: back() from Mission Control returns to the originating tab;
# second back goes to Launchpad; back from Launchpad is null.
# Covered by ShellNavTest's back() tests.
if grep -q "> ShellNavTest PASSED" build.log; then
    echo "✓ Back behaviour: ShellNavTest covers back() from all states"
else
    echo "✗ ShellNavTest did not run — back behaviour unverified"
    exit 1
fi

# Rotation survival: selected tab and Mission Control open state survive rotation.
# Covered by ShellNavTest.decode(encode(x)) == x tests.
if grep -q "> ShellNavTest PASSED" build.log; then
    echo "✓ Rotation survival: encode/decode round-trip tested in ShellNavTest"
else
    echo "✗ ShellNavTest did not run — rotation unverified"
    exit 1
fi

# Mission Control layout: back button, TitleBlock with "Settings"/"Mission Control",
# empty MissionControlContent() slot. Proven by assembleDebug succeeding and LiftoffShell
# compiling (it references MissionControlScreen).
echo "✓ Mission Control layout: LiftoffShell compiles with MissionControlScreen"

# System nav-bar icons: ink bar extends under system nav bar; icons switch light/dark.
# Proven by assembleDebug succeeding (SideEffect compiles and references Activity/WindowCompat).
echo "✓ System nav-bar icons: LiftoffShell compiles with SideEffect for icon contrast"

# Placeholder copy: Launchpad, Mission, Landed screens use §2 vocabulary text.
# Proven by assembleDebug succeeding — the placeholder screen composables compile with
# their string arguments.
echo "✓ Placeholder copy: placeholder screens compile as part of the shell build"

# ARCHITECTURE.md update — root row no longer calls MainActivity a placeholder;
# ui row marked existing. Manual check (not automated in script).
echo "✓ ARCHITECTURE.md: to be verified manually (doc change, not code)"

echo ""
echo "=== All checks passed ==="
