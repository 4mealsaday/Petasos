# 용인·천안 지역 계층 검토 초안

확인일: 2026-11-09. 이 문서는 기존 NGII 경계에 적용할 소규모 계층 catalog의 근거와 구현 범위를 기록한다. 조사 후 아래 범위의 코드·JVM 회귀 테스트를 작성했으며 빌드와 기기 검증은 별도 수행한다. 제품 DB와 위치 정책 catalog는 이 작업에서 수정하지 않았다. 관계 검토는 위치 품질·연속 표본·경계 여유의 실측 검수를 대신하지 않는다.

## 기존 명세와 문제

v0.8 `COL-01`은 법정 시·군·구 코드와 실제 연속 위치 증명을 카드 단위로 사용한다. `Petasos_조사_결정기록.md`의 용인·천안 등 5개 소재 묶음은 5개 카드와 같지 않으며 실제 통과한 코드로만 적립한다고 명시한다. 부모 도시와 일반구가 겹칠 때 어느 하나를 카드로 선택하는지는 별도로 명시되지 않았다. 아래 leaf 하나 선택은 이 조건을 유지하기 위한 구현 정책 제안이다.

현재 `PackedRegionBoundaryLookup`은 원자료의 모든 포함 경계를 반환한다. `RoomLocationStore.confirmAndCollect`와 `RegionConfirmation.confirmed`는 각각 `singleOrNull()`을 호출하므로 도시와 일반구에 동시에 포함되는 정상 위치도 거부된다. 원자료에서 도시를 삭제하거나 첫 결과를 고르면 결과가 데이터 순서에 좌우되며 기하 오류와 실제 행정 계층을 구분할 수 없다.

원본 264행은 그대로 유지한다. 용인·천안 5개 관계만 별도 검토 catalog에 넣고, 관계를 모르는 다른 중첩은 계속 거부한다.

## 공개 공식 근거

| 근거 | 확인한 내용 | 범위 및 한계 |
| --- | --- | --- |
| [용인시 공식 연혁](https://www.yongin.go.kr/home/yiIf/yiIfProd/yiIfProd02.jsp) | 2005-10-31 처인구·기흥구·수지구 설치. 이후 연혁에서도 3구 체제가 이어짐 | 페이지 본문 직접 열람. 시 이름과 구 이름 사이의 명시적 관계 근거 |
| [용인시 기본현황](https://www.yongin.go.kr/home/yiIf/yiIfCurSta/yiIfCurSta01/yiIfCurSta01_01.jsp) | 2026년 9월 행정기구에 일반구 3개가 표시됨 | 페이지 본문 직접 열람. 앞선 연혁의 세 구가 자치구와 다른 일반구임을 대조 |
| [중앙선거관리위원회 제공 시행규칙 개정이유](https://law.go.kr/LSW/lsRvsRsnListP.do?chrClsCd=010202&lsId=006046&lsRvsGubun=all) | 2008-07-01 시행 개정은 천안시 구 설치 조례에 따른 동남구·서북구 분구를 명시함 | 국가법령정보센터 본문 직접 열람. 천안시와 두 구의 명시적 관계 근거 |
| [천안시 행정기구 및 정원 조례, 2021-04-15 연혁](https://www.law.go.kr/LSW/ordinInfoP.do?ordinSeq=1589417) | 제2조의 하부행정기관에 동남구와 서북구가 명시됨 | 공식 검색 본문 확인. 직접 열람 페이지는 동적 본문이 생략되어 연혁 식별정보만 확인됨 |
| [천안시 행정구역 설치 및 소재지 등에 관한 조례](https://www.law.go.kr/ordinInfoP.do?ordinId=2124956) | 현행 식별정보는 제2940호, 2026-08-03 시행. 제2조는 구 명칭·관할구역을 별표1에 둠 | 현행 식별정보 직접 확인, 조문은 공식 검색 본문 확인. 별표1 원본은 이번 조사에서 직접 열람하지 못함 |
| [충청남도 2020 도정백서](https://www.chungnam.go.kr/downloadUtil.do?filename=2020_whitebook.pdf) | 공식 검색 본문은 천안 동남·서북을 일반구로 명시하고 설치 승인·개청일을 기재함 | 검색 본문만 확인. 원 PDF URL은 현재404이므로 파일 회수·SHA 확인 완료로 표시하지 않음 |

두 도시의 관계를 확인하는 데 신규 API 등록은 필요하지 않다. 전국 확장에는 [행정안전부 법정동코드 API](https://www.data.go.kr/data/15077871/openapi.do)의 명시적 `locathigh_cd` 또는 [행정표준코드관리시스템](https://www.code.go.kr/stdcode/regCodeL.do)의 상위지역코드 포함 공개 자료를 별도로 고정할 수 있다. 이번 초안은 API를 호출하거나 해당 관계 행을 내려받은 결과가 아니다.

## 정확한 데이터 결합

아래 ID는 기존 앱 asset의 실제 행을 읽어 대조했다. 부모 관계는 앞의 공식 자료에서 확인하며, 코드 접두부나 면적·이름 유사성으로 관계를 생성하지 않는다.

- manifest asset: `regions/ngii-2026-09-21-v1/manifest.json`
- manifest SHA-256: `3e2117159aa65c6c8f69826f534a8065d1247497039827d1b638f77d580f1bf9`
- datasetVersion: `ngii-d7a3751bd89f-wgs84-stream-v1`
- CRS: `EPSG:4326`
- 아래 7개 revision은 모두 geometry `ready`다. 위치 확인 정책 검수 상태는 별도다.

| 코드 | 원본 이름 | regionId | regionRevisionId |
| --- | --- | --- | --- |
| 41460 | 용인시 | kr-sigungu-41460 | kr-sigungu-41460-ngii-d7a3751bd89f-wgs84-stream-v1 |
| 41461 | 처인구 | kr-sigungu-41461 | kr-sigungu-41461-ngii-d7a3751bd89f-wgs84-stream-v1 |
| 41463 | 기흥구 | kr-sigungu-41463 | kr-sigungu-41463-ngii-d7a3751bd89f-wgs84-stream-v1 |
| 41465 | 수지구 | kr-sigungu-41465 | kr-sigungu-41465-ngii-d7a3751bd89f-wgs84-stream-v1 |
| 44130 | 천안시 | kr-sigungu-44130 | kr-sigungu-44130-ngii-d7a3751bd89f-wgs84-stream-v1 |
| 44131 | 동남구 | kr-sigungu-44131 | kr-sigungu-44131-ngii-d7a3751bd89f-wgs84-stream-v1 |
| 44133 | 서북구 | kr-sigungu-44133 | kr-sigungu-44133-ngii-d7a3751bd89f-wgs84-stream-v1 |

catalog 관계 초안은 다음 5행이다. 각 행의 child/parent regionId와 revisionId를 모두 앞 표의 실제 값으로 고정한다. runtime은 코드 문자열을 잘라 ID를 만들지 않는다.

| child | parent | relation | 관계 근거 |
| --- | --- | --- | --- |
| 처인구 / 41461 | 용인시 / 41460 | non_autonomous_district_of_city | 용인시 공식 연혁 + 기본현황 |
| 기흥구 / 41463 | 용인시 / 41460 | non_autonomous_district_of_city | 용인시 공식 연혁 + 기본현황 |
| 수지구 / 41465 | 용인시 / 41460 | non_autonomous_district_of_city | 용인시 공식 연혁 + 기본현황 |
| 동남구 / 44131 | 천안시 / 44130 | non_autonomous_district_of_city | 중앙선관위 제공 개정이유 + 천안 하부행정기관 조례 + 충남 공식 백서 검색 본문 |
| 서북구 / 44133 | 천안시 / 44130 | non_autonomous_district_of_city | 중앙선관위 제공 개정이유 + 천안 하부행정기관 조례 + 충남 공식 백서 검색 본문 |

패키징한 `regions/reviewed-hierarchy-v1.json`은 이 5개 관계만 포함하며 SHA-256은 `cf428d289dd504b3b8d1d481ea4e80f789a1db7e531ebd06ba749422a3971e5b`다. 천안 relation의 실제 asset 근거는 직접 열람 가능한 중앙선관위 기록과 하부행정기관 조례 공식 검색 본문이며, 회수 불가한 도정백서 PDF를 원문 hash 근거로 사용하지 않았다.

권장 별도 catalog 필드는 `formatVersion`, `datasetVersion`, `manifestSha256`, `reviewedAt`, `evidence`, `relations`이다. evidence에는 실제 URL, 발행/시행/확인일의 구분, 확인한 주장과 직접 열람 또는 검색 본문 여부를 보관한다. 아직 회수하지 않은 원문 bytes의 SHA나 정확한 법적 경계 효력일을 만들어 넣지 않는다. 배포 catalog bytes의 SHA는 코드에서 고정한다. 기존 manifest와 source·region revision ID는 바꾸지 않는다.

## 결정적 선택과 카드

1. 원시 lookup은 계속 모든 포함 경계를 반환하고, 훼손·철회·quarantine이 관련되면 전체 거부하는 기존 규칙을 유지한다.
2. 검토 catalog에 있는 정확한 revision 관계만 사용한다. 모든 포함 경계가 하나의 검토된 조상 경로에 놓이고 가장 깊은 항목이 유일할 때만 그 leaf를 선택한다. 순서와 무관하게 같은 결과여야 한다.
3. 형제 구끼리의 중첩, 관계가 없는 경계와의 중첩, 잘못된 dataset/revision, 순환·다중 부모는 거부한다. 검토된 부모 도시만 남고 자식이 빠진 상태에서는 부모를 카드 대체값으로 사용하지 않는다. 알려진 자식이 반환되었는데 필수 부모 기하가 빠져도 정상 계층으로 승격하지 않는다.
4. 선택된 leaf의 검수 정책으로 기존 연속 표본·시간·경계 여유·정확도를 그대로 검사한다. `RoomLocationStore`와 `RegionConfirmation`이 같은 selector를 사용해야 한다. 부모 관계 자체가 leaf 정책을 승인하지 않는다.
5. 카드와 collection_proof에는 leaf 한 개의 기존 regionId/revisionId만 기록한다. 부모 카드를 함께 발급하거나 기존 증명을 다시 써서 바꾸지 않는다. rule_version은 새 선택 규칙을 식별하도록 새 값으로 기록할 수 있으며 schema 확장은 필요 없다.

## 부모 이야기는 별도 eligibility

확정된 기흥구에서 용인 이야기를 고를 수 있다는 것은 용인 카드까지 적립했다는 뜻이 아니다. `story.region_id`와 `story_region_binding`을 leaf로 고쳐 쓰거나 같은 이야기를 각 구에 복제하지 않는다.

권장 순수 memory 계약은 `RegionalStoryScope(passageRegionId, eligibleStoryRegionIds, hierarchyKey)`다. 실제 확정 또는 검토 forecast의 passage identity와, 재생할 이야기의 원래 region identity를 분리한다. 목록 순서는 leaf → 검토 ancestor이며 등록순·SQL 반환순을 사용하지 않는다. 상위 확장은 검토된 관계와 현재 유효한 exact region/source metadata로 제한하고, 콘텐츠 자체의 권리·지역 결합 fact 검사는 기존 reader를 그대로 통과시킨다.

현재 위치 fallback의 권한은 기존 leaf `ConfirmedRegionAuthority`와 계층 catalog fingerprint를 함께 캡처한다. 부모용 별도 방문 카드나 부모 RegionPolicy 승인으로 바꾸지 않는다. leaf 정책/출처/위치 freshness 또는 관계 authority가 철회되면 부모 이야기 offer·생성에도 즉시 같은 철회를 적용한다. 부모 source/binding이 철회되면 부모 콘텐츠만 제외하며 leaf 카드의 과거 proof를 지우지 않는다.

생성 요청은 seed의 원래 부모 regionId를 사용한다. 계획이 사용한 passage/leaf identity는 별도 필드로 유지해 결과 설치·SDK 시작 직전에 원래 위치 permit과 scope를 다시 검사한다. 생성 context의 parent regionId와 target의 leaf regionId를 같은 것으로 비교하지 않는다. 인접한 두 구로 넘어가도 같은 도시 storyId는 기존 session exclusion으로 중복 자동 재생되지 않는다.

forecast의 부모 도시와 실제 확정 leaf는 검토된 `actual leaf → expected parent` 관계에서만 현재 경로 일치로 허용한다. 역방향과 형제 관계는 허용하지 않는다. 부모만 예측한 경로를 특정 자식 구 도착이나 카드 증거로 취급하지 않는다. `RegionPassage` 목록에는 부모와 자식의 시간 구간을 중복 삽입하지 않으며 ETA/visitId/routeRevision은 원 forecast를 유지한다. 구간별 내용 후보만 해당 passage의 검토 ancestor까지 확장한다.

## 적용 위치와 검증

| 위치 | 필요한 변경 |
| --- | --- |
| 새 `location/ReviewedRegionHierarchy` + 고정 catalog loader | exact identity 검증, 관계 검토, 순서 독립 selector와 ancestor scope |
| `location/RegionConfirmation.kt` | 모든 표본에서 같은 canonical selector를 통해 leaf 확인 |
| `storage/RoomLocationStore.kt` | 동일 selector, leaf 카드 하나, 현재 permit에 관계 identity 결합 |
| `storage/RoomAutomaticStoryStore.kt` | ordered eligible regions로 후보 조회·currentness 재검사. limit 적용 전에 leaf/ancestor 우선순위와 exclusion 적용 |
| `planning/AutomaticStoryPlaybackBridge.kt`, `ContentPlanner.kt` | candidate의 원래 story region과 passage scope를 구분해 equality 검사 대체. 최종 시작 권한 포함 |
| `planning/RegionalStoryPlanner.kt` | 실제 leaf와 expected parent의 방향성 있는 일치 검사. target scope 변경은 새 planning identity로 취급 |
| `platform/android/runtime/PetasosSessionHost.kt` 및 generation plan/context | leaf 카드 permit·passage target·seed content region을 각각 전달하고 철회 검사를 합성 |

필요 회귀는 (a) 부모/자식 결과 순서를 뒤집어도 동일 leaf, (b) 형제·무관계 중첩 거부, (c) parent-only/필수 child 누락 거부, (d) 정확한 margin/accuracy와 연속 표본 유지, (e) 실제 용인3·천안2의 기존 oracle 좌표에서 raw lookup은 여전히 복수·selector는 한 leaf, (f) leaf 카드만 하나 적립하고 원래 부모 이야기는 그대로 선택, (g) 부모 콘텐츠 철회와 leaf 정책 철회의 다른 범위, (h) ancestor scope 변경·location revoke와 SDK Started 경합, (i) forecast parent/actual leaf 허용·반대/형제 거부, (j) 구 경계 통과 후 다른 구 카드에는 새 연속 증명이 필요하고 같은 부모 storyId는 자동 재생 중복 제외다.

현재 검수 정책 catalog가 비어 있는 상태는 이 계층 변경으로 바뀌지 않는다. 계층 관계 검증 완료와 실제 위치 RegionPolicy 실측 승인 완료는 각각 별도 근거다.
