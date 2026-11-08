# dev.30: 두 랜드마크 접근 정책과 연속 관측

2026-11-09. 문화역서울284(S01)와 대전엑스포과학공원(S10)의 기존 공개 이야기·독립 연결 사실에 접근 정책을 연결한다. **명시적으로 INACTIVE인 내비의 CurrentPosition 모드만 대상**이다. 실제 Pleos 경로, 주행, 입구·도로 접근, 사람 청취 검수와 구분한다. 기기 검증 결과는 아래에 별도 기록한다.

## 발견한 문제와 수정

직전 두 측정의 이동량이 오차 범위보다 작으면 실제 접근 중에도 매번 HOLDING이 됐다. 동일한 648개 합성 조건에서 초기 판정은 각 장소·정책 후보의 36조건 중 20개만 접근을 확인했다. 특히 오차를 주입하지 않은 5Hz 조건은 0/9였다. 반경 150/300/500m를 바꿔도 누락은 같았다.

`planning/LandmarkMovementEvidence`로 후보·경로별 관측 구간을 분리했다. 최대 64개, 기존 측정 시각 기준 10초 freshness 안의 연속 측정만 사용하며 기존 `LandmarkApproach` 거리·accuracy 비교를 재사용한다. 평균점·직선 예측점이나 새 오차 임계값을 만들지 않는다.

- 최신 점과 비교했을 때 어느 보관점이든 명백한 RECEDING이면 과거 순접근보다 우선하고 구간을 초기화한다.
- 중간에 만료된 점이 있으면 그 점까지의 prefix를 버린다. stale·무효·불순서·권한·경로·후보 변경과 clear/terminal도 이전 구간을 폐기한다.
- 중복 수신은 새 이동 증거가 아니다. 같은 place의 복수 후보는 기존 permit도 철회한다.
- 새 encounter에는 cooldown 이후 새 외부 측정과 그 이후의 실제 sufficient baseline을 요구한다. 시간이 지나도 expired/consumed ID는 부활하지 않는다.

12개 신규 회귀와 기존 랜드마크 검사를 합친 **45개/5 suite가 통과**했다. 독립 코드 검토에서 추가 blocker는 없었다. 작은 방향 전환이 측정 불확실성보다 작을 때 즉시 실제 방향을 알아낸다는 주장은 하지 않는다.

## 공개 입력과 비교

기존 asset과 [좌표 검토](p0-landmark-data-plan.md)를 그대로 사용했다. 공개 합성 fixture는 두 대표점의 남북 자오선 −850..850m를 1m 간격으로 만든 것이다. 독립 `pyproj.Geod(WGS84)` forward/inverse 3,402회로 출력 12자리 좌표를 대조했고, 재생성 결과는 byte-identical이다. 좌표 계산의 오차 검사는 POI 실측 정확도가 아니다.

| 입력 | SHA-256 |
| --- | --- |
| 공개 콘텐츠 asset | `c8f34431486e0581c2f2a9916eba2db17e6511259f47b4f7ee7a31d70cd7862b` |
| 좌표 검토 evidence | `9d6443850b13d84768ad7dd2497efabe7600d80df5cd4dc1375353e602554c8b` |
| 합성 fixture | `76939bfe69cb9975fbb486f060c3434ec416500c42c46b36b86b6b449bd2bfa4` |
| generator | `dc95e5a2f45556ea00d91f0ea75419b29457ac593cfa8da1111cef572a3d194e` |

장소 2개 × 반경 150/300/500m × cooldown 60/180/300초의 18후보를 비교했다. 각 후보는 속도 5/15/25m/s × 1/5Hz × accuracy 5/15/30m × 오차 없음/양방향 방사 오차의 36조건, 전체 648조건이다. 합성 오차는 sample index마다 부호가 바뀌므로 두 빈도 모두 양방향을 실제 방문한다. 정지·반경 경계·인터럽트·통과·stale·권한 철회·재접근은 별도 guard 검사다.

| 장소별 각 정책 후보 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 1Hz, 오차 주입 없음 | 2/9 | 8/9 |
| 5Hz, 오차 주입 없음 | 0/9 | 8/9 |
| 1Hz, 양방향 오차 | 9/9 | 9/9 |
| 5Hz, 양방향 오차 | 9/9 | 9/9 |
| 합계 | 20/36 | 34/36 |
| 잘못된 방향·반경 승인 | 0 | 0 |

수정 후 전체 648조건 중 612개에서 접근했다. 남은 누락은 오차 주입 없는 5m/s·accuracy 30m의 두 빈도 조건이다. 10초 신선도 안의 이동이 양쪽 오차 범위를 분리하지 못하므로 보수적으로 남긴다. 실제 Android accuracy는 통계적 값이며 이 유한 합성 검사는 현장 보장이 아니다.

## 첫 두 정책 선정

**반경 300m / cooldown 180초 / `android-location-v1` / `petasos-landmark-current-position-20261109-v1`**를 정확 두 장소에 선정했다. 방향·통과 규칙은 `decreasing_distance` / `increasing_distance_after_approach`를 유지한다. 검토 근거와 결과는 [공개 evidence](data/landmark-current-position-demo-review-v1.json)에 고정했다.

- 실제 네이버 공개 합성 출발·도착 조회 2회에서 대표점과 도로 형상의 최소 거리는 서울284 약105.1m, 엑스포148.3m였다. 150m 반경은 엑스포에서 약1.7m 여유뿐이며 합성 최소 선행시간도 1~3.2초여서 제외했다.
- 300m의 합성 최소 선행시간은 오차·빈도 그룹별 7~9.2초다. 500m는 15~17.2초를 확보하지만 승인된 조건 수는 늘지 않고 더 넓은 주변을 포함한다. 300m를 첫 두 장소의 운영 선택으로 한정했다.
- 180초는 60/300초 사이의 명시적 시연 운영값이다. 현장 최적값을 통계적으로 찾았다는 뜻이 아니다. 모든 cooldown 후보에서 새 외부 관측·새 접근이 필요함을 확인했다.

선행시간은 합성 표본상 최근접점까지의 시간이다. TTS 시작·완료나 음악이 끝나기 전 안내 기회를 보장하지 않는다. 실제 네이버 guide 시간창도 반경 안 실제 체류시간으로 해석하지 않는다. 공개 조회 입력은 실제 도로 진입점·법적 접근점이 아니며 조회 경로가 활성 Pleos 경로와 같다는 근거도 아니다.

선정 evidence SHA는 `28642339cb2366ab005bc0616e4211780df177e3b4d1a2a2a0dbfa71313c4653`, catalog SHA는 `93c1aa05a2771289f338e3483f48e37dec18d0388ca4d1048944703674e20a49`다. 해시는 검토한 데이터와 코드를 고정할 뿐 근거의 진실을 자동 증명하지 않는다.

## 설치·현재 권한과 기존 기록

고정 asset을 읽는 `BundledLandmarkPolicyCatalog`를 기존 콘텐츠 설치 성공 직후 같은 Host worker에 연결했다. 기존 strict codec와 원자적 installer를 재사용한다. 반경·cooldown은 catalog에 있고 관측 판정과 UI는 별도 파일이므로 후속 조정을 기능 화면에 섞지 않는다.

기존 story/fact/version/paragraph ID, 모든 `story.place_id=NULL`, 사용자 동의·설정·청취 이력을 유지한다. exact 재설치는 no-op이고 rejected 정책이나 회수된 source 권한은 복구하지 않는다. 이미 들은 두 이야기는 정상 자동 반복 제외가 적용되므로 정책이 유효해도 제품 후보가 2개라고 요구하지 않는다.

기기 probe는 독립 DB에서 실제 두 catalog의 첫 설치·재설치·후보2·좌표 metadata·지역정책 보존·철회를 검사한다. 제품 probe는 실제 Host 초기화 뒤 저장된 exact2/current 정책을 **읽기만** 확인하며 설치·수리하지 않는다. 기존 제품 지역9 정책 검사도 유지한다.

## 검증 기록

초기 누락 결과는 `artifacts/milestone-30/landmark-review-initial.json`, 수정 결과는 `landmark-review-fixed.json`에 보존했다. 초기 샌드박스 Gradle 로컬 포트 차단과 네이버 DNS 실패도 성공 API 응답과 분리했다. 최종 결과는 아래와 같다. 이 문서의 후보 선정은 실제 주행·음성·차량 수용 검수 완료를 뜻하지 않는다.

- Debug JVM **1,002개/79 suite**, 호스트73개(생략0), privacy 앱·검증 APK 빌드(2분48초)와 Lint(41초), 알려진 앱 SDK 로그 sink 정적 감사 PASS. 전체 suite의 648조건 보고서는 targeted 보고서와 byte-identical이다.
- 동일 최종 APK 기기 **5종/5회 PASS**: landmark-policy-storage(742ms), landmark-storage(453ms), automatic-planning(539ms), representative-route-runtime(221ms), app-playback(4,289ms).
- 제품 Host가 설치한 현재 지역9·랜드마크2 정책을 읽기 확인했다. 공개 이야기의 실제 TTS 시작·완료·저장·Stop도 PASS이며 이 4.289초는 전체 probe 시간이지 음향 길이 측정이 아니다.
- 최종 설치/실행 smoke PASS, 소스572개와 새 파일 inventory/최종 APK 해시 일치, 앱 전면 실행·기존 HTTPS health200·재부팅 뒤 개발용 reverse 복원. 기존 사용자 기록·동의·키를 보존했다.
- [최종 집계](../../artifacts/milestone-30/verification.json), APK 보존 `.local/builds/dev30/privacy-final/`. 제품 APK SHA `0ed5e3883dfcf8fcb608459bf78d7deb29c2cdf5e637679b51161c82b866ae1a`, 검증 APK `acb24dbd9cadede450da84a9d770e827940d9430c831d4f22c1141f033395638`.

실제 위치 수신·닫기/재열기는 dev.29 결과를 유지하며 이번에 반복하지 않았다. 실제 활성 경로 membership·주행·Home/background·Suspend·사람 발화·새 모델 호출·UI 스타일 변경은 이번 검증 범위가 아니다. 기존 SpeechService 로그 문제 때문에 제품 마이크는 false다.
