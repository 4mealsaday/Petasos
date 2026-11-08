# 격리 데모 실행부 — dev.32

이 문서는 dev.32 실행부 체크포인트를 기록한다. 후속 화면·실제 CarUx 연결과 단일 Activity 경계는 [dev.33 데모 화면](demo-ui.md)을 따른다.

최종 UI에 연결할 **무음 실행부**다. 스타일·레이아웃 변경과 데모 Activity는 아직 포함하지 않았다. 제품 앱은 `com.petasos.app`, 이 실행부는 `com.petasos.app.demo`이며 데이터와 키 이름을 공유하지 않는다. 기존 제품 설치와 사용자 동의·기록을 유지한다.

## 구현

| 위치 | 책임 |
| --- | --- |
| `app/src/demo/AndroidManifest.xml` | 일반 Application, 권한·컴포넌트·SDK query 없는 데모 시작 경계 |
| `demo/DemoFixtureStore.kt` | demo package/worker 확인, 고유 DB·키, 고정 공개 자산 설치, 종료 확인 후 자기 파일만 정리 |
| `demo/DemoVirtualTime.kt` | 정확한 중간 시각·단계/FIFO, 취소, 5분 전진 제한, pending1,024/event10,000 상한 |
| `demo/DemoSilentOutputs.kt` | 실제 음성/음원 없이 대본의 설치 길이와 10초 음악 fixture에 따른 시작·정지·완료 callback |
| `demo/DemoScenarioSession.kt` | 실제 Coordinator/Runtime/Room/planner/자동 story·music bridge 조립, 명령과 가상 경로·관측 입력 |
| `demo/DemoScenarioSnapshot.kt` | 공개 제목·ID·모드·시각·위치·계획 revision·완료 개수만 불변 복사해 전달 |

UI 바인딩과 디자인을 제외한 구현이다. 제품 `PetasosApplication`·MediaService·Host·Controls는 생성하지 않는다. 실제 마이크·TTS·위치·네이버·모델 호출도 이 실행부에 없다. 단일 worker에서 생성하고 조작해야 한다. UI worker 연결·차량 제한 구독·동시 수명주기 취소는 다음 화면 단계에서 검증해야 하며 현재 완료로 표시하지 않는다.

빌드 type/앱 ID 분리는 [Android build variants](https://developer.android.com/build/build-variants), 권한·Application 교체는 [manifest merge 규칙](https://developer.android.com/build/manage-manifests)을 따랐다. 종속 manifest가 다시 추가할 수 있으므로 선언만 믿지 않고 최종 병합 결과와 설치된 package를 검사한다.

## 시나리오와 시간 의미

시작은 공개 S01(문화역서울284)의 설치된 34초 길이다. 다음 10초 무음 음악까지 더해 가상 44초의 미래 지역을 선택한다. 첫 경로는 안성(S06), 다른 고정 경로는 유성(S10)이다. 서울→안성/유성의 압축 시간은 실제 도로 ETA가 아니다.

Play/Stop/Next/Previous는 기존 runtime 이벤트로 전달한다. 음악 2초 시점 Stop은 정상 완료가 아니라 중단 저장이다. 1초를 더 기다리고 경로를 바꾼 후 Play하면 남은 8초 음악을 재개하고, 완료 저장 뒤 변경한 지역 이야기를 시작한다. 오래된 후보는 재사용하지 않는다.

큰 시간 전진도 모든 중간 이벤트를 정확한 시각에 처리한다. 같은 시각에는 관측 → 출력 → 타이머 → 재편성 순서이며, 각 callback이 추가한 runtime FIFO 작업을 다음 예약 callback 전에 완료한다. 음악 종료시각에 남은 길이0으로 기존 후보부터 지워지는 것을 피하고 완료·저장 결과로 재편성한다. 경로 변경이나 권한 상실은 최종 출력 권한에서 즉시 거부한다.

관측은 시작 후 180초까지 명시된 1Hz 합성 fixture다. `OBSERVATION_GAP` 선택은 이후 표본 공급을 중단한다. snapshot 읽기·Play·시간 전진의 종료 자체는 측정 시간을 갱신하지 않는다. 위치/지역 카드 적립 증거를 만들지 않으며 제품 route_point를 작성하지 않는다.

시작 목록은 문단 attempt의 실제 저장된 Started 순서이며 재시작은 같은 이야기 ID가 반복될 수 있다. 공개10편은 현재 모두1문단이다. 표시 snapshot은 명령·위치·콘텐츠 권한이 아니다. 음악 일시정지에서는 이전 이야기 제목을 현재 이야기로 표시하지 않는다.

`close()`의 true는 runtime의 durable-close ACK를 받은 경우만 반환한다. 실패 시 DB·키를 보존하고 정리/Reset 성공으로 표시하지 않는다. 종료 실패 뒤 재시도는 기존 runtime 계약을 따른다. 앱 종료 후 남은 fixture를 자동 탐색·정리하거나 복원하는 기능은 이번 범위에 없다.

## 검사와 한계

[최종 집계](../../artifacts/milestone-32/verification.json)는 완료 후 실제 결과를 기록한다. 정적 boundary 검사는 transitive call graph나 실행 격리 전체를 증명하지 않는다. 기기 검사도 실제 차량·음성·최종 화면 수용을 대신하지 않는다.

```sh
./scripts/gradle.sh -PpetasosTestBuildType=demo :app:testDemoUnitTest :app:assembleDemo :app:assembleDemoAndroidTest
./scripts/gradle.sh -PpetasosTestBuildType=demo -PpetasosDemoLint=true :app:lintDemo
./scripts/gradle.sh :app:lintPrivacy
python3 scripts/audit-demo-boundary.py --manifest app/build/intermediates/merged_manifests/demo/processDemoManifest/AndroidManifest.xml --source-dir app/src/demo/java --output artifacts/milestone-32/demo-boundary.json
python3 scripts/probe-demo.py
```

권한 없는 demo manifest로 공유 제품 speech adapter를 분석한 첫 Lint는 `MissingPermission` 22건으로 실패했다. `lint-demo.xml`은 사용하지 않는 speech 파일4개의 해당 검사만 제외하며, `petasosDemoLint=true`는 단독 `:app:lintDemo` 호출에만 허용한다. 제품 Lint는 원래 설정으로 별도 실행한다. 제품 권한 검사나 demo의 실제 권한0 경계가 완화된 것은 아니다. 이 예외는 전체 call graph의 도달 불가능성을 증명하지 않으며, 음성을 demo에 연결할 경우 재검토해야 한다. [공식 파일별 Lint 설정](https://developer.android.com/studio/write/lint)을 사용한다.

메모리 제약 때문에 에뮬레이터를 종료한 뒤 빌드/Lint를 순서대로 실행하고 JVM 종료를 확인한 뒤 기기 검사를 실행한다. 일반 `probe-pleos.py`는 제품 앱용이며 demo variant를 받지 않는다.

남은 화면 작업은 실제 CarUx/Activity gate와 worker 명령 연결, 공유 mapper/renderer, 선택한 최종 디자인, 사용자가 조작할 시연 패널과 시각 검수다. 사람 대화·실주행·OpenRouter 설정 재확인 등 기존 미완료 범위도 그대로 남는다.
