# SpeechToText 3.3.1

확인일: 2026-11-06. 사용자의 발화를 텍스트로 변환한다. 명령 실행과 대화 생성은 앱의 별도 처리다. [API 이름 색인](../api/stt.md)

## 차량 마이크 경로

STT_SERVICE 권한 → `SpeechToText(context, mode)` → 초기화 성공 → `addListener()` → locale·필요한 서버 등록 → `request()` → 결과 처리 → `stop()` 및 해제 순서다. `request()`가 마이크 입력을 시작하므로 앱 실행·재개 자체와 연결하지 않는다. [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions), [초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/initialization-and-release), [입력 시작·중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop)

지원 locale 안내에는 ko-KR, en-US, fr-CA, es-US, es-MX가 있다. `setLocale()`은 다음 요청 전에 설정한다. 이전 앱 설정 또는 시스템 locale로의 기본값 선택에 의존하지 않고 한국어를 명시한다. [언어 설정](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/set-locale)

## 결과 리스너

| 콜백 | 의미·주의 |
| --- | --- |
| `onReady()` | 인식 요청 준비 |
| `onStartedRecognition()` | 인식 시작 |
| `onUpdated(stt, completed)` | 중간/최종 텍스트. completed=true가 결과 완료 표시 |
| `onEndedRecognition()` | 인식 종료 이벤트. 최종 텍스트 및 모든 stop 요청의 완료 응답과 구분. 아래 실행 관찰 참고 |
| `onUpdatedEpdData(on, off)` | 음성 구간의 입력 버퍼 byte offset. 시각·ms가 아님 |
| `onUpdatedRms(rms)` | RMS 신호 크기. dB로 해석하지 않음 |
| `onError(errorType)` | 오류 |

요청 전에 리스너를 연결하고 해제에는 동일 객체를 사용한다. 완료 텍스트와 종료 이벤트의 도착 순서가 항상 같다는 보장은 이 가이드에서 확인하지 못했다. [이벤트 명세](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners)

## 외부 PCM 경로

`sendAudio(bytes)`는 ON_DEVICE에서 외부 PCM을 전송한다. 첫 버퍼가 인식을 시작하므로 별도 `request()`가 필요하지 않다. 이 경로에서는 Ready·RMS 콜백을 발생시키지 않으며, 다른 모드에서는 호출을 무시하고 오류 콜백도 보내지 않는다고 API가 설명한다. 마지막 입력 뒤 `completeAudioSend()`가 필요하다. 마이크 probe의 Ready 기준을 그대로 재사용하지 않는다. [API 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/speech-to-text/ai.pleos.playground.stt/speech-to-text/entry)

가이드의 3,200 bytes/100 ms는 16 kHz·16-bit·mono PCM 예시다. 모든 입력 형식의 고정 규격으로 확대하지 않는다. 제품의 원음 미저장 정책과 독립적인 합성 테스트 자산으로 시험한다. [PCM 입력](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)

## 모드·실패 처리

ON_DEVICE는 서버 등록을 하지 않는다. SERVER와 HYBRID의 서버 인식은 My Project 등록·연결 결과를 확인한다. 서버와 온디바이스의 승인·성능 결과를 따로 기록한다. [등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/app-registration), [모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)

MIC_ERROR는 입력 장치·개인정보 설정, PERMISSION_DENIED는 서비스 권한, UNAUTHENTICATED는 자격 증명을 조사한다. NETWORK_ERROR·TIMEOUT·SERVER_INTERNAL_ERROR 등은 제한된 재시도 대상으로 분류하고, NOT_SUPPORTED_API는 모드·환경을 재확인한다. 모든 오류를 네트워크 실패로 처리하지 않는다. [오류 코드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/error-code)

## 현재 실행 결과와 제품 정책

최초 `dev.2` probe는 Ready 직후 stop 뒤 Ended가 오지 않아 BLOCKED였다. 후속 `dev.3` 기록은 SDK 3.3.1의 바이트코드에서 Ended가 EPD_OFF를 전달한다는 사실을 확인했다. 무발화 취소에는 이 이벤트가 없을 수 있으므로, 모든 stop 요청의 완료 응답으로 기다리지 않는다. 후속 시험은 합성 PCM의 최종 결과·EPD 종료와 무발화 취소·해제 시 녹음 및 서비스 연결 정리를 각각 확인했다. 이는 이번 문서 작업에서 재시험한 결과가 아니라 기존 개발 기록의 관찰이다. [최초 기록](../../../development/2026-11-06-sdk-probes.md), [후속 검증](../../../development/2026-11-06-stt-verification.md)

같은 후속 기록에는 비동기 stop 직후 release할 때 재연결이 발생한 사례와, stop 후 1초 동안 연결을 유지하는 우회책이 있다. 이 시간은 해당 SDK·환경의 관찰에 따른 값이며 SDK가 보장하는 종료 대기 시간은 아니다. 사람의 실제 마이크 발화, 부하·중단 환경, 입력 종료와 출력 전환은 별도 검증으로 남아 있다.

Petasos는 버튼 1회에 발화 1회를 처리하고 자동으로 마이크를 다시 열지 않는다. 취소된 turn의 늦은 결과, 중복 completed, 결과/종료 역순을 처리한다. 최종 텍스트는 암호화 임시 보관하고 요약 커밋 후 정리한다. [동작 명세](../../../Petasos_동작명세.md), [데이터 계약](../../../Petasos_데이터_API_계약.md)
