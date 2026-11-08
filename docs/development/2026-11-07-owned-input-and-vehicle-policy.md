# 앱 소유 마이크 입력과 차량 정책 연결

작성일: 2026-11-07. 이 문서는 구현 경계와 공식 근거를 기록한다. 실제 빌드·기기 결과는 해당 마일스톤의 실행 기록을 따른다. 사람 발화 시험이나 차량 Suspend 시험을 수행한 것으로 간주하지 않는다.

## 마이크 종료 확인 경로

기존 `PleosSpeechInput`의 `SpeechToText.request()` 경로는 SDK가 마이크를 소유한다. SDK 3.3.1의 `onEndedRecognition()`은 EPD_OFF이며, 무발화 취소·`stop()` 완료 응답이 아니다. 이 기존 진단용 어댑터의 기록은 유지한다.

런타임의 새 `PleosPcmSpeechInput`은 Android `AudioRecord`를 직접 소유한다. 사용자의 한 번의 명시적 입력 요청에 16 kHz / PCM16 / mono 캡처를 한 번 열고, Pleos ON_DEVICE의 공개 `sendAudio()`로 짧은 메모리 버퍼를 전달한다. SDK의 `request()`는 호출하지 않는다. 이 경로는 [Pleos 외부 PCM 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)를 따른다. 해당 공식 가이드는 분할 전송과 마지막 `completeAudioSend()`를 명시한다.

입력 종료 증거는 같은 `AudioRecord` 인스턴스에 대해 **`stop()` 정상 반환 → `RECORDSTATE_STOPPED` 확인 → `release()` 정상 반환**을 모두 만족했을 때만 생성한다. 하나라도 실패하면 기존 `INPUT_STOP_UNCONFIRMED` 차단을 유지한다. 녹음기를 생성하기 전 취소되었으면 해당 요청이 녹음을 시작하지 않았다는 별도 사실을 사용한다. [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)

`PcmCaptureSession`은 1,600개 sample scratch buffer 하나를 사용하며 각 read 후 지운다. 정상 100 ms 간격으로 최대 3,200 bytes를 전달한다. `PcmPollSchedule`은 monotonic 절대 시각으로 다음 읽기를 계산하여 처리 비용이 매 tick 누적되지 않게 한다. 지연 보정에도 최소 20 ms를 양보하고, 긴 정체 뒤에는 모든 누락 tick을 재생하지 않고 deadline을 재설정한다. 이는 누락 원음을 복원하거나 새로운 오디오 큐를 만드는 기능이 아니다. 각 전송 버퍼는 별도 LE16 배열이며 앱은 전송 뒤 참조를 보관하거나 파일로 저장하지 않는다. SDK 내부에서 버퍼를 참조할 수 있어 전송 직후 배열을 덮어쓰지 않는다. 원음을 서버로 전송하는 경로나 전체 발화를 축적하는 큐는 없다. Android PCM16의 `short[]` non-blocking read를 사용한다.

입력 `Ready`는 SDK의 외부 PCM 콜백이 아니라 실제 소유 녹음기의 recording 상태 확인을 뜻한다. STT `Started`는 SDK의 최초 EPD_ON을 한 번 전달한다. EPD 종료 또는 최종 텍스트가 오면 녹음을 즉시 중지하고 `completeAudioSend()`를 호출한다. EPD 뒤 최종 텍스트 수신 가능성을 위해 SDK 리스너는 명세의 500 ms 유예 동안 유지한다. 종료 증거를 먼저 보관하고 Coordinator의 명시적 `stopInput`에 응답하므로, 최종 텍스트/종료 이벤트 역순에서도 기존 저장→입력 종료→생성 순서를 유지한다. 명시적 취소 이후에는 최종 텍스트를 전달하지 않는다.

`RECORD_AUDIO` 런타임 권한이 필요하다. 연결·앱 실행·서비스 재연결 자체로 권한을 요청하거나 녹음을 시작하지 않는다. 권한 거부, 읽기 오류, 녹음 시작 실패에는 자동 재시작하지 않는다. 입력 시간 제한은 기존 Coordinator가 적용하며 어댑터에도 65초의 추가 캡처 상한이 있다. SDK stop/release 경쟁 조건을 위한 1초 drain은 계속 별개이며 마이크 종료 증거로 사용하지 않는다.

검증 도구:

- `PcmCaptureSessionTest`: stop/state/release 각 실패, 부분 시작 실패, 캡처 전 취소, 권한 거부, PCM16 변환, scratch 정리, 중복 종료와 재시작 차단.
- `PcmPollScheduleTest`: 반복 처리 비용의 누적 drift 차단, 지연 보정 중 최소 yield, 긴 정체 후 누락 tick 폭주 차단, monotonic clock 역행 거부.
- `stt-owned-pcm`: 새 어댑터·실제 Pleos 인식 엔진에 합성 녹음기를 주입한다. 실제 마이크를 열지 않으며, 결과와 종료 증거·늦은 결과 차단을 검사한다.
- `stt-owned-stream`: 같은 합성 음성 뒤 무음 PCM을 계속 공급하며 입력 EOF 없이 SDK의 Started/Ended/Final 수신 여부를 검사한다. Ready→Started 5초 및 Ended→Final 500 ms 초과를 별도 메타데이터로 기록한다. 어댑터 인식 성공과 제품 시간 제한 준수를 구분한다.
- `stt-owned-listen`: 같은 어댑터의 실제 `AudioRecord`를 사용하는 명시적 사람 발화 시험이다. 아래 SDK 원문 로그 차단 문제가 해소되고 사용자 준비를 확인한 뒤에만 실행할 수 있다. 현재는 사람 발화 준비를 요청하지 않고 보류한다. probe 자체는 원문 대신 최종 결과 존재·길이와 종료 증거만 기록하지만 SDK 내부 로그까지 제거하지는 않는다.

합성 녹음기 검증은 실제 하드웨어 녹음 종료나 사람 발화 품질 검증을 대신하지 않는다.

최초 새 어댑터의 유한 fixture는 파일 끝에서 0개 sample만 반환하여 `completeAudioSend()`를 보내지 않는 결함이 있었다. 유한 입력의 EOF를 별도로 전달해 녹음기 종료→`completeAudioSend()`를 먼저 실행하고 최종 결과는 계속 받을 수 있도록 수정했다. 실제 `AudioRecord`는 EOF를 보고하지 않으며, 단순 무음이나 0개 read를 입력 종료로 추정하지 않는다. 따라서 유한 PCM 성공만으로 live microphone 종료 동작을 확정하지 않는다. SDK의 공개 PCM 전송을 지원한다는 사실과 제품의 실시간 대화 시간 제한을 충족한다는 사실은 별도 검증 대상이다.

## 최신 합성 PCM 실행 결과

2026-11-07 13:39 UTC에 Pleos `release_connect_v2.1.0` 에뮬레이터에서 실행한 두 probe는 모두 `PASS`였다. 이 판정은 실제 SDK의 fixture 인식, 주입한 합성 녹음기 종료 확인, 종료 후 늦은 결과 차단에 대한 것이다. 제품의 시간 제한 충족 여부는 아래처럼 별도로 판단한다. 두 실행 모두 같은 앱·테스트 APK를 사용했으며 정확한 SHA-256과 실행 범위는 각 JSON에 있다.

| 입력 경로 | probe 결과 | Ready→Started | Ended→Final | 제품 시간 제한 관찰 |
| --- | --- | --- | --- | --- |
| 유한 PCM, EOF에서 종료·완료 요청 | [PASS 기록](../../artifacts/milestone-9/stt-owned-pcm-verified/stt-owned-pcm.json) · [이벤트 시각](../../artifacts/milestone-9/stt-owned-pcm-verified/stt-owned-pcm.txt) | 21,986 ms | 87 ms | 첫 발화 5초 제한 **실패**, Final 500 ms 이내. 제품 대화 성공으로 인정하지 않는다. |
| EOF 없는 PCM 스트림 | [PASS 기록](../../artifacts/milestone-9/stt-owned-stream-verified/stt-owned-stream.json) · [이벤트 시각](../../artifacts/milestone-9/stt-owned-stream-verified/stt-owned-stream.txt) | 2,306 ms | 164 ms | 이 한 번의 실행에서 두 제한 이내. 반복 안정성이나 실제 마이크 성능을 보장하지 않는다. |

continuous 소스는 fixture 뒤 무음을 계속 공급하도록 구성되며 EOF를 반환하지 않는다. 이번 실행에서는 SDK의 Started와 Ended를 먼저 받았고, Ended 처리에서 캡처 종료와 `completeAudioSend()`를 수행한 뒤 Final을 받았다. 이는 완료 요청 전 EPD 이벤트를 받을 수 있다는 관찰이며, Final도 완료 요청 전에 수신했다는 증거는 아니다. 또한 기록된 공급량은 72,000 samples로 유한 fixture 전체 72,313 samples보다 작아, 이번 실행이 fixture 이후의 추가 무음까지 공급했다고 주장하지 않는다.

두 실행은 실제 `AudioRecord`를 생성하지 않았다. `owned_input_stop_confirmed`는 주입한 `FixtureRecorder`의 stop/state/release 확인이며 물리 마이크 종료 증거가 아니다. 사람 발화, 실제 입력 장치, 장시간 연속 캡처, 차량 Suspend 복귀는 이 결과에 포함되지 않는다. 기존 첫 발화 5초 및 Ended 뒤 Final 500 ms 제한은 변경하지 않았고, 합성 probe의 `PASS`는 아래 SDK 원문 로그 문제에 따른 개인 입력·답변 출력 제한을 해제할 근거가 되지 않는다.

## SDK 원문 로그 조사와 개인 대화 활성화 사전조건

2026-11-07 설치에 사용하는 **SpeechToText/TextToSpeech 3.3.1 AAR의 정적 바이트코드 조사**에서 원문을 Android 로그 API로 전달하는 경로를 확인했다. 사람 발화·개인 답변을 생성하거나 읽어 확인한 결과가 아니다. 조사에는 `javap -p -c`, JAR 클래스 목록, SHA-256만 사용했으며 실제 발화·답변·토큰·logcat 원문은 수집하지 않았다.

| 확인 대상 | 확인한 클래스·메서드와 동작 |
| --- | --- |
| STT 인식 응답 | `ai.pleos.playground.stt.service.SttCallback.onResponse(String type, String data)`는 `UpdateRms` 외 이벤트에서 `type`과 **전체 data**를 이어 붙여 `ai.pleos.playground.stt.utils.Log.a(String,String)`에 전달한다. 해당 메서드 bytecode offset 21–53에 로그 조립·호출이 있다. `UpdateSttResult`는 중간 텍스트, `UpdateSttCompleted`는 최종 결과 객체의 원본 data가 이 경로를 지난다. |
| STT 로그 sink | `ai.pleos.playground.stt.utils.Log.a(String,String)`의 offset 43은 `android.util.Log.d(String,String)` 호출이다. 이 메서드에는 debug 설정·사용자 동의·원문 제거 분기가 없다. |
| TTS 공개 진입점 | `ai.pleos.playground.tts.TextToSpeech.speak(String,boolean)` → `TtsServiceManager.a(String,boolean,String)` → 선택된 TTS factory로 텍스트를 전달한다. `TtsFactory.a(Mode)`와 `TtsFactory$WhenMappings`를 함께 확인하여 ON_DEVICE가 `OnDeviceStreamTts`를 선택함을 확인했다. |
| TTS 발화 원문 | `ai.pleos.playground.tts.factory.OnDeviceStreamTts$doSpeak$1.invokeSuspend(Object)`는 offset 78–89에서 전달 텍스트를 StringBuilder에 누적한다. `isFinal=true`이면 offset 100–154에서 **누적 전체 텍스트**를 로그 메시지에 포함해 `tts.utils.Log.a`를 호출한 뒤 builder를 비운다. `StreamTts$doSpeak$1.invokeSuspend(Object)`에도 동일 경로가 있다. 이는 응답 callback의 내용과 무관하게, 발화 요청 자체에서 원문이 로그로 전달되는 직접 근거다. |
| TTS 응답 data | `ai.pleos.playground.tts.service.TtsCallback.onResponse(String,String)`도 `UpdateRms` 외 이벤트의 전체 `data`를 offset 21–54에서 `tts.utils.Log.a`로 전달한다. 다만 각 응답 data가 발화 원문을 포함하는지는 이 클라이언트 AAR만으로 확정하지 않는다. |
| TTS 로그 sink | `ai.pleos.playground.tts.utils.Log.a(String,String)`도 offset 43에서 조건 없이 `android.util.Log.d(String,String)`를 호출한다. |

앱의 `PleosSpeechOutput`은 `Mode.ON_DEVICE`와 `speak(text, true)`를 사용한다. 따라서 현재 호출 방식은 확인한 전체 텍스트 로그 분기에 해당한다. 앱 자체가 진단 로그에서 텍스트를 생략하거나 probe 수집기가 메타데이터만 저장하는 것은 **SDK 내부의 로그 호출 제거와 다르다**. 이 조사로 OS의 로그 보관 기간·접근 주체·서비스 프로세스의 추가 로그까지 확정하지는 않는다.

확인한 원본 아티팩트의 SHA-256은 다음과 같다. Gradle transformed `classes.jar`와 원본 AAR 안의 `classes.jar` 해시가 동일함도 확인했다.

| 아티팩트 | SHA-256 |
| --- | --- |
| `ai.pleos.playground:SpeechToText:3.3.1` AAR | `35109ad13400a7fa103cca9fabbf9b785e63a6a98b17a0698a1aef51458d37bf` |
| 위 AAR의 `classes.jar` | `260dfd339ed9ed2b7718da7dc9722b2a60b933b41d4b4c9ccaa0016e7bf0b369` |
| `ai.pleos.playground:TextToSpeech:3.3.1` AAR | `bfbfb58376c69604afad3389878980461971683f0c51f619d03ab74b5e6a0375` |
| 위 AAR의 `classes.jar` | `17ed98d62aaeac6b7e35e988e972294b98f3b1c72bca3fad1cffab323ee78b4a` |

원본은 프로젝트 `.gradle-user-home/caches/modules-2/files-2.1/ai.pleos.playground/` 아래 해당 SDK/3.3.1 경로에서 읽었다. 공개 선언과 공식 문서에서 이 로그를 끄는 지원 설정은 확인하지 못했다. 공식 릴리즈 노트도 STT/TTS `utils.Log`의 **공개 API 노출 제거**를 명시한다. 이는 내부 로그 실행 제거를 뜻하지 않는다. [공식 SDK 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)

개인 대화 capability 활성화 판단에는 마이크 종료 증거 외에 **STT 인식 원문과 TTS 답변 원문의 SDK 로그 경로 해소**가 필요하다. 지원되는 로그 비활성화 설정 또는 수정 SDK를 확보하고 실제 설치 산출물에서 확인하기 전에는 사람 마이크 시험과 개인 답변 TTS를 완료·허용으로 판단하지 않는다. 임의 `isFinal=false`, 리플렉션, 숨겨진 SDK API, probe 로그 필터로 이 조건을 우회하지 않는다. 합성·비개인 fixture의 인식/제어 검증은 별도 범위로 유지한다.

별도로, 공식 PCM 문서와 현재 AAR에는 `completeAudioSend()` 이전 EPD_ON/OFF·Final 수신이나 5초/500 ms 지연 상한의 보장이 없다. AAR의 `sendAudio`는 `IOnDeviceStt.send`, 완료는 `requestCompleteSendBuffer("DEFAULT", package)`로 전달되며 실제 인식 서비스의 flush 정책은 포함되어 있지 않다. 위 continuous 실행은 완료 요청 전 EPD 수신 사례를 보여주므로 batch 전용이라고 단정하지 않는다. 동시에 한 번의 관찰을 지연 보장으로 확대하지 않으며, 제품의 첫 발화 5초·Ended 뒤 Final 500 ms를 임의로 늘리거나 이벤트를 늦춰 숨기지 않는다. [PCM 전송](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data), [결과 리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners)

## 차량 UX 및 컴포넌트 전원 정책

`CarPolicySource`는 공개 `Car.createCar` 생명주기와 `CarUxRestrictionsManager`를 연결한다. 현재 UX 제한을 먼저 반영하고 listener를 등록한 뒤 다시 읽어 조회/구독 사이의 변경을 반영한다. 변경 콜백에서도 최신 값을 재조회한다. `activeRestrictions` 전체와 최대 문자열 길이·항목 개수·깊이를 전달하므로 앞으로 화면별 정책을 적용할 수 있다. 현재 프리뷰 허용 조건은 연결된 실제 값에서 DO 요구가 없고 active flags가 baseline인 경우다. [Pleos 주행 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide), [Android CarUxRestrictionsManager](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager)

초기 연결 전, 연결 끊김, 조회/리스너 등록 실패, 해제는 모두 `available=false`로 전달한다. 미확인을 주차 상태로 치환하거나 차량 속도만으로 주행 허용을 결정하지 않는다. 이전 연결에서 늦게 도착한 콜백은 세대 및 manager identity 검사로 차단한다.

전원은 공개 `CarPowerManager.addPowerPolicyListener()`로 AUDIO와 MICROPHONE을 구독한다. `android.car.permission.READ_CAR_POWER_POLICY`가 필요하다. 변경 알림의 일부 컴포넌트만 보고 나머지를 허용하지 않도록 `getCurrentPowerPolicy()`의 누적 전체 상태를 읽는다. 오디오와 마이크 차단을 각각 전달하고, 현재 상태를 알 수 없으면 둘 다 차단한다. [Android CarPowerManager](https://developer.android.com/reference/android/car/hardware/power/CarPowerManager), [Power policy filter](https://developer.android.com/reference/android/car/hardware/power/CarPowerPolicyFilter)

이 전원 정책 이벤트는 실제 Suspend 진입/종료 콜백이 아니다. 공개 SDK에 없는 `CarPowerManager.setListener` 등의 숨겨진 System API를 사용하지 않는다. 정책 복구는 사용자 정지 의도를 변경하거나 자동으로 마이크를 여는 근거가 아니다. 실제 Suspend/Resume 및 경쟁 앱의 포커스 시험은 별도로 수행해야 한다.
