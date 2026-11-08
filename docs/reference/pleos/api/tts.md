# TextToSpeech API 이름 색인

공식 가이드 기준 3.3.1 · 수집일 2026-11-06

웹페이지가 동적으로 불러오는 공식 Dokka 본문에서 추출했다. 메서드 이름의 중복 오버로드는 하나로 묶었다. 이 색인은 검색용이며, 매개변수·nullability·권한·deprecated 여부는 연결된 클래스 원문과 사용 중인 AAR을 대조한다. 모든 SDK 타입을 수록한 목록은 아니다.

[전체 안내](../README.md) · [기계 판독 색인](symbol-index.json)

## EventListener

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts.listener/event-listener/entry)

**수명주기·기타:** `onDone`, `onError`, `onReady`, `onStart`, `onStop`, `onUpdatedRms`

## OnInitializeListener

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts.listener/on-initialize-listener/entry)

**수명주기·기타:** `onFailure`, `onSuccess`

## OnServerConnectionListener

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts.listener/on-server-connection-listener/entry)

**수명주기·기타:** `onConnected`, `onFailed`

## TextToSpeech

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/text-to-speech/ai.pleos.playground.tts/text-to-speech/entry)

**수명주기·기타:** `addEventListener`, `initialize`, `release`, `removeEventListener`, `speak`, `stop`

**구독:** `registerApp`

**설정·제어:** `setAudioAttributes`, `setAudioZoneId`, `setLocale`, `setSpeakerType`

