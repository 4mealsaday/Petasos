# 위치 접근 gate 계약 감사

확인일: 2026-11-08. 공개 공식 문서, 이미 확보한 FusedLocation 1.1.3 공식 source, 현재 앱을 읽기 전용으로 대조했다. 이 감사에서는 실제 위치 수집·기기 조작·권한 변경·제품 코드 변경을 하지 않았다. 실제 `app-location-live` 결과는 별도 실행 증거로 추가한다.

## 결론

- 현재 non-null Pleos `UPDATE` 수신 후 Android 소스를 열고 10초간 허용하는 lease는 **앱의 보수적 정책**이다. 공식 SDK가 정의한 접근 허가 토큰·주기적 heartbeat·차단 해제 확인이 아니다.
- 공식 문서는 위치 변경 시 콜백을 설명하지만 최대 수신 간격을 보장하지 않는다. 따라서 콜백 침묵만으로 개인정보 차단을 확정할 수 없고, 콜드 시작·정차·서비스 연결 대기에서도 현재 gate가 Android 구독을 막을 수 있다. 반대로 이전 non-null 콜백도 이후 10초의 접근 허용을 보장하지 않는다.
- `SubscriptionRequested`는 앱의 등록 호출 반환이다. 서비스의 허가 ACK로 승격하지 않는다. Android fine/coarse 권한과 제공자 ON 역시 Pleos 차량 개인정보 설정의 허가 증명이 아니다.
- 공개 FusedLocation API에서 개인정보 차단 여부를 먼저 확정하거나, 그 확인과 표준 Android 제공자 구독을 원자적으로 연결하는 preflight 계약을 찾지 못했다. 이것은 모든 개발의 선행 지원 문의가 필요하다는 뜻이 아니다. 실제 실패를 분리 진단한 뒤, 확인된 차단을 우회하지 않는 범위에서 변경을 판단한다.

## 공식 문서가 정한 범위

| 근거 | 확인한 계약과 한계 |
| --- | --- |
| [위치 데이터 접근](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access) | 마지막 위치는 새 측정 요청이 아니며 계산 데이터가 없으면 null일 수 있다. 등록 후 위치 변경을 콜백으로 알린다. 주기·초기 데이터 도착 시간·접근 lease는 정의하지 않는다. |
| [권한·차단 안내](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions) | Pleos dangerous 권한이 필요하다. 개인정보 차단 팝업은 안내이며 접근을 허용하지 않는다. Android fine/coarse/background 동작을 이 SDK 문서가 별도로 보장하지 않는다. |
| [오류 처리](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/error-handling) | `onError(Exception)`와 nullable 데이터를 처리한다. 예외 종류와 재시도 가능성은 실제 예외·환경으로 판단한다. 공개 `PrivacyException` 타입이나 차단에 대한 선행 ACK는 명시하지 않는다. |
| [초기화·해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release) | 지원 확인, 초기화, 조회/구독, 동일 리스너 해제, SDK release 순서다. `initialize()`/등록 반환을 서비스 접근 허가 응답으로 정의하지 않는다. |
| [Android LocationManager](https://developer.android.com/reference/android/location/LocationManager) | 제공자 목록·활성 상태 조회와 위치 조회/구독은 별개다. 첫 위치 수신은 지연될 수 있다. Android 계약을 Pleos 개인정보 차단의 전달 순서 보장으로 확장하지 않는다. |

## 확보한 공식 source의 전달 경로

공식 배포본: [FusedLocation 1.1.3 sources.jar](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/FusedLocation/1.1.3/FusedLocation-1.1.3-sources.jar). 기존 로컬 사본은 `/tmp/petasos-pleos-location-audit/FusedLocation-1.1.3-sources.jar`이며 해시는 [기존 공식 조사 artifact](../../artifacts/milestone-19/official-location-contract-audit.json)에 있다. 아래는 이 버전의 구현 관찰이며 향후 버전에 대한 보장은 아니다.

| jar 내부 심볼 | 관찰 |
| --- | --- |
| `ai/pleos/playground/location/fused/FusedLocation.kt`: `isSupported` | 서비스 패키지 존재 검사이며 bind·접근 허가 확인이 아니다. |
| `service/LocationServiceManager.kt`: `checkPermission` | custom permission 부재 시 동기 `SecurityException`을 throw한다. |
| 같은 파일: `getLastKnownLocation` | transport null을 `onChanged(null)`로 전달한다. transport 오류 문자열은 일반 `Exception`으로 감싸서 `onError`에 전달한다. |
| `service/stub/FusedLocationListenerStub.kt`: `onChangeEvent`, `onErrorEvent` | update transport null은 앱의 `onChanged(null)`로 전달하지 않고 SDK 내부 고정 로그만 남긴다. 오류 문자열은 일반 `Exception`으로 감싼다. 따라서 문서의 nullable 서명만으로 모든 update null이 앱에 도착한다고 가정할 수 없다. |
| `service/BaseServiceConnector.kt`: `request`, `sendApi`, `consumeAllCached` | 연결 전 호출은 큐에 저장될 수 있다. binder 예외는 일반 `Exception`으로 재포장한다. 큐 재실행은 기본 빈 실패 콜백을 사용하므로 일부 원격 실패가 앱 콜백 없이 SDK 내부 로그에만 남을 가능성이 있다. 등록 호출 반환은 실제 연결/허가 완료가 아니다. |

별도 공식 의존성의 로컬 사본 `/tmp/petasos-pleos-location-audit/fused-location-data-sources.jar`에는 `ai/pleos/playground/location/fused/exception/LocationException.kt`의 `LocationException(errorCode: ErrorCode)`와 `constant/ErrorCode.kt`가 있다. 여기에는 permission/location-disabled/empty-result/service-failure 코드가 있지만, 위 SDK 전달 경로가 이 타입을 보존한다고 보장하지 않는다. 확인한 공식 source와 문서에서 `PrivacyException` 정의는 찾지 못했다. 임의 오류 문자열을 특정 차단 상태로 단정하지 않는다.

## 현재 앱과 좌표 없는 진단

- [PleosAuthorizedLocationSource.kt](../../app/src/main/java/com/petasos/app/location/PleosAuthorizedLocationSource.kt)의 `accessEvent`는 non-null UPDATE만 lease를 만들며 `startMeasurement`는 lease 이후에만 실행된다. `authorized`는 수신 간격 만료를 `NoLocation(UPDATE)`로 처리한다. 이 `NoLocation`은 실제 제공자의 null과 구분해야 하는 앱의 합성 상태다.
- [PetasosSessionHost.kt](../../app/src/main/java/com/petasos/app/platform/android/runtime/PetasosSessionHost.kt)는 `RECEIPT_GAP_CANDIDATE_MS=10_000`을 이 gate에도 전달한다. 위치 품질 신선도 후보값을 개인정보 접근 허가 유효 기간으로 해석할 공식 근거는 없다.
- [PleosLocationSource.kt](../../app/src/main/java/com/petasos/app/platform/pleos/location/PleosLocationSource.kt)는 오류의 operation과 예외 클래스명만 전달한다. 원문 메시지·좌표를 진단에 추가하지 않는다.
- [AndroidLocationSource.inspect](../../app/src/main/java/com/petasos/app/platform/android/location/AndroidLocationSource.kt)는 제공자명/ON·OFF/권한 비트만 조회하며 last-known·현재 위치·구독을 호출하지 않는다. Pleos `isSupported()`도 데이터 수신 없이 확인할 수 있다.
- [ProductLocationProbe.kt](../../app/src/androidTest/java/com/petasos/app/diagnostics/ProductLocationProbe.kt)의 Room 집계는 좌표를 출력하지 않지만, 0건만으로 Pleos 무수신·lease 대기·Android 무수신을 구별하지는 못한다. 필요하면 test-only 소스 wrapper에서 각 소스의 생성/시작/등록 요청 횟수, sample/null/error 횟수, operation/예외 클래스명, lease 만료 횟수만 집계한다. 오류가 실제 typed인 경우에만 알려진 enum을 추가한다. 원문 오류·위치 payload·전체 위치 로그를 출력하지 않는다.

이 감사만으로 gate를 등록 반환 기반으로 완화하거나 표준 제공자가 차량 개인정보 차단을 준수한다고 선언하지 않는다. 제품 변경 여부는 실제 실행에서 어느 단계가 진행됐는지 확인한 뒤 결정한다.
