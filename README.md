<h1 align="center">
  <img src="docs/design/petasos-logo.svg" width="34" height="34" alt="">
  Petasos
</h1>

<p align="center">
  <a href="https://developer.android.com/training/cars"><img alt="Platform" src="https://img.shields.io/badge/platform-Android%20Automotive%20API%2034-3DDC84?style=flat-square&logo=android&logoColor=white"></a>
  <a href="https://document.pleos.ai/"><img alt="Pleos Connect" src="https://img.shields.io/badge/Pleos%20Connect-2.1.0-0078FF?style=flat-square"></a>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white">
  <img alt="Gradle" src="https://img.shields.io/badge/Gradle-8.11.1-02303A?style=flat-square&logo=gradle&logoColor=white">
  <img alt="Python" src="https://img.shields.io/badge/Python-3.11-3776AB?style=flat-square&logo=python&logoColor=white">
</p>

Petasos는 차량 내비게이션 위에서 **지금 지나는 지역의 이야기를 들려주는** Pleos Android 동행 앱입니다.
내비 화면 위에 뜨는 말하기 버튼과 자막 카드로 지도를 가리지 않고 듣다가, 궁금하면 버튼을 눌러 AI에게 물어볼 수 있고, 길가에 보이는 곳은 짧게 알려 줍니다.
이동이 끝나면 지나온 길과 들은 이야기가 "내 여행" 지도에 남습니다.
시연 경로는 **천안역 → (한빛탑 경유) → KAIST 본원**이고, 이 한 경로에 이야기 4편·음악 1곡·창밖 안내 1곳·질문 응답을 모두 담았습니다.
아래 "데모 시연"이 그 경로를 처음부터 끝까지 재현하는 절차입니다.

| 홈 | 내비 위 표시 | 내 여행 |
| --- | --- | --- |
| <img src="docs/screenshots/home.png" width="320"> | <img src="docs/screenshots/overlay.png" width="320"> | <img src="docs/screenshots/my-trips.png" width="320"> |

## 무엇을 하는 앱인가

내비게이션은 "어디로 가는지"는 알려 주지만 "지금 어디를 지나고 있는지"는 말해 주지 않습니다. Petasos는 그 빈자리를 채웁니다. 차가 시·군·구 경계를 넘을 때마다 그 지역의 이야기를 한 편씩 들려주고, 궁금한 것은 말로 물어볼 수 있습니다.

| 경험 | 하는 일 |
| --- | --- |
| **지역 이야기** | 지나는 시·군·구마다 30초 남짓한 이야기 한 편. 문단 단위로 읽어 자막과 소리가 같이 넘어갑니다 |
| **질문** | 내비 위 말하기 버튼 → 음성 질문 → 음성·자막 답변 → 듣던 자리로 복귀 |
| **창밖 안내** | 랜드마크 옆을 지날 때 "오른쪽 앞으로 한빛탑이 보여요" 한 줄을 끼워 넣고 원래 재생으로 돌아옵니다 |
| **내 여행** | 지나온 길, 이야기를 끝까지 들은 지역, 들은 이야기 카드가 지도에 남습니다 |

한 번의 이동에서 일어나는 일입니다.

```
동행 시작 ──→ 내비가 앞으로 나오고, 그 위에 말하기 버튼과 자막 카드가 뜹니다
   │
   ├─ 시·군·구가 바뀌면 ────→ 전환 멘트 → 그 지역 이야기 → 전환 멘트
   ├─ 이야기 사이 ─────────→ 음악 한 곡
   ├─ 랜드마크를 지날 때 ───→ 창밖 안내 한 줄 (끝나면 원래 재생으로)
   └─ 말하기 버튼 ─────────→ 질문 → 답변 → 듣던 자리로
   │
동행 끝내기 ─→ "내 여행"에 여정이 남습니다
```

**지켜야 했던 두 가지 원칙**이 설계의 대부분을 결정했습니다.

- **답변은 지어내지 않습니다.** AI 답변은 사용자가 **끝까지 들은 문단**에 들어 있는 사실만 근거로 씁니다. 앱이 서버 응답의 근거 ID가 실제로 들은 문단의 것인지 직접 검증하고, 아니면 버립니다. 그래서 이야기 도중에 물으면 아직 못 들은 내용은 답에 쓰이지 않습니다.
- **재생되는 콘텐츠는 모두 검수를 거칩니다.** 이야기·창밖 안내는 출처가 달린 콘텐츠 팩에서만, 음악은 권리 확인과 SHA-256이 기록된 번들에서만 나옵니다. 모델이 만든 문장이 그대로 재생되는 경로는 없습니다.

## 지금 구현된 것과 목킹한 것

기술적으로 아직 제약이 있어 **데모용으로 대체한 부분**이 있습니다. 어디까지가 실제로 도는 코드이고 어디부터가 대체인지 먼저 밝혀 둡니다.

| 기능 | 지금 동작 | 제한과 이유 | 실제 제품이라면 |
| --- | --- | --- | --- |
| **랜드마크 창밖 안내** | 콘텐츠 팩에 **미리 등록한 랜드마크** 좌표에 가까워지면 끼어듭니다 | 주행 중 창밖을 실시간으로 인식하는 것은 아직 어렵습니다 | 실시간 인식·POI 연동 |
| **음악** | APK에 번들한 **CC BY 4.0 무료 음원**, 데모는 1곡 35초 | 저작권·API 제약으로 스트리밍 서비스에 연결하지 못했습니다 | 사용자의 음악 서비스 연결 |
| **주변에 대한 질문** | 팩에 미리 담긴 **이야기 속 사실로만** 답합니다 | 네이버 지도의 주변 장소 검색은 아직 연동 전입니다 | 네이버 지도 API로 실시간 주변 검색 |
| **주행 위치** | `scripts/demo_route.py`가 에뮬레이터에 좌표를 주입합니다 | 실차 GPS 대신입니다 | 차량 위치를 그대로 |
| **내비 목적지** | 동행을 시작하면 앱이 코드에 고정된 좌표 하나를 내비에 요청합니다(`CompanionControls.requestDemoDestinationRoute`, "대전엑스포과학공원") | 앱에 목적지 선택 화면이 없고, 시연 경로를 매번 같게 만들기 위해서입니다 | 사용자가 내비에서 고른 목적지를 그대로 따라감 |

그래서 시연 중 내비 화면에는 목적지가 "대전엑스포과학공원"(한빛탑 좌표)으로 뜨고, GPS 주행은 그보다 조금 더 가서 KAIST 본원에서 끝납니다.

반대로 **대체하지 않은 것**도 적어 둡니다. 이야기 편성(지역 판정 → 선택 → 재생 → 기록), 질문 응답의 근거 검증, 내비 연동, "내 여행" 지도는 실제 코드가 돕니다. 네이버 지도는 "내 여행" 지도 표시(Maps SDK)와 **다음 지역 예측용 길찾기**(Directions API)에 이미 쓰고 있고, 연동하지 않은 것은 주변 장소 검색 쪽입니다. 이야기와 랜드마크 문구는 미리 생성·검수해 팩으로 묶은 것이고, 주행 중에 모델이 만들어 바로 읽는 경로는 없습니다.

## 설치와 실행

**필요한 것**: Pleos Connect 2.1.0 에뮬레이터(Android 14 / API 34, ARM64), Android SDK Platform-Tools(`adb`), Python 3. macOS(Apple Silicon) 기준입니다.

> 에뮬레이터 AVD를 만들 때 **기기 프로필을 `Pleos_Connect_v2_0`으로 고르셔야 합니다**(2560×1440, mdpi, 가로). 일반 Automotive 프로필(1024×768 등)로 만들면 Pleos UI가 깨져 보이고 앱 화면도 제대로 뜨지 않습니다. AVD 이름은 아래 명령과 같게 `Pleos_Connect_v2.1.0`으로 합니다.

**저장소를 먼저 clone 합니다.** 시연에 쓰는 reset 스크립트·주행 스크립트·경로 시나리오가 모두 저장소 안에 있어서, APK만 받아서는 데모를 돌릴 수 없습니다. clone 하면 제출용 APK(`apk/Petasos_Petasos_release.apk`, 약 80 MB)도 같이 받아집니다. 코드를 고쳐 빌드하시려면 아래 "소스에서 빌드하기"를 보시면 됩니다.

사용자 ID가 10이 아니면(`adb shell pm list users`로 확인) 아래 모든 `--user` 값을 바꿉니다. **1)·3)을 뺀 나머지는 에뮬레이터를 재부팅할 때마다 다시 합니다.**

```sh
# 1) 저장소 받기
git clone https://github.com/4mealsaday/Petasos.git && cd Petasos

# 2) 에뮬레이터 부팅 — 호스트 DNS가 막힌 뒤에는 내비가 "서비스 연결 중"에서 멈추므로 DNS를 지정합니다
~/Library/Android/sdk/emulator/emulator -avd Pleos_Connect_v2.1.0 -no-snapshot-load -no-boot-anim -dns-server 8.8.8.8,1.1.1.1 &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done

# 3) APK 설치 (이전 설치가 있으면 서명이 달라 먼저 지웁니다)
adb uninstall com.petasos.app 2>/dev/null
adb install --user 10 apk/Petasos_Petasos_release.apk

# 4) 내비 위 표시(overlay) 권한 — 둘 다 필요합니다
adb shell appops set --user 10 com.petasos.app SYSTEM_ALERT_WINDOW allow
adb shell pm grant --user 10 com.petasos.app android.permission.SYSTEM_ALERT_WINDOW

# 5) 에뮬레이터가 Mac 마이크를 입력으로 쓰도록 합니다
adb emu avd hostmicon

# 6) 출발 위치를 천안역에 맞춥니다
adb emu geo fix 127.1489 36.8099
```

7. **앱 실행** — 에뮬레이터 하단 바의 Petasos 아이콘, 또는 `adb shell am start --user 10 -n com.petasos.app/.MainActivity`.
8. **온보딩** — "계속하기" → 관심사 선택(또는 "건너뛰기"). **위치·마이크 권한은 반드시 앱 화면에서 허용해 주세요.** `pm grant`로 미리 주면 온보딩이 건너뛰어져 홈 화면의 조작부가 그려지지 않습니다.
9. **동행 시작** — 홈에서 누르고 권한 대화상자를 모두 허용합니다(오디오 녹음 → "알 수 없는 작업"(Pleos 권한) → 위치). 내비가 앞으로 나오고 오른쪽에 둥근 말하기 버튼, 아래에 자막 카드가 뜹니다.
10. **내비에서 "안내 시작"을 누릅니다.** 경로 후보 화면이 뜨면 반드시 눌러야 합니다. 내비가 안내 중이 아니면 앱이 지역 편성 대상을 잡지 못해 **아무 이야기도 나오지 않습니다**(내비 하단에 "안내 종료"가 보이면 안내 중입니다).
11. **소리** — 차량 볼륨은 부팅할 때마다 20/45로 돌아갑니다. 에뮬레이터 볼륨 버튼으로 올리시면 됩니다(아래 reset 스크립트가 매번 해 줍니다). Mac 출력 볼륨도 85% 이상으로 두세요 — 낮으면 음성 인식도 되지 않습니다.

## 데모 시연

시연 경로는 **천안역 → 한빛탑 → KAIST 본원**이고, 이야기 4편·음악 1곡·창밖 안내 1곳·질문 응답이 이 한 경로에 모두 들어 있습니다. `--video` 프리셋으로 약 7분입니다.

**주행 전에 reset을 돌립니다.** 청취 기록을 지우고, 출발 위치와 볼륨을 되돌리고, 지역당 이야기 한 편만 나오는 데모 모드를 켭니다. 이미 들은 이야기는 다시 나오지 않으므로 **다시 시연할 때마다 필요합니다.**

```sh
./scripts/demo-reset.sh
```

그다음 앱에서 **동행 시작** → 내비에서 **안내 시작**을 누르고, 주행 스크립트를 실행합니다.

```sh
python3 scripts/demo_route.py --scenario cheonan-kaist --video
```

### 장면별 진행

| # | 위치 | 들리는 것 | 시연자가 할 일 |
| --- | --- | --- | --- |
| 1 | 천안 동남구 | 전환 멘트 → 이야기 "천안삼거리에서 갈라진 길"(32초) → 전환 멘트 | — |
| 2 | — | 음악 (Prelude No. 1, 35초) | — |
| 3 | 청주 흥덕구 | 전환 멘트 → 이야기 "운천동 흥덕사 터"(33초) → 전환 멘트 | — |
| 4 | — | 음악 | — |
| 5 | 대전 대덕구 | 전환 멘트 → 이야기 "신탄진 길목의 식당들"(37초) | 이야기가 **끝난 뒤** 말하기 버튼을 누르고 **"아까 그 칼국수집, 어디 있다고 했지?"** 라고 묻습니다 |
| 6 | — | 음악 | — |
| 7 | 대전 유성구 (한빛탑 앞) | 재생 중인 이야기나 음악에 🗼 **"오른쪽 앞으로 한빛탑이 보여요. 1993년 대전엑스포를 기념해…"** 가 끼어들고 원래 재생으로 복귀합니다. 이어서 이야기 "박람회가 남긴 과학공원"(36초) | — |
| 8 | KAIST 본원 도착 | — | 홈에서 **동행 끝내기** → **내 여행**에서 지나온 길과 이야기 카드를 보여 줍니다 |

**말하기(5번)**: 오른쪽 둥근 버튼을 누릅니다. 이야기 중이면 현재 문장이 끝난 뒤, 아니면 바로 **말씀해 주세요 → 듣는 중… → 인식한 질문 → 답변 자막** 순으로 나오고, 답변이 끝나면 듣던 자리로 돌아옵니다. 답변은 **끝까지 들은 문단에 있는 사실만** 근거로 쓰므로 질문은 이야기가 끝난 뒤에 하셔야 답이 나옵니다. 아래 "질문 응답용 로컬 서버"를 띄우지 않으셨다면 "지금은 답변 서버에 연결할 수 없어요."가 나온 뒤 돌아옵니다.

**내비 화면을 가리지 않으려면**: ⚙ 설정 → **내비 위 자막**을 끄시면 됩니다. 말하기 버튼은 그대로 남습니다.

## 질문 응답용 로컬 서버

음성 질문에 대한 답변은 Mac에서 도는 로컬 HTTPS 서버(`server/`)가 OpenRouter를 호출해 만듭니다. **서버가 없어도 이야기·음악·창밖 안내·내 여행은 모두 동작하고, 질문에만 "지금은 답변 서버에 연결할 수 없어요."가 나옵니다.** 쓰시려면 Python 3.11 이상과 OpenRouter API 키가 필요합니다.

```sh
python3.11 -m venv .local/server-venv && .local/server-venv/bin/pip install -r server/requirements.txt
cp .env.example .env                                   # PETASOS_OPENROUTER_API_KEY 를 채웁니다 (.env는 git에 올라가지 않습니다)
python3.11 server/provision-local.py --directory .local/server        # 기기 토큰·설정 생성 (토큰은 24시간 유효)

# 답변의 근거 파일 — 기기에 설치된 콘텐츠 팩에서 뽑습니다. 팩이 다르면 어떤 질문에도 답이 나오지 않습니다.
mkdir -p .local/content/bundles .local/content/evidence
chmod 700 .local .local/content .local/content/bundles .local/content/evidence
cp app/src/main/assets/content/packs/cheonan-kaist.json .local/content/bundles/ && chmod 600 .local/content/bundles/cheonan-kaist.json
PYTHONPATH=server .local/server-venv/bin/python scripts/build-content-bundle.py \
  --evidence-from-bundle .local/content/bundles/cheonan-kaist.json \
  --allow-device-id local-development-device --output .local/content/evidence/cheonan-kaist.json

PYTHONPATH=server .local/server-venv/bin/python server/dev-local-https.py prepare    # 로컬 개발 인증서
```

그다음 `.local/server/config.json`에서 `evidenceFile`을 방금 만든 파일의 절대 경로로 바꾸고 `provider`를 채웁니다. **OpenRouter 계정의 Privacy 설정(ZDR only, 입출력 로깅 OFF)을 직접 확인하신 뒤에만** `accountContentLoggingDisabled`를 `true`로 두세요 — 서버는 이 값만 믿고 호출합니다.

서버를 띄우고 토큰과 CA를 에뮬레이터에 전달합니다. 토큰은 인자·인텐트로 넘기지 않고 파일로만 전달하며, 앱이 읽어 암호화 저장소로 옮깁니다.

```sh
PYTHONPATH=server .local/server-venv/bin/python server/dev-local-https.py serve --port 8443 &
curl -sk https://127.0.0.1:8443/v1/health          # {"status":"ok",...}
adb reverse tcp:8443 tcp:8443                       # 에뮬레이터를 재부팅할 때마다 다시 실행합니다

adb shell "rm -rf /data/local/tmp/petasos-demo; mkdir -p /data/local/tmp/petasos-demo"
adb push .local/server/device-provisioning.token /data/local/tmp/petasos-demo/demo-server.token
adb push .local/server/dev-tls/ca.pem /data/local/tmp/petasos-demo/demo-server-ca.pem
adb shell chmod -R a+rX /data/local/tmp/petasos-demo
adb shell am start --user 10 -n com.petasos.app/.MainActivity      # 앱이 실행·재개될 때 파일을 읽습니다
adb shell rm -r /data/local/tmp/petasos-demo                        # 읽은 뒤 평문 토큰을 지웁니다
```

앱은 이 CA를 `localhost` 연결에만 신뢰합니다. 답변이 "잘 못 들었어요"로 돌아오면 `adb logcat -s PetasosTalk`에 이유가 한 줄로 찍힙니다(`AUTHORIZATION`이면 24시간짜리 기기 토큰이 만료된 것입니다).

## 소스에서 빌드하기

| 도구 | 버전 |
| --- | --- |
| JDK | 17 |
| Android SDK | Platform 36(`android-36`), Build-Tools 35.0.0, Platform-Tools, Emulator |
| Gradle | 8.11.1 (AGP 8.10.1, Kotlin 2.2.21) — `./gradlew`가 알아서 받습니다 |

Pleos SDK(Maven)는 빌드가 `nexus-playground.pleos.ai`에서 받습니다. 저장소는 "설치와 실행" 1)에서 이미 받으셨다고 보고 이어집니다.

```sh
# 1) local.properties — Android SDK 위치(비추적 파일). 환경변수 ANDROID_HOME으로 대신해도 됩니다.
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 2) JDK 17 지정 후 debug 빌드 — 처음에는 의존성을 받느라 몇 분 걸립니다
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk

# 3) 설치 — 저장소의 release APK와 서명이 달라 서로 덮어쓸 수 없으므로 먼저 지웁니다
adb uninstall com.petasos.app 2>/dev/null
adb install --user 10 app/build/outputs/apk/debug/app-debug.apk
# 이후 권한·온보딩·시연은 "설치와 실행" 4)부터와 같습니다
```

지도 키가 담긴 `.env`가 없어도 빌드·실행됩니다(내장 지도로 대신 뜹니다). 빌드 유형은 debug/release 둘뿐이고 **둘 다 `BuildConfig.DEMO=true`**입니다. release 서명 키(`.local/android/`)는 개발자 개인 키라 없으면 debug만 빌드됩니다. 테스트는 `./gradlew :app:testDebugUnitTest`로 돌립니다.

**코드를 보실 때**

- `playback/`: 이야기·말하기·복귀·음악 상태기계(`PlaybackCoordinator`). 이 앱의 중심입니다.
- `planning/`, `location/`, `content/`: 지역 편성, 시·군·구 판정, 창밖 안내 판단(`WindowLandmarks`), 콘텐츠 검증.
- `platform/`: Pleos TTS/STT·내비·위치 어댑터, 미디어 세션, `overlay/CompanionOverlay`(내비 위 표시), `runtime/PetasosSessionHost`(전체 조립).
- `storage/`, `presentation/`: Room 저장소와 홈·내 여행 화면.
- `server/`: 답변 생성 서버(Python ASGI).
- 콘텐츠를 새로 만드는 방법은 `docs/CONTENT_PIPELINE.md`에, 제품·동작 명세는 `docs/Petasos_제품명세.md`·`docs/Petasos_동작명세.md`에 있습니다.

## 사용한 Pleos SDK·Android API

| 구분 | API | 핵심 경험에서의 역할 |
| --- | --- | --- |
| Pleos SDK | `TextToSpeech` 3.3.1 (ON_DEVICE) | 이야기·답변·창밖 안내를 문장 단위로 읽어 자막과 소리를 맞춥니다 |
| Pleos SDK | `SpeechToText` 3.3.1 | 말하기 버튼으로 들어온 질문을 글자로 바꿉니다(호스트 마이크 PCM 입력) |
| Pleos SDK | `NaviHelper` 3.0.5 | 동행을 켜면 내비를 앞으로 가져오고, 목적지 경로를 요청하고, 경로 상태를 살핍니다 |
| Pleos SDK | `FusedLocation` 1.1.3 · `pleos.car.permission.FUSED_LOCATION` | Pleos 위치 사용 허가를 받아 이야기를 고를 지역을 확정합니다 |
| Pleos 권한 | `TTS_SERVICE` · `STT_SERVICE` · `NAVI_ROUTE` · `NAVI_ROUTE_SEARCH` · `NAVI_CUSTOM_MAP` | 위 SDK 호출 허가 |
| Android Car | `READ_CAR_POWER_POLICY`, `CarUxRestrictions` | 전원 상태에서 소리를 멈추고, 주행 제한 중에는 자막 카드를 한 줄로 줄입니다 |
| Android | `WindowManager` `TYPE_APPLICATION_OVERLAY` (`SYSTEM_ALERT_WINDOW`) | 내비 위에 말하기 버튼과 자막 카드를 겹쳐 띄웁니다 |
| Android | 포그라운드 서비스(`location`·`microphone`·`mediaPlayback`) | 내비가 앞에 있어도 위치 수신·마이크·재생을 이어 갑니다 |
| Android | `LocationManager` | GPS를 받아 지나온 길을 기록하고 시·군·구를 판정합니다 |
| Android | `AudioManager` 오디오 포커스 · `AudioAttributes` | 이야기·음악·내비 음성이 서로 양보하게 하고, 안내 중 음악을 잠깐 멈췄다 이어 틀게 합니다 |
| Android | `AudioRecord` | 질문 음성을 PCM으로 모아 STT에 넘깁니다 |
| Jetpack | Media3 `ExoPlayer`·`MediaSession` 1.11.1 | 내장 음악 재생과 시스템 미디어 세션 |
| Jetpack | Room 2.8.5 | 여정·경로·들은 이야기·수집 기록을 기기 안에 저장합니다 |
| Jetpack | WorkManager 2.12.0 | 대화 요약을 백그라운드에서 한 번 만듭니다 |
| Android | Keystore | 서버 토큰과 대화 임시 저장을 암호화합니다 |
| 외부 SDK | NAVER Maps SDK 3.24.0 | "내 여행" 지도 바탕 |

## 알려진 한계

위 "지금 구현된 것과 목킹한 것"이 **일부러 대체한 것**이라면, 여기는 **직접 겪고 원인을 찾아낸 것**입니다. 시연 중 눈에 띌 만한 것만 적었습니다.

- **내비 위치 마커가 따라오지 않습니다**: Pleos 에뮬레이터 2.1.0의 GNSS NMEA 체크섬이 고정값(`*47`)이라 내비의 파서가 문장을 거부합니다(내비 로그 `NMEA checksum mismatch`). 앱은 시스템 위치 제공자로 정상 수신하므로 이야기 편성에는 영향이 없습니다. 그래서 시연 중 내비 마커는 출발점에 머뭅니다.
- **내비 "경로 탐색 오류" 창**: 에뮬레이터 네트워크가 내비 자체 서버에 닿지 않을 때 뜹니다. 닫으시면 됩니다.
- **에뮬레이터를 오래 켜 두면 차량 서비스가 죽습니다**: 홈에 "차량 연결 상태를 확인하고 있어요"만 남고 **동행 시작**이 사라집니다(로그 `car_service not ready`). 재부팅하면 됩니다. 시연 전에는 새로 부팅해 두시는 쪽이 안전합니다.
- **음성 입력이 호스트 마이크에 민감합니다**: Mac 출력·입력 볼륨이 낮으면 인식되지 않습니다. Pleos 음성 서비스가 인식 문장을 자체 로그에 남기는 플랫폼 이슈가 있으며 앱에서 제어할 수 없습니다.
- **창밖 안내가 가끔 빠집니다**: 안내는 말하던 문장이 끝난 뒤에 끼어듭니다. 그 사이 차가 랜드마크를 지나가 버리면 조용히 취소됩니다(한 여정에 한 번뿐이라 재시도하지 않습니다). 시연 시나리오는 한빛탑 구간 속도를 낮춰 이 확률을 줄여 두었습니다.
- **답변의 근거 범위**: 답변은 **끝까지 들은 문단**에 있는 사실만 씁니다. 이야기 도중에 물으면 아직 못 들은 내용은 답에 쓰이지 않습니다. 근거 검증이 출처를 묶어 주지만 문장 자체의 정확성을 보장하지는 않으므로, 모델 답변은 틀릴 수 있습니다.
- **콘텐츠 양**: 시연 경로(천안~대전) 이야기 4편·창밖 안내 1곳, 이전 서울~대전 경로 이야기 10편·창밖 안내 4곳이 전부입니다.
- **실차 검수 없음**: 동행 끝내기가 곧 여정 종료이고, 실차용 정책 검수는 하지 않았습니다.

## 라이선스·출처

- **글꼴**: Pretendard — SIL Open Font License 1.1 (`app/src/main/assets/licenses/Pretendard-OFL.txt`).
- **음악**: Chris Zabriskie, CC BY 4.0, 원본 파일을 변경 없이 사용했습니다. 곡별 크레딧이 `app/src/main/assets/music/CREDITS.txt`와 앱의 "데이터 출처" 화면에 있습니다. 이 크레딧이 작곡가의 보증을 뜻하지는 않습니다. 네 곡이 번들돼 있고 데모는 이야기 사이에 "Prelude No. 1" 한 곡만 씁니다.
  - "Prelude No. 1" — 앨범 *Preludes* (2009)
  - "A New Day In a New Sector" — 앨범 *Music from Neptune Flux* (2016)
  - "The Temperature of the Air on the Bow of the Kaleetan" — 앨범 *Undercover Vampire Policeman* (2012)
  - "I Knew My Way Downtown and Walking Was Deluxe" — 앨범 *I Made This While You Were Asleep* (2020)
- **지역 경계**: 국토지리정보원 연속수치지형도 행정경계(시군구), 브이월드 제공 — CC BY 2.0 KR. 화면용으로 단순화했습니다.
- **이야기·창밖 안내**: 한국관광공사 TourAPI(공공누리 제1유형, 변경 금지인 제3유형 원문은 쓰지 않았습니다)·Odii, 문화체육관광부·농촌진흥청·국토교통부·국사편찬위원회(공공누리 제1유형), 한국학중앙연구원 한국민족문화대백과사전(청계산 사실; 공공저작물, 사실만 새 문장으로 작성). 출처 행은 콘텐츠 팩의 `sources`에 있습니다.
- **지도**: NAVER 지도 (NAVER Maps SDK). 지도 키는 공개 식별자만 APK에 들어갑니다.
- **코드 라이선스**: 추후 결정입니다.
