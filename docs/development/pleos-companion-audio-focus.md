# Pleos 내비와 함께 사용하는 오디오 포커스

확인일: 2026-11-09. 이 문서는 Petasos의 이야기·내부 음악 출력과 다른 앱의 오디오 포커스 경쟁을 다룬다. 내비 화면 호출·목적지·실제 선택 경로 수신은 별도 기능이다.

## 공식 API와 적용 방식

- [Android 오디오 포커스](https://developer.android.com/media/optimize/audio-focus): 출력 직전 요청 결과를 확인하고, 같은 `AudioFocusRequest`로 반환한다. 음성 콘텐츠는 `CONTENT_TYPE_SPEECH`를 사용한다. 일시 상실 후 실제 `AUDIOFOCUS_GAIN`을 관찰하고 사용자 재생 의도를 확인한다.
- [Pleos 오디오 포커스 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development): 플랫폼의 R/E/C 중재와 LOSS·TRANSIENT·CAN_DUCK·GAIN을 따른다. MUSIC/NAVIGATION은 보통 MAY_DUCK 기반 공존을 사용하며, Gleo 등에 따른 일시 상실 시 출력을 중단한다. 앱이 차량의 중재 행렬을 자체적으로 대체하지 않는다.
- [Pleos Suspend 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus): 진입 시 출력·포커스 정리와 상태 저장을 수행한다. 이 문서의 포커스 복구는 실제 Suspend 수신 경로 검증을 대신하지 않는다.

이야기는 길 안내 음성이 아니므로 `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH`를 유지한다. 이야기와 내비 음성이 겹쳐 내용을 놓치지 않도록 `setWillPauseWhenDucked(true)`로 이야기 출력을 중단하고, 복귀 시 중단된 문단부터 새 요청으로 재생한다. 이는 Petasos의 음성 콘텐츠에 적용한 정책이며 차량 전체의 concurrent 재생 정책 변경이 아니다.

내부 음악은 `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`, `setWillPauseWhenDucked(false)`로 정상적인 시스템 duck을 허용한다. 플랫폼이 CAN_DUCK 콜백을 전달하는 경우에만 해당 플레이어 음량을 0.2로 줄이고 GAIN 때 1로 복원한다. 실제 감쇄량·안내 가청성은 청취 검증 대상이다.

## 구현 계층

| 계층 | 책임 |
| --- | --- |
| `platform/android/audio/AndroidOutputAudioFocus` | 출력 하나의 실제 AudioManager 요청을 소유한다. 일시 상실 시 등록을 유지하고, 실제 GAIN만 복귀 이벤트로 전달한다. 영구 LOSS·명시적 취소 후 늦은 이벤트를 차단한다. |
| `PleosSpeechOutput` | 상실 직후 SDK stop을 먼저 제출한다. 실제 SDK onStop/onDone과 포커스 상태를 구분한다. |
| `PleosRuntimeSpeech` | 물리적 STOPPED를 확인한 뒤 SDK 연결을 해제한다. 복귀 대기 중에는 텍스트·SDK 출력 대신 정확한 이전 요청의 포커스 등록만 유지한다. |
| `Media3RuntimeMusic` | 실제 플레이어 종료와 위치를 확인한다. ExoPlayer의 자체 포커스 관리는 끄고, Runtime이 승인한 새 시도만 재생한다. 이전 플레이어가 GAIN을 받고 독자적으로 재생하지 않는다. |
| `PlaybackRuntime` | 일시 상실한 `OperationToken`, 세션, 취소 epoch, 시작 fence를 묶어 복귀 권한을 보관한다. 실제 GAIN이 동일 권한에 속할 때만 FOCUS_TRANSIENT를 해제한다. |
| `PlaybackCoordinator`·기존 resume planner | 기존 재생 의도·출력 종료 확인·재생 체크포인트·현재 콘텐츠 조건을 적용해 새 출력 시도를 결정한다. |

`RuntimeSpeech`와 `RuntimeMusic`의 `cancelFocusRecovery()`는 복귀 대기 등록만 취소한다. 이 호출이나 포커스 반환은 물리적인 출력 종료 증거가 아니다. 자막 래퍼도 같은 취소 요청을 그대로 전달한다.

## 상태 경계

1. TRANSIENT 또는 이야기 CAN_DUCK을 받으면 출력 중지를 요청하고 FOCUS_TRANSIENT로 막는다. 재생 의도와 체크포인트는 유지한다.
2. SDK의 실제 STOPPED 또는 ExoPlayer의 확인된 release 전에는 다음 출력이 시작할 수 없다. 정지 관찰 타임아웃은 종료 확인으로 취급하지 않는다.
3. 일시 상실로 멈춘 출력의 SDK·플레이어는 정리하되 AudioManager 등록은 유지한다. GAIN을 얻으려고 반복 요청하거나 타이머로 복귀시키지 않는다.
4. 실제 GAIN이 오면 Runtime이 동일 세션·시도·epoch·fence인지 확인한다. 일치하면 일시 포커스 차단을 해제하고 기존 planner를 거쳐 새 SDK·플레이어 요청을 만든다. GAIN이 STOPPED보다 먼저 도착해도 물리적 종료를 기다린다.
5. 사용자 Stop은 재생 의도를 지우고 복귀 등록을 취소한다. Stop이 worker 큐에서 처리되기 전이라도 ingress fence가 오래된 GAIN을 차단한다.
6. Suspend·세션 종료·전원 오디오 차단·저장소 실패는 대기 중인 복귀 권한을 폐기한다. 다른 blocker는 그대로 남는다. 그 blocker를 별도로 해제하더라도 이전 GAIN을 재사용하지 않고 필요한 새 포커스를 요청한다.
7. 영구 LOSS 또는 최초 요청 거부는 자동 복귀하지 않는다. 출력 종료가 확인되고 다른 blocker가 없을 때, 사용자의 새 Play만 영구 포커스 차단을 해제하고 포커스를 다시 요청할 수 있다.
8. GAIN 이후 STOPPED 전에 영구 LOSS가 다시 오면 계획된 복귀도 취소한다. 이전 출력의 늦은 GAIN은 다음 출력의 일시 상실을 해제할 수 없다.

GAIN으로 이야기 완료·스탬프·사용자 발화를 합성하지 않는다. 중단된 문단은 완료로 기록하지 않으며, 음악은 확인된 정지 위치를 사용한다. 마이크 개인정보 제약은 변경하지 않는다.

## 검증 범위와 현재 상태

구현·회귀 테스트와 통합 Gradle·에뮬레이터 검증을 완료했다. 최종 수치와 artifact는 아래 dev.35 확인 결과 및 검증 집계를 따른다.

- `PlaybackRuntimeTest`: 일시 상실 → STOPPED → GAIN 복귀, GAIN 선행 시 STOPPED 대기, 중복·오래된 GAIN, Stop ingress 경합, 전원·종료 차단, 영구 LOSS, GAIN 이후 영구 LOSS, 명시적 재시도와 다른 blocker 보존.
- `RuntimeMusicTest`: 확인된 정지 위치에서 새 시도로 복귀, 플레이어 정리 뒤 GAIN, Stop 뒤 복귀 금지, 포커스 거부 후 명시적 다른 곡 재시도.
- `AudioFocusRecoveryProbe` / `audio-focus-recovery`: 실제 AudioManager에 합성 MEDIA 및 NAVIGATION 속성 요청을 보내 TRANSIENT·GAIN·영구 LOSS 콜백과 취소 뒤 GAIN 차단을 확인한다. player·TTS·마이크·실제 경로를 사용하지 않는다. `outputFinished()` 경계는 이 probe에서 합성하므로 물리적 스피커 종료 증거가 아니다.
- 기존 실제 TTS·음악 출력 probe를 함께 실행해 출력·정지 경로의 호환성을 확인한다.

위 검사만으로 실제 Pleos 내비 안내와 이야기의 청취 품질, 안내 첫 음절, 모든 차량의 duck 동작, 통화·Gleo·실차 Suspend 조합을 통과했다고 주장하지 않는다. 해당 경쟁 앱과 실제 음성 출력은 별도 검증해야 한다.

## dev.35 확인 결과

최종 제품 JVM1,086/87·빌드/Lint PASS. 실제 AudioManager 합성 경쟁 probe2,435ms, 제품 공개TTS5,960ms, Media3 합성 음원/Room6,682ms PASS. [집계](../../artifacts/milestone-35/verification.json). 실제 내비 안내와 TTS 동시 청취·차량별 음질/duck/Suspend는 별도 미검증이다.
