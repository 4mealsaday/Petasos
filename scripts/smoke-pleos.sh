#!/usr/bin/env bash
# Build first, then run: ./scripts/smoke-pleos.sh [emulator-5554] [--build-type debug|privacy]
# Optional overrides: ANDROID_SDK_ROOT, ANDROID_HOME, PETASOS_APK, PETASOS_ARTIFACTS_DIR.
set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
serial=emulator-5554
build_type=debug
serial_supplied=false
package=com.petasos.app
component=$package/.MainActivity
adb=
app_uid=
log_start=

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --build-type)
            [ "$#" -ge 2 ] || fail 'Missing --build-type value.'
            build_type=$2; shift 2 ;;
        emulator-*)
            [ "$serial_supplied" = false ] || fail 'Only one emulator serial is supported.'
            serial=$1; serial_supplied=true; shift ;;
        *) fail 'Usage: smoke-pleos.sh [emulator-5554] [--build-type debug|privacy]' ;;
    esac
done
case "$build_type" in debug|privacy) ;; *) fail 'Build type must be debug or privacy.' ;; esac
expected_apk=$project_root/app/build/outputs/apk/$build_type/app-$build_type.apk
apk=${PETASOS_APK:-$expected_apk}
if [ "$build_type" = privacy ]; then
    [ "$apk" = "$expected_apk" ] || fail 'Privacy smoke requires the exact privacy APK path.'
    artifacts=${PETASOS_ARTIFACTS_DIR:-$project_root/artifacts/milestone-12/privacy-smoke}
else
    artifacts=${PETASOS_ARTIFACTS_DIR:-$project_root/artifacts/milestone-1}
fi
case "$serial" in
    emulator-*)
        emulator_port=${serial#emulator-}
        case "$emulator_port" in
            ''|*[!0-9]*) fail 'The serial must be emulator- followed by digits.' ;;
        esac
        ;;
    *) fail 'Only an explicitly selected emulator serial is allowed.' ;;
esac

local_sdk=
if [ -f "$project_root/local.properties" ]; then
    local_sdk=$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' \
        "$project_root/local.properties" | sed -n '1p' | sed 's/\\\(.\)/\1/g' | tr -d '\r')
fi
for sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$local_sdk" "$HOME/Library/Android/sdk"; do
    if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then
        adb=$sdk/platform-tools/adb
        break
    fi
done
[ -n "$adb" ] || fail 'adb was not found. Set ANDROID_SDK_ROOT or sdk.dir in local.properties.'
[ -f "$apk" ] || fail "APK not found for selected build type: $build_type. Build the matching app variant first."

device_state=$("$adb" -s "$serial" get-state 2>/dev/null) || fail "$serial is not connected. Start the Pleos emulator first."
[ "$device_state" = device ] || fail "$serial is not ready (state: $device_state)."
fingerprint=$("$adb" -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')
case "$(printf '%s' "$fingerprint" | tr '[:upper:]' '[:lower:]')" in
    *pleos*) ;;
    *) fail "Selected emulator does not identify as Pleos: $fingerprint" ;;
esac
api_level=$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
[ "$api_level" = 34 ] || fail "Expected Pleos API 34, found API $api_level."
user_id=$("$adb" -s "$serial" shell am get-current-user | tr -d '\r')
case "$user_id" in
    ''|*[!0-9]*) fail 'Could not determine the current Android user.' ;;
esac

mkdir -p "$artifacts"
{
    printf 'checked_at_utc=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf 'serial=%s\nfingerprint=%s\napi_level=%s\nandroid_user=%s\n' "$serial" "$fingerprint" "$api_level" "$user_id"
    printf 'package=%s\ncomponent=%s\napk=%s\n' "$package" "$component" "$apk"
    printf 'build_type=%s\napk_sha256=%s\n' "$build_type" "$(shasum -a 256 "$apk" | cut -d ' ' -f 1)"
    printf 'scope=APK install and launch only; SDK integrations are tested separately\n'
} > "$artifacts/verification.txt"

# Filter by the installed app UID, including crashes after its process exits.
# Never clear logcat or retain SDK debug logs, which may include vendor speech payloads.
finish() {
    result=$?
    trap - EXIT
    if [ -n "$app_uid" ] && [ -n "$log_start" ]; then
      if [ "$build_type" = privacy ]; then
        if ! "$adb" -s "$serial" logcat -d -B -b main -b system -b crash --uid="$app_uid" \
            -T "$log_start.000" '*:V' 2>/dev/null \
            | python3 "$project_root/scripts/audit-speech-logs.py" summarize-crashes --output "$artifacts/crash-counts.json"; then
            printf 'ERROR: Privacy smoke crash observation failed or was inconclusive.\n' >&2
            result=1
        fi
      else
        if ! "$adb" -s "$serial" logcat -d -b main -b system --uid="$app_uid" \
            -T "$log_start.000" -v threadtime 'PetasosSummary:E' 'AndroidRuntime:F' '*:S' > "$artifacts/app.log" 2> "$artifacts/app-logcat-error.txt"; then
            printf 'ERROR: Could not capture app-filtered logcat.\n' >&2
            result=1
        fi
        if ! "$adb" -s "$serial" logcat -d -b crash --uid="$app_uid" \
            -T "$log_start.000" -v threadtime 'AndroidRuntime:F' 'libc:F' 'DEBUG:F' '*:S' > "$artifacts/crash.log" 2> "$artifacts/crash-logcat-error.txt"; then
            printf 'ERROR: Could not capture app-filtered crash logcat.\n' >&2
            result=1
        fi
        if grep -Eq 'FATAL EXCEPTION|Fatal signal|Abort message' "$artifacts/app.log" "$artifacts/crash.log"; then
            printf 'ERROR: App crash evidence was found in the captured logs.\n' >&2
            result=1
        fi
      fi
    fi
    if [ "$result" -eq 0 ]; then
        printf 'result=PASS\n' >> "$artifacts/verification.txt"
        printf 'PASS: Petasos installed and resumed on %s (Android user %s).\nEvidence: %s\n' "$serial" "$user_id" "$artifacts"
    else
        printf 'result=FAIL\n' >> "$artifacts/verification.txt"
        printf 'Verification failed. Evidence: %s\n' "$artifacts" >&2
    fi
    exit "$result"
}
trap finish EXIT

printf 'Installing Petasos on %s for Android user %s...\n' "$serial" "$user_id"
if [ "$build_type" = privacy ]; then
    install_result=$("$adb" -s "$serial" install --user "$user_id" -r "$apk" 2>/dev/null) || fail 'Privacy installation failed.'
    printf '%s\n' "$install_result" | grep -q '^Success' || fail 'Privacy install did not report success.'
    unset install_result
    printf 'installation=confirmed\n' > "$artifacts/install.log"
else
    if ! "$adb" -s "$serial" install --user "$user_id" -r "$apk" > "$artifacts/install.log" 2>&1; then
        fail "Installation failed; see $artifacts/install.log"
    fi
    grep -q '^Success' "$artifacts/install.log" || fail "Install did not report success; see $artifacts/install.log"
fi

app_uid=$("$adb" -s "$serial" shell cmd package list packages -U --user "$user_id" "$package" \
    | tr -d '\r' | awk -v target="package:$package" '$1 == target { for (i=2; i<=NF; i++) if ($i ~ /^uid:/) { sub(/^uid:/, "", $i); print $i } }')
case "$app_uid" in
    ''|*[!0-9]*) fail 'Could not determine the installed app UID for the current Android user.' ;;
esac
log_start=$("$adb" -s "$serial" shell date +%s | tr -d '\r')
case "$log_start" in
    ''|*[!0-9]*) fail 'Could not read the emulator clock for scoped log capture.' ;;
esac
printf 'app_uid=%s\nlog_start_epoch_seconds=%s\n' "$app_uid" "$log_start" >> "$artifacts/verification.txt"

"$adb" -s "$serial" shell am force-stop --user "$user_id" "$package"
if [ "$build_type" = privacy ]; then
    launch_result=$("$adb" -s "$serial" shell am start -W --user "$user_id" -n "$component" 2>/dev/null) || fail 'Privacy activity launch failed.'
    printf '%s\n' "$launch_result" | grep -q '^Status: ok' || fail 'Privacy activity did not report Status: ok.'
    unset launch_result
    printf 'launch=confirmed\n' > "$artifacts/launch.log"
else
    if ! "$adb" -s "$serial" shell am start -W --user "$user_id" -n "$component" > "$artifacts/launch.log" 2>&1; then
        fail "Activity launch failed; see $artifacts/launch.log"
    fi
    grep -q '^Status: ok' "$artifacts/launch.log" || fail "Activity did not report Status: ok; see $artifacts/launch.log"
fi
sleep 1

app_pid=$("$adb" -s "$serial" shell ps -A -o PID,UID,NAME | tr -d '\r' \
    | awk -v uid="$app_uid" -v app="$package" '$2 == uid && $3 == app { print $1 }')
[ -n "$app_pid" ] || fail 'The Petasos process is not running for the current Android user.'
resumed=$("$adb" -s "$serial" shell dumpsys activity activities | tr -d '\r' \
    | awk -v target="$component" -v user="u$user_id " '/mResumedActivity|topResumedActivity/ && index($0, target) && index($0, user) { print }')
[ -n "$resumed" ] || fail 'MainActivity is not the resumed activity for the current Android user.'
printf 'app_pid=%s\nresumed_activity=%s\n' "$app_pid" "$resumed" >> "$artifacts/verification.txt"

if [ "$build_type" = privacy ]; then
    # Results UI may contain an existing personal summary, even without any new microphone input.
    printf 'screenshot=omitted_in_privacy_smoke\n' >> "$artifacts/verification.txt"
else
    if ! "$adb" -s "$serial" exec-out screencap -p > "$artifacts/petasos-emulator.png"; then
        fail 'Could not capture the resumed Petasos screen.'
    fi
    [ -s "$artifacts/petasos-emulator.png" ] || fail 'The emulator screenshot is empty.'
fi
