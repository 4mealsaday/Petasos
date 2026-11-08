# Pleos 음성 서비스 기술 문의 초안

상태: **미전송 초안**. 2026-11-08 합성 STT 6회·TTS 2회 관측까지 반영했다. 실제 사람 발화, 개인 답변, 키·토큰·프로젝트 식별자, 원문 로그는 포함하지 않는다.

공식 문의 안내는 [Pleos 개발자 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq) 하단과 [STT 오류 가이드](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/SpeechToText/error-code)의 service administrator 안내를 참고한다. 음성 SDK 전용 티켓 경로나 담당 부서는 공개 문서에서 확인하지 못했으므로 발송 전에 기존 프로젝트 담당 채널을 확인한다. 이 문서 작성 과정에서는 외부 연락을 하지 않았다.

## 제목

ON_DEVICE SpeechService의 인식·합성 원문 로그 비활성화 및 외부 PCM 실시간 처리 계약 확인 요청

## 문의 내용

안녕하세요. Pleos Connect 앱에서 ON_DEVICE STT/TTS와 외부 PCM 입력을 검증하고 있습니다. 개인 대화 활성화 전에 서비스 측 원문 로그를 끄는 공식 방법과 실시간 입력의 준비·종료 동작을 확인하고자 합니다. 현재 사람 마이크와 개인 답변 TTS는 비활성 상태이며, 재현은 비개인 합성 fixture만 사용했습니다.

검증 환경은 다음과 같습니다.

| 항목 | 값 |
| --- | --- |
| 에뮬레이터 | Pleos Connect `release_connect_v2.1.0`, Android API 34, ARM64 |
| 클라이언트 SDK | `ai.pleos.playground:SpeechToText:3.3.1`, `TextToSpeech:3.3.1` |
| 실제 연결 서비스 | `ai.pleos.playground.caas/.service.speech.SpeechService` |
| 설치 서비스 버전 | `4.2.10-coral-aosp`, versionCode `4002010` |
| 서비스 APK SHA-256 | `df219cadd7ad8353d9c7cb8080741a7377615f869ac7ff8ef110c07c4faf50ad` |
| 입력 경로 | `Mode.ON_DEVICE`, 외부 PCM16 little-endian / 16 kHz / mono, 최대 3,200 bytes씩 약 100 ms 간격으로 `sendAudio()` |
| 입력 제어 | SDK 소유 마이크 `request()` 미사용. 합성 입력에는 실제 `AudioRecord`도 미사용 |

### 1. 클라이언트와 서비스 로그를 분리한 관측

클라이언트 AAR의 정적 검사에서 STT callback data 및 `notifySttChanged` 인식 문자열이 DEBUG/INFO로, ON_DEVICE TTS의 누적 speak 본문이 DEBUG로 전달되는 것을 확인했습니다. 앱 패키지에 들어가는 해당 로그 sink만 제거하는 R8 진단 빌드를 별도로 만들었습니다. SDK parser·listener·binder와 설치된 서비스는 수정하지 않았습니다. 이것을 공식 SDK 기능이나 서비스 로그 해결책으로 간주하지 않습니다.

2026-11-08 KST continuous 합성 PCM 3회, 선행 무음 1.5초를 추가한 PCM 2회, finite PCM 구성 1회, 합성 TTS speak/stop 각 1회의 관측은 다음과 같습니다. 매회 앱 클라이언트는 새 프로세스로 실행했지만 실제 연결된 서비스 프로세스는 유지됐습니다.

| 항목 | 결과 |
| --- | --- |
| SDK 인식·입력 종료·늦은 결과 거부 / TTS 동작 probe | 8회 모두 PASS |
| 앱 클라이언트의 canary / payload 구조 / stacktrace 일치 | 8회 모두 0건 |
| STT 실행의 SpeechService 합성 canary 일치 | 6회 각각 **18건** |
| STT 실행의 SpeechService 인식 payload 구조 일치 | 6회 각각 **2건** |
| TTS speak / stop의 SpeechService 합성 canary 일치 | 각각 **1건** |
| unknown process 일치 | 8회 모두 0건 |
| observer positive control / PID coverage | 8회 모두 통과 / 완전 |
| observer 누락·불명확 원인 | 8회 모두 없음 |

canary와 payload 구조 count는 서로 겹칠 수 있어 합산하지 않습니다. 관측은 알려진 합성 문장을 메모리에서 비교해 **개수와 고정 metadata만** 남겼으며, 원문 로그나 인식 문장은 저장하지 않았습니다. 앱 쪽 0건은 이 실행 경로·관측 구간에 한정된 결과이며 SDK·서비스 전체의 무로그 보장이 아닙니다. 실제 바인딩 대상 서비스 프로세스에서 STT/TTS canary가 검출되어, 앱 클라이언트 로그 제거만으로 개인 대화의 원문 로그 문제를 해소할 수 없다고 판단합니다.

다음 정보를 요청드립니다.

1. STT 중간·최종 인식문, TTS 입력문 및 callback payload, 예외에 포함된 원문을 **클라이언트 SDK와 SpeechService 양쪽에서** 비활성화하는 공식 공개 설정/API가 있습니까? 서비스·native engine·진단 로그까지 적용되는 범위와 기본값을 알려주십시오.
2. 현재 에뮬레이터의 개발용 서비스와 배포 차량용 서비스의 logging 정책이 다릅니까? 원문을 기록하지 않는 검증 가능한 SDK/서비스/에뮬레이터 버전과 적용 절차, 호환 조합을 제공해 주실 수 있습니까?
3. 설정이나 수정 버전이 있다면 서비스 재연결·프로세스 재시작·사용자 전환 뒤에도 유지되는지, 정상/오류/취소 경로를 포함하는지 확인 부탁드립니다. 로그 수집기에서 원문을 숨기는 방법이 아닌 **발생 자체를 끄는 지원 경로**가 필요합니다.

### 2. 외부 PCM의 준비·EPD·최종 결과 계약

공식 [외부 PCM 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)는 분할 전송과 마지막 `completeAudioSend()`를 설명합니다. 실시간 입력에서 EOF가 없을 때의 준비 상태 및 callback 지연 상한은 확인하지 못했습니다.

현재 앱은 SDK의 EPD_ON을 Started, EPD_OFF를 Ended로 취급합니다. 앱이 소유한 입력의 Ready는 캡처 준비 상태를 뜻하며 SDK 엔진이 발화를 인식할 준비를 마쳤다는 별도 보증으로 해석하지 않습니다. EPD_OFF를 받으면 입력을 종료하고 `completeAudioSend()`를 호출하므로 아래 continuous 결과도 Final을 완료 요청 **이전**에 받았다는 증거가 아닙니다.

| 합성 입력 관찰 | 앱 입력 Ready→SDK Started | SDK Ended→Final | 해석 |
| --- | ---: | ---: | --- |
| 기존 유한 PCM: EOF에서 완료 요청 | 21,986 ms | 87 ms | 첫 발화 5초 요구 초과 |
| 기존 EOF 없는 PCM 한 번 | 2,306 ms | 164 ms | 해당 실행에서만 두 요구 이내 |
| 이번 privacy 빌드 EOF 없는 PCM 3회 | 2,488 / 932 / 925 ms | 5 / 4 / 10 ms | 같은 warm 서비스, 앱은 매번 새 프로세스 |
| 이번 선행 무음 1.5초 PCM 2회 | 2,421 / 2,441 ms | 4 / 5 ms | 같은 warm 서비스 |
| 이번 finite PCM 구성 1회 | 920 ms | 4 ms | callback으로 먼저 종료되어 EOF 미실행 |

이번 6개 STT 실행은 모두 payload 72,313 samples 중 65,600 samples를 공급한 뒤 callback으로 입력을 종료했습니다. 남은 fixture tail은 무음이며, finite 구성도 EOF까지 도달하지 않았습니다. `completeAudioSend()` 시각은 계측하지 않았으므로 Final이 완료 요청 이전에 도착했다는 결론을 내리지 않습니다. 실제 마이크나 cold 서비스 초기화·재시작은 시험하지 않았습니다. 이번 warm 반복 결과로 과거 21,986 ms의 지연 원인이 해소되었다고 해석하지 않습니다. 우리 제품의 첫 발화 대기 5초, EPD_OFF 뒤 최종 결과 유예 500 ms 요구는 유지하고 있습니다. 다음 계약을 확인 부탁드립니다.

4. `initialize(OnInitializeListener)`의 `onSuccess()` 후 첫 `sendAudio()`를 즉시 보내도 됩니까? callback은 binder 연결만 의미합니까, cold start 모델 로딩·엔진 입력 준비 완료까지 의미합니까? 외부 PCM의 준비 완료를 확인할 공식 callback 또는 실패 신호가 있습니까?
5. EOF 없는 지속 스트림에서 `completeAudioSend()` **이전** EPD_ON/OFF 전달이 지원 계약입니까? EPD_OFF 후 입력 종료·`completeAudioSend()` 호출이 권장 흐름인지, Final 전달에 완료 요청이 필요한지 알려주십시오.
6. cold start/재연결 시 첫 발화 인식과 EPD_OFF→Final에 알려진 초기화 비용·버퍼링·최대 지연이 있습니까? 지원되는 PCM chunk 크기/간격, backpressure 방식, 권장 초기화 절차 또는 호환 버전이 필요합니다. 요구 시간을 임의로 늘리거나 자체 VAD를 SDK EPD로 가장하는 방식은 적용하지 않았습니다.
7. 외부 PCM 입력의 명시적 취소·종료 뒤 서비스가 해당 입력을 더 처리하지 않는다는 확인 방법이 있습니까? SDK 마이크 종료와 앱 소유 `AudioRecord.stop`/STOPPED/`release` 확인은 구분하고 있습니다.

원문이 없는 실행 metadata, 정확한 아티팩트 해시와 최소 합성 재현 절차는 제공할 수 있습니다. 필요한 진단 항목과 안전한 제출 방법을 안내해 주시면 해당 범위에 맞춰 준비하겠습니다.

## 내부 근거 — 전송 시 첨부 범위 별도 선택

- [클라이언트 정적 감사 및 AAR SHA](2026-11-08-speech-client-audit.md)
- [패키지·서명·manifest 비교](../../artifacts/milestone-12/package-audit.json)
- [설치된 서비스 APK 식별](../../artifacts/milestone-12/service-artifact.json)
- [continuous 1](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-first.json), [2](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-second.json), [3](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-third.json)
- [선행 무음 1](../../artifacts/milestone-12/privacy-observer/stt-owned-delayed-first.json), [2](../../artifacts/milestone-12/privacy-observer/stt-owned-delayed-second.json), [finite 구성](../../artifacts/milestone-12/privacy-observer/stt-owned-pcm-first.json)
- [TTS speak](../../artifacts/milestone-12/privacy-observer/tts-speak.json), [TTS stop](../../artifacts/milestone-12/privacy-observer/tts-stop.json)
- [기존 유한/continuous PCM 관찰과 한계](2026-11-07-owned-input-and-vehicle-policy.md)

내부 실행 JSON 전체를 그대로 보내지 않는다. 불필요한 로컬 경로·PID/UID·실행 식별자는 제외하고 위 환경·집계·시간·SDK/서비스 버전만 사용한다. 추가 서비스 아티팩트 식별 결과는 필요한 범위만 별도 검토한다.
