# 홈·내 여행 UI와 내비 동시 표시 — dev.36

2026-11-09 사용자 정정을 현재 제품 방향으로 삼는다. **Pleos 내비 지도 + Petasos 원형 말하기 버튼 + 하단 가로 자막/음악 카드가 동시에 보여야 한다.** 내비 앱으로 화면을 전환하거나 두 앱을 멀티윈도우로 여는 것은 이 요구의 완료가 아니다. 후속 요청에 따라 데모는 [실제 내비 목적지·공통 가상 GPS 재생](native-navigation-demo.md) 방향으로 조사했으며 전체 통합은 아직 미완료다.

## 현재 반영한 화면

- 기본 홈: 큰 ON/OFF 동행 버튼과 `나의 기록` 두 버튼. 저장된 소개 완료 여부에 따른 소개 화면은 제거했다.
- 내 여행: `내 여행` 제목 아래 기존 기록 지도·지역 앨범·지난 여정을 바로 표시한다. 듣기/설정 상단 탭과 소개/재생 준비 카드는 없다. 오른쪽 아래 톱니바퀴에서 설정을 연다.
- 설정: 기존 위치 동의·관심사·여정 기능을 유지한다. 기록 다시 듣기와 기존 오디오 기능은 고급 재생 영역에 둔다.
- 파랑·하늘색 그라데이션, 반투명 표면, 밝은 테두리, 둥근 모서리와 그림자로 유리 질감을 표현했다. 실제 배경 굴절/실시간 blur를 구현했다고 주장하지 않는다. 홈 버튼은 기본 208dp·28sp이며 600dp 창 너비를 기준으로 세로/가로 배치된다. 실제 화면 검수 후 1000dp 이상에서는 320dp·40sp·88dp 아이콘, 브랜드48sp로 확대했다. 설정 아이콘은 기본72dp/큰 화면88dp이며, 기록 지도 높이는 기본340dp/큰 화면600dp다. 기록 글씨도 큰 화면의 제목40sp·본문24sp·버튼26sp로 분리했다.

`JourneyShell`은 페이지·표시 상태만 소유하고 `CompanionControls`가 홈 동작을 담당한다. 홈/고급 재생 레이아웃, `colors_glass.xml`, `dimens_journey.xml`, `styles_journey.xml`, 유리 표면 drawable을 분리했다. 스타일 수정은 지역 선정·기록 저장·재생 런타임을 변경할 필요가 없다.

## 동시 표시의 실제 상태

후속 단기 진단에서는 **실제 Pleos 내비 화면 위 Android 공식 overlay 두 창 표시·버튼 입력·60초 자동 제거에 성공했다.** 별도 무데이터 진단 패키지에만 임시 권한을 부여하고 default로 복원·제거했다. 제품 ON 연결이나 일반 설치 승인 절차의 검증은 아니다. 현재 Pleos 2.1.0 이미지 사용자10에서는 공식 Android overlay 승인 설정 Intent를 처리하는 Activity가 없다. NaviHelper 공개 API에는 내비 위 Petasos UI를 부착할 표시 영역이나 내비 실제 가시성/창 경계 계약도 없다. 상세 근거와 공식 링크는 [내비 overlay 조사](pleos-navigation-overlay.md)에 있다.

제품 adapter가 아직 연결되지 않아 **현재 홈 ON은 연결 안내를 표시하고 OFF를 유지한다.** 이 버튼은 위치 수집·재생·내비 전환·권한 설정을 시작하지 않는다. 제품에는 `SYSTEM_ALERT_WINDOW` 권한을 선언하거나 adb/appops 강제 허용도 하지 않았다. `PleosCompanionDisplaySupport`는 권한 상태와 설정 화면 존재를 각각 읽기 전용으로 보고하며, 검증된 내비 표시 adapter가 없으므로 `navigationSurfaceAvailable=false`다. 표준 Android overlay 자체가 불가능하다는 결론이나 모든 Pleos 버전 미지원 판정은 아니다.

원형 말하기 버튼·가로 자막/음악 카드의 **외부 내비 부착은 미구현**이다. 현재 제품은 요청한 주행 화면을 완성한 버전이 아니다. 마이크 false와 Virtual Console 보류도 그대로다.

## ON/OFF 수명주기 기반

- 위치 동의/Activity 표시/일반 Play만으로 동행을 켜지 않는다. 위치·자동 편성에는 명시 ON의 유효한 foreground lease가 필요하다.
- `prepareVisibleCompanion` → 실제 session commit → foreground ACK → 정확한 future 소비를 준비했다. 공개 UI에서는 현재 표시 지원 gate 때문에 호출하지 않는다.
- 일반 `prepareVisiblePlayback`은 오디오만 준비한다. 따라서 기존 고급 재생은 위치 동행을 시작하지 않는다.
- `disableCompanion`은 캡처·자동 편성·자막 권한을 즉시 철회하고 오디오 Stop을 요청한다. 결과 future는 source 정리 barrier이며 실제 오디오 Stop ACK를 뜻하지 않는다. 저장된 동의·지역 기록·여정 row는 삭제하지 않는다.
- 향후 overlay 자막은 Activity 표시 권한과 독립적인 owner identity로 분리한다. 이 기반이 실제 window 부착이나 주행 승인 완료를 뜻하지 않는다.

dev.35의 `app-companion`/`app-location-live` 성공은 당시 Play 기반 동행 구조의 역사적 결과다. dev.36의 ON 성공으로 재사용하지 않는다. 현재 해당 검사는 명시적으로 BLOCKED를 반환하며, 새 `journey-ui` 검사는 홈 두 버튼·기록 탐색·큰 버튼·지원 불가 ON의 무시작 및 OFF에서 위치 기록/선호 무변경을 검사한다.

## 검증

최종 제품 APK·검사 APK 빌드, Lint, demo Kotlin 컴파일을 통과했다(`build-release-check.log`). JVM 1,097개/89 suites와 호스트 검사78개가 통과했다. 실제 Pleos에서 `journey-ui-verified` 16,823ms와 `app-playback-final` 13,909ms PASS다. UI 검사는 홈/기록/설정·261개 앨범·360/600/900dp 배치와 OFF에서 위치 구간/표본/선호 무변경을 확인했다. 공개 TTS 출력·완료 저장·Stop과 일반 Play의 위치 무시작도 확인했다. 앞선 CarUx 상태 해제로 중단된 결과는 `journey-ui-final`에 보존했다. 결과 집계는 `artifacts/milestone-36/verification.json`에 둔다.
