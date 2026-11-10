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
- 내 여행 지도(2차): 네이버 일반 지도(2D) 바탕 + 표시층. 바탕은 건물·대중교통·자전거·교통·지적·지형 레이어 끄기, 지명 0.85배, 밝기 -0.2(0/-0.2/-0.4 비교는 `Claude outputs/design-v1/05f-map-brightness-*`). 아직 못 모은 지역은 `regions-display-v1.json` 경계를 `#141A24` 45% 덮개 + `#B4BBCB` 0.8dp 경계로 덮고, 다닌 길은 약 10m 단순화(빛 번짐 10dp 35% + 본선 4dp `#0078FF`, 여정별 별도 선이라 겹치면 진해짐). 진입 시 여정을 날짜순 3초(시스템 애니메이터 배율 적용)로 그리며 끝에 흰 점, 선이 닿은 지역의 덮개가 차례로 벗겨지고 화면을 누르면 즉시 완성. 지도 로직(덮개·진행)은 `JourneyReveal` 하나를 네이버 레이어와 자체 지도가 공유.
- 대체 지도: 진입 시 네트워크 없음 / 인증 실패 콜백 / 3초 안에 로딩 완료 콜백 없음이면 자체 지도(흰 바탕, 경계 `#E5E8EF`)로 같은 덮개·선·애니메이션을 그린다. 한 번 연 뒤에는 그 화면에서 바꾸지 않고 다음 진입 때 다시 판단. 디버그 빌드는 사유를 `PetasosMap` 로그로 남김. 첫 진입은 타일을 처음 받느라 3초를 넘겨 대체 지도로 뜰 수 있음(release에서 이후 4회 진입은 모두 네이버 지도).
- 지역 탭 앨범: 모은 지역 카드가 먼저, 안 모은 지역은 회색 실루엣 + 흐린 이름으로 뒤에. 시와 구가 함께 있는 도시(수원시+장안구…)는 구만 그려 덮개가 이중으로 겹치지 않게 함.
- 데이터 한계: `regions-display-v1.json`에 세종특별자치시·대전 중구 경계가 없어 그 자리는 덮개가 없다(덮개 로직 문제 아님).
- 첫 실행 "계속하기" = 위치 기록 동의 저장. DEMO는 설정이 없을 때만 기본 허용이라, 관심사를 저장하면서 "꺼짐" 행이 새로 생겨 ON이 막히던 문제를 막기 위함.
- 확인(새로 설치한 release): 첫 실행 → ON → GPS(`--video --speed 2`) → 이야기·음악 카드 → 말하기 → 답변 카드 → 이야기 복귀 → OFF → 내 여행(지역 14·이야기 3, 여정 "서울 용산구 → 대전 유성구") 통과.
- 관심사 버그 해결(`0c5e256`): 관심사가 있으면 지역 확정 전(region=null)에 관심사 전용 후보가 offer되어 이야기가 바로 시작됐고, 시작 직후 내비·위치 ingress가 offer 권한을 철회하면 브리지가 같은 후보를 새 offerId로 다시 offer(옛 철회는 이미 소비돼 `offer_not_found`)해서 진행 중이던 시작이 offerId 검사에서 거부 → PAUSED. 이제 관심사는 현재 지역 이야기의 우선순위로만 쓰고(태그 맞는 것 먼저, 없으면 지역 이야기 아무거나) 지역 확정 전에는 아무것도 시작하지 않음. 관심사 있음/없음 모두 ON → GPS → 서울 중구 이야기 재생 확인(debug), 새 release에서 관심사 선택 + 전 구간 확인.
- 첫 실행 저장 실패 해결(`bd97b75`): 위치 권한이 아직 없을 때 `suspendCompanionSources`가 Activity의 표시 상태까지 지워 관심사·동의 저장이 모두 거부됐음(overlay만 해제하도록 수정).
- 정리: `RecordsPanel`·고급 재생·여정 마치기·목적지 검색 UI 삭제(재생·기록 로직은 그대로), `DemoListeningHistory`로 DEMO 초기화를 main에 두고 debug 리시버가 재사용, 호스트에 표시 전용 `displayRegionId()` 추가.

- 환경(인터넷): 이번 세션의 에뮬레이터는 외부 접속이 정상(ping 8.8.8.8·도메인, 네트워크 VALIDATED, 내비 `requestRoute` 성공, 네이버 지도 로딩 성공)이라 "인증 시간 초과"·"경로 탐색 오류"는 재현되지 않음. 에뮬레이터는 호스트 DNS(`netsimd --host-dns`)를 따라가므로, 이전 세션에서는 호스트 네트워크가 바뀐 뒤 에뮬레이터를 다시 켜지 않아 DNS/인터넷이 막혔을 가능성이 높지만 그 상태를 다시 만들어 확인하지는 못함.
- 아이콘: 런처 어댑티브 전경/배경·round는 이미 시안 A와 동일(`d95dcb9`), 앱 목록·하단 바·내비 위 원형 버튼 모두 같은 날개 모자. 스크린샷 `09-launcher-icon`.
- 확인(새로 설치한 release, 관심사 선택): 첫 실행 → ON → GPS 전 구간 → 이야기 → OFF → 내 여행(지역 14곳·이야기 4편, 애니메이션·터치 즉시 완성).

**7단계 — 디자인 v2(홈 6.2·내 여행 6.4) + 경로선·수집 기준** (스크린샷 `Claude outputs/design-v2/`)
- 경로선 직선 원인(DB로 확인): 예전 `--video`는 1957점 중 255점만 보냈고 앱은 1초 간격 제한으로 333행만 저장(점 간격 중앙값 303m, p90 1.5km, 최대 6.5km). 수정: `demo_route.py`가 모든 프리셋에서 원본 점을 빠짐없이 보내고 100m 넘는 직선 구간만 보간(`--max-spacing`, 원본 점이 곧 도로 굽이), 영구 에뮬레이터 콘솔 소켓으로 0.12초 간격 전송(약 7분, GPX 2785점 재생성). 앱은 DEMO에서 위치 요청 간격 0.
- 처음 시도한 30m·20Hz는 앱이 감당 못 함(재현·계측): 점 하나 저장에 최근 점들로 지역 확정을 다시 계산해 20점 묶음이 약 1초 → 처리량 약 20점/초. 에뮬레이터가 같은 fix를 두 번 넘겨 입력 40점/초 → 작업 큐가 200초 이상 밀림 → 10초 신선도 검사가 세그먼트를 계속 끊고(`receipt_gap`) 결국 위치 끊김, 안성·천안 이야기 미재생. 수정: 100ms 안 같은 좌표 중복 fix 제거(`AndroidLocationSource`, 1초 간격일 땐 발생 안 함) + 8Hz. 재검증(debug, 영상 모드): 세그먼트 1개·끊김 0·밀림 0, 최대 점 간격 100m, 5개 대표 이야기 모두 청취, 19개 지역.
- 모은 지역 = 이야기를 하나라도 끝까지 들은 시군구: 기존 이야기 수집 기록(완료 재생 증거) + 이야기의 지역 연결로 계산(`RoomRecordsAtlasStore.heardRegions`, 새 테이블 없음). 부모 시(용인시·천안시) 이야기와 DEMO 같은 시 폴백 이야기는 그 이야기를 들을 때 마지막으로 들어가 있던 관련 시군구에 붙임. 지도 덮개·진입 애니메이션·지역 앨범·홈 숫자 모두 같은 기준, 지나가기만 한 지역은 회색 유지(선은 그대로).
- 홈: 꺼짐 = 3단계(아이콘+글) + 준비 상태(위치·마이크·내비 위 표시, "설정 필요" → 해당 권한 요청, 위치는 Pleos→Android 2단계 연속, 표시 권한은 설정 화면 또는 안내 문구) + 동행 시작. 켜짐 = 현재 지역 크게 + 지금 듣는 이야기/음악·곡명(1초 갱신), 지역 확정 전 "출발하면 이야기를 들려드릴게요". 내 여행 카드 = 자체 지도 축소판(애니메이션 없음, 들은 지역만 열림 + 다닌 길, 기록 범위 맞춤) + 숫자, OFF 직후 기록 다시 읽음.
- 하단 바 아이콘: 소스에 예전 모양 리소스 없음(런처 어댑티브·round·알림 아이콘 모두 새 모자, 예전 `ic_petasos`는 d95dcb9에서 이미 삭제). 예전 release(57e342c, 원형 모자)를 설치하면 앱 목록은 원형 모자인데 하단 바는 새 모자 → 하단 바(CarSystemUI)가 패키지별 아이콘을 따로 캐시. 재설치·홈 화면(ai.umos.homescreen) 재시작·에뮬레이터 재부팅으로도 안 바뀜, SystemUI 강제 종료는 user 빌드라 무시됨. 스크린샷 `05b`.
- 테스트: `CaptioningRuntimeSpeechTest` 2개를 현재 동작에 맞게 수정(허가는 시작 권한만, 입력 콜백은 lastHeard 기록용으로 감쌈) → JVM 963개 전부 통과.
- 확인(새로 설치한 release): 첫 실행 → 준비 상태 버튼으로 권한 3개 → ON → `--video` 전 구간 → OFF → 홈 7곳·이야기 9편 → 내 여행(네이버 지도, 경로가 경부고속도로를 따라감, 들은 지역만 열림).
- 내비 위 말하기 버튼: 앱 로고(모자) → 흰 마이크(`ic_mic`, 동행 카드·자막 카드와 같은 아이콘). 답변 준비 중에는 마이크를 줄여 올리고 아래에 점 3개. release에서 대기·듣는 중·답변 준비 중·실패 4상태 확인(`06*`). DESIGN.md 6.3의 "흰 모자" 문구는 아직 그대로.
- 자막 카드 위치: 내비 검색창 위(하단 200dp, 지도 폭 전체)에서 지도 오른쪽 아래로(내비 왼쪽 패널 오른쪽 ~ 음량·설정 버튼 왼쪽, 하단 108dp). 내비 대기·경로 안내 두 상태에서 내비 컨트롤과 겹치지 않음 확인(`07a`·`07b`). Petasos 화면이 앞에 있으면 버튼·카드를 숨기고 터치도 통과(`AppInFront`, 재생 계속), 내비로 나가면 다시 보이고 버튼 동작 확인(`07c`).
- 환경: 에뮬레이터 재부팅 후 DNS가 안 되면(IP ping은 되는데 도메인 해석 실패, 내비 "서비스 연결 중" 멈춤) `-dns-server 8.8.8.8,1.1.1.1`로 부팅하면 해결.
- 환경: 세션 중 에뮬레이터 기본 네트워크가 사라져(호스트는 정상, 에뮬레이터 `ping` unreachable, 비행기 모드 토글로 복구 안 됨) 내 여행이 자체 지도로 대체됨(`fallback reason=no-network`), 재부팅 후 복구. 내비 위 표시는 우리 홈 화면 위에도 그대로 떠 있음(기존 동작).

**8단계 — 콘텐츠 파이프라인 1차 전 + 창밖 안내** (`docs/CONTENT_PIPELINE.md` 7장 "1차 전", 스크린샷 `Claude outputs/landmark-v1/`)
- 서버 파이프라인(`server/petasos_server/pipeline/`): 출처 어댑터 TourAPI(기존 `TourismClient` 재사용, 지역 목록 `areaBasedList2` + 위치 목록 보충 + `detailCommon2` 개요)·국가유산청(목록/상세, 키 불필요) → 공통 `Place`/`Fact`/`SourceDocument`, 파일 캐시 `.local/content/cache`(같은 출처 ID 재조회 없음), 라이선스 필드 필수. TourAPI 위치 목록은 대전 쪽이 거의 비어 있어(반경 10 km 관광지 3곳) 경로가 지나는 시군구는 지역 목록으로 받음.
- 1단계 정리 때 함께 지워졌던 TourAPI/Odii 계약 스냅샷을 `server/petasos_server/reference/`로 복원 → 서버 테스트 실패 71 → 5(남은 5개는 옛 promptVersion·데모에서 뺀 프라이버시 차단 기대값, 변경 전부터 실패).
- 팩 형식 v3 = 기존 번들 + `packId`·`version`·`createdAt`·`promptVersion`·`scoringVersion`·`scope`·`landmarks[]`, `stories[].interestScores`는 선택(1차 전 비어 있음). 서버 `content_bundle`·앱 `ContentBundleCodec`/`RoomContentInstaller`가 같은 규칙으로 검증·설치(새 설치 경로 없음, 랜드마크는 기존 `place.landmark_policy_json` 열 재사용 → DB 스키마 변경 없음). 경로 좌표 때문에 코덱은 소수 허용 파서를 쓰되 정수 필드는 그대로 정수만 받음.
- 프롬프트: `server/prompts/`(guide_persona·story·answer·summary_resume·landmark_intro, 파일마다 버전). 합친 시스템 프롬프트는 분리 전 상수와 바이트 동일(sha256 테스트). summary/resume 문장은 문서 목록 밖이라 `summary_resume.md`로 따로 둠.
- `server/tools/build_pack.py --scenario seoul-daejeon [--install]`: 경로 2 km 안 장소 → 보임 규칙(`visibility.py`: 탑·전망대·경기장·대교·산성·산=large, 그 외 small, 유물·기록유산·골목·도서관 등 제외, 가로 거리가 small 150 m/large 1 km 안인 것만) → `Scorer`(rules-v0: 유산 등급·분류·개요 길이·경로 거리) → 지역당 최대 3곳 → intro(KOGL-1은 모델, KOGL-3·국가유산청은 원문 한 문장 인용, 인용 factId 검사) → 팩 + 검토표 `demo/packs/seoul-daejeon.review.md`. `InterestScorer`는 자리만. `GET /v1/packs/region/{code}`는 같은 `build_region_pack`을 부르는 뼈대(설정에 `packs`가 없으면 503).
- 시나리오: `demo/scenarios/seoul-daejeon/`(scenario.json·route.json·route.gpx), `demo_route.py --scenario`. 이야기 10편·문단·해시는 그대로 팩에 옮김(빌드 시 기존 번들과 동일 비교, 앱 테스트로도 확인). 랜드마크 11곳.
- 앱 창밖 안내: 기존 랜드마크 정책 체계(승인 목록·SHA 고정·300 m/180초·내비 경로 없을 때만, 관련 테스트 포함) 삭제. `WindowLandmarkGuide`(최근 GPS로 진행 방향·속도, 앞 ±20°/앞 옆 20~60°/옆 60~120°, 그 뒤는 말하지 않음, 가로 거리 반경, 가장 가까워지기 12초 이내에 도래, 시속 252 km 초과는 무시, 여정당 1회) + `InterjectingRuntimeSpeech`(문단을 문장 단위로 TTS → 현재 문장 끝에 "{방향} 창밖으로 {이름}이/가 있어요. {intro}" 삽입 후 다음 문장, 재생 상태기계는 변경 없음, 문장 간격 약 70 ms). 음악 중이면 음악을 멈추고 포커스를 반납했다가 같은 위치에서 재개(DEMO 15초 타이머는 실제 재생 시간만 셈). 대화 중(버튼·듣기·답변)이면 버림, 다른 안내가 말하는 중이면 다음 GPS에서 다시 시도. 자막 카드: 랜드마크 아이콘 + "오른쪽 앞 · 한빛탑" + intro, 이야기 자막은 이제 실제 말하는 문장. 설정 "창밖 안내"(기본 켜짐). 내비 경로 유무와 무관.
- 영상 프리셋: 빠른 재생 구간은 시속 252 km를 넘어 안내하지 않으므로 scenario.json `video.landmarks`(반포대교 분수·청계산·용인포은아트홀·한빛탑) 앞 500 m~뒤 150 m만 28 m/s·20 m 간격으로 재생(GPX 8.6분, 2895점).
- 확인(debug, 새 설치, `--video` 1회, 빌드 동시 실행 없음): 안내 4회 — 앞쪽·반포대교 달빛무지개분수 / 오른쪽 앞·청계산 / 앞쪽·용인포은아트홀(직후 S03 시작) / 왼쪽 앞·한빛탑, 대표 이야기 5편 각 2문단 완료. 이야기 중 삽입: 한빛탑 650 m 앞에서 ON → S10 1문장 → 한빛탑 안내 → S10 다음 문장(debug·release 모두 TTS 로그로 확인). 대화: 질문 → 답변(문장 단위 자막) → 음악 복귀. release 새 설치: 첫 실행 → 이야기 중 안내 → 설정 끄면 같은 접근에서 안내 없음 → 다시 켬.
- 첫 회차에서 같은 Mac에서 gradle 빌드를 돌리자 에뮬레이터가 굶어 화면 프레임 최대 13초·작업 큐 약 18초가 밀렸고 S05를 놓침(앱 로직 아님). 재생 중에는 빌드하지 않을 것.
- 남은 것: 랜드마크 표 사용자 검토(빼기·고치기 후 `build_pack.py` 재실행 → 앱 asset·`BundledStoryCatalog.SHA256` 교체), 국가유산청 설명문 이용 조건 확인, README의 `demo/route.*` 경로 표기(이번 범위 밖이라 그대로), 홈 "청취 기록 초기화" 후 내 여행 숫자 갱신 여부 미확인.

**9단계 — 랜드마크 목록 정리 + 방향 말하기 수정** (`docs/CONTENT_PIPELINE.md` 6장 갱신판, 스크린샷 `Claude outputs/landmark-v2/`)
- 팩 v2(promptVersion `landmark-intro-v2`): 랜드마크 4곳만 유지 — 반포대교 달빛무지개분수(large, 왼쪽 92 m)·청계산(large, 오른쪽 178 m)·용인포은아트홀(small, 왼쪽 75 m)·한빛탑(large, 왼쪽 179 m). 선택은 `scenario.json` `landmarks.include`(place id 허용 목록, 사람 검토 결과)로 고정, 나머지 7곳은 뺌.
- intro: 분수는 기네스 등재 중심(운영 중 표현 금지), 포은아트홀은 포은 정몽주의 호에서 딴 이름 중심, 둘 다 `landmarks.notes`(편집 메모, 제공 사실 중 무엇을 강조·배제할지만 지시)로 유도. 청계산은 TourAPI(KOGL-3) 대신 한국민족문화대백과사전 청계산 항목을 대조해 쓴 자체 사실문장 4개(소속 지역·높이 618 m·이칭 청룡산·동쪽으로 경부고속도로)를 `demo/scenarios/seoul-daejeon/curated_sources.json`의 `INDEPENDENT_FACT_STATEMENTS_ONLY` 출처로 사용(기존 용인시 연혁 출처와 같은 방식, 좌표는 별도 출처 행). 프롬프트에 "지금·오늘 일어나는 일 금지", "제공된 사실 밖 배경지식 금지" 추가, 검증기도 "지금/오늘/매일/가동/운영 중" 표현을 거부.
- `build_pack.py`: 변경금지(KOGL-3)·유형 미확인 원문(`License.allows_modification=False`)은 intro로 쓰지 않음(인용도 안 함). curated 출처가 있으면 그것으로 대체.
- 고속도로 구간(32~128 km) 대형 후보 거리(경로 기준, 대표 좌표): 독립기념관 2.6 km 좌·흑성산 4.4 km 좌·흑성산성 3.4 km 좌는 2 km 밖, 천안삼거리공원 0.5 km 우(공원, 외관으로 알아보기 어려움)·천안 영남루 0.4 km 우(문화유산자료, small 반경 밖)·한국잠사박물관 0.3 km 좌(KOGL-3). 2 km 안에 큰 건물·탑·산이 없어 xlarge 등급은 만들지 않음.
- 앱 방향: 지나갈 때의 가로 위치 부호로 말함("잠시 후 오른쪽/왼쪽으로 ○○이 보여요"), large이면서 진행 방향 ±20° 안이고 200 m 이상 앞이면 "앞쪽 멀리 ○○이 보여요". 카드 라벨도 같은 표현("앞쪽 멀리 · ○○").
- 서버 테스트 실패 5개 정리: 옛 promptVersion 기대값 2개(v0.8-3 → v0.8-5) 수정, 데모에서 뺀 프라이버시 검토 만료 차단 테스트 1개 삭제·1개는 로깅 비활성 확인만 남김 → 서버 318개 전부 통과. 앱 JVM 938개 전부 통과. README의 옛 `demo/route.*` 경로를 `demo/scenarios/seoul-daejeon/`로 수정.
- 확인(새로 설치한 release, 빌드·다른 작업 없이 `--video` 1회, 8.6분): 안내 4회 — 앞쪽 멀리·반포대교 분수 / 앞쪽 멀리·청계산 / 잠시 후 왼쪽으로·용인포은아트홀 / 잠시 후 왼쪽으로·한빛탑(모두 문장 순서대로 말하고 이야기로 이어짐, 카드 라벨·자막 일치). 앞의 세 번은 음악 중, 포은아트홀 뒤 S03, 한빛탑 뒤 S10 시작 확인. 한빛탑 650 m 앞에서 시작하는 이야기 중 끼워 넣기는 1회차(debug·release)에서 확인.

**10단계 — 1차 제출 전 마지막 정리** (스크린샷 `Claude outputs/final-check/`, README용 `docs/screenshots/`)
- 방향 표현: "앞쪽 멀리"는 large이면서 지나갈 때 가로 거리 100 m 안쪽(그리고 아직 200 m 이상 앞)일 때만, 그 밖에 진행 방향 ±60° 안이면 "오른쪽 앞으로 / 왼쪽 앞으로 ○○이 보여요", 60° 밖은 "잠시 후 오른쪽/왼쪽으로"(`WindowDirection`). 실제 경로 시뮬레이션과 새로 설치한 release에서 확인: 반포대교 분수 앞쪽 멀리 / 청계산 오른쪽 앞으로 / 용인포은아트홀 왼쪽 앞으로 / 한빛탑 왼쪽 앞으로(카드 라벨 동일). 이야기 문장 → 안내 → 다음 문장은 한빛탑 650 m 앞에서 시작해 재현(release: S10 첫 문장 → "잠시 후 왼쪽으로 한빛탑이 보여요…" → S10 다음 문장).
- 음악 3곡 추가(모두 Chris Zabriskie, CC BY 4.0, 공식 원본 무변경, 앨범 페이지의 트랙 번호·길이와 파일 길이가 일치하는 것만): A New Day In a New Sector(3:06), The Temperature of the Air on the Bow of the Kaleetan(4:24), I Knew My Way Downtown and Walking Was Deluxe(4:31). 매니페스트·자산·`CREDITS.txt` 갱신, APK 64 → 79 MB. DEMO에서 계획기가 가정하는 음악 간격을 15 s로 제한(곡이 길어져도 다음 이야기 예측이 늦어지지 않게). 서울역 정지 상태에서 4곡이 차례로 반복 재생되는 것을 미디어 세션 메타데이터로 확인.
- README 최종: 앱 소개·영상 링크 자리·스크린샷 3장, 복사해 쓰는 설치·실행 블록, 선택 사항(AI 서버·CA·토큰 전달), SDK·API 표, 라이선스·출처, 알려진 한계. 앱 안 "데이터 출처"에도 TourAPI·백과사전 출처 추가.
- 버그 수정(README 절차를 새 설치부터 따라 하다 발견): 내비에 실제 경로가 잡혀 음성 안내가 시작되면 앱이 첫 이야기에서 멈췄다(9분간 내 여행 0곳). 문장 단위 TTS 래퍼가 내비 음성에 포커스를 뺏긴 뒤의 `FOCUS_REGAINED`를 버려 코디네이터가 계속 대기했던 것. 포커스 이벤트를 문단 소유자에게 전달하도록 고치고 회귀 테스트 추가(수정 전 실패 확인). 이전 회차들은 내비 경로가 없어(경로 탐색 오류) 드러나지 않았다.
- 최종 확인(새로 설치한 release, README 절차를 에뮬레이터 재부팅부터 그대로): 부팅(-dns-server) → 설치 → overlay 권한 2개 → hostmicon → 위치 고정 → 첫 실행 → 동행 시작(권한 3회) → `demo_route.py --video` 8.6분 → 안내 4회·이야기 10편·음악 → 동행 끝내기 → 내 여행(6곳·이야기 10편). 설정의 청취 기록 초기화("초기화했어요")도 확인.
- 비밀값 검사(전체 42커밋 + 작업 트리 + APK): `.env`의 API 키·서비스 키·네이버 클라이언트 시크릿·서명 비밀번호·기기 토큰·CA 개인키 모두 0건. APK에는 공개 지도 식별자(NCP 키 ID)만 있고 시크릿은 없음. 비밀 패턴(개인키·sk-or-·AKIA·ghp_ 등) 0건. 추적되는 인증서는 debug용 공개 CA뿐. 원격 저장소는 아직 없음.
- 알려진 가장자리: 안내 시점에 내비 음성 등으로 일시 정지 상태이면 그 안내는 버려지고 같은 장소는 다시 하지 않는다(1회차 재현 시 한 번 발생, 재시도에서는 정상).

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
