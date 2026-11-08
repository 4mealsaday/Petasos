# dev.10 앱 통합과 실제 생성 연결

현행 v0.8의 결정과 시간 제한을 유지한다. 이번 단계는 기존 프리뷰에 기능 제어를 연결하고 음악·차량 정책·복구·위치/내비·실제 생성 서버를 조립한다. 화면 디자인과 데모 시나리오는 확정하지 않았다. 실행 증거는 `artifacts/milestone-9/`에 보관한다.

## 제품 실행 경계

`PetasosMediaService`가 단일 `PetasosSessionHost`와 Coordinator를 소유한다. Activity는 `presentation/PlaybackControls`로 표준 MediaBrowser 명령을 전달하고, 같은 프로세스에서 화면이 허용될 때만 명시적인 말하기를 전달한다. 앱 시작·목록 조회·prepare는 재생·입력·이동 세션을 시작하지 않는다. 미디어 호출자의 신뢰 여부를 확인하고 임의 URI·seek·Talk custom command를 받지 않는다.

이야기 목록은 실제 설치 콘텐츠에서 읽는다. 재생은 현재 권한·해시를 다시 확인하며, 답변은 기존 `RoomContentContext`와 실제 `GenerationClient`에 연결한다. 요약은 turn 동결 후 WorkManager에 예약한다. 시작 복구는 같은 once gate에서 TX-04와 요약 복구를 순서대로 실행하므로 새로 연 세션을 뒤늦은 시작 작업이 중단시키지 않는다.

선택한 이야기를 끝까지 들은 뒤 명시적으로 Play를 누르면 첫 문단부터 새 시도로 재생한다. 같은 이야기의 미완료 checkpoint는 저장한 문단에서 이어 듣는다. 완결된 checkpoint를 재개 후보로만 취급해 재청취가 시작되지 않던 문제를 수정했다. 음악 checkpoint가 남은 일반 Play는 기존 음악을 이어 듣고, `setMediaItem`으로 이야기를 새로 선택한 Play는 그 이야기로 전환한다. 완료 커밋·명시 재생·미완료 복귀·음악 중단/재개와 이야기 선택을 순수 Coordinator 회귀로 검사한다. 소진된 이야기 checkpoint가 남아 있어도 중단한 음악의 미디어 제목·위치를 유지한다.

CarUxRestrictions 미확인/제한 상태에서는 기존 프리뷰를 숨긴다. 화면 제한만으로 허용된 배경 음성을 중단하지 않는다. 공개 CarPowerPolicy의 AUDIO/MICROPHONE 상태는 별도 차단으로 처리한다. 이는 실제 Suspend 진입 콜백을 구현했다는 뜻이 아니다. [공식 근거·입력 계약](./2026-11-07-owned-input-and-vehicle-policy.md), [화면 연결](./parked-controls.md).

차량 API는 Gradle `useLibrary("android.car")`로 컴파일한다. 이 Pleos 이미지의 `BOOTCLASSPATH`에는 `/apex/com.android.car.framework/javalib/android.car-module.jar`가 있고 PackageManager의 shared-library 목록에는 android.car가 없다. 수동 `uses-library android.car required=true` 선언은 실제 `INSTALL_FAILED_MISSING_SHARED_LIBRARY`를 일으켜 제거했다. Android 36 `optional.json`의 `manifest:false`와 [공식 UX 연동 방식](https://developer.android.com/training/cars/parked/automotive-os#ux-restrictions)을 따른다. OEM의 `ai.pleos.playground.car` 이름으로 임의 교체하거나 시스템 JAR를 APK에 넣지 않았다.

`CoordinatorSessionPlayer`는 Coordinator의 상태를 시스템에 투영한다. 이야기 선택 없이 시작한 대화도 transcript 없는 일반 메타데이터를 사용한다. Media3의 empty timeline + READY/BUFFERING 금지 조건은 [1.11.1 공식 소스](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/SimpleBasePlayer.java)를 따른다.

## 음악·기록·편성

Media3 1.11.1 ExoPlayer는 검토된 로컬 음악 파일 한 개를 반복 없이 재생한다. `STATE_ENDED`만 정상 곡 완료이며, 말하기·정지·다음은 중단과 실제 위치를 커밋한다. 이전은 현재 곡 처음부터 새 시도다. 자동 이야기 전환은 곡 종료 뒤에만 가능하다. 플레이어 release 성공과 timeout 오류를 구분하고 실제 출력 종료 증거를 기다린다. 테스트의 합성 WAV는 검증 APK 전용이며 제품 음악으로 설치하지 않는다. 현재 제품 카탈로그에는 권리가 확인된 음악 파일이 없으므로 기본값은 빈 카탈로그다.

위치 기록은 `vehicle.location_enabled=1`과 위치 권한을 모두 요구한다. Media Stop과 기록 일시정지는 구분한다. 실제 Pleos 위치의 timestamp/confidence 의미와 행정 경계 데이터가 미확인인 동안 자동 지역 안내·적립은 차단한다. 읽기 전용 NaviHelper의 목적지/경로 변경만 revision을 바꾸며 일반 질문을 일괄 폐기하지 않는다. [위치·내비 세부 계약](./location-navigation-contract.md).

자동 후보는 이미 들은 storyId와 안정된 factId를 제외한다. 현재 콘텐츠 권한과 위치 freshness를 SDK 시작 직전에 다시 검사한다. 복귀는 현재 허용되는 checkpoint 후보를 사용한다. 서버의 story/resume API와 관심사·랜드마크 접근 편성은 아직 별도 연결 범위다.

## OpenRouter와 개발 HTTPS

사용자가 승인한 계정 설정을 저장했다. 모델 제공자별 ZDR 스위치 5개를 켰고 Google·기타 모델은 ZDR only, 유료/무료 학습 허용·무료 프롬프트 공개·서비스 개선 할인은 껐다. Default Workspace의 input/output logging OFF를 확인했다. [계정 privacy](https://openrouter.ai/settings/privacy), [워크스페이스 observability](https://openrouter.ai/workspaces/default/observability).

검토된 `anthropic/claude-sonnet-4.6` / `amazon-bedrock/global` 제공자로 실제 답변·요약을 호출했다. provider 정책에는 검토 만료 시각이 있으며 만료 후 실패를 허용된 호출로 바꾸지 않는다. 보관 조건·허용 endpoint·실제 요청 메타데이터는 [실제 API 기록](./2026-11-07-live-apis.md)과 비공개 artifact를 따른다.

Android의 첫 실호출은 모델의 `stop` 동작이 앱 허용 범위를 벗어나 거부됐다. canonical v0.8 계약과 클라이언트 권한은 유지하고 모델용 answer schema/prompt를 현행 `continue`/`unsupported`로 제한했다. 서버 재시작 후 Android의 정상 HTTPS에서 답변·동일 requestId 재조회 일치·요약을 10,584ms에 검증했다. 입력은 고정된 비개인 시험 문장이고 마이크·TTS·실제 청취 완료 또는 요약 DB 저장 검증으로 확대하지 않는다. 최초 실패 artifact도 보존한다.

`server/dev-local-https.py`는 127.0.0.1에만 TLS 서버를 연다. debug APK만 개발 CA를 localhost에 한정해 신뢰하고 정상 hostname 검증을 사용한다. release의 인증서 신뢰는 변경하지 않는다. 서버 개인키·기기 토큰은 `.local/server/`의 제한된 파일에 보관한다. 개발 provision 도구는 토큰을 stdin으로 앱 private cache에 전달하고 Keystore 저장 확인 뒤 평문 파일을 삭제한다. OpenRouter 키는 APK에 넣지 않는다. 이 연결은 공개 배포나 Virtual Console이 아니다.

검증 뒤 로컬 HTTPS 서버는 종료했다. 인증서·서버 설정·앱의 암호화 토큰은 보존했고, 다음 실호출 전 [실행 절차](./live-api-setup.md)에 따라 `server/dev-local-https.py serve --port 8443`를 다시 실행해야 한다. provider 검토 만료 후에는 먼저 보관 조건을 재검토한다.

## 검증 범위와 남은 항목

실제 Room/Keystore, 실제 SDK, 합성 음성/좌표/모델, 실제 외부 모델 호출을 구분한다. 기기는 Pleos Connect 2.1.0 / API34 ARM64 / `emulator-5554` / Android user10이다. 각 APK 해시와 시험 범위는 `artifacts/milestone-9/verification.json` 및 개별 probe JSON에 기록한다.

JVM **268개 / 24개 suite**, 서버 **167개**, 호스트 도구 **53개**를 통과했다. JVM 실패·오류·skip은 0이다. Room 매핑은 24개 테이블·153개 제약 트리거로 일치한다. 서버의 일반 회귀 검사는 합성 provider이며 실제 OpenRouter 호출은 아래 별도 기기 결과를 따른다.

마지막 재생/음악 선택 수정이 모두 반영된 소스로 `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug`를 실행해 **BUILD SUCCESSFUL**을 확인했다(`build-final-verified.log`, 8분 10초). Lint는 **No issues found**다. 수정 도중 실행했던 중간 빌드의 실패 로그는 `build-before-final-selection.log`에 별도 보존하며 최종 통과 증거로 사용하지 않는다.

최종 APK로 `media-session-final`과 `app-playback-final`을 다시 통과했다. 앱 설치·Activity 전면 실행·프로세스·범위가 제한된 crash 로그 검사도 PASS이며 `app-smoke/petasos-emulator.png`에서 이야기 선택·재생/정지와 비활성 말하기 표시를 확인했다. 최종 앱 SHA-256은 `b096d6dc4122e3c97ba5cf66026d065fca22652ebc7edefebd36b581c48c2acc`다. 나머지 변경되지 않은 기능 시험은 각 실행 APK 해시를 보존하며 최종 APK에서 전부 재실행했다고 표시하지 않는다.

| 검증 | 결과와 범위 |
| --- | --- |
| 실제 앱 재생 | `app-playback` PASS: 제품 MediaBrowser → Service → Host → 실제 Pleos TTS, 설치된 공개 이야기 완료를 `petasos.db`에 커밋, Stop 후 자동 입력/위치/생성 없음 |
| 미디어 제어 | `media-session` PASS: 표준 제어·메타데이터·release, browse/prepare 무재생, 직접 Talk도 live gate로 거부 |
| 차량 UI | `car-policy-ui` PASS: 실제 현재 CarUx 조회와 합성 unknown/restricted visibility. 차량 상태를 변경하지 않음 |
| 음악 | `music-runtime` PASS: 실제 Media3/Room, 시작·완료·정지·위치·Previous/Next. 합성 WAV이며 제품 음악 자산은 아님 |
| 위치·복구 | `location-storage`/`session-recovery` PASS: 실제 Room/Keystore와 합성 좌표·경계·중단 데이터. 실제 이동/자동 지역 적립은 아님 |
| 내비 | `navigation` PASS: 실제 NaviHelper 연결·route 상태 callback·해제. 경로를 변경하지 않음 |
| 실제 모델 | `generation-provision`/`generation-live-final` PASS: 앱 Keystore 설정과 정상 Android HTTPS의 실제 답변·재조회 일치·요약. 비개인 고정 문장, 마이크/출력 없음 |
| runtime·요약 | `runtime-scripted`/`summary-storage`/`summary-rights-final` PASS: 실제 Room/Keystore에 합성 입력/생성. TX-03 원자성·재시도·권한 철회·원문 보존·키 손실 구분 |
| 유한 PCM | `stt-owned-pcm-verified` 인식/종료 probe PASS. Ready→Started 21,986ms로 첫 발화 5초 제한 **실패**, Ended→Final 87ms |
| EOF 없는 PCM | `stt-owned-stream-verified` probe PASS. 이번 실행은 2,306ms/164ms로 5초/500ms 충족. 사람 발화·AudioRecord·장시간 안정성 증거는 아님 |

요약 권한의 첫 실행은 50초 host timeout으로 미완료였으며 실패 artifact를 보존했다. 여러 임시 Room DB와 Keystore 검사를 끝낼 수 있게 해당 probe의 host 제한만 90초로 조정했고 77,235ms에 9개 항목 PASS를 확인했다. 제품의 발화·final·생성 시간 제한은 바꾸지 않았다.

- 사람의 실제 마이크 대화와 음질·인식률은 별도다. 새 앱 소유 PCM 입력은 유한 입력과 EOF 없는 연속 입력을 각각 검사하며, SDK 이벤트가 제품의 5초/500ms 제한을 충족했는지 따로 기록한다.
- 실제 AAR에서 STT 결과 및 TTS 전체 입력을 SDK 자체가 `Log.d`로 전달하는 경로를 확인했다. 공개 logging-off 계약이 확인되기 전 제품 `supportsLiveInput()`은 false다. 버튼/host 직접 호출 모두 권한 요청·마이크·세션 생성을 차단한다. 이미 설치된 공개 이야기의 재생과 고정 비개인 입력을 쓰는 실제 API probe는 별개다. bytecode와 해시는 [음성 검증 문서](./2026-11-07-owned-input-and-vehicle-policy.md)에 기록한다.
- 실제 Suspend/Resume, 경쟁 오디오 포커스, 주행 중 화면 전환, 백그라운드 위치 및 실이동은 미검증이다. Virtual Console은 보류한다.
- 위치 품질 의미·경계 정책, 내부 음악 자산, 대표 경로 콘텐츠 5묶음/10소재, 데모와 UI 디자인은 완료 표시하지 않는다.
- 로컬 개발 HTTPS와 한 번의 실호출은 공개 운영 배포·장시간 안정성·답변 품질 검수를 대신하지 않는다.

