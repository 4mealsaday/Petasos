# UI 표시 계층 분리 — dev.31

재생과 기록 설정의 **상태 계산 → View 표시 → 실제 명령** 책임을 분리했다. 색상·배치·문구와 사용자 데이터는 그대로이며, 최종 UI 디자인은 아직 선택 대기다.

## 파일 책임

| 파일 | 책임 |
| --- | --- |
| `presentation/PlaybackPresentationState.kt` | Android/Host에 의존하지 않는 재생 표시 입력·상태·열거형 |
| `presentation/PlaybackPresentationMapper.kt` | 현재 상태를 버튼 활성화·상태·안내 문구 종류로 계산 |
| `presentation/PlaybackPresentationRenderer.kt` | 기존 View의 가시성·활성화·리소스 문구만 적용 |
| `presentation/PlaybackControls.kt` | 실제 MediaBrowser/Host, 차량 제한, Activity 수명주기, 목록, 클릭과 마이크 권한 처리 |
| `presentation/TripPresentationState.kt` | 위치 동의·권한 단계·저장/이동 종료의 순수 표시 상태 계산 |
| `presentation/TripControlsRenderer.kt` | 기존 기록 버튼·선택 위젯의 표시 속성만 적용 |
| `presentation/TripControls.kt` | 실제 동의 저장, 권한 요청, 기록 조회, 선택, 이동 종료 처리 |

색상·글꼴 역할·간격은 `res/values/colors.xml`, `styles.xml`, `dimens.xml`, 배치는 `res/layout/`, 문구는 `strings*.xml`에서 변경한다. 새로운 정보 구조에는 mapper/renderer 및 목록·입력 바인딩 변경이 필요할 수 있지만 편성·재생·저장 로직과 분리되어 있다. 전체 UI를 완전히 선언형으로 바꾸거나 별도 앱 모듈을 추가하지 않았다.

## 유지한 동작

- 실제 차량 제한이 UNKNOWN/RESTRICTED이면 Host 조회 전에 화면부터 숨긴다. 표시 모델은 실행 권한이 아니며 모든 명령은 기존 실시간 조건을 검사한다.
- 재생/정지/다음/이전의 Media3 명령 가능 여부, 이야기 준비·여행 종료·대화 상태의 표시 우선순위를 유지한다.
- 제품의 실제 마이크 입력 차단은 그대로다. 합성 표시 상태에서 Talk 버튼을 확인해도 제품 음성 입력이 활성화되지는 않는다.
- 여행 종료 중 기존 위치 기록 동의 ON은 끌 수 있고 OFF는 켤 수 없다. 저장 중 쓰기와 기록 읽기의 가용성은 구분한다.
- renderer는 클릭 리스너·목록 어댑터·선택·입력 중 관심사·비동기 결과 문구를 교체하지 않는다.
- UI XML·색상·문구·DB 스키마·콘텐츠·지역/랜드마크 정책은 변경하지 않았다.

## 검증 범위

순수 mapper 회귀와 실제 Android 위젯 통합을 나눠 검사한다. `car-policy-ui`의 새 본문은 분리된 미부착 View에 합성 입력을 주고 실제 mapper/renderer를 실행한다. 별도의 기존 본문은 실제 CarUx 상태를 읽고 연결 종료를 검증한다. 차량 상태는 변경하지 않는다.

미부착 View 검사 본문은 제품 Host/DB·권한 요청·실제 음성/미디어/위치 명령을 호출하지 않는다. **instrumentation의 정상 대상 Application 시작은 그대로이며, 시작 복구가 제품 DB에 접근할 수 있다.** 따라서 전체 프로세스가 제품 DB로부터 격리됐다는 증거로 사용하지 않는다. 실제 제품 MediaBrowser/TTS와 MainActivity 닫기/재열기 위치 회귀는 별도 결과로 기록한다. 후자는 Home/background 검수가 아니다.

JVM 1,018개/81 suite, 호스트 73개, privacy 빌드·Lint·정적 audit와 동일 APK 기기 3종/smoke가 PASS다. 소스 581개와 설치 APK 해시도 일치한다. 위치 회귀는 17.929초, 복귀 새 구간1·새 점2·새 유효 UPDATE1·정리 후 active0이다. 최종 검사 결과는 [dev.31 집계](../../artifacts/milestone-31/verification.json)를 따른다. 최종 스타일·격리 시연 앱은 이 변경에 포함하지 않으며 [데모 설계](isolated-ui-demo-plan.md)와 사용자 UI 방향 선택 뒤 진행한다.
