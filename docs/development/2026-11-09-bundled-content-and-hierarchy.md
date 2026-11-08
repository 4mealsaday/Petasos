# dev.25: 검토 이야기 10개와 용인·천안 계층 연결

확인일: 2026-11-09 KST. 설치·기기 검증 대상은 **0.8.0-dev.25 privacy APK**다. [집계](../../artifacts/milestone-25/verification.json)는 성공과 실패를 모두 보존한다. 이후 작성 중인 dev.26 보조 경로 코드는 이 APK의 기능이나 검증 수치에 포함하지 않는다.

## 구현 범위

[검토한 S01~S10 대본](p0-content-review-evidence.md)을 고정 APK 자산으로 넣고 `BundledStoryCatalog`를 제품 저장소 초기화에 연결했다. 12개 출처·18개 사실·10개 이야기와 독립 지역 binding을 기존 v2 설치 경계로 처리한다. 기존 stable ID와 사용자 청취 이력을 보존하며, 이 목록을 조회하는 것만으로 자동 재생하거나 마이크를 시작하지 않는다.

[용인 3구·천안 2구의 관계 근거](2026-11-09-region-hierarchy-review-draft.md)를 exact dataset·manifest·region revision에 고정했다. 같은 위치가 도시와 소속 일반구에 함께 포함되면 검토 관계의 leaf 한 개를 선택한다. 모르는 중첩·형제 중첩·필수 부모 기하 누락은 계속 거부한다. 부모 도시 이야기는 별도 콘텐츠 범위로 선택할 수 있지만 부모 카드까지 적립하지 않는다.

계층 관계와 대본 검토는 위치 정책 승인이 아니다. **실제 승인 정책 catalog는 0개**이며 264개 경계의 방문 정책은 미검토다. 정상 기하 261개·격리 3개와 기존 proof 보존 규칙을 유지한다.

## 확인한 결과

| 검사 | 결과와 범위 |
| --- | --- |
| Debug JVM | 65 suite / 868 tests, 실패·오류·생략 0. [집계와 XML 해시](../../artifacts/milestone-25/jvm-test-summary.json). 이전 privacy JVM 결과 855/63은 이번 수치에 합산하지 않음 |
| Privacy 빌드·Lint | 앱/검증 APK 빌드와 `lintPrivacy` PASS |
| `bundled-stories` | disposable Room에 10개 고정 이야기·정확한 binding 설치 PASS. 실제 제품의 모든 대본 청취 검증은 아님 |
| `region-hierarchy` | 공식 기하·공개 합성 좌표로 leaf 카드 하나와 parent 이야기 범위 PASS. 실제 정책 승인 아님 |
| 회귀 4종 | `automatic-planning`, `region-policy-storage`, `media-session`, `runtime-tts` PASS. 마지막 항목은 실제 Pleos TTS와 합성 이야기이며 사람 발화 아님 |
| 실제 위치 재검사 | `app-location-live-repeat` PASS, **28,777ms(약 28.8초)**. 실제 전면 표본과 Activity 닫기/재열기·새 구간 저장을 확인 |
| 앱 설치·실행 | privacy smoke PASS, 해당 시점 전면 Activity 확인. UID 범위 crash/stack 지표 0 |
| 앱 SDK 정적 로그 검사 | 알려진 Speech SDK debug/stack sink 제거와 모델 보존 PASS. 별도 SpeechService 원문 로그 부재 증거는 아님 |

기기 probe는 총 9회다. 성공 7회와 아래 실패 2회를 구분하며, 같은 최종 앱/검증 APK 쌍을 사용했다. smoke는 별도 검사다.

- 첫 `app-location-live`는 84,397ms 뒤 `app_location_cleanup_failed`로 FAIL이었다. [첫 결과](../../artifacts/milestone-25/app-location-live/app-location-live-privacy.json)를 보존하며 원인을 확정하지 않는다. 같은 APK의 [재검사](../../artifacts/milestone-25/app-location-live-repeat/app-location-live-privacy.json)가 PASS다.
- 두 위치 검사에서 Home/task 요청만으로 Activity가 숨겨졌다는 증거는 없었다. 재검사는 명시적인 Activity close/reopen fallback에서 실제 `onStop`·`onDestroy`를 확인했다. **Home/background 수명주기 검증은 남아 있다.**
- `app-playback`은 기존 사용자의 위치 기록 ON을 감지하여 36ms에 선행조건 FAIL이었다. 재생을 시작하기 전 종료했으므로 제품 오디오 재생 성공·실패의 근거로 계산하지 않는다. 검사를 통과시키려고 사용자 동의를 OFF로 바꾸지 않았다.

## 다음 소스 작업과 운영 제한

dev.26에서 네이버 보조 경로용 HTTPS endpoint, Android 제한 클라이언트·경계 투영·현재 위치 연결을 작성 중이다. 서버 호스트 검사와 소스 검토 결과를 dev.25의 설치·통합 결과로 합산하지 않는다. 네이버 경로는 `AUXILIARY`이며 활성 Pleos 경로와 동일하다고 주장하거나 실제 방문 적립 근거로 사용하지 않는다. [네이버 설정·실호출 기록](naver-route-setup.md)을 따른다.

공개 Android 태그별 로그 설정도 [별도 합성 관측](speech-log-tag-control.md)으로 조사했다. 실제 서비스 태그가 공개 property 이름에 허용되지 않는 문자를 포함하여 정확 태그별 억제 경로를 적용할 수 없었다. 전역 로그 억제·숨겨진 API·서비스 수정은 수행하지 않았고 제품 마이크는 false다. 사람 발화와 Virtual Console은 계속 보류한다.

OpenRouter 보관 설정의 읽기 전용 재확인은 브라우저 자동화 탐색 실패로 완료하지 못했다. 계정 페이지·로그인 상태·개별 스위치를 관측하지 못했으므로 `privacyReviewedUntil`은 갱신하지 않았다. 앞서 승인한 ZDR 설정 결정을 철회하거나 현재 스위치가 바뀌었다고 추정하지 않는다. 설정 변경·로그인·키 출력도 하지 않았다.
