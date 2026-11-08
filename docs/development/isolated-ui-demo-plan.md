# 격리 UI 데모 설계 — 구현 전

2026-11-09, dev.31 표시 계층 분리를 기준으로 검토한 다음 단계다. **별도 demo 앱과 시연 화면은 아직 구현·빌드하지 않았다.** UI 방향 선택 뒤 진행하며 기술 계약의 추가 사용자 결정은 필요하지 않다.

## 최소 구성

`demo` build type와 `.demo` applicationIdSuffix를 사용하고 `src/demo`에만 진입점을 둔다. 제품의 일반 debug/privacy/release에는 데모 입력을 추가하지 않는다. 별도 모듈·새 라이브러리·범용 시나리오 편집기·자동 진행·프로세스 종료 후 복원은 첫 범위에 필요하지 않다.

일반 `android.app.Application`과 단일 `DemoActivity`를 사용한다. 제품 `PetasosApplication`은 시작 시 요약/복구 저장소에 접근하고 `PlaybackControls`는 제품 Host/MediaService를 연결하므로 데모에서 만들지 않는다. 현재 instrumentation의 미부착 renderer 검사도 정상 Application 시작을 우회하지 않으므로 완전히 격리된 데모를 대신하지 않는다.

```text
DemoActivity → 실제 주차 제한과 명령 확인 → DemoScenarioSession
                                              ↓
                              기존 runtime/planner + 별도 Room
                                              ↓
              기존 mapper/renderer ← 불변 표시 snapshot ← 실제 실행 결과
```

- `DemoScenarioSession`: 단일 worker가 기존 PlaybackRuntime, Coordinator, Room persistence/planning stores, RegionalStoryPlanner, PlaybackLookahead, ContentPlanner와 자동 story/music bridges를 소유한다.
- `DemoVirtualTime`: 명시적 합성 관측과 무음 출력 callback의 시간/취소를 관리한다. 실제 오디오·마이크·위치·모델·네이버 호출은 없다.
- `DemoFixtureStore`: demo package를 검사하고 고정된 공개 이야기와 고유 demo DB/key를 설치한다. 제품 DB나 동의·기록을 복사·초기화하지 않는다.
- `DemoActivity`: 동일 mapper/renderer에 실제 코어 결과를 전달한다. 실제 CarPolicySource의 주차 제한과 Activity 가시성을 매 명령 제출/실행 시 확인한다. UNKNOWN/RESTRICTED·pause/close에서는 진행을 중단하고 재진입만으로 Play 의도를 복원하지 않는다.

별도 Application 하위 클래스는 초기 동작이 없으므로 필요하지 않다. CarPolicySource는 UX 제한을 먼저 읽으므로 무음 데모에서 전원 권한을 추가할 필요가 없다. 합성 시나리오는 주차 상태를 덮어쓰지 않는다.

## manifest와 데이터 경계

demo manifest에서 application을 일반 Application/단일 launcher로 교체한다. 최종 병합 결과를 검사해 제품 Activity, MediaService, WorkManager/Startup, Room invalidation, profile installer 등 불필요한 service/provider/receiver가 남지 않도록 한다. 허용 permission/component 목록을 검사하며 실제 차량 제한 읽기에 필요한 권한만 남긴다. INTERNET, 마이크·위치·Fused·STT/TTS/Navi, FGS, 부팅·wake lock과 SDK query/metadata는 제외한다. 종속 라이브러리 병합 결과를 실제 APK로 확인한다.

demo source는 제품 Host/서비스/Controls, 실제 speech/location/navigation adapters, 네트워크 client와 설정 파일을 참조하지 않는다. 기존 privacy 변환과 알려진 로그 sink 검사를 그대로 적용한다. `.demo` package·다른 UID·별도 데이터 경계와 명시적 DB/key 이름을 검증한다. demo DB 정리도 소유한 정확한 파일/key만 대상으로 한다.

## 시간과 명령 의미

Play/Stop/Next/Previous는 기존 runtime 명령을 실행한다. 선택 이야기 Play는 `playInstalledStory()`가 받는 IDLE/PAUSED·READY·pending commit 0 조건을 지킨다. 첫 화면의 이야기 선택은 정지 상태로 제한하거나 기존 StorySelectionIntent와 Stop → 저장 ACK → 선택 재생 순서를 구현한다. 표시 선택만으로 재생 상태를 바꾸지 않는다.

별도의 시연 조작은 제한된 가상 시간 전진, 고정된 합성 경로 선택, Reset만 제공한다. arbitrary 좌표/URL/개인 문장/다음 이야기 강제 지정은 받지 않는다. 화면은 코어 snapshot·현재 target/revision·저장 결과를 표시하며 예상 결과를 직접 주입하지 않는다.

시간을 크게 전진해도 중간 due event를 순서대로 처리한다. 동일 시각의 관측/경로 변경 → 권한 갱신 → 출력 완료 → 저장 ACK/재편성 순서를 고정한다. 관측 freshness는 fixture 표본만 갱신할 수 있고 시계 전진이나 render가 캐시를 새 관측으로 만들지 않는다. callback token/epoch·bounded queue/timer/event budget으로 취소와 reset 후 늦은 결과를 차단한다.

`PlaybackRuntime.close((Boolean) -> Unit)`의 성공 여부를 그대로 유지한다. 정상 저장·종료 ACK 전 Reset 성공을 표시하거나 DB를 삭제하지 않는다. 실패/미확정 종료를 성공으로 숨기지 않는다.

## 구현 후 수용 검사

1. `.demo` 병합 manifest·package/UID·정적 참조·APK 로그 sink와 일반 제품 variant 분리를 검사한다.
2. 동일 session을 화면과 자동 검사에서 사용한다. 기존 [대표 지역 전환](representative-route-runtime-scenario.md)의 S01 → 합성 무음 음악 → 경로 변경 후 S10을 재현하고 이전 S06 시작 0·정확 revision·음악 완료 저장 선행을 확인한다.
3. 큰 시간 전진과 작은 전진의 동등성, 중간 Stop/재개, Next/Previous, 경로 변경, 오래된 관측, Reset/종료 실패와 늦은 callback을 검사한다.
4. 실제 주차 제한과 pause/close가 화면 및 대기 명령을 차단하고 자동 재시작하지 않는지 확인한다. 차량 상태 주입이나 가짜 PARKED를 shipping 진입점에 넣지 않는다.
5. 사용자 UI 선택을 반영한 화면에서 직접 조작하고 시연을 검수한다. 제품 프로세스·권한 요청 없이 데모만 실행되는지 확인한다.

이 단계는 **대화형 무음 편성/재생 시연**이며 실제 음성, 실제 선택 경로 일치, 실주행, Suspend, 사람 발화나 최종 시각 디자인 수용을 대신하지 않는다.
