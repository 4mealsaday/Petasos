# 검토 지역 정책 bundle v1

dev.24의 개발자 검토 자료 반입 계약이다. 원격 배포 API나 사용자의 임의 파일 가져오기 기능이 아니다. 제품은 APK에 포함한 고정 자산과 코드의 SHA-256 pin이 일치할 때만 읽는다. 현재 `app/src/main/assets/regions/reviewed-policy-catalog-v1.json`의 승인 목록은 비어 있으며 실제 264개 지역의 방문 정책은 미검토다.

## 형식

최상위 객체는 정확히 `formatVersion`(정수 1), `manifestSha256`(대상 기하 manifest SHA-256), `reviews`(검토 객체 배열)를 갖는다. 모든 JSON 키는 중복을 허용하지 않고, 추가 키·잘못된 UTF-8·소수/지수 표기의 숫자를 거부한다. 전체 크기는 256 KiB, 정책 수는 최대 512개이며 대상 revision은 중복될 수 없다. 빈 목록은 설치 변경을 하지 않는다.

각 `reviews` 항목의 필드는 다음과 같다.

| 필드 | 의미 |
| --- | --- |
| `formatVersion` | 정수 1 |
| `regionRevisionId` | 기존 기하 manifest에 있는 ready 지역 revision |
| `manifestSha256` | bundle과 같은 원본 manifest 해시 |
| `geometrySha256` | 해당 지역 원본 geometry 해시 |
| `policy` | 아래 DATA-11 정책 객체 |
| `verifiedAt` | 검토 시각의 epoch 밀리초 정수 |
| `reviewBasis` | 비어 있지 않은 검토 근거, 최대 4,000자 |
| `evidenceSha256` | 검토 자료의 SHA-256 식별자 |

`policy`는 정확히 `datasetVersion`, `consecutiveSamples`, `minConfirmationDurationMs`, `boundaryMarginMeters`, `qualityAdapterVersion`, `coordinateReferenceSystem`을 갖는다. 원본 dataset version과 CRS `EPSG:4326`을 유지한다. 표본 수는 2–100, 시간과 거리는 음수가 아닌 정수이며 거리의 Double 변환에서 값이 바뀌면 거부한다. 품질 어댑터는 실제 수집 어댑터와 맞아야 판정할 수 있다. 임계값의 기본값은 없다.

## 검토·설치 절차

1. 대상 지역의 경계 왕복·오차·수신 단절·오래된 표본에 대한 검수 자료를 준비한다. 원본 경계와 품질 어댑터 버전, 임계값의 근거를 함께 기록한다.
2. 오프라인에서 검토한 자료의 해시와 명시적 정책값을 목록에 넣는다. `evidenceSha256`의 형식 검증은 자료의 실재·검토 내용이 참임을 검증하지 않는다. 이 책임은 자산을 검토해 배포하는 개발 과정에 있다.
3. 실제 catalog 바이트를 고정한 뒤 `BundledRegionPolicyCatalog`의 SHA pin을 함께 갱신하고 빌드·회귀 검증한다. `BundledRegionCatalog`가 worker에서 기하 설치 후 catalog를 읽어 모든 항목을 검증하고 단일 Room transaction으로 전달한다.
4. 정확한 최초 pending JSON·NULL 검수 시각·과거 proof 없음이 모두 맞는 revision만 최초 확정한다. 이미 같은 정책이면 no-op이다. 어느 항목이든 실패하면 해당 정책 batch 전체가 취소된다. 별도 기하 설치 transaction은 유지된다.

정책 최초 확정은 이미 존재하는 기하 revision에 처음 DATA-11 정책을 부여하는 제한된 전이이다. 그 후에는 정책·근거·검수 시각을 덮어쓰지 않는다. 정책 교체·재검수용 revision 계약, 관리용 철회 명령과 원격 관리 UI는 이 형식의 범위가 아니다. 목록에서 항목을 빼는 행위도 기존 DB 정책을 철회하지 않는다. 이미 철회된 DB 정책은 같은 catalog의 재설치로 복구하지 않는다.

기하 자산 pin과 기존 지역 ID·dataset version·Room schema 2를 유지한다. 실제 위치 기록 동의·권한과 마이크 상태를 바꾸지 않는다. 합성 정책은 폐기용 검증 DB에서만 사용하며 제품 catalog에 넣지 않는다.
