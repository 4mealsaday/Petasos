#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
jdk_dir="${PETASOS_JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

if [[ ! -x "$jdk_dir/bin/java" ]]; then
    echo "Set PETASOS_JAVA_HOME to a JDK 17 directory." >&2
    exit 1
fi

java_version="$("$jdk_dir/bin/java" -version 2>&1)"
if [[ "$java_version" != *'version "17.'* ]]; then
    echo "Petasos builds use JDK 17. Set PETASOS_JAVA_HOME to JDK 17." >&2
    exit 1
fi

cd "$project_dir"
# Keep downloaded dependencies and the local debug keystore out of source control.
export JAVA_HOME="$jdk_dir"
export GRADLE_USER_HOME="$project_dir/.gradle-user-home"
export ANDROID_USER_HOME="$project_dir/.local/android"
mkdir -p "$ANDROID_USER_HOME"
exec ./gradlew --no-daemon "$@"
