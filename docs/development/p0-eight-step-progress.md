# 원래 작업 1~8 진행표

갱신: 2026-11-09 KST. 단계 번호는 사용자와 논의한 원래 목록을 유지한다. dev.25 같은 개발 버전 번호와 다르다. 최신 설치·기기 검증 결과는 [dev.25 기록](2026-11-09-bundled-content-and-hierarchy.md)이다. dev.26 보조 경로 코드는 작성 중이며 아직 빌드·설치 검증하지 않았다. 이후 소스 작성이나 호스트 테스트를 설치된 앱의 기능 완료로 계산하지 않는다.

| 번호 | 작업 | 구현·확인한 범위 | 완료에 남은 범위 |
| --- | --- | --- | --- |
| 1 | 실제 위치 품질 연결 | 동의·정확한 위치 권한, Android 표준 측정 의미, 실제 전면 수신, Activity 닫기/재열기 검증 | 실주행 오차·경계 검수, Home/background 수명주기 검증. 화면 닫기 검증과 구분 |
| 2 | 실제 경로 기반 미래 지역 예측 | 길이·음악을 반영한 지역 선택, 대화·지연·경로 변경 시 재편성, Pleos 내비 상태/변경 연결. 네이버 공식 예제 도로 경로·구간 ETA 실호출 PASS | dev.26에서 HTTPS 보조 endpoint와 Android 예측 연결 작성 중. 전체 흐름의 빌드·기기 검증 남음. 외부 경로를 활성 Pleos 경로로 간주하지 않음 |
| 3 | 지역 판정·지역 카드 활성화 | 공식 NGII 264개 경계(261 정상/3 격리), 지역 판정·proof, 검토 정책 설치·철회 검사. 용인 3구·천안 2구의 고정 계층·leaf 선택 기기 검증 PASS | 지역별 판정 기준 실측 검수와 실제 승인 정책은 0개여서 자동 적립 비활성 |
| 4 | 지역별 콘텐츠 공급 완성 | 공공 API 조회, 근거 기반 생성·v2 설치·지역 연결·중복 방지 구조. dev.25에 검토 이야기 10개 패키징·최초 설치 연결, disposable Room 설치·binding 기기 검증 PASS | 10개 대본의 실제 제품 재생·길이, 음악 후 다음 지역 전환 검수. 이번 제품 재생 probe는 위치 기록 ON 선행조건에서 종료되어 오디오를 시작하지 않음 |
| 5 | 실제 마이크 대화 완성 | STT/TTS·대화·복귀·요약 흐름과 합성 입력 검증 | 별도 SpeechService의 문장 로그 문제 해결, 사람 발화·실제 대화 검증. 제품 마이크 false 유지 |
| 6 | 주요 랜드마크 안내 | 접근·만료·재접근·인터럽트·출력 권한 처리 | 실제 장소 좌표·이야기·접근 정책과 경로 포함 근거 연결 |
| 7 | 차량 환경 연결·전체 검수 | CarUxRestrictions, 오디오/마이크 전원 정책, 포커스·저장·시작 복구 기반 | 실제 Suspend, 주행·백그라운드·전체 흐름 통합 검수. Virtual Console 보류 유지 |
| 8 | 최종 UI·데모 | 기존 기능 프리뷰, 스타일/배치와 기능 코드 분리 | 최종 디자인과 대표 경로 데모 제작·검수. 아직 착수 전 |

1번의 기본 연결·기기 검증을 마쳤고 2~4번의 실제 입력과 자료를 준비하는 중이다. 5~7번은 기반 구현이 있지만 전체 수용 검증을 마쳤다는 뜻은 아니다. 현재 근거로 8단계 중 몇 단계를 모두 완료했다고 단일 숫자나 백분율로 표시하지 않는다.

## 이번 작업과 검증 경계

- Git: 사용자 요청에 따라 로컬 기준점 `2371634`와 태그 `baseline-dev24`를 만들었다. 작업은 `work/petasos-p0-completion`에서 기록하며 기존 데이터·키를 지우지 않는다. 원격 업로드 없음.
- 콘텐츠: [준비 목록](p0-content-inventory.md)에 기존 5묶음·10소재와 공식 API 응답 23개, 실제 장소 6곳의 좌표 후보를 기록했다. 이야기 10개는 [검토 기록](p0-content-review-evidence.md)에 따라 dev.25 고정 APK 묶음과 최초 설치 경로로 연결했고 disposable Room에서 설치·binding을 검증했다. 장소 좌표/자동 적립 정책은 별도 미승인이다.
- dev.25 검증: debug JVM 868개/65 suite, privacy 앱/검증 APK 빌드·Lint, 기기 probe 7종과 설치·실행 smoke PASS. 실제 위치는 약 28.8초의 Activity 닫기/재열기 재검사 PASS이며 Home/background 검증과 구분한다. 첫 위치 FAIL과 재생 선행조건 FAIL도 [집계](../../artifacts/milestone-25/verification.json)에 보존했다. 이전 privacy JVM 855/63과 dev.26 소스 검사를 합산하지 않는다.
- 음성: `android-speech-capability`는 녹음/인식/합성 없이 공식 Android 지원 메타데이터만 조회하는 테스트다. 호스트 probe 테스트 61개 통과. privacy APK 기기 검사에서 recognizer/TTS service는 보였으나 on-device recognizer는 없고 표준 TTS 초기화는 ERROR였다. 별도 서비스의 합성 문장 로그도 재현되었다. 공개 태그별 property 억제도 [실제 태그 문자 제약](speech-log-tag-control.md)으로 적용하지 못해 제품 마이크 false를 유지한다.
- OpenRouter: 계정 보관 설정의 읽기 전용 재확인은 브라우저 탐색 실패로 완료하지 못했다. 로그인·스위치 상태를 관측하지 못했으므로 검토 만료일을 갱신하지 않았다. 이전 승인 결정은 유지하며 계정 설정이 바뀌었다고 추정하지 않는다.
- 경로 후보: 카카오 응답 파서/요청 클라이언트는 독립 모듈로만 작성했다. 관련 서버 테스트 26개, 환경 파일 테스트 13개 통과. 실제 호출, Android 연결, 제품 endpoint 추가 없음. 공급자를 카카오로 확정한 것이 아니다.

## 경로 공급자 안내 정정

기존 [조사·결정 기록](../Petasos_조사_결정기록.md)의 방침은 **Pleos NaviHelper 읽기 우선, 네이버 Directions 보조 검토**다. 카카오는 도로별 좌표와 예상시간을 대응하기 쉬워 추가 조사한 후보였으며 명세 결정이 아니다. 사용자 질문에 따라 필수 등록처럼 안내한 요청을 철회했다. `.env`의 카카오 항목은 선택 후보로만 남고 입력할 필요가 없다.

Pleos 공개 SDK 3.0.5와 설치된 공개 ABI에서 목적지·경유지·잔여시간·변경 이벤트는 확인했다. 전체 도로 좌표열과 구간 ETA를 조회하는 계약은 확인하지 못했다. `TripSummary.routePoints`는 경유지·목적지이고, `currentRouteIndex`만으로 도로 형상을 복원할 수 없다. [NaviHelper 정리](../reference/pleos/guides/06-navihelper.md), [공식 API](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper/navi-helper/entry).

[네이버 Directions](https://api.ncloud-docs.com/docs/application-maps-directions5)는 전체 `path`와 `guide.pointIndex`/`guide.duration`을 제공한다. `section`은 일부 주요 도로 정보이므로 전체 구간 시간을 대신하지 않는다. 외부 길찾기는 Pleos가 현재 선택한 경로와 다를 수 있으며, 같은 출발지·목적지·옵션에도 동일 경로가 보장되지 않는다. 보조 예측에 사용할지와 불일치 처리 범위를 구분한 후 연결하며, 외부 계산을 실제 방문이나 카드 적립 근거로 넣지 않는다.

위 경로 문제와 독립적인 콘텐츠 검토·음성 지원 확인은 계속할 수 있다. 현재 카카오 키나 사용자 발화 테스트를 다시 요청하지 않는다.

네이버 Directions 5 키 입력은 완료됐고 공개 예제 실제 호출이 PASS다. 좌표 4,067개·시간 구간 29개에서 전체 coverage와 합계 일치를 확인했으며 원문 경로는 저장하지 않았다. [설정·검증 기록](naver-route-setup.md)을 따른다. 서버 HTTPS 보조 endpoint는 소스·호스트 검사까지 준비했으며 Android 예측 연결은 dev.26에서 작성 중이다. 아직 설치된 dev.25의 경로 예측 기능으로 계산하지 않는다.
