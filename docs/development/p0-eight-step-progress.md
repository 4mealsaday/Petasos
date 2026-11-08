# 원래 작업 1~8 진행표

갱신: 2026-11-09 KST. 단계 번호는 사용자와 논의한 원래 목록을 유지한다. dev.27 같은 개발 버전 번호와 다르다. 최신 설치·검증 결과는 [dev.30 기록](landmark-current-position-policy-review.md)이다. 이후 소스/자료 준비를 설치 앱의 검증 완료와 혼동하지 않는다.

| 번호 | 작업 | 구현·확인한 범위 | 완료에 남은 범위 |
| --- | --- | --- | --- |
| 1 | 실제 위치 품질 연결 | 동의·정확한 위치 권한, Android 표준 측정 의미, 실제 전면 수신. dev.27 2회와 dev.29의 Activity 닫기/재열기 PASS | 실주행 오차·Home/background 검증. dev.26 간헐적 실패 원인은 미확정으로 보존 |
| 2 | 실제 경로 기반 미래 지역 예측 | 길이·음악 기반 지역 선택과 동적 재편성, Pleos 목적지·경유지 메모리 입력, HTTPS 보조 endpoint·Android 예측 연결. dev.26 최종 기기에서 실제 네이버 공개 경로·NGII 투영·합성 앵커 PASS | 실제 Pleos 선택 목적지와 실제 위치를 함께 쓰는 전체 흐름, 대표 시연 경로의 시간 수용 검수. 보조 경로와 활성 Pleos 경로의 동일성은 미확인 |
| 3 | 지역 판정·지역 카드 활성화 | NGII 264개 경계(261 정상/3 격리), 계층 leaf 선택·연속 proof. 이동 시연 검토 뒤 정확9개 정책 선정·제품 DB 현재 권한 확인·5Hz exact6개 proof/철회 검증 | 나머지255개 정책은 pending. 시연9개 경계 외·실주행 오차 수용 검증, 짧은 통과 누락(선택 후보20/81 확인) |
| 4 | 지역별 콘텐츠 공급 완성 | 공공 API·근거 생성·v2 설치·지역 연결. 검토 이야기 10개 패키징. 위치 동의를 보존한 기본 이야기 10편의 실제 TTS 전체 완료. dev.30에서 음악 완료 후 exact 다음 지역 이야기·Stop/재개·경로 변경의 격리 통합 시나리오 PASS | 실제 이동·음악과 다음 지역을 결합한 전체 검수·사람 청취 품질 |
| 5 | 실제 마이크 대화 완성 | STT/TTS·대화·복귀·요약 흐름과 합성 입력 검증 | 별도 SpeechService의 문장 로그 문제 해결, 사람 발화·실제 대화 검증. 제품 마이크 false 유지 |
| 6 | 주요 랜드마크 안내 | 접근·만료·재접근·인터럽트·출력 권한 처리. 공식 좌표 6곳 대조, 서울284 S01·엑스포 S10의 place 2개 패키징. 기존 이야기 ID 보존, 연속 최대64개 신선한 관측으로 고빈도 접근 누락 보완. 300m/180초의 exact2 정책 설치·제품 현재 권한·철회 검증, 합성 조건 장소별34/36 | 실제 접근·정지/통과·청취 수용 검수. active-route membership 미완료, UNKNOWN/ACTIVE 내비는 CurrentPosition으로 대체하지 않음 |
| 7 | 차량 환경 연결·전체 검수 | CarUxRestrictions, 오디오/마이크 전원 정책, 포커스·저장·시작 복구 기반. 대표 지역→음악→경로 변경→지역 runtime/Room 통합 PASS | 실제 Suspend, 주행·백그라운드·전체 흐름 통합 검수. Virtual Console 보류 유지 |
| 8 | 최종 UI·데모 | 기존 기능 프리뷰, 스타일/배치 분리. 격리 대표 runtime 시나리오 준비 | 듣기/여정/기록 중심의 UI 방향 사용자 선택 대기. 선택 뒤 화면 상태 분리·최종 UI·시연 화면 구현 |

1번의 기본 연결과 dev.27 위치 복귀 재검사 2회는 통과했지만 dev.26 간헐적 실패의 원인은 미확정이다. 2~4번의 실제 입력과 자료를 연결하는 중이며, 5~7번도 기반 구현이 전체 수용 검증 완료를 뜻하지 않는다. 현재 근거로 8단계 중 몇 단계를 모두 완료했다고 단일 숫자나 백분율로 표시하지 않는다.

## 이번 작업과 검증 경계

- Git: 사용자 요청에 따라 로컬 기준점 `2371634`와 태그 `baseline-dev24`를 만들었다. 작업은 `work/petasos-p0-completion`에서 기록하며 기존 데이터·키를 지우지 않는다. 원격 업로드 없음.
- 콘텐츠: [준비 목록](p0-content-inventory.md)에 기존 5묶음·10소재와 공식 API 응답 23개, 실제 장소 6곳의 좌표 후보를 기록했다. 이야기 10개는 [검토 기록](p0-content-review-evidence.md)에 따라 dev.25 고정 APK 묶음과 최초 설치 경로로 연결했고 disposable Room에서 설치·binding을 검증했다. [랜드마크 자료 초안](p0-landmark-data-plan.md)은 첫 두 장소의 공식 대표점만 별도 검토했으며 기존 story/fact를 그대로 유지한다. dev.30 현재 exact9 지역 정책과 exact2 CurrentPosition 랜드마크 정책만 검토·연결했으며, 그 외 지역255개와 active-route 랜드마크는 미완료다.
- dev.25 검증: debug JVM 868개/65 suite, privacy 앱/검증 APK 빌드·Lint, 기기 probe 7종과 설치·실행 smoke PASS. 실제 위치는 약 28.8초의 Activity 닫기/재열기 재검사 PASS이며 Home/background 검증과 구분한다. 첫 위치 FAIL과 재생 선행조건 FAIL도 [집계](../../artifacts/milestone-25/verification.json)에 보존했다. 이전 privacy JVM 855/63과 dev.26 소스 검사를 합산하지 않는다.
- dev.26 검증: 최종 Debug JVM **964개/75 suite**, privacy 앱·검증 APK 빌드, Lint·APK 정적 감사 PASS. 기기 probe 6종 중 `auxiliary-route`, `navigation`, `automatic-planning`, `region-hierarchy`, `media-session`의 **5종 PASS**, `app-location-live` **FAIL**이다. 보조 경로는 실제 HTTPS 공개 예제 4,138점·27 guide에서 NGII 지역 시간창 7개와 unknown guide 18개를 얻었고, **합성 Android 점·시계**로 passage 2개와 만료·철회를 검증했다. 투영 71ms, 전체 1,202ms였다. [JVM 집계](../../artifacts/milestone-26/jvm-test-summary.json), [기기 결과](../../artifacts/milestone-26/auxiliary-route/auxiliary-route-privacy.json).
- dev.26 위치 실패: 47,911ms 후 복귀 새 segment의 live UPDATE를 확인하지 못했고 cleanup도 실패했다. `returned_valid_11`은 **세션 누적값**이므로 복귀 후 11개 새 수신으로 읽지 않는다. [실패 결과](../../artifacts/milestone-26/app-location-live/app-location-live-privacy.json)와 [원 관측](../../artifacts/milestone-26/app-location-live/app-location-live-privacy.txt)을 보존한다. 같은 최종 APK의 설치·실행 [smoke PASS](../../artifacts/milestone-26/final-smoke/verification.txt)는 위치 실패 해결의 근거가 아니다. 실제 Pleos 경로·실제 위치 E2E와 주행 수용 검증은 남아 있다.
- 음성: `android-speech-capability`는 녹음/인식/합성 없이 공식 Android 지원 메타데이터만 조회하는 테스트다. 호스트 probe 테스트 61개 통과. privacy APK 기기 검사에서 recognizer/TTS service는 보였으나 on-device recognizer는 없고 표준 TTS 초기화는 ERROR였다. 별도 서비스의 합성 문장 로그도 재현되었다. 공개 태그별 property 억제도 [실제 태그 문자 제약](speech-log-tag-control.md)으로 적용하지 못해 제품 마이크 false를 유지한다.
- OpenRouter: 계정 보관 설정의 읽기 전용 재확인은 브라우저 탐색 실패로 완료하지 못했다. 로그인·스위치 상태를 관측하지 못했으므로 검토 만료일을 갱신하지 않았다. 이전 승인 결정은 유지하며 계정 설정이 바뀌었다고 추정하지 않는다.
- 경로 후보: 카카오 응답 파서/요청 클라이언트는 독립 모듈로만 작성했다. 관련 서버 테스트 26개, 환경 파일 테스트 13개 통과. 실제 호출, Android 연결, 제품 endpoint 추가 없음. 공급자를 카카오로 확정한 것이 아니다.

## 경로 공급자 안내 정정

기존 [조사·결정 기록](../Petasos_조사_결정기록.md)의 방침은 **Pleos NaviHelper 읽기 우선, 네이버 Directions 보조 검토**다. 카카오는 도로별 좌표와 예상시간을 대응하기 쉬워 추가 조사한 후보였으며 명세 결정이 아니다. 사용자 질문에 따라 필수 등록처럼 안내한 요청을 철회했다. `.env`의 카카오 항목은 선택 후보로만 남고 입력할 필요가 없다.

Pleos 공개 SDK 3.0.5와 설치된 공개 ABI에서 목적지·경유지·잔여시간·변경 이벤트는 확인했다. 전체 도로 좌표열과 구간 ETA를 조회하는 계약은 확인하지 못했다. `TripSummary.routePoints`는 경유지·목적지이고, `currentRouteIndex`만으로 도로 형상을 복원할 수 없다. [NaviHelper 정리](../reference/pleos/guides/06-navihelper.md), [공식 API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry).

[네이버 Directions](https://api.ncloud-docs.com/docs/application-maps-directions5)는 전체 `path`와 `guide.pointIndex`/`guide.duration`을 제공한다. `section`은 일부 주요 도로 정보이므로 전체 구간 시간을 대신하지 않는다. dev.26은 이 입력을 명시적 보조 예측으로 연결하고, 변경·이탈·만료·권한 철회 때 예측을 폐기한다. 외부 길찾기는 Pleos가 현재 선택한 경로와 다를 수 있으며, 같은 출발지·목적지·옵션에도 동일 경로가 보장되지 않는다. 외부 계산을 실제 방문이나 카드 적립 근거로 넣지 않는다.

위 경로 문제와 독립적인 콘텐츠 검토·음성 지원 확인은 계속할 수 있다. 현재 카카오 키나 사용자 발화 테스트를 다시 요청하지 않는다.

네이버 Directions 5 키 입력은 완료됐고, CLI·인증 HTTPS endpoint·최종 Android APK의 공개 예제 호출을 각각 검증했다. 호출마다 계산된 경로 개수는 다르며 [설정·검증 기록](naver-route-setup.md)에 분리했다. 원문 경로·사용자 좌표를 로그나 경로 파일에 남기지 않았고 모델 호출도 없었다. 실제 Pleos 목적지·유효 위치·음악 뒤 자동 이야기까지 이어지는 대표 경로 검수는 다음 범위다.

- dev.27: JVM 969/75, privacy 앱/검증 APK 빌드·Lint·정적 audit, 동일 APK 기기 6종/7회와 smoke PASS. 실제 위치 2회 PASS는 dev.26 실패 원인 해결을 뜻하지 않는다. [집계](../../artifacts/milestone-27/verification.json).

- dev.28: JVM 985/76, 호스트70, privacy 빌드·Lint·정적 audit, 동일 APK 기기6종/15회와 smoke PASS. 이야기10편의 필수 문단 전체 실제 재생 구간 합계192.666초, 정상 청취 이력 보존. 두 실제 place 설치·연결 정책 계약은 완료했으나 실제 랜드마크 승인 정책0개다. [집계](../../artifacts/milestone-28/verification.json).

- dev.29: JVM986/77·호스트86·privacy 빌드/Lint/audit·동일 APK 기기8종/smoke PASS. N2/1000ms/5m를9개 exact leaf에만 적용했고 제품 DB에서 현재9개를 읽기 확인했다. 이동81/81·짧은통과20/81, 실제 주행 보장은 아니다. 위치 닫기/재열기19.569초 PASS. [집계](../../artifacts/milestone-29/verification.json).

- dev.30: JVM1,002/79·호스트73·privacy 빌드/Lint/audit·동일 APK 기기5종/smoke PASS. 실제 제품의 지역9·랜드마크2 current 정책을 읽기 확인했다. 고빈도 접근을 연속 관측으로 보완했으며 두 장소의 합성 조건은 각각20/36→34/36이다. [대표 지역 전환](representative-route-runtime-scenario.md)은 exact 새 이야기와 revision·음악 완료 저장·Stop/재개를 확인했다. 실제 위치·주행·사람 음성·모델·UI 변경과 구분한다. [집계](../../artifacts/milestone-30/verification.json).

## 현재 다음 작업과 사용자 선택

다음 독립 구현은 최종 UI와 격리 데모 화면이다. 사용자에게 **듣기 중심 어두운 화면(추천) / 지나갈 지역·여정 중심 어두운 화면 / 밝은 여행 기록 화면** 중 방향을 요청했으며 아직 답변은 없다. 기존 UI 스타일·배치는 변경하지 않았다. 실제 프리뷰는 제품 DB/Host에 연결되어 있으므로 기존 청취 기록을 초기화해 시연하지 않는다.

음성은 다른 공식 STT·TTS를 보관 조건과 함께 검토·연결할지, 보류하고 UI·데모를 진행할지 답변 대기다. OpenRouter 설정 이후 변경 여부에 대한 기존 질문도 대기이며 실제 모델 요청이나 검토 만료일 갱신은 하지 않았다. 네이버 키와 위치 권한은 재요청하지 않는다. 사람 발화 테스트는 지금 요청하지 않는다.
