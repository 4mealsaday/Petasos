# NaviHelper 3.0.5

확인일: 2026-11-06. Petasos는 내비 상황 읽기와 경로 변경 명령을 분리한다. [API 이름 색인](../api/navihelper.md)

## 연결과 권한

`NaviHelper(context)` → `initialize(onConnected, onFailed)` → `addListener()` → 정보 요청 → 콜백 처리 → 리스너 제거·release 순서다. 연결 실패와 내비 요청 오류는 서로 다른 경로로 전달된다. [연결](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [오류](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/error-handling)

| 기능 | API 예 | 권한 |
| --- | --- | --- |
| 정보 읽기 | `getDestinationInfo`, `getCurrentLocationInfo`, `getRouteStateInfo`, `getTBTInfo`, `requestTrip` | NAVI_ROUTE |
| 경로 수정 | `requestRoute`, `requestRouteWithWaypoints`, `cancelRoute`, `requestReRoute`, `changeRouteOption`, `addWaypoint`, `removeWaypoint` | NAVI_ROUTE_SEARCH |
| 내비 화면 | `requestNavigationScreen` | NAVI_CUSTOM_MAP |

권한에는 `pleos.car.permission.` 접두사를 붙인다. 메서드는 대부분 값을 직접 반환하는 getter가 아니라 요청을 보내고 리스너로 결과를 전달한다. [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions), [클래스 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry)

## 읽기 데이터

| 모델 | 확인한 필드·단위 | 해석 시 주의 |
| --- | --- | --- |
| DestinationInfo | 좌표·이름·주소·POI, `remainDistance: Int`(m), `remainTime: Int`(s), `tollFare: Int`(원) | 필드 설명 일부에 waypoint 표현이 남아 있다. 실제 목적지 응답과 대조 |
| TBTInfo | `type: TurnType`, `distance: Double`(m), `description: String` | 다음 회전 정보이며 전체 경로 좌표열이 아님 |
| TripInfo | `distance: Int`, `eta: Int`(s), `estimatedSocAtDest: Int`(%), `energyConsumption: Double`(Wh), `summary` | ETA는 초 단위 안내만으로 절대 시각인지 남은 시간인지 확정하지 않음 |
| DrivingInfo | `destination: DrivingState`, `waypoints: List<DrivingState>` | 목표 지점별 상태 |
| DrivingState | `duration: Int`(현재 위치→대상 초), `distance: Int`(출발점→대상 m), 충전 관련 값 | 거리와 시간의 기준점이 다르므로 같은 기준으로 환산하지 않음 |
| TripSummary | `trafficConditions`, `routePoints` | routePoints는 경유지·목적지 정보 목록으로 설명됨. 도로 형상을 표현하는 polyline으로 가정하지 않음 |

근거: [DestinationInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-info/entry), [TBTInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/t-b-t-info/entry), [TripInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/trip-info/entry), [DrivingInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/driving-info/entry), [DrivingState](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/driving-state/entry), [TripSummary](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/trip-summary/entry)

잔여 거리·시간 필드의 존재는 문서로 확인했다. 현재 앱에서 NaviHelper를 연결해 값이 제공되는지는 별도의 런타임 시험이 필요하다.

## 이벤트

경로 탐색은 `onRouteStarted`, `onRoutingCompleted`, `onRouteCancelled`, `onReRouteEnded` 등으로 추적한다. 목적지 변경·도착과 경유지 변경·도착을 각각 구분한다. `onDestinationInfo`, `onTBTInfo`, `onTripInfo`, `onDrivingInfo`, `onNavigationStatus` 등의 전달 여부는 Navigation 앱의 구현과 상태에 따라 달라진다. [리스너 가이드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners)

Petasos 권고: 이벤트를 앱의 routeRevision 갱신과 연결하되 SDK가 앱의 세대 번호를 제공한다고 가정하지 않는다. 경로 의존 생성 결과만 무효화하고, 일반 질문 답변과 현재 유효한 대화는 명세에 따라 유지한다.

## 목적지·경유지 요청 — 확장 참조

Notion의 직접 링크는 `requestRouteWithWaypoints(RouteWithWaypointsRequest)`다. 요청에 최종 `RouteInfo`와 `List<RequestWaypointInfo>`를 넣고 NAVI_ROUTE_SEARCH 권한 및 처리 콜백을 확인한다. 이것은 경로를 읽는 API가 아니라 내비 경로 설정 요청이다. [직접 링크 원문](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints), [요청 모델](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-with-waypoints-request/entry)

3.0.5의 좌표 순서는 latitude → longitude다. Kotlin named argument를 사용하면 순서 혼동을 줄일 수 있다. 일부 Routes Java 예제는 이전 순서로 보이는 값을 포함하므로 그대로 복사하지 않는다. `onRouteEnded` 대신 `onRoutingCompleted`를 우선하고, 남은 값은 `totalDistance/totalTime` 대신 `remainDistance/remainTime`을 확인한다. [RouteInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-info/entry), [경로 예제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/routes), [버전 변경](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)

내비 제어는 별도 사용자 의도와 제품 범위가 필요하다. 이야기 재생을 위해 임의로 목적지나 경로 옵션을 바꾸지 않는다. [제품 명세](../../../Petasos_제품명세.md)

2026-11-08 재확인: NaviHelper 3.0.5·navi-data 3.0.4 및 동적 API 본문에서도 전체 도로 polyline·구간 ETA 공급 계약을 찾지 못했다. `TripSummary.routePoints`의 POI, traffic 비율, currentRouteIndex로 전체 경로를 보간하지 않는다. dev.19 어댑터는 목적지/옵션/경유지 변경 시 기존 의존성을 철회하고 완료 후 새 상태/identity를 기다린다. 취소·오류 뒤 지연 콜백은 새 경로 시작 전까지 거절하며, 시뮬레이션 경로를 실제 이동의 예측에 사용하지 않는다. 공개 콜백의 순서/요청 식별 계약은 별도 확인이 필요하다. [버전·공식 URL·해시 감사](../../../../artifacts/milestone-19/official-navigation-contract-audit.json), [앱 적용 계약](../../../development/location-navigation-contract.md).
