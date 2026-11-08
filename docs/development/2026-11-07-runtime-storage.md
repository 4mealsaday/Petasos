# Runtime 실행부와 암호화 저장소 첫 구현

후속 구현: [dev.6 생성 서버·TX-03 요약·WorkManager](./2026-11-07-generation-summary.md). 아래는 dev.5 당시 범위와 검증 이력이다.

기준: 2026-11-07, `versionCode=5`, `0.8.0-dev.5`. [dev.4 Coordinator](./2026-11-07-playback-coordinator.md)에 직렬 실행 host, 실제 Room 저장소와 Pleos 음성 bridge를 연결한 단계다. [v0.8 동작 명세](../Petasos_동작명세.md)와 [데이터 계약](../Petasos_데이터_API_계약.md)을 유지한다. UI에 연결한 완성 대화 기능이나 전체 제품 검수 통과를 뜻하지 않는다.

dev.5 앱·테스트 APK를 새로 빌드해 JVM·호스트 회귀와 Pleos 통합 검사 4종을 실행했다. 아래 결과는 각 검사 범위에만 적용하며 실제 STT·서버·주행 검수를 대신하지 않는다.

## 실행 경계

| 위치 | 책임 |
| --- | --- |
| `runtime/RuntimePorts.kt` | 직렬 큐·시계·저장소·음성·생성 provider 계약. 개인 텍스트 객체의 진단 문자열은 가림 |
| `runtime/PlaybackRuntime.kt` | Coordinator 효과를 실행하고 정확한 operation token을 붙인 결과를 같은 큐로 반환 |
| `platform/android/runtime/AndroidRuntimeQueue.kt` | 전용 worker에서 이벤트·짧은 저장 트랜잭션·타이머를 직렬 처리 |
| `platform/pleos/speech/PleosRuntimeSpeech.kt` | worker 요청을 Android main thread의 실제 Pleos 어댑터로 전달 |
| `storage/RoomRuntimePersistence.kt` | 세션·turn·재생 attempt·checkpoint·카드·암호화 payload의 저장 경계 |
| `storage/PayloadCipher.kt`, `ConversationPayload.kt` | Android Keystore 암호화와 임시 대화 payload 형식 |
| `storage/SchemaRows.kt`, `SchemaConstraints.kt`, `StorageDatabase.kt` | 참조 SQL의 Room entity와 보충 제약 설치 |

Coordinator 잠금 안에서 DB·네트워크·SDK를 호출하지 않는다. 실제 저장은 전용 worker에서 수행하고 성공적으로 커밋된 뒤에만 완료 이벤트를 보낸다. 생성 provider는 별도 인터페이스이며 네트워크 작업을 저장 트랜잭션 안에 넣지 않는다. 늦은 콜백, 취소된 operation, 이전 세대의 생성 응답은 현재 요청과 대조한다.

일반 앱의 `MainActivity`와 XML preview는 변경하지 않았으며 새 runtime에 연결하지 않았다. 앱을 실행하는 것만으로 음성 출력이나 마이크 요청이 시작되지 않는다. 시험 진입점은 별도 Android 테스트 APK에 있다. 첫 제품 화면은 디자인 논의와 CarUxRestrictions 연결을 거쳐야 한다.

## Room 스키마와 트랜잭션

선택한 의존성은 [Room **2.8.5**](https://developer.android.com/jetpack/androidx/releases/room), Kotlin 2.2.21에 대응하는 [KSP **2.2.21-2.0.5**](https://github.com/google/ksp/releases/tag/2.2.21-2.0.5)다. 실제 DB 첫 버전은 `1`이며 스키마를 export한다. 파괴적 migration fallback을 사용하지 않는다. 이후 스키마 변경에는 명시적인 migration과 검증이 필요하다.

`scripts/generate-room-schema.py`가 [참조 SQL](../../spec/petasos-v0.8.sql)을 읽어 **24개 Room entity**를 만든다. 컬럼 타입·nullability·기본값·PK·FK·unique 제약을 매핑하고, Room annotation으로 직접 표현하지 못하는 제약은 생성 시 설치한다.

- CHECK 71개를 INSERT/UPDATE 트리거 142개로 표현한다. NULL을 허용하는 SQLite CHECK 의미도 유지한다.
- trip·session·recording segment의 active 행을 각각 최대 하나로 제한하는 트리거 6개를 설치한다. UPDATE의 자기 행 제외는 `OLD` 기본키를 사용한다.
- terminal attempt 불변, checkpoint revision 단조성, frozen 요약 version, 이야기 카드 완료 조건, 카드 근거 종류를 확인하는 원본 트리거 5개를 보존한다. 보충 트리거는 총 **153개**다.
- provider event와 지역/이야기 카드의 partial unique는 관련 NULL·CHECK 제약 아래에서 동일한 중복 제한을 적용하는 unique index로 매핑한다. FK 조회를 위한 인덱스도 추가한다.

현재 구현은 다음 저장 경로를 제공한다.

| 계약 | 구현 범위 |
| --- | --- |
| TX-01 | 활성 turn의 최종 발화와 암호화 payload를 같은 트랜잭션에 저장. 같은 최종 텍스트 재수신은 기존 payload를 유지하고, 다른 최종 텍스트로 바꾸려는 요청은 거부 |
| 답변 임시 저장 | 답변 문단·근거 참조를 암호화 payload에 반영하고 turn/payload version을 함께 갱신. 저장 성공 전 답변 출력 금지 |
| TX-02 | 활성 attempt의 terminal 전이, checkpoint, 충족한 이야기 카드와 근거를 같은 트랜잭션으로 커밋. 중간 실패 시 전체 rollback |
| turn 동결 | 실제 완료한 답변 문단과 저장된 payload의 일치를 확인하고 turn을 동결. 중단 문단을 완료 문단으로 바꾸지 않음 |
| 새 세션 열기 | 남아 있는 prepared/playing attempt와 열린 turn·구간·세션을 중단 상태로 처리한 뒤 새 session ID 등록. 이전 재생을 자동 완료하거나 자동 재생하지 않음 |

`started_at`은 실제 STARTED 이벤트 뒤 기록한다. 시작이 확인되지 않은 attempt를 completed로 저장하지 않는다. 이야기 카드는 같은 story version의 필수 문단이 모두 완료되어야 생성하며, 이미 받은 story ID의 카드는 중복 생성하지 않는다. 카드 근거에는 해당 버전의 완료 attempt를 연결한다. 서로 다른 버전의 일부 완료를 합쳐 카드를 만들지 않는다.

24개 테이블이 존재한다는 것이 모든 기능의 구현을 뜻하지 않는다. 지역 판정·지역 카드 적립, 콘텐츠 설치, 요약 Worker 등은 아래 미구현 범위에 남아 있다. 생성 응답의 전체 JSON schema·요청 식별자·근거 검증도 실제 생성 provider가 책임져야 하며, `RuntimeAnswer` 타입이나 저장소의 근거 참조 검사만으로 대체하지 않는다.

## 개인 대화 암호화

최종 발화, 답변 전문과 문단 재생 이력은 `pending_conversation`의 암호화 payload에 보관한다. 일반 콘텐츠 대본은 기존 콘텐츠 테이블에 둘 수 있지만 개인 대화 원문을 그 테이블에 복사하지 않는다. 원음 저장은 구현하지 않는다.

- Android Keystore에 AES-256-GCM과 HMAC-SHA-256 키를 별도로 둔다. 앱 DB에 키 원문을 저장하지 않는다.
- 암호화마다 Keystore가 생성한 12-byte nonce를 사용하고 `(key_version, nonce)` 중복을 DB에서 제한한다. GCM 인증 태그는 ciphertext에 포함된다.
- AAD는 형식 식별자, session ID, turn ID, payload version을 길이 구분한 바이트 형식으로 묶는다. 다른 세션·turn·version으로 암호문을 옮겨 복호화하려는 시도를 검출한다.
- `payload_hash`는 AAD와 평문을 입력으로 한 키 기반 HMAC이다. 짧은 개인 발화를 공개 SHA-256으로 직접 비교할 수 있는 값을 저장하지 않는다.
- 복호화·무결성 확인·키 접근 실패 시 ciphertext를 보존하고 해당 turn을 blocked로 표시한다. 기존 암호화 payload가 있으면 누락된 키를 새 키로 바꾸어 성공한 것처럼 처리하지 않는다.

평문은 SDK/생성 호출과 암복호화에 필요한 일시적인 메모리 객체로 존재한다. 사용한 일부 byte buffer는 덮어쓰지만 JVM `String` 전체의 즉시 메모리 삭제를 보장하는 구현은 아니다. 진단 문자열과 로그에는 개인 원문을 넣지 않는다. TX-03 요약 커밋·payload 삭제가 아직 없으므로 이번 단계에서 요약 후 원문 정리까지 완료됐다고 해석하지 않는다.

## 실제 음성 연결과 입력 종료 제한

Pleos bridge는 connect 성공 후에만 speak/request를 보낸다. SDK 요청을 전혀 보내기 전에 취소된 작업은 해당 작업이 입력·출력을 시작하지 않았다는 근거로 정리할 수 있다. 오디오 포커스 거절도 `sdk.speak()` 미발행을 구분한다. 정상 TTS Done/Stop은 해당 출력의 종료 증거이며, 정상 완료가 bridge에 도착한 뒤 host의 stop이 늦게 도착하는 순서도 처리한다. 이미 취소된 출력의 늦은 Done으로 완료를 복구하지 않는다.

반면 **차량 마이크 STT의 stop 완료를 일반 앱이 확정할 공개 경로는 현재 확인하지 못했다.** 실제 요청을 발행한 입력을 stop/close한 뒤에는 `INPUT_STOP_UNCONFIRMED`를 알리고 다음 음성 작업을 차단한다. `Ended`, stop/close 반환, 어댑터의 1초 유예, bridge의 관찰 타이머를 `InputStoppedConfirmed`로 바꾸지 않는다. 따라서 이번 단계는 실제 STT 발화에서 생성·TTS 답변까지 이어지는 사용 가능한 대화 기능이 아니다.

| 확인한 근거 | 적용과 한계 |
| --- | --- |
| [Pleos 시작·중지 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop), [결과 리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners) | 가이드는 stop의 마이크 종료와 Ended의 인식 종료를 설명한다. 그러나 사용 중인 3.3.1 AAR의 공개 선언에는 별도 stop 완료 callback/future·녹음 session ID·활성 입력 조회가 없다. 기존 실행·바이트코드 관찰에서는 Ended가 EPD_OFF여서 무발화 취소의 종료 응답으로 사용할 수 없다. |
| [Android AudioRecordingCallback](https://developer.android.com/reference/android/media/AudioManager.AudioRecordingCallback), [입력 공유 안내](https://developer.android.com/media/platform/sharing-audio-input#configuration_changes) | AudioManager callback/query는 전역 녹음 구성을 관찰한다. AudioRecord/MediaRecorder 인스턴스 callback은 그 recorder 범위이므로 다른 UID인 CaaS recorder를 소유하는 대안이 아니다. |
| [AOSP Android 14 AudioService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/audio/AudioService.java#12224), [권한 선언](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/res/AndroidManifest.xml#5867) | 전역 목록의 식별 정보는 `MODIFY_AUDIO_ROUTING` 권한에 따라 구분된다. 이 권한은 `signature\|privileged\|role`이며 일반 앱의 런타임 권한 요청으로 얻는 권한이 아니다. |
| [AOSP AudioRecordingConfiguration](https://android.googlesource.com/platform/frameworks/base/+/master/media/java/android/media/AudioRecordingConfiguration.java#155), [RecordingActivityMonitor](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/audio/RecordingActivityMonitor.java#169) | AOSP 공개용 목록은 UID/package를 익명화하고 system-only source는 제외한다. session 번호가 남아도 Pleos 요청과 연결할 공개 ID가 없으므로 특정 turn의 종료 ACK로 사용하지 않는다. 이 두 구현 링크는 AOSP main 참고이며 Pleos 시스템 이미지 자체의 소스라고 주장하지 않는다. |

전역 녹음 목록이 비거나 줄었다는 관찰과 특정 요청의 종료 확인은 구분한다. [기존 STT 호스트 감사](./2026-11-06-stt-verification.md)는 adb 권한으로 UID/session·동일 PID 생존·서비스 바인딩을 함께 확인한 시험 증거다. 이를 일반 제품 앱에서 사용할 수 있는 종료 확인 API로 대체하지 않는다. 이 제한을 해결하려면 Pleos의 요청별 종료 계약 또는 신뢰할 수 있는 별도 플랫폼 확인 경로가 필요하다.

## 검증 구성과 실행 결과

`storage` probe는 실제 Room/SQLite와 Android Keystore를 사용한다. `runtime-tts`는 실제 Coordinator·host·Room·Pleos TTS로 합성 대본의 두 문단과 카드 적립을 검사한다. `runtime-tts-stop`은 실제 출력의 중지 콜백과 중단 커밋을 검사하며, 청각적으로 스피커가 무음인지 측정하는 검사는 아니다. `runtime-scripted`는 실제 host·Room·Keystore를 쓰되 **음성 이벤트·최종 발화·생성 답변은 시험 코드가 주입**한다. scripted 입력의 종료 확인은 마이크가 없는 시험 입력의 확인이며 Pleos 마이크 종료 증거가 아니다. 합성 콘텐츠 설치기는 테스트 APK에만 있으며 제품 콘텐츠 설치기가 아니다.

| 검사 | 결과 | 기록할 증거와 범위 |
| --- | --- | --- |
| 앱·테스트 APK 빌드 및 Lint | PASS | `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`; `artifacts/milestone-4/build.log` |
| JVM 회귀 검사 | PASS 74개 | Coordinator 39, 음성 종료 10, runtime 19, 암호화 6. `artifacts/milestone-4/TEST-*.xml` |
| 호스트 검증 스크립트 회귀 | PASS 18개 | `python3 -m unittest discover -s scripts/tests -v`; 녹음·바인딩 증거 판정 |
| Room 매핑 재생성 대조 | PASS | `python3 scripts/generate-room-schema.py --check`; 24 tables / 153 triggers |
| `storage` | PASS | FK/CHECK/active unique, 최종 발화 중복, nonce·무결성·키 유실, TX-01/TX-02 rollback, 파일 내 개인 원문 미검출, 재개방, unknown migration 거부; `storage.txt/json` |
| `runtime-tts` | PASS | 실제 TTS STARTED/Done → 완료 2개 → 카드 1개·근거 2개 → 세션 마감·재개방 |
| `runtime-tts-stop` | PASS | 실제 TTS STARTED 후 수동 정지, Stop 콜백과 interrupted 커밋·추가 재생 차단 |
| `runtime-scripted` | PASS | 주입된 발화·답변으로 암호화 커밋 → 답변 완료·동결 → 대본 복귀. 실제 STT/서버 시험 아님 |
| 일반 앱 설치·실행 | PASS | preview 전면 실행, 동일 user 앱 프로세스, 크래시 없음; `artifacts/milestone-4/launch/verification.txt` |

실행 대상은 Pleos Connect 2.1.0 / Android 14(API 34), ARM64 `emulator-5554`, Android user 10이다. 네 probe 모두 동일한 앱·테스트 APK를 사용했으며 [결과 요약](../../artifacts/milestone-4/summary.json)과 `artifacts/milestone-4/`의 probe별 TXT/JSON에 시각·해시·범위를 기록했다.

- 앱 SHA-256: `6f787871196a855f6cdfc331fbedb6f5ddfd7b694ac0b411c1b18d787d4e8b96`
- 테스트 APK SHA-256: `ab0733c4f443458e737827c52f976be2ead344e8ff645952c7e76534d063a2a5`

빌드에서 SDK XML 도구 버전 차이와 vendor native library strip 메시지는 남았지만 Kotlin 컴파일과 Lint는 통과했다. 기존 JDK/Gradle/AGP/Kotlin은 유지했다. [공식 권장 KSP](https://developer.android.com/build/migrate-to-ksp)를 사용하며 최신 KSP 2.3.13은 AGP 8.12 이상을 요구해 현재 고정 도구와 맞는 2.2.21-2.0.5를 선택했다. 이것은 앱 제품 기능의 제한과 별개인 빌드 도구 호환 선택이다.

 검증 도중 주입한 실패나 테스트용 키 삭제는 임의 이름의 시험 DB·키에만 적용한다. 실제 사용자 데이터에 적용하지 않는다.

읽기전용 선행 검사에서는 생성한 Kotlin metadata와 트리거를 메모리 SQLite에 재구성해 참조 SQL 시나리오 10개를 확인했다. 이는 Android Room annotation processor·기기 SQLite·Keystore 실행 결과를 대체하지 않으며 위 표의 PASS로 계산하지 않는다.

## 아직 연결하지 않은 기능과 사용자 테스트

이번 단계에는 실제 생성 서버·인증·응답 transport 검증이 없다. 기본 생성 provider는 명시적으로 unavailable을 반환하며 대체 답변을 지어내지 않는다. TX-03 요약 Worker·payload 삭제, TX-05 제품 콘텐츠 설치기, 음악/Media3 MediaLibraryService·MediaSession, 위치 기반 편성·지역 판정, 플랫폼 Suspend/Resume·서비스 복구와 CarUxRestrictions도 미구현이다. 세션 복구 저장 코드가 있다는 이유로 전체 TX-04와 차량 절전 검수를 통과했다고 해석하지 않는다.

`AndroidRuntimeClock`은 절전 시간도 포함하는 `elapsedRealtime()`을 쓰지만 worker의 예약 대기는 `nanoTime` 계열의 절전 시간을 제외하는 시간축을 사용한다. 실제 Suspend/Resume 연결 시 만료된 작업을 재평가하거나 취소해야 한다. 현재 타이머 구현만으로 절전 복귀 직후 timeout 처리까지 보장하지 않는다. [Android SystemClock 공식 설명](https://developer.android.com/reference/android/os/SystemClock).

화면은 아직 runtime과 연결되지 않아 사용자가 버튼으로 새 통합 흐름을 시험할 단계가 아니다. 현재 사용자가 직접 수행해야 할 필수 테스트는 없다. 직접 확인이 필요한 동작이 준비되면 조작 순서와 기대 결과를 별도로 안내한다. **사람의 실제 마이크 발화와 Virtual Console 연결은 사용자 요청에 따라 계속 보류한다.** 실제 음성 청취 품질·첫 음절·포커스 경쟁·엄격한 입력→출력 전환·실차 검수는 자동 저장/상태 검사와 구분해 남긴다.
