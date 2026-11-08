# TextToSpeech 3.3.1

확인일: 2026-11-06. 이야기·답변·연결 문장의 음성 출력에 사용한다. [API 이름 색인](../api/tts.md)

## 준비와 요청

`TextToSpeech(context, mode)`를 생성하고 `initialize(OnInitializeListener)`의 성공을 확인한다. `addEventListener()`로 이벤트를 연결하고 한국어 locale을 설정한다. SERVER 및 HYBRID의 서버 합성은 `registerApp(clientID, clientSecret, listener)` 등록 후 `onConnected()`를 기다린다. ON_DEVICE는 서버 등록 없이 사용한다. 모드 생략 시 HYBRID이므로 오프라인 검증에서는 명시적으로 지정한다. [모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [초기화](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/initialization-and-release), [서버 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/app-registration)

`speak(text, isFinal)`의 기본 마지막 조각 표시는 true다. 여러 조각을 한 발화로 전달할 때만 앞 조각은 false, 마지막은 true로 전달한다. `stop()`은 진행 중인 출력을 중단한다. [합성·중지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop)

## 콜백의 의미

| 콜백 | 앱에서 구분할 상태 |
| --- | --- |
| `onReady()` | 요청 준비 |
| `onStart()` | 출력 시작 |
| `onDone()` | 정상 재생 완료 |
| `onStop()` | 재생 중단 |
| `onUpdatedRms(Double)` | 음성 신호 크기 갱신 |
| `onError(ErrorType)` | 실패 |

`onStop()`을 문단 청취 완료로 적립하지 않는다. 해제 전 `removeEventListener()`에 등록했던 객체를 전달한다. [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners)

## 출력 설정과 제약

API 참조에는 `setLocale`, `setAudioAttributes`, `setAudioZoneId`, `setSpeakerType`가 있다. 음성·zone의 허용 값과 실차 동작은 해당 함수와 실행 환경을 확인한다. 반환되는 PCM 파일 또는 재생 위치를 제공한다고 가정하지 않는다. [클래스 API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/entry)

선행 묵음 가이드는 150~200 ms를 권장하지만, Pleos TTS 서비스가 직접 출력하는 경로에 앱이 PCM 패딩을 적용할 수 있는지는 별도 검증 사항이다. 앱에서 단순히 sleep을 넣었다고 선행 무음 오디오가 출력된 것으로 보지 않는다. [오디오 공통 규칙](08-audio-and-media.md)

## 오류 분류

권한·인증 오류는 설정을 수정하고, 지원하지 않는 API·언어는 기능 또는 locale을 바꾼다. 네트워크·타임아웃·서버·엔진 오류는 유한한 재시도와 실패 안내로 처리한다. `ErrorType`의 새 값도 기본 분기로 수용한다. 3.3.1의 오류 콜백을 문자열 인자로 구현하지 않는다. [오류 표](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/error-code)

## Petasos 적용과 검증

앱이 만든 attempt ID와 SDK 객체의 수명을 연결하고, 중지·새 요청 후 도착하는 과거 콜백을 차단한다. 버튼을 눌렀다는 이유로 즉시 STT를 켜지 않고 현재 문단과 유도 문장의 완료를 기다린다. 이는 SDK 자체 보장이 아닌 [제품 상태 규칙](../../../Petasos_동작명세.md)이다.

기존 probe는 초기화, 정상 완료, 중지 콜백을 확인했다. 실제 첫 음절 손실·한국어 청취 품질과 다른 앱의 포커스 경쟁은 별도 시험이다. [실행 기록](../../../development/2026-11-06-sdk-probes.md)
