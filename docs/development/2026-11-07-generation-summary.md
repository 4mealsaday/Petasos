# 생성 서버 연결과 동결 대화 요약

기준: 2026-11-07, `versionCode=6`, `0.8.0-dev.6`. [dev.5 실행 host·암호화 저장소](./2026-11-07-runtime-storage.md)에 HTTPS 생성 경계, 서버, TX-03 요약 저장과 WorkManager 재시도를 추가했다. 현행 v0.8의 [API-01~09/TX-03](../Petasos_데이터_API_계약.md)와 [REC-03~07](../Petasos_동작명세.md)을 유지하며 참조 SQL·JSON schema는 바꾸지 않았다.

일반 UI는 여전히 프리뷰다. 실제 모델의 답변 품질이나 마이크에서 답변 재생까지 이어지는 완성 대화 기능을 검증한 단계가 아니다. 기기 시험의 생성 응답은 로컬 HTTPS 서버의 합성 provider가 제공한다. 사람의 발화와 Virtual Console은 사용자 요청대로 보류한다.

## 구현 경계

| 위치 | 책임 |
| --- | --- |
| `generation/` | strict JSON/schema, HTTPS transport, 요청·응답 식별자/근거/현재 상태 검사, 암호화 기기 설정, runtime answer·summary provider |
| `summary/` | 플랫폼 독립 claim/commit 계약, 실행 취소·timeout, 재시도 정책, 프로세스 시작 복구 gate, turn 동결 후 예약 wrapper |
| `storage/RoomSummaryStore.kt` | 실제 Room/Keystore snapshot, 조건부 claim, TX-03, 재시도·중단 복구 |
| `platform/android/summary/` | turn별 WorkManager 고유 작업, 네트워크 조건, ID만 전달하는 Worker, 시작 시 미예약 작업 복구 |
| `PetasosApplication.kt` | 백그라운드 요약 복구·예약 시작. 마이크·음성 재생을 시작하지 않음 |
| `server/petasos_server/` | HTTPS ASGI API, 기기 인증·소유권·멱등성·제한, 검토된 근거, OpenRouter 어댑터 |

단일 Android 모듈 안에서 UI·상태 전이·저장·네트워크·작업 예약을 분리했다. `MainActivity`나 색상·스타일·레이아웃을 네트워크 코드가 참조하지 않는다. `ApiRuntimeGeneration`과 `SummarySchedulingPersistence`는 제품 composition에서 연결할 경계이며, 일반 프리뷰 화면에 연결하지 않았다. 답변에 필요한 현재 turn/경로/근거 snapshot을 임의 기본값으로 만들지 않는다.

## HTTPS와 인증

앱은 비스트리밍 HTTPS JSON만 사용한다. 인증서·호스트 이름 검증, 리다이렉트 금지, 응답 본문 크기 제한을 적용한다. 시험에서만 일회용 CA를 명시적으로 신뢰하며 제품의 인증서 검증을 끄지 않는다. 기기 bearer token과 server origin은 Android Keystore AES-GCM으로 암호화한 `noBackupFilesDir` 설정에 저장한다. 기존 설정의 키가 없어지면 암호문을 보존하고 실패한다. OpenRouter API 키는 APK에 포함하지 않는다.

앱과 서버는 같은 `spec/generation-v0.8.schema.json`을 사용한다. Android resource 사본은 JVM 검사에서 canonical 파일과 바이트 단위로 대조한다. 중복 JSON key, 잘못된 UTF-8, Long 범위 초과, 알 수 없는 필드, 본문 256 KiB 초과와 요청에 없는 식별자·근거를 거부한다. 성공 응답의 schemaVersion/requestId/sessionId/turnId/turnVersion/cancelEpoch/routeDependency/inputHash를 대조한다. summary는 현재 재생 세대 대신 동결된 turn version으로 검사한다.

서버는 토큰 해시·만료·철회와 요청 소유권을 검사한다. 인증 주체+requestId를 키로 삼고 서버 비밀키로 계산한 정규화 본문 HMAC을 비교한다. 같은 ID의 다른 입력은 409, 같은 입력의 진행 중 요청은 합류한다. 성공 결과는 최대 60초 메모리에서 재사용하며 후속 요청이 없어도 만료한다. 재시도 가능한 실패는 대기 기한 후 같은 ID로 실제 provider를 다시 호출한다. 이 재시도에도 기기별 60건/분·동시 2건 제한을 적용한다. 장기 중복 기록은 없으므로 캐시 만료·프로세스 재시작 뒤 재호출 비용은 발생할 수 있다.

실시간 answer는 전체 15초 안에서 최대 한 번 재시도하며, 취소 시 서버 cancel을 요청한다. 요약 Worker의 중단은 해당 transport만 분리한다. 일시적인 OS 중단으로 논리 요약 요청까지 취소하면 다음 같은-ID 재시도가 서버의 CANCELLED 캐시에 막히므로 두 취소를 구분한다. 늦은 응답은 호출 상태와 DB claim/version을 다시 대조한다.

서버는 허용된 source revision을 운영자가 검토한 저장소와 대조하고 임의 URL을 수집하지 않는다. fact/tradition 분류와 source 관계를 검사하며 전승을 `verifiedFactRevisionIds`에 넣을 수 없다. 문장이 실제 근거를 정확히 표현하는지는 구조 검사만으로 증명할 수 없어 모델 평가가 별도로 필요하다. 요청·응답·공급자 원문 오류·토큰은 서버 로그나 내용 디스크 캐시에 남기지 않는다. 자세한 실행·설정 절차는 [서버 README](../../server/README.md)에 둔다.

## 요약 저장과 재시도

요약 입력은 암호화 payload에서 읽은 확정 사용자 텍스트, 정상 완료된 응답 문단과 중단 문단의 key다. 중단 문단의 본문과 생성됐지만 재생하지 않은 문단은 요약 본문 입력에 넣지 않는다. claim은 같은 frozen turn/version의 요청 ID와 HMAC을 재사용하고 시도 번호를 증가시킨다.

TX-03은 하나의 Room 트랜잭션 안에서 현재 turn·payload version, 동결 상태, 미저장 상태, 요청 ID/HMAC/시도 번호를 다시 비교한다. 조건을 만족할 때만 요약 삽입, turn `saved`, 임시 payload 삭제와 생성 성공 메타데이터를 함께 커밋한다. 중복·과거 응답은 현재 payload를 지우지 않는다. 근거 상태가 바뀌어 결과를 허용할 수 없으면 blocked 처리하고 원문을 보존한다. 복호화·키 실패도 원문을 삭제하지 않는다.

WorkManager 2.12.0의 고유 작업은 `turnId`만 inputData에 넣고 outputData에도 대화 텍스트를 싣지 않는다. 새 동결 작업은 KEEP, 실행 중인 작업의 후속 예약은 APPEND_OR_REPLACE를 사용한다. DB 커밋 후 enqueue 전에 종료돼도 앱 시작 시 다시 찾는다. 앱의 초기화 큐보다 Android JobService가 먼저 실행될 수 있어 Worker와 Application이 같은 복구 gate를 통과한 뒤 claim한다. DB 실패로 이전 claim이 running에 남은 WorkManager 재실행도 별도로 복구한다.

REC-06의 최대 3회는 **한 묶음의 실행 횟수 총 3회**로 적용했다. 첫 실패 후 30초, 두 번째 실패 후 2분에 후속 작업을 예약한다. 세 번째 실패는 10분의 다음 시도 힌트를 저장하고 `retry_wait`로 남기며 자동 네 번째 작업을 등록하지 않는다. 다음 앱 프로세스 시작에서 묶음을 초기화하고 저장된 대기 기한을 재평가한다. 서버 Retry-After가 더 길면 그 기한을 따른다. 매우 먼 기한은 WorkManager 지연 계산에 넘기지 않고 하루 이내에 DB 기한을 다시 확인하며, 이 확인 자체가 조기 생성 시도는 아니다. 실제 실행 시점은 OS·네트워크 조건에 따른다.

인증·형식·설정 오류는 설정 변경 전 자동 재시도를 막는다. `configurationChanged` 경계는 수정 후 다시 예약할 때 호출하며 설정 전달/UI 흐름은 이후 연결한다. 키 소실 blocked는 설정 변경으로 해제하지 않는다. 요약 실패 자체로 임시 텍스트를 만료시키지 않는다.

공식 기준은 [WorkManager 릴리스](https://developer.android.com/jetpack/androidx/releases/work), [사용자 정의 초기화](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration), [통합 시험 API](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing)다. WorkManager 2.12.0의 AAR 최소 compileSdk/AGP 조건을 기존 compileSdk 36/AGP 8.10.1이 만족하며 JDK·Kotlin·Room 버전은 유지했다.

## 검증 결과

최종 APK 기준 실행 결과는 `artifacts/milestone-5/`와 [결과 요약](../../artifacts/milestone-5/summary.json)에 기록했다. 실행 대상은 Pleos Connect 2.1.0 / Android 14(API 34), ARM64 `emulator-5554`, Android user 10이다.

| 검사 | 결과와 범위 |
| --- | --- |
| 앱·테스트 APK / Lint / JVM | PASS / PASS / 119개. Coordinator 39, runtime 19, 음성 종료 10, 암호화 7, 생성 28, 요약 실행·재시도·복구 gate 16. `build.log`, `TEST-*.xml` |
| 서버 회귀 | PASS 36개: 인증·소유권·제한·동시성·멱등성·취소·재시도·스키마·근거·OpenRouter 설정 검사. 외부 모델은 mock transport |
| 호스트 스크립트 / Room 매핑 | PASS 18개 / 24 tables·153 triggers 대조 |
| `summary-storage` | PASS 11개 marker: 미동결 제외·미청취 본문 제외·ID/HMAC 재사용·stale 응답·TX-03 rollback/중복·blocked·3회 소진·중단/시작 복구·동시 claim·키 소실 원문 보존 |
| `summary-work` | PASS 3개 marker: 실제 WorkManager 고유 작업·turnId만 전달·대기 중 원문 보존·두 번째 시도의 같은 요청 ID와 TX-03 반영 |
| `generation-https` | PASS 9개 marker: 실제 TLS/인증·암호화 설정·스키마/근거·멱등 요청·요약 provenance·HTTPS→Room TX-03·소유 취소·잘못된 토큰·설정 키 손실 보존 |
| `storage` / `runtime-scripted` | PASS / PASS. 기존 Room·Keystore·TX-01/02와 합성 대화의 저장→답변 완료→동결→대본 복귀 회귀 |
| 일반 설치·실행 | PASS. 최종 dev.6 APK 업데이트 설치·앱 프로세스·프리뷰 전면 Activity·크래시 없음. `launch/verification.txt` |

다섯 기기 probe는 같은 앱·시험 APK를 사용했다.

- 앱 SHA-256: `e572180451622039087d2dc88fa9ecce95a855fc9d2dd193c4a539b52d8a6411`
- 테스트 APK SHA-256: `45f129bff02140bac7feea14c22ba20e391852a07c34018d55df86f8e2f869a9`

기존 SDK XML 도구 버전 경고는 남았지만 컴파일·AAR metadata 검사·Lint는 통과했다. 이번 단계에서 TTS 어댑터를 바꾸지 않았으며 실제 TTS 완료/중지의 실행 증거는 [dev.5 검사](./2026-11-07-runtime-storage.md)를 유지한다.

`summary-work`는 실제 Android WorkManager·Room·Keystore를 사용하되 TestDriver로 네트워크 조건·대기 완료를 제어하고 생성 결과를 주입한다. 실제 30초/2분/10분 경과나 OS 종료·재부팅 후 재예약을 통과한 검사가 아니다. startup 경합은 JVM gate 검사와 실제 DB 복구 검사로 확인하며 강제 프로세스 종료·재부팅 검수는 남아 있다. 시험 초기에 공유 DB를 너무 일찍 닫는 fixture 오류를 찾아 수정했다.

`generation-https`는 실제 TLS·Android transport·서버 인증·스키마를 거쳐 실제 Room TX-03까지 연결한다. CA·토큰·DB·키는 일회용이며 `adb reverse`와 함께 종료 시 정리한다. 모델 응답은 합성이므로 OpenRouter 연결·품질·비용 또는 실제 사람의 발화를 검증하지 않는다. 시험 원문과 토큰을 결과 artifact에 남기지 않는다.

## 남은 작업과 사용자 확인

Gleo 자유 생성은 공식 문서에서 필요한 앱 주도 생성 계약을 확인하지 못했고, Pleos 2.1.0 릴리스에는 LLM SDK 제공 종료가 명시돼 있다. 현행 API-09에 따라 별도 OpenRouter 어댑터를 준비했다. 공식 근거와 provider 보관 조건은 [서버 README](../../server/README.md#gleo-확인과-openrouter-어댑터)에 기록했다. 실제 모델·provider는 아직 선정/연결하지 않았으며 유료 호출이나 공개 배포도 하지 않았다.

실제 연결에는 서버 전용 API 키, 모델/provider와 보관 조건 확인, 기기 토큰 전달이 필요하다. 키 값은 채팅·소스에 넣지 않고 비추적 로컬 설정/서버 환경에 주입한다. 서버의 fail-closed 설정 검사는 계정의 원격 개인정보 설정을 대신 증명하지 않는다.

다음 독립 개발 범위는 검토된 콘텐츠의 제품 설치기 TX-05와 로컬 근거 snapshot 연결이다. 이후 실제 모델 연결·평가, 제품 UI composition과 디자인·CarUxRestrictions를 진행한다. 요청별 STT stop 확인 경로, 전체 명령/ResumePlan, Media3, 위치 기반 편성·지역 카드, 실제 차량 Suspend/Resume와 전체 TX-04는 남아 있다. Ended·close 반환·1초 유예를 마이크 종료 보장으로 쓰지 않는다.

현재 화면에서 사용자가 새 기능을 직접 조작해 검증할 단계는 아니다. 필수 수동 테스트는 없고, 실제 모델 연결 준비 또는 직접 청취/발화가 필요한 시점에 입력 방법·기대 결과를 별도로 안내한다. 실제 발화와 Virtual Console은 계속 보류한다.
