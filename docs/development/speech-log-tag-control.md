# Android 공개 로그 태그 설정 조사

확인: 2026-11-09. 이 문서는 Pleos API 34 에뮬레이터의 **새 합성 실험**에서 검증할 공개 개발자 설정을 설명한다. 실제 사람 발화, 앱 배포 시 무로그 보장, 제품 마이크 capability 활성화 근거가 아니다. 이 조사·도구 변경 자체에서는 ADB 실행이나 시스템 설정 변경을 하지 않았다.

## 공개 억제 경로

Android 공식 [Logcat 문서의 System property filtering](https://developer.android.com/tools/logcat#logging-system-overview)은 `liblog`가 `logd`에 전송할 최소 심각도를 `log.tag.<TAG>`, `persist.log.tag.<TAG>`, `log.tag`, `persist.log.tag` 순서로 읽는다고 설명한다. `S`는 모든 로그를 억제하는 값이다. `adb logcat <TAG>:S`는 이미 생성된 로그의 **표시 필터**이므로 같은 효과로 간주하지 않는다.

에뮬레이터와 같은 Android 14 기준 공개 소스도 이를 뒷받침한다.

- [android_util_Log.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/core/jni/android_util_Log.cpp#94): Java의 native 로그 함수가 `__android_log_buf_write`를 호출한다.
- [logger_write.cpp](https://android.googlesource.com/platform/system/logging/+/refs/tags/android-14.0.0_r1/liblog/logger_write.cpp#386): 실제 전송 전에 `__android_log_is_loggable`를 검사한다. Java 호출부가 `Log.isLoggable`를 직접 호출하지 않았다는 이유만으로 이 설정이 무효라고 판단하면 안 된다.
- [properties.cpp](https://android.googlesource.com/platform/system/logging/+/refs/tags/android-14.0.0_r1/liblog/properties.cpp#105): 태그별 속성 우선순위와 `S → ANDROID_LOG_SILENT`를 확인할 수 있다. 다만 프로세스가 별도 최소 심각도를 지정했다면 속성 수준과 그 값 중 낮은 값을 사용한다(248행). 따라서 설정값만 읽어서 실제 억제 성공을 선언할 수 없다.

직접 파일 기록, 이벤트 버퍼, 별도 진단 시스템, custom native logger 등은 위 Java/liblog 경로와 같은 계약으로 추정하지 않는다. Pleos 배포 이미지가 AOSP와 완전히 같다는 증명도 아니므로 실제 서비스의 합성 canary 재관측이 필요하다.

## 개발 환경과 앱 배포의 차이

[Android 14 shell.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-14.0.0_r1/private/shell.te#153)는 `log_tag_prop` 쓰기를 shell에 허용한다. 이 항목은 `userdebug_or_eng` 조건 밖에 있으므로 AOSP 기준 에뮬레이터에만 한정된 기능은 아니다. 디바이스의 ADB 접근과 실제 정책은 별도로 확인해야 한다.

반면 [app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-14.0.0_r1/private/app_neverallows.te#42)는 일반 앱의 속성 서비스 쓰기를 금지한다. 따라서 개발자가 ADB로 설정하는 방법은 공개 개발·관리 경로이지만, 일반 APK가 다른 프로세스의 로그를 끄는 배포용 공개 API가 아니다. 숨겨진 `SystemProperties` 호출이나 앱에서 `setprop`를 실행하는 우회를 추가하지 않는다.

[공식 시스템 속성 문서](https://source.android.com/docs/core/architecture/configuration/add-system-properties)는 `persist` 접두사가 재부팅 후 보존을 뜻한다고 설명한다. 첫 검증에서는 한정된 태그의 비영속 `log.tag.<TAG>`와 원래 값 복원을 검토한다. 전역 `log.tag=S`는 시스템 전체 진단에 영향을 주므로 태그별 검증과 동등한 대안으로 취급하지 않는다. 태그는 패키지별 namespace가 아니므로 같은 태그를 사용하는 다른 프로세스에도 영향을 줄 수 있다.

## 현재 증거의 한계

dev21의 관측 JSON은 canary·payload 구조별 개수만 보존했다. 과거 원문 로그나 태그 목록을 재구성할 수 없다. `.local/sdk-inspection/stt-service-bytecode.txt`는 이름과 달리 **클라이언트 `SttServiceManager`의 bytecode**이며 별도 `ai.pleos.playground.caas/.service.speech.SpeechService` 구현이 아니다. 이 파일로 서비스의 태그, native sink, 전 경로를 확정하지 않는다.

## 새 합성 관측의 선택적 태그 집계

`scripts/audit-speech-logs.py observe`에 `--include-matched-tags`를 명시하면 `matchedTagObservation`이 추가된다. 기본 출력 형식과 기존 match 판정은 유지된다. 이 옵션은 기존 고정 합성 probe에만 제공하며 실제 마이크 probe와 `summarize-crashes`에는 제공하지 않는다.

선택적 집계의 범위는 다음과 같다.

- `known_canary` 또는 `recognition_payload_shape`가 일치한 레코드의 태그만 집계한다. `stacktrace_sink`만 일치하거나 일치가 없는 태그는 보존하지 않는다.
- 원래 태그를 변환·정규화·잘라서 출력하지 않는다. 정확한 태그가 없으면 속성 적용 대상도 추정하지 않는다.
- 태그는 ASCII 식별자 `[A-Za-z][A-Za-z0-9_.:-]*`, 최대 64자, 서로 다른 이름 최대 64개로 제한한다. 잘못된 속성 이름, 개인정보/credential/payload 의심 예약어, 긴 숫자·해시·opaque token 형태를 거절한다.
- 거절한 이름·해시·내용을 저장하지 않고 `rejectedTagCount`만 증가시킨다. 한 레코드가 두 category에 일치해도 거절 건수는 1이다. 0보다 크면 출력 목록은 누락이 있는 것이며 태그 적용 범위가 완전하다고 주장할 수 없다.
- `rejectedTagReasonCounts`는 고정된 거절 사유별 개수만 추가한다. 길이·문자·속성명·민감 예약어·opaque 식별자·태그 수 제한을 구분하며 거절된 이름이나 부분 문자열은 출력하지 않는다. Android 14 [속성명 검증](https://android.googlesource.com/platform/system/core/+/refs/tags/android-14.0.0_r1/init/util.cpp#430)에서 허용하지 않는 문자가 포함된 태그라면 정확한 `log.tag.<TAG>`를 만들 수 없으므로 이름을 임의로 치환하지 않는다.
- 허용한 태그별로 프로세스 역할과 두 category의 개수만 저장한다. UID/PID 귀속이 확인되지 않은 경우 `unknown_process`로 남긴다. binary 로그와 메시지 본문은 디스크·stdout에 쓰지 않는다.

이 문법·예약어 검사는 임의 ASCII 식별자에 비밀이 절대 없다는 증명이 아니다. 이 옵션의 허용 범위는 고정 합성 입력을 사용하는 통제된 실험이다. 임의 앱·사용자 대화·외부 입력에 확대하려면 정적으로 검토된 태그 allowlist가 필요하다. 알려지지 않은 태그를 그대로 비밀 무포함으로 간주하지 않는다.

이 옵션은 설정을 바꾸지 않는다. 다음 단계에서 태그를 별도로 검토한 후 기존 값 보존 → 한정된 태그에 `S` 적용 → 동일 합성 probe의 기능·로그 동시 검증 → 원래 값 복원을 수행할 수 있다. 서비스 버전·APK hash, 관측 창, PID/binder coverage, positive control, 거절 건수를 함께 판단해야 한다. 합성 실험 무검출 결과를 실제 마이크 승인이나 일반 배포 보장으로 승격하지 않는다.

## 2026-11-09 합성 실관측 결론

[거절 사유 관측](../../artifacts/p0-completion/speech-capability/stt-service-tag-reasons.json)의 finite EOF 합성 probe는 기능·EOF 순서·제품 타이밍 모두 PASS였다. 앱/unknown 프로세스의 일치는 0건이고 SpeechService의 canary는 16건, payload 구조는 2건이었다. 관측 PID/binder coverage와 positive control은 통과했다.

태그는 16개 매치 레코드 모두 `unsupported_tag_characters`로 거절되었고 출력 목록은 비어 있다. 이는 단순히 추가한 출력 문법이 보수적이어서 거절된 `tag_identifier_shape`와 다르며, 실제 Android 속성명에 허용하지 않는 문자를 포함한 경우다. 원래 태그를 그대로 사용하는 `log.tag.<TAG>`를 공개 `setprop` 명령으로 구성할 수 없으므로 이 이미지에서 관측한 경로의 정확한 태그별 차단은 적용하지 않았다. 태그 문자열을 치환하거나 wildcard/prefix가 적용된다고 추측하지 않는다.

이 결과로 이번 공개 태그 설정 조사는 종료한다. 전역 로그 억제, 서비스/이미지 변경, 숨겨진 API, 실제 마이크 입력, 제품 마이크 게이트 활성화는 수행하지 않았다. 기능 PASS와 음성 원문 로그 MATCH_FOUND는 별개로 유지하며, 배포 가능한 원문 로그 차단 근거는 확보되지 않았다.

## 2026-11-09 공개 대안 재검토

[공식 SDK 릴리즈](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)의 최신 공개 STT/TTS는 3.3.1(2026-10-19)이다. Log 유틸의 공개 노출 종료는 별도 SpeechService에서 원문 로그가 제거됐다는 근거가 아니다. 공개 API/설정에서 새 원문 로그 OFF 경로는 확인하지 못했다.

[Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)의 on-device 인식기는 현재 기기에서 지원되지 않는다. [EXTRA_PREFER_OFFLINE](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_PREFER_OFFLINE)은 구현체가 무시할 수 있으며, 기본 recognizer의 존재가 무로그 보장을 뜻하지 않는다. 표준 TTS의 기존 초기화 ERROR도 그대로다. 이 재검토에서는 실제 인식·마이크·기기/계정 설정을 실행하지 않았다.

사용자에게 다른 제공사의 공식 STT·개인 대화용 TTS를 보관 조건과 함께 검토해 연결할지, 음성 대화를 보류하고 UI·데모를 계속할지 선택을 요청했다. 답변 전에는 새로운 음성 제공자를 선정하거나 실제 발화를 전송하지 않는다. STT만 바꾸고 개인 답변을 현재 Pleos TTS로 보내는 것은 출력 원문 로그 문제를 해결하지 않는다. 독립적인 지역·콘텐츠 개발은 계속한다.
