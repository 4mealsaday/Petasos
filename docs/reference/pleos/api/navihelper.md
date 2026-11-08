# NaviHelper API 이름 색인

공식 가이드 기준 3.0.5 · 수집일 2026-11-06

웹페이지가 동적으로 불러오는 공식 Dokka 본문에서 추출했다. 메서드 이름의 중복 오버로드는 하나로 묶었다. 이 색인은 검색용이며, 매개변수·nullability·권한·deprecated 여부는 연결된 클래스 원문과 사용 중인 AAR을 대조한다. 모든 SDK 타입을 수록한 목록은 아니다.

[전체 안내](../README.md) · [기계 판독 색인](symbol-index.json)

## CurrentLocationInfo

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/current-location-info/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `address` | `String` |
| `currentRouteIndex` | `Int?` |
| `heading` | `Double = 0.0` |
| `latitude` | `Double` |
| `longitude` | `Double` |
| `routeId` | `String` |

## DestinationInfo

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/destination-info/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `address` | `String` |
| `displayLatitude` | `Double? = null` |
| `displayLongitude` | `Double? = null` |
| `latitude` | `Double` |
| `longitude` | `Double` |
| `name` | `String` |
| `poiId` | `String` |
| `poiProvider` | `String` |
| `poiSubId` | `String?` |
| `remainDistance` | `Int` |
| `remainTime` | `Int` |
| `tollFare` | `Int` |

## DrivingInfo

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/driving-info/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `destination` | `DrivingState` |
| `waypoints` | `List<DrivingState>` |

## DrivingState

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/driving-state/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `chargerCount` | `Int = 0` |
| `chargingPlan` | `ChargingPlan? = null` |
| `distance` | `Int` |
| `duration` | `Int` |
| `isChargingStation` | `Boolean = false` |
| `isRecommendedChargingStation` | `Boolean = false` |
| `maxChargingPower` | `Int = 0` |
| `name` | `String` |
| `remainDestinationChargingValue` | `Int` |
| `targetSoc` | `Int = 0` |

## NaviHelper

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry)

**수명주기·기타:** `addListener`, `addWaypoint`, `cancelRoute`, `changeRouteOption`, `initialize`, `release`, `removeListener`, `removeWaypoint`, `requestNavigationScreen`, `requestReRoute`, `requestRoute`, `requestRouteWithWaypoints`, `requestTrip`

**조회:** `getBookmarkInfo`, `getChargerOperatorInfo`, `getCurrentLocationInfo`, `getDestinationInfo`, `getRecentDestinationInfo`, `getRouteStateInfo`, `getTBTInfo`, `getWaypointInfo`

## NaviHelperEventListener

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper.listener/navi-helper-event-listener/entry)

**수명주기·기타:** `onBookMarkChanged`, `onBookmarkInfo`, `onCameraAlert`, `onCautionPointInfo`, `onChargerOperatorInfo`, `onCurrentLocationInfo`, `onCurrentRoadSpeedLimit`, `onDestinationArrived`, `onDestinationChanged`, `onDestinationInfo`, `onDrivingInfo`, `onError`, `onNavigationStatus`, `onReRouteEnded`, `onRecentDestinationInfo`, `onRouteCancelled`, `onRouteOptionChanged`, `onRouteStarted`, `onRouteStateInfo`, `onRoutingCompleted`, `onTBTInfo`, `onTripInfo`, `onWayPointArrived`, `onWayPointInfo`, `onWaypointChanged`

## RequestWaypointInfo

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/request-waypoint-info/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `address` | `String` |
| `latitude` | `Double` |
| `longitude` | `Double` |
| `poiId` | `String` |
| `poiName` | `String?` |
| `poiProvider` | `String` |
| `poiSubId` | `String` |
| `waypointIndex` | `WaypointIndex` |

## RouteInfo

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-info/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `address` | `String` |
| `displayLatitude` | `Double? = null` |
| `displayLongitude` | `Double? = null` |
| `isMobileRequest` | `Boolean = false` |
| `latitude` | `Double` |
| `longitude` | `Double` |
| `poiId` | `String` |
| `poiName` | `String` |
| `poiProvider` | `String` |
| `poiSubId` | `String` |
| `routeOption` | `RouteOption` |

## RouteStateInfo

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-state-info/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `isRouting` | `Boolean` |
| `naviMode` | `NaviMode` |
| `remainDestinationChargingInt` | `Int` |
| `remainWaypointChargingInt` | `List<Int>` |
| `routeOption` | `RouteOption` |
| `termsAgreement` | `Boolean` |
| `trafficStatus` | `TrafficStatus?` |

## RouteWithWaypointsRequest

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/route-with-waypoints-request/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `destination` | `RouteInfo` |
| `waypoints` | `List<RequestWaypointInfo>` |

## TBTInfo

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/t-b-t-info/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `description` | `String` |
| `distance` | `Double` |
| `type` | `TurnType` |

## TripInfo

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/trip-info/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `distance` | `Int` |
| `energyConsumption` | `Double` |
| `estimatedSocAtDest` | `Int` |
| `eta` | `Int` |
| `summary` | `TripSummary` |

## TripRequest

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/trip-request/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `latitude` | `Double` |
| `longitude` | `Double` |
| `poiId` | `String` |
| `poiName` | `String` |
| `poiSubId` | `String?` |

## TripSummary

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/trip-summary/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `routePoints` | `List<RoutePointInfo>` |
| `trafficConditions` | `List<TrafficInfo>` |

