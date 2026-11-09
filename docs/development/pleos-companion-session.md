# Pleos 내비와 함께 실행하는 주행 청취 — dev.35

> **dev.36 최신 정정:** 요청한 화면은 내비 위 Petasos 버튼·자막의 동시 표시다. 홈·내 여행 UI를 재구성했지만 현재 공식 표시 경로가 확인되지 않아 ON은 OFF를 유지한다. 아래 dev.34/35 내용과 검증은 당시 구현의 기록이며 현재 overlay 완료를 뜻하지 않는다. [현재 상태](companion-home-ui.md), [공식 지원 조사](pleos-navigation-overlay.md).

목적지 선택·경로 표시·길 안내는 Pleos Navigation이 담당한다. Petasos 듣기 화면의 자체 지도는 제거했고, 자체 지도는 기록·지역 앨범에서 사용한다. 기본 관심사·지역 선정·이야기 길이 기반 재편성·수집 판정은 변경하지 않는다.

## 화면과 공식 내비 명령

`JourneyShell`은 `NavigationScreenCommands`를 통해 내비 열기, 검색어로 목적지 찾기, 현재 경로 보기를 요청한다. 실제 SDK 호출은 별도 `PleosNavigationScreens`에 둔다. 기존 `PleosNavigationSource`의 읽기 수명주기와 분리한다.

- `Foreground`, `SearchPlace(keyword)`, `OverviewRoute(RouteOverviewShowInfo())`만 사용한다.
- 목적지는 사용자가 네이티브 내비 검색 결과에서 선택한다. 화면 요청이 성공했다는 반환만으로 실제 화면 표시나 경로 설정 성공을 기록하지 않는다.
- `NAVI_CUSTOM_MAP`은 공식 화면 요청 권한이다. 임의 화면 삽입·다른 앱 위 overlay 권한으로 해석하지 않는다.
- 연결 대기 중 화면 숨김·새 명령·권한 상실은 이전 명령을 폐기한다. 자동 재전송하지 않는다.
- 가로 자막·재생 컨트롤은 Petasos 창 안에 표시한다. 좁은 창에서는 버튼이 다음 행으로 배치된다. 외부 내비 위 임의 자막·모자 버튼은 구현하지 않았다.

공식 근거: [NaviHelper](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro), [화면 요청](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/request-navigation-screen), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions), [최대 두 앱의 화면 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model).

## 명시적으로 시작한 위치 세션

`PlaybackControls`의 실제 재생/기록 다시 듣기 동작은 `PetasosSessionHost.prepareVisiblePlayback()`을 거친다. 위치 동의가 OFF이면 일반 오디오 재생으로 진행한다. ON이면 실제 위치 권한과 현재 Activity의 조작 가능 상태를 검사하고, 저장된 런타임 세션이 준비된 뒤 location foreground service를 시작한다.

`CompanionLocationService`는 알림을 표시해 foreground로 승격하고, 자신의 MediaBrowser 연결로 같은 `PetasosMediaService`/Host를 유지한다. 그러고 나서 정확한 요청에 ACK한다. 따라서 이야기 사이 무음이나 오디오 정지 상태에서도 Activity 연결에만 의존하지 않는다. `CompanionSessionAuthority`는 메모리에만 존재하며, 설정 ON·서비스 재생성·외부 미디어 Play·복구가 새로운 권한을 만들지 못한다.

| 상황 | 동작 |
| --- | --- |
| ACK 전 화면 숨김/시작 실패/시간 초과 | 해당 시작 요청 취소 |
| ACK 후 내비로 화면 전환 | 같은 이동의 위치 수신·기록·자동 편성 유지 |
| 일반 재생 정지 | 오디오 정지, 이동/위치 세션은 유지 |
| 위치 기록 OFF, 위치 권한 상실, 이동 종료 | 세션 권한 폐기 및 location service 종료 |
| Task 제거 | 서비스 종료·오디오 Stop, 이동 종료 성공을 임의 기록하지 않음 |
| 프로세스/서비스 재시작 | 자동 재개 없음, 새 명시 재생 필요 |
| 이전 요청의 늦은 ACK/종료 | 새 요청의 권한을 획득·폐기하지 못함 |

`FOREGROUND_SERVICE_LOCATION`과 location 타입의 nonexported 서비스를 선언했다. `ACCESS_BACKGROUND_LOCATION`이나 overlay 권한은 추가하지 않는다. 기존 전면 위치 이용과 동의 철회 규칙을 유지하고, 활성 foreground 세션만 Activity 비가시 상태의 위치 이용을 허용한다. 마이크는 계속 비활성이다.

공식 근거: [location FGS](https://developer.android.com/develop/background-work/services/fgs/service-types#location), [전면 서비스 시작](https://developer.android.com/develop/background-work/services/fgs/launch), [백그라운드 시작 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Media3 유휴 수명주기](https://developer.android.com/media/media3/session/background-playback).

오디오 포커스 복귀는 [별도 구현 기록](pleos-companion-audio-focus.md)을 따른다. 실제 내비 음성·주행·Suspend·사람 발화 수용과 합성 포커스 검사를 구분한다. Virtual Console 보류는 유지한다.

## 검증

[검증 집계](../../artifacts/milestone-35/verification.json): 제품 빌드·Lint PASS, JVM 1,086개/87 suite 실패·생략0, 호스트78개·서버 환경15개 PASS. 데모 공통 코드 컴파일과 최종 병합 manifest 권한0/Activity1개를 확인했으며 새 데모 APK는 설치하지 않았다.

- `app-companion` 12,668ms PASS: 실제 같은 이벤트의 Play→Stop 이후6초 무재생/FGS 미시작; 정상 명시 재생→FGS ACK→오디오 Stop→검사용 Browser 해제→공식 내비 열기→실제 Activity onStop→같은 Host/session의 새 측정·수신 UPDATE 저장. 모델/대화/선호 버전 무변경, lease 종료·Activity 복귀, 이력 유지.
- `audio-focus-recovery` 2,435ms PASS: 실제 AudioManager에 합성 내비 안내 포커스를 요청해 TRANSIENT→GAIN, 취소 후 무복귀, 영구 LOSS 무자동복귀를 확인했다. 실제 내비 음성이나 TTS와 동시에 청취한 검사는 아니다.
- `journey-ui` 6,614ms PASS: 12개 관심사, 기록 지도,261개 실루엣/22페이지, 지역 상세, 자체 지도 부재,360/600/900dp 컨트롤 배치를 확인했다. 처음90초 host timeout은 원인 미확정으로 보존한다. 같은 제품·검증 APK 재검사는 통과했다.
- `app-playback` 5,960ms PASS: 실제 제품 공개 TTS 완료·저장·Stop. `music-runtime` 6,682ms PASS: Media3 합성 음원·Room의 재생/중단/복귀/위치 커밋.
- `app-location-live` 19,998ms PASS: 명시 foreground lease 없이 Activity 닫기/재열기의 실제 onStop, 구간 종료·표본 수 안정, 복귀 새 구간/새 유효 UPDATE, cleanup active0. Home/background 성공과 구분한다. 첫 시도는 새 UI에서 설정 페이지를 열지 않는 기존 검사 가정으로 BLOCKED였고, 검사코드만 고쳤다.

모든 기기 검사의 제품 APK는 동일하다. 앞의5종은 이전 검증 APK, 마지막 위치 검사는 설정 페이지 진입만 바꾼 최종 검증 APK다. 제품 Lint 이후 수정은 이 검증 코드와 서버 환경 parser뿐이다. `.env`의 기존 공개 `PETASOS_NAVER_MAPS_ANDROID_KEY_ID`를 서버의 엄격한 허용 목록에 추가해 재시작 오류를 해결했고 기존 인증서·설정으로 HTTPS health200을 확인했다. API 키·계정 보관 설정·만료 기한은 바꾸지 않았다.

최종 시각 검수에서 기본 host 렌더러는 View/window가 표시 상태여도 화면을 비웠고, 에뮬레이터 로그에 ColorBuffer/texture 오류가 있었다. 기존 AVD를 이번 실행만 `-gpu swiftshader`로 다시 열어 동일 APK의 소개·듣기 화면을 확인했다. [최종 화면](../../artifacts/milestone-35/product-listen-software.png). AVD 영구 설정·제품 코드는 바꾸지 않았으며 첫 UI 시간 초과의 원인으로 단정하지 않는다.
