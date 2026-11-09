# 실제 Pleos 내비 기반 가상 주행 데모

확인일: 2026-11-09. 목표는 **실제 Pleos Navigation에서 목적지를 설정하고, 에뮬레이터의 가상 위치 이동을 내비와 Petasos가 함께 받는 데모**다. 자체 지도에 내비 화면을 흉내 내거나 앱 내부 시간만 이동하는 방식으로 대체하지 않는다. Petasos의 자체 지도는 여행 기록·지역 앨범에 사용한다.

네비 위 원형 말하기 버튼·가로 자막 카드의 동시 표시는 별도 표시 기능이다. [Overlay 지원 조사](pleos-navigation-overlay.md)와 이 문서의 경로·위치 검증을 함께 보되, 한쪽 결과를 다른 쪽 성공으로 해석하지 않는다. 현재 제품의 지원 검사 결과는 구현 준비 상태이며 Pleos 플랫폼 전체의 영구 미지원 선언이 아니다.

## 공식 경로 설정 API

검사 대상은 `NaviHelper:3.0.5`, `navi-data:3.0.4`다. 공식 문서와 프로젝트에 설치된 SDK의 공개 ABI를 대조했다.

| 작업 | 공식 API / 권한 | 결과를 확인하는 방법 |
| --- | --- | --- |
| 네비 검색 화면 열기 | `requestNavigationScreen(SearchPlace(keyword))` / `NAVI_CUSTOM_MAP` | 네비에서 검색 결과를 선택하고 실제 목적지 설정을 확인한다. 검색 화면 요청 자체는 경로 시작이 아니다. |
| 네비 화면을 앞으로 요청 | `requestNavigationScreen(Foreground)` / `NAVI_CUSTOM_MAP` | 실제 기기 화면을 확인한다. 표시 완료 전용 callback 계약은 찾지 못했다. |
| 좌표·장소로 목적지 설정 | `requestRoute(RouteInfo)` / `NAVI_ROUTE_SEARCH` | `onRouteStarted`, `onRoutingCompleted`, `onDestinationChanged`, `onError`를 처리하고 목적지·경로 상태를 다시 읽는다. |
| 경유지를 포함한 목적지 설정 | `requestRouteWithWaypoints(RouteWithWaypointsRequest)` / `NAVI_ROUTE_SEARCH` | 위 경로 이벤트와 경유지 응답을 확인한다. |
| 현재 위치·목적지·경로 상태 조회 | `getCurrentLocationInfo`, `getDestinationInfo`, `getRouteStateInfo` / `NAVI_ROUTE` | 대응하는 listener callback에서 결과를 받는다. |
| 경로 취소 | `cancelRoute()` / `NAVI_ROUTE_SEARCH` | 취소 이벤트·갱신된 경로 상태를 확인한다. |

권한 이름에는 `pleos.car.permission.` 접두사를 붙인다. 현재 제품 manifest에는 `NAVI_ROUTE`와 `NAVI_CUSTOM_MAP`만 있으며, 직접 목적지 설정을 구현할 때 `NAVI_ROUTE_SEARCH` 선언이 필요하다. `requestRoute`가 예외 없이 반환했다는 사실은 경로 탐색 완료를 뜻하지 않는다. Kotlin의 `latitude`, `longitude` named argument를 사용한다. 공식 Java 예제의 위치 인자 순서를 그대로 복사하지 않는다. [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions), [목적지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), [경유지 요청](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints).

NaviHelper는 기존 Pleos Navigation에 요청을 전달하는 SDK다. 별도 내비 지도 렌더러를 Petasos에 만드는 API로 해석하지 않는다. 공식 신청 안내상 NaviHelper는 신청 없이 사용할 수 있으며, Emulator 2.1.0 이후 CRN 입력도 요구되지 않는다. 조사한 안내에는 이 목적지 설정을 위해 별도 Naver/Kakao 키를 등록하라는 요구가 없다. 이것은 현재 네비 서버의 접속·경로 계산 성공을 보장하지는 않는다. [NaviHelper 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro), [SDK 신청 안내](https://document.pleos.ai/docs/pleos-only/create-my-project).

## 내장 모의주행과 에뮬레이터 위치 재생의 구분

`NaviMode.SIMULATION_GUIDING`은 공개 상태 enum에 존재한다. 그러나 공개 `NaviHelper` 메서드와 `NavigationScreenRequest` 하위형 전체에서 **내장 모의주행 시작·정지·진행률 변경 명령을 찾지 못했다**. `OverviewRoute(RouteOverviewShowInfo)`의 모델에는 `durationMs`만 있으며 이를 주행 시뮬레이션 명령으로 사용하지 않는다. 네비 자체 UI에 모의주행 버튼이 있는지는 별도 기기 확인 대상이다. 상태 값의 존재만으로 외부 앱의 시작 API를 만들어 호출하지 않는다. [NaviMode](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.constants/navi-mode/entry), [화면 요청 종류](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/navigation-screen-request/entry).

반면 Android Emulator는 다음 개발용 위치 입력을 공식 제공한다.

- Extended Controls → Location에서 단일 위치를 지정한다.
- Routes에서 경로를 재생·정지하고 반복·재생 속도를 설정한다.
- GPX/KML 파일을 읽어 위치열을 재생한다.
- 콘솔의 `geo fix longitude latitude [altitude] [satellites] [velocity]`로 GPS 위치를 전달한다. `adb -s <serial> emu geo fix ...` 형태로 사용할 수 있다. 좌표 순서는 **경도 → 위도**, 속도 단위는 **knots**다.

Android 공식 설명에서 이 입력은 LocationManager에 위치를 공급하는 mock provider다. Pleos FusedLocation 또는 네비의 수신 성공은 각각 실제 콜백과 화면으로 검증해야 한다. [위치·경로 재생](https://developer.android.com/studio/run/emulator-extended-controls#location), [GPS 콘솔 명령](https://developer.android.com/studio/run/emulator-console#geographic-location).

위치 주입은 Virtual Console 연결 없이 가능하다. Virtual Console은 별도 차량 상태 도구이며, GPS에서 계산한 속도와 VHAL의 `PERF_VEHICLE_SPEED`는 같은 값이라는 보장이 없다. GPS 이동 성공을 주행 제한·기어·핸들 버튼 동작 검증으로 대신하지 않는다. 현재 Virtual Console 보류 결정은 유지한다. [Pleos 차량 상태 도구](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator), [GPS 속도와 차량 속도](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq).

## 기기에서 확인한 것과 남은 것

| 항목 | 결과 / 범위 |
| --- | --- |
| 초기 dev.2 FusedLocation 합성 A→B | PASS. 캐시와 별개인 실제 Pleos UPDATE 수신. [당시 기록](2026-11-06-sdk-probes.md) |
| 현재 dev.36 FusedLocation 합성 A→B | PASS, 1,433ms. `location_fixture_a`, `location_fixture_b` 수신. 아래 증거 참조. |
| 현재 내비 UI | `ai.umos.maps.android.navigation.app`의 UI 표시를 관찰했다. |
| SwiftShader 실행 중 내비 지도 | 지도 영역이 검정으로 관찰됐다. Driving View와 다른 UI는 렌더링됐다. 원인 미확정. |
| `-gpu host -feature -Vulkan` 비교 | Driving View texture가 안정적으로 표시되고 내비 아이콘·경로선도 표시됐다. 바탕 도로·지명은 여전히 비어 있어 지도 표시 전체 해결은 아니다. |
| 실제 네비 UI의 목적지·경로 | 공개 장소 `COEX` 검색 → 코엑스 선택 → 길안내 `31분`, `15km` 표시를 확인했다. UI를 통한 실제 경로 설정 성공이다. |
| 현재 SDK 읽기 연결 | PASS, 212ms. 실제 NaviHelper 연결·경로 상태 콜백을 받고 listener를 해제했다. 현재 probe는 목적지·좌표 값을 출력하지 않으므로 특정 목적지 일치나 위치 변화의 증거로 삼지 않는다. [기록](../../artifacts/milestone-36/navigation-read/navigation-privacy.json) |
| SDK `requestRoute` 실행 수용 | 아직 검증하지 않았다. 위 UI 결과를 SDK 호출·완료 callback의 성공으로 대신하지 않는다. |
| GPS 입력의 실제 네비 위치 반영 | 미확인. 강남 부근 공개 합성 좌표 12개를 1초 간격으로 주입한 후에도 TBT `80m`, `31분`, `15km` 표시는 유지됐다. 화면에는 pan 차이가 있으므로 픽셀 차이만으로 GPS 동기화 성공을 판정하지 않는다. |
| 전체 경로 재생과 Petasos 이야기 동기화 | 아직 검증하지 않았다. |

현재 위치 재검사의 [JSON](../../artifacts/milestone-36/location-fixture/location-privacy.json)에는 실행 시각·APK 해시·fingerprint·합성 좌표 fixture 범위가, [TXT](../../artifacts/milestone-36/location-fixture/location-privacy.txt)에는 이벤트와 1,433ms가 기록돼 있다. 환경은 Pleos Connect 2.1.0 / Android14 API34 / 사용자10이다. 이 PASS는 실제 SDK 콜백 증거이며 실차 GPS·전체 제품·전체 경로의 수용 검사가 아니다.

실제 네비 화면 증거: [검색](../../artifacts/milestone-36/navigation-host-search.png), [목적지 선택 후 길안내](../../artifacts/milestone-36/navigation-native-route.png), [GPS 구간 주입 후](../../artifacts/milestone-36/navigation-after-gps-segment.png). 다음 검증은 NaviHelper의 실제 상태·현재 위치 응답과 FusedLocation의 공유 위치 입력을 함께 비교하는 것이다.

## 검정 지도 진단의 경계

공식 Pleos 릴리스노트·설치 문서·FAQ·NaviHelper 가이드에서 현재 관찰과 일치하는 지도 검정 known issue, 내비 GPS 주입 미지원, 별도 지도 키나 새 내비 앱 설치 필수 안내는 찾지 못했다. 따라서 이 증상을 Pleos 네비 미지원으로 확정하거나, 사용자에게 근거 없는 키 발급·외부 문의를 선행 조건으로 요구하지 않는다. [2.1.0 릴리스](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note).

Pleos 설치 가이드는 Mac ARM64 이미지를 제공하고 Cold boot를 요구하며, 이전 Studio 설정 UI 기준 Hardware graphics를 안내한다. Android는 Apple Silicon의 MoltenVK 호환 문제에 SwiftShader를 공식 대안으로 제시하고, GLES 앱에는 `-feature -Vulkan`을 사용한 비교도 안내한다. 따라서 SwiftShader 자체를 잘못이라고 단정하지 않는다. 같은 AVD의 `-gpu host -feature -Vulkan` 실행에서는 Driving View texture와 내비 아이콘·경로선이 표시됐지만 바탕 도로·지명은 비어 있었다. 렌더러 변경 뒤의 부분 개선이며, 지도 데이터·서버·렌더링 중 남은 원인은 확정되지 않았다. UI 경로 설정 성공과 GPS 동기화 미확인을 각각 유지한다. [Pleos 설치](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator), [Android Mac 그래픽 문제](https://developer.android.com/studio/run/emulator-troubleshooting#macos-hardware-rendering), [렌더러 종류](https://developer.android.com/studio/run/emulator-acceleration#accel-graphics).

Android 문서의 “Google Maps가 Extended Controls에서 보이지 않는 문제”는 호스트 Google Maps WebView 문제다. 이를 기기 안의 Pleos Navigation 지도 검정 증상 원인으로 옮겨 적용하지 않는다.

## 데모 통합 구조

다음은 구현 방향이며 완성된 기능 목록이 아니다.

1. **네비 제어 어댑터**: 실제 NaviHelper에 검색·목적지·취소 요청을 보낸다. 요청 발행, 경로 탐색 중, 완료, 오류를 구분한다. 목적지 설정과 지역 이야기 선택 알고리즘을 분리한다.
2. **공통 위치 재생기**: 호스트에서 도로 위의 공개 합성 경로를 에뮬레이터 GPS로 재생한다. 재생·일시정지·탐색·속도 조절은 한 시간축으로 관리한다. Petasos 내부 시계만 움직여 네비와 다른 곳을 가리키게 만들지 않는다.
3. **데모 입력·기록 경계**: 네비/Fused 콜백으로 실제 도착한 가상 위치를 사용하되 데모 세션으로 표시한다. 기존 실제 여행·지역 적립과 저장소 또는 명시적 데이터 출처를 분리한다. 뒤로 탐색하면 이전 예측·준비 결과를 폐기하고 새 세대를 시작한다.
4. **기존 이야기·대화 실행기**: 준비된 이야기 콘텐츠를 사용할 수 있지만 사용자 인터랙션의 응답은 실시간 생성한다. 대화 인터럽트와 시간 지연은 다음 이야기 지역 계획을 다시 계산한다. 다음 후보 지역을 사용자 화면에 미리 표시하지 않는 결정은 유지한다.
5. **네비 위 표시 어댑터**: 원형 버튼과 하단 자막·음악 카드를 별도 window로 표시한다. 네비 가시성·표시 영역·주행 제한을 반영하고, 경로 주입 성공과 overlay 성공을 각각 검증한다.

현재 `AndroidLocationMeaningAdapter`는 `metadata.isMock`을 실제 위치 증거로 인정하지 않고, `PleosNavigationSource`는 `SIMULATION_GUIDING`을 실주행 경로 예측에서 구분한다. 이 검사를 전역으로 제거하지 않는다. 데모 세션이 가상 위치를 다루는 명시적 경계를 추가해야 한다. 에뮬레이터 위치가 어떤 `isMock` 값으로 노출되는지는 별도 관찰값이며, 이 값 하나에만 데모 데이터 구분을 의존하지 않는다.

마이크는 별도의 기존 제약으로 현재 제품 capability가 false다. 위치·overlay·목적지 설정이 성공했다고 실제 마이크 대화까지 완료로 표시하지 않는다. 실시간 답변 경로와 실제 발화 검증은 준비 상태와 기존 음성 개인정보 검사를 확인한 후 별도로 진행한다.

## 다음 수용 검사

- 네비 현재 위치 응답·지도 카메라·Fused UPDATE를 같은 공개 A→B 입력으로 비교한다. 캐시 응답만으로 통과시키지 않는다.
- UI에서 확인한 코엑스 경로의 실제 NaviHelper 상태·목적지·현재 위치 응답을 먼저 확인한다. 이어 공식 `requestRoute`의 탐색 완료와 갱신된 목적지·경로 상태를 확인하고 오류·타임아웃을 구분한다.
- 짧은 도로 경로를 재생해 네비 지도 이동·잔여 거리/TBT와 Petasos 지역 입력이 함께 갱신되는지 확인한다. 외부 길찾기 경로와 Pleos 선택 경로의 일치를 확인하지 않고 동일 경로라고 주장하지 않는다.
- 일시정지·뒤로 탐색·경로 이탈·목적지 변경 때 준비된 이야기 계획을 적절히 폐기·재계산하는지 확인한다.
- 이야기 중 대화 인터럽트와 응답 후 재개를 검사한다. 실제 마이크 검사는 별도 항목으로 남긴다.
- 종료 후 위치 주입·overlay·오디오·세션 작업이 남지 않고 기존 실제 여행 기록이 변하지 않았는지 확인한다.

실제 네비 경로 형상 전체는 여전히 별도 계약이다. `TripSummary.routePoints`는 목적지·경유지 정보이며 도로 전체 polyline으로 보간하지 않는다. [기존 경로 계약](location-navigation-contract.md), [SDK 형상 조사](../../artifacts/milestone-19/official-navigation-contract-audit.json).
