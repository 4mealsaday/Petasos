# Pleos 위치 의미·경로 기하 문의 초안

2026-11-08 작성. **미전송**. 계정·키·사용자 좌표·실제 이동 이력은 포함하지 않는다.

환경: Pleos Connect 2.1.0 ARM64/API34, FusedLocation 1.1.3, NaviHelper 3.0.5, navi-data 3.0.4.

Petasos는 실제 위치와 도로 경로를 토대로 곧 통과할 지역의 이야기를 준비합니다. 시간·정확도·경로가 확인되지 않으면 자동 지역 안내와 지역 카드 적립을 보류하고 있습니다. 다음 공개 계약을 확인 부탁드립니다.

1. `LocationData.timestampMillis`는 UTC epoch milliseconds인지, 부팅 이후 단조 시각인지, 다른 원점인지요? 위치를 측정한 시각인지 전송한 시각인지, last-known 캐시에서도 원래 측정 시각을 유지하는지도 필요합니다.
2. `LocationData.confidence`의 단위·범위·정확도 의미·신뢰수준·결측 표현은 무엇인가요? 수평 정확도 미터로 변환하는 공식 방식이 있나요?
3. 측정 단조 시각·정확도 또는 동일한 품질 보장을 제공하는 다른 지원 API가 있나요? Android LocationManager를 병용할 수 있다면 지원 provider와 차량 개인정보 차단/권한 정책을 확인 부탁드립니다.
4. 현재 활성 내비 경로의 **전체 도로 polyline과 도로 구간별 예상 통과 시간**을 읽는 지원 API가 있나요? 필요 SDK 버전·권한·경로 revision/재탐색 완료 시점·취소/해제 계약을 알려주세요.
5. `TripSummary.routePoints`와 `RoutePointInfo`는 목적지·경유지로 확인했습니다. 이를 전체 도로 형상으로 간주해도 되는 별도 보장이 있나요? 없다면 외부 길찾기 결과와 활성 Pleos 경로의 동일성을 확인할 지원 방법이 있나요?
6. 목적지·옵션·경유지 변경 시 `onRouteStarted`/`onRoutingCompleted`/`onReRouteEnded` 및 상태/identity 응답의 순서·요청 상관관계가 보장되나요? 취소나 오류 뒤 지연된 응답을 구분할 공식 식별자가 있나요?

확인 근거:

- [LocationData](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/location-data/entry)
- [confidence](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/location-data/confidence)
- [timestampMillis](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused.model/location-data/timestamp-millis)
- [위치 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)
- [내비 조사](../reference/pleos/guides/06-navihelper.md)

2026-11-08 개별 속성의 동적 공식 본문은 각각 Float/Long 선언만 제시했다. 확인 asset SHA-256: confidence `884d011689eb56b3f4412da9958aab1171add732bda25c1c135fc846d9f4364c`, timestampMillis `28b10120c42a3dd023ca492032e0f3c66c86b1ec7ae519445d8c825a1e393ed9`. 선언만으로 시간 원점이나 정확도 단위를 추정하지 않는다.

dev.19에서 공식 Maven metadata와 FusedLocation 1.1.3 sources/Javadoc, 전송 모델 source까지 재확인했다. timestamp/confidence는 변환 없이 복사되며 시간 원점·수평 정확도 단위에 대한 추가 계약을 찾지 못했다. Android 표준 Location API 정의를 Pleos provider 지원 보장으로 대신 적용하지 않는다. [위치 조사 증거](../../artifacts/milestone-19/official-location-contract-audit.json), [내비 조사 증거](../../artifacts/milestone-19/official-navigation-contract-audit.json).
