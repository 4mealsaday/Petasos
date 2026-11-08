# 공식 API로 진행할 위치·음성 연결 경로

작성일: 2026-11-08. 사용자가 공유한 [Notion 개발 환경](https://app.notion.com/p/ce7cf80747f283d4a3848106d478aabe)과 공식 문서, 현재 코드를 대조했다. 사용자 요청은 공개된 공식 API 범위에서 구현을 이어가는 것이다. 이 기록은 새 기기 관측이나 권한 승인을 의미하지 않는다.

## 이전 판단의 수정

업체 지원 창구나 별도 보증을 확보해야 모든 위치·음성 개발을 계속할 수 있다는 판단은 과도했다. 공식 API의 지원 능력을 실행 환경에서 점검하고, 사용 가능한 범위를 독립 어댑터로 구현할 수 있다. 지원 문의는 이 작업의 선행 조건이 아니며 외부 연락은 하지 않았다.

Notion 개발 환경 문서는 FusedLocation의 위치 조회·구독, NaviHelper의 경로/경유지 기능, STT/TTS의 세 모드를 정리한다. 이 기능의 존재와 개별 값의 의미·전체 도로 형상 제공 여부·개인정보 검수 결과는 구분한다.

## 음성: ON_DEVICE는 이미 구현됨

- 제품 TTS는 [PleosSpeechOutput.kt](../../app/src/main/java/com/petasos/app/platform/pleos/speech/PleosSpeechOutput.kt) 126행, 제품 PCM STT는 [PleosPcmSpeechInput.kt](../../app/src/main/java/com/petasos/app/platform/pleos/speech/PleosPcmSpeechInput.kt) 145행에서 `Mode.ON_DEVICE`를 명시한다.
- 공식 문서상 ON_DEVICE는 네트워크와 Speech 서버 앱 등록이 필요 없다. 서버 인식을 사용하는 HYBRID/SERVER에만 My Project 자격 증명과 등록이 필요하다. 현재 경로를 위해 사용자에게 추가 Client ID/Secret을 요청하지 않는다. [STT 모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro), [TTS 모드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [앱 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/app-registration)
- 앱 소유 PCM을 `sendAudio()`로 보내고 마지막 입력 뒤 `completeAudioSend()`를 호출하는 것은 공식 ON_DEVICE API다. 현재 구현은 이 경로를 사용한다. [PCM 입력 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)

ON_DEVICE는 처리 위치를 정하며 무로그 모드를 뜻하지 않는다. 기존 합성 관측에서 SpeechService `4.2.10-coral-aosp`의 STT 시험 문장 18건/인식 payload 구조 2건, TTS 시험 문장 1건이 검출됐다. 앱 측 일치는 각각 0건이었다. 이 수치는 새 실행 결과가 아니며 [기존 STT 관측](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-first.json), [기존 TTS 관측](../../artifacts/milestone-12/privacy-observer/tts-speak.json)을 따른다.

따라서 현재 [PetasosSessionHost.kt](../../app/src/main/java/com/petasos/app/platform/android/runtime/PetasosSessionHost.kt) 111행의 `supportsLiveInput() = false`는 서버 등록 누락을 뜻하지 않는다. 개인 대화 원문 로그와 입력 시간 검수는 별도 요구다. 동시에 공식 API를 사용하는 합성 cold 시작·실제 EOF·완료 요청 시점·취소/최종 결과 순서 시험은 계속 진행할 수 있다. 공급자의 지연 보증이 있어야 시험할 수 있는 것은 아니다. 마이크 실발화와 개인 답변 출력은 기존 개인정보 조건을 충족한 후 별도 검수한다.

## 위치: 표준 Android 제공자부터 점검

다음 작업은 Android `LocationManager`의 `getAllProviders()`, `hasProvider()`, `getProviderProperties()`, `isProviderEnabled()`, `isLocationEnabled()`로 제공자 능력과 활성 상태를 확인하는 것이다. 이 단계에서는 `getLastKnownLocation()`·`getCurrentLocation()`·위치 업데이트 구독을 호출하지 않아 실제 좌표를 수집하지 않는다. **이 문서 작성 시 새 제공자 관측 결과는 없다.** [공식 LocationManager](https://developer.android.com/reference/android/location/LocationManager)

후속 관측(2026-11-08 07:17:20Z): 좌표/이력을 저장하지 않는 shell 서비스 메타데이터 검사에서 `gps`, `fused`, `passive` 제공자와 Android user10의 전역 위치 ON을 확인했다. `network` 제공자 표시는 없었다. [관측 JSON](../../artifacts/milestone-20/android-location-capability-metadata.json). 앱의 공개 API 호출·실제 수신·정확도 검증과는 구분한다. 기존 위치 동의나 권한은 변경하지 않았다.

지원되는 제공자가 있으면 별도 Android 위치 어댑터를 구현할 수 있다. 표준 `Location`은 epoch 측정 시각, 부팅 후 측정 단조 시각, `hasAccuracy()`로 존재를 확인하는 68% 수평 정확도 미터를 제공한다. 신선도는 측정 단조 시각으로 계산하고 원래 측정·수신 시각과 제공자 출처를 보존한다. [공식 Location](https://developer.android.com/reference/android/location/Location)

Android의 의미를 Pleos 필드에 덮어씌우지는 않는다. 현재 [PleosLocationSource.kt](../../app/src/main/java/com/petasos/app/platform/pleos/location/PleosLocationSource.kt) 143행 이후는 `confidence`·`timestampMillis` 원본과 수신 시각을 분리한다. 이 원본의 의미 미확인은 그대로 유지한다. 서로 다른 제공자의 위치에 다른 제공자의 정확도나 측정 시각을 붙이지 않는다.

실제 수집에는 기존의 명시적 위치 기록 동의, 필요한 Android 런타임 권한, 전역 위치/제공자 상태와 기존 개인정보 차단 처리를 적용한다. 오류·차단·권한 철회 후 오래된 표본이 권한을 복구하지 못하도록 현재 중단·신선도 검사를 유지한다. Pleos가 알린 차단을 표준 API로 우회하지 않는다. [Pleos 위치 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)

현재 [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) 11행은 Pleos 위치 권한을 선언하지만 Android fine/coarse 위치 권한은 선언하지 않는다. 제공자 조사만을 위해 권한을 추가하거나 허용할 필요는 없다. 실제 어댑터를 연결할 때 필요한 권한 요청을 구현하되 자동 grant·동의 ON·위치 설정 변경은 하지 않는다.

## 다음 실행 순서와 남는 범위

1. 좌표를 받지 않는 표준 Android 제공자 능력 점검.
2. 지원 결과에 따라 출처가 구분된 어댑터와 정상 권한 요청 흐름 구현, 합성 품질·신선도 검증.
3. 공식 ON_DEVICE 음성 API의 초기화·EOF·취소·시간 제한 검증 계속 진행.
4. 실제 수집이나 발화가 필요한 시점에만 사용자 동의·권한·시험 입력을 요청.

표준 위치 API가 전체 활성 도로 경로와 구간 ETA를 제공하는 것은 아니다. NaviHelper 목적지·경유지를 도로 polyline으로 가장하지 않으며 실제 미래 지역 입력의 제약은 별도로 기록한다. 이번 판단 수정은 미검토 RegionPolicy나 지역 적립 권한을 승인하지 않고, Virtual Console 보류 결정도 변경하지 않는다.
