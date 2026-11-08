# dev.12 — 기록 설정·이동 종료·결과 조회

기준: 2026-11-08, 현행 v0.8 유지. 주요 기능을 먼저 개발하고 UI 디자인·데모 시나리오는 이후 정한다는 사용자 결정에 따라 기존 리소스 스타일로 기능 제어를 추가했다. 최종 검증 결과는 `artifacts/milestone-11/verification.json`을 따른다.

## 구현

- `records/VehiclePreferences`, `RoomVehiclePreferenceStore`: 차량 공용 위치 허용과 최대 20개 수동 관심사. 읽기는 preference 행을 생성하지 않는다. 명시 저장만 version/updated_at을 갱신하고 stale version은 거부한다. 다른 JSON 값은 보존한다. 관심사만 처음 저장하면 위치는 OFF다. DATA-12 초기 음성/빈도 값을 저장하지만 미확인 SDK 음성 선택 UI는 추가하지 않는다. 잘못된 관심사 JSON도 위치 동의 철회를 막지 않는다.
- `TripControls`: 위치 기록 켜기/끄기, 별도 Pleos 위치 권한 요청, 쉼표로 구분한 관심사 저장, 최근 이동 선택·새로고침·이동 종료. 플랫폼 권한과 위치 설정을 모두 허용한 기존 이동에서만 위치 소스를 연다. 화면 조회·관심사 저장은 새 세션을 만들지 않는다. 위치 허용이나 기기 권한은 자동으로 변경하지 않는다.
- `PlaybackRuntime.close(callback)`: 실제 출력·입력 stop ACK, 시도/turn/checkpoint 저장, 세션 닫기 이후에만 true를 전달한다. ACK가 없으면 미확정으로 남는다. 최종 세션 닫기 저장만 실패한 경우 명시적 재시도로 처리하며 앞선 대화/재생 저장 실패를 세션 플래그 변경으로 덮지 않는다.
- `RoomTripLifecycleStore`: 모든 세션·구간·시도·turn 정리 확인 후 trip을 `closed`, `user_end`, 실제 종료 시각으로 원자 갱신한다. checkpoint의 playIntent와 비-summary 대기 요청만 취소한다. 요약 작업·기록은 보존하고 반복 종료는 처음 종료 시각을 유지한다. 이전 interrupted 구간/세션의 미확인 시각은 새로 채우지 않는다.
- `PetasosSessionHost.endTrip`: 사용자 종료 의도를 즉시 fence하고 위치 수신을 먼저 차단한다. 종료 중 Play/Next/Previous/새 이야기 요청을 막으며, 위치 동의 철회는 계속 허용한다. 10초는 화면에 미확정을 알리는 한도일 뿐 stop ACK를 만들어내는 타이머가 아니다. DB 종료 성공 뒤 runtime을 해제하고 다음 명시 Play는 새 이동을 만든다. 최신 이동이 closed면 오래된 interrupted 이동을 자동 재선택하지 않는다. 서비스 파괴는 미완료 종료 future를 실패로 마치고 user_end 성공으로 표시하지 않는다.
- `RoomTripResultsStore`: 읽기 전용 snapshot으로 상태·실제 시각·위치 품질별 표본 수·재생 완료/중단/실패·요약 상태·저장된 요약·수집 증명을 조회한다. 미완료/반복 재생을 완료한 이야기 수로 바꾸지 않는다. 수집은 영속 proof 연결로 이동을 귀속하며 여러 이동에 걸친 카드를 이번 이동 신규 획득으로 오인하지 않는다. 출처가 철회된 공개 제목/본문과 임시 대화 payload는 조회하지 않는다.

## UI 교체 경계

기능 binding은 `presentation/TripControls.kt`, 결과 문구 조합은 `presentation/TripResultsFormatter.kt`, 화면 배치는 `res/layout/view_trip_controls.xml`, 고정 문구는 `res/values/strings_records.xml`이다. 기존 `styles.xml`, `colors.xml`, `dimens.xml`을 재사용한다. Room/SDK/Coordinator에는 View나 스타일을 넣지 않았다.

결과는 정차 화면에서만 보이고 화면이 비가시화되면 요약·관심사 텍스트를 지운다. 저장된 요약은 원래 제품 기록 정책에 따라 표시하며 final STT/임시 답변/정밀 좌표/오류 원문은 표시하지 않는다. 현재 화면은 최근 이동 20개와 요약·카드 상세 각 50개를 표시하고 전체 집계는 제한 없이 계산한다. 상세가 잘리면 전체 개수와 표시 범위를 알린다. 최종 기록 탐색 UX는 디자인 단계에서 다룬다.

## 검증

2026-11-08 KST 검증 완료. [집계 JSON](../../artifacts/milestone-11/verification.json), [최종 빌드 로그](../../artifacts/milestone-11/build.log), [호스트 검사](../../artifacts/milestone-11/host-tests.log)를 보존했다.

| 검사 | 결과와 범위 |
| --- | --- |
| 앱·검증 APK 빌드, Lint | PASS, `No issues found.` |
| JVM | 348개 / 27 suite, 실패·오류·skip 0 |
| 호스트 스크립트 | 55개 PASS |
| 신규 Room probe | `vehicle-preferences`, `trip-results`, `trip-lifecycle` PASS. 임시 DB·합성 데이터로 저장·조회·rollback·복구를 검사 |
| 실제 제품 종료 | `app-trip-lifecycle` PASS. 설치된 공개 이야기의 실제 TTS 시작·정지 ACK → 세션/이동 종료 저장, 종료 중 Play 거부, 같은 host에서 다음 Play의 새 이동 생성, Media Stop의 이동 유지 확인 |
| 기존 기기 회귀 | `runtime-scripted`, `session-recovery`, `location-storage`, `summary-storage`, `media-session`, `car-policy-ui` PASS |
| 설치·실행 smoke | PASS. [결과](../../artifacts/milestone-11/app-smoke/verification.txt), [기록 화면](../../artifacts/milestone-11/app-smoke/petasos-records.png) |

기기 검증은 총 **10종 PASS**다. 제품 DB를 쓰는 `app-trip-lifecycle`의 공개 재생 기록은 보존했고 제품 위치 동의·권한은 변경하지 않았다. 마이크, 실제 위치 수집/주행, 차량 상태 변경, 외부 모델 호출은 이번 검사에 포함하지 않았다. 서버 177개와 참조 계약 13개는 [dev.11 증거](../../artifacts/milestone-10/verification.json)이며 이번에 재실행하지 않았다.

검증한 제품 APK의 SHA-256은 `e5da5f1aa044003672763415aa9c9e2ba302afee7de9ff9bedea6915ab361b2f`다. 9종은 테스트 APK `b1683719e0527116b59a9140a8d1b926a9a55bc02cf8bad353401497da7aa394`로 통과했다. 마지막 `trip-lifecycle`은 주입한 SQL 실패의 예외 검사를 고친 최종 테스트 APK `256128617fce3347481377bc6ac1448ac3ee188b4ffde41ed206a517bfbf85ee`로 재실행해 통과했다. [final-test-only-change.json](../../artifacts/milestone-11/final-test-only-change.json)은 `TripLifecycleProbe.kt` 한 파일만 바뀌었고 제품 코드와 나머지 9종의 테스트 코드는 그대로임을 기록한다. 초기 `vehicle-preferences`의 숫자 표현 기대값 실패와 `trip-lifecycle`의 예외 기대값 실패는 각각 `*-before-*-fix/`에 남겼다. 실패를 삭제하거나 최종 APK에서 10종을 모두 다시 실행한 것으로 표시하지 않는다.

정확한 최종 앱·테스트 APK는 `.local/builds/dev12/`에 보존했고 집계 시 해시를 다시 확인했다. [소스 snapshot](../../artifacts/milestone-11/source-sha256.json)의 211개 파일은 dev.13 변경 시작 직전 Root가 모두 일치함을 확인했다. 이후 개발 소스와의 차이는 dev.12 검증 실패를 뜻하지 않는다. 현재 localhost HTTPS 개발 서버는 유지 중이며 공개 배포는 하지 않았다.

## 남은 범위

다음 dev.13은 명시적으로 선택한 R8 privacy 변형의 합성 입력 검증이다. 아직 SDK 원문 로그 차단 성공이나 제품 마이크 활성화를 의미하지 않는다. 실제 마이크는 SDK 원문 로그·live PCM timing 문제가 해결되기 전 계속 비활성이다. Virtual Console과 실제 주행/Suspend는 보류한다. 실제 위치의 시간/품질 의미, 검토된 경계·랜드마크 좌표와 음악 권리 자산이 없으므로 위치 기반 자동 안내/카드 적립을 검증 완료로 표시하지 않는다. 관심사는 새 이야기 요청에 연결되지만 명시 의도/랜드마크/자동 생성 편성의 전체 정책은 별도 단계다. 사용자 발화·주행·주관적 음질 검증과 전체 P0 데모 완료를 주장하지 않는다.
