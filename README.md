# Petasos

Petasos는 지금 지나가는 지역의 이야기를 들려주는 Pleos Android 동행 앱이다.
내비게이션 화면 위에 말하기 버튼과 자막 카드가 겹쳐 떠서, 운전 중 지도를 가리지 않고 이야기를 듣다가 궁금한 것을 바로 물어볼 수 있다.
이동이 끝나면 지나온 경로선·지역 앨범·들은 이야기 카드가 "나의 기록"에 남는다.

시연 경로는 서울역 → 대전 엑스포과학공원(경부축)이고, 지역마다 준비된 이야기(S01~S10)와 음악이 GPS 위치에 따라 자동으로 이어진다.

## 테스트 환경

| 항목 | 값 |
| --- | --- |
| 실행 대상 | Pleos Connect 2.1.0 에뮬레이터 (Android 14 / API 34, ARM64) |
| 앱 | `com.petasos.app` 1.0.0-demo, minSdk 28 / targetSdk 34 |
| 호스트 | macOS, `adb`, Android emulator (호스트 마이크 사용) |
| AI 대화 서버(선택) | Python 3, OpenRouter API 키 |

## 빠른 시작 (심사용)

### 1. 설치와 권한 — 아래 블록을 그대로 실행

에뮬레이터를 부팅한 뒤 실행한다. 사용자 ID가 10이 아니면(`adb shell pm list users`로 확인) `--user` 값을 바꾼다.

```sh
adb install --user 10 apk/Petasos_Petasos_release.apk
# 내비 위 overlay(말하기 버튼·자막 카드) 권한: 둘 다 필요하다.
adb shell appops set --user 10 com.petasos.app SYSTEM_ALERT_WINDOW allow
adb shell pm grant --user 10 com.petasos.app android.permission.SYSTEM_ALERT_WINDOW
# 에뮬레이터가 Mac 마이크를 입력으로 쓰도록 켠다. 에뮬레이터를 재부팅할 때마다 다시 실행한다.
adb emu avd hostmicon
```

macOS가 에뮬레이터의 마이크 접근을 처음 한 번 물을 수 있다.

### 2. 실행

1. 홈 화면에서 **동행 켜기**를 누른다. 권한 대화상자가 3번 뜨면 모두 허용한다(오디오 녹음 → "알 수 없는 작업"(Pleos 내비 권한) → 위치).
2. 내비게이션이 앞으로 나오고 오른쪽에 둥근 Petasos 버튼, 아래에 자막 카드가 보인다.
3. 지역이 확정되기 전에는 아무것도 재생하지 않고 카드에 "출발하면 이야기를 들려드릴게요"가 표시된다. 지역이 확정되면 그 지역 이야기(서울 중구 → S01)가 먼저 나온다. 음악은 이야기와 이야기 사이에만 나온다.
4. 내비에 "경로 탐색 오류"가 뜨면 **닫기**를 누른다(에뮬레이터 네트워크가 내비 서버에 닿지 않기 때문이며 앱 동작과는 무관하다).

### 3. GPS 재생

ON 하기 전에 시작 위치를 서울역에 맞춰 두면 첫 이야기가 빨리 시작한다.

```sh
adb emu geo fix 126.9698291 37.5547273
```

재생 방법은 둘 중 하나를 쓴다.

- **GPX 재생**: 에뮬레이터 Extended controls(⋯) → Location → Routes → **Load GPX/KML** → `demo/scenarios/seoul-daejeon/route.gpx` 선택 → **Play route**. 약 9분 코스다.
- **스크립트 재생**: `python3 scripts/demo_route.py --video` (`adb emu geo fix`로 같은 경로를 약 9분에 재생, 기본 시나리오는 `demo/scenarios/seoul-daejeon/`). `--pause`를 붙이면 Enter로 일시정지/재개한다.

서울 중구에서 이야기가 시작되고, 용인·안성·천안·대전을 지나며 지역마다 이야기와 음악이 이어진다.

### 4. 말하기

오른쪽 둥근 버튼을 누른다(이야기를 듣기 전에도 된다). 이야기 중이면 현재 문단이 끝난 뒤, 아니면 바로 카드에 **말씀해 주세요 → 듣는 중… → 인식한 질문 → 답변 자막**이 차례로 나오고, 답변이 끝나면 같은 이야기의 다음 문단(없으면 음악 → 다음 이야기)으로 돌아간다. 이야기를 듣기 전이면 현재 지역 이름만 맥락으로 일반 질문에 답하고, 답변이 끝나면 다시 출발을 기다린다. Mac 마이크에 대고 말하면 된다.

답변 서버에 연결할 수 없으면 카드에 "지금은 답변 서버에 연결할 수 없어요."가, 연결은 됐지만 답을 받지 못한 그 밖의 실패에는 "잘 못 들었어요. 다시 눌러 주세요."가 잠깐 나온 뒤 이야기로 돌아간다. 이야기·음악·overlay·기록은 서버 없이 그대로 동작한다.

### 5. 종료와 기록

도착하면 홈에서 **동행 켜기(ON)** 를 눌러 끈다. 이 데모 빌드에서 OFF는 "도착"이라 이번 여정을 종료한다. **나의 기록**에서 지나온 경로선, 지나온 지역 앨범, 이야기 카드, (서버가 있었다면) 대화 요약 한 줄을 볼 수 있다.

## AI 대화용 로컬 서버 (선택)

AI 답변은 Mac에서 도는 로컬 HTTPS 서버(`server/`)가 OpenRouter를 호출해 만든다. 서버가 없어도 위 시연은 모두 된다. 서버를 쓰려면 OpenRouter API 키가 필요하다.

```sh
# 1) 의존성과 키
python3 -m venv .local/server-venv && .local/server-venv/bin/pip install -r server/requirements.txt
cp .env.example .env        # PETASOS_OPENROUTER_API_KEY 만 채우면 된다 (.env는 git에 올라가지 않는다)

# 2) 기기 토큰·설정 생성 (토큰은 24시간 유효, 값은 출력되지 않는다)
python3 server/provision-local.py --directory .local/server

# 3) 앱에 번들된 이야기에서 서버용 근거 파일 생성
mkdir -p .local/content/bundles .local/content/evidence
cp app/src/main/assets/content/p0-stories-v2.json .local/content/bundles/p0-stories-v2.json && chmod 600 .local/content/bundles/p0-stories-v2.json
.local/server-venv/bin/python scripts/build-content-bundle.py \
  --evidence-from-bundle .local/content/bundles/p0-stories-v2.json \
  --allow-device-id local-development-device --output .local/content/evidence/demo.json

# 4) 로컬 개발 인증서 생성 (CA 30일, 서버 인증서 7일)
PYTHONPATH=server .local/server-venv/bin/python server/dev-local-https.py prepare
```

5. `.local/server/config.json`을 열어 `evidenceFile`을 `.local/content/evidence/demo.json`의 절대 경로로 바꾸고 `provider`를 채운다. 아래는 개발 중 사용한 값이다. `privacyReviewedUntil`은 미래의 Unix 초이고, **OpenRouter 계정의 Privacy 설정(ZDR only, 입출력 로깅 OFF)을 직접 확인한 뒤에만** `accountContentLoggingDisabled`를 `true`로 둔다. 서버는 이 값만 믿고 호출하므로 확인은 사용자 몫이다.

```json
"provider": {
  "type": "openrouter",
  "model": "anthropic/claude-sonnet-4.6",
  "acceptedModelIds": ["anthropic/claude-4.6-sonnet-20260217"],
  "providerSlug": "amazon-bedrock/global",
  "acceptedProviderNames": ["Amazon Bedrock"],
  "privacyReviewedUntil": 1893456000,
  "accountContentLoggingDisabled": true,
  "maxOutputTokens": 2048
}
```

6. 서버를 띄우고 에뮬레이터에 토큰과 CA를 전달한다. 토큰은 인자·인텐트로 넘기지 않고 파일로만 전달하며, 앱이 읽어 암호화 저장소에 옮긴다.

```sh
PYTHONPATH=server .local/server-venv/bin/python server/dev-local-https.py serve --port 8443 &
curl -sk https://127.0.0.1:8443/v1/health          # {"status":"ok",...}
adb reverse tcp:8443 tcp:8443                       # 에뮬레이터를 재부팅할 때마다 다시 실행

adb shell "rm -rf /data/local/tmp/petasos-demo; mkdir -p /data/local/tmp/petasos-demo"
adb push .local/server/device-provisioning.token /data/local/tmp/petasos-demo/demo-server.token
adb push .local/server/dev-tls/ca.pem /data/local/tmp/petasos-demo/demo-server-ca.pem
adb shell chmod -R a+rX /data/local/tmp/petasos-demo
adb shell am start --user 10 -n com.petasos.app/.MainActivity      # 앱이 실행·재개될 때 파일을 읽는다
adb shell rm -r /data/local/tmp/petasos-demo                        # 읽은 뒤 평문 토큰을 지운다
```

앱은 이 CA를 `localhost` 연결에만 신뢰한다. 토큰이 만료(24시간)되면 2)부터 새로 만들거나 `.local/server/tokens.json`의 `expiresAt`을 늘리고, 인증서가 만료(서버 7일)되면 4)와 CA 전달을 다시 한다.

답변 모델은 `provider.model` 한 줄이다(현재 답변 2~4초). 바꾸면 `acceptedModelIds`도 OpenRouter가 돌려주는 ID로 같이 바꾸고 서버를 재시작한다.

## APK

- 위치: `apk/Petasos_Petasos_release.apk` (약 60MB). git에 포함돼 있다.
- 로컬 release 키(`.local/android/release.keystore`)로 서명한 데모용 빌드이며, 위 "DEMO" 동작이 켜져 있다(아래 한계 참고).

## 알려진 한계

- **데모 빌드 동작**: 가상(에뮬레이터) 위치를 허용하고, 이야기 사이 음악은 15초로 줄였으며, OFF가 여정 종료다. 실차용 정책 검수는 하지 않았다.
- **콘텐츠**: 이야기 10편(서울~대전 구간)뿐이고 S01·S03·S05·S08·S10은 2문단, 나머지는 1문단이다. 경로가 지나지 않는 동구 이야기(S09)는 대전 안에서 대체 선택된다.
- **답변 출처**: 이야기 자료에 없는 질문은 "일반적으로 알려진 바로는"을 붙여 일반 지식으로 짧게 답한다. 모델 답변이므로 틀릴 수 있다.
- **음성 입력**: 호스트 마이크로 들어오는 소리가 작으면 인식이 안 된다(Mac 출력 볼륨이 낮을 때도 그렇다). Pleos 음성 서비스가 인식 문장을 자체 로그에 남기는 플랫폼 이슈가 있으며 앱에서 제어할 수 없다.
- **내비 위치 마커**: Pleos 에뮬레이터 2.1.0의 GNSS NMEA 체크섬이 고정값(`*47`)이라 내비의 NMEA 파서가 문장을 거부하고(내비 로그 `NMEA checksum mismatch`), 그래서 내비 위치 마커가 주입한 위치를 따라가지 않는다. 앱은 시스템 위치 제공자로 정상 수신한다.
- **내비 경로 오류 창**: 에뮬레이터 네트워크가 내비 자체 서버에 닿지 않아 "경로 탐색 오류"가 뜬다. 닫으면 되고 앱 흐름에는 영향이 없다.
- **GPX 재생**: Extended controls의 GPX 재생은 같은 에뮬레이터 위치 기능을 쓰지만, 제작자가 그 화면으로 직접 재생해 확인하지는 않았다. 검증된 경로는 `scripts/demo_route.py`이다.

---

# 개발자용

## 개발 환경

| 항목 | 고정값 |
| --- | --- |
| JDK | 17 (`PETASOS_JAVA_HOME`로 경로 지정, 기본 Homebrew openjdk@17) |
| Gradle Wrapper / AGP / Kotlin | 8.11.1 / 8.10.1 / 2.2.21 |
| Room / WorkManager / Media3 / NaviHelper | 2.8.5 / 2.12.0 / 1.11.1 / 3.0.5 |
| compileSdk / minSdk / targetSdk | 36 / 28 / 34 |

프로젝트 루트의 비추적 `local.properties`에 `sdk.dir=<Android SDK 경로>`를 둔다. 의존성은 `.gradle-user-home/`, 서명 키는 `.local/android/`에 보관하며 둘 다 git에서 제외된다. `.env`의 `PETASOS_NAVER_MAPS_ANDROID_KEY_ID` 같은 공개 지도 식별자만 APK에 들어간다.

## 빌드

```sh
./scripts/gradle.sh :app:assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./scripts/gradle.sh :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk
./scripts/gradle.sh :app:testDebugUnitTest
```

빌드 유형은 debug/release 둘뿐이고 둘 다 `BuildConfig.DEMO=true`다. debug에는 개발용 도구(`app/src/debug`)와 로그가 더 있다.

release 서명은 `.local/android/release.keystore`와 `.local/android/release-signing.properties`(`storePassword`, `keyPassword`, `keyAlias`)를 읽는다. 없으면 서명 없이 빌드된다. 새 키는 `keytool -genkeypair -keystore .local/android/release.keystore -storetype PKCS12 -alias petasos-release -keyalg RSA -keysize 2048 -validity 9125`로 만든다. 빌드 후 `apk/Petasos_Petasos_release.apk`로 복사한다.

호스트 디스크가 부족하면 `rm -rf app/build`로 회수한다(재생성됨).

## debug 전용 도구

```sh
# 청취 이력 초기화 (재시연용)
adb shell am broadcast --user 10 -f 0x20 -p com.petasos.app -a com.petasos.app.debug.RESET_LISTENING_HISTORY
# 영상 프리셋: 지역당 대표 이야기 1편(S01·S03·S05·S08·S10)만 재생
adb shell am broadcast --user 10 -f 0x20 -p com.petasos.app -a com.petasos.app.debug.SET_VIDEO_MODE --ez enabled true
```

서버 토큰·CA 전달은 위 "로컬 서버" 절의 파일 방식이 debug/release 공통이다(debug에는 `PROVISION_SERVER` 브로드캐스트도 남아 있으며 `run-as`로 앱 cache에 둔 토큰을 읽는다). `-f 0x20 --user 10 -p <패키지>`는 강제 종료된 패키지에도 브로드캐스트를 전달하기 위해 필요하다. debug 로그는 `adb logcat -s PetasosTalk`로 본다(상태 전이, STT 인식, 답변 지연).

## 콘텐츠 번들

앱의 이야기는 `app/src/main/assets/content/p0-stories-v2.json`이고 SHA-256이 `BundledStoryCatalog.SHA256`에 고정돼 있다. 검토 JSON(`.local/content/review/`, git 제외)을 고친 뒤 다음으로 다시 만든다.

```sh
.local/server-venv/bin/python scripts/build-content-bundle.py --review .local/content/review/<검토파일>.json --output .local/content/bundles/<새이름>.json
```

이미 설치된 (이야기, 버전)은 바뀔 수 없으므로 내용을 바꾸면 `version`을 올린다. 새 번들을 asset으로 복사하고 `BundledStoryCatalog.SHA256`을 갱신한다.

## 스크립트

- `scripts/demo_route.py`: GPS 재생. `--video`(영상 프리셋 속도), `--gpx demo/scenarios/seoul-daejeon/route.gpx`(타임스탬프 GPX 내보내기), `--speed`, `--pause`.
- `scripts/build-region-dataset.py`, `scripts/region-boundaries.py`: 시·군·구 경계 데이터 변환.
- `server/provision-local.py`, `server/dev-local-https.py`: 로컬 서버 설정과 HTTPS 개발 서버.
- 에뮬레이터를 재부팅하면 `adb reverse`, `adb emu avd hostmicon`, overlay 권한을 다시 확인한다. 부팅은 `emulator -avd Pleos_Connect_v2.1.0 -no-snapshot-load -no-boot-anim`.

## 구조

- `app/src/main/java/com/petasos/app/playback/`: 이야기·말하기·복귀·음악 상태기계(`PlaybackCoordinator`).
- `runtime/`: 직렬 효과 실행, 타이머, 자막(`CaptioningRuntimeSpeech`) 등 플랫폼 독립 포트.
- `platform/`: Pleos TTS/STT·내비·위치 어댑터, 미디어 세션, 동행 서비스, `overlay/CompanionOverlay`, `runtime/PetasosSessionHost`(전체 조립).
- `planning/`, `location/`, `music/`, `content/`: 지역 편성, 지역 판정, 음악, 콘텐츠 검증.
- `generation/`: 서버 HTTPS 클라이언트와 `DemoServerSetup`(DEMO 서버 연결).
- `storage/`: Room 스키마, 암호화 저장소, 기록·수집 증명.
- `presentation/`: 홈·나의 기록 화면.
- `server/`: 답변·요약 생성 서버(Python ASGI). `docs/Petasos_제품명세.md`, `docs/Petasos_동작명세.md`가 명세, `docs/reference/pleos/`가 Pleos SDK 가이드다.
- `DEMO_PLAN.md`: 데모 단계별 계획과 진행 현황.
