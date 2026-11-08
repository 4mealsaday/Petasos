# FusedLocation 1.1.3

확인일: 2026-11-06. 위치 수신·랜드마크 후보 판정에 사용한다. [API 이름 색인](../api/fused-location.md)

## 호출 흐름

`FusedLocation(context)` 생성 → `isSupported()` 확인 → `initialize()` → 캐시 조회 또는 업데이트 구독 → 구독 해제 → `release()` 순서다. `isSupported()`는 서비스 패키지 존재를 확인하며 위치 수신 성공을 뜻하지 않는다. `unregisterFusedLocationCallback()`과 `release()`는 역할이 다르다. 해제 후 재사용하려면 새 인스턴스를 만든다. [수명주기](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release), [API 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/entry)

| API | 결과·용도 |
| --- | --- |
| `getLastKnownLocation(callback)` | 계산돼 있는 마지막 위치 1회. 새 측정을 요청하는 의미가 아님 |
| `registerFusedLocationCallback(listener)` | 변경되는 위치를 수신 |
| `unregisterFusedLocationCallback(listener)` | 등록했던 동일 객체로 구독 해제 |
| `showLocationAccessBlockedPopup()` | 개인정보 설정 등으로 접근이 차단된 상황의 안내 |

위치 데이터는 `onChanged(LocationData?)`, 오류는 `onError(Exception)`으로 받는다. null과 오류, 캐시와 새 업데이트를 별도 상태로 처리한다. [위치 접근](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access), [오류 처리](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/error-handling)

## 데이터 필드

| 필드 | 타입 | 이번 조사에서 확인한 범위 |
| --- | --- | --- |
| `latitude`, `longitude` | Double | 위도·경도 |
| `altitude` | Double | 고도. 상세 단위·기준면 설명 추가 확인 |
| `heading` | Float | 진행 방향. 기준축·유효 범위 추가 확인 |
| `confidence` | Float | 신뢰도 값. 정확도 미터 또는 0~1 점수로 임의 변환하지 않음 |
| `timestampMillis` | Long | 필드명과 타입 확인. epoch/elapsed 시간축은 설명이 없어 추가 확인 |

공식 동적 API 본문을 확인했지만 이 페이지에는 각 속성의 단위·시간 기준 설명이 충분하지 않았다. 수신 UTC·단조 시각을 앱에서 따로 기록하고 SDK 원시값을 보존한다. 이 처리 방침은 Petasos 구현 권고다. [LocationData](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/location-data/entry)

## 근접 알림

`addProximityAlert(request, listener)`로 등록하고 `removeProximityAlert(alertId)`로 해제한다. `ProximityAlertRequest`는 유한한 위도[-90,90], 경도[-180,180], 양수인 `radiusMeters: Float`, 비어 있지 않은 전이 집합을 요구한다. 전이는 ENTER와 EXIT다. 등록 성공 때 받은 식별자로 이벤트를 연결한다. 이벤트 모델은 `alertId`, `transition`을 제공한다. [요청](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/proximity-alert-request/entry), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/proximity-alert-event/entry), [리스너](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.listener/proximity-alert-listener/entry)

## Petasos 적용

- 위치 이벤트는 바로 재생 명령으로 바꾸지 않고 후보 판정에 전달한다.
- 같은 지역 이동은 현재 이야기를 끊지 않는다. 주요 랜드마크만 문단 뒤 삽입 후보가 된다.
- 사용자 발화 중 지나간 후보는 자동 안내 대상에서 제외한다. 기록 보관 정책과 후보 만료를 혼동하지 않는다.
- SDK 근접 알림이 앱 재시작·정차·경계 왕복·위치 단절에서 어떻게 동작하는지는 시험해야 한다. 내부 지오펜스 판정과 중복 적립되지 않도록 선택한 경로를 기록한다.

제품 정책: [동작 명세](../../../Petasos_동작명세.md). 위치 probe는 ADB로 주입한 두 합성 좌표를 실제 Pleos 콜백으로 수신한 기록이 있다. 실제 GPS 주행·경로 재생의 검증과는 다르다. [실행 기록](../../../development/2026-11-06-sdk-probes.md)

2026-11-08 재확인: 공식 Maven 최신/릴리스는 1.1.3이며 공개 source의 `LocationData`와 transport mapper도 timestamp/confidence의 의미를 추가로 정의하지 않는다. 표준 Android Location의 시간·정확도 정의만으로 Pleos 값에 적용할 수 없고, Android provider 병용의 차량 개인정보/권한 계약도 미확인이다. [공식 source·문서 조사와 해시](../../../../artifacts/milestone-19/official-location-contract-audit.json), [미전송 문의 초안](../../../development/pleos-location-route-support-request.md).
