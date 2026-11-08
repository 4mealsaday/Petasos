# 공개 경계 이동 시연 자료와 첫 9개 지역 정책

2026-11-09, dev.29 설치·검증 완료. [검토 원본](data/region-policy-demo-review-v1.json)은 DATA-11의 시연 자료·값·근거를 고정하며, 실제 차량 측정이나 도로 경로 자료라고 주장하지 않는다.

## 자료와 독립 검사

국토지리정보원 SHA 고정 geometry와 검토 hierarchy에서 9개 exact leaf의 경계 단면을 골랐다. 독립 GEOS가 실제 기준점과 합성 측정점을 별도로 분류한다. 제품 Packed lookup·변환 오차 1m 차감·hierarchy·Android 의미 어댑터·normalizer·recorder·연속 proof를 적용한다. 네이버 경로, 실제 사용자 위치, 제품 DB, 네트워크를 사용하지 않는다.

대상: `11140, 41461, 41463, 41465, 41550, 44131, 44133, 30110, 30200`. 서울 중구, 용인 3구, 안성, 천안 2구, 대전 동구·유성구다. 이웃 지역·용인시/천안시 parent·나머지 지역에는 정책을 확대하지 않는다.

각 지역은 밖→안→6초 체류→밖, 짧은 실제 진입, 계속 밖에 있으면서 측정점이 경계를 오가는 흔들림을 포함한다. 1/2/5Hz와 accuracy3/10/30m 조합으로 **243개 trace·8,019개 표본·3,348개 좌표**다. 초기 시간 위상 패턴이 1/5Hz와 겹치는 문제는 반입 전에 수정했다. 현재 81개 흔들림 trace 모두 독립 기준점은 밖이고 측정점은 양쪽을 포함한다.

Fixture SHA: `08533135821e4adb969e4bf94ed6b68bc45f526f965b5b9f0840f5600449c8d3`.
Generator SHA: `0e98806d20dd5737cc65c7d9fa32b12aa3c2efee40c11614342675259c2e39bb`.

`build-region-policy-traces.py`는 bbox 지연 읽기·geometry LRU16개/100,000점·누적750,000점·120초 제한을 둔다. 실제 생성 2회 약1초, byte/SHA 동일, 상주 최대81,611점·누적95,012점이었다. JVM은 immutable 공개 좌표 최대8,192개를 memoize하며 fixture strict decimal 한도만 시험용1MiB다. 제품 JSON 한도는 바꾸지 않았다. 기본 Python에는 GIS가 없어 첫15개가 skip됐고, 기존 `.local/region-venv/bin/python`에서 **15개 모두 PASS**했다.

## 비교 결과와 선택

N2/3/5 × 최소0/1/2초 × 여유5/10/20m의 27개를 총6,561회 재생했다. 모든 후보에서 false/premature/outside-bounce 확정은0이다. 별도 정지·무효·중복·단절·시계 하네스729양성/2673음성도 PASS다. 이 숫자들은 기기 방문 증거가 아니다.

| 후보 | 이동 확인 | p50 / p95 / 최대(초) | 짧은 실제 통과 확인 |
| --- | ---: | --- | ---: |
| **N2 · 1초 · 5m** | **81/81** | **1.8 / 3 / 3** | **20/81** |
| N3 · 1초 · 5m | 81/81 | 2 / 4 / 4 | 20/81 |
| N2 · 1초 · 10m | 80/81 | 2 / 4 / 4.5 | 16/81 |
| N2 · 2초 · 5m | 81/81 | 2.8 / 4 / 4 | 0/81 |

첫 검토값은 **N2·최소1000ms·여유5m**, Android adapter `android-location-v1`이다. 고빈도 수신에서도 최소시간을 보존하고, 검토한 이동 사례의 누락 없이 지연을 낮추는 선택이다. 실제 proof는 1/2/5Hz에서2/3/6개다. 경계 여유는 보고 accuracy에 더하며 변환오차1m는 이미 거리에서 차감한다.

이는 5m·1초가 다른 후보보다 안전하다는 통계적 증명이 아니다. Android accuracy는 통계적 반경이며 테스트는 명시된 bounded noise만 포함한다. 지역별 경계 단면 하나이므로 모든 경계·실주행 정확도를 보장하지 않는다. 짧은 통과61/81은 확인하지 못했고 1Hz는0/27이었다. 짧은 실제 진입을 자동 오판정으로 분류하거나 누락을 숨기지 않는다. 모든 값은 코드 기본값이 아니라 이9개 revision의 별도 검토 데이터다.

## 설치 범위

[검토 evidence](data/region-policy-demo-review-v1.json) SHA는 `730068a2a886c93429196ef292d1d40467b1960e9a1fc23d55ff573c012b9e7d`다. exact manifest·geometry·hierarchy·fixture·하네스·비교 결과를 고정하고 선택값과 한계를 기록한다. APK catalog SHA는 `426d66669f5687215d03b4bcefa83a4b305b77da00bd941481bbf48ef6168568`이다.

기존 설치기를 그대로 사용해 해당9개만 최초 확정한다. 나머지255개는 pending이며 격리된3개도 그대로다. 새 consent나 실제 route point·카드·proof를 만들지 않는다. 실제 자동 판정은 사용자가 켠 위치 기록, 현재 유효한 Android 표본, 연속 증거, 현재 권한을 모두 요구한다. 철회된 정책 재설치·다른 값 덮어쓰기는 기존 계약대로 거부한다. 수치 변경은 새 revision 검토 대상이다.

후보 보고서의 `approvedPolicyCount=0`은 하네스 자체가 정책을 승인하거나 제품을 수정하지 않았다는 범위다. 실제9개 선정과 승인은 별도 review 파일/catalog가 담당한다.

## 재현

```sh
.local/region-venv/bin/python scripts/build-region-policy-traces.py --assets app/src/main/assets --output /private/tmp/new-public-region-traces.json
.local/region-venv/bin/python -m unittest discover -s scripts/tests -p test_region_policy_traces.py
PETASOS_REGION_POLICY_MOVING_REPORT="$PWD/artifacts/milestone-29/moving-policy-candidates.json" PETASOS_REGION_POLICY_REPORT="$PWD/artifacts/milestone-29/stationary-policy-candidates.json" ./scripts/gradle.sh :app:testDebugUnitTest --tests com.petasos.app.location.RegionPolicyMovingTraceTest --tests com.petasos.app.location.RegionPolicyDemoHarnessTest
```

생성기는 기존 출력 파일을 덮어쓰지 않는다. 빌드 전에 에뮬레이터를 종료하고, 모든 JVM이 끝난 뒤 기기 검사를 시작한다.

## 최종 빌드와 기기 검증

Debug JVM **986개/77 suite**, 호스트 probe71개와 GIS 환경 trace15개, privacy 앱/검사 APK 빌드·Lint·알려진 앱 SDK 로그 sink 정적 검사 PASS다. 전체 JVM 뒤에는 instrumentation의 제품 정책 관측과 제품 주석만 보완했으며 최종 APK 쌍을 다시 빌드·Lint/audit했다. 비교 보고서는 첫 결과와 최종 전체 JVM 결과가 byte-identical이다. [최종 집계](../../artifacts/milestone-29/verification.json).

동일 최종 APK 기기 **8종**(`region-policy-storage`, `region-storage`, `region-hierarchy`, `bundled-stories`, `automatic-planning`, `auxiliary-route`, `app-playback`, `app-location-live`)과 설치/실행 smoke PASS다. source564개를 최종 대조했다.

- 실제 catalog를 읽은 disposable Room에서 정확9개 설치/재설치·255개 pending·마지막 항목 충돌의 전체 rollback·각 지역5Hz 정확6개 proof·9개 leaf 카드·parent 카드0을 확인했다. 정책/출처 철회 후 재설치는 거부하고 기존 카드/proof는 보존했다. 합성 위치를 제품 방문으로 적립하지 않았다.
- 실제 제품 Host의 MediaBrowser 초기화 후, 제품 DB에서 catalog9개 canonical 정책·verifiedAt·현재 source/정책 권한을 **읽기만 하여** 확인했다. probe가 정책을 설치하거나 실패를 복구하지 않았다. 실제 공개 이야기 문단 TTS와 Stop도4.638초 PASS다.
- NAVER 공식 공개 예제 실제 HTTPS:4,203점/29guide, NGII 시간창8개·unknown16개, 투영86ms·전체1,382ms. 합성 Android anchor로 passage5개와 만료/철회를 검사했다. 보조 경로가 실제 Pleos 선택 경로임을 입증하지 않는다.
- 실제 위치 Activity 닫기/재열기19.569초 PASS, 복귀 뒤 새 segment1·새 point2·새 유효 UPDATE1, cleanup active segment0. Home/background는 여전히 미검증이며 dev.26 간헐적 실패 원인도 미확정으로 남긴다.

두 landmark 정책은 아직0개다. 실제10편 전체 TTS는 dev.28 결과를 유지하며 이번에 반복하지 않았다. 마이크 대화·Virtual Console·새 모델 요청·UI 변경은 이 단계에 포함하지 않는다.
