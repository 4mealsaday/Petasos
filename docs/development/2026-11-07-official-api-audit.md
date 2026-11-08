# 공식 API 대조와 dev.4 수정

후속: [dev.5 실행 host·암호화 저장소와 통합 검증](./2026-11-07-runtime-storage.md)을 추가했다. 이 문서는 dev.4 당시의 범위·실행 증거를 보존한다.

기준일: 2026-11-07. [공식 문서 참고집](../reference/pleos/README.md), 실제 고정 의존성의 선언·바이트코드, 현행 v0.8 명세를 대조했다. 검사 대상은 앱의 Kotlin·manifest·리소스·Gradle 설정, Android 진단 APK, 호스트 검증 스크립트와 참조 계약 코드다. 다운로드한 SDK 자체를 수정하거나 전체 Pleos API를 실행한 검사는 아니다.

## 발견한 문제와 수정

| 항목 | 기존 문제 | dev.4 처리 |
| --- | --- | --- |
| STT 종료 | `Ended`를 받은 뒤 명시적 stop/close가 `sdk.stop()`을 생략했다. 늦은 final을 취소로 차단하는 조건도 건너뛸 수 있었다. | EPD 종료와 마이크 종료를 분리하고, 요청을 발행했다면 명시적 취소·해제에서 stop을 요청한다. 취소 뒤 final은 전달하지 않는다. |
| TTS 해제 | 정상 완료 후에도 stop을 보냈고, stop 직후 동기 release를 호출했다. SDK 내부 stop은 비동기여서 STT와 같은 해제 경쟁 가능성이 있었다. | 정상 Done/Stop을 확인한 출력에는 불필요한 stop을 보내지 않는다. 아직 종료가 확인되지 않은 요청은 stop 후 최대 1초의 잔여 유예를 두고 해제한다. 오류를 정상 종료 확인으로 취급하지 않는다. |
| 오디오 포커스 | stop 호출 직후 포커스를 반납했고 포커스 상실 종류를 하나로 합쳤다. | Stop 콜백 또는 close 정리 시 포커스를 반납한다. 영구·일시·duck 상실을 구분하고, 자신의 stop을 요청한 뒤 외부에 상실 이벤트를 전달한다. 자동 재생은 하지 않는다. |
| CCP 선언 | 임시 터치 화면에 CCP 미지원 metadata가 없었다. | `com.hmg.rotary.ROTARY_SUPPORTED=false`를 명시했다. CCP 탐색 기능을 구현했다는 의미는 아니다. |

STT의 `onEndedRecognition()`은 실제 3.3.1 구현에서 EPD_OFF에 대응한다. 무발화 취소의 완료 응답으로 사용할 수 없다. TTS의 `onDone()`과 `onStop()`도 완료 이력과 중단 이력을 구분해야 한다. [STT 가이드](../reference/pleos/guides/05-stt.md), [TTS 가이드](../reference/pleos/guides/04-tts.md), [공식 STT 시작·중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop).

공통 `SpeechRequestCleanup`은 요청 발행·출력 종료 확인·stop 요청 시각만 관리한다. **1초 유예, close 반환, STT Ended는 물리적 입력/출력 종료 확인이 아니다.** 닫힌 어댑터의 외부 콜백은 즉시 차단하고, SDK 연결과 TTS 포커스는 필요한 유예 뒤 해제한다. 지연 자체를 보장으로 사용하는 제품 코드는 추가하지 않았다.

포커스 처리는 [공식 오디오 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development), CCP 선언은 [공식 CCP 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ccp-rotary-controller-development)를 따랐다. 경쟁 앱·통화·Gleo에 의한 실제 포커스 중재는 별도 검증이 필요하다.

## 일치한 구현

- FusedLocation 1.1.3의 지원/권한 확인, 초기화, 구독·캐시 구분, nullable 처리, 같은 리스너 해제 후 release, 세션별 새 인스턴스 생성이 공식 수명주기와 맞는다. timestamp/confidence를 임의 해석하지 않고 원시값과 수신 시각을 구분한다.
- TTS/STT 3.3.1은 ON_DEVICE를 명시하고 초기화 성공 뒤 한국어 locale·리스너를 설정한다. 권한과 CaaS 패키지 가시성을 확인한다. FusedLocation 서비스 가시성은 AAR manifest에서 정상 병합된다.
- 합성 STT PCM 검사는 `request()` 없이 `sendAudio()`/`completeAudioSend()`를 사용한다. 마이크 Ready와 다른 콜백 계약으로 검증하며 테스트 자산은 별도 테스트 APK에만 들어간다.
- 공식 Maven 의존성과 minSdk 28, 현재 Pleos API 34 ARM64 대상이 일치한다. 특정 JNA x86_64 lint 예외에 해당하는 바이너리는 ARM64 APK에 포함하지 않는다.
- 일반 `MainActivity`는 임시 XML 화면만 표시한다. 위치·음성 연결은 진단 APK에 있으며 일반 실행에서 자동 재생하거나 마이크를 열지 않는다. 화면 리소스·capability 계약·Pleos 어댑터의 의존 경계를 유지했다.
- `spec/`의 SQL/JSON·Python 코드는 v0.8의 참조 계약과 검사 도구다. 실제 Android 저장소·네트워크 구현으로 해석하지 않는다.

검증 도구의 고정 보고 날짜도 실행 시점의 UTC 날짜·시각으로 바꿨다. 참조 검사에서 SDK를 실행하지 않는다는 범위 설명을 명확히 해, 다른 SDK 실행 기록까지 미실행으로 오해하지 않도록 했다.

## 아직 구현하지 않은 플랫폼 통합

현재 프리뷰와 개별 SDK 검증이 제품 정책을 모두 충족한다는 뜻은 아니다.

- CarUxRestrictions의 현재 제한 조회와 변경 리스너는 **첫 제품 화면 연결 전** 추가해야 한다. DO 플래그를 생략하는 것만으로 대체할 수 없다.
- Media3 MediaLibraryService/MediaSession, 시스템 미디어 탐색·표준 하드키·중지, 실제 Suspend 연결은 미구현이다. 문서 예제의 `onSuspendEntering()`을 자동 콜백으로 가정하지 않는다.
- 포커스 경쟁, TTS 첫 음절·선행 묵음, 문단 연속 재생 지연, 엄격한 STT→TTS 전환은 별도 실행 검수 대상이다. 호스트 adb 감사는 제품 앱의 종료 확인 API로 사용할 수 없다.
- NaviHelper·Vehicle·Gleo는 아직 호출하지 않는다. 제공 종료된 LLM SDK를 생성 서버 대용으로 추가하지 않았다.
- CCP Back·폼 팩터·실차·실제 위치 품질·권한 취소·서비스 장애는 개별 성공 콜백만으로 검증되지 않는다.
- 사용자가 보류한 사람의 실제 발화와 Virtual Console 연결은 수행하지 않았다.

## 검증 결과

`0.8.0-dev.4`, Pleos Connect 2.1.0 / API 34 ARM64, `emulator-5554`, Android 사용자 10에서 실행했다. 과거 dev.2/dev.3 증거를 덮어쓰지 않고 `artifacts/milestone-3/`에 기록했다.

| 검사 | 결과 | 범위 |
| --- | --- | --- |
| 앱·테스트 APK 빌드 및 Lint | PASS | `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` |
| JVM 단위 검사 | 49/49 PASS | Coordinator 39개, 음성 종료 정책 10개. 모의 이벤트와 확인 응답 기반 |
| 호스트 검사 회귀 | 18/18 PASS | 녹음 세션·동일 PID·서비스 연결 감사 파서 |
| 참조 계약 검사 | 13/13 PASS | SQL·JSON·명세 링크. 실제 앱 DB 트랜잭션과 별개 |
| `location` | PASS | 합성 A→B 좌표의 실제 UPDATE 수신 |
| `tts-speak` / `tts-stop` | PASS | 실제 한국어 ON_DEVICE 재생 완료·명시적 중단 콜백 |
| `tts-close` | PASS | Started 뒤 close, 동일 PID 생존 중 활성 CaaS 연결→해제 확인 |
| `stt-pcm` | PASS | 합성 문장의 실제 최종 인식 일치, Ended 뒤 final, 마이크 사용 없음 |
| `stt-cancel` / `stt-close` | PASS | 동일 PID 생존 중 실제 녹음 종료와 CaaS 연결 해제 |
| 일반 앱 설치·실행 | PASS | Activity 전면 실행·프로세스·앱 범위 크래시 검사·화면 확인 |

TTS close는 동일 PID 12114에서 연결을 확인한 뒤 약 1.33/1.82/2.32초에 연결 해제를 확인했다. `playerActivityVerified=false`이며 **서비스 연결 해제만으로 스피커 무음을 증명하지 않는다.** STT 합성 경로는 Ready 0회, Started 8회, Ended 1회, final 1회였고, 무발화 취소에서는 이번에도 Ended가 없었다. 로그에는 이벤트·시간·연결/녹음 식별 메타데이터만 남겼다.

일곱 SDK probe는 모두 같은 APK를 사용했다. 앱 SHA-256은 `9194fcb3830e25b6f14ff5c1103aa62e6957d51ccc3a541006a34c5777a9eef6`, 테스트 APK는 `dd3ea47880c85fbb0ff40ad3669cee3bf27dfd96a1b5e87f94885251de33ffd7`이다. 원시 결과는 probe별 TXT/JSON, JVM 결과는 `unit-tests.json` 및 JUnit XML, 일반 실행은 `launch/`에 있다.

빌드에 SDK XML v3/v4 도구 간 형식 경고는 남아 있으나 빌드·Lint는 성공했다. 이는 API 호출 오류를 뜻하지 않으며, 고정한 빌드 도구·SDK를 변경할 때 다시 확인한다. 전체 v0.8 A/R/D/N 제품 검수나 실차·사람의 발화·음성 품질의 통과를 의미하지 않는다.

다음 개발은 [PlaybackCoordinator](./2026-11-07-playback-coordinator.md)의 UI·SDK 독립 상태 전이다. 실제 SDK 종료 확인과 저장 커밋을 별도 이벤트로 요구하고, 미구현 host를 성공한 것으로 가장하지 않는다.
