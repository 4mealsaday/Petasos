# dev15 명시 재생 명령의 로컬 처리

작성: 2026-11-08. 구현 버전은 **0.8.0-dev.15**다. **privacy·기본 debug 앱/테스트 APK 빌드·Lint, JVM 435개/34 suite, 호스트 115개, 알려진 앱 SDK 로그 sink 정적 감사, 기기 회귀 12종과 privacy 설치/실행 smoke가 통과했다. 최종 기기 상태와 동결 소스 230개 일치도 확인했다.** [최종 집계](../../artifacts/milestone-14/verification.json)는 `COMPLETE_WITH_SPEECH_PRIVACY_BLOCKER`이며 별도 서비스 로그 문제가 해결됐다는 뜻은 아니다. 시험별 합성 입력과 실제 SDK의 범위는 아래 표를 따른다. 직전 완료 검증은 [dev14 자동편성 기록](./2026-11-08-automatic-story-planning.md)에 보존한다.

이번 단계는 최종 사용자 입력 전체가 등록된 재생 명령과 일치할 때, 저장·입력 종료 확인을 거쳐 로컬 Coordinator에서 처리하는 기능이다. 자연어 일반 명령 해석이나 모델의 재생 권한을 확대하지 않는다. 별도 Pleos SpeechService의 원문 로그 문제가 해결되지 않아 **제품 마이크는 계속 비활성**이며 실제 발화 시험도 요청하지 않는다.

## 명세와 지원 범위

[동작 명세](../Petasos_동작명세.md)의 RUN-01/02/04/05/07과 REC-02~05, [데이터/API 계약](../Petasos_데이터_API_계약.md)의 TX-01/TX-03을 유지한다. 새 Room schema나 API task를 추가하지 않는다.

canonical `answer.action` enum은 `continue/next/music/stop/replay/unsupported` 여섯 종류다. 현재 서버·앱의 모델 응답 허용 정책은 이 가운데 `continue/unsupported`와 `targetContentId=null`만 수용한다. 요청 wire에는 임의 재생 제어 권한이나 허용 대상 목록을 추가하지 않았다. 이번 로컬 분류는 **저장된 최종 사용자 입력**에만 적용하며 모델 응답·출처·요약 텍스트를 명령으로 해석하지 않는다. `intent` API를 새로 만들거나 모델 action의 실행 권한을 늘리지 않는다.

| 명령 예 | 로컬 처리 |
| --- | --- |
| `멈춰` | 배경 청취·상호작용 의도를 모두 false로 바꾸고 PAUSED. 다음 focus gain이나 늦은 후보가 재생을 시작하지 않음 |
| `계속 재생해` | 기존 이야기/음악 checkpoint의 결정적 복귀. checkpoint가 없으면 기존 편성 규칙에 따른 준비 콘텐츠/음악 또는 대기 |
| `건너뛰어` | 현재 배경 콘텐츠를 건너뜀. 이야기의 stable ID를 현재 세션 자동 후보에서 제외하며, 음악 skip은 정상 완료로 간주하지 않음 |
| `다시 재생해` | 현재 음악은 0 ms, 현재 immutable 이야기 버전은 첫 문단에서 새 attempt. 기존 완료 이력과 명시 replay 예외 유지 |
| `음악 틀어줘` | 기존 음악 checkpoint가 있으면 해당 위치, 없으면 준비된 허가 음악의 처음부터 재생. 외부 URI나 모델이 제시한 자산을 등록하지 않음 |

허용 표현의 전체 목록과 정규화는 [ExplicitPlaybackCommandParser](../../app/src/main/java/com/petasos/app/playback/ExplicitPlaybackCommandParser.kt)가 담당한다. 입력은 160자 이내 전체 문자열로 비교한다. NFC·앞뒤 공백·제한된 공백/끝 문장부호 정리만 적용하고 부분 문자열, 유사어, 대상 이름을 추론하지 않는다. 여러 줄·숨은 제어 문자 등은 명령으로 채택하지 않는다. 예를 들어 단독 `다음`은 등록된 명령이 아니다.

일반 질문, 부정문, 복합 요청, 인용, 이름으로 지정한 콘텐츠 요청 등 allowlist와 일치하지 않는 입력은 기존 일반 답변 경로로 전달된다. 그 경로의 설정·근거·현재성 검사를 그대로 거치므로 답변이 반드시 생성된다는 뜻은 아니다. 해당 문장에서 재생 명령을 추출해 실행하지 않는다.

음악이나 replay 대상이 없으면 각각 `재생할 수 있는 음악이 준비되어 있지 않습니다.`, `다시 재생할 콘텐츠가 없습니다.`라는 고정 공개 안내를 한 번 출력한 뒤 PAUSED를 유지한다. 두 의도는 false이며 기존 checkpoint를 보존한다. 새 자동 story offer가 도착해도 안내를 대신하거나 배경 이야기로 자동 복귀하지 않는다. 제품 음악 카탈로그는 여전히 비어 있어 현재 제품의 음악 명령은 자산 없음 경로에 해당한다.

## 저장·입력 종료·취소 경계

처리 순서는 `최종 입력 → TX-01 암호화 커밋 → 독립적인 입력 종료 ACK → 저장된 입력 읽기 → 전체 문자열 분류 → 현재 요청 재검사 → turn 동결·checkpoint 저장 → 허용 출력`이다. 중간 STT 결과를 명령으로 쓰지 않는다. SDK Ended, stop 호출의 반환, close 또는 타이머를 물리적 입력 종료 ACK로 바꾸지 않는다.

[PlaybackRuntime](../../app/src/main/java/com/petasos/app/runtime/PlaybackRuntime.kt)은 기존 `GenerateAnswer` effect의 원래 transition fence를 저장소 읽기와 큐 경계까지 유지한다. 저장된 발화를 읽은 뒤 새 fence를 취득해 이전 명령에 권한을 다시 주지 않는다. 로컬 분기와 일반 답변 모두 현재 session/epoch/turn/request, 상호작용 의도, 입력 READY, 출력·커밋 정리 상태, blocker 및 시작 fence를 검사한다.

[PlaybackCoordinator](../../app/src/main/java/com/petasos/app/playback/PlaybackCoordinator.kt)의 `ExplicitCommandResolved`는 현재 요청 token을 가진 결과만 수용한다. 새 Talk·취소·Stop·blocker·세션 종료·만료 뒤 결과는 재생 권한이 없다. 명령 수락 시 이전 실시간 요청과 타이머를 무효화하고 사용자-only turn을 CLOSED로 동결한다. 저장 effect는 `FreezeTurn → SaveCheckpoint → 결정적 복귀/고정 안내` 순서다. freeze나 checkpoint 저장이 실패하면 기존 저장 blocker와 fence가 새 출력을 막는다.

Next의 `ExcludeAutomaticStory`는 전이 observer가 편성을 다시 읽기 전에 전달한다. 이는 명시적인 건너뛰기 이력이며 실제 Started, 완료 청취, 카드 획득 증거가 아니다. 다시 듣기는 새 attempt를 만들고 기존 완료 attempt를 변경하지 않는다. 음악을 건너뛴 것을 정상 곡 종료로 취급하거나 이야기 사이 음악 간격을 충족한 것으로 저장하지 않는다. 최종 출력의 현재 권리·본문·자산 검사는 [dev14 출력 경계](./2026-11-08-automatic-story-planning.md)를 유지한다.

## 요약과 복구

로컬 명령은 답변 본문이나 성공한 생성 요청을 만들어내지 않는다. 암호화 payload에는 최종 사용자 입력이 있고 `responseParagraphs`, 완료·중단 응답 key 목록은 비어 있다. turn 동결 뒤 기존 요약 예약을 유지하며 요약 입력은 사용자 텍스트와 실제 완료 응답만 포함한다. 고정 공개 안내를 개인 답변으로 추가하지 않는다.

**명령 분류·실행에는 answer 모델과 resume 모델을 호출하지 않지만, 동결된 turn의 기존 백그라운드 summary 작업은 이후 모델을 호출할 수 있다.** 따라서 “명령은 모든 모델/네트워크 호출을 하지 않는다”는 보장은 하지 않는다. 요약 저장·turn saved·payload 삭제는 기존 TX-03의 원자적 커밋으로 처리하며 요약 전에 원문을 지우지 않는다. 개인 관심 설정을 명령이나 요약으로 자동 변경하지 않는다.

정상 명령 완료 turn은 CLOSED다. 외부 Stop·취소·중단이 먼저 수락되면 기존 interrupted/cancelled/failed 정책을 유지한다. 프로세스 복구는 미완료 turn과 시도를 정리하고 요약을 다시 예약할 수 있으나, 저장된 사용자 입력을 재분류하여 명령을 재실행하지 않는다. 앱 시작·복구·요약 완료 자체는 재생·마이크 시작 권한이 아니다.

## 파일 책임과 검증

| 파일/경계 | 책임 |
| --- | --- |
| [ExplicitPlaybackCommandParser](../../app/src/main/java/com/petasos/app/playback/ExplicitPlaybackCommandParser.kt) | 제한된 전체 입력 allowlist와 정규화. 텍스트를 영구 저장하거나 로그에 출력하지 않음 |
| [PlaybackContract](../../app/src/main/java/com/petasos/app/playback/PlaybackContract.kt), [PlaybackCoordinator](../../app/src/main/java/com/petasos/app/playback/PlaybackCoordinator.kt) | 명령 enum·현재 요청 결과·상태 전이·정지/복귀/안내·자동 후보 제외 |
| [PlaybackRuntime](../../app/src/main/java/com/petasos/app/runtime/PlaybackRuntime.kt) | 커밋된 발화만 분류, 원래 fence 유지, provider 우회, 저장·SDK effect 실행 |
| [RoomRuntimePersistence](../../app/src/main/java/com/petasos/app/storage/RoomRuntimePersistence.kt) | 기존 TX-01/turn/재생 저장 계약과 두 고정 공개 안내 ID. schema 변경 없음 |
| [ExplicitPlaybackCommandProbe](../../app/src/androidTest/java/com/petasos/app/storage/ExplicitPlaybackCommandProbe.kt) | 별도 DB·시험 전용 Keystore namespace와 scripted 포트의 저장소 회귀 |
| [SdkProbeInstrumentation](../../app/src/androidTest/java/com/petasos/app/diagnostics/SdkProbeInstrumentation.kt) | `local-commands` 등록과 고정 mark/오류 코드 수집 |

`local-commands`는 실제 Room·Android Keystore를 사용한다. 입력·출력·생성 포트는 시험용이며 **실제 AudioRecord/STT/TTS/스피커/GPS/HTTP/모델을 호출하지 않는다.** scripted `StoppedConfirmed`는 코어의 ACK 필요 조건을 검사하기 위한 명시적인 시험 이벤트이며 실제 마이크 종료 증거가 아니다. 실제 사용자 DB·키·설정을 변경하지 않고 disposable DB와 시험 키를 정리한다. 본문 대신 고정 mark와 실패 코드만 출력한다.

검사에 포함한 경계는 다음과 같다.

- 최종 입력이 저장돼도 scripted stop ACK 전에는 명령·새 출력·answer provider 호출이 발생하지 않음.
- Stop·Continue·Next·Replay의 checkpoint/새 attempt와 완료·카드 이력의 구분.
- 음악/replay 대상 없음 안내 뒤 PAUSED 유지와 늦은 story offer의 무재생.
- 최종 입력 저장 후 출처 권리 철회, 큐에 들어온 ACK보다 늦게 ingress한 수동 Stop, freeze/checkpoint SQL 실패의 새 출력 차단.
- 사용자-only frozen turn의 빈 완료 응답으로 요약 claim, 합성 요약 결과의 TX-03 커밋·중복 거부.
- TX-01 뒤 입력 ACK 전에 프로세스를 잃은 상황을 합성한 실제 TX-04 복구. 새 세션/출력/명령 재실행 없이 사용자-only turn 동결.

순수 parser·Coordinator·runtime 회귀를 포함한 privacy JVM 실행은 435개/34 suite, 실패·오류·skip 모두 0으로 통과했다. 2026-11-08 03:43:25Z에 끝난 [사전 실행](../../artifacts/milestone-14/preflight-result.json)은 `testPrivacyUnitTest`와 `compilePrivacyAndroidTestKotlin`이며, [JVM 집계](../../artifacts/milestone-14/jvm-tests.json)를 따른다. 최종 빌드가 UP-TO-DATE 결과를 재사용하더라도 새로운 JVM 실행으로 중복 집계하지 않는다. 호스트 도구 회귀 115개는 별도 [실행 기록](../../artifacts/milestone-14/host-tests.json)과 [원본 로그](../../artifacts/milestone-14/host-tests.log)에 보존했다. 실제 타이밍·사람 발화 인식 품질·음향적 정지·장시간 안정성 검증을 뜻하지 않는다.

| 검증 항목 | 현재 상태 |
| --- | --- |
| privacy JVM 사전 검사 | PASS: 435 tests / 34 suites, failures/errors/skipped 모두 0 |
| instrumentation Kotlin 컴파일 | PASS: `compilePrivacyAndroidTestKotlin`. APK 설치·실행 검사는 아님 |
| 호스트 도구 회귀 | PASS: 115 tests, 원본 로그 보존. Android/서버 검사와 별도 집계 |
| privacy 앱·instrumentation APK 빌드 / Lint | PASS — 2026-11-08 03:46:40Z 완료 |
| `local-commands` 및 관련 기기 회귀 | PASS — 동일 APK 쌍의 12종. 아래 범위표 참조 |
| 알려진 앱 SDK 로그 sink 정적 감사 | PASS — 알려진 DEBUG/stacktrace sink 부재, STT raw INFO helper 호출 0, 모델/reflection·제어 참조 보존 |
| 기본 debug 앱·instrumentation APK 빌드 / Lint | PASS — 03:49:36Z 완료. debug JVM은 별도 재실행하지 않음 |
| privacy 설치·실행 smoke | PASS — 03:49:59Z 완료. 최종 APK 설치·실행 범위이며 SDK 검증은 별도 |
| 최종 기기 상태 | PASS — 03:50:11Z. dev15 최종 APK 해시 일치·앱 전면, 앱/서비스 recording configuration 각각 0, HTTPS health 200·기존 reverse 유지 |
| 최종 동결 소스 일치 | PASS — 03:50:15Z. 230개 파일의 변경·미기록 파일 0 |

[privacy 빌드 결과](../../artifacts/milestone-14/build-result.json)와 [기기 실행 목록](../../artifacts/milestone-14/probes/run-results.json)을 확인했다. 기기 회귀는 03:47:02Z~03:47:54Z에 실행했으며 12개 개별 보고서 모두 같은 다음 APK 쌍을 기록한다.

| privacy APK | SHA-256 |
| --- | --- |
| 앱 | `be39f80023d25e560a7595b9600c79fe6ead19c0d95a81549af5b8c1cffc3d45` |
| instrumentation | `291a9b799067ed30715eb36e7dd8e65675e68cf38a0d50f6ba44ee9e573c952d` |

위 APK 쌍은 `.local/builds/dev15/privacy-final/`에 보존했으며 [보존 기록](../../artifacts/milestone-14/final-apks.json)을 따른다. [debug 호환 빌드](../../artifacts/milestone-14/debug-build-result.json), [privacy smoke](../../artifacts/milestone-14/smoke-result.json)와 [설치·실행 확인](../../artifacts/milestone-14/privacy-smoke/verification.txt), [최종 소스 검사](../../artifacts/milestone-14/final-source-check.json)를 개별 증거로 남겼다.

[최종 기기 상태](../../artifacts/milestone-14/final-device-state.json)는 Android user 10에서 최종 privacy 앱이 전면에 있고 설치 APK 해시가 일치함을 기록한다. 앱과 별도 SpeechService의 active recording configuration은 각각 0이었다. 이는 해당 시점의 읽기 관측이며 요청별 입력 종료 ACK·연속 감시·서비스 무로그 증거가 아니다. localhost 개발 HTTPS는 health 200, 기존 adb reverse는 이미 유지되어 복원할 필요가 없었다. 새 모델/API 호출이나 provider 검토 갱신 결과로 해석하지 않는다.

| 통과한 기기 probe | 실제 실행·합성 경계 |
| --- | --- |
| `local-commands` | 실제 disposable Room·Keystore, scripted 입력/출력/생성 포트. 고정 mark 9개 통과. 실제 마이크·음성 출력·위치·모델 없음 |
| `runtime-scripted`, `automatic-planning` | 실제 Room/Keystore 또는 Room의 합성 콘텐츠·scripted runtime. 실제 오디오·마이크·GPS·모델 없음 |
| `runtime-tts`, `runtime-tts-stop` | 합성 공개 이야기의 실제 Pleos TTS·Room. 완료/Stopped callback과 저장을 확인하며 음향적 무음·사람 대화 검증은 아님 |
| `music-runtime` | 시험용 합성 음원의 실제 Media3·Room. 제품 음악 자산 검증은 아님 |
| `session-recovery`, `summary-storage`, `resume-context` | 실제 Room·Keystore의 합성 중단/동결 자료·TX-03·현재성/복구 검사. 실제 OS Suspend·모델 호출 없음 |
| `media-session` | 실제 MediaController 명령 전달과 제품 browse/입력 gate. 제어 대상은 시험용이며 오디오·마이크 없음 |
| `app-trip-lifecycle` | 제품 공개 이야기의 TTS 시작 저장·정지 ACK·이동 종료·다음 명시 재생의 새 이동. 위치 동의·사람 발화·모델 호출 없음 |
| `car-policy-ui` | 실제 CarUx 관측과 합성 visibility. 차량 상태 변경이나 실주행 없음 |

[local-commands 보고서](../../artifacts/milestone-14/probes/local-commands/local-commands-privacy.json)의 전체 probe 소요시간은 제품 명령 응답 지연이나 실제 마이크 종료 시간으로 사용하지 않는다. [정적 감사](../../artifacts/milestone-14/privacy-apk-audit.json)는 앱 DEX의 알려진 sink와 참조를 검사한 범위이며 전체 taint 분석·모든 SDK 모드 기능·별도 SpeechService 무로그를 증명하지 않는다. 이번 단계에서 별도 서비스 원문 로그를 다시 감사하지 않았고 기존 blocker는 그대로다.

## 유지하는 제한

별도 SpeechService 원문 로그 blocker와 첫 발화 5초/Final 500 ms 계약은 변경하지 않는다. [dev13 음성 privacy 검증](./2026-11-08-speech-privacy-verification.md)과 [문의 초안](./pleos-speech-support-request.md)을 따른다. 이번 코드로 제품 마이크가 활성화됐거나 사람 대화가 완성됐다고 표시하지 않는다.

Virtual Console은 계속 보류한다. UI 외형·색상·배치와 데모 시나리오를 바꾸지 않는다. 실제 음악 권리 자산, 위치 품질·지역 경계·랜드마크 정책, 이름 기반 콘텐츠 탐색, 모델 action 제어와 개인 연결 문장 출력은 별도 후속 범위다.
