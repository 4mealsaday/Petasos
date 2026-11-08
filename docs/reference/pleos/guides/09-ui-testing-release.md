# 차량 UI·테스트·배포 참고

확인일: 2026-11-06. 공모전 제출과 App Market 배포는 별도 절차다.

## 주행 제한

CarUxRestrictionsManager로 현재 제한을 읽고 변경 리스너를 등록한다. DO 선언 여부만으로 모든 UI를 허용하지 않고 ActiveRestrictions의 기능별 플래그를 반영한다. 플랫폼이 지역별 제한을 전달하므로 앱에서 국가별 규칙을 임의 복제하지 않는다. [연동 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide)

화면 제한은 속도가 0인지로만 판단하지 않는다. 상세한 카드 목록·설정·긴 텍스트 입력과 청취·큰 버튼을 분리하고, 제한 중 열려 있는 화면의 처리도 시험한다. 구체적인 허용 범위는 [지역별 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)과 [주행 안전 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)를 대조한다.

## CCP와 Back

User-installed 앱의 CCP 지원은 선택 가능하나 필수 조작은 터치로 완결해야 한다. 미지원 선언은 application metadata의 `com.hmg.rotary.ROTARY_SUPPORTED=false`다. Pre-installed 앱 요건을 일반 사용자 설치 앱에 그대로 적용하지 않는다. 최상위 CCP Back은 task를 숨기는 흐름을 설명하며, 실제 표준 Back 이벤트 전달 여부를 확인해야 한다. [CCP 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ccp-rotary-controller-development)

핸들 말하기 버튼 재매핑, CCP 회전 포커스, 미디어 기본 제어는 서로 다른 기능이다. 데모에 ccNC 테마를 추가할 근거가 되지 않는다.

## 키보드와 화면 크기

IME로 텍스트 입력 화면이 조정되는 동작과 입력 없는 Dialog가 밀리는 문제를 구분한다. 문서는 필요한 경우 SystemBars만 insets에 반영하는 방법을 설명한다. Activity 적용은 Android의 키보드·insets 정책에 맞춰 확인한다. [IME 처리](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/ime-window-resize-handling-guide)

폼 팩터 테스트에서는 보조 화면 생성·회전·레이아웃 잘림·터치·상태 복원을 검사한다. 별도 창/Go/Bridge 설정은 [환경 문서](01-environment.md)를 따른다. [원문](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide)

## 앱 분류

Petasos는 지역 이야기를 듣는 경험이 중심이므로 Music/Audio 적합성을 먼저 검토할 수 있다. 이는 공식 승인된 분류가 아니라 구현 권고다. 지도 또는 위치 SDK를 사용한다는 사실만으로 길 안내 앱이 되는 것은 아니다. 오디오 분류의 미디어 연동 요건은 [오디오 문서](08-audio-and-media.md)에 정리했다.

Maps/Navigation으로 분류하거나 직접 길 안내를 구현한다면 단일 활성 경로·사용자 의도에 따른 경로 전환·최소 권한 등 별도 정책을 적용해야 한다. 기존 내비를 이용하는 지역 이야기와 경로 안내 소유권을 혼동하지 않는다. [Navigation 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/navigation-app-development-guide)

## 제출·심사 준비

Notion의 공모전 개발 환경 링크는 최소 API·SDK·포털과 예제를 소개하는 안내다. 특정 SDK의 승인이나 공모전 수상을 보장하는 문서가 아니다. [공모전 개발 환경](https://appdevchallenge.auto-hmg.io/guide/development)

App Market의 기능·성능·안정성 심사는 별도 확인한다. 앱명을 한국어로 표시하더라도 외국어 공식명 태그가 필요하다는 정책이 있으므로 공식명 Petasos를 일관되게 관리한다. [기능·안정성](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-functionality-performance-stability), [앱명 태그](https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-tagging-guide)

새 SDK·서비스·이미지 버전으로 바꿀 때는 권한·null·오류 enum·콜백 순서·하드키·포커스·프로세스 재시작을 다시 검증한다. 테스트 환경과 APK 해시, 모의/실제 SDK 구분은 기존 [검수 명세](../../../Petasos_검수명세.md)를 따른다.
