# Pleos 위치·음성 SDK 개별 검증

후속 상태: `0.8.0-dev.3`의 [STT 자동 검증·종료 순서 수정 기록](./2026-11-06-stt-verification.md)을 함께 읽는다. 아래 표와 BLOCKED는 최초 `dev.2` 실행 당시의 증거로 유지한다.

2026-11-06, Petasos `0.8.0-dev.2`. 최소 앱 다음 단계로 실제 SDK 어댑터와 별도 검증 APK를 추가했다. 현재 화면은 임시 프리뷰이며 디자인을 확정하지 않았다. [구조 설명](./architecture.md)을 참고한다.

## 환경과 범위

- Pleos Connect 2.1.0 / Android 14(API 34), ARM64, `emulator-5554`, Android 사용자 10.
- FusedLocation `1.1.3`, TextToSpeech `3.3.1`, SpeechToText `3.3.1`. 공식 Pleos Maven 저장소는 Pleos 그룹으로 제한했다.
- 음성 모드: 명시적 `ON_DEVICE`, 한국어. 이번 검증에는 프로젝트 비밀키나 서버 인증을 사용하지 않았다. 서버/Hybrid 모드의 이용 권한은 미검증이다.
- 설치 서비스: `ai.pleos.playground.caas` `4.2.10-coral-aosp`, FusedLocation 서비스 `1.0.0`.
- 앱은 FUSED_LOCATION/TTS_SERVICE/STT_SERVICE 사용자 정의 권한을 선언한다. 위치 권한은 이번 에뮬레이터 검증 명령에서 명시적으로 부여했다. 제품용 권한 설명·요청·거부 후 복구 화면은 미구현이다.
- 일반 실행 시 SDK 초기화·위치 수신·재생·마이크 입력이 없다. 각 동작은 별도 테스트 APK를 명령으로 실행해야 시작한다.
- Virtual Console은 연결하지 않았다. 위치 probe는 `adb emu geo fix`로 공개 합성 좌표 두 개를 주입하며 실제 GPS 이동·경로 품질 시험과 구분한다.

## 검증 결과

| 항목 | 결과 | 확인한 것 / 한계 |
| --- | --- | --- |
| 앱·테스트 APK 빌드, lint | PASS | `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` |
| 일반 앱 설치·전면 실행 | PASS | SDK 추가 후에도 프리뷰 정상 표시, 실행 로그에 앱 크래시 없음 |
| 위치 | PASS | 실제 Pleos UPDATE 콜백으로 합성 A→B 좌표 수신, 캐시 응답과 구분 |
| TTS 연결 | PASS | 실제 초기화 성공 콜백 |
| TTS 재생 | PASS | ready → started → completed 수신 |
| TTS 중지 | PASS | started 후 stop 요청 → stopped 수신 |
| STT 연결 | PASS | 실제 초기화 성공 콜백 |
| STT 준비·중지 | BLOCKED | ready까지 수신, 즉시 stop 후 ended 미수신. 상세 아래 |
| TTS 실제 청취 품질 | 미확인 | 콜백 성공과 실제 스피커 출력·청취 품질을 구분 |
| STT 실제 발화·최종 텍스트 | 미실행 | 사용자 발화 준비 확인 후 실행 |
| 전체 제품 A/R/D/N 검수 | 미실행 | Coordinator, 저장, 생성, 주행 UX 등은 아직 미구현 |

실행 증거는 `artifacts/milestone-2/`의 `build.log`, 각 probe TXT/JSON과 `launch/`에 있다. JSON에 앱/테스트 APK SHA-256·에뮬레이터 fingerprint·실행 시각·결과를 남긴다. 원래 최소 앱 증거 `artifacts/milestone-1/`는 유지했다.

### STT 종료 확인 이슈

`stt-ready`는 초기화 후 `request()`를 호출하고 `Ready`를 받자마자 `stop()`을 호출했다. 최초 3초 관찰에서 `Ended`가 오지 않았으며, 진단 관찰 시간을 10초로 늘린 재시험에서도 동일했다(최종 실행 총 10,671 ms). 제품 명세의 request 후 Ready 제한 3초는 유지했다. SDK 문서는 중지 후 `onEndedRecognition()` 콜백을 설명한다. 어댑터의 취소 상태는 발화 결과를 버리지만 `Ended`를 차단하지 않으므로, 확인되지 않은 종료 이벤트를 임의로 만들어 PASS 처리하지 않았다. [공식 시작·중지 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop), [이벤트 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners).

타임아웃 시 `close()`로 리스너 제거·SDK 해제를 시도하며 호스트 스크립트가 앱 프로세스를 종료한다. 이것은 SDK의 마이크 종료 확인 콜백을 대체하지 않는다. 실제 서비스 종료 상태·준비 직후 중지의 동작·호스트 마이크 연결을 구분해 확인해야 한다. 해결 전에는 전체 청취↔대화 전환을 검증 완료로 간주하지 않는다.

## 구현과 남은 검증

위치는 가용성·권한·구독·캐시·실시간 업데이트를 구분한다. 원래 timestamp/confidence를 보존하고 수신 시각도 기록하되, SDK 시간축·confidence 단위 의미를 추정해 신선도나 정확도로 사용하지 않는다. 세션을 종료하면 늦은 콜백을 버린다. 구독 해제·release의 동기 예외는 probe 실패로 보고한다.

음성은 초기화에 제한 시간을 두고, 연결만으로 출력이나 마이크를 시작하지 않는다. TTS는 Audio Focus를 얻은 후 출력하고 포커스를 잃으면 중지한다. 이 포커스 상실 경로의 실제 경쟁 앱 시험은 아직 하지 않았다. SDK의 요청 식별자 부재 때문에 현재 인스턴스당 요청 하나만 사용한다. 음성 `close()`의 해제는 best effort이며 모든 서비스 상태에서 성공을 독립적으로 검증한 것은 아니다.

검증 코드가 발화 원문·원음·실제 좌표를 파일이나 앱 로그에 남기지 않도록 했다. STT 결과는 메모리에서 비어 있지 않은 최종 결과의 존재만 확인한다. SDK/차량 서비스 내부 로깅 정책은 별도 점검 대상이며, 발화 probe에서 전체 logcat을 수집하지 않는다. SDK가 병합한 INTERNET 등의 Manifest 선언이 있으므로 이 빌드를 네트워크 권한 없는 앱으로 표현하지 않는다.

현재 APK는 ARM64 native 라이브러리 두 개만 포함한다. Lint가 APK에서 제외된 JNA 5.13.0 x86_64까지 검사하는 문제는 해당 파일 경로에만 예외를 뒀다. Automotive 필수 앱이므로 ChromeOS ABI 권고도 Gradle 파일에 한정해 제외했다. ARM64 정렬 검사와 다른 lint 검사는 유지한다. 도구의 SDK XML 버전 경고는 남아 있지만 빌드/실행 실패는 없었다.

다음 순서:

1. STT 종료 확인 및 실제 마이크 발화/최종 결과를 검증한다. 필요한 경우 호스트 마이크 연결을 점검한다. TTS는 실제 청취도 확인한다.
2. 단일 PlaybackCoordinator의 상태 전이·요청 수명·타임아웃을 화면과 분리해 구현한다. v0.8의 이미 결정한 정책은 유지한다.
3. 본 청취·대화 화면 연결 전에 디자인 방향을 논의한다. 현재 색상과 카드 구성을 최종 디자인으로 확대하지 않는다.
4. 이야기 한 편의 흐름에 저장·복구·생성 서버를 순차 연결한다. CarUxRestrictions/차량 상태 검증 시 Virtual Console 연결을 다시 다룬다.
