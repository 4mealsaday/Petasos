# Pleos 음성 클라이언트 로그 감사와 dev13 privacy 빌드

작성·공식 문서 확인: 2026-11-08. **수정 후 privacy 빌드·JVM·lint와 8개 합성 기능 probe는 통과했으나, 별도 SpeechService에서 STT/TTS 합성 원문 canary가 검출되었다.** 따라서 제품 마이크 capability는 계속 비활성이다. 실제 사람 발화·개인 답변 TTS·모델 호출은 이 조사에 포함되지 않는다.

APK의 별도 정적 비교는 [package-audit.json](../../artifacts/milestone-12/package-audit.json)에 기록했다. dev12 대비 동일 서명·ID, version/debuggable 외 manifest, permissions/components, localhost CA/XML, 합성 PCM asset 및 선택한 runner/model DEX 선언 보존을 확인했다. privacy의 리소스 파일명은 aapt 최적화로 단축되므로 resource table 이름으로 실제 파일을 찾아 비교했다. 이 정적 검사는 서비스 무로그나 실제 마이크 검증을 대신하지 않는다.

## 조사 범위와 원본

설치 대상 SpeechToText/TextToSpeech 3.3.1 AAR를 `javap -p -c`로 읽었다. 원본 AAR의 `classes.jar`와 Gradle transformed JAR의 SHA-256은 동일했다. 전체 클래스의 로그 참조를 열거한 뒤 현재 ON_DEVICE 호출 경로, 전달 값, 예외 분기를 확인했다. 아래 offset은 **원본 JVM bytecode offset**이며 최적화 후 DEX 주소가 아니다.

| 원본 | SHA-256 |
| --- | --- |
| SpeechToText 3.3.1 AAR | `35109ad13400a7fa103cca9fabbf9b785e63a6a98b17a0698a1aef51458d37bf` |
| STT classes.jar | `260dfd339ed9ed2b7718da7dc9722b2a60b933b41d4b4c9ccaa0016e7bf0b369` |
| TextToSpeech 3.3.1 AAR | `bfbfb58376c69604afad3389878980461971683f0c51f619d03ab74b5e6a0375` |
| TTS classes.jar | `17ed98d62aaeac6b7e35e988e972294b98f3b1c72bca3fad1cffab323ee78b4a` |

두 JAR에서 Android Log 참조는 각각 `utils.Log`에만 있고, `printStackTrace` 참조는 STT `SttCallback`에만 있다. `PrintStream`/`PrintWriter` 직접 참조는 없었다. 이 범위는 전이 의존성·네이티브 코드·Android framework·별도 설치된 음성 서비스 전체를 포함하지 않는다. `.local/sdk-inspection/stt-service-bytecode.txt`도 **클라이언트 SttServiceManager**이며 서비스 구현 자체가 아니다.

## 확인한 로그 경로

`ai.pleos.playground.stt.utils.Log`와 `ai.pleos.playground.tts.utils.Log`의 매핑은 동일하다. 각 함수는 인자 null 검사, 스레드 이름 접두어 구성, Android 로그 호출만 수행한다. parsing/listener/binder 제어는 없다.

| SDK 함수 `(String,String): void` | 실제 Android 함수 | STT/TTS 정적 호출 수 | privacy 처리 |
| --- | --- | --- | --- |
| `a` | `Log.d` DEBUG | 71 / 62 | Android 두 인자 DEBUG sink 제거 |
| `b` | `Log.e` ERROR | 48 / 32 | 유지 |
| `c` | `Log.i` INFO | 50 / 27 | STT helper만 제거 |
| `d` | `Log.w` WARN | 8 / 9 | 두 SDK helper 제거 |

호출 수에는 미사용 모드·API가 포함된다. 실행 횟수나 모두 도달 가능하다는 의미가 아니다.

| 직접 관측한 클래스·메서드 | 로그로 전달되는 값과 판단 |
| --- | --- |
| `stt.service.SttCallback.onResponse` / `tts.service.TtsCallback.onResponse` | `UpdateRms` 외 이벤트의 type 및 전체 data가 DEBUG helper로 전달된다(STT 53, TTS 54). 서비스가 각 TTS data에 무엇을 넣는지까지 이 AAR로 확정하지 않는다. |
| `tts.factory.OnDeviceStreamTts$doSpeak$1.invokeSuspend` | `isFinal=true`일 때 누적 speak 본문이 DEBUG helper에 전달된다(100–154). 현재 앱의 `speak(text,true)`가 이 분기를 사용한다. |
| `stt.viewmodel.SttViewModel$notifySttChanged$1.invokeSuspend` | `$stt` 문자열을 63에서 읽고 66에서 붙인 뒤 **INFO** helper를 84에서 호출한다. DEBUG만 제거하면 인식 원문 경로가 남는다. |
| `stt.viewmodel.SttViewModel$notifyError$1.invokeSuspend` | opaque error 문자열을 INFO에 전달한 뒤 `ErrorType`으로 변환한다(77). helper만 제거하고 변환·listener는 유지한다. |
| `stt.service.SttCallback.onResponse` | 예외 분기의 무인자 `Throwable.printStackTrace()`(1228)를 제거 대상으로 지정한다. 예외 처리 자체를 무효화하지 않는다. |
| `OnDeviceStt.f()` / `OnDeviceStreamTts.f()` | bind 실패 후 unbind의 `IllegalArgumentException`을 문자열에 붙여 WARN에 전달한다(123 / 113). 실제 발화가 포함되었다는 증거는 없으나 외부 예외 문자열 경계이므로 해당 순수 helper만 제거한다. |
| `tts.service.TtsCallback.onResponse` | 미지원 이벤트의 **type** 문자열을 WARN에 전달한다(362). data를 이 WARN에 붙이는 것은 아니다. 같은 TTS WARN 규칙으로 제거한다. |

현재 앱은 ON_DEVICE를 선택하며 UA, registerApp, Ambient AI, enrollment, EIC, profile, wake-word, speaker 설정을 사용하지 않는다. ON_DEVICE binder 실패의 ERROR는 예외 객체를 버리고 고정 문구를 출력한다. 현재 사용 중인 TTS INFO는 동작 이름·모드·locale·오디오 설정이므로 유지했다.

미사용 UA/registerApp 실패 ERROR에는 opaque 문자열이 있고, TTS Ambient AI INFO에도 String 인자가 있다. SDK 전체가 모든 모드에서 정제되었다고 주장하지 않는다. 두 service manager의 UA flag는 생성 시 false이며, 현재 speak/request는 UA channel을 통과하지 않는다. TTS의 unknown `SpeakRequest` ERROR도 이 미사용 channel에 있고, 현재 AAR의 두 request subtype은 앞선 분기에서 처리된다. 새 SDK나 이 API들을 사용하면 다시 감사해야 한다.

## 빌드 설정과 최초 실패

[privacy-rules.pro](../../app/privacy-rules.pro)는 위의 로그 함수만 `-assumenosideeffects`로 지정한다. 전체 ERROR/INFO 제거, SDK 본체 no-op, binder/listener/parser 변경은 없다. Android DEBUG·Throwable 규칙은 최적화 대상 코드 전체의 정확한 signature에 적용되며 SDK 전용 패키지 규칙은 INFO/WARN helper로 한정한다.

앱·probe API는 keep하고, STT Gson 모델(`ConversationInfo`, `SttFinalResult`, `stt.data.**`), TTS `AudioInfo`, `AudioAttributesParcel`/CREATOR 및 reflection attributes를 최적화 없이 보존한다. `-dontshrink`, `-dontobfuscate`는 유지하고 SDK 함수 본문 최적화는 허용한다. `-dontshrink` 때문에 호출되지 않는 logger 본체가 DEX에 남을 수 있으므로 **실제 호출 위치와 유입 경로**를 검사해야 한다. 문자열 또는 메서드 존재 여부만으로 성공·실패를 판정하지 않는다.

첫 시도는 `debuggable=true`였고, AGP가 최적화 차단 경고를 출력한 뒤 Tink 누락 클래스 때문에 실패했다. 이 시도는 검증된 privacy APK를 만들지 못했다. 로컬 AGP 8.10.1 `VariantManager`의 해당 조건과 `R8Task$CreationAction`의 debuggable 전달을 확인했다. 공식 [R8Command 소스](https://r8.googlesource.com/r8/+/refs/heads/main/src/main/java/com/android/tools/r8/R8Command.java)(확인 blob `6d92e4b642bdbc4f993192ad9bc0e483bb9a53f6`)도 DEBUG 모드에서 optimization을 끈다.

수정한 `privacy`는 `debuggable=false`, minify=true, resource shrink=false다. 기존 debug 서명·application ID·앱 데이터와 dev13 버전을 유지하고 기본 debug는 그대로 둔다. `petasosTestBuildType`은 `debug`(기본)/`privacy`만 허용한다. non-debuggable variant에는 `run-as` fixture 작업을 사용할 수 없다. 정상 동일 서명 instrumentation으로 검사하며 manifest/DEX 사후 변조는 하지 않는다.

privacy는 debug manifest/resource source set을 명시적으로 공유한다. `debug_network_security_config.xml`은 **localhost 한정 CA domain-config**이므로 debuggable=false에도 적용된다. `debug-overrides`가 아니다. 일반 release는 이 source set을 공유하지 않는다. [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)

## Tink 누락 클래스 9개 한정 처리

R8의 9개 누락형은 미사용 `com.google.crypto.tink.util.KeysDownloader` 및 중첩 helper에만 연결된다. `-dontshrink`로 남은 다운로드 유틸의 HTTP transport/static initializer, fetch/cache 처리, Joda cache clock 참조다. 다음 **정확한 클래스 이름**에만 `-dontwarn`을 추가했다. 새 의존성이나 패키지 wildcard는 추가하지 않았다.

```text
com.google.api.client.http.GenericUrl
com.google.api.client.http.HttpHeaders
com.google.api.client.http.HttpRequest
com.google.api.client.http.HttpRequestFactory
com.google.api.client.http.HttpResponse
com.google.api.client.http.HttpTransport
com.google.api.client.http.javanet.NetHttpTransport$Builder
com.google.api.client.http.javanet.NetHttpTransport
org.joda.time.Instant
```

확인한 Tink Android 1.8.0 JAR SHA-256은 `5efe8f1cb6e75814ae06793d5ff8d9b01e6f88ef97ecc062d14ebc27027dbf78`, 함께 확인한 캐시 Tink 1.7.0은 `88970a456a08ba4c66b01b23e5846ca1095cc14e54cb48363e5d2e15a1307308`이다. 두 구버전 POM은 HTTP/Joda 의존성을 **생략**한다. optional 플래그가 있다고 해석하지 않았다.

빌드 도구·compiler를 제외한 캐시 상위집합 250개 JAR/AAR, 67,892개 class를 확인한 결과 `KeysDownloader` 참조는 이 두 JAR의 본체·Builder·익명 Runnable 내부뿐이었다. Pleos AAR 530개 class 및 앱 소스에서도 외부 참조는 없었다. 이는 literal classfile/String 참조 검사이며 일반적인 동적 reflection 부재의 증명은 아니다. 현재 제품에 이 utility를 선택하는 설정·reflection 경로는 없다. 공식 [Tink Java Apps 릴리즈](https://github.com/tink-crypto/tink-java-apps/releases)도 이 유틸을 payment-token/rewarded-ad 기능으로 분리한 이력을 설명한다.

`dontwarn`은 누락형을 구현하지 않는다. 새 코드가 downloader를 사용하면 실제 의존성을 갖춰야 하며 이 억제 근거를 다시 검토해야 한다. 새로운 missing-class 오류를 일괄 억제하지 않는다.

## 빌드·DEX·합성 실행 결과

[최종 빌드](../../artifacts/milestone-12/build.log)는 exit 0, 3분 19초로 완료했다. `assemblePrivacy`, `assemblePrivacyAndroidTest`, `testPrivacyUnitTest`, `lintPrivacy`가 통과했다. JVM XML 27개 suite의 348 tests는 failures/errors/skipped 모두 0이며 lint XML의 issue는 0이다. 별도 19개 broad 회귀 결과는 이 문서 작성 시점의 검사 범위에 포함하지 않는다.

[최적화 DEX 비교](../../artifacts/milestone-12/static-privacy-comparison.json)에서 확인한 DEBUG·무인자 stacktrace sink가 사라졌고, STT `c`/`d` 및 TTS `d` helper를 호출하는 executable instruction은 각각 0개였다. DEBUG helper `a` 호출 71/62개는 남지만 그 helper 내부의 Android DEBUG sink는 없다. `dontshrink`로 남은 미호출 INFO/WARN helper 본체와 실제 호출 제거를 구분했다. [모델 검사](../../artifacts/milestone-12/static-privacy.json)는 필드·생성자·generic list signature·SerializedName·Parcelable CREATOR 보존을 확인했고, 앱의 send/complete/stop/speak 호출 수는 baseline과 같았다. 이는 전체 프로그램 taint 분석이나 모든 모드의 실행 증명이 아니다.

이번 8개 실행에서 클라이언트 프로세스는 매번 새로 시작했지만 실제 SpeechService 프로세스는 유지됐다. 서비스는 `ai.pleos.playground.caas/.service.speech.SpeechService`, version `4.2.10-coral-aosp` / code `4002010`이다. 따라서 **warm 서비스 반복 관찰**이며 cold 서비스 초기화·재시작·실제 `AudioRecord`는 시험하지 않았다.

| 합성 STT 실행 | 앱 입력 Ready→SDK Started | SDK Ended→Final | 기능 probe / 로그 관찰 |
| --- | ---: | ---: | --- |
| [stream 1](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-first.json) | 2,488 ms | 5 ms | PASS / MATCH_FOUND |
| [stream 2](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-second.json) | 932 ms | 4 ms | PASS / MATCH_FOUND |
| [stream 3](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-third.json) | 925 ms | 10 ms | PASS / MATCH_FOUND |
| [1.5초 선행 무음 1](../../artifacts/milestone-12/privacy-observer/stt-owned-delayed-first.json) | 2,421 ms | 4 ms | PASS / MATCH_FOUND |
| [1.5초 선행 무음 2](../../artifacts/milestone-12/privacy-observer/stt-owned-delayed-second.json) | 2,441 ms | 5 ms | PASS / MATCH_FOUND |
| [finite 구성 1](../../artifacts/milestone-12/privacy-observer/stt-owned-pcm-first.json) | 920 ms | 4 ms | PASS / MATCH_FOUND |

STT 6개 각각에서 서비스 canary 18건, 인식 payload 구조 2건을 관측했다. [TTS speak](../../artifacts/milestone-12/privacy-observer/tts-speak.json)와 [TTS stop](../../artifacts/milestone-12/privacy-observer/tts-stop.json)도 기능 probe는 PASS이며 서비스 canary가 각각 1건 검출됐다. 8개 모두 앱·unknown process의 canary/payload/stacktrace 일치는 0건, positive control 통과, PID coverage complete, reasonCodes 빈 목록이다. canary와 payload 구조 count는 겹칠 수 있어 합산하지 않는다. 이 **기능 PASS와 원문 로그 MATCH_FOUND는 서로 다른 판정**이다.

모든 STT 실행은 payload 72,313 samples 중 65,600 samples를 보낸 시점에 callback으로 입력이 끝났다. 남은 6,713 samples는 fixture 파일 확인상 무음이다. delayed 실행은 이 payload 앞에 24,000 samples의 무음을 추가한 것이다. finite로 구성한 실행도 실제 EOF에 도달하지 않았으므로 이번 결과를 EOF 종료 경로 재검증으로 인정하지 않는다. `completeAudioSend()` 호출 시각은 계측하지 않았다. 따라서 Final이 완료 요청 전에 도착했다고 주장하지 않는다.

observer는 합성 canary를 메모리에서 비교하고 해시·시각·개수·고정 실패 코드·coverage만 저장했다. 원문 logcat·인식 텍스트·예외 내용은 저장하지 않았다. 0건은 실행한 경로와 관찰 구간에 한정된 결과이며, R8은 별도 서비스 프로세스를 수정하지 않는다. 현재 남은 활성화 차단은 **서비스 측 원문 로그를 끄는 공식 설정 또는 수정 아티팩트**이며 [지원 문의 초안](pleos-speech-support-request.md)에 요청 사항을 정리했다. 문의는 전송하지 않았다.

기존 [PCM 결과](2026-11-07-owned-input-and-vehicle-policy.md)의 finite 21,986/87 ms, continuous 2,306/164 ms도 유효한 과거 관측으로 유지한다. 이번 warm 실행의 920–2,488 ms / 4–10 ms만으로 과거 지연 실패의 원인이 해결되었다거나 cold/live 입력 요구를 충족한다고 판정하지 않는다. 첫 발화 5초·Final 500 ms 요구는 변경하지 않았다.

수정 후 빌드에 사용한 소스 SHA-256:

| 파일 | SHA-256 |
| --- | --- |
| app/build.gradle.kts | `f7bea3226a32531acd29a675bf35ec6eb3a6ea685359f6f3bb16b6d9c78585dd` |
| app/privacy-rules.pro | `8fe07a3ab9d6dc5598931df4ca2735505740b9bfd554005821ae259ce8709896` |
| app/privacy-test-rules.pro | `524eaf7a723ac4cba504eae9c010af07baaad90c3fd21eb8ac598b0e43ef485e` |

2026-11-08 확인한 [Pleos 릴리즈](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note), [STT import](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/importing-sdk), [TTS import](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/importing-sdk)의 공개 최신 버전은 3.3.1(2026-10-19)이었다. 로그 off API나 이후 공개 수정 버전은 찾지 못했으며, 이 부재를 vendor 보증으로 해석하지 않는다. R8 구현 방식은 Android의 [추가 규칙](https://developer.android.com/topic/performance/app-optimization/additional-rule-types), [최적화 활성화](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization), [testBuildType](https://developer.android.com/studio/test/advanced-test-setup) 공식 문서를 따른다.
