# 위치 기록·지역 판정·내비 편성 연결

현행 v0.8의 LOC-01~04, COL-01, TIME-03, REC-08과 기존 Room v1 스키마를 사용한다. UI와 데모 시나리오 데이터는 이 계층에 포함하지 않는다. 실행 검증 결과는 해당 개발 단계의 검증 기록을 따른다.

## 위치 기록

- `location/LocationNormalizer`는 원본 좌표·heading·altitude·confidence·timestamp와 수신 wall/monotonic 시각을 분리한다. NaN 등 의심 값도 JSON 문자열 원본으로 남기고 정규화 좌표는 null로 기록한다. 로그와 `toString()`에는 좌표를 출력하지 않는다.
- Pleos FusedLocation의 timestamp 원점과 confidence의 정확도 의미는 아직 검증되지 않았다. 기본 `LocationMeaningAdapter.UNKNOWN`은 수신 시각을 측정 시각으로 대체하지 않는다. 실제 콜백은 `unknown` 기록만 가능하고 자동 안내·지역 적립에는 사용하지 않는다.
- 검증된 어댑터가 측정 시각·정확도·허용 최대 정확도를 제공해야 `valid/stale/invalid`를 판정한다. 위치 신선도 10초는 명세의 개발 후보값이며 실제 이동 품질 검증이 남았다. last-known도 실제 측정 시각을 기준으로 판정한다.
- dev.19는 수신 당시 이미 소비한 측정 나이를 뺀 단조 시각 만료일을 계산한다. 예를 들어 9초 지난 표본의 남은 권한은 수신 후 1초뿐이다. 현재/예정 지역·랜드마크 선택, 생성 권한, 실제 출력 직전 모두 같은 만료일을 검사하며 원본 측정/수신 시각은 바꾸지 않는다.
- main 콜백마다 고유한 `LocationInputAuthority.ReceiptPermit`을 발급하고 정확한 point ID에 묶어 저장 대기·재시도까지 보존한다. 의심 입력·연결 오류는 즉시 철회한다. 같은 밀리초의 다른 표본, 오류 뒤 pending batch flush, 오래된 worker 콜백이 현재 위치 권한을 복구할 수 없다. 편성 문맥은 해당 표본의 불변 검증 함수를 보관하고 생성/출력 직전까지 재검사한다. 위치와 무관한 명시 관심사는 유지한다.
- `LocationRecorder`는 명시적 이동 시작과 active Coordinator session 이후 `begin()`한다. SDK 이벤트를 동일 worker queue에 전달하고 `tick()`을 최대 1초 batch deadline 및 수신 단절 검사에 맞춰 호출한다. 최대 20개 또는 1초 중 먼저 커밋하며 종료/suspend에는 즉시 flush한다. 미디어 pause는 기록 중단 사유가 아니다.
- 실제 `PetasosSessionHost`는 `RoomLocationStore.isExplicitlyEnabled()`의 vehicle preference `location_enabled=1`과 Pleos 위치 권한을 모두 확인한 경우에만 SDK 기록을 시작한다. 설정 행이 없거나 0이면 동의가 없는 상태다. 앱 시작·목록 조회·Media Play가 이 값을 자동으로 켜지 않는다. Android 위치 권한 허용과 위치 기록 설정도 서로 별개다. 실제 설정 저장 후에는 same-process `locationSettingsChanged()`로 재평가한다.
- 수신 단절 정책 `maxReceiptGapMs`에는 임의의 검증 완료 기본값을 두지 않는다. 개발 host가 후보값을 명시적으로 주입하고 실제 이동에서 점검해야 한다. 권한 거부·연속 업데이트 중단·수신 공백·단조 시각 역행은 별도 구간으로 나눈다.
- 실패한 batch는 같은 point ID로 재시도한다. `RoomLocationStore`는 동일 point retry와 제공자가 보장한 `(provider, providerEpoch, sourceEventId)`만 중복 제거한다. 동일 좌표·동일 측정 시각만으로 삭제하지 않는다. GPS 업데이트는 `session.route_revision`을 증가시키지 않는다.
- TX-04는 **중단된 구간·세션 마감과 새 세션 생성**이다. 기존 `RoomRuntimePersistence.openSession()`을 사용한다. 복구 시 이전 구간의 `ended_at`은 null이고 `interruption_detected_at`과 `last_observed_at`을 보존한다. LocationStore는 Coordinator가 만든 active session 안에서만 새 구간을 연다.

## 지역 카드와 후보

- `RegionBoundaryLookup` 자체 기본값은 NONE이며 dev.18 Host는 검토한 공식 NGII packed 경계 catalog를 주입한다. 설치·검증 실패는 NONE으로 보류한다. 실제 데이터셋 없이 근사 경계·대표점 반경으로 행정 지역을 만들지 않는다. 외부 경계 어댑터는 포함 여부와 실제 경계까지의 최소 내부 거리를 제공해야 한다.
- 기존 `region_revision.policy_json`에 datasetVersion, consecutiveSamples, minConfirmationDurationMs, boundaryMarginMeters, qualityAdapterVersion과 `coordinateReferenceSystem: "EPSG:4326"`을 확인한다. 현재 엄격한 JSON 계약의 수치는 정수이며 경계 margin은 미터 정수로 저장한다. 연속 표본은 2~100개로 제한한다. 미지원 정책은 적립을 보류한다.
- RegionRevision과 해당 SourceEvidence 모두 검토 완료이고 display/distribution 허가가 현재 유효해야 한다. 단순 FK 존재만으로 승인하지 않는다.
- 같은 구간의 **연속 저장행**을 확인한다. 중간 unknown/invalid 행을 건너뛰지 않는다. 동일 clock scope, 증가하는 측정·수신 시각, 확인 시간, 품질 어댑터 버전, 데이터셋·좌표계, `경계 margin + 위치 정확도`를 모두 만족해야 한다. 겹친 지역이 여러 개면 임의로 하나를 고르지 않는다.
- 위치 batch·지역 카드·확인에 사용한 모든 point proof는 하나의 DB 트랜잭션으로 기록한다. 안정된 regionId의 카드는 한 번만 적립하며 지역 코드/정책 revision 변경으로 재지급하거나 이전 proof를 덮어쓰지 않는다.
- `currentRegionStories()`는 현재 지역의 최신 published version 후보를 읽고 기존 콘텐츠 설치기의 현재 출처 허가·hash·필수 문단 검사를 다시 수행한다. 완료한 이야기의 안정된 story ID와 완료한 문단에 포함된 안정된 fact ID가 겹치는 자동 후보를 제외한다. 중단·준비 상태는 완료로 취급하지 않는다. 이 배제는 자동 편성에만 적용하며 사용자의 설치 이야기 명시 재생은 계속 가능하다.
- `ContentPlanner`는 현재 지역 이야기 우선 후보와 경로 의존성, 단일 랜드마크 후보, terminal 만료를 다룬다. 지역 변경 자체로 진행 중 이야기를 중단시키지 않는다. 랜드마크의 접근 방향·거리 변화·통과 판정은 **아직 실제 검토 정책과 별도 approach adapter가 필요하다**. 현재 LocationPlaybackBridge는 일반 지역 이야기만 자동 공급한다.
- LocationPlaybackBridge는 OfferStory/ClearOfferedStory를 내보내고 RuntimeContentAuthority를 구현한다. SDK 직전에 offer ID·현재 지역·유효 위치·마지막 실제 수신 시각·말하기 상태·문단을 재검사한다. 해당 offer 철회는 큐의 clear 명령보다 먼저 메모리 권한을 해제한다. 실제 시작 확인 이후에는 core가 해당 gate를 소비하므로 지역 변경으로 이미 시작한 일반 이야기의 다음 문단을 끊지 않는다.
- ContentCheckedResumePlanner는 Coordinator가 제시한 checkpoint/music 후보만 위임하며 선택 응답 직전에 현재 콘텐츠 허가를 다시 검사한다. 이 wrapper는 서버 LLM 복귀 플래너를 구현하거나 가상의 응답을 만들지 않는다.

## 읽기 전용 내비 어댑터

`PleosNavigationSource`는 공식 `ai.pleos.playground:NaviHelper:3.0.5` 및 전이 의존성 `navi-data:3.0.4`의 실제 AAR 시그니처와 대조했다. 권한은 `pleos.car.permission.NAVI_ROUTE`만 사용한다. 초기화 연결 콜백 후 listener 등록과 조회, 종료 시 listener 제거와 release 순서를 따른다. [의존성](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions), [수명주기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release)

`getRouteStateInfo`, `getCurrentLocationInfo`, `getDestinationInfo`만 호출한다. 경로 설정·취소·옵션 변경·지도 요청 권한과 명령은 사용하지 않는다. 연결 실패/권한 거부/읽기 실패는 명시적 상태로 전달하고 현재 위치 모드로 돌아간다. callback 등록 성공을 실제 경로 데이터 수신 성공으로 표시하지 않는다.

`NavigationRevisionTracker`는 route ID·목적지 identity뿐 아니라 경로 시작·목적지/옵션/경유지 변경·재탐색 완료에서도 기존 경로 의존성을 철회한다. 변경 후에는 완료 콜백과 새 active state/identity를 기다린다. 취소·오류 이후 늦은 완료나 identity는 복구할 수 없으며 명시적인 새 `onRouteStarted` 또는 새 source가 필요하다. 경유지 도착 자체, 현재 좌표·주소·heading·route index, 남은 거리/시간 변화는 경로 변경으로 취급하지 않는다. 목적지 identity는 메모리 digest만 보관하며 이벤트에 목적지명·좌표·digest를 노출하지 않는다. 시뮬레이션은 별도 flag로 보존하되 `observedRouteDependency`는 null이므로 실제 이동의 예측 권한으로 쓰지 않는다. [콜백](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners)

Host의 내비 ingress revision도 main에서 즉시 증가한다. worker가 아직 이전 이벤트를 처리 중이어도, 이전 revision에 묶인 지역 생성·출력 권한은 최종 검사에서 거절된다. SDK 공개 콜백에 요청 상관관계/순서 보장이 없어 완료를 받지 못하면 경로 의존성을 보류하며 임의로 복구하지 않는다.

내비 routeRevision의 영구 session 기록은 직렬 host의 `RoomNavigationStore.apply(sessionId, snapshot)` 경계를 따른다. active session에서 단조 증가만 허용하며 이전 revision/닫힌 session의 콜백은 false로 거부한다. route가 없어져도 숫자 revision은 유지하고 routeDependency=null을 메모리에 별도로 전달한다. 새 SDK 객체는 `readRevision()` 값에서 시작한다. `isCurrent(sessionId, routeDependency)`는 경로 의존 후보/생성에만 사용한다. 들은 문단에 관한 일반 질문은 기존대로 routeRevision=null이고 내비 변경만으로 폐기하지 않는다. 서버 story/resume 생성 API 연결은 별도 작업이다.

## Service host 연결

Service 생성·MediaBrowser 연결·라이브러리 조회는 trip/session을 만들거나 위치·내비를 시작하지 않는다. 명시적 Play 또는 보이는 화면의 Talk에서 runtime이 필요해질 때만, 앱 복구 once gate를 통과한 뒤 새 active session을 커밋한다. 그 뒤 읽기 전용 내비와 동의가 있는 위치 기록을 연결한다. Talk만으로 만든 세션은 이야기·음악을 자동 재생하지 않는다. 저장소 복구와 신규 세션 생성의 순서를 `SummaryBackground.ensureRecovered()`로 직렬 보장한다.

SDK 콜백은 session fence를 확인하고 단일 runtime worker queue에서 처리한다. 미디어 Stop은 입력·재생을 중지하지만 활성 이동의 위치 기록은 유지한다. 세션 종료는 SDK fence를 먼저 내린 다음 pending 위치를 flush하고 구간을 닫는다. SDK/DB 실패가 생기면 자동 후보 권한을 즉시 철회하고 실패한 batch의 ID를 보존한다. 재시도 중인 batch가 커밋되어도 중단된 SDK 입력을 유효한 실시간 위치로 되살리지 않는다.

현재 host의 실제 품질 어댑터는 UNKNOWN이다. dev.18에서 공식 NGII 경계 lookup을 연결했지만 지역 방문 정책의 verified_at은 NULL이다. 코드 연결이 완료되어도 실환경의 자동 지역 안내/지역 카드가 활성화되지는 않는다. 후보 공급, 복귀 후보 재검사, 실제 첫 출력 확인 후 후보 소비는 기존 PlaybackRuntime 포트로 연결한다. 실제 출처 허가 및 SDK 직전 자동 후보 권한 검사를 통과해야 출력할 수 있다.

2026-11-08 후속: `ReviewedPolygonBoundaryLookup`에 검토된 EPSG:4326 Polygon/MultiPolygon을 주입하는 기하 엔진을 추가했다. 경계 거리는 최단 거리의 보수적 하한이며 0m는 margin/accuracy가 0이어도 확정에 쓰지 않는다. 이 사전 준비 당시에는 host의 NONE을 유지했다. 이후 dev.18에서 공식 ZIP/PDF를 검토하고 실제 경계를 주입했다([설치 기록](2026-11-08-official-region-boundary-import.md)). VWorld API의 domain 없는 실조회는 성공했고 원본은 저장하지 않았다. 개별 이용허락이 표시된 공식 ZIP/PDF 확보 이후 지역 코드·EPSG:5179 변환·정책을 검토한다. [후속 구현/검증](2026-11-08-official-region-boundary-preparation.md), [공식 자료](../reference/regions/README.md)를 따른다.

내비 `TripSummary.routePoints`를 도로 polyline이나 실제 이동 이력으로 가정하지 않는다. [기존 공식 SDK 조사](../reference/pleos/guides/06-navihelper.md)

## 검증 범위

JVM 테스트는 품질 분류·batch/재시도·구간 단절·지역 경계 확인·후보 만료·내비 revision·출력 직전 freshness·복귀 후보 검사를 다룬다. `location-storage` 기기 probe는 실제 Room과 합성 좌표/경계만 사용하며 임시 DB를 삭제한다. `navigation` probe는 실제 SDK의 읽기 연결/콜백/명시적 fallback/해제를 확인하고 목적지를 설정하지 않는다.

실제 가거도 설치 자료의 좌표·지역·랜드마크 정책은 null 그대로다. 공식 경계의 기준일·방문 정책 검토, 실제 위치 품질 어댑터 검증, 경로 접근 방향/통과 정책과 실이동 시험 없이 자동 지역 안내·수집을 완료한 것으로 보고하지 않는다.
