# STT 자동 검증과 종료 순서 수정

후속 변경: 2026-11-07 `dev.4`에서 STT Ended 뒤 명시적 stop/close를 생략하던 조건을 수정했다. 아래는 dev.3 당시 실행 기록이며, 최신 코드는 [공식 API 감사와 재검증](./2026-11-07-official-api-audit.md)을 따른다. 과거 실행 결과를 소급 변경하지 않는다.

기준: 2026-11-06, Petasos `0.8.0-dev.3`, Pleos Connect 2.1.0 / API 34 ARM64, SpeechToText SDK 3.3.1 ON_DEVICE. [이전 개별 SDK 검증](./2026-11-06-sdk-probes.md)의 STT 이슈를 후속 조사했다. 사용자는 실제 발화 테스트를 나중에 하기로 했으므로, 호스트 마이크를 활성화하거나 직접 발화 시험을 진행하지 않았다.

## 결과

| 검사 | 결과 | 실제로 확인한 범위 |
| --- | --- | --- |
| 합성 PCM 인식 `stt-pcm` | PASS | 한국어 합성 음성을 실제 Pleos 엔진으로 인식. 최종 결과가 예상 문장과 일치하고 EPD 종료 이벤트 수신 |
| 무발화 취소 `stt-cancel` | PASS | 입력 준비 시 활성 녹음 구성 확인 → stop → 동일 녹음 세션의 종료, 서비스 연결 해제 |
| 입력 중 해제 `stt-close` | PASS (3회) | close가 먼저 stop을 요청한 후 지연 release. 프로세스가 살아 있는 동안 녹음·서비스 연결 해제 확인 |
| 앱·테스트 APK·lint | PASS | `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` |
| 호스트 검증 파서 회귀 검사 | PASS | 12개. PID 종료/변경, 세션 불일치, 재시작, 잔여 연결, 알 수 없는 덤프 등을 성공으로 오인하지 않음 |
| 사람의 실제 마이크 발화 | 보류 | 사용자 요청. 호스트 입력·소음·개인별 인식 품질은 아직 검증하지 않음 |

결과는 `artifacts/milestone-2/stt-pcm.*`, `stt-cancel.*`, `stt-close.*`, `stt-build.log`에 있다. 해제 반복 3회의 증거는 `stt-close-repeats/`에 보존했다. 각 JSON에 해당 앱·테스트 APK 해시가 들어 있다. 초기 실패는 `stt-before-fix/`에 보존했다. 앱의 일반 화면은 여전히 임시 프리뷰이며 이 테스트들을 자동으로 실행하지 않는다.

## 원인과 수정

### Ended는 stop 완료 응답이 아님

SDK 3.3.1의 `SttCallback.onResponse` 바이트코드를 확인하면 `onEndedRecognition()`은 `UpdateEpdState`의 EPD_OFF를 전달한다. 발화가 없는 Ready 직후 취소에는 이 이벤트가 없을 수 있다. 기존 `stt-ready` 검사는 이 이벤트를 중지 응답으로 요구했으므로 BLOCKED였다. [공식 시작·중지 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop)의 포괄적 설명과 실제 콜백 경로를 구분했다.

기존 결과를 PASS로 바꾸거나 가짜 Ended를 만들지 않았다. 별도 `stt-cancel` 검사로 활성 녹음 구성과 실제 stop 기록, 서비스 연결 해제를 검증했다. 제품 코드에서도 `Ended`를 모든 취소의 완료 신호로 사용하지 않도록 인터페이스 주석을 수정했다.

### stop 직후 release의 재연결 경쟁 조건

SDK의 `stop()`은 내부 코루틴에 작업을 넣고 반환하지만, `release()`는 즉시 binder를 해제한다. 이전 `close()`는 두 메서드를 연속 호출했다. 뒤늦게 실행된 stop 작업이 서비스를 다시 bind하는 경로가 있으며, 수정 전 실제 앱 PID 5786이 살아 있는 상태에서 2초 후에도 `Client AppBindRecord`가 남는 것을 재현했다.

현재 어댑터는 close 즉시 새 작업·콜백을 차단하고, stop 요청 시각부터 **1초 동안 SDK 연결을 유지한 뒤 main Handler에서 release**한다. 메인 스레드를 sleep으로 막지 않는다. 이미 stop을 요청했다면 남은 시간만 기다리며, 정상 Ended 후에는 불필요한 stop을 다시 호출하지 않는다. 반복되는 SDK EPD_ON은 앱에 Started 한 번으로 전달한다.

1초는 SDK 3.3.1에서 관측한 경쟁 조건을 피하기 위한 제한된 우회책이다. 공개 API에 stop 완료 확인 수단이 없으므로, 그 시간이 지났다는 사실이나 `close()` 반환을 마이크 종료 보장으로 해석하지 않는다. SDK·에뮬레이터 변경 시 재검증하며, 엄격한 입력/출력 상호 배제의 제품 검수는 별도로 유지한다. v0.8 정책을 변경하지 않았다.

## 검증이 성공을 판정하는 방법

취소/해제 검사는 Ready에서 활성 녹음의 `(recordingId, uid, sessionId)`를 확인한다. 앱은 고유 토큰의 호스트 확인 신호를 기다린 후 stop/close를 실행한다. 호스트는 close 후 약 1.25/1.75/2.25초에 녹음과 서비스 연결을 검사하며, 각 검사 전후에 **같은 Petasos PID가 살아 있는지** 확인한다. 따라서 프로세스 종료로 자원이 정리된 것을 정상 close로 오인하지 않는다.

Android가 최초 녹음 기록을 `start` 대신 `update`로 남기는 경우가 있어, update 자체를 시작 증거로 쓰지 않는다. Ready 시점의 활성 구성과 그 뒤 동일 세션의 stop 순서를 요구한다. 다른 녹음 source, 예상 밖 세션, 해석할 수 없는 덤프, 남은 연결은 실패 처리한다. 소스·세션 번호 등의 메타데이터만 남기고 오디오나 발화 원문을 기록하지 않는다.

PCM 검사는 프로덕션 마이크 어댑터와 분리해 SDK의 `sendAudio()`/`completeAudioSend()`를 직접 사용한다. `request()`를 호출하지 않는다. CaaS 녹음 이벤트가 새로 생기지 않는지도 검사한다. 이 결과는 실제 인식 엔진·SDK 콜백의 검증이며, 사람의 마이크 발화 시험을 대체하지 않는다. [공식 PCM 전송 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data).

최종 PCM 실행에서 EPD 종료는 시작 기준 4,051 ms, 최종 텍스트는 4,066 ms에 수신됐다. 최종 결과가 Ended 뒤 15 ms에 도착해 명세의 500 ms 유예 안에 들어왔다. 이 한 표본으로 모든 발화의 시간 분포를 보장하지 않는다. SDK Started는 8번 반복되어 앱 어댑터에 중복 방지를 추가했다. 직접 PCM 경로에는 Ready가 없었으며, 마이크 request의 Ready 경로와 구분한다.

## 재실행과 합성 입력

```sh
./scripts/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
python3 -m unittest discover -s scripts/tests -v
python3 scripts/probe-pleos.py stt-pcm
python3 scripts/probe-pleos.py stt-cancel
python3 scripts/probe-pleos.py stt-close
```

`stt-cancel`/`stt-close`는 게스트 STT 입력을 잠깐 열어 수명을 확인한다. 호스트 마이크 활성화 명령은 실행하지 않는다. 실제 발화는 사용자가 준비된 별도 시점에 `stt-listen`으로 검증한다. `stt-ready`는 과거의 Ended 가정을 재현하는 진단용으로 남겼으며 일반 통과 검사에 사용하지 않는다.

합성 fixture는 테스트 APK의 `app/src/androidTest/assets/stt_fixture.pcm`에만 포함된다. 사람의 음성을 저장한 파일이 아니다.

- 생성: macOS `say`, Yuna, rate 160, “지금 지나가는 지역의 이야기를 알려 주세요.”
- 변환: `afconvert`로 16 kHz / signed LE16 / mono, 앞 0.5초·뒤 1초 무음 추가.
- 길이: 144,626 bytes / 약 4.52초.
- SHA-256: `86a9064d6ee7da10c2dddd8c64934d1f95d23638b5dfbd7fb3209331df320c13`.
- 비교: 공백·문장부호를 제거한 예상 문장과 최종 인식 결과의 정확한 일치. 원문을 테스트 로그에 출력하지 않는다.

다음에는 UI와 독립적인 PlaybackCoordinator의 상태 전이·타임아웃을 구현할 수 있다. 사람의 실제 발화, 포커스 경쟁, 부하/중단 환경의 입력 종료와 출력 전환 검수는 별도 항목으로 남긴다. UI 디자인 논의와 Virtual Console 보류 결정은 유지한다.
