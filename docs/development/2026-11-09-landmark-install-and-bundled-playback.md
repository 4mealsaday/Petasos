# 랜드마크 연결 설치와 기본 이야기 전체 재생

2026-11-09, dev.28. 기존 이야기·fact ID와 청취 이력을 보존한 랜드마크 정책 설치 계약을 적용했고, 기본 이야기 10편의 실제 제품 TTS 완료를 확인했다. UI·마이크·Virtual Console은 변경하지 않았다.

## 구현

- [정책 설치 계약](reviewed-landmark-policy-install.md): 엄격한 review codec, 장소 메타데이터 해시, 독립 이야기 연결 근거, 현재 권한 reader, 원자적 최초 설치/정확한 재설치/철회를 분리했다. 새 정책만 기존 NULL story.place_id를 명시적으로 연결할 수 있고 기존 FK를 바꾸지 않는다. 별도 schema 변경은 없다.
- TX-05가 같은 장소를 재설치할 때 verified/rejected/NULL/손상된 기존 정책을 보존한다. 이야기당 중복 장소 연결은 후보를 발급하지 않는다.
- [검토한 두 장소](p0-landmark-data-plan.md)의 공식 대표점과 좌표 출처를 고정 콘텐츠 asset에 추가했다. 14 sources/18 facts/2 places/10 stories/10 versions/10 bindings이며 기존 12 sources와 모든 fact/story/version/binding은 동일하다. 새 좌표 출처는 생성용 권한을 부여하지 않는다.
- **실제 랜드마크 승인 정책은 0개다.** 이 단계가 자동 랜드마크 안내 활성화를 의미하지 않는다. 반경·방향·통과·cooldown 시연 검토와 catalog 호출 연결은 다음 단계다.

콘텐츠 SHA-256: `c8f34431486e0581c2f2a9916eba2db17e6511259f47b4f7ee7a31d70cd7862b`.

## 최종 검증

Debug JVM **985개/76 suite**, 호스트 probe **70개**, privacy 앱/검증 APK 빌드·Lint·알려진 앱 음성 SDK 로그 sink 정적 검사 PASS다. 동일 최종 APK 쌍의 기기 검사 **6종/15회**와 설치/실행 smoke PASS다. 소스 560개와 최종 APK 해시를 대조했다. [최종 집계](../../artifacts/milestone-28/verification.json).

저장소 검사 5종은 `landmark-policy-storage`, `landmark-storage`, `bundled-stories`, `regional-content`, `automatic-planning`이다. 별도 disposable Room에서 기존 이력/동의 보존, immutable 충돌·배치 rollback·철회·현재 권한과 실제 고정 콘텐츠 설치를 검증했다. `content-install` 일반 도구는 privacy의 run-as 미지원 검사에서 실행 전에 거부됐다. 기기 실패로 합산하거나 수행 완료로 표시하지 않는다.

`bundled-playback`은 asset SHA·exact seed version·content hash를 확인한 뒤 storyId 정렬 index별로 한 편씩 실제 제품 MediaBrowser→Host→Pleos TTS→Room 경로를 실행했다. 현재 대본 10개는 모두 필수 문단 1개이며, 새 session에서 모든 필수 문단의 완료와 정상 Stop을 확인했다. 문단별 `ended_elapsed_ms-started_elapsed_ms`가 양수이고 동일 clock scope인지 검사했다.

| Index | 이야기 | 저장된 재생 구간(초) |
| --- | --- | ---: |
| 0 | 역이 문화공간으로 | 19.973 |
| 1 | 장이 서던 고장의 물건들 | 20.732 |
| 2 | 다시 불리게 된 양지와 백암 | 20.517 |
| 3 | 이름에 남은 숫자 284 | 17.019 |
| 4 | 천안삼거리에서 갈라진 길 | 18.837 |
| 5 | 철도와 함께 자란 대전 | 18.541 |
| 6 | 박람회가 남긴 과학공원 | 20.941 |
| 7 | 안성맞춤과 두 가지 유기 | 20.055 |
| 8 | 용구와 처인에서 용인으로 | 18.146 |
| 9 | 여행길에 떠올리는 호두과자 | 17.905 |

총 **192.666초**다. 이는 실제 SDK 콜백과 Room에 저장된 단조 시간의 구간이며, 오디오 파형을 녹음해 측정한 음향 길이나 사람의 발음 품질 평가는 아니다. 기존 생성 예상 길이 값을 이 관측으로 덮어쓰지 않는다. 실제 자동 편성은 이미 Started·완료 관측을 반영하는 흐름을 사용한다.

검사는 Activity를 열지 않았고 위치 기록 설정을 전후 그대로 보존했다. 위치 구간·대화·모델 요청은 만들지 않았으며 정상 청취 기록과 이야기 완료는 제품 DB에 남겼다. 따라서 자동 반복 제외 대상이 되며, 명시적 다시 듣기는 기존 정책대로 가능하다. [10편 결과](../../artifacts/milestone-28/bundled-playback-summary.json).

dev.27의 실제 위치 닫기/재열기 2회 PASS는 이전 기록을 따르며 이번에 재실행하지 않았다. dev.26 간헐적 위치 실패 원인은 여전히 미확정이다. Home/background·실주행·실제 Pleos 선택 경로·음악 뒤 다음 지역 통합 수용 검증은 이 결과에 포함하지 않는다.

## 다음 순서

공식 경계에 기반한 이동·체류·짧은 통과·경계 흔들림 시연 자료로 RegionPolicy 후보를 비교하고, 근거가 확인된 정확 9개 leaf만 첫 검토 대상으로 삼는다. 이어 두 장소의 접근 정책을 검토한다. 마이크 대화는 별도 SpeechService 원문 로그 문제로 비활성 상태다. 다른 공식 STT/TTS 검토 여부는 사용자 선택을 기다리며 독립적인 개발은 계속한다.
