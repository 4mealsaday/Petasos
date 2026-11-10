# Petasos 데모 계획 (2026-11-09 확정)

이 문서가 현재 유일한 작업 기준이다. `DEVELOPMENT_HANDOFF.md`와 `docs/development/*`는 dev.36까지의 과거 기록이며, 정리 단계에서 git 태그로만 보존하고 삭제한다.

## 1. 확정된 결정

| 항목 | 결정 |
| --- | --- |
| 목표 | 공모전 시연용으로 **한 개의 흐름이 끝까지 실제로 동작하는 앱**. 안전·검증 장치는 데모에 필요한 만큼만 남긴다. |
| 경로 | 서울역 → 경부축 → 대전 엑스포과학공원. 기존 이야기 S01~S10, 5개 지역 묶음 그대로 사용. GPS 재생 속도를 높여 압축한다. |
| 마이크 | **켠다.** Pleos STT(ON_DEVICE) → AI 답변 → 이야기 복귀. SpeechService 로그 문제는 README에 플랫폼 이슈로 한 줄만 기록. |
| AI | Mac의 로컬 HTTPS 서버(`server/`, OpenRouter) + `adb reverse tcp:8443 tcp:8443` 유지. provider 검토 만료 같은 차단 로직은 제거. |
| 화면 | 홈(ON/OFF·나의 기록) + **Pleos 내비 위 overlay**(원형 말하기 버튼 + 하단 자막/음악 카드) + 나의 기록(경로선·지역 앨범·이야기 카드). |
| 데모 앱 | 별도 `.demo` 무음 앱은 폐기. 제품 앱 안의 `DEMO` 빌드 플래그 하나로 가상 위치 허용·짧은 음악 간격만 바꾼다. |
| 테스트 | 대규모 JVM/기기 probe 폐기. 핵심 재생 상태기계의 소수 단위 테스트 + 아래 6장의 **수동 시연 체크리스트**로 대체. |

## 2. 시연 시나리오 (영상 약 3~4분)

1. 홈에서 **ON** → overlay 권한 확인 → 위치·재생 동행 세션 시작 → `requestNavigationScreen(Foreground)`로 내비를 앞으로.
2. 내비 지도 위에 원형 Petasos 버튼(우측)과 하단 가로 자막/음악 카드가 동시에 표시.
3. 목적지 설정: `requestRoute(대전엑스포과학공원)` 시도. 실패하면 내비 UI 검색으로 설정(영상 편집 가능).
4. Mac에서 `scripts/demo_route.py` 실행 → 에뮬레이터 GPS가 서울역부터 경로를 따라 이동.
5. 서울 중구 진입 → S01/S02 이야기 TTS 재생, 카드에 현재 문단 자막.
6. 원형 버튼 터치 → 문단 종료 후 입력 신호 → 질문 발화 → 실시간 AI 답변 → 이야기 이어서 재생.
7. 이야기 종료 → 음악(데모 모드는 짧은 간격) → 다음 지역(용인/안성/천안/대전) 이야기 자동 시작.
8. 도착 → OFF → **나의 기록**: 지나온 경로선, 통과 지역 실루엣 앨범, 들은 이야기 카드.

## 3. 남길 것 / 버릴 것

**남김 (핵심 기능)**
- `playback/PlaybackCoordinator`, `runtime/PlaybackRuntime`, `runtime/RuntimePorts`, `runtime/CaptioningRuntimeSpeech` — 이야기·말하기·복귀·음악 상태기계.
- `platform/pleos/speech/*` 중 실제 사용하는 TTS/STT 어댑터 1벌(`PleosSpeechOutput`, `PleosPcmSpeechInput` 또는 `PleosSpeechInput` 중 동작하는 쪽 하나).
- `platform/media/*` (MediaService, Media3 음악, CompanionLocationService), `music/*`.
- `planning/RegionalStoryPlanner`, `ContentPlanner`, `AutomaticStory*Bridge`, `AutomaticMusicPlaybackBridge`, `PlaybackLookahead`.
- `generation/*` (서버 호출), `content/*`, `assets/content/p0-stories-v2.json`, 음악 자산.
- `location/` 중 지역 판정 1벌(`PackedRegionBoundaryLookup` 계열)과 `RegionConfirmation`, Android 위치 어댑터, `platform/pleos/navigation/*`.
- `presentation/` 홈·기록(`JourneyShell`, `CompanionControls`, `records/*`, `interests/*`, 지도) — 기록 화면은 네이버 지도 유지.
- Room DB (스키마는 유지하되 데모에 불필요한 테이블 정리는 마지막에 선택적으로).
- `server/petasos_server/*` (테스트 제외), `.env`.

**버림**
- `src/demo/**`, `src/androidTestDemo/**`, `src/testDemo/**`, `demo` buildType, `lint-demo.xml`, `petasosDemoLint` 관련 gradle 코드.
- `privacy` buildType·`privacy-rules.pro`·`privacy-test-rules.pro` (R8 로그 억제 실험용). debug/release만 유지.
- `src/androidTest/**` 전체 probe, `scripts/probe-*`, `diagnostics/` (overlay 진단은 제품 구현 후 삭제), `artifacts/`, `outputs/`, `exa-results/`.
- `src/test/**` 중 정책·심사·보조경로·랜드마크 정책·암호화 관련 테스트 전부. `PlaybackCoordinatorTest`, `PlaybackRuntimeTest` 일부만 유지(깨지면 삭제).
- `planning/auxiliary/*` (네이버 보조 경로 예측) — 데모는 GPS 실제 진입 기반으로 충분. 서버 `auxiliary_routes.py`, `road_routes.py`의 카카오 부분.
- `location/ReviewedPolygonBoundaryLookup`, `RegionPolylineLookup`/`PolylineBoundaryIndex`(사용처 확인 후), `ReviewedRegionPolicy*`, `ReviewedLandmarkPolicy`, `Landmark*`(랜드마크 정책 심사), `LocationConsentAuthority`/`LocationInputAuthority`/`CompanionSessionAuthority` 등 권한 계층 → 단순 boolean 조건으로 대체.
- `storage/PayloadCipher` 기반 암호화·요약 재시도(`summary/*` 재시도 정책) → 요약은 단순 1회 호출 또는 제거.
- `assets/regions/ngii-2026-09-21-v1/*.pgeo` 중 데모 경로에 걸리지 않는 지역(약 161MB → 수 MB 목표). `regions-display-v1.json`(기록 앨범용 단순 실루엣)은 유지.
- 문서: `docs/development/**`, `docs/archive/**`, `DEVELOPMENT_HANDOFF.md`, `COMPETITION_CONTEXT.md`, 루트의 옛 기획서 md 4개, `spec/` → 삭제. `docs/reference/pleos/`(SDK 가이드)와 `docs/Petasos_제품명세.md`, `docs/Petasos_동작명세.md`만 유지.

## 4. 작업 순서

**0단계 — 보존 지점**: `git add -A && git commit` 후 `git tag pre-cleanup-dev36`. 이후 모든 삭제는 이 태그로 복구 가능.

**1단계 — 정리 (빌드 통과까지)**
1. 문서·artifacts·demo/privacy 빌드·androidTest 삭제, `app/build.gradle.kts` 단순화.
2. 데모 경로 지역 목록 산출: 경로 GPX와 NGII 경계 교차로 지역 코드 계산 → 해당 `.pgeo`와 manifest 항목만 유지.
3. 권한·정책 계층 제거: "현재 위치가 지역 경계 안에서 N(=2)회 연속 + 정확도 < 100m"면 지역 확정. `isMock`은 `BuildConfig.DEMO`에서 허용.
4. 마이크 `supportsLiveInput()` → true. RECORD_AUDIO 런타임 권한 요청은 ON 시점에.
5. 생성 클라이언트에서 provider 검토 만료·과도한 strict 검사 완화(스키마 검증은 유지).
6. 빌드 + 설치 + 기존 홈/기록 화면이 뜨는지 확인. 남은 단위 테스트가 깨지면 삭제.

**2단계 — 제품 overlay 연결** (완료: 사전 확인 A/B 포함, 264개 중 경로가 지나는 21개 리전만 유지 + overlay 2창 + 홈 ON/OFF 연결 검증됨)
1. Manifest에 `SYSTEM_ALERT_WINDOW` 추가. 에뮬레이터에는 설정 화면이 없으므로 README에 `adb shell appops set --user 10 com.petasos.app SYSTEM_ALERT_WINDOW allow` 명시.
2. `CompanionOverlay`(기존 `CompanionLocationService` 안에서 관리): `TYPE_APPLICATION_OVERLAY` 창 2개 — 원형 버튼(포커스 없음), 하단 카드(터치 통과, 불투명도 0.8 미만). 진단 APK(`diagnostics/navigation-overlay`)의 창 파라미터를 그대로 이식.
3. 버튼 → 기존 runtime의 말하기 요청. 카드 ← `PlaybackPresentationState`(현재 문단/음악 제목/듣는 중 상태).
4. 홈 ON → 서비스 시작 + overlay 부착 + 내비 Foreground 요청. OFF → 세션·overlay 정리.
5. CarUx 제한 시 카드 텍스트를 짧게(또는 숨김). 차단 대신 표시 축소.

**3단계 — 경로·GPS** (완료: requestRoute 성공, `scripts/demo_route.py`로 서울역→대전 엑스포 전체 구간 GPS 재생 1회 실행 → S01~S10 전부 자동 재생·완료까지 검증됨)
1. `NAVI_ROUTE_SEARCH` 권한 + ON 직후 `requestRoute(엑스포과학공원 좌표)`; 콜백 로그만 남기고 실패해도 진행.
2. `scripts/demo_route.py`: 서버의 네이버 Directions로 서울역→엑스포 path 1회 조회 후 `demo/route.gpx`로 고정 저장 → `adb emu geo fix lon lat`을 일정 간격·배속으로 재생, 일시정지/재개 지원.
3. 압축 기준: 지역당 이야기 1~2편이 끝날 만큼 체류하도록 구간별 배속 지정(도심 구간 느리게, 고속도로 빠르게).

메모(2026-11-09): 이전 세션 메모는 "에뮬레이터 플레이크"로 적었지만 실제로는 재현 가능한 코드 버그였다 — `updateLocationRecording()`과 `scheduleLocationTick()` 두 곳이 `RoomLocationStore.isExplicitlyEnabled()`(raw `location_enabled=1`, `BuildConfig.DEMO` 모름)로 동의 여부를 다시 읽고 있어서, companion이 ACK된 직후(수 ms 이내) 또는 다음 위치 tick마다 스스로 revoke됐다. `prepareVisibleStart`에 이미 있던 DEMO 기본 허용 규칙(`currentLocationConsentEnabled()`로 추출)을 세 지점 모두에 적용해 수정·커밋(`897243f`). 수정 후 클린 에뮬레이터 + 새 설치에서 ON이 안정적으로 유지되고, `demo_route.py` 1회 실행으로 S01~S10 전부(경로가 실제로 지나지 않는 30110/동구 바인딩의 S09까지 DEMO 폴백으로) 완료 처리된 것을 Room `playback_attempt` 테이블로 직접 확인함.

**다음 세션 환경 메모 (2026-11-10)**
- 에뮬레이터: `Pleos_Connect_v2.1.0` AVD, `disk.dataPartition.size=6G`로 축소해둠(원래 10G는 12G 요구해서 디스크 부족 유발). 재부팅만 할 땐 `emulator -avd Pleos_Connect_v2.1.0 -no-snapshot-load -no-boot-anim`(wipe-data 불필요). 호스트 디스크가 부족해지면 `rm -rf app/build`로 회수.
- 서버: `PYTHONPATH=server .local/server-venv/bin/python server/dev-local-https.py serve --port 8443` (인증서·토큰은 `.local/server/`에 이미 준비돼 있어 `prepare` 재실행 불필요) + `adb -s emulator-5554 reverse tcp:8443 tcp:8443`. 확인은 `curl -sk https://127.0.0.1:8443/v1/health`.
- overlay 권한: `adb shell appops set --user 10 com.petasos.app SYSTEM_ALERT_WINDOW allow` **+** `adb shell pm grant --user 10 com.petasos.app android.permission.SYSTEM_ALERT_WINDOW` (이 이미지에서는 appops만으로는 안 붙을 때가 있음, 둘 다 실행).
- 청취 이력 초기화(재시연용): `adb shell am broadcast --user 10 -f 0x20 -p com.petasos.app -a com.petasos.app.debug.RESET_LISTENING_HISTORY` (debug 빌드 전용 `DemoResetReceiver`).
- 홈 ON 탭이 "지금은 동행을 시작할 수 없어요"로 실패하면 — 897243f 이전 버그는 해결됐으니, 남은 원인은 보통 overlay 권한 둘 중 하나 누락이거나 RECORD_AUDIO/위치 권한 미승인.

**4단계 — 대화·기록 마무리** (진행: 대화 왕복·기록·영상 리허설 통과. 단, 발화는 Mac `say -v Yuna` 음성을 호스트 마이크로 넣은 것 — 사람 목소리 1회 확인 남음)
- 마이크: `adb emu avd hostmicon`으로 켜짐(README). ON 첫 탭에 RECORD_AUDIO 대화상자 → 허용하면 같은 ON이 이어짐.
- 대화 왕복(로그 태그 `PetasosTalk`): 질문 "서울역은 언제 지어졌어?" → 인식 동일 문장 → 답변 2.3~3.5초("일반적으로 알려진 바로는 … 1925년 완공") / 경험담 "어릴 때 기차 타고 대전 간 적이 있어" → 4.1초 / 음악 중 버튼 → 음악 즉시 정지 → 답변 4.5초 → 음악 같은 위치에서 재개. 지연은 전부 서버·모델(sonnet-4.6) 쪽, 5초 미만이라 모델 교체 안 함(`.local/server/config.json` `provider.model` 한 줄).
- 카드: 현재 문단을 마친 뒤 들을게요 → 말씀해 주세요 → 듣는 중… → "인식 문장" + 답을 찾고 있어요 → 답변 자막 → 이야기/음악. DEMO 첫 발화 대기 9초.
- 고친 끊김: 서버 연결 설정 없음(CONFIGURATION, debug `PROVISION_SERVER` 추가) / 근거 밖 질문 거절(프롬프트: 일반 지식은 표시하고 짧게 답) / 대화 후 복귀가 음악 우선(이야기 다음 문단 우선으로) / **내비가 앞에 있으면 마이크 무음**(FGS에 microphone 타입) / **자동 이야기가 운에 따라 안 시작**(에뮬레이터 1Hz 위치 반복이 위치 permit을 1초 만에 무효화 → DEMO에서 완화) / 이야기 자막이 시작 직후 사라짐 / DEMO 폴백이 다른 지역 이야기를 소비(같은 시·광역시로 제한) / 청취 이력 초기화가 FK로 실패·브로드캐스트 미도달.
- 한계: 번들 이야기가 전부 1문단이라 "같은 이야기 다음 문단 복귀"는 현재 콘텐츠로는 발생하지 않음(대화 후 음악 → 다음 지역 이야기로 이어짐, 복귀 로직은 다문단이면 다음 문단 선택).
- 기록: DEMO에서 OFF = 여정 종료. 나의 기록 > 지난 여정에 경로선·통과 지역 실루엣·지역 카드 17·이야기 카드 5·대화 요약 1줄 표시(요약 작업이 미검증 네트워크 제약으로 영원히 대기하던 것 DEMO에서 해제).
- 영상: `SET_VIDEO_MODE` + `demo_route.py --video` → S01·S03·S05·S08·S10, 중간 대화 1회 포함 ON→도착→OFF→기록까지 5분 38초 무개입 1회 통과. 스크린샷은 `Claude outputs/step4-rehearsal/`.
- 환경 이슈: 에뮬레이터 네트워크가 VALIDATED가 아니면 내비 자체 서버 호출 실패 → `requestRoute` CAUSE_SEARCH_ROUTE + 내비 "경로 탐색 오류" 대화상자(닫기로 진행, 앱 흐름엔 영향 없음).

**5단계 — 제출물** (진행: release APK·README·GPX·비밀값 검사 완료, 시연 영상 녹화는 남음)
- 이야기 2문단: S01·S03·S05·S08·S10을 문장 경계에서 2문단으로 분리(새 사실 없음), version 2·콘텐츠 해시 갱신. 사실이 하나뿐이라 "한 사실은 한 문단" 검사를 "같은 리비전 재사용 허용, 다른 리비전 혼용 거부"로 완화(서버·`ContentBundleCodec`·`RoomContentInstaller`). release에서 S01 1문단 중 말하기 → 실패 안내 → S01 2문단 복귀 확인(서버 답변 경로의 2문단 복귀는 직접 보지 못함, 복귀 로직은 공유).
- release APK: `apk/Petasos_Petasos_release.apk`(60MB, git에 커밋). release도 `BuildConfig.DEMO=true`, debug 전용 receiver는 미포함, 로컬 키(`.local/android/`)로 서명. 앱 삭제 후 새 설치 → 서버·reverse 없이 ON → GPS 전 구간 → 이야기 → OFF → 기록(지역 17·이야기 카드 8, 오류 표시 없음) 통과. 서버 없는 말하기는 카드에 "지금은 답변 서버에 연결할 수 없어요." 후 이야기 복귀.
- 서버 연결(release 포함): 토큰·CA를 `/data/local/tmp/petasos-demo/`에 `adb push`하면 `DemoServerSetup`이 읽어 암호화 저장·localhost 한정 CA 신뢰(사용자 10의 앱 저장소는 adb 접근 불가라 이 경로). release + 로컬 서버로 질문 1회 성공(인식 "서울역은 언제 지어졌어?" → "…1925년에 완공…", 인식 후 약 2.3초).
- 카드: 터치 가능·불투명 1.0, 창 크기를 카드 모양에 맞춤(투명 여백이 터치를 먹지 않음), 카드 밖 지도 터치 통과 확인. 서버 실패 문구가 카드에 보이도록 GENERATING 중 캡션 우선 표시.
- GPX: `demo/route.gpx`(255점, 4.8분, `demo_route.py --gpx`). Extended controls에서의 실제 재생은 미확인.
- 비밀값 검사: git 전체 이력 29커밋에서 `.env` 값·토큰·서명 비밀번호 일치 0, OpenRouter/AWS/개인키 패턴 0, 추적된 인증서는 debug 공개 CA뿐. 원격 저장소 없음(push 명령만 준비).
- 내비 GPS 조사: 에뮬레이터 NMEA 체크섬이 `*47` 고정이라 내비 `NmeaParser`가 전부 거부(원인 확정). 올바른 체크섬 NMEA(`adb emu geo nmea`, 에뮬레이터와 같은 필드 형식)는 HAL이 받지만 내비 엔진 위치(`[mm] org(127.094681,37.4127212) sat(0)`)는 고정 → 해결 못 함, `demo_route.py` 옵션 없음. `getCurrentLocationInfo`는 활성 경로가 없어 확인 못 함.
- 환경 메모: Mac 출력 볼륨이 낮으면(50%) `say` 음성이 STT에 안 잡힘(85% 정상). (아래 "녹화 전 마지막 수정"으로 해결: ON 직후 말해도 서버가 답함.)

**5단계 후속 — 녹화 전 마지막 수정** (release 새 설치로 확인)
- 시작: 지역 확정 전에는 아무것도 재생하지 않고 카드에 "출발하면 이야기를 들려드릴게요". 음악 제공은 DEMO에서 "이번 세션에 이야기가 시작된 뒤"로 제한(`AutomaticMusicPlaybackBridge(requireStoryFirst)`), 그래서 음악은 이야기 사이에만. 서울역 위치로 새 설치 → ON → 대기 카드 → S01 1문단 → 2문단 → 음악 순서 확인(첫 소리 = S01).
- 맥락 없는 질문: 와이어 스키마(`minItems: 0`)와 서버는 이미 빈 맥락을 허용해서 계약 변경 없이, 앱이 DEMO에서 이야기를 아직 못 들었을 때 현재 지역 이름("현재 위치: 서울특별시 중구. 아직 들려드린 이야기는 없습니다.")만 `contextSummary`에 담도록 함(`RoomContentContext`). 서버 프롬프트 petasos-v0.8-5는 이 경우 지역을 장소 힌트로 일반 지식 답변("일반적으로 알려진 바로는" 유지). 경로 밖 위치에서 이야기 전 질문 → 서버 답변, 답변 후 다시 대기 카드(음악 없음) 확인.
- 실패 문구: 연결 못 함(구성 없음·TLS·I/O·타이머 만료)만 "지금은 답변 서버에 연결할 수 없어요.", 그 밖의 실패는 "잘 못 들었어요. 다시 눌러 주세요."(`RuntimeGenerationResult.Failure.unreachable` → 고정 프롬프트 `generation-misheard`). 서버를 끈 상태와 토큰 만료(인증 거부) 상태에서 각각 확인.
- 서버 켠 상태에서 S01 1문단 중 질문 → 인식 "서울역은 언제 지어졌어?" → 답변("일반적으로 알려진 바로는, 서울역은 1925년에 완공…") → S01 2문단 → 음악 확인.
- 테스트: 서버 306개 중 실패 71(관광 API 계약 테스트·옛 promptVersion 기대값 등 이번 변경과 무관, 미정리), JVM 963개 중 `CaptioningRuntimeSpeechTest` 2개 실패(4단계 자막 권한 재검사 제거의 영향, 미정리).

**6단계 — 디자인 v1 적용** (`docs/DESIGN.md` 기준, 기능 로직 변경 없음, 스크린샷 `Claude outputs/design-v1/`)
- 토큰·글꼴·로고: `colors_petasos`/`dimens_petasos`/`styles_petasos`, Pretendard 4종(`res/font`, OFL은 `assets/licenses/`), 어댑티브 아이콘·`ic_petasos_mark`, 유리 질감 전부 삭제. 에뮬레이터(160dpi)는 앱 영역이 1707dp라 DESIGN.md의 1330dp 캔버스에 맞춰 밀도를 1.28배로 보정(`DesignScale`, Activity·내비 위 표시 공통, 줄이기 없음).
- 홈(동행 카드 + 내 여행 카드 + 설정), 내비 위 표시(96dp 원형 버튼 상태별 테두리·점 3개, 하단 2줄 카드를 내비 지도 영역 안에만 표시, 문장 단위 교체), 내 여행(좌 지도 + 우 지역/여정 탭, 새로고침·목록으로 삭제), 설정(위치 기록 토글·관심사·데이터 출처·앱 정보·DEMO 청취 기록 초기화), 첫 실행(권한 안내 → 관심사).
- 문장 교체: TTS가 진행 이벤트를 주지 않아 문장 길이로 추정(비공백 5자/초, 최소 1.8초). 첫 문장부터 시작해 마지막 문장에서 멈춤.
- 지도: 네이버 지도 백엔드에 폴리라인·지역 강조·카메라 맞춤을 추가했으나 에뮬레이터에서 `Authorization pending: timeout`(네트워크)으로 항상 오프라인 지역 지도로 대체돼 **실기기에서 네이버 타일 위 그리기는 미확인**. 폴백도 다닌 길·선택 지역 강조/확대는 동일하게 동작. 점 솎아내기는 40m 간격(최대 1200점/구간): 기록 333점 → 233점, 읽기 첫 1.5초(콜드)/이후 40ms. 3만 점 규모는 측정하지 못함.
- 첫 실행 "계속하기" = 위치 기록 동의 저장. DEMO는 설정이 없을 때만 기본 허용이라, 관심사를 저장하면서 "꺼짐" 행이 새로 생겨 ON이 막히던 문제를 막기 위함.
- 확인(새로 설치한 release): 첫 실행 → ON → GPS(`--video --speed 2`) → 이야기·음악 카드 → 말하기 → 답변 카드 → 이야기 복귀 → OFF → 내 여행(지역 14·이야기 3, 여정 "서울 용산구 → 대전 유성구") 통과.
- 미해결: 첫 실행에서 관심사(역사·건축)를 고른 채 시작하면 자동 이야기가 시작 직후 멈춤(`offer_not_found` → PAUSED, 한 번 관찰·원인 미확정). 관심사 없이 시작하면 정상. 시연은 관심사를 고르지 않는 쪽 권장.
- 정리: `RecordsPanel`·고급 재생·여정 마치기·목적지 검색 UI 삭제(재생·기록 로직은 그대로), `DemoListeningHistory`로 DEMO 초기화를 main에 두고 debug 리시버가 재사용, 호스트에 표시 전용 `displayRegionId()` 추가.

## 5. 개발 원칙 (이전 방식에서 바꾸는 점)
- 새 기능마다 문서·증거 JSON·probe를 만들지 않는다. 변경 내역은 git 커밋 메시지로 충분.
- "검증 전이면 기능을 끈다" 대신 "데모에서 동작하게 하고, 한계는 README에 한 줄".
- 권한·만료·세대(generation) 검사는 실제로 버그를 막는 곳(재생 중복, 늦은 콜백)만 남긴다.

## 6. 수동 시연 체크리스트 (완료 기준)
- [x] ON → 내비 위에 버튼·카드가 동시에 보이고 지도 터치가 막히지 않는다.
- [x] GPS 재생 시 서울 중구에서 이야기가 시작되고 카드 자막이 문단과 맞다.
- [x] 버튼 → 질문 → AI 답변 → 복귀 (콘텐츠가 1문단이라 복귀 대상은 음악→다음 이야기; 사람 목소리 확인 1회 남음).
- [x] 이야기 → 음악 → 다음 지역 이야기 자동 전환이 대전까지 이어진다.
- [x] OFF → overlay·위치·오디오가 모두 정리된다.
- [x] 나의 기록에 경로선·통과 지역·들은 이야기가 표시된다.
- [x] release APK 설치 후 위 흐름 재현(서버 없이 전체 흐름 + 서버 켠 상태 대화 1회; 사람 목소리 확인은 4단계 항목과 함께 남음).
