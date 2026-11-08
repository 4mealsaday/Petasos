# 검토된 이야기–지역 연결: 콘텐츠 bundle v2

이 형식은 검토한 이야기의 소속 지역을 기존 지역 데이터에 연결한다. 기존 v0.8 이야기·출처·사실 모델과 생성 API는 유지한다. 지역을 연결해도 위치 유효성, 경로 예측, 방문 정책, 지역 카드 또는 랜드마크 판정이 승인되지는 않는다.

## 버전과 역할

`formatVersion=1`의 필드·UUID 생성·본문 해시·출력 바이트는 그대로 유지한다. v1에는 `regionBindings`가 없으며, 해당 필드를 추가하면 거부한다. v2는 기존 최상위 필드를 모두 유지하고 필수 `regionBindings` 배열만 추가한다. 빈 배열은 허용하며, 이름·주소·좌표·관광 API 코드에서 연결을 자동 추론하지 않는다. `stories`와 `places`의 기존 필드 구조도 변경하지 않는다.

Python 컴파일러는 검토 입력의 참조와 형식을 확인한다. Android 설치기는 기존에 설치된 정확한 지역 revision과 출처를 대조하고 콘텐츠와 연결 근거를 한 트랜잭션으로 설치한다. 이후 런타임도 저장된 연결 근거와 현재 출처 권한을 검사해야 한다. 단순한 `story.region_id` 값만으로 검토된 연결을 대신하지 않는다.

## `regionBindings` 계약

배열 크기는 0~32개이고 같은 `storyId`는 한 번만 나온다. 각 객체는 아래 10개 필드를 모두 포함하며 추가 필드를 허용하지 않는다.

| 필드 | 형식·의미 |
|---|---|
| `storyId` | 같은 bundle에 존재하는 이야기 ID |
| `regionId` | 기존 지역의 안정된 ID |
| `regionRevisionId` | 검토 대상인 기존 지역 revision ID |
| `regionCode` | NGII `SIG_CD`와 대조할 ASCII 숫자 5자리 문자열. 앞의 0을 보존한다. |
| `datasetVersion` | 기존 지역 dataset 버전. 공백만인 값 금지, UTF-16 128단위 이하 |
| `regionName` | 기존 지역 revision의 정확한 이름. 공백만인 값 금지, UTF-16 500단위 이하 |
| `regionSourceRevisionId` | 기존 지역 geometry의 출처 revision ID. 콘텐츠 bundle의 텍스트 출처 목록에 넣을 필요가 없다. |
| `associationFactRevisionId` | 같은 bundle에 있는 지역 소속 근거 사실 revision ID |
| `verifiedAt` | 이 연결을 검토한 epoch milliseconds. 0 이상이며 검증 현재 시각 이하 |
| `reviewBasis` | 연결 검토의 근거와 범위. 공백만인 값 금지, UTF-16 4,000단위 이하 |

ID는 기존 `[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}` 규칙을 따른다. 소속 근거 사실은 `kind=verified_fact`여야 하며 `tradition`을 사용하지 않는다. 해당 사실의 검토 시각은 연결 검토 시각보다 늦을 수 없다. 사실의 모든 출처에는 기존의 검토 완료·표시·배포 허가 규칙이 적용된다. `allowGeneration`은 독립된 권한이므로 false인 자료를 연결했다고 생성 근거로 승격하지 않는다.

소속 사실은 이야기의 발화 문단에 포함되지 않아도 된다. 연결 근거는 문단과 별도로 보존·검증해야 하므로, 새 생성 버전에서 그 사실을 발화하지 않아도 근거가 사라지지 않는다. 형식 검증은 사실 본문이 실제로 소속을 입증한다는 의미 검토를 대신하지 않는다.

## 검토 입력

기존 `kind=petasos-content-source-review`를 사용한다. v2 검토 입력은 최상위 `formatVersion=2`, `regionBindings` 배열이 필요하다. 각 binding에서 `storyId` 대신 `storyKey`, `associationFactRevisionId` 대신 `associationFactKey`를 사용한다. 두 값은 같은 검토 입력의 이야기·사실 `stableKey`와 정확히 일치해야 한다. 나머지 8개 필드는 bundle과 동일하다.

아래 조각은 형식 설명을 위한 합성 예시이며 승인 자료가 아니다.

```json
{
  "storyKey": "synthetic-story",
  "regionId": "kr-sigungu-12345",
  "regionRevisionId": "synthetic-region-r1",
  "regionCode": "12345",
  "datasetVersion": "synthetic-regions-v1",
  "regionName": "합성 지역",
  "regionSourceRevisionId": "synthetic-geometry-r1",
  "associationFactKey": "synthetic-administrative-association",
  "verifiedAt": 1794000000000,
  "reviewBasis": "합성 검토: 지정한 자료의 명시적인 소속 사실을 확인함"
}
```

컴파일 명령은 기존과 같다. 새 출력 파일에만 쓰며 기존 파일을 덮어쓰지 않는다.

```sh
.local/server-venv/bin/python scripts/build-content-bundle.py \
  --review .local/content/review/regional-source-review.json \
  --output .local/content/bundles/regional-v2.json
```

기존 v1 파일을 v2로 저장하는 것만으로 기존 이야기의 지역 연결을 변경할 수는 없다. 같은 stable story ID의 immutable metadata 변경은 설치 충돌이다. 기존 가거도 이야기와 장소의 NULL 지역·좌표는 그대로 유지한다. 이 버전은 새로 검토한 이야기의 최초 설치를 지원하며, 기존 이야기의 지역 보완은 이력과 stable identity를 보존하는 별도 metadata 마이그레이션이 필요하다. 지역을 붙이기 위해 같은 이야기에 새 ID를 부여하지 않는다. 새 이야기가 기존 사실을 재사용하더라도 완료 이야기·stable fact 반복 방지 규칙은 유지한다.

## 설치와 생성의 경계

Android는 지역 ID·revision·코드·dataset·이름·geometry 출처가 기존 설치본과 일치하는지 확인한다. 콘텐츠 bundle로 새 지역을 만들거나 기존 geometry/방문 정책을 수정하지 않는다. 경계 출처의 사용 권한과 DATA-11 방문·안내 정책 승인은 별도이며, 연결 설치가 `region_revision.verified_at`를 채워서는 안 된다.

별도 연결 메타데이터는 v0.8의 생성된 기본 테이블을 바꾸지 않는 Room 확장으로 보존한다. 출처·사실·연결 근거가 없거나 철회·변조되면 해당 지역 연결을 런타임에서 사용할 수 없어야 한다. 재설치는 동일한 입력일 때 no-op이며 다른 지역·근거·검토 정보로의 덮어쓰기를 허용하지 않는다.

`build_evidence_registry()`는 v1과 v2 모두 기존 `sources`·`facts` 형식으로 내보낸다. 등록 기기 허용 목록과 모든 관련 출처의 생성 허가가 필요하다. `regionBindings`, 지역 geometry, 검토 메모를 모델 근거에 자동 추가하지 않는다. 생성 요청의 `story.regionId`는 앱이 검증한 설치된 이야기 metadata에서 전달하며 evidence registry에 별도 story/region 필드를 만들지 않는다.

자료 연결과 실제 주행 검증은 구분한다. 지역 자동 청취를 활성화하려면 유효한 실제 위치, 검토된 지역 정책, 경로별 진입·이탈 예측, 현재 재생 권한을 함께 충족해야 한다.
