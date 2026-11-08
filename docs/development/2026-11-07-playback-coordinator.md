# PlaybackCoordinator 첫 구현

후속: [dev.5 실행 host·암호화 저장소와 통합 검증](./2026-11-07-runtime-storage.md)을 추가했다. 이 문서는 dev.4 당시의 범위·실행 증거를 보존한다.

기준: 2026-11-07, `0.8.0-dev.4`. [v0.8 동작 명세](../Petasos_동작명세.md)의 재생·대화 규칙을 화면과 SDK에서 분리한 순수 Kotlin 코어다. 앱 화면에 연결한 완성 대화 데모는 아니다.

## 구조와 사용 경계

- `playback/PlaybackContract.kt`: 입력 이벤트, 외부 작업 효과, 토큰, snapshot 계약.
- `playback/PlaybackCoordinator.kt`: 세션 하나의 직렬 상태 전이. `dispatch(event, nowMs)`가 새 상태와 실행할 효과를 반환한다.
- `app/src/test/.../playback/`: 모의 이벤트·확인 응답·단조 시각으로 실행하는 JVM 회귀 검사.
- `platform/pleos/speech/`: 실제 SDK 어댑터. 이번 코어와의 실행 host는 아직 연결하지 않았다.

호출자는 `SystemClock.elapsedRealtime()` 같은 단조 시각을 사용한다. 메서드는 동기화되지만, 반환된 효과 실행은 반드시 하나의 host 큐에서 순서를 유지해야 한다. DB·네트워크·SDK 작업은 Coordinator 잠금 밖에서 실행하고 완료 이벤트를 되돌린다. 타이머도 host가 예약하며 코어는 타이머 토큰과 기한을 검사한다. host 없이 시간만 흘러서는 동작하지 않는다.

사용자 텍스트와 답변 전문은 코어 상태에 두지 않는다. 대본은 ID, 개인 대화는 `PayloadHandle`로 참조한다. handle은 본문이 아닌 host의 불투명 식별자여야 하며 문자열 출력은 가린다. 이것이 실제 암호화 저장을 구현했다는 의미는 아니다.

## 구현한 첫 흐름

준비된 대본의 문단 출력 → 말하기 버튼 → 현재 문단 완료 및 커밋 확인 → 고정 유도 문장 → 입력 요청 → 최종 결과 저장 확인 → 별도 입력 종료 확인 → 답변 요청 → 답변 저장 확인 → 문단별 출력·완료 커밋 → 대본 checkpoint 복귀를 다룬다.

- 버튼 350 ms 중복 억제와 대기·유도·입력 중 재입력 취소.
- 정상 출력 Done의 완료 기록과 중단의 interrupted 기록을 구분. 같은 완료를 중복 적립하지 않고 커밋 응답 전 다음 문단을 시작하지 않는다.
- STT 최종 결과 1회, Ended/final 순서 역전, Ready 3초·첫 발화 5초·최대 입력 60초·종료 후 final 500 ms 타이머.
- 최종 발화의 저장 성공과 입력 종료 확인을 모두 받은 뒤에만 생성 요청. 저장 실패 시 생성을 차단한다.
- 답변 총 15초, 같은 논리 요청 ID로 최대 1회 재시도. 늦은 응답·중복 응답·취소된 세대의 콜백과 타이머를 차단한다.
- 답변 저장 성공 후 출력, 실제 완료된 답변 문단만 동결 자료에 포함. 복귀 계획 3초와 제공한 checkpoint 후보 검사.
- 사용자 정지·외부 blocker·Suspend를 별도 의도로 처리. 수동 정지 후 blocker가 해제되어도 자동 재생하지 않는다. 새 세션은 재생 의도가 꺼진 새 인스턴스에서 시작한다.

`InputEnded`는 음성 구간 종료다. `InputStoppedConfirmed`는 독립적으로 확인된 마이크 종료다. 이 둘은 다른 이벤트이며, STT의 Ended·close 반환·1초 유예를 종료 확인으로 변환하는 코드는 없다. 입력/출력이 STOPPING 상태이면 반대 작업을 시작할 수 없다. 종료 확인이 오지 않을 때 잠금이 유지되는 것은 현재 코어의 보수적인 동작이다. 제품 host에서 종료 확인·장애 복구 경로를 구현해야 한다.

`CommitPlayback`은 TX-02에 따른 완료 이력·checkpoint·카드 조건의 원자적 저장을 요구하는 명령이다. `PersistUtterance`와 `PersistAnswer`도 암호화 저장 성공 확인을 요구한다. 단위 검사에서 보내는 성공 이벤트는 시험 입력이며 실제 SQLite/Room 트랜잭션 성공으로 해석하지 않는다. 생성 응답의 JSON 스키마·근거·API 식별자 검증은 host 책임이며, `ValidatedAnswer` 타입만으로 그 검증이 실행되지는 않는다.

## 이번 단계 밖의 기능

음악 재생·위치 기반 편성·경로 의존성 무효화·전체 ResumePlan action·기록 요약 Worker·암호화 저장소·서버 인증/생성·실제 중단 복구는 다음 단계다. 새 대본 선택은 현재 IDLE/PAUSED에서만 지원하며 재생 도중 선택 우선순위까지 완성하지 않았다. 실제 host에서 사용할 어댑터 복구·서비스 재연결도 별도다.

첫 제품 화면 전에는 사용자와 디자인을 논의하고 CarUxRestrictions를 연결한다. 현재 preview의 스타일이나 배치는 변경하지 않았다. 표준 미디어 제어·Suspend는 실제 플랫폼 이벤트를 host에 연결해야 하며, 단위 검사 이벤트 주입을 플랫폼 검증으로 기록하지 않는다.

## 검증

```sh
./scripts/gradle.sh :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
```

실행 결과는 [공식 API 대조 기록](./2026-11-07-official-api-audit.md)의 검증 절에 함께 남긴다. JVM 검사, 실제 SDK probe, 전체 제품 A/R/D/N 검수는 서로 다른 범위다.
