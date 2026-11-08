# FusedLocation API 이름 색인

공식 가이드 기준 1.1.3 · 수집일 2026-11-06

웹페이지가 동적으로 불러오는 공식 Dokka 본문에서 추출했다. 메서드 이름의 중복 오버로드는 하나로 묶었다. 이 색인은 검색용이며, 매개변수·nullability·권한·deprecated 여부는 연결된 클래스 원문과 사용 중인 AAR을 대조한다. 모든 SDK 타입을 수록한 목록은 아니다.

[전체 안내](../README.md) · [기계 판독 색인](symbol-index.json)

## FusedLocation

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/entry)

**수명주기·기타:** `addProximityAlert`, `initialize`, `release`, `removeProximityAlert`, `showLocationAccessBlockedPopup`

**조회:** `getLastKnownLocation`, `isSupported`

**구독:** `registerFusedLocationCallback`

**구독 해제:** `unregisterFusedLocationCallback`

## FusedLocationListener

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.listener/fused-location-listener/entry)

**수명주기·기타:** `onChanged`, `onError`

## LocationData

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/location-data/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `altitude` | `Double` |
| `confidence` | `Float` |
| `heading` | `Float` |
| `latitude` | `Double` |
| `longitude` | `Double` |
| `timestampMillis` | `Long` |

## ProximityAlertEvent

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/proximity-alert-event/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `alertId` | `String` |
| `transition` | `ProximityTransition` |

## ProximityAlertListener

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.listener/proximity-alert-listener/entry)

**수명주기·기타:** `onError`, `onRegistered`, `onTransition`

## ProximityAlertRequest

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/proximity-alert-request/entry)

| 속성 | 타입·기본값 |
| --- | --- |
| `latitude` | `Double` |
| `longitude` | `Double` |
| `radiusMeters` | `Float` |
| `transitionTypes` | `Set<ProximityTransition>` |

## ProximityTransition

[공식 클래스·모델 참조](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/proximity-transition/entry)

**수명주기·기타:** `valueOf`, `values`

| 속성 | 타입·기본값 |
| --- | --- |
| `entries` | `EnumEntries<ProximityTransition>` |
| `name` | `String` |
| `ordinal` | `Int` |

