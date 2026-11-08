# dev.13 — 음성 로그 경로 축소와 합성 검증

작성일: 2026-11-08. 기준은 v0.8 / `0.8.0-dev.13`이다. **앱의 opt-in privacy 빌드에서는 확인한 SDK 로그 경로를 제거했지만, 별도 Pleos Speech 서비스에서 합성 시험 문장이 검출됐다. 제품 `supportsLiveInput()`은 계속 false다.** 사람 발화나 개인 답변 TTS를 보내지 않았다. 빌드·Lint·JVM 348개/27 suite·호스트 108개·기기 회귀 19종과 설치 smoke를 통과했다. 이 기능 검증과 서비스 원문 로그 문제는 구분하며, 기계 판독 집계는 [verification.json](../../artifacts/milestone-12/verification.json)을 따른다.

기능 기준인 [dev.12 기록 설정·이동 종료·결과 조회](./2026-11-08-trip-records.md)와 이전 실제 API 검증은 보존한다. 이번 단계는 마이크 활성화, 최종 UI·데모, Virtual Console, 공개 배포를 포함하지 않는다.

## 변경 범위

`privacy`는 명시적으로 선택하는 별도 빌드 타입이다. 기본 `debug`를 유지하고 `debuggable=false`, R8 minify=true, resource shrink=false로 빌드한다. 기존 application ID·개발 서명·앱 데이터를 유지한다. debug의 localhost 전용 CA 리소스와 manifest를 공유하지만 일반 release의 신뢰 범위는 늘리지 않는다. non-debuggable이므로 `run-as`가 필요한 provision/fixture probe는 지원하지 않으며 실행 도구가 해당 조합을 거부한다.

R8 규칙은 확인한 Android DEBUG sink, 무인자 `Throwable.printStackTrace`, STT INFO/WARN 및 TTS WARN helper를 대상으로 한다. SDK 본체나 Binder 송신·콜백·파서를 no-op으로 바꾸지 않는다. 모델 필드·생성자·generic list·enum SerializedName·Parcelable CREATOR와 실제 음성 제어 참조를 별도로 검사한다. Tink의 미사용 downloader 경로에서 누락된 정확한 9개 타입만 경고 예외를 두었고 일괄 wildcard 억제를 사용하지 않는다. 상세 API·AAR hash·남은 INFO/ERROR 경로는 [클라이언트 감사](./2026-11-08-speech-client-audit.md)에 있다.

`audit-speech-apk.py`는 모든 DEX의 실행 명령을 읽어 문자열 테이블/주석/annotation에 포함된 가짜 호출을 제외한다. 중복 class, 모델 필드의 실제 signature, Parcelable CREATOR의 정확한 타입과 메서드를 확인한다. 이 검사는 전체 프로그램의 정보 흐름 증명이나 기능 실행을 대신하지 않는다.

`audit-speech-logs.py`는 합성 canary와 인식 payload 구조를 메모리에서만 대조한다. 앱·별도 Speech 서비스·알 수 없는 프로세스를 나누고 실제 서비스 바인딩, UID/PID 관측 범위와 positive control을 확인한다. 충돌·관측 공백·서비스 소멸을 성공으로 처리하지 않는다. 원본 logcat/인식 결과/예외 본문을 파일에 남기지 않고 count·시간·고정 코드·hash만 기록한다. privacy smoke도 원본 로그와 스크린샷 대신 제한된 crash count를 기록한다.

## APK와 소스 경계

| 산출물 | SHA-256 | 적용한 검사 |
|---|---|---|
| dev.12 debug 앱 | `e5da5f1aa044003672763415aa9c9e2ba302afee7de9ff9bedea6915ab361b2f` | 정적 비교 baseline; dev.12 결과는 당시 기록 유지 |
| dev.13 privacy 앱 | `454f86b8f424e75ad5a3fd42d81b9de61a631a571ad5dbbc54e3e1dd774f68aa` | 정적 감사, 합성 로그 관측, 회귀 probe와 smoke의 동일 앱 |
| privacy 최초 테스트 APK | `492238fd22673410f9c934863514d2b9280798f3ce5b66ac40a726ac3fc037a3` | 합성 로그 관측 8회, 회귀 17종, 최초 resume-context 실패, package audit |
| privacy 최종 테스트 APK | `0d5741c943e0c459c3529c45e72e83f3b9d0990581a573e1426981b525c20540` | resume-context 재검증과 app-trip-lifecycle |

최초 APK 둘은 `.local/builds/dev13/privacy-initial/`, 최종 둘은 `.local/builds/dev13/privacy-final/`에 보존한다. [package-audit.json](../../artifacts/milestone-12/package-audit.json)은 **최초 테스트 APK**와 dev.12 비교 증거다. 동일 서명·ID, version/debuggable을 제외한 manifest, 권한·컴포넌트, localhost CA/XML, 합성 PCM과 선택한 runner/model 선언 보존을 확인했다. 이를 최종 테스트 APK의 별도 정적 비교 결과로 바꾸어 표시하지 않는다.

`resume-context`의 최초 실패는 제품 저장 제약이 열린 turn을 가진 세션 종료를 거부했기 때문이다. 시험에서 earlier open-turn rejection을 확인한 뒤 합성 `still-open` turn을 동결하도록 **시험 파일 한 곳만** 고쳤다. 제품의 close 제약이나 이전 거부 검사를 완화하지 않았다. [변경 범위](../../artifacts/milestone-12/final-test-only-change.json), [최초 실패](../../artifacts/milestone-12/probes/resume-context-before-fixture-freeze/resume-context-privacy.json), 수정 전/후 소스 snapshot을 보존한다. 최종 `source-sha256.json`은 218개 파일이며 [최종 source 확인](../../artifacts/milestone-12/final-source-check.json)에서 Root가 전부 일치를 확인했다. 이후 개발 소스를 과거 APK와 비교해 당시 검증을 바꾸지 않는다.

## 정적 감사 결과

| 검사 | dev.12 debug | dev.13 privacy |
|---|---:|---:|
| DEX 수 | 13 | 3 |
| class 수 | 19,670 | 20,161 |
| executable invoke 수 | 427,877 | 390,660 |
| 확인한 Speech SDK DEBUG/무인자 stacktrace sink 부재 | false | true |
| STT 원문 INFO helper 호출 | 50 | 0 |
| 모델 보존 / 앱 음성 제어 참조 존재 | true / true | true / true |

[baseline](../../artifacts/milestone-12/static-debug.json), [privacy](../../artifacts/milestone-12/static-privacy.json), [비교](../../artifacts/milestone-12/static-privacy-comparison.json), [helper 호출 목록](../../artifacts/milestone-12/privacy-helper-calls.json)을 함께 읽는다. STT `c`/`d`, TTS `d`의 실행 호출은 모두 0개다. DEBUG helper 호출 자체는 남지만 내부의 해당 Android sink가 없다. TTS INFO와 미사용 UA/AmbientAI/registerApp의 문자열 경로 등은 남아 있으므로 SDK의 모든 기능·모드에 대한 무로그 보장은 아니다.

## 합성 입력 실행과 로그 관측

8회 모두 **기능 PASS / 로그 MATCH_FOUND**다. 사용한 서비스는 `ai.pleos.playground.caas/.service.speech.SpeechService`, version `4.2.10-coral-aosp` / code `4002010`이다. 서비스 APK SHA-256은 `df219cadd7ad8353d9c7cb8080741a7377615f869ac7ff8ef110c07c4faf50ad`이며 [서비스 기록](../../artifacts/milestone-12/service-artifact.json)에 보존한다. 같은 warm 서비스 프로세스에 앱 프로세스만 재시작한 관측이다.

| 합성 STT trial | Ready → Started | Ended → Final |
|---|---:|---:|
| [연속 1](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-first.json) | 2,488 ms | 5 ms |
| [연속 2](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-second.json) | 932 ms | 4 ms |
| [연속 3](../../artifacts/milestone-12/privacy-observer/stt-owned-stream-third.json) | 925 ms | 10 ms |
| [1.5초 선행 무음 1](../../artifacts/milestone-12/privacy-observer/stt-owned-delayed-first.json) | 2,421 ms | 4 ms |
| [1.5초 선행 무음 2](../../artifacts/milestone-12/privacy-observer/stt-owned-delayed-second.json) | 2,441 ms | 5 ms |
| [finite 구성 1](../../artifacts/milestone-12/privacy-observer/stt-owned-pcm-first.json) | 920 ms | 4 ms |

STT 6회 각각에서 서비스 canary 18건과 payload 구조 2건을 관측했다. [TTS speak](../../artifacts/milestone-12/privacy-observer/tts-speak.json)·[TTS stop](../../artifacts/milestone-12/privacy-observer/tts-stop.json)에서는 서비스 canary가 각각 1건이었다. count 종류는 겹칠 수 있어 합산하지 않는다. 8회 모두 앱/unknown process의 canary·payload·stacktrace 일치는 0건, positive control과 PID coverage는 통과했고 crash indicator와 reason code는 없었다. 앱의 한정된 실행 창에서 0건인 것과 별도 서비스의 원문 노출은 별개 결과다.

모든 STT는 payload 72,313 sample 중 65,600 sample을 공급한 뒤 콜백으로 중지했다. 남은 6,713 sample은 무음이고 선행 무음 시험은 앞에 24,000 sample을 붙였다. **finite 구성 시험도 실제 EOF에 도달하지 않았으므로 EOF 완료 경로를 검증하지 않았다.** `completeAudioSend()`의 호출 시각도 측정하지 않아 Final과의 선후 관계를 결론 내리지 않는다. cold 서비스 시작·실제 사람 발화·제품 전체 대화 지연은 미시험이다. 제품 첫 발화 5초와 final 유예 500 ms는 그대로이며 이전 dev.10 지연 관측을 이번 warm 결과로 덮어쓰지 않는다.

## 빌드·회귀 검증 집계

| 검사 | 현재 결과 / 증거 |
|---|---|
| 최초 privacy 빌드 | PASS, 3분 19초; [build.log](../../artifacts/milestone-12/build.log) |
| privacy JVM / Lint | 348개·27 suite, 실패/오류/skip 0; Lint `No issues found.` |
| 최종 privacy 테스트 APK 재빌드 | PASS, 32초; [build-test-fixture-fix.log](../../artifacts/milestone-12/build-test-fixture-fix.log) |
| 기본 debug 앱/테스트 APK·Lint 호환 | PASS, 56초; [build-debug-compatibility.log](../../artifacts/milestone-12/build-debug-compatibility.log); JVM 재실행으로 합산하지 않음 |
| 호스트 Python | 108개 PASS; [host-tests-final.json](../../artifacts/milestone-12/host-tests-final.json) |
| 기능 회귀 probe | 19종 PASS: 최초 테스트 APK 17종 + 최종 테스트 APK의 resume-context/app-trip-lifecycle 2종 |
| privacy 설치·실행 smoke | PASS; [verification.txt](../../artifacts/milestone-12/privacy-smoke/verification.txt), [crash count](../../artifacts/milestone-12/privacy-smoke/crash-counts.json): 33개 record, crash/stacktrace indicator 0 |
| 합성 Speech 관측 | 8회 기능 PASS, 별도 서비스 로그 8회 MATCH_FOUND |
| 서버·참조 계약 | dev.11의 177개/13개 결과 보존, 이번 미재실행 |

19종은 storage, runtime-scripted, runtime-tts, runtime-tts-stop, session-recovery, summary-storage, summary-rights, summary-work, music-runtime, media-session, car-policy-ui, navigation, location-storage, vehicle-preferences, trip-results, trip-lifecycle, story-generation-storage, resume-context, app-trip-lifecycle이다. 각각의 실제 SDK/DB와 합성 입력 범위는 집계 JSON의 개별 `scope`를 따른다. 최종 제품 이동 종료 probe는 공개 이야기의 실제 TTS 시작·정지 ACK, 세션/이동 종료 저장, 같은 host의 다음 Play가 새 이동을 만드는 경계를 확인했다. 마이크·모델 호출·실차 주행은 포함하지 않는다.

호스트 108개 기록은 완료된 도구 실행 결과의 수치·exit code를 전사한 것이다. 원래 stdout은 도구 응답에만 있었고 파일을 소급 생성하지 않았다. `recordedAtUtc`는 기록 생성 시각이며 실행 UTC가 아니다. 이전 `host-tests.log`도 보존한다.

최초 debuggable privacy 빌드는 최적화 차단과 Tink 누락형으로 실패했으며 [당시 로그](../../artifacts/milestone-12/build-debuggable-missing-optional-types.log)를 보존한다. 이 실패를 성공 APK의 증거로 쓰지 않는다. 최초 정적 parser 결과도 `static-debug-before-audit-fix.json`에 남기고 보강된 실행 명령 parser로 baseline을 다시 검사했다.

[마지막 기기 상태](../../artifacts/milestone-12/final-device-state.json)는 dev.13 설치·전면 상태, 앱과 Speech 서비스의 active recording configuration 각각 0개, 개발용 reverse와 loopback HTTPS listener 유지를 확인한다. 2026-11-07T16:45:50Z의 읽기 전용 한시점 관측이며 새 캡처·권한/위치 변경·모델 호출의 증거가 아니다.

## 재실행과 다음 조건

기본 debug는 기존 빌드 명령을 사용한다. privacy는 별도로 명시한다. 빌드와 기기 검사를 동시에 실행하지 않고 새 artifact 디렉터리로 기존 증거를 보존한다.

```sh
./scripts/gradle.sh -PpetasosTestBuildType=privacy :app:assemblePrivacy :app:assemblePrivacyAndroidTest :app:testPrivacyUnitTest :app:lintPrivacy --console=plain
python3 scripts/probe-pleos.py storage --build-type privacy --artifacts-dir artifacts/privacy-next/storage
PETASOS_ARTIFACTS_DIR=artifacts/privacy-next/smoke ./scripts/smoke-pleos.sh emulator-5554 --build-type privacy
```

제품 마이크는 계속 차단한다. 서비스의 공식 원문 로그 억제 설정 또는 수정 버전과 적용 절차를 확인해야 하며 [문의 초안](./pleos-speech-support-request.md)은 **아직 전송하지 않았다**. 사용자의 공식 설정/수정 버전/문의 창구 확인은 대기 중이다. 로그 문제가 해결된 뒤에도 cold/연속 입력·실제 EOF·제품 시간 제한을 확인하고, 그 다음에 사용자와 실제 발화·대화/요약 시험을 진행한다. Virtual Console은 보류하고 UI 디자인·데모 작업은 주요 기능 검수 뒤로 유지한다. provider 승인·만료 조건은 [실제 API 기록](./2026-11-07-live-apis.md)을 따르며 이번에는 모델 호출이나 승인 갱신을 수행하지 않았다.
