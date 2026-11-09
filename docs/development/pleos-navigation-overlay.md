# Pleos 내비 위 Petasos 표시 — 지원 조건 조사

확인일: 2026-11-09. 목표는 **Pleos Navigation 지도 위 원형 Petasos 말하기 버튼과 하단 가로 자막·음악 카드의 동시 표시**다. 내비 앱 전환이나 두 앱 멀티윈도우를 이 요구의 완료로 대신하지 않는다. 자체 지도는 기록·지역 앨범에 한정한다.

## 실제 렌더링 후속 확인 — 2026-11-09

**현재 Pleos 2.1.0 에뮬레이터에서는 공식 Android overlay를 내비 화면 위에 표시할 수 있다.** 별도 진단 APK `com.petasos.diagnostics.overlay`에만 60초 시험용 special access를 임시 허용해 원형 버튼·가로 카드의 실제 동시 표시와 버튼의 고정 문구 변경을 확인했다. 이후 자동 제거 로그를 확인하고 app-op을 기존 `default`로 복원한 뒤 진단 앱을 제거했다. 제품 `com.petasos.app`의 권한·기록·마이크는 변경하지 않았다.

[진단 소스·절차](../../diagnostics/navigation-overlay/README.md), [실제 내비와 동시 표시](../../artifacts/milestone-36/overlay-diagnostic/navigation-refocused-settled.png), [집계](../../artifacts/milestone-36/overlay-diagnostic/verification.json). 진단은 공식 `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`를 썼으며, private SDK·Accessibility·UsageStats는 사용하지 않았다.

앞선 설정 Activity 부재는 일반 사용자 승인 경로가 미확인이라는 근거다. 렌더링 자체 불가능 또는 플랫폼 영구 미지원의 근거로 해석하면 안 된다. 현재 제품의 `navigationSurfaceAvailable=false`는 제품 adapter 미완료 상태이지 플랫폼 기능 검사 결과가 아니다.

실제 내비가 resumed인 상태에서 두 창의 표시를 확인했지만, 지도 자체는 진단 전부터 검정이었다. 지도 타일·목적지 탐색·경로 주행 성공으로 해석하지 않는다. 단기 Activity 진단은 task를 뒤로 보낼 때 원래 내비가 즉시 복귀하지 않아 내비를 다시 전면에 올렸다. 실제 제품에는 화면 수명주기·권한 승인·내비 영역 추적·운전 중 표시 조건과 세션 통합이 더 필요하다. 지도 조작의 통과 시험도 이번 결과만으로 확정하지 않는다.

## 확인한 공식 API와 한계

Android의 `TYPE_APPLICATION_OVERLAY`는 다른 앱 Activity 위에 별도 window를 표시하는 공식 API다. `SYSTEM_ALERT_WINDOW`를 선언하고 사용자가 설정 화면에서 허용해야 하며, 앱은 `Settings.canDrawOverlays(context)`로 현재 권한을 확인한다. `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`은 허용 설정 화면의 공개 진입점이다. 다만 Android 문서는 해당 Activity가 없는 기기도 있을 수 있다고 명시한다. [Window 유형](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY), [권한](https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW), [설정 API](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION).

Pleos의 API 정책은 동일 기능의 AAOS 표준 API가 있으면 표준 사용을 권장한다. 따라서 NaviHelper에 overlay API가 없다는 이유만으로 Android overlay가 불가능하다고 판단하지 않는다. 반대로 표준 API의 존재만으로 현재 Pleos 기기·양산 차량·앱 심사에서 이 표시 방식이 허용된다고 확정하지도 않는다. 조사한 Pleos 공개 문서에서 이 사용 사례의 허용·금지 규정을 직접 확인하지 못했다. [Pleos API 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/pleos-connect-sdk-api).

| 대상 | 확인 결과 |
| --- | --- |
| `NaviHelper.requestNavigationScreen` | 기존 Navigation 앱의 화면 전환 요청이다. Bitmap·View·Surface를 반환하는 임베드 API가 아니다. |
| `NavigationScreenRequest.Foreground` | 내비 화면을 앞으로 요청한다. 실제 표시 성공 전용 callback은 확인되지 않았다. |
| `NaviHelperEventListener.onNavigationStatus` | 경로 안내 상태다. 내비 Activity의 실제 화면 가시성이나 window 경계를 나타내는 계약이 아니다. |
| 공개 listener 전체 | 실제 내비 window의 표시·숨김·크기·좌표 callback을 확인하지 못했다. |
| Android application overlay | 기본적으로 다른 앱 위에 놓이는 window다. 특정 내비 앱에만 자동으로 부착되는 계약이 아니다. |
| `InternalApi.updateOccupiedRegion(Rect)` | 설치 SDK ABI에 있으나 내부 API이며 window 경계 조회도 아니다. 사용하지 않는다. |

SDK 대조 대상은 `NaviHelper:3.0.5`, `navi-data:3.0.4`다. 공식 동적 API 본문과 설치된 공개 ABI를 읽었다. [화면 요청](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/request-navigation-screen), [요청 모델](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.data/navigation-screen-request/entry), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners).

## Pleos Connect 2.1.0 에뮬레이터 읽기 검사

최초 읽기 검사 당시 기존 `emulator-5554`, Android API34, 현재 사용자10에서 package resolver와 권한 상태만 읽었다. 이 초기 검사에서는 Activity 실행, 권한 승인, app-op 변경, 경로 변경은 하지 않았다. 후속 분리 진단의 임시 app-op 변경·복구와 실제 내비 실행은 위 별도 절에 기록했다.

| 검사 | 결과 |
| --- | --- |
| `am get-current-user` | `10` |
| 사용자10 overlay 설정 action resolve, package URI 없음 | `No activity found` |
| 같은 action resolve, `package:com.petasos.app` 지정 | `No activity found` |
| 같은 action의 전체 Activity query | `No activities found` |
| `android.intent.action.MANAGE_SPECIAL_APP_ACCESSES` resolve | `No activity found` |
| 앱 정보 설정 resolve | `ai.umos.vehiclecontrol/.VehicleControlActivity` |
| 검사 당시 Petasos `SYSTEM_ALERT_WINDOW` 선언 | 없음 |
| 검사 당시 Petasos SAW app-op | `default` |
| 설치 CarSettings APK | SpecialAccess Activity는 있으나 overlay 설정 action·DrawOverlay 구현을 발견하지 못함 |

앱 정보 설정 화면이 존재한다는 사실은 overlay 허용 화면이 존재한다는 증거가 아니다. 검사 범위에서 사용자가 공개 설정 Intent로 overlay를 허용할 경로는 확인되지 않았다. 모든 Pleos 버전에서의 영구 미지원으로 일반화하지 않는다.

AOSP Android14 CarSettings manifest에도 `MANAGE_OVERLAY_PERMISSION` handler가 없고, 추가 특수 접근 메뉴는 PermissionController가 제공할 때만 연결한다. 현재 기기 관찰과 일치하는 참고 자료이며 Pleos 구현을 대체하는 증거는 아니다. [AOSP CarSettings manifest](https://android.googlesource.com/platform/packages/apps/Car/Settings/+/refs/heads/android14-release/AndroidManifest.xml), [추가 특수 접근 메뉴](https://android.googlesource.com/platform/packages/apps/Car/Settings/+/refs/heads/android14-release/src/com/android/car/settings/applications/specialaccess/MoreSpecialAccessPreferenceController.java).

## 표시·입력·주행 조건

권한이 있어도 시스템은 overlay 위치·크기·가시성을 변경할 수 있다. Android12 이상에서는 상대 앱이 `setHideOverlayWindows(true)`로 비시스템 overlay를 숨길 수 있다. 현재 에뮬레이터 표시 진단은 성공했으나 양산 기기·제품 통합의 성공은 별도 검증 대상이다. [Window 제약](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY), [Overlay 숨김](https://developer.android.com/about/versions/12/features#hide-application-overlay-windows).

구현을 검증할 때는 원형 버튼과 하단 카드를 각각 필요한 크기의 window로 구성하고, 키 입력 초점을 가져가지 않는 방향을 우선한다. 전체 화면 투명 window로 지도를 덮으면 지도 조작을 가로막을 수 있다. Android12 이후 비터치 window를 통과하는 입력에는 가림 정도 등의 제한도 있다. 표시 영역에 맞는 `WindowContext`를 사용하고 크기·화면 변경을 처리해야 한다. 이는 구현 검토 방향이며 현재 overlay가 구현됐다는 뜻은 아니다. [입력 규칙](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE), [WindowContext](https://developer.android.com/reference/android/content/Context#createWindowContext(int,%20android.os.Bundle)).

Pleos Driving view는 앱이 임의로 가릴 수 없다. Overlay 특수 접근은 운전 중 UI 사용 승인이 아니며, `CarUxRestrictions`와 문자열·콘텐츠·설정·검색 등 기능별 제한을 적용해야 한다. 화면 허용 여부가 불명확하면 표시하지 않는다. 자막을 무제한 문단으로 노출하거나 기존 정차 제한을 overlay로 우회하지 않는다. [Pleos 화면 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model), [주행 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide).

마이크의 while-in-use 권한과 foreground service 시작 조건도 별도다. Overlay 표시 권한만으로 백그라운드 마이크 캡처를 허용하지 않는다. [Android foreground service 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

## 코드의 읽기 전용 지원 검사

`platform/pleos/navigation/PleosCompanionDisplaySupport.kt`의 `inspect(Context)`는 다음 사실만 반환한다.

| 필드 | 의미 |
| --- | --- |
| `overlayPermissionGranted` | 현재 `Settings.canDrawOverlays` 결과. 조회 실패는 false. |
| `overlaySettingsAvailable` | 공개 overlay 설정 action을 처리할 Activity 존재 여부. 조회 실패는 false. 설정이 있다는 사실은 권한이 있다는 뜻이 아니다. |
| `navigationSurfaceAvailable` | 실제 내비 가시성과 안전한 표시 영역의 검토된 공개 계약. 현재 확인되지 않아 항상 false. |

`inspect`는 설정 화면을 실행하거나 권한을 요청하지 않고, 내비·위치·재생 세션도 시작하지 않는다. `SYSTEM_ALERT_WINDOW` 선언은 추가하지 않는다. PackageManager 조회 가시성을 위해 manifest의 해당 action `queries` 선언만 별도 반영한다.

권한과 실제 내비 표시 영역의 두 조건이 갖춰지기 전에는 지원 완료로 표시하지 않는다. `NAVI_CUSTOM_MAP` 권한, 경로 안내 중 상태, 설정 화면 존재 중 하나로 이를 대체하지 않는다. 제품에서 private API·Accessibility·UsageStats 또는 개발용 app-op을 일반 사용자 승인 흐름으로 대체하지 않는다. 위 분리 진단의 일시적인 emulator app-op은 표시 가능성 확인에만 사용했다. 실제 제품 버튼·카드 연결과 운전 중 사용 수용 검사는 아직 완료되지 않았다.
