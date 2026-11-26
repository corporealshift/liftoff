#!/usr/bin/env bash
# Runs Gradle with the JDK, SDK and Gradle pinned under ~/android-toolchain
# (C:/Users/corpo/android-toolchain on Windows), none of which are on PATH.
# Without that toolchain it falls back to the wrapper, which is what CI runs.
set -euo pipefail
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) toolchain="C:/Users/corpo/android-toolchain" ;;
    *) toolchain="$HOME/android-toolchain" ;;
esac
# Only point at directories that exist: a JAVA_HOME naming nothing breaks
# every launcher, the wrapper included.
if [ -d "$toolchain/jdk" ]; then export JAVA_HOME="${JAVA_HOME:-$toolchain/jdk}"; fi
if [ -d "$toolchain/sdk" ]; then export ANDROID_HOME="${ANDROID_HOME:-$toolchain/sdk}"; fi
if [ -x "$toolchain/gradle/bin/gradle" ]; then
    exec "$toolchain/gradle/bin/gradle" "$@"
fi
exec "$(dirname "$0")/gradlew" "$@"
