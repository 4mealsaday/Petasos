# Vehicle 2.3.2

확인일: 2026-11-06. SDK 소개는 Service 2.3.21, 샘플 문서는 Service 2.3.22를 기준으로 한다. 실제 설치 서비스 버전을 별도로 확인한다. [도메인별 API 이름 색인](../api/vehicle.md)

## 공통 사용 흐름

Vehicle을 생성·초기화하고 `getDoor()` 등으로 도메인을 얻는다. 도메인을 직접 생성하지 않는다. Capability로 읽기·쓰기 지원과 영역별 범위를 확인한 뒤 조회·구독·필요한 제어를 호출한다. 비동기 결과는 `onComplete` 또는 `onFailed(Exception)`으로 처리한다. 리스너를 해제하고 SDK를 release한다. [소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [도메인 획득](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object), [구독](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/callback)

Capability의 `AccessType`은 NONE/READ/WRITE/READ_WRITE다. 일부 API는 `CapabilityConfig`, 영역별 `AreaBounds`를 함께 반환한다. API별 반환 형태를 확인하고, 결과를 받지 못한 경우를 미지원으로 확정하지 않는다. [Capability](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/check-capability)

## 도메인별 확인한 범위

아래는 현재 동적 API 참조를 읽은 요약이다. Notion의 개괄 표보다 실제 함수 선언을 우선해 기능 존재를 판단한다. 모든 도메인의 함수 이름과 원문은 [API 색인](../api/vehicle.md)에 있다.

| 도메인 | 확인한 대표 기능 | Petasos 적용 판단 |
| --- | --- | --- |
| Brake | 브레이크 페달 상태 | 주차 브레이크 또는 제동 제어로 확대하지 않음 |
| CarInfo | VIN, 이전 CRN API | 식별 데이터는 필요한 경우만 검토 |
| Display | 온도 표시 단위 조회·지원 확인 | 화면 렌더링 SDK와 다름 |
| Door | 도어 위치·잠금·글로브박스 | 기본 이야기 기능에 필수 아님 |
| DrivingMode | 주행 모드·크루즈 상태·목표 속도 조회 | 크루즈 목표 속도는 실제 차량 속도가 아님 |
| EvBattery | 충전율·효율·충전 설정·알림 | 충전 상황 확장 시 검토 |
| Fuel | 연료 비율·연비 | 이동 해설 P0에 필수 아님 |
| HVAC | 공조 온도·풍량·풍향·전원 등 | 이야기만을 위해 자동 제어하지 않음 |
| Light | 등화·실내·앰비언트 상태와 제어 | 확장 기능 |
| Odometer | 주행 거리·시간·트립 | 위치 기반 지역 판정과 구분 |
| Safety | 경고·telltale·안전 상태 | 안전 이벤트와 콘텐츠 오디오 관계 검토 |
| Seat | 탑승·안전벨트·좌석 위치·열선·통풍 | 실제 지원·권한에 따라 확장 |
| SideMirror | 미러 열선 | 확장 기능 |
| SteeringWheel | 앞바퀴 조향각·핸들 열선 | 음성 버튼 재매핑 기능으로 해석하지 않음 |
| Tire | 저압 상태 | 확장 기능 |
| Toll | ETCS 음성 안내 음량 | 통행료·결제 조회 API로 확대하지 않음 |
| TurnSignal | 방향 지시등 상태 | 필요 시 이동 맥락 보조 |
| Window | 창문·블라인드·Smart Glass | 확장 기능 |
| Wiper | 와이퍼 상태·스위치 | 확장 기능 |

예: [Brake](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/entry), [CarInfo](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/car-info/entry), [Display](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/display/entry), [DrivingMode](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/driving-mode/entry), [SteeringWheel](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/entry)

## 권한·실패·값의 단위

조회는 주로 CAR_*이고 제어는 CONTROL_CAR_*지만 함수마다 다르다. 예를 들어 SteeringWheel의 각도는 CAR_DRIVE, 열선 조회는 CAR_CLIMATE다. 도메인 이름만 보고 권한을 생성하지 않는다. [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [세부 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/steering-wheel/entry)

`onFailed`의 모든 예외가 VehicleException은 아니다. 해당 타입일 때 `errorCode`를 읽고, 미지원 속성·영역·값 타입, 권한, 연결, 내부 서비스, 주행 제한, 빈 결과를 구분한다. [오류 분류](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code)

클러스터 실제 속도는 공식 FAQ가 AAOS `CarPropertyManager`의 `PERF_VEHICLE_SPEED` 사용을 안내한다. GPS 추정 속도나 크루즈 목표 속도로 대체했다고 표시하지 않는다. [플랫폼 FAQ](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq)

## 샘플 앱을 읽을 때

공식 샘플은 capability → 현재값 → 변경 → 업데이트를 확인하는 데 유용하다. 화면의 초기화 표시만으로 서비스 연결·지원 여부를 확정하지 않는다. 샘플 minSdk 23은 배포 앱의 최소 API 28 요구와 다르다. `getHvacTemperatureValueSuggestion`과 VehicleSpeedListener 제거, capability 오버로드·타입 변경도 확인한다. [샘플](https://document.pleos.ai/docs/pleos-only/tutorial/vehicle-sdk-sample-app), [변경 내역](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)
