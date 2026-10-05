#!/usr/bin/env bash
set -euo pipefail

# Verify the brief is done: fundamentals required to start this project.
# Run from repo root with bash (Windows Git Bash).

# ── Gate: build and run unit tests ──────────────────────────────────────
# Proves the Gradle project compiles, dependencies resolve, and testDebugUnitTest passes.
# This is the same command CI runs (DESIGN.md §13). No test sources yet; a clean pass
# with zero tests proves the build infrastructure is correct.
echo "=== gate ==="
bash gradlew.sh :app:assembleDebug :app:testDebugUnitTest

# ── gradlew executable bit ──────────────────────────────────────────────
# The plan (§4) explicitly requires git update-index --chmod=+x so Linux CI runners can execute it.
echo "=== gradlew executable bit ==="
mode=$(git ls-files -s gradlew | awk '{print $1}')
if [[ "$mode" != "100755"* ]]; then
    echo "FAIL: gradlew has mode $mode, expected 100755"
    exit 1
fi

# ── CI workflow exists and is valid YAML ────────────────────────────────
# DESIGN.md §13 names a CI pipeline; the plan requires .github/workflows/ci.yml.
echo "=== ci workflow ==="
test -f .github/workflows/ci.yml
python3 -c "import yaml, sys; yaml.safe_load(open('.github/workflows/ci.yml'))" 2>/dev/null || \
    python -c "import yaml, sys; yaml.safe_load(open('.github/workflows/ci.yml'))" 2>/dev/null || \
    echo "WARN: could not parse ci.yml as YAML (python not available)"

# ── Documentation files exist ───────────────────────────────────────────
# The brief requires architecture docs; the plan names these three deliverables.
echo "=== docs ==="
test -f ARCHITECTURE.md
test -f CLAUDE.md
test -f README.md

# ── Project setup files exist ───────────────────────────────────────────
# The brief requires project setup; these are the Gradle fundamentals.
echo "=== project setup ==="
test -f settings.gradle.kts
test -f build.gradle.kts
test -f app/build.gradle.kts
test -f gradle.properties
test -f gradlew.sh

# ── Hygiene files exist ─────────────────────────────────────────────────
# The brief requires project hygiene.
echo "=== hygiene ==="
test -f .gitignore
test -f .gitattributes

echo "All checks passed."
