# 코드 구조와 UI 교체 경계

dev.31: [UI 표시 계층 분리](./presentation-separation.md). 재생·기록 설정을 순수 입력/상태 mapper와 View renderer로 분리했다. 실제 명령·동의·권한·수명주기는 기존 Controls에 남는다. XML·색상·문구는 유지하며 최종 UI 방향을 기다린다. [격리 데모](./isolated-ui-demo-plan.md)는 설계이며 아직 구현하지 않았다.

dev.24 추가: [검토 지역 정책 설치와 현재 권한](./2026-11-08-reviewed-region-policy.md). `location/ReviewedRegionPolicy*`는 Android 독립 형식 검증을, `RegionGeometryMetadata`는 원본 기하 동일성을, `storage/RoomRegionPolicyInstaller/Store`는 최초 확정과 현재 근거 조회를 담당한다. `platform/android/location/BundledRegionPolicyCatalog`는 고정 APK 자산을 worker에서 읽어 설치한다. 현재 빈 승인 목록은 실제 지역을 활성화하지 않는다.

`ConfirmedRegionAuthority`는 원시 위치 권한과 분리된 원자적 지역 권한이다. 저장소에서 얻은 판정 revision·정책/출처 fingerprint를 편성·생성·최종 출력에 전달하고, DB 재검사는 worker에서 실행한다. UI/main은 메모리의 철회 상태만 읽는다. Room schema 2와 기존 proof를 유지하며 UI 파일은 수정하지 않는다. 공식 Android 위치 연결과 실제 전면 수신은 [dev.23 기록](./2026-11-08-android-location-integration.md)을 따른다. 아래 이전 단계의 구조 설명과 당시 검증 수치는 해당 시점 기록으로 보존한다.

구현 기준: 2026-11-08, `0.8.0-dev.18`. [공식 지역 경계 설치](./2026-11-08-official-region-boundary-import.md)는 변환 도구·순수 스트리밍 기하·Room 출처 설치·Android assets 조립으로 분리했다. 현행 v0.8의 결정과 시간 제한, 기존 프리뷰의 외형과 UI/실행 계층 분리를 유지한다. [dev.16 검토 음악](./2026-11-08-reviewed-music.md)은 최종 privacy·debug 빌드/Lint, JVM 502개/40 suite·호스트 117개·기기 13종과 설치·실행, 실제 출처 UI 확인을 통과했다. [dev.15 명시 재생 명령](./2026-11-08-explicit-playback-commands.md)은 최종 privacy·debug 빌드/Lint, JVM 435개/34 suite·호스트 115개·기기 12종과 설치/실행 smoke를 통과했다. 음성 서비스 로그 차단 조건은 [dev.13 기록](./2026-11-08-speech-privacy-verification.md)을 따른다. 이 문서는 코드 책임과 실행 경계를 설명한다.

## 현재 디렉터리

```text
app/src/main/
  java/com/petasos/app/
    MainActivity.kt                 # 레이아웃·Activity 수명주기·권한 결과 전달
    PetasosApplication.kt           # WorkManager 설정·시작 복구/요약 예약
    presentation/                  # MediaBrowser 제어·정차 화면 visibility·명시적 Talk
    playback/                      # UI·SDK 독립 Coordinator와 이벤트/효과 계약
    runtime/                       # 직렬 효과 실행·타이머·음성/음악/생성/복귀/권한 포트
    location/                      # 위치 품질·batch·단절·연속 지역 확인 순수 로직
    planning/                      # 지역·명시 관심사 후보·자동 출력 권한·복귀 현재성 검사
    music/                         # 검토 음악 manifest·원자 설치·경로·hash·권리·출처
    generation/                    # HTTPS·strict JSON/schema·암호화 설정·생성 어댑터
    summary/                       # 요약 실행·저장소 계약·재시도·복구 gate
    content/                       # 검토 콘텐츠 DTO·참조/권리/hash 검증
    records/                       # 차량 설정·이동 결과 DTO와 순수 검증
    storage/                       # Room·Keystore·TX01~TX05·콘텐츠/위치/내비/복구 저장
    capability/
      location/                    # SDK 타입 없는 위치 입력 계약
      navigation/                  # 읽기 전용 내비 상태·route revision 계약
      speech/                      # 음성 출력/입력 계약과 이벤트
    platform/android/runtime/      # 단일 제품 host·전용 worker queue·monotonic clock
    platform/android/music/        # APK 번들 → worker 소유 로컬 카탈로그
    platform/android/summary/      # WorkManager 고유 작업·Worker·복구/예약 연결
    platform/automotive/            # 공개 Car UX·AUDIO/MICROPHONE 전원 정책
    platform/media/                 # MediaLibraryService·Coordinator Player·ExoPlayer 음악
    platform/pleos/
      location/                    # FusedLocation 실제 읽기 연결
      navigation/                  # NaviHelper 실제 읽기 연결
      speech/                      # TTS·STT·AudioRecord 소유 PCM·runtime bridge
  assets/music/                    # 검토한 원본 음원·manifest·출처 고지
  resources/generation/            # 앱에 포함하는 v0.8 생성 JSON 계약
  res/
    layout/activity_main.xml       # 화면 구성과 배치
    layout/view_trip_controls.xml  # 기록 설정·이동 결과 기능 배치
    values/strings_music.xml       # 음원 출처의 고정 문구
    values/strings_records.xml     # 기록 기능의 고정 문구
    values/colors.xml              # 색상
    values/styles.xml              # 테마·글꼴·텍스트 역할
    values/dimens.xml              # 여백·간격·모서리·아이콘 치수
    values/strings.xml             # 사용자에게 보이는 고정 문구
    drawable/                      # 배경 도형·벡터 아이콘
    xml/                           # 백업/전송 제외·개발 TLS 신뢰 범위
app/schemas/                       # Room schema export
app/src/androidTest/               # 실제 SDK/Room/Media3 검증·합성 fixture 전용
app/src/test/                      # 순수 상태·권한·위치·편성·PCM·생성/요약 검사
app/privacy-rules.pro              # opt-in R8의 확인된 음성 로그 sink/모델 보존 규칙
app/privacy-test-rules.pro         # privacy instrumentation 진입점·fixture 보존
server/
  petasos_server/                  # ASGI·인증·생성·근거 재검사·공공 API·콘텐츠 컴파일
  tests/                          # 합성 provider·HTTP/TLS fixture·회귀 검사
scripts/                          # 빌드·설치·실제 API/SDK 검증 실행
docs/                             # 현행 명세와 개발 증거
spec/                             # 참조 SQL/JSON 계약·검사
artifacts/                        # 비밀값·원문을 제외한 로컬 검증 결과
```

현재 규모에서는 하나의 Gradle 앱 모듈 안에서 책임별 패키지를 분리한다. `capability/`, `playback/`, `location/`, `planning/`의 계약과 순수 정책은 Android View나 Pleos SDK 타입에 의존하지 않는다. `platform/`은 이를 실제 Android/Pleos API에 연결한다. 저장소는 UI 리소스를 참조하지 않으며 Activity가 SQL·SDK 객체·생성 요청을 직접 실행하지 않는다.

## 검토 음악과 자동 공급

`music/ReviewedMusicBundle`은 strict manifest·권리·정확 크기/SHA·출처를, `ReviewedMusicInstaller`는 같은 디렉터리의 bounded 복사·fsync·atomic rename을 담당한다. `platform/android/music/BundledMusicCatalog`는 worker에서 APK 원본을 앱 전용 no-backup 경로에 설치하고 불변 snapshot을 공개한다. 현재 실제 번들은 CC BY 4.0의 「Prelude No. 1」 1곡이며 원본을 변경하지 않았다. 출처와 추가 규칙은 [dev.16 기록](./2026-11-08-reviewed-music.md)을 따른다.

`AutomaticMusicPlaybackBridge`는 명시 재생 의도 안에서 다음 후보 1개만 공급한다. Stop/차단은 후보를 철회하며 실패는 의도를 내려 자동 재시도 루프를 막는다. Media3는 repeat OFF이고, 단일곡이 다시 선택돼도 별도 시도로 등록한다. 이야기/음악의 교대 허용과 정상 완료 저장은 Coordinator/TX-02가 소유한다. 파일 hash는 worker에서 검증하며 실제 player를 만들고 prepare/play하기 직전 main에서는 현재 권리 snapshot·만료와 Stop fence만 읽는다. 음악 Started를 한 번만 저장하고 core에도 전달한다.

`MusicCreditsControls`는 정차 화면의 읽기 전용 출처만 표시하며 비가시화/host 교체 시 대화상자와 늦은 결과를 차단한다. `StorySelectionIntent`는 스피너 표시값과 명시 선택을 구별해 음악 정지 후 Play가 이전 이야기를 재선택하지 않도록 한다. 시스템 미디어 metadata는 실제 곡명과 작곡자를 표시한다. 카탈로그 읽기나 출처 조회는 세션·재생을 만들지 않는다.

## 확정 입력의 로컬 재생 명령

dev.15의 `playback/ExplicitPlaybackCommandParser`는 저장된 최종 입력 전체가 허용 표현과 일치할 때만 5종 명령을 반환한다. `runtime/PlaybackRuntime`이 최종 저장과 입력 종료 ACK 이후 원래 시작 권한을 확인해 `ExplicitCommandResolved`를 전달한다. Coordinator가 turn 동결·checkpoint 저장과 정지/복귀를 결정하며 UI·서버·모델 응답을 명령 판정에 사용하지 않는다. 일반 질문·부정·복합 문장은 기존 답변 경로다.

명령은 answer/resume 모델 요청과 가짜 answer 저장을 생략한다. 사용자-only turn의 후속 요약은 기존 정책에 따라 모델을 호출할 수 있다. 음악/다시 듣기 대상이 없으면 고정 안내 후 정지하며, 명시 Next의 세션 story 제외는 자동편성 갱신 전에 전달한다. 기존 화면·리소스 분리와 마이크 차단을 유지한다. 지원 문구·시험 범위·최종 상태는 [dev.15 기록](./2026-11-08-explicit-playback-commands.md)을 따른다.

## 새 이야기와 복귀의 책임

`GenerationPlanningContract`/`GenerationClient`가 wire DTO·현재성·15초/3초 제한을, `RoomStoryGenerationStore`가 읽기 snapshot·근거 pool·TX-05 새 버전 설치를 담당한다. `RoomResumeGenerationContext`는 암호화 frozen 대화의 실제 완료 부분만 읽으며, `ApiRuntimeResumePlanner`를 `ContentCheckedResumePlanner` 안에서 조립한다. `PlaybackCoordinator`는 후보와 fallback, `PlaybackRuntime`은 고정 재생 authority와 수락 ACK, `PetasosSessionHost`는 명시적 준비 job·취소·저장 후 재생 연결을 소유한다. 화면에는 버튼/문구/상태 바인딩만 추가했다.

## 제품 실행과 화면

`PetasosMediaService`가 단일 `PetasosSessionHost`와 `CoordinatorSessionPlayer`를 소유한다. Media3 1.11.1의 표준 MediaBrowser/MediaLibraryService 명령은 host의 Play/Stop/Next/Previous로 전달되고, 실제 재생 상태는 Coordinator에서 시스템 Player로 투영된다. 허용된 설치 이야기 ID만 선택할 수 있다. 임의 외부 URI·임의 seek·마이크 custom command는 받지 않으며 외부 controller의 신뢰 여부를 검사한다. ExoPlayer는 음악 출력만 담당하고 시스템 명령의 직접 대상이 아니다.

서비스 생성·라이브러리 조회·prepare는 이동 세션, 재생, 위치 구독, 마이크를 시작하지 않는다. 사용자의 명시적 Play 또는 새로 만들어 듣기에서 필요한 runtime을 만든다. 새 이야기 요청은 생성/설치가 끝나기 전 자동 Play를 보내지 않는다. 같은 프로세스의 보이는 화면에서 명시적 Talk만으로 IDLE 세션을 만드는 경로도 준비되어 있으며 이 경로에는 Play를 자동 전달하지 않는다. 다만 아래 SDK 로그·실시간 입력 제한 때문에 제품의 실제 음성 입력 gate는 기본 비활성이다. `PetasosSessionHost`는 RoomRuntimePersistence, PleosRuntimeSpeech, Media3RuntimeMusic, 실제 GenerationClient, 현재 콘텐츠를 검사하는 복귀 planner, 위치/내비를 단일 worker queue에서 조립한다. 세션 종료는 입력·출력 정리와 저장을 거치고, 확인되지 않은 종료를 성공으로 저장하지 않는다.

`MainActivity`는 수명주기와 권한 결과만 `presentation/PlaybackControls`로 전달한다. PlaybackControls는 설치 이야기 목록, 표준 미디어 버튼, 고정 상태 문구와 Talk 요청을 연결한다. RECORD_AUDIO는 명시적 Talk 클릭에만 요청하고, 허가 결과 뒤에도 새 클릭을 요구한다. TALK_PENDING/PROMPT/LISTENING의 재클릭은 취소이며 GENERATING/ANSWERING 중 재입력은 허용하지 않는다. onPause/onStop 또는 UX 제한은 visible-input gate를 내린다. 원문 발화·생성 본문·정밀 좌표·오류 원문은 이 화면에 표시하지 않는다. [정차 화면 연결](./parked-controls.md)

제품 UI와 host는 별도 live voice readiness gate로 실제 마이크 진입을 막는다. 이 gate가 비활성인 현재는 마이크 권한을 받았거나 화면이 허용되어도 음성 대화를 시작할 수 없다. 권한·명시 클릭·IDLE Talk 배선의 구현과 제품 음성 기능 활성화는 별도 상태다.

색상은 `colors.xml`, 텍스트 역할은 `styles.xml`, 치수는 `dimens.xml`, 배치는 `layout/`, 표시 문구는 `strings.xml`에서 바꾼다. `preview_*`와 `Widget.Petasos.Preview.*`는 임시 이름이다. 새로운 화면 정보 구조와 상호작용은 `presentation/`도 수정해야 하지만 재생·저장·SDK 코드를 함께 고칠 필요는 없다. 향후 UI 디자인 논의는 현재 기능 연결과 별도로 진행한다.

## 기록 설정·이동 종료·결과의 책임

`records/`는 차량 설정과 이동 결과 DTO를 소유한다. `RoomVehiclePreferenceStore`는 명시 저장의 version CAS와 다른 설정 보존을, `RoomTripResultsStore`는 저장된 요약·실제 시도/위치 품질 집계·수집 proof의 읽기 전용 snapshot을 담당한다. `TripControls`가 같은 프로세스 host의 기능을 호출하고 `TripResultsFormatter`가 결과 문구를 만든다. 별도 XML·문자열 리소스를 사용하므로 이후 화면 배치를 바꿔도 Room/SDK 종료 계약은 유지한다. 화면이 숨겨지면 관심사·요약 표시를 지운다.

명시 이동 종료는 Media Stop과 구별한다. host가 시작 fence와 위치 callback 경계를 먼저 닫고 `PlaybackRuntime.close(callback)`의 실제 stop·저장 ACK를 기다린 뒤 `RoomTripLifecycleStore`가 `user_end`를 원자 저장한다. 10초 UI deadline은 성공 ACK가 아니며 실패 시 재생 차단을 유지한다. 세션/구간의 과거 unknown 종료 시각을 채우지 않고 요약 작업과 기록을 보존한다. 완료 후 host는 runtime을 비우며 최신 trip이 closed이면 이전 interrupted trip을 다시 골라 재생하지 않는다. 다음 명시 Play가 새 trip을 만든다. 설정 조회·관심사 저장·결과 읽기 자체로 세션이나 위치 수집을 만들지 않는다.

## 음성·음악과 차량 정책

실제 Pleos TTS/STT는 3.3.1이다. 출력 요청은 operation token으로 구분하며 실제 Started/Completed/중지 확인과 저장 결과를 Coordinator에 전달한다. Stop ingress와 세션·취소 세대는 대기 중 SDK 시작과 늦은 콜백을 차단한다. SDK 콜백에 요청 ID가 없으므로 음성 어댑터는 요청마다 새 인스턴스를 만들며 종료된 인스턴스의 결과를 채택하지 않는다. dev.14의 startPreparedOutput은 실제 Connected 뒤 worker에 최신 본문·출처/offer 검사를 요청하고 main에서 RuntimeSpeechPermit의 원자적 상태·Stop fence·offer 신선도를 다시 확인한 뒤 speak한다. 연결 전에 본문을 보관하거나 main thread에서 Room을 읽지 않는다.

제품 입력은 `PleosPcmSpeechInput`이 Android AudioRecord를 소유하고 16kHz PCM16 mono를 공개 `sendAudio()`로 전달한다. SDK 소유 마이크를 여는 기존 `request()` 경로는 진단용 어댑터에 남긴다. 입력 종료는 동일 녹음기의 stop 정상 반환·STOPPED 상태·release 성공으로 확인한다. EPD_OFF나 1초 SDK drain을 녹음 종료 증거로 치환하지 않는다. 짧은 메모리 버퍼만 사용하며 원음을 파일에 저장하거나 서버로 전송하지 않는다. 종료가 확인되지 않으면 입력 종료 차단을 유지한다. 유한 합성 PCM과 EOF 없는 연속 입력, 사람 발화는 별도 시험이다. [PCM 종료·차량 정책 계약](./2026-11-07-owned-input-and-vehicle-policy.md)

STT/TTS 3.3.1의 확인한 원문 로그 경로는 opt-in `privacy` R8에서 제거했다. 이 빌드는 non-debuggable이며 기본 debug와 별도로 선택한다. 모든 DEX의 실행 명령·모델 필드/생성자/annotation·음성 제어 참조를 검사하고 실제 합성 STT/TTS 기능도 확인했다. 그러나 별도 Pleos SpeechService는 앱 R8 적용 대상이 아니며 8회 모두 서비스 합성 원문 일치가 관측됐다. 제품의 실제 마이크 경로는 계속 차단한다. 앱 측 관측 0건을 서비스 무로그나 모든 SDK 모드의 안전성으로 해석하지 않는다. dev.21에서 재부팅 후 첫 합성 STT를 포함한 4회 시간 기준과 실제 EOF 2회를 확인했다. 서비스 내부 엔진 coldness와 사람 발화는 확인하지 않았다. [정적/실행 증거](./2026-11-08-speech-privacy-verification.md)와 [SDK 감사](./2026-11-08-speech-client-audit.md)를 따른다.

`music/LocalMusicCatalog`는 검토된 로컬 파일만 경로·SHA-256·재생 허가·유효기간으로 확인한다. 모델 응답이나 외부 MediaItem URI로 음악을 등록하지 않는다. RoomRuntimePersistence가 음악 자산 ID·재생 위치 checkpoint와 시도를 저장하고 Media3RuntimeMusic이 한 곡을 반복 없이 출력한다. STATE_ENDED만 정상 완료이며 Stop/Talk/Next는 중단과 실제 위치를 기록한다. Previous는 곡 처음의 새 시도다. 이야기를 정상 완료했거나 이미 음악을 시작한 뒤의 다음 자동 이야기에는 정상 곡 완료 커밋이 필요하고 skip·중단은 이 조건을 해제하지 않는다. 정상 완료가 focus 차단 중 커밋되면 빈 복귀 후보는 다음 편성으로 이어지지만, 유효 후보에 대한 model wait는 늦은 offer로 덮어쓰지 않는다. 미완료 이야기의 명시 Next는 다른 허용 후보를 선택할 수 있다. dev.22는 같은 이동의 실제 완료/시도 기록에서 음악 간격을 복원하도록 Room과 Host를 연결했다. 저장된 포인터나 벽시계로 음악 완료를 추정하지 않으며 자동 재생은 허용하지 않는다. dev.16은 검토한 CC BY 4.0 원본 1곡을 제품 카탈로그에 연결했다. 검증용 WAV는 별도 테스트 APK 전용이며 실제 제품 음원과 구분한다.

`CarPolicySource`는 공개 CarUxRestrictionsManager와 CarPowerManager의 현재 값을 조회하고 구독한다. 첫 unknown·단절·제한은 전체 프리뷰를 숨기며 정차/연결 확인 안내를 표시한다. UX 제한만으로 허용된 배경 오디오를 중단하지 않는다. AUDIO 차단은 POWER_AUDIO blocker, MICROPHONE 차단은 별도 입력 gate로 적용한다. 미확인 전원 상태는 허용으로 간주하지 않는다. 이 컴포넌트 정책 연결은 실제 Suspend/Resume 콜백 구현이나 실차 시험 완료를 뜻하지 않는다.

## 저장·복구·검토 콘텐츠

`storage/`는 기존 Room v1 스키마와 Keystore 암호화를 사용한다. 실행 상태 전이는 Coordinator에, TX01~TX05의 원자적 저장은 저장소에 둔다. TX-04는 중단된 세션·구간 정리와 명시적 새 세션 생성이며 지역 카드 적립의 이름이 아니다. RoomSessionRecovery는 이전 prepared/playing 시도를 interrupted로 정리하고 대화 동결·취소 세대·암호화 중간 응답 상태를 보존한다. 관찰하지 못한 종료 시각을 만들어 넣지 않는다.

`SummaryBackground.ensureRecovered()`의 once gate가 세션 복구 후 요약 복구를 실행한다. Application 시작 작업, Worker와 host의 명시적 runtime 생성이 같은 gate에 참여하므로 늦은 시작 복구가 새 세션을 중단시키지 않는다. 실패한 복구는 완료로 캐시하지 않는다. 복구 후보는 저장된 참조이며 실제 재생 전 콘텐츠의 현재 hash·출처 허가를 다시 확인한다. 이전 play intent만으로 앱 시작 시 자동 재생하지 않는다.

`content/`는 검토 파일→서버 컴파일→앱 strict 계약 검증→TX-05 설치 경계를 소유한다. RoomContentInstaller는 동일 묶음 재설치를 no-op으로 처리하고 revision을 덮어쓰지 않는다. `prepareStory()`와 `playInstalledStory()`는 준비 핸들을 연결하되 출력 등록 및 SDK 직전 현재 권한과 hash를 다시 검사한다. 콘텐츠 거부는 저장 장애와 구분하며 이미 등록한 출력은 실제 interrupted로 커밋하고 대화 동결·세션 종료를 계속 저장한다. 실제 자료의 권리와 좌표 보류는 [출처 기록](./2026-11-07-content-source-review.md)을 따른다.

## 위치·내비·편성

FusedLocation 1.1.3의 실제 입력은 `LocationNormalizer`와 `LocationRecorder`를 거쳐 최대 20개/1초 batch로 저장한다. 원본 timestamp/confidence와 수신 wall/monotonic 시각을 분리하며 동일 좌표만으로 중복 제거하지 않는다. host는 active session 커밋 후 `vehicle.location_enabled=1`과 플랫폼 권한을 모두 확인해야 기록을 시작한다. Media Stop은 기록 동의 해제나 이동 종료가 아니다. 세션 종료는 구간을 flush/마감하며 구독 실패와 단절은 자동 후보 권한을 철회한다.

실제 timestamp 원점·confidence 의미는 미확인이므로 기본 품질 어댑터는 UNKNOWN이다. dev.18 Host는 공식 NGII 경계를 지역별 assets로 스트리밍 조회하지만, 모든 방문 정책은 미검토 상태로 유지한다. 설치 오류·출처 철회·격리 bbox는 결과를 보류한다. 검토한 품질 매핑·경계 데이터셋·RegionPolicy 없이 자동 지역 안내나 카드를 활성화하지 않는다. 지역 확인은 연속 저장행의 품질·시간·정확도·경계 여유·정책 revision을 검사하고 위치 batch와 카드/proof를 같은 트랜잭션에 저장한다. 안정된 region ID는 revision이 바뀌어도 중복 적립하지 않는다.

NaviHelper 3.0.5의 읽기 전용 어댑터는 경로 상태·route ID·목적지 identity만 편성에 사용한다. route identity/목적지 변경과 reroute 완료만 revision을 바꾸며 GPS·잔여 시간/거리 갱신은 바꾸지 않는다. RoomNavigationStore는 active session revision을 단조 저장한다. null route는 숫자 revision을 유지한 현재 위치 모드이고 SDK 불가/권한 실패는 명시적 unavailable이다. 일반 들은 이야기 질문은 경로와 독립이며 route-dependent 후보만 revision 검사를 적용한다.

`RoomAutomaticStoryStore`는 현재지역 → 명시 관심 태그의 정확한 교집합 순서로 최신 published 콘텐츠를 조회하고, 현재 권리/hash·전역에서 한 문단이라도 completed인 story와 완료 문단의 stable fact를 제외한다. 이야기 전체 완료·카드 획득 판정과는 구분한다. `ContentPlanner`와 `AutomaticStoryPlaybackBridge`는 후보를 OfferStory/ClearOfferedStory로만 전달하며 직접 재생하지 않는다. 위치 unknown·오래된 위치는 지역 후보만 제외한다. 세션에서 시작하거나 명시적으로 건너뛴 story ID를 저장소의 결과 64개 제한 전에 제외하고, 명시 replay는 자동 반복 필터와 분리한다. 설정 변경 ingress는 기존 권한을 먼저 철회하고 저장 후 새 snapshot으로 편성한다. SDK 직전 정확한 offer/story/version/paragraph·위치 신선도·입력 상태·현재 DB 적격성을 검사한다. 실제 첫 출력 이후 일반 이야기를 단순 위치 변경으로 끊지 않는다. ContentCheckedResumePlanner는 core가 제시한 현재 유효한 checkpoint/music 후보만 선택한다. 명시 story 생성과 대화 후 서버 resume 선택은 dev.11에 연결했다. dev.17에 대상 지역 seed의 외부 자동 생성 코드 연결을 추가했다. 실제 경로·지역 데이터 공급과 랜드마크 접근/통과 정책은 후속이다. [dev.14 기록](./2026-11-08-automatic-story-planning.md), [위치·내비 계약](./location-navigation-contract.md)

## 생성·요약과 실제 API

제품 host는 `ApiRuntimeGeneration`을 실제 GenerationClient와 RoomContentContext에 연결한다. 문맥은 같은 session에서 완료된 이야기 문단의 현재 출처·fact 연결과 커밋된 발화를 사용한다. 요청 ID·turnVersion·근거 fingerprint·현재 권한이 달라지면 응답을 거부한다. 현재 모델 답변에서 실행하는 범위는 `continue`/`unsupported`와 null 대상이며, 모델의 music/next/replay/stop 명령 라우팅은 아직 허용하지 않는다. 표준 미디어 버튼의 명령은 이 모델 경로와 별개다.

GenerationClient는 HTTPS·기기 인증·strict JSON·canonical v0.8 schema를 검증한다. 서버 origin과 기기 토큰은 DeviceGenerationConfigStore가 Keystore로 암호화하고 백업 제외 영역에 저장한다. 개발용 HTTPS는 debug와 opt-in privacy APK에서 localhost 한정 CA 신뢰를 사용하며 일반 release에는 추가하지 않는다. hostname 검증을 유지한다. OpenRouter 키는 서버에만 있고 APK에 포함하지 않는다.

`summary/`는 Android·Room·WorkManager 독립 실행 정책이다. 제품 host가 turn 동결 커밋 후 WorkManager에 요약을 예약한다. RoomSummaryStore는 claim·생성 전후·TX-03에서 요청 시도·입력 HMAC·근거 fingerprint·현재 출처 권한을 재검사한다. 입력은 커밋된 사용자 텍스트와 재생 완료 응답으로 제한하고 미완료 응답은 키만 전달한다. 요약 저장·saved 상태·임시 암호문 삭제·요청 완료를 하나의 트랜잭션으로 처리한다. 실패 시 암호문을 보존하며 권한 차단·키 손상·일시 오류를 구분한다. WorkManager Data에는 turn ID만 넣는다.

서버는 인증·기기별 요청 소유권·멱등성·취소·provider 호출과 근거 검사를 분리한다. FileEvidenceRegistry는 운영자가 검토한 private 파일을 권한 경계마다 새로 읽고, 잘못된 교체 파일에 이전 승인을 대신 사용하지 않는다. 신규 요청·provider 전후·cache/동시 대기 응답 전달 전에 근거를 재검사하며 철회된 요청은 권한을 복구해도 같은 requestId로 되살리지 않는다. 이미 관찰한 revision 내용의 변경을 거부하고 장기 revision 보존은 운영 배포 책임으로 남긴다.

실제 공공데이터 API와 OpenRouter answer/summary를 호출한 기록이 있다. 공공 API는 공식 operation/input/output registry를 사용하고 수집 결과를 검토 전 pending으로 둔다. OpenRouter는 검토한 단일 모델/provider와 보관 조건, 검토 만료 시각을 적용한다. 실제 호출 성공·합성 transport 시험·앱의 사람 대화 품질은 구분한다. 현재 승인 상태·실행 결과·만료와 제한은 [실제 API 기록](./2026-11-07-live-apis.md), 설정·로컬 서버 실행은 [서버 README](../../server/README.md)를 따른다.

## 검증과 남은 연결

순수 JVM 검사는 Coordinator·출력 경계·후보·복구 계약·PCM 종료와 품질/지역 정책을 다룬다. 기기 probe는 실제 Room/Keystore·Media3·Pleos SDK·Car UX와 합성 입력을 명시적으로 구분한다. 서버 회귀 시험의 provider는 합성이고 실제 외부 모델 검사는 별도 실행이다. dev.14는 시작/Next 경합을 보완한 최종 privacy 빌드·Lint·JVM 393개/31 suite·기기 12종이 통과했다. 호스트 112개·기본 debug 앱/테스트 APK·Lint·privacy 설치/실행 smoke·최종 기기 상태와 225개 소스 일치도 통과했다. debug JVM은 별도 재실행하지 않았다. 초기 387개/12종과 중간 390개를 별도 보존하고 최종 결과와 합산하지 않는다. dev.15의 명령·최종 230개 소스와 기기 검증은 [dev.15 기록](./2026-11-08-explicit-playback-commands.md)에 모으며, dev.14 결과는 [기존 기록](./2026-11-08-automatic-story-planning.md)에 보존한다. 이전 결과는 [dev.13 기록](./2026-11-08-speech-privacy-verification.md)에 보존한다. dev.13 privacy는 JVM 348개/27 suite·호스트 108개·기기 회귀 19종·smoke PASS이며 별도 합성 음성 8회의 기능 PASS와 서비스 로그 MATCH_FOUND를 구분한다. 기본 debug 앱/테스트 APK·Lint 호환도 확인했다. 기록 기능의 [dev.12 검증](./2026-11-08-trip-records.md)과 dev.11 서버/참조 계약 결과를 보존하며 서버/참조 계약은 이번에 재실행하지 않았다.

제품 마이크는 SDK 원문 로그와 입력 시간 제한 문제가 해결될 때까지 비활성이다. 이후에도 사람의 실제 발화와 인식/응답 품질은 별도 검증해야 한다. 실제 Suspend/Resume·경쟁 오디오 포커스·주행 중 UI·백그라운드 위치·실이동, 검토한 위치 품질 의미·행정 경계·랜드마크 접근 정책, 추가 음악 자산과 대표 경로 콘텐츠, 외부 자동 생성 편성의 실제 경로/지역 입력 연결이 남아 있다. dev.15의 전체 문구 명령과 dev.17의 지역 생성 코드 연결을 넘어 임의 모델 명령을 실행하지 않는다. dev.13의 opt-in R8 privacy 합성 검증은 앱 로그 경로 축소와 기능 보존을 확인했으나 별도 서비스 로그 때문에 마이크 활성화 조건을 충족하지 못했다. 공식 설정/수정 버전/문의 창구 확인은 대기 중이며 문의 초안은 미전송이다. 최종 UI와 데모 시나리오, 공개 운영 배포와 장시간 안정성은 이번 기능 조립으로 완료 처리하지 않는다. Virtual Console은 계속 보류한다.

## dev.17 길이 기반 지역 편성

`RegionalRouteForecastSource`는 검토된 실제 위치·경로·지역 경계에서 통과 시간창을 만드는 공급자 포트다. 현재 제품 기본값은 `UNAVAILABLE`이며 목적지/경유지 좌표를 도로 경로로 사용하지 않는다. `RegionalStoryPlanner`는 예측 지역과 실제 현재 지역을 분리하고, 별도 planningRevision과 routeRevision으로 대기 후보와 생성 권한을 갱신한다.

`RoomStoryTimingStore`가 검토된 immutable 문단 길이를 읽고 `PlaybackLookahead`가 실제 출력 시작·음악 위치·현재 checkpoint로 다음 재생 경계를 계산한다. `PlanningBoundaryMemory`는 정상 커밋·Started 대기의 짧은 공백만 제한적으로 보존한다. `AutomaticStoryGeneration`은 세션당 한 준비 작업을 직렬 큐와 atomic 권한으로 관리하고 `PetasosSessionHost`가 기존 GenerationClient/RoomStoryGenerationStore를 조립한다. 생성 결과는 `AutomaticStoryPlaybackBridge`를 통해서만 공급하며 실제 재생은 Coordinator가 결정한다. 화면 스타일/레이아웃과 별개다.

현재 지역 적격성 저장소·관심사 fallback·현재 권리 검사·반복 방지는 계속 사용한다. 최초 Play는 명시 선택이 없으면 자동 경로로 진입하고, 선택 목록에는 자동 편성 항목이 있다. 실제 데이터 연결 제한과 검증 범위는 [dev.17 기록](./2026-11-08-duration-aware-regional-planning.md)을 따른다.


## dev.22 랜드마크 경계

`location/LandmarkApproach`는 검토 정책과 실제 측정 두 점의 접근·통과만 계산한다. `planning/LandmarkCandidateSession`은 세션의 후보 1개와 만료·재접근 이력을 관리한다. `storage/RoomLandmarkCandidates`는 좌표·접근 정책·이야기 연결 근거와 현재 콘텐츠 권한을 확인하며, 일반 후보와 `RoomAutomaticStoryEligibility`를 공유한다. 숫자 정책과 실제 위치 의미는 이 클래스들에 기본 승인값으로 넣지 않는다.

Host는 동일 `ContentPlanner`와 `AutomaticStoryPlaybackBridge`에 조립한다. 랜드마크 전용 재생 큐나 UI 로직은 없다. 실제 위치의 신선도와 미래 지역 대상의 준비 여부를 분리하고, 알려진 비활성 내비 상태만 현재 위치 모드로 인정한다. 활성 경로에는 별도 경로 포함 근거가 필요하다. 위치 batch의 마지막 현재 permit을 가진 점끼리 비교하며 과거 점을 현재 입력으로 재생하지 않는다.

일시정지·차단·대화 중에도 통과 여부를 갱신하되 출력 권한은 철회한다. 일반 이야기는 기존 길이 기반 지역 대상이 있을 때 선택하고, 랜드마크도 기존 이야기 연속성과 음악 정상 완료 이후의 자동 슬롯을 사용한다. 자세한 정책 형식은 [검토 랜드마크 계약](./reviewed-landmark-policy.md)을 따른다. 이 구현은 실제 좌표/접근 정책의 검토 완료나 실주행 수락을 뜻하지 않는다.
