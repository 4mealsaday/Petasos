# Petasos — 데이터·API 계약

버전 0.8 · 기준일 2026-11-06 · [동작 명세](./Petasos_동작명세.md) · [검수 명세](./Petasos_검수명세.md)

이 문서는 구현 계약이다. 로컬 SQL은 Room 매핑을 검토할 참조 스키마이며 배포된 DB가 아니다. 실제 앱·SDK·네트워크 검증 상태는 검수 문서에서 관리한다.

## 1. 공통 규칙

**DATA-01 식별자.** 앱 생성 ID는 UUID 문자열을 기본으로 한다. 원천 제공자 ID는 별도 연결표에 보관한다. ID는 재사용하지 않는다. AI가 ID·버전·시각을 생성해 상태 소유자가 되는 구조를 사용하지 않는다.

**DATA-02 관계.** Session→Trip, RecordingSegment→Session/Trip, RoutePoint→Segment, Turn→Session, PlaybackAttempt→Session/Paragraph/Turn, Summary→Turn/version, Collection→Story/version 또는 Region으로 연결한다. FK는 실제로 활성화하며 참조 중인 영구 기록은 삭제하지 않는다.

**DATA-03 버전.** DB 스키마 버전, API schemaVersion, storyVersionId, factRevisionId, sourceRevisionId, routeRevision, turnVersion, 설정 version은 별개다. 대본·사실·출처 revision의 내용은 발행 후 덮어쓰지 않는다. 수정본을 추가하고 과거 이력은 당시 revision을 참조한다. sourceId/factId/storyId는 의미상 같은 대상의 안정된 ID다.

**DATA-04 필수값.** SQL의 NOT NULL·CHECK·UNIQUE·FK가 물리 제약의 기준이다. 비어 있는 문자열로 null을 대신하지 않는다. 공개 자료에서 모르는 날짜·위치·정확도는 null과 상태값으로 표현한다. 원천 0값을 모든 제공자에서 결측으로 단정하지 않는다.

**DATA-05 변경 주체.** 위치 어댑터만 RoutePoint를 추가한다. 콘텐츠 설치기는 검토된 자료를 등록한다. Coordinator만 재생·세션·turn·checkpoint를 바꾼다. 요약 Worker는 REC-04 트랜잭션으로 요약 상태를 바꾼다. 화면은 저장소를 읽고 명령을 Coordinator에 보낸다.

**DATA-06 조회.** 경로는 segmentId/sequence, 청취는 paragraphId/state와 turnId, 요약은 turnId로 조회한다. trip별 위치는 segment를 조인하고 페이지 단위로 읽는다. 전체 경로·대화를 LLM 입력에 넣지 않는다. 대본 버전별 필수 문단 집합으로 완료를 계산하고, 카드 수와 청취 완료 횟수를 혼동하지 않는다.

## 2. 시간 계약

**TIME-01.** DB의 `*_at`, `valid_until`은 UTC Unix epoch milliseconds의 signed 64-bit 정수다. API도 같은 단위의 정수를 사용한다. 화면은 Asia/Seoul로 변환한다. 날짜만 알려진 원천은 원래 날짜 문자열을 sourceUpdatedRaw에 보존하고 정확한 순간을 뜻하는 sourceUpdatedAt은 null로 둔다.

**TIME-02.** 타임아웃·지연·경과 시간은 Android elapsedRealtime 계열 단조 시계를 사용한다. clockScopeId는 프로세스 시작 시 새 UUID이며, 같은 scope에서만 두 값을 비교한다. 재시작 후 이전 경과값을 이어 계산하지 않는다. UTC 시각은 보정으로 역행할 수 있으므로 전이 순서와 최신성 충돌은 sequence/stateRevision/turnVersion으로 처리한다. UTC 차이만으로 음수 duration을 DB 오류로 판정하지 않는다.

**TIME-03.** observedAt은 측정 시각, receivedAt은 수신 시각이다. rawTimestamp·sourceTimeUnit을 함께 보관하고 의미가 확인되지 않은 시각을 임의 변환하지 않는다. last-known 위치의 수신이 지금이라는 이유로 신선한 측정값으로 취급하지 않는다.

**TIME-04.** startedAt/endedAt은 실제 관찰한 사건, interruptionDetectedAt은 복구 때 중단을 발견한 사건이다. 비정상 종료에서는 endedAt=null이다. collection earnedAt은 조건을 충족한 사건, createdAt은 DB 적립 시각이다. summary createdAt은 요약 커밋 시각이며 utteranceFinalizedAt과 다르다.

**TIME-05.** updatedAt은 변경 가능한 설정·checkpoint·암호화 payload에만 둔다. append-only 이력에 형식적인 updatedAt/deletedAt을 만들지 않는다. retryAt은 영구 일정 힌트이며 재시작 시 현재 작업 상태·OS 조건·시각 변경을 재평가한다.

근거: [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock). 실제 시각과 단조 시각의 용도는 플랫폼 문서에 근거하며, 저장 필드와 scope 정책은 Petasos 설계다.

## 3. 영구·임시 데이터와 내용 검증

| 분류 | 내용 | 보관 |
| --- | --- | --- |
| 콘텐츠 원천·대본 | 이용 가능한 출처 revision, 사실, 장소, 대본·문단 | 참조 이력이 있는 버전 유지. 라이선스에 맞는 본문만 별도 보관 |
| 영구 사용자 기록 | 이동·위치·재생 attempt·대화 메타데이터·요약·카드·명시적 설정 | 자동 삭제 없음, 기기 로컬 |
| 암호화 임시 대화 | 최종 STT, 개인 대화 답변·연결 문장, 문단별 재생 참조 | 요약 커밋 성공 후만 삭제, 실패하면 유지 |
| 메모리 전용 | 인식 중간 결과, 재생 후보, 활성 ContentItem/ResumePlan | 프로세스 수명. 영구 근거는 별도 레코드 |
| 일반 진단 로그 | ID·버전·상태·오류·시간·사용량 | 원문 발화·개인 답변·정밀 좌표·토큰 없음 |

**DATA-07.** ContentItem은 런타임 DTO이며 텍스트가 있는 공통 영구 테이블을 만들지 않는다. kind는 story/music/answer/prompt/transition이다. 일반 대본은 paragraphId, 음악·고정 안내는 assetId, 개인 대화 문단은 turnId+transientParagraphKey로 참조한다. answer와 개인화 transition은 PendingConversation 안에만 본문을 저장한다.

**DATA-08.** PendingConversation payload는 AES-GCM 암호화 대상으로 다음 구조를 가진다: userFinalText, responseParagraphs(key,text,sourceRevisionIds), playedParagraphKeys, interruptedParagraphKeys. 요약 입력은 userFinalText와 완료된 responseParagraphs만 포함한다. 암호화마다 새 nonce를 사용하고 keyVersion+nonce를 중복시키지 않는다. payloadHash는 원문의 단순 해시가 아닌 로컬 비밀키 기반 HMAC이며 로그에 노출하지 않는다.

**DATA-09.** 요약은 summaryText와 provenance를 저장한다. provenance는 요약된 userClaims, verifiedFactRevisionIds, unresolvedQuestions, completedResponseKeys, partialUnknownKeys다. 원문 전문이나 재생하지 않은 답변을 요약 이력에 복사하지 않는다. summaryState는 영구 Turn에 있으며 성공한 Summary 행에 pending/failed 상태를 섞지 않는다.

**DATA-10.** 준비 콘텐츠 설치 시 사실/출처 revision의 존재, 이용 가능 범위, 최소 1개 필수 문단, 문단별 fact 연결, stable factId 중복 편성, 좌표계와 지역 코드를 검증한다. sourceUpdatedAt은 최신성 증거 하나이며 fetchedAt이 최근이라는 이유로 영업시간·가격을 최신으로 판단하지 않는다.

**DATA-11.** RegionPolicy JSON은 datasetVersion, consecutiveSamples(2 이상), minConfirmationDurationMs, boundaryMarginMeters, qualityAdapterVersion을 요구한다. LandmarkPolicy JSON은 approachRadiusMeters, passedRule, directionRule, cooldownMs, policyVersion을 요구한다. 각 값은 시연 경로 자료로 채우고 검토 상태가 verified일 때만 자동 판정에 사용한다. 미검증 설정은 자동 안내·수집을 보류한다.

**DATA-12.** preference JSON의 keywords는 사용자가 선택한 최대 20개 문자열, voice는 locale/voiceId/rate, storyFrequency는 mode/betweenStoriesMusicTracks다. 초기값은 ko-KR, 설치 기기의 기본 음성, rate=1.0, mode=balanced, 음악 1곡이다. 지원 음성 ID·속도 범위는 SDK 확인 후 설정한다. 개인 발화로 자동 갱신하지 않는다.

## 4. 트랜잭션과 불변 조건

| ID | 트랜잭션 | 비교·제약 | 성공 후 상태 |
| --- | --- | --- | --- |
| TX-01 | 최종 발화 확정 + 암호화 payload | turn 활성, 같은 최종 결과의 재수신은 no-op | utteranceFinalizedAt·version·payload 커밋 후에만 생성 전송 |
| TX-02 | 재생 terminal 전이 + checkpoint + 카드·근거 | attempt가 prepared/playing이고 활성 어댑터와 일치. stateRevision이 최신 | 완료 attempt와 카드가 일치. 실패 시 모두 rollback |
| TX-03 | 요약 삽입 + Turn saved + payload 삭제 | frozen=true, 요청 version=turn version=payload version, 기존 요약 없음 | summary·savedAt 존재, payload 없음 |
| TX-04 | 중단된 구간·세션 마감 + 새 세션 | 열린 세션/구간은 각각 최대 1개 | 지난 콜백은 새 재생 상태를 바꾸지 못함 |
| TX-05 | 콘텐츠 revision·fact/source 연결 설치 | 참조 무결성·필수 문단·권리 검토 완료 | 완전한 콘텐츠 묶음만 published |

**INV-01.** 같은 attempt의 terminal 결과는 하나다. stopped 이후 늦은 completed는 금지한다. 반복 청취는 새 attempt이며 같은 이벤트를 새 attempt로 바꾸어 중복 적립하지 않는다.

**INV-02.** 같은 storyVersionId의 필수 paragraphId마다 completed attempt가 하나 이상 있을 때만 story 카드 생성이 가능하다. 서로 다른 세션의 완료는 합산할 수 있지만 버전은 섞지 않는다. unique storyId가 대본 버전과 무관한 카드 중복을 막는다.

**INV-03.** CollectionProof는 지역 카드에는 valid RoutePoint와 판정 당시 regionRevisionId, 이야기 카드에는 해당 버전의 completed PlaybackAttempt만 연결한다. FK 외에 카드 종류·버전·완료 상태를 TX-02에서 검사한다. 좌표가 해당 지역에 포함되는지와 경계 확인 조건은 검증된 RegionPolicy로 판단한다.

**INV-04.** 임시 payload를 삭제해도 ConversationTurn은 유지한다. Summary는 영구 Turn/version을 참조한다. 오래된 Worker 결과는 no-op이며 현재 payload를 지우지 않는다. frozen turn은 새로운 발화나 재생으로 수정하지 않고 후속 대화는 새 turnId로 만든다.

**INV-05.** 콘텐츠·checkpoint·요약에 담긴 참조는 실제 존재하고 허용된 버전이어야 한다. lastCompletedParagraphId는 복귀 이야기와 같은 버전이어야 한다. 정밀 좌표 누락 구간을 실제 기록으로 보간하거나 중단 문단을 완료로 간주하지 않는다.

네트워크 호출은 DB 트랜잭션 밖에서 수행한다. SQLite 쓰기 실패는 rollback하고 UI에 미저장 상태를 전달한다. [SQLite 트랜잭션](https://www.sqlite.org/lang_transaction.html), [외래 키](https://www.sqlite.org/foreignkeys.html), [Room 고유 제약](https://developer.android.com/training/data-storage/room/defining-data)

## 5. 런타임 객체

| 객체 | 필드·필수 여부 | 유효성 |
| --- | --- | --- |
| ContentItem | contentId, kind, sessionId, requestId(nullable), cancelEpoch, routeDependency(nullable), paragraphId/assetId/turnId+transientParagraphKey 중 해당 포인터, status, expiresAt(nullable) | 재생 직전에 세대·의도·blocker·위치 확인. text는 메모리 또는 암호화 payload |
| ResumePlan | action(resume/skip/switch/wait), targetContentId(nullable), paragraphId(nullable), transitionText(nullable), reason | wait는 target=null, 그 외에는 제공한 후보 ID만 허용. 개인 대화 연결 문장은 임시 보관 |
| RouteContext | sessionId, routeRevision, destinationSummary(nullable), currentRegionId(nullable), qualityStatus | 정밀 전체 경로를 생성 입력으로 보내지 않음 |
| AssetManifest | assetId, kind(music/prompt/prepared_voice), sha256, localPath, license, attribution, durationMs(nullable) | 앱 배포 자산. 경로 존재·해시·권리 검증, 사용자 원음과 분리 |

## 6. Petasos 서버 API

**API-01.** P0는 HTTPS JSON 요청/응답이며 스트리밍을 사용하지 않는다. 버전 경로는 /v1이다. 스키마는 로컬 [generation-v0.8.schema.json](../spec/generation-v0.8.schema.json)을 따른다. 앱 DTO는 camelCase, DB는 snake_case를 사용한다.

| 메서드·경로 | 역할 | 본문/응답 |
| --- | --- | --- |
| POST /v1/generations | story/answer/resume/summary 작업 | Request → SuccessResponse 또는 ErrorResponse |
| POST /v1/generations/{requestId}/cancel | 해당 요청의 취소 표식 | 본문 없음. 소유 요청이면 204, 알 수 없는 요청이면 404 |
| GET /v1/health | 프로세스 준비 상태 | 200: {"status":"ok","schemaVersion":"0.8"}. 키·모델 응답·개인 데이터 미포함 |

**API-02 인증.** 데모는 등록 기기별 충분히 긴 무작위 bearer token을 사용한다. 서버는 해시와 만료·철회 상태를 관리하고 모든 생성·취소 요청의 소유자를 검사한다. 토큰은 저장소/APK에 하드코딩하지 않고 개발 시 기기 전용 암호화 설정으로 전달한다. Pleos Client Secret과 OpenRouter 키는 이 토큰을 대신하지 않는다. 인터넷 공개 전에 TLS·만료·철회·인증 실패 시험을 통과해야 한다.

**API-03 요청.** schemaVersion, requestId, sessionId, cancelEpoch, routeDependency, task, inputHash, payload를 보낸다. turnId/turnVersion은 answer/summary에 필수다. requestId와 입력 HMAC은 동일 요청의 재시도에서 유지한다. 서버는 클라이언트 inputHash를 신뢰해 중복 판정하지 않고 받은 본문을 정규화해 서버 비밀키로 자체 지문을 계산한다. 같은 ID에 다른 입력은 409다. 생성 모델에 식별자·인증·재생 권한을 판단하도록 맡기지 않는다.

**API-04 근거.** 서버는 클라이언트의 source ID를 허용된 자료 저장소와 대조하고 해당 revision의 자료로 입력을 구성한다. 임의 URL을 따라가거나 네이버 검색 결과를 근거로 받아들이지 않는다. 사용자 발화는 데이터이며 시스템 지시나 도구 실행 권한이 아니다.

**API-05 응답.** 서버가 요청 메타데이터를 그대로 결합하고 모델은 작업 결과만 만든다. 앱은 스키마 검증→ID/출처/버전 검증→현재 상태 검증 후 사용한다. summary 결과는 재생 세대 대신 REC-04의 frozen turn version으로 검증한다. 실시간 응답은 수신 시와 출력 직전에 모두 검증한다.

| task | payload의 필수 필드 | result의 필수 필드 |
| --- | --- | --- |
| story | storyId, topicId, regionId(nullable), sourceRevisionIds(1~50), excludedFactIds, interestTags(최대 20) | storyId, storyVersionId, paragraphs(1~12), estimatedDurationMs |
| answer | userText, contextSummary, heardParagraphIds(최대 64), sourceRevisionIds | paragraphs(1~8), action, targetContentId(nullable) |
| resume | contextSummary, candidates(최대 20; contentId/paragraphId(nullable)/kind) | action, targetContentId(nullable), paragraphId(nullable), transitionText(nullable), reason |
| summary | userText, completedResponses(최대 32; paragraphKey/text/sourceRevisionIds), partialUnknownKeys | summaryText, userClaims, verifiedFactRevisionIds, unresolvedQuestions, completedResponseKeys, partialUnknownKeys |

배열 ID는 중복을 허용하지 않는다. 빈 목록도 필드를 생략하지 않고 빈 배열로 보낸다. 문단은 paragraphKey/text/kind/sourceRevisionIds/factRevisionIds를 가진다. fact/tradition은 허용된 근거가 필요하며 personal/transition을 공인 사실로 적립하지 않는다. 서버가 문단 키를 부여하고 중복 키를 거부한다.

answer action은 continue/next/music/stop/replay/unsupported다. resume action은 resume/skip/switch/wait이며 wait의 대상은 null, 나머지는 제공한 후보만 가능하다. story/resume의 turnId·turnVersion은 null이고 answer/summary에는 값이 필요하다.

성공 응답은 요청 메타데이터와 status=ok, result, metadata(modelId/provider/promptVersion/inputTokens/outputTokens)를 반환한다. 제공자가 토큰 수를 주지 않으면 inputTokens/outputTokens는 null이며 0으로 꾸미지 않는다. 오류 응답은 schemaVersion/requestId(nullable)/status=error/code/retryable/retryAfterMs(nullable)를 반환한다. 요청에 없는 ID·세대·행동은 스키마 통과 여부와 별개로 거부한다. 정상 answer 요청의 합성 예시는 로컬 spec/request-example.json에 제공한다.

**API-06 오류.** 모든 오류는 requestId, code, retryable, retryAfterMs(nullable)를 가진다. 개인 원문·공급자 원문 오류를 그대로 앱 로그에 넣지 않는다.

| HTTP | code | 처리 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / UNSUPPORTED_SCHEMA | 수정 전 재시도 없음 |
| 401/403 | UNAUTHORIZED / FORBIDDEN | 인증·권한 수정 전 재시도 없음 |
| 404 | NOT_FOUND | 존재하지 않는 요청 취소. 다른 기기의 요청 내용은 노출하지 않음 |
| 409 | IDEMPOTENCY_CONFLICT / STALE_CONTEXT | 같은 입력 재확인 또는 현재 상태로 새 요청 |
| 422 | NO_EVIDENCE / INVALID_MODEL_OUTPUT | 근거 없는 답변을 만들지 않음. 제한된 복구 후 대기 |
| 429 | RATE_LIMITED | Retry-After가 남은 전체 대기 한도 안일 때만 재시도 |
| 502/503/504 | UPSTREAM_UNAVAILABLE / GENERATION_TIMEOUT | RUN/REC 대기·재시도 정책 적용 |
| 499에 의존하지 않음 | CANCELLED | 서버는 409 오류 코드 또는 취소 endpoint 204, 앱은 로컬에서 즉시 무효화 |

**API-07 중복·취소.** 서버는 인증 주체+requestId를 중복 키로 사용한다. 같은 키의 요청 본문을 서버가 계산한 지문으로 비교해 다르면 409로 거부하고, 같으면 진행 중 요청에 합류하거나 결과를 최대 60초 메모리에서 재사용한다. 내용 캐시는 디스크에 남기지 않는다. 서버 재시작 후 모델 재호출·비용 재발생은 가능하며, 앱 DB의 멱등 반영이 중복 재생·요약을 막는다. 취소는 상위 모델의 즉시 과금 중단을 보장하지 않는다.

**API-08 제한.** 요청 본문은 최대 256 KiB, 사용자 최종 텍스트는 최대 4,000자, 생성 문단·연결 문장은 최대 240자다. 글자 제한이 실제 음성 15초를 보장하지 않으므로 음성 길이를 별도로 검증한다. 등록 기기당 시작값은 60건/분·동시 2건이며 앱은 실시간 작업 1개와 요약 Worker 1개를 우선한다. 현재 비용 상한은 사용자 미지정이며 임의 결제하지 않는다. 모델·제공자·promptVersion·토큰·지연·오류를 메타데이터로 기록한다.

**API-09 외부 생성.** Gleo 자유 생성 가능성을 먼저 확인하고 요구 기능을 충족하지 못하면 OpenRouter 어댑터를 사용한다. TTS/STT는 별도 음성 SDK다. provider 보관 조건을 충족하지 못하면 조용히 조건을 완화하지 않고 실패 처리한다.

## 7. 스키마 변경·보존

DB version은 정수로 관리하고 Room schema export를 저장소에 보관한다. 마이그레이션은 이전 데이터 fixture로 참조·개수·내용·카드 유일성·암호화 payload를 검증한다. 파괴적 초기화와 암묵적 영구 기록 cascade 삭제는 사용하지 않는다.

원본 출처·대본 수정은 revision 추가다. 지역 코드 변경 시 검토한 매핑으로 안정된 regionId를 유지할지 결정하고 regionRevision을 추가한다. 기존 획득 근거의 regionRevisionId는 바꾸지 않으며 새 코드만으로 같은 지역 카드를 재지급하지 않는다. 스키마 변경과 원천 데이터 갱신을 구분한다. [Room 마이그레이션](https://developer.android.com/training/data-storage/room/migrating-db-versions)

## 8. 필드 사전

아래 사전은 참조 SQL에서 생성한다. SQL의 PK/FK/UNIQUE/CHECK 전문은 [petasos-v0.8.sql](../spec/petasos-v0.8.sql)에 둔다. TEXT/INTEGER/REAL/BLOB은 SQLite 저장 타입이며 INTEGER 시간은 Kotlin Long, 불리언은 0/1로 매핑한다. JSON 컬럼도 DATA-08~12와 API 스키마의 구조 검증을 거친다. 각 모델의 생성 주체·보관 수명은 DATA-05와 3절을 따른다.

<!-- FIELD_DICTIONARY -->

### 8.1. trip

이동 단위. 영구 보관; Coordinator가 상태 변경.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| trip_id | TEXT · 필수 | 없음 | 이동 기록 ID. 다른 모델에서는 Trip 참조 |
| started_at | INTEGER · 필수 | 없음 | 시작을 관찰한 UTC 시각 |
| ended_at | INTEGER · 선택(null) | 없음 | 정상 종료를 관찰한 UTC 시각. 비정상 종료는 null |
| last_observed_at | INTEGER · 선택(null) | 없음 | 마지막으로 관찰·커밋한 위치의 UTC 시각 |
| interruption_detected_at | INTEGER · 선택(null) | 없음 | 복구 과정에서 중단을 발견한 UTC 시각 |
| status | TEXT · 필수 | 없음 | 모델별 처리 상태. 허용 enum은 CHECK 참조 |
| end_reason | TEXT · 선택(null) | 없음 | user_end/service_end/suspend/permission_lost 등 관찰한 종료 이유 |
| revision | INTEGER · 필수 | 1 | 낙관적 동시성 검사에 사용하는 증가 버전 |

### 8.2. session

Coordinator 실행 세대. 이전 응답 재사용 방지용 영구 메타데이터.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| session_id | TEXT · 필수 | 없음 | Coordinator 실행 세대 ID |
| trip_id | TEXT · 필수 | 없음 | 이동 기록 ID. 다른 모델에서는 Trip 참조 |
| started_at | INTEGER · 필수 | 없음 | 시작을 관찰한 UTC 시각 |
| closed_at | INTEGER · 선택(null) | 없음 | 세션 또는 대화 상태를 닫은 UTC 시각 |
| status | TEXT · 필수 | 없음 | 모델별 처리 상태. 허용 enum은 CHECK 참조 |
| cancel_epoch | INTEGER · 필수 | 0 | 관련 실시간 요청 무효화 세대. 음수가 아님 |
| state_revision | INTEGER · 필수 | 0 | 수용된 상태 변경 순번. 시각 대신 최신 상태 비교 |
| route_revision | INTEGER · 필수 | 0 | 내비 경로 변경 버전. GPS 수신마다 증가하지 않음 |

### 8.3. recording_segment

연속 위치 수신 구간. 수신 공백을 실제 주행으로 연결하지 않음.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| segment_id | TEXT · 필수 | 없음 | 위치 기록 구간 ID |
| trip_id | TEXT · 필수 | 없음 | 이동 기록 ID. 다른 모델에서는 Trip 참조 |
| session_id | TEXT · 필수 | 없음 | Coordinator 실행 세대 ID |
| started_at | INTEGER · 필수 | 없음 | 시작을 관찰한 UTC 시각 |
| ended_at | INTEGER · 선택(null) | 없음 | 정상 종료를 관찰한 UTC 시각. 비정상 종료는 null |
| last_observed_at | INTEGER · 선택(null) | 없음 | 마지막으로 관찰·커밋한 위치의 UTC 시각 |
| interruption_detected_at | INTEGER · 선택(null) | 없음 | 복구 과정에서 중단을 발견한 UTC 시각 |
| status | TEXT · 필수 | 없음 | 모델별 처리 상태. 허용 enum은 CHECK 참조 |
| end_reason | TEXT · 선택(null) | 없음 | user_end/service_end/suspend/permission_lost 등 관찰한 종료 이유 |

### 8.4. route_point

위치 수신 이력. append-only; 어댑터가 원본·정규화 값을 분리.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| point_id | TEXT · 필수 | 없음 | 수신 위치 기록 ID |
| segment_id | TEXT · 필수 | 없음 | 위치 기록 구간 ID |
| sequence | INTEGER · 필수 | 없음 | 구간 안 수신 순번. 위치 시각과 별개 |
| provider | TEXT · 필수 | 없음 | 원천 또는 생성 제공자 식별자 |
| provider_epoch | TEXT · 선택(null) | 없음 | 제공자 event ID의 유효 범위. 모르면 event ID와 함께 null |
| source_event_id | TEXT · 선택(null) | 없음 | 제공자가 중복 식별을 보장하는 ID. 임의 좌표 해시를 넣지 않음 |
| raw_timestamp | TEXT · 선택(null) | 없음 | 변환 전 제공자 timestamp의 문자열 표현 |
| source_time_unit | TEXT · 필수 | 없음 | epoch_ms/epoch_s/elapsed_ms/other/unknown |
| observed_at | INTEGER · 선택(null) | 없음 | 검증된 측정 시각의 UTC 변환. 의미를 모르면 null |
| received_at | INTEGER · 필수 | 없음 | 앱이 위치를 수신한 UTC 시각 |
| received_elapsed_ms | INTEGER · 필수 | 없음 | 수신 시 단조 시각, ms. 같은 clockScopeId에서만 비교 |
| clock_scope_id | TEXT · 필수 | 없음 | 단조 시계를 함께 비교할 프로세스 실행 범위 ID |
| latitude | REAL · 선택(null) | 없음 | 정규화한 WGS84 위도, degree. 범위 밖 원본은 raw 컬럼에 보존 |
| longitude | REAL · 선택(null) | 없음 | 정규화한 WGS84 경도, degree |
| raw_coordinates_json | TEXT · 필수 | 없음 | 수신 원본 좌표만 보존한 JSON. 다른 개인정보는 복제하지 않음 |
| accuracy_meters | REAL · 선택(null) | 없음 | 제공자가 의미를 보장한 수평 정확도, m. 미지원이면 null |
| provider_confidence_json | TEXT · 선택(null) | 없음 | 제공자 confidence 원값과 단위·어댑터 버전 |
| quality_status | TEXT · 필수 | 없음 | valid/stale/invalid/unknown. valid만 안내·수집 후보 |
| route_revision | INTEGER · 필수 | 없음 | 내비 경로 변경 버전. GPS 수신마다 증가하지 않음 |

### 8.5. source_evidence

출처의 고정 revision. 콘텐츠 설치기가 검토 후 등록.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| source_revision_id | TEXT · 필수 | 없음 | 내용 해시로 고정한 출처 revision 참조 |
| source_id | TEXT · 필수 | 없음 | 같은 출처 항목의 안정된 내부 ID |
| provider | TEXT · 필수 | 없음 | 원천 또는 생성 제공자 식별자 |
| source_url | TEXT · 필수 | 없음 | 원문 URL. 생성 API가 임의로 따라가는 입력이 아님 |
| title | TEXT · 필수 | 없음 | 사용자에게 표시할 제목 |
| fetched_at | INTEGER · 필수 | 없음 | 이 revision을 최초 수집한 UTC 시각 |
| source_updated_at | INTEGER · 선택(null) | 없음 | 원문이 명시한 정확한 수정 시각. 모르면 null |
| source_updated_raw | TEXT · 선택(null) | 없음 | 날짜만 있는 표기 등 원문의 갱신 정보 |
| verified_at | INTEGER · 선택(null) | 없음 | 자료·매핑·대본을 검토한 UTC 시각 |
| content_hash | TEXT · 필수 | 없음 | 허용된 정규화 내용의 SHA-256. revision 동일성 확인 |
| license_json | TEXT · 필수 | 없음 | 라이선스 종류·원문 링크·자산별 조건 |
| allowed_uses_json | TEXT · 필수 | 없음 | allowGeneration/allowDisplay/allowDistribution boolean 및 검토 근거 |
| attribution | TEXT · 필수 | 없음 | 배포·표시에 필요한 출처 문구 |
| review_status | TEXT · 필수 | 없음 | draft/verified/rejected |

### 8.6. region

동일 지역의 안정된 ID. 카드 중복 기준.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| region_id | TEXT · 필수 | 없음 | 동일 지역을 나타내는 안정된 내부 ID |
| name | TEXT · 필수 | 없음 | 표시 이름 |

### 8.7. region_revision

지역 코드·원천·판정 정책의 고정 revision. 과거 통과 근거 보존.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| region_revision_id | TEXT · 필수 | 없음 | 판정 당시의 지역 코드·경계 데이터·정책 revision |
| region_id | TEXT · 필수 | 없음 | 동일 지역을 나타내는 안정된 내부 ID |
| code | TEXT · 필수 | 없음 | 선정한 법정 시·군·구 코드 |
| dataset_version | TEXT · 필수 | 없음 | 지역 판정 원천의 버전 |
| name | TEXT · 필수 | 없음 | 표시 이름 |
| source_revision_id | TEXT · 필수 | 없음 | 내용 해시로 고정한 출처 revision 참조 |
| policy_json | TEXT · 필수 | 없음 | DATA-11 RegionPolicy. 검증 전 자동 적립 금지 |
| verified_at | INTEGER · 선택(null) | 없음 | 자료·매핑·대본을 검토한 UTC 시각 |

### 8.8. place

장소와 좌표 근거. 좌표 변경은 검토하고 관련 콘텐츠 revision 보존.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| place_id | TEXT · 필수 | 없음 | 동일 장소의 내부 ID |
| region_id | TEXT · 선택(null) | 없음 | 동일 지역을 나타내는 안정된 내부 ID |
| name | TEXT · 필수 | 없음 | 표시 이름 |
| aliases_json | TEXT · 필수 | '[]' | 별칭 문자열 배열 |
| latitude | REAL · 선택(null) | 없음 | 정규화한 WGS84 위도, degree. 범위 밖 원본은 raw 컬럼에 보존 |
| longitude | REAL · 선택(null) | 없음 | 정규화한 WGS84 경도, degree |
| coordinate_source_revision_id | TEXT · 선택(null) | 없음 | 좌표를 확인한 출처 revision |
| landmark_policy_json | TEXT · 선택(null) | 없음 | DATA-11 LandmarkPolicy. null이면 자동 랜드마크 제외 |

### 8.9. place_external_id

제공자 ID를 내부 장소로 연결. 이름만으로 자동 동일시하지 않음.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| provider | TEXT · 필수 | 없음 | 원천 또는 생성 제공자 식별자 |
| external_id | TEXT · 필수 | 없음 | 제공자 안에서의 장소 ID |
| place_id | TEXT · 필수 | 없음 | 동일 장소의 내부 ID |
| verified_at | INTEGER · 필수 | 없음 | 자료·매핑·대본을 검토한 UTC 시각 |

### 8.10. fact_revision

사실 내용의 고정 버전. 안정된 factId로 반복 방지.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| fact_revision_id | TEXT · 필수 | 없음 | 특정 내용·근거에 고정된 사실 revision |
| fact_id | TEXT · 필수 | 없음 | 표현이 달라도 같은 사실을 묶는 ID |
| version | INTEGER · 필수 | 없음 | 해당 대상의 증가 revision. DB/API 버전과 별개 |
| statement | TEXT · 필수 | 없음 | 검증한 사실 또는 전승의 명제 |
| kind | TEXT · 필수 | 없음 | 모델별 구분 enum. SQL CHECK와 DATA-07 참조 |
| verified_at | INTEGER · 필수 | 없음 | 자료·매핑·대본을 검토한 UTC 시각 |

### 8.11. fact_source

사실 revision과 출처 revision의 다대다 연결.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| fact_revision_id | TEXT · 필수 | 없음 | 특정 내용·근거에 고정된 사실 revision |
| source_revision_id | TEXT · 필수 | 없음 | 내용 해시로 고정한 출처 revision 참조 |

### 8.12. story

이야기의 안정된 정체성. 카드 지급 단위.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| story_id | TEXT · 필수 | 없음 | 대본 버전과 무관한 이야기 ID. 카드 유일성 기준 |
| topic_id | TEXT · 필수 | 없음 | 주제 분류 ID |
| title | TEXT · 필수 | 없음 | 사용자에게 표시할 제목 |
| core_question | TEXT · 필수 | 없음 | 이야기의 핵심 질문 |
| interest_tags_json | TEXT · 필수 | '[]' | 관심사 태그 문자열 배열 |
| place_id | TEXT · 선택(null) | 없음 | 동일 장소의 내부 ID |
| region_id | TEXT · 선택(null) | 없음 | 동일 지역을 나타내는 안정된 내부 ID |

### 8.13. story_version

발행된 대본 버전. 내용 수정은 새 버전 추가.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| story_version_id | TEXT · 필수 | 없음 | 청취 완료를 합산할 대본 버전 ID |
| story_id | TEXT · 필수 | 없음 | 대본 버전과 무관한 이야기 ID. 카드 유일성 기준 |
| version | INTEGER · 필수 | 없음 | 해당 대상의 증가 revision. DB/API 버전과 별개 |
| content_hash | TEXT · 필수 | 없음 | 허용된 정규화 내용의 SHA-256. revision 동일성 확인 |
| created_at | INTEGER · 필수 | 없음 | 해당 레코드를 최초 커밋한 UTC 시각 |
| verified_at | INTEGER · 선택(null) | 없음 | 자료·매핑·대본을 검토한 UTC 시각 |
| valid_until | INTEGER · 선택(null) | 없음 | 변동 정보의 유효 기한 UTC. null은 영구 최신 보장이 아님 |
| estimated_duration_ms | INTEGER · 필수 | 없음 | 예상 음성 길이, ms. 실제 재생 시간과 별개 |
| status | TEXT · 필수 | 없음 | 모델별 처리 상태. 허용 enum은 CHECK 참조 |

### 8.14. paragraph

버전에 귀속된 일반 대본 문단. 사용자 개인 답변을 저장하지 않음.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| paragraph_id | TEXT · 필수 | 없음 | 고정된 대본 버전의 문단 ID |
| story_version_id | TEXT · 필수 | 없음 | 청취 완료를 합산할 대본 버전 ID |
| ordinal | INTEGER · 필수 | 없음 | 0부터 시작하는 버전 내 문단 순서 |
| required | INTEGER · 필수 | 1 | 이야기 완료에 필수이면 1, 선택 문단이면 0 |
| text | TEXT · 필수 | 없음 | 검토된 일반 대본. 개인 대화 원문 저장 금지 |
| audio_ref | TEXT · 선택(null) | 없음 | 권리가 확인되고 실제 존재하는 준비 음성 참조 |
| estimated_duration_ms | INTEGER · 필수 | 없음 | 예상 음성 길이, ms. 실제 재생 시간과 별개 |

### 8.15. paragraph_fact

문단별 사실·전승의 근거 연결.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| paragraph_id | TEXT · 필수 | 없음 | 고정된 대본 버전의 문단 ID |
| fact_revision_id | TEXT · 필수 | 없음 | 특정 내용·근거에 고정된 사실 revision |

### 8.16. conversation_turn

원문 정리 뒤에도 유지되는 대화 상태·식별자.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| turn_id | TEXT · 필수 | 없음 | 접수된 말하기 한 번의 영구 ID |
| session_id | TEXT · 필수 | 없음 | Coordinator 실행 세대 ID |
| created_at | INTEGER · 필수 | 없음 | 해당 레코드를 최초 커밋한 UTC 시각 |
| utterance_finalized_at | INTEGER · 선택(null) | 없음 | 최종 인식 텍스트 확정·커밋 시각 |
| closed_at | INTEGER · 선택(null) | 없음 | 세션 또는 대화 상태를 닫은 UTC 시각 |
| turn_version | INTEGER · 필수 | 1 | 요약 입력 snapshot의 버전 |
| lifecycle | TEXT · 필수 | 없음 | open/closed/cancelled/interrupted/failed |
| frozen | INTEGER · 필수 | 0 | 입력·재생 결과 확정 후 1. 동결 뒤 새 발화는 새 turn |
| summary_state | TEXT · 필수 | 없음 | no_text/pending/running/retry_wait/saved/blocked |
| attempt_count | INTEGER · 필수 | 0 | 해당 생성·요약의 누적 시도 수 |
| retry_batch_count | INTEGER · 필수 | 0 | 현재 자동 요약 재시도 묶음의 횟수 |
| last_attempt_at | INTEGER · 선택(null) | 없음 | 마지막 요약 시도 UTC 시각 |
| next_retry_at | INTEGER · 선택(null) | 없음 | 재시도 예정 UTC 시각. OS 실행 보장 아님 |
| summary_saved_at | INTEGER · 선택(null) | 없음 | 요약 트랜잭션의 커밋 시각 |
| last_error_code | TEXT · 선택(null) | 없음 | 최근 정규화 오류 코드. 원문/비밀값 없음 |

### 8.17. pending_conversation

요약 전 암호화 payload. REC-04 성공 시에만 정리.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| turn_id | TEXT · 필수 | 없음 | 접수된 말하기 한 번의 영구 ID |
| payload_version | INTEGER · 필수 | 없음 | 암호화 본문이 표현하는 turnVersion |
| ciphertext | BLOB · 필수 | 없음 | AES-GCM 암호문과 인증 태그. 평문 복제 없음 |
| nonce | BLOB · 필수 | 없음 | 암호화마다 새로운 12-byte nonce |
| key_version | TEXT · 필수 | 없음 | Keystore 키 버전 참조 |
| payload_hash | TEXT · 필수 | 없음 | 평문 동일성을 검사하는 비밀키 기반 HMAC |
| created_at | INTEGER · 필수 | 없음 | 해당 레코드를 최초 커밋한 UTC 시각 |
| updated_at | INTEGER · 필수 | 없음 | 이 가변 레코드를 마지막 커밋한 UTC 시각 |

### 8.18. conversation_summary

성공적으로 커밋된 영구 요약. 작업 실패 상태는 Turn에서 관리.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| summary_id | TEXT · 필수 | 없음 | 영구 요약 ID |
| turn_id | TEXT · 필수 | 없음 | 접수된 말하기 한 번의 영구 ID |
| turn_version | INTEGER · 필수 | 없음 | 요약 입력 snapshot의 버전 |
| summary_text | TEXT · 필수 | 없음 | 요약된 대화 내용. 원문 전문 아님 |
| provenance_json | TEXT · 필수 | 없음 | DATA-09 주장·검증 사실·완료/중단 응답 구분 |
| summary_schema_version | TEXT · 필수 | 없음 | 요약 결과 구조 버전 |
| model_id | TEXT · 필수 | 없음 | 실제 생성에 사용한 모델 ID |
| prompt_version | TEXT · 필수 | 없음 | 생성 또는 요약 프롬프트 버전 |
| created_at | INTEGER · 필수 | 없음 | 해당 레코드를 최초 커밋한 UTC 시각 |

### 8.19. playback_attempt

재생 시도 이력. terminal 상태 이후 결과 변경 금지.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| attempt_id | TEXT · 필수 | 없음 | 문단/음악 재생 시도 ID |
| session_id | TEXT · 필수 | 없음 | Coordinator 실행 세대 ID |
| kind | TEXT · 필수 | 없음 | 모델별 구분 enum. SQL CHECK와 DATA-07 참조 |
| paragraph_id | TEXT · 선택(null) | 없음 | 고정된 대본 버전의 문단 ID |
| turn_id | TEXT · 선택(null) | 없음 | 접수된 말하기 한 번의 영구 ID |
| transient_paragraph_key | TEXT · 선택(null) | 없음 | 암호화 임시 본문 안의 문단 키. 본문 삭제 후 메타데이터만 유지 |
| asset_id | TEXT · 선택(null) | 없음 | AssetManifest의 음악·고정 안내·준비 음성 ID |
| state | TEXT · 필수 | 없음 | prepared/playing/completed/interrupted/failed |
| started_at | INTEGER · 선택(null) | 없음 | 시작을 관찰한 UTC 시각 |
| completed_at | INTEGER · 선택(null) | 없음 | 정상 완료를 확인한 UTC 시각. 중단에는 null |
| interrupted_at | INTEGER · 선택(null) | 없음 | 중단을 관찰한 UTC 시각 |
| ended_elapsed_ms | INTEGER · 선택(null) | 없음 | 종료 단조 시각, ms. started와 같은 scope일 때만 duration 계산 |
| started_elapsed_ms | INTEGER · 선택(null) | 없음 | 출력 시작 단조 시각, ms |
| clock_scope_id | TEXT · 필수 | 없음 | 단조 시계를 함께 비교할 프로세스 실행 범위 ID |
| position_ms | INTEGER · 선택(null) | 없음 | 음악 플레이어 위치, ms. TTS 정확한 seek 용도로 사용하지 않음 |
| interruption_reason | TEXT · 선택(null) | 없음 | user_stop/focus_lost/suspend/error 등의 중단 이유 |

### 8.20. playback_checkpoint

이동별 최근 복귀 포인터. 전체 청취 이력의 대체가 아님.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| trip_id | TEXT · 필수 | 없음 | 이동 기록 ID. 다른 모델에서는 Trip 참조 |
| session_id | TEXT · 필수 | 없음 | Coordinator 실행 세대 ID |
| state_revision | INTEGER · 필수 | 없음 | 수용된 상태 변경 순번. 시각 대신 최신 상태 비교 |
| mode | TEXT · 필수 | 없음 | checkpoint 종류: story/music/idle |
| paragraph_id | TEXT · 선택(null) | 없음 | 고정된 대본 버전의 문단 ID |
| last_completed_paragraph_id | TEXT · 선택(null) | 없음 | 복귀 이야기와 같은 버전에서 마지막 완료한 문단 |
| asset_id | TEXT · 선택(null) | 없음 | AssetManifest의 음악·고정 안내·준비 음성 ID |
| music_position_ms | INTEGER · 선택(null) | 없음 | 재개할 음악 위치, ms |
| play_intent | INTEGER · 필수 | 없음 | 청취 지속 의도 0/1. 복구 시 자동 재생 권한은 아님 |
| blockers_json | TEXT · 필수 | 없음 | 출력을 막는 이유 문자열 집합 |
| updated_at | INTEGER · 필수 | 없음 | 이 가변 레코드를 마지막 커밋한 UTC 시각 |

### 8.21. collection_record

지역·이야기 카드. 대상당 1개.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| collection_id | TEXT · 필수 | 없음 | 획득 카드 ID |
| collection_type | TEXT · 필수 | 없음 | region/story |
| region_id | TEXT · 선택(null) | 없음 | 동일 지역을 나타내는 안정된 내부 ID |
| story_id | TEXT · 선택(null) | 없음 | 대본 버전과 무관한 이야기 ID. 카드 유일성 기준 |
| story_version_id | TEXT · 선택(null) | 없음 | 청취 완료를 합산할 대본 버전 ID |
| rule_version | TEXT · 필수 | 없음 | 획득 판정 규칙 버전. 카드 중복 키에는 포함하지 않음 |
| earned_at | INTEGER · 필수 | 없음 | 획득 조건이 충족된 사건의 UTC 시각 |
| created_at | INTEGER · 필수 | 없음 | 해당 레코드를 최초 커밋한 UTC 시각 |

### 8.22. collection_proof

카드의 실제 위치 또는 문단 완료 근거.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| collection_id | TEXT · 필수 | 없음 | 획득 카드 ID |
| proof_key | TEXT · 필수 | 없음 | 문단 또는 지역 판정 샘플을 구별하는 근거 키 |
| attempt_id | TEXT · 선택(null) | 없음 | 문단/음악 재생 시도 ID |
| point_id | TEXT · 선택(null) | 없음 | 수신 위치 기록 ID |
| region_revision_id | TEXT · 선택(null) | 없음 | 판정 당시의 지역 코드·경계 데이터·정책 revision |

### 8.23. preference

차량 공용 명시적 설정. 과거 요약을 설정으로 자동 승격하지 않음.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| scope_id | TEXT · 필수 | 없음 | P0는 vehicle 하나. 사용자 신원/서버 인증 수단 아님 |
| keywords_json | TEXT · 필수 | '[]' | 사용자가 선택한 키워드 배열, DATA-12 |
| voice_json | TEXT · 필수 | 없음 | locale/voiceId/rate, DATA-12 |
| story_frequency_json | TEXT · 필수 | 없음 | mode/betweenStoriesMusicTracks, DATA-12 |
| location_enabled | INTEGER · 필수 | 없음 | 명시적 위치 사용 설정 0/1 |
| version | INTEGER · 필수 | 없음 | 해당 대상의 증가 revision. DB/API 버전과 별개 |
| updated_at | INTEGER · 필수 | 없음 | 이 가변 레코드를 마지막 커밋한 UTC 시각 |

### 8.24. generation_request

호출·취소·사용량 메타데이터. 원문 프롬프트·응답은 저장하지 않음.

| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |
| --- | --- | --- | --- |
| request_id | TEXT · 필수 | 없음 | 한 논리 생성 요청 ID. 같은 입력 재시도는 ID 유지 |
| session_id | TEXT · 필수 | 없음 | Coordinator 실행 세대 ID |
| turn_id | TEXT · 선택(null) | 없음 | 접수된 말하기 한 번의 영구 ID |
| task | TEXT · 필수 | 없음 | story/answer/resume/summary |
| input_hash | TEXT · 필수 | 없음 | 정규화 요청 본문의 기기 비밀키 기반 HMAC |
| schema_version | TEXT · 필수 | 없음 | API 구조 버전 |
| cancel_epoch | INTEGER · 필수 | 없음 | 관련 실시간 요청 무효화 세대. 음수가 아님 |
| route_revision | INTEGER · 선택(null) | 없음 | 내비 경로 변경 버전. GPS 수신마다 증가하지 않음 |
| turn_version | INTEGER · 선택(null) | 없음 | 요약 입력 snapshot의 버전 |
| created_at | INTEGER · 필수 | 없음 | 해당 레코드를 최초 커밋한 UTC 시각 |
| finished_at | INTEGER · 선택(null) | 없음 | 요청 terminal 처리 UTC 시각 |
| status | TEXT · 필수 | 없음 | 모델별 처리 상태. 허용 enum은 CHECK 참조 |
| attempt_count | INTEGER · 필수 | 0 | 해당 생성·요약의 누적 시도 수 |
| model_id | TEXT · 선택(null) | 없음 | 실제 생성에 사용한 모델 ID |
| provider | TEXT · 선택(null) | 없음 | 원천 또는 생성 제공자 식별자 |
| prompt_version | TEXT · 필수 | 없음 | 생성 또는 요약 프롬프트 버전 |
| error_code | TEXT · 선택(null) | 없음 | 정규화한 API 오류 코드 |
| usage_json | TEXT · 선택(null) | 없음 | inputTokens/outputTokens/latencyMs 등 사용량 메타데이터 |

