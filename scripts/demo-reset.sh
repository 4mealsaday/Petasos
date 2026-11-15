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
#   1. ends an open trip through the UI   (a trip killed mid-music leaves a musicCheckpoint that
#                                          keeps the next run in IDLE — see PlaybackLookahead)
#   2. stops the app                      (so the reset is not racing playback)
#   3. clears listening + collection history
#   4. compares the device DB with the content pack (see the pm clear note below)
#   5. restores the scenario's origin     (adb emu geo fix)
#   6. raises the car volume to maximum   (resets to 20/45 on every boot)
#   7. starts the app
#   8. re-enables video mode              (one representative story per region)
#
# It does NOT re-provision the server token: that survives a reinstall but NOT `pm clear`. If
# answers fail with "연결할 수 없어요", re-run the token steps in README.md ("AI 대화용 로컬 서버" 6).
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

# Report whether a Petasos button with this label is on screen, without touching it.
has_label() {
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
    if sys.argv[1] in text and "petasos" in pkg and 'clickable="true"' in t:
        sys.exit(0)
sys.exit(1)
PY
}

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

echo "[1/8] 열려 있는 동행 정상 종료"
# A trip killed mid-music leaves a musicCheckpoint, and PlaybackLookahead only bootstraps the
# first story when `state.musicCheckpoint == null`. Force-stopping does NOT clear it, so an
# unfinished trip makes every later run sit in IDLE with target=null and play nothing.
"$ADB" shell am start --user "$USER_ID" -n "$PKG/.MainActivity" >/dev/null 2>&1
# Wait for the home controls to render. Right after `adb install` the first draw takes longer than
# a fixed sleep, and a missed tap leaves the trip open -> the next drive plays nothing at all.
ended=no
i=0
while [ "$i" -lt 8 ]; do
    i=$((i + 1))
    sleep 3
    if has_label "동행 시작"; then
        echo "      열린 동행 없음"
        ended=yes
        break
    fi
    if tap_label "동행 끝내기"; then
        echo "      '동행 끝내기' 눌렀음 (확인 한 번 더)"
        sleep 4
        tap_label "동행 끝내기" || true
        sleep 4
        ended=yes
        break
    fi
done
if [ "$ended" = no ]; then
    echo "      ⚠️  홈 화면을 읽지 못했습니다. 앱에서 직접 '동행 끝내기'를 누른 뒤 다시 실행하세요." >&2
    echo "      ⚠️  열린 동행이 남으면 다음 주행은 아무 이야기도 재생하지 않습니다." >&2
fi

echo "[2/8] 앱 종료"
"$ADB" shell am force-stop --user "$USER_ID" "$PKG"
sleep 2

echo "[3/8] 청취·수집 기록 초기화"
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

echo "[4/8] 기기 DB와 팩 대조"
# `adb install` keeps app data and the content installer never overwrites a pack that is already
# installed, so new stories silently never reach the DB. Nothing is logged when that happens: the
# region just plays no story. Compare the two and say so loudly.
ADB="$ADB" PACK="$ROOT/app/src/main/assets/content/packs" python3 - <<'PY' || true
import glob, json, os, sqlite3, subprocess, tempfile
adb, packs = os.environ["ADB"], os.environ["PACK"]
tmp = tempfile.mkdtemp()
db = os.path.join(tmp, "petasos.db")
for ext in ("", "-wal", "-shm"):
    with open(db + ext, "wb") as f:
        subprocess.run([adb, "shell", "run-as", "com.petasos.app", "--user", "10", "cat",
                        f"/data/user/10/com.petasos.app/databases/petasos.db{ext}"],
                       stdout=f, stderr=subprocess.DEVNULL, timeout=120)
if not os.path.getsize(db):
    print("      (DB 없음 — 앱이 아직 한 번도 안 떴습니다)")
    raise SystemExit
installed = {r[0] for r in sqlite3.connect(db).execute("select story_id from story")}
expected = set()
for path in glob.glob(os.path.join(packs, "*.json")):
    expected |= {s["storyId"] for s in json.load(open(path))["stories"]}
missing = expected - installed
if missing:
    print(f"      ⚠️  팩의 이야기 {len(missing)}편이 DB에 없습니다. 그 지역은 아무 소리 없이 건너뛰어집니다.")
    print("      ⚠️  콘텐츠를 바꿨다면:  adb shell pm clear --user 10 com.petasos.app")
    print("      ⚠️  그 뒤 권한·온보딩·서버 토큰을 다시 설정하세요 (01-실행절차.md 5·6장).")
else:
    print(f"      확인: 팩의 이야기 {len(expected)}편이 모두 DB에 있습니다")
PY

echo "[5/8] 출발 위치 복귀: ${NAME:-origin} ($LON, $LAT)"
"$ADB" emu geo fix "$LON" "$LAT" >/dev/null

echo "[6/8] 차량 볼륨 최대"
"$ADB" shell 'for i in $(seq 1 50); do input keyevent 24; done'
"$ADB" shell dumpsys car_service --services CarAudioService 2>/dev/null \
    | grep -m1 "Gain indexes" | sed 's/^ */      /'

echo "[7/8] 앱 시작"
"$ADB" shell am start --user "$USER_ID" -n "$PKG/.MainActivity" >/dev/null
sleep 5

echo "[8/8] 영상 모드 켜기"
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
