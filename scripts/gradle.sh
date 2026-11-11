#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
is_jdk17() { [[ -x "$1/bin/java" ]] && [[ "$("$1/bin/java" -version 2>&1)" == *'version "17.'* ]]; }

# JDK 17 lookup: PETASOS_JAVA_HOME, then JAVA_HOME, then the macOS java_home helper, then Homebrew openjdk@17.
jdk_dir="${PETASOS_JAVA_HOME:-}"
if [[ -z "$jdk_dir" ]]; then
    candidates=("${JAVA_HOME:-}")
    [[ -x /usr/libexec/java_home ]] && candidates+=("$(/usr/libexec/java_home -v 17 2>/dev/null || true)")
    command -v brew >/dev/null 2>&1 && candidates+=("$(brew --prefix openjdk@17 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home")
    for candidate in "${candidates[@]}"; do
        if [[ -n "$candidate" ]] && is_jdk17 "$candidate"; then jdk_dir="$candidate"; break; fi
    done
fi

if [[ -z "$jdk_dir" || ! -x "$jdk_dir/bin/java" ]]; then
    echo "JDK 17 not found. Set PETASOS_JAVA_HOME (or JAVA_HOME) to a JDK 17 directory." >&2
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
