#!/usr/bin/env bash
# Done means the project gate passes through the committed Gradle wrapper, exactly as CI
# runs it, and the APK it builds is the Liftoff app.
# Run from the repo root with bash (Git Bash on Windows).
set -euo pipefail

# The JDK and SDK are not on PATH on this machine; CI's runner provides its own.
export JAVA_HOME="${JAVA_HOME:-C:/Users/corpo/android-toolchain/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-C:/Users/corpo/android-toolchain/sdk}"

apk=app/build/outputs/apk/debug/app-debug.apk
rm -f "$apk"

# ── Gate: the command CI runs (DESIGN.md §13), through the wrapper ──────
echo "=== gate ==="
bash ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest

# ── The wrapper must be executable on the Linux CI runner ───────────────
echo "=== gradlew executable bit ==="
mode=$(git ls-files -s gradlew | awk '{print $1}')
if [[ "$mode" != "100755" ]]; then
    echo "FAIL: gradlew is tracked with mode '${mode:-untracked}', CI's ./gradlew needs 100755"
    exit 1
fi

# ── The built APK is the Liftoff app (DESIGN.md §11) ────────────────────
echo "=== apk ==="
if [[ ! -s "$apk" ]]; then
    echo "FAIL: assembleDebug did not produce $apk"
    exit 1
fi

aapt2=""
for d in $(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V -r); do
    for f in "$d"aapt2.exe "$d"aapt2; do
        if [[ -x "$f" ]]; then aapt2="$f"; break 2; fi
    done
done
if [[ -z "$aapt2" ]]; then
    echo "FAIL: no aapt2 found under $ANDROID_HOME/build-tools"
    exit 1
fi

badging=$("$aapt2" dump badging "$apk" | tr -d '\r')

expect() {
    if ! grep -qE "$1" <<<"$badging"; then
        echo "FAIL: APK $2"
        echo "$badging" | head -40
        exit 1
    fi
}
expect "^package: name='com\.liftoff\.app'" "package is not com.liftoff.app"
expect "^application-label:'Liftoff'" "label is not Liftoff"
expect "^launchable-activity: name='com\.liftoff\.app\." "has no launcher activity in com.liftoff.app"
expect "^uses-permission: name='android\.permission\.INTERNET'" "does not request INTERNET"
# aapt2 prints minSdkVersion; legacy aapt printed sdkVersion.
expect "^(minSdkVersion|sdkVersion):'26'$" "minSdk is not 26"
expect "^targetSdkVersion:'35'" "targetSdk is not 35"

echo "All checks passed."
