#!/usr/bin/env bash
# Standalone framework-only diagnostic. No Gradle, dependency download, or device access.
set -euo pipefail

diagnostic_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$diagnostic_dir/../.." && pwd)"
jdk_dir="${PETASOS_JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
sdk_dir="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
tools_dir="$sdk_dir/build-tools/35.0.0"
android_jar="$sdk_dir/platforms/android-36/android.jar"
output_dir="$project_dir/.local/diagnostics/navigation-overlay"
keystore="$project_dir/.local/android/debug.keystore"

for required in "$jdk_dir/bin/javac" "$tools_dir/aapt2" "$tools_dir/d8" \
    "$tools_dir/zipalign" "$tools_dir/apksigner" "$android_jar" "$keystore"; do
    if [[ ! -f "$required" ]]; then
        echo "Required local Android diagnostic build tool or debug keystore is missing." >&2
        exit 1
    fi
done

export JAVA_HOME="$jdk_dir"
export PATH="$jdk_dir/bin:$PATH"
mkdir -p "$output_dir/classes" "$output_dir/dex"
# This output directory is solely owned by this diagnostic builder.
find "$output_dir/classes" -type f -name '*.class' -delete
find "$output_dir/dex" -type f -name '*.dex' -delete

"$jdk_dir/bin/javac" -J-Xmx256m --release 8 \
    -classpath "$android_jar" -d "$output_dir/classes" \
    "$diagnostic_dir/src/com/petasos/diagnostics/overlay/OverlayDiagnosticActivity.java"
"$jdk_dir/bin/jar" cf "$output_dir/classes.jar" -C "$output_dir/classes" .
"$tools_dir/d8" -JXmx256m --min-api 30 --lib "$android_jar" \
    --output "$output_dir/dex" "$output_dir/classes.jar"
"$tools_dir/aapt2" link -I "$android_jar" --manifest "$diagnostic_dir/AndroidManifest.xml" \
    -o "$output_dir/resources.apk"
python3 - "$output_dir" <<'PY'
from pathlib import Path
import sys
from zipfile import ZIP_DEFLATED, ZipFile

out = Path(sys.argv[1])
with ZipFile(out / 'resources.apk') as source, ZipFile(out / 'unsigned.apk', 'w', ZIP_DEFLATED) as target:
    for item in source.infolist():
        target.writestr(item, source.read(item.filename))
    target.write(out / 'dex/classes.dex', 'classes.dex')
PY
"$tools_dir/zipalign" -f 4 "$output_dir/unsigned.apk" "$output_dir/aligned.apk"
# Android's standard local debug key, never a production signing credential.
"$tools_dir/apksigner" -JXmx256m sign --ks "$keystore" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out "$output_dir/navigation-overlay-diagnostic.apk" "$output_dir/aligned.apk"
"$tools_dir/apksigner" -JXmx256m verify "$output_dir/navigation-overlay-diagnostic.apk"
"$tools_dir/aapt2" dump permissions "$output_dir/navigation-overlay-diagnostic.apk"
shasum -a 256 "$output_dir/navigation-overlay-diagnostic.apk"
