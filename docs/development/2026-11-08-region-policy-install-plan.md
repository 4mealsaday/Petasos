# 지역 정책 설치 분리 검토안

작성일: 2026-11-08. 기준: v0.8, dev.23 이후의 읽기 전용 설계 검토.

후속 상태: 이 문서는 당시의 미확정 검토를 보존한다. dev.24에서 선택한 최초 확정 범위와 구현·검증 결과는 [후속 구현 기록](./2026-11-08-reviewed-region-policy.md)을 따른다.

이 문서는 **구현 전 제안**이다. 지역 정책 설치기, 정책값, 실제 지역 자동 안내·적립을 승인하거나 활성화하지 않는다. 특히 아래의 `pending → reviewed` 최초 확정 방식이 v0.8의 불변 지역 revision 의미와 양립하는지는 아직 확정하지 않았다. 이번 작업은 이 문서 저장에 한정하며 제품 코드·DB·manifest와 정책값을 변경하지 않았다.

## 확인된 명세와 현재 동작

- [DATA-11](../Petasos_데이터_API_계약.md)은 RegionPolicy에 `datasetVersion`, `consecutiveSamples`(2 이상), `minConfirmationDurationMs`, `boundaryMarginMeters`, `qualityAdapterVersion`을 요구한다. 값을 시연 경로 자료로 검토하고 verified 상태에서만 자동 판정에 사용한다.
- [COL-01](../Petasos_동작명세.md)은 법정 시·군·구 단위, 데이터셋 버전·코드·좌표계, 연속 위치와 경계 규칙을 요구한다. 경계 부근 횟수·거리·시간은 검증한 RegionPolicy를 따르며 설정이 없으면 적립을 보류한다.
- [데이터 계약 8.7](../Petasos_데이터_API_계약.md)의 `region_revision`은 **지역 코드·원천·판정 정책의 고정 revision**이며 과거 통과 근거를 보존한다. INV-03의 지역 proof는 판정 당시 `regionRevisionId`를 참조한다. DATA-03은 대본·사실·출처 revision의 발행 후 덮어쓰기를 금지한다. 이 문구만으로 미검토 지역 revision의 최초 정책 추가가 허용된다고 단정할 수는 없다.
- [현재 SQL](../../spec/petasos-v0.8.sql)의 `region_revision`에는 `UNIQUE(code,dataset_version)`가 있다. 같은 원본 데이터셋·지역 코드에 정책만 다른 새 행을 단순 추가할 수 없다. `collection_proof_kind`는 지역 proof의 대상 revision에 `verified_at IS NOT NULL`을 요구한다.
- [RoomRegionInstaller](../../app/src/main/java/com/petasos/app/storage/RoomRegionInstaller.kt)는 출처·지역과 함께 기하 provenance, `policyStatus=pending`, `verified_at=NULL`을 고정 비교한다. [BundledRegionCatalog](../../app/src/main/java/com/petasos/app/platform/android/location/BundledRegionCatalog.kt)는 load 시 재설치하고, 조회 때 `isAuthorized()`로 다시 비교한다. 따라서 현재 정책 JSON과 검수 시각만 직접 UPDATE하면 다음 load에서 immutable conflict가 나거나 geometry 조회 권한을 잃는다.
- [공식 경계 설치 기록](./2026-11-08-official-region-boundary-import.md)에 따라 264개 중 261개 geometry가 ready이고 3개는 격리돼 있다. 기하 이용 출처 검토와 방문 판정 정책 검토는 별개다. 모든 실제 지역 정책은 미검토 상태로 설치됐다.
- [RoomLocationStore](../../app/src/main/java/com/petasos/app/storage/RoomLocationStore.kt)의 현재 정책 읽기는 필수 수치·데이터셋·출처 허가와 검수 시각의 존재/비음수를 확인하지만, 미래 검수 시각이나 엄격한 정책 검수 봉투 전체를 확인하지 않는다. 새 설치 계약을 구현한다면 읽기와 쓰기가 같은 검증기를 사용하도록 맞출 필요가 있다.
- [RegionConfirmation](../../app/src/main/java/com/petasos/app/location/RegionConfirmation.kt)은 연속 저장행, 품질 어댑터, clock scope, 증가하는 측정·수신 시각, 확인 시간, 정확도와 경계 margin을 확인한다. 여러 지역이 겹치면 하나를 임의 선택하지 않는다. 이 규칙은 이번 제안에서 변경하지 않는다.

## 최소 구현 후보: 기하 설치와 최초 정책 확정 분리

아래는 아직 채택하지 않은 설계 후보다. 기존 지역 정책이 없는 상태에서 설치 경계를 먼저 구현하기 위한 좁은 범위이며, 일반적인 정책 교체 기능은 포함하지 않는다.

1. **기하 provenance 비교를 분리한다.** 기존 region/source ID, 코드·이름·dataset version, manifest와 geometry SHA, 크기·상태, 좌표계, 변환 정보를 고정 비교한다. 정책 검수 상태를 바꿨다는 이유로 정상 geometry가 무효화되지 않도록 한다. 출처 철회·변조·격리를 재설치로 복원하지 않는 기존 동작은 유지한다.
2. **순수 정책 DTO/codec과 Room 설치기를 분리한다.** 가칭 `ReviewedRegionPolicyBundle/Codec`는 DATA-11 필드와 CRS, 대상 revision·기하 fingerprint, 검수 시각·근거를 명시한다. strict JSON의 필수/추가/중복 키·타입·길이·수치 범위를 검사하고, 실제 임계값이나 자동 검수 기본값을 제공하지 않는다. 정확한 직렬화 형식과 필드 목록은 구현 전에 별도 계약으로 확정한다.
3. **정확한 pending 행의 최초 확정만 허용하는 방식을 검토한다.** 같은 pinned geometry이고 기존 JSON이 최초 pending 봉투와 정확히 일치하며 `verified_at=NULL`, 과거 collection proof가 없는 경우만 원자적 확정 대상으로 삼는다. 동일 JSON·검수 시각의 재설치는 no-op, 다른 값은 conflict로 처리하는 안이다. 이 조건들이 불변 revision 계약을 충족한다는 결론은 아직 내리지 않았다.
4. **철회를 최초 pending 상태로 되돌리지 않는다.** 예를 들어 검수 시각을 NULL로 철회하더라도 이미 확정한 정책 봉투는 보존한다. 이를 최초 미검토 행으로 오인해 같은 설치 요청이 다시 승인하지 않도록 한다. 철회·재검수의 구체적인 저장 계약은 최초 확정 계약과 함께 검토해야 한다.
5. **정책과 출처의 현재 유효성을 공동 검사한다.** source의 현재 검수 상태·display/distribution 허가, 미래가 아닌 검수 시각, 정책이 검토한 기하·품질 어댑터를 확인한다. `source.verifiedAt ≤ policy.verifiedAt ≤ now`를 설치와 사용 시 검사하는 안이다. codec 통과만으로 정책 수치가 실증됐다고 판단하지 않는다.
6. **정책 수정본 설치는 제외한다.** 첫 확정 뒤 다른 정책으로 덮어쓰지 않는다. `datasetVersion`에 임의 정책 접미사를 붙여 UNIQUE 제약을 우회하거나 지역 ID를 복제하지 않는다. 정책 수정본은 과거 proof를 보존하는 별도 revision 계약을 먼저 정해야 한다.

설치기는 기존 [로컬 콘텐츠 설치 경계](./2026-11-07-content-install.md)처럼 개발자가 검토한 자료를 명시적으로 전달하는 도구로 제한한다. JSON의 `verified` 문자열로 외부 작성자를 인증하는 원격 배포 API나 앱의 임의 파일 가져오기 기능을 뜻하지 않는다.

## 미해결: 최초 확정과 불변 revision

가능한 해석은 둘이며, 현재 명세와 코드만으로 한쪽을 확정하지 않는다.

| 해석 | 의미 | 남은 문제 |
| --- | --- | --- |
| pending 행을 미발행 준비 상태로 취급 | 기하 동일성을 유지한 채 아직 없던 판정 정책을 최초 1회 확정하고 이후 고정 | 현재 명세는 pending을 별도 준비 상태로 정의하지 않는다. 같은 regionRevisionId의 JSON 변경을 허용해도 되는지 명시해야 한다. proof가 없다는 조건만으로 이 해석이 자동 성립하지 않는다. |
| 설치 시점부터 지역 revision 전체를 불변으로 취급 | pending JSON도 덮어쓰지 않고, 검수된 정책은 독립 revision으로 추가 | 기존 `UNIQUE(code,dataset_version)`와 proof의 지역 revision 참조에 맞는 추가 schema/연결 계약이 필요하다. 독립 정책 테이블만 만들고 canonical `verified_at=NULL`을 유지하면 기존 proof trigger와 양립하지 않는다. |

따라서 **최초 확정은 무마이그레이션 최소안의 후보이며, 확정된 구현 방침이 아니다.** 구현 전에 regionRevisionId가 어떤 내용의 불변성을 보장하는지, pending 행의 상태, 정책 철회와 이후 수정본이 과거 proof에 미치는 영향을 함께 정리해야 한다. 기존 사용자 요구사항을 임의로 완화하거나 원본 dataset version을 다른 의미로 재사용하지 않는다.

지역–이야기 연결은 별도 경계다. [RoomRegionalContentBindings](../../app/src/main/java/com/petasos/app/storage/RoomRegionalContentBindings.kt)는 지역 행을 fingerprint에 포함한다. 정책 메타데이터가 추가되면 기존 후보 키가 바뀔 수 있으므로, 이미 발급한 후보 철회와 재조회가 올바르게 동작하는지 검증해야 한다. 특히 정책이 철회됐는데 이전의 confirmed region ID만으로 새 후보 권한을 발급하지 않는지 최종 출력 경로까지 확인해야 한다. 이는 확인할 설계·검수 항목이며, 이번 문서에서 해당 동작을 구현 완료나 재현된 결함으로 표시하지 않는다.

## 구현 시 예상 파일과 검수

계약이 확정되면 다음 책임으로 나누는 안이다.

- `location/ReviewedRegionPolicyBundle.kt` 또는 별도 codec: 순수 DTO·strict 파싱·기하 및 검수 참조 계약.
- `storage/RoomRegionInstaller.kt`: geometry 설치·원본 동일성 비교. 정책 승인 책임을 맡기지 않는다.
- `storage/RoomRegionPolicyInstaller.kt`: 검수된 정책의 원자적 설치·멱등·충돌 처리.
- `storage/RoomLocationStore.kt`: 공통 codec/current review 검증으로 정책 읽기.
- `platform/android/location/BundledRegionCatalog.kt`: 필요한 경우 설치 경계 조립. 기존 manifest pin과 source 철회 검사를 유지한다.
- 순수 JVM 테스트와 폐기되는 Room DB probe: 실제 정책을 넣지 않고 설치·재로드·철회·proof 보존을 검증한다.

합성 검수에는 첫 설치와 동일 재설치, 서로 다른 값의 거부, batch 전체 rollback, 정책 설치 후 catalog 재로드, 기하 SHA/identity 변조, 격리 지역, 미래 검수 시각, 출처 권한 철회, 정책 철회 후 재설치의 자동 재승인 방지, 과거 proof와 중복 카드 보존을 포함한다. 시·소속 구 중첩을 미검토 정책 필터로 숨겨 단일 지역처럼 만드는 동작도 금지한다.

manifest bytes·기존 ID·canonical schema 유지 여부는 선택한 revision 계약에 따라 확정한다. 무마이그레이션 후보를 선택했다는 가정 없이 schema 변경 불필요를 완료 사실로 기록하지 않는다.

## 실제 정책 활성화에 필요한 근거

구현·합성 검수와 실제 정책 검수는 별개다. 실제 활성화에는 시연 경로의 **경계 왕복, 위치 오차, 수신 단절·오래된 점**에 대한 근거가 필요하다. 현재 최초 실제 위치 수신 확인은 위치 어댑터 데이터 흐름 검증이며 RegionPolicy의 횟수·시간·거리 임계값 검증을 대신하지 않는다.

- 검수 자료가 어떤 원본 경계 revision·코드·좌표계와 품질 어댑터에 해당하는지 명시한다.
- NGII 변환의 예상 정확도 1m를 확정 오차 상한으로 취급하지 않는다. 파일 날짜·카탈로그 날짜를 법정 경계 효력일로 꾸미지 않는다.
- 미검토 정책·격리 geometry·중첩 지역은 계속 보류한다. 실제 정책값을 placeholder나 일반적인 경험칙으로 채우지 않는다.
- 공개 시연 자료로 확보·검토할 수 있는 부분은 개발 범위에서 진행할 수 있다. review/verified 필드가 있다는 이유만으로 매번 사용자 승인이 필요하다고 해석하지 않는다.
- 현재 설계 기록과 구현 준비를 위해 새 사용자 질문은 필요 없다. 실제 자료를 확보하지 못해 사용자의 시험이나 자료 제공이 필요할 때만 구체적으로 요청한다. Virtual Console 실행이 필요해지면 기존 보류 결정을 먼저 다루며, 이번 검토는 그 결정을 변경하지 않는다.

실제 전체 도로 경로·구간 ETA 공급, 지역별 이야기 연결, 랜드마크 정책 설치는 이 문서의 지역 정책 설치 설계만으로 완료되지 않는다. 관련 상태는 [공식 API 진행 경로](./2026-11-08-official-only-platform-path.md), [지역 이야기 연결](./regional-content-bundle-v2.md), [랜드마크 검토 계약](./reviewed-landmark-policy.md)을 따른다.
