# 격리 데모 화면 — dev.33

제품 앱과 별개인 **Petasos Demo**에서 듣기 중심의 어두운 검토용 화면을 제공한다. 최종 제품 디자인 승인을 대신하지 않는다. 기존 제품 설치·동의·청취 이력을 유지하며 실제 마이크, 음원, 위치, 경로 API, 모델을 호출하지 않는다. 공개 이야기의 길이와 합성 경로를 실제 재생 코어에 전달하는 무음 시연이다.

## 파일 책임

| 위치 | 책임 |
| --- | --- |
| `app/src/demo/res/layout/activity_demo.xml` | 화면 배치·조작 영역 |
| `app/src/demo/res/values/{colors,styles,dimens,strings}_demo.xml` | 색상·스타일·간격·고정 문구 |
| `app/src/demo/res/values/demo_runtime_strings.xml` | 실행 결과 표시 문구 |
| `app/src/demo/java/com/petasos/app/demo/DemoPresentation.kt` | 불변 실행 결과를 공유 mapper/renderer와 화면에 연결 |
| `demo/DemoActivity.kt` | 실제 CarUx 구독·Activity 가시성·명령 전달 |
| `demo/DemoSessionController.kt` | 단일 worker·명령 취소·초기화·종료 확인 |
| `demo/DemoScenarioSession.kt` 등 | [dev.32 실행부](demo-engine.md): 실제 코어·Room·길이 기반 편성 |

스타일을 변경할 때 runtime이나 지역 선택 알고리즘을 수정할 필요가 없다. 화면의 제목·후보·완료 개수는 실행 결과이며 예상 결과를 UI에서 생성하지 않는다. 제품 Host·Controls·서비스는 생성하지 않는다.

## 시연 순서

1. Pleos에서 **Petasos Demo**를 실행한다. 실제 CarUx 상태를 확인한 정차 상태에서만 조작 화면이 열린다.
2. 재생을 누르고 `+30초`, `+1초` 4회를 누르면 34초 이야기 뒤 10초 음악 간격이 시작된다. 실제 소리는 재생하지 않는다.
3. `+1초` 2회 뒤 정지한다. 음악 2초 지점이 중단 상태로 저장된다.
4. `서울 → 유성` 경로를 선택하고 `+1초` 뒤 재생한다. `+1초` 8회 뒤 유성 이야기를 시작한다. 이전 안성 후보는 재생하지 않는다.
5. 처음부터 버튼은 이전 시연의 저장 종료와 정리를 확인한 뒤 새로운 시연을 만든다. 제품 기록은 유지한다.

시간은 버튼으로만 흐른다. 화면 재진입만으로 재생하거나 시간이 흐르지 않는다. 경로는 압축된 고정 시연이며 실제 주행 ETA를 표현하지 않는다. 위치 입력 중단은 관측 만료 동작을 확인하는 조작이다.

## 수명주기와 검사 범위

실제 `CarPolicySource`의 UX 제한과 resumed 상태를 확인한다. UNKNOWN/RESTRICTED와 화면 이탈은 즉시 명령 권한을 철회하고 대기 작업을 취소한다. worker는 명령 실행과 1초 이하의 시간 전진 구간마다 권한을 확인한다. 이미 진행 중인 구간 안의 가상 시각까지 한 번 더 이동할 수 있지만, 출력 시작·완료는 현재 권한을 다시 확인한다. 이는 실시간 오디오 중단 지연 측정이 아니다.

기기 검사는 실제 CarUx 관측, 실제 Activity와 View 버튼, runtime/Room, instrumentation으로 호출한 pause/resume, Activity finish·durable close를 검사한다. instrumentation pause/resume는 OS Home/background 또는 실제 차량 Suspend 검수와 구분한다. 주행 상태를 주입하거나 가짜 PARKED로 열지 않는다.

[집계](../../artifacts/milestone-33/verification.json): JVM1,030/83·호스트13·빌드/Lint/audit PASS, 실제 기기5.748초 PASS다. source610개·최종APK와 설치 해시·다른 앱 ID 및 제품 dev.31 보존을 확인했다. 실제 화면2장을 2560×1440에서 검토했으며 모든 조작이 겹침·잘림 없이 보인다. [검토 화면](../../artifacts/milestone-33/demo-screen.png). 기존 dev.32 검사는 컴포넌트0인 실행부이고 dev.33은 정확한 launcher Activity1개를 허용하는 별도 `--profile ui` 경계 검사다. 제품 권한 검사는 별도 원래 Lint 설정을 유지한다.

사용자의 화면 디자인·조작감 검토, 사람 발화, 실주행·실제 선택 경로 일치, Suspend 등은 자동 검사 완료와 별개다. OpenRouter 보관 설정 재확인과 음성 API 대안 선택에 관한 기존 질문도 그대로 남는다.
