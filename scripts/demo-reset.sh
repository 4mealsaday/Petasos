#!/bin/sh
# Reset the running emulator to a clean demo start.
#
#     scripts/demo-reset.sh [scenarioId]        # default: cheonan-kaist
#
# Why this exists: `adb install` over an existing build KEEPS app data, so the listening
# history survives a reinstall and every already-heard story is silently skipped
# (the app sits in mode=IDLE and nothing plays). An emulator reboot does not clear it
# either. Run this before every demo drive.
#
# It does, in order:
#   1. stops the app                      (so the reset is not racing playback)
#   2. clears listening + collection history
#   3. restores the scenario's origin     (adb emu geo fix)
#   4. raises the car volume to maximum   (resets to 20/45 on every boot)
#   5. re-enables video mode              (one representative story per region)
#   6. starts the app
#
# It does NOT re-provision the server token: that survives a reinstall. If answers fail
# with "연결할 수 없어요", re-run the token steps in demo/seoul-daejeon-demo/01-실행절차.md.
set -eu

SCENARIO="${1:-cheonan-kaist}"
USER_ID="${PETASOS_DEMO_USER:-10}"
PKG="com.petasos.app"
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SCENARIO_FILE="$ROOT/demo/scenarios/$SCENARIO/scenario.json"

if [ ! -f "$SCENARIO_FILE" ]; then
    echo "시나리오를 찾을 수 없습니다: $SCENARIO_FILE" >&2
    exit 1
fi

# adb is usually not on PATH in a plain shell; fall back to the SDK location.
if command -v adb >/dev/null 2>&1; then
    ADB=adb
elif [ -x "${ANDROID_HOME:-}/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
elif [ -x "${ANDROID_SDK_ROOT:-}/platform-tools/adb" ]; then
    ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
    ADB="$HOME/Library/Android/sdk/platform-tools/adb"
else
    echo "adb를 찾을 수 없습니다. ANDROID_HOME을 설정하거나 platform-tools를 PATH에 넣으세요." >&2
    exit 1
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "에뮬레이터가 연결돼 있지 않습니다 ($ADB devices)." >&2
    exit 1
fi

read_origin() {
    python3 - "$SCENARIO_FILE" "$1" <<'PY'
import json, sys
origin = json.load(open(sys.argv[1]))["origin"]
print(origin.get(sys.argv[2], ""))
PY
}
LON=$(read_origin longitude)
LAT=$(read_origin latitude)
NAME=$(read_origin name)

# Tap a Petasos button by its label. Returns 0 when it was found and tapped.
tap_label() {
    ADB="$ADB" python3 - "$1" <<'PY'
import os, re, subprocess, sys
adb = os.environ["ADB"]
def sh(*a):
    return subprocess.run([adb, *a], capture_output=True, text=True, timeout=60).stdout
sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
xml = sh("shell", "cat", "/sdcard/ui.xml")
for m in re.finditer(r"<node[^>]*>", xml):
    t = m.group(0)
    text = (re.search(r'text="([^"]*)"', t) or [None, ""])[1]
    pkg = (re.search(r'package="([^"]*)"', t) or [None, ""])[1]
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
    if sys.argv[1] in text and "petasos" in pkg and 'clickable="true"' in t and b:
        x1, y1, x2, y2 = map(int, b.groups())
        sh("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        sys.exit(0)
sys.exit(1)
PY
}

echo "[1/7] 열려 있는 동행 정상 종료"
# A trip killed mid-music leaves a musicCheckpoint, and PlaybackLookahead only bootstraps the
# first story when `state.musicCheckpoint == null`. Force-stopping does NOT clear it, so an
# unfinished trip makes every later run sit in IDLE with target=null and play nothing.
"$ADB" shell am start --user "$USER_ID" -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 4
if tap_label "동행 끝내기"; then
    echo "      '동행 끝내기' 눌렀음 (확인 한 번 더)"
    sleep 4
    tap_label "동행 끝내기" || true
    sleep 3
else
    echo "      열린 동행 없음"
fi

echo "[2/7] 앱 종료"
"$ADB" shell am force-stop --user "$USER_ID" "$PKG"
sleep 2

echo "[3/7] 청취·수집 기록 초기화"
"$ADB" shell am broadcast --user "$USER_ID" -f 0x20 -p "$PKG" \
    -a com.petasos.app.debug.RESET_LISTENING_HISTORY >/dev/null
sleep 3
if "$ADB" logcat -d -s DemoResetReceiver 2>/dev/null | tail -1 | grep -q "history reset$"; then
    echo "      확인: Demo listening history reset"
else
    echo "      ⚠️  초기화 확인 로그를 찾지 못했습니다. adb logcat -s DemoResetReceiver 로 확인하세요." >&2
fi
"$ADB" shell am force-stop --user "$USER_ID" "$PKG"
sleep 1

echo "[4/7] 출발 위치 복귀: ${NAME:-origin} ($LON, $LAT)"
"$ADB" emu geo fix "$LON" "$LAT" >/dev/null

echo "[5/7] 차량 볼륨 최대"
"$ADB" shell 'for i in $(seq 1 30); do input keyevent 24; done'
"$ADB" shell dumpsys car_service --services CarAudioService 2>/dev/null \
    | grep -m1 "Gain indexes" | sed 's/^ */      /'

echo "[6/7] 앱 시작"
"$ADB" shell am start --user "$USER_ID" -n "$PKG/.MainActivity" >/dev/null
sleep 5

echo "[7/7] 영상 모드 켜기"
"$ADB" shell am broadcast --user "$USER_ID" -f 0x20 -p "$PKG" \
    -a com.petasos.app.debug.SET_VIDEO_MODE --ez enabled true >/dev/null
sleep 2
"$ADB" logcat -d -s DemoResetReceiver 2>/dev/null | tail -1 | sed 's/^/      /'

cat <<EOF

준비 완료. 이제 앱에서:
  1. 세션이 열려 있으면 '동행 끝내기' → '동행 시작'
  2. 내비 경로 선택 화면이 뜨면 '안내 시작'
  3. 주행:  .local/server-venv/bin/python scripts/demo_route.py --scenario $SCENARIO --video
EOF
