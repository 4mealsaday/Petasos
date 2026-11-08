# 이야기 길이에 따른 지역 동적 편성

2026-11-08 작업 기록. **dev.17 최종 빌드와 아래 범위의 검증을 완료했다. 실도로 예측 입력은 여전히 미연결이다.** 최종 판정은 [milestone-16 검증 기록](../../artifacts/milestone-16/verification.json)에 집계했다. 이전 단계의 PASS나 중단 전 일부 결과를 이번 변경 전체의 검증으로 사용하지 않는다.

## 기준과 범위

사용자가 확인한 흐름은 `곧 지날 지역의 이야기 준비 → 생성한 이야기의 길이로 다음 재생 시점 계산 → 그때 지날 지역 선정 → 이동·대화·지연에 맞춰 재계산`이다. [보관된 v0.7](../archive/Petasos_v0.7_notion_2026-11-06.md)의 앞으로 지날 지역 준비와 예상 길이 활용 방향을 따르되, 현행 [v0.8 동작명세](../Petasos_동작명세.md)의 음악 정상 완료 경계·사용자 상호작용 우선순위와 [데이터 계약](../Petasos_데이터_API_계약.md)의 위치 품질·실제 위치와 예측 분리 규칙을 유지한다. 과거 명세를 현행 명세로 대체하지 않는다.

이 구현은 검토된 경로 예측을 받는 편성 알고리즘과 제품 Host 연결을 추가한다. 아직 실제 도로의 경로·지역별 도착 시간을 공급하는 어댑터까지 완성한 것은 아니다. 생성은 설치된 검토 콘텐츠 중 대상 지역의 적격 seed가 있을 때 기존 생성 API로 준비한다. 임의의 지역을 검색하거나 미검토 자료를 수집하는 기능은 포함하지 않는다.

## 대상 지역과 재생 시점

[RegionalStoryPlanner](../../app/src/main/java/com/petasos/app/planning/RegionalStoryPlanner.kt)는 재생 명령 대신 `RegionalStoryTarget`을 만든다. 검토된 예측은 같은 세션·단조 시계·경로 revision에 속한 지역별 진입/이탈 예상 시간과 방문 ID를 제공한다. 같은 지역을 다시 지나가는 경우도 방문 ID로 구분한다.

1. 최초 자동 시작에는 현재 시점을 기준으로 현재 또는 곧 지날 유효 구간을 고른다.
2. 이야기 재생 중에는 현재 문단의 남은 예상 시간, 뒤 문단의 예상 시간, 실제로 다음에 제안된 음악 한 곡의 길이를 합산한다. 음악 재생 중에는 그 곡의 실제 위치를 기준으로 남은 길이를 계산한다.
3. 계산된 다음 이야기 시작 시점에도 아직 지나치지 않은 첫 구간을 다음 대상으로 고른다. 예를 들어 이야기의 남은 시간이 60초이고 다음 음악이 약 68.5초라면 약 128.5초 뒤의 지역을 준비한다.
4. 실제 위치·경로 revision·예측 유효기간·대화 상태가 바뀌면 다시 계산한다. 경로 예측이 현재 확정 지역과 어긋나면 그 예측을 사용하지 않는다. 검토된 예측이 없으면 신선하고 유효한 실제 현재 지역만 후보로 삼으며, 미래에도 그 지역에 머문다고 주장하지 않는다.

앞으로 30초 이내 접근 허용과 최대 10분 계획 범위는 **현재 코드의 조정 가능한 공학적 기본값**이다. 노션에서 확정한 수치나 검증된 실주행 정책으로 해석하지 않는다. 위치 수신이 10초를 넘겨 오래되거나 품질이 유효하지 않으면 지역 계획을 철회한다. ETA만 바뀌고 같은 방문이 계속 유효하면 기존 생성 작업을 유지할 수 있지만, 허가 검사는 항상 최신 시각·예측으로 수행한다.

## 실제 시작과 일시적인 전이 공백

[PlaybackLookahead](../../app/src/main/java/com/petasos/app/planning/PlaybackLookahead.kt)는 `StartOutput` 요청 시점부터 시간을 빼지 않는다. 실제 SDK `STARTED`가 영속 저장된 뒤 해당 시도의 시간을 시작하며, [RoomStoryTimingStore](../../app/src/main/java/com/petasos/app/storage/RoomStoryTimingStore.kt)는 설치된 정확한 story version과 문단 순서에 맞는 예상 길이를 읽는다. 음악의 새로운 실제 위치가 도착하면 그 위치가 이전 시간 기준을 대체한다.

예상 길이가 0이 됐다고 재생 완료·지역 도착·체크포인트를 만들지 않는다. 길이가 없거나 출력이 예상보다 늦게 끝나면 다음 경계를 알 수 없는 상태로 돌아가 새 증거를 기다린다. [PlanningBoundaryMemory](../../app/src/main/java/com/petasos/app/planning/PlanningBoundaryMemory.kt)는 정상 완료 커밋·재개·실제 시작 대기 사이의 짧은 공백에서만 직전의 유효한 예측을 5초 미만 유지한다. 이 5초도 공학적 기본값이며, 대화·정지·차단·취소 세대 변경을 넘겨 보존하지 않는다. 이미 시작한 출력의 예상 길이를 초과한 상태를 이 장치로 연장하지 않는다.

[PlaybackRuntime](../../app/src/main/java/com/petasos/app/runtime/PlaybackRuntime.kt)은 실제 시작을 저장하고 현재성/fence를 다시 확인한 다음, 정확한 자동 offer 소비와 시작 이력 반영을 `OutputStarted` 상태 전이보다 먼저 알린다. 이후 Host가 다음 지역을 계산하므로, 재계산이 방금 시작한 이야기의 offer를 교체해 시작 이력을 잃는 순서 경합을 막는다. 진행 중인 이야기는 지역이 바뀌었다는 이유로 끊지 않으며 자동 다음 이야기는 Coordinator의 실제 음악 정상 완료 경계를 따른다.

## 생성과 자동 시작 연결

[AutomaticStoryGeneration](../../app/src/main/java/com/petasos/app/planning/AutomaticStoryGeneration.kt)은 한 번에 하나의 지역 생성 작업만 유지한다. [PetasosSessionHost](../../app/src/main/java/com/petasos/app/platform/android/runtime/PetasosSessionHost.kt)는 `대상/seed 검증 → 기존 생성 준비 → 응답 검증과 설치 → 설치된 후보 재조회 → OfferStory` 순서로 연결한다. 생성 완료 자체가 직접 재생 권한을 부여하지 않는다. 설치 전과 실제 출력 직전에 세션·의도·지역·경로·출처 권리 등 현재성을 다시 확인한다.

대화 진입·정지·계획 교체 시 이전 작업 권한을 철회하고 늦은 응답을 사용하지 않는다. 같은 결정에서 성공적으로 설치한 새 version을 다시 새 생성 seed로 오인하지 않도록 이전 seed와 결과 version을 함께 기억한다. 실패 결과도 제한된 이력에 보관해 동일 결정의 반복 재시도 루프를 피한다. 지역 생성 대기 중에도 기존 관심사 후보와 음악/대기 정책은 Coordinator를 통한다.

첫 재생에서 설치 목록의 첫 이야기를 임의 선택하던 경로를 제거했다. 화면의 첫 항목은 `지역 이야기 자동 편성`이며, 사용자가 실제로 다른 이야기를 선택했을 때만 명시 선택으로 전달한다. 유지된 spinner 값만으로 새 명시 선택을 만들지 않아 재개 중인 이야기·음악을 덮어쓰지 않는다.

## 남아 있는 실제 환경 제한과 검증

현재 제품의 `RegionalRouteForecastSource`는 **UNAVAILABLE**, 위치 의미 어댑터는 **UNKNOWN**, 지역 경계 조회는 **NONE**이다. 목적지/경유지의 남은 시간만으로 전체 도로 형상이나 중간 지역을 추정해 채우지 않았다. 따라서 실제 도로에서 지역을 자동 생성·재생하는 end-to-end 검증과 이번 경로의 새 실제 모델 호출은 아직 수행하지 않았다. 합성 경로·상태 기반 회귀가 통과하더라도 이 제한을 해제하는 증거가 되지 않는다.

빌드 도중 컴퓨터가 메모리 부족으로 종료되어 작업을 재개했다. 중단 전 기록은 `artifacts/milestone-16/before-restart/`에 구분해 보존한다. 재개 후에는 메모리를 낮춘 단일 worker 설정으로 컴파일·테스트·패키징 등 단계를 순차 실행하고 최종 소스 기준 결과를 다시 확인한다. 최종 Android 빌드·JVM·기기 결과는 재시작 후 소스 기준이며 중단된 R8/Lint를 성공으로 집계하지 않았다.

제품 마이크는 기존 SpeechService 원문 로그 차단 사유로 계속 비활성이고, Virtual Console 연결은 보류한다. 이번 작업을 위해 사람 발화나 실제 차량 상태 변경을 요청하지 않는다.

## 최종 검증 결과

- JVM **599개/45 suite**, 실패·오류·skip 0. 호스트 **117개**는 재시작 전 실행한 같은 도구 코드의 결과다.
- privacy·debug 앱/검증 APK 빌드와 Lint PASS. Gradle heap 1GiB·worker 1·Kotlin in-process, 시험 JVM 256MiB로 단계별 실행했다.
- 동일 최종 APK 쌍의 기기 검사 **15종 PASS**: 자동편성/길이/권리, 생성 저장, 위치 저장, 명령, scripted runtime, 실제 TTS/정지, 음악, 복구, 요약, resume, MediaSession, 이동 수명주기, 제품 이야기→음악, 차량 UX. 합성 경로 검증을 실도로 검증으로 표시하지 않는다.
- 새 이동의 일반 Play가 이전 이야기를 다시 지정하지 않고 자동 음악을 선택하는 실제 제품 동작, 이야기→음악·정지/위치 재개·정상 종료 후 다음 출력을 확인했다.
- 알려진 앱 SDK 로그 sink 정적 검사·privacy 설치/전면 실행 smoke PASS. 기존 별도 SpeechService 로그 문제는 해제되지 않았다.
- 최종 258개 소스가 일치했다. 05:28:05Z 설치 해시·전면 실행, 앱/서비스 활성 녹음 각각 0, HTTPS health 200과 기존 reverse 복구를 확인했다. 이는 시점 관측이며 연속 무녹음 증거나 새 모델 호출 검증이 아니다.

최종 앱/검증 APK는 `.local/builds/dev17/privacy-final/`에 보존한다. [APK 해시](../../artifacts/milestone-16/final-apks.json), [전체 검증](../../artifacts/milestone-16/verification.json), [재시작 복구](../../artifacts/milestone-16/restart-recovery.json)를 따른다. 다음 단계는 검토된 실제 위치 의미·지역 경계·경로별 통과 시간과 지역별 근거 공급을 연결하는 것이다.
