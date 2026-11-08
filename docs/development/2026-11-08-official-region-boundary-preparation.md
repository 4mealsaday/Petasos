# 공식 지역 경계 연결 준비

2026-11-08, dev.17 후속 소스 작업. 설치된 앱은 기존 dev.17을 유지한다. 이번 단계는 경계 판정 엔진·실제 API 검증이며 실도로 자동 편성 활성화나 새 APK 배포 완료가 아니다.

## 구현과 확인

- `location/ReviewedPolygonBoundaryLookup`: EPSG:4326 Polygon/MultiPolygon의 오목 경계·구멍·섬을 판정한다. GeoJSON 경도·위도 직선 경계를 사용하고, 지역 revision과 dataset version을 결과에 함께 전달한다.
- 외부 입력은 깊이 복사해 불변화하며 자기 교차·겹친 동일 지역·잘못된 구멍·좌표 범위·닫히지 않은 ring을 거절한다. 다른 지역 사이 중복은 모두 반환해 기존 지역 확정 계층이 모호함을 거절하게 한다.
- 경계까지 거리는 WGS84 최단 거리의 **보수적 하한**이다. 경도·위도 120..135E/30..45N 범위를 지원하고 1mm 수치 여유를 뺀다. 실제 거리보다 넉넉하게 안전한 내부라고 판단하지 않는다. 데이터 검토·지역 정책 검토는 생성자 호출과 별개다.
- 메모리/CPU 상한: 전체 100,000좌표, ring당 32,769좌표, 위상 검사 비교 2,000,000회. 전국 고해상도 자료를 그대로 한 번에 읽는 용도가 아니다. 공식 파일을 취득한 뒤 대상 지역 추출과 검토가 필요하다.
- `RegionConfirmation`에 양수 내부 거리 조건을 추가했다. 정확도·정책 margin이 모두 0이어도 경계선 또는 수치 허용오차 근처의 점을 지역 방문으로 확정하지 않는다. 실제 기하 엔진과 결합한 회귀 테스트로 확인했다.
- `server/petasos_server/region_boundaries.py` 및 `scripts/region-boundaries.py`: 공식 VWorld 시군구 코드 단독 조회, HTTPS 고정 endpoint, 선택 `domain`, 개수/페이지/CRS/형상 확인, 원본 비저장, 안전한 상태 출력. API 원본의 구조 검증은 기하 위상·권한 승인을 대신하지 않는다.
- 사용자 입력 키로 `11650`(공식 문서의 서초구 예시)을 실제 호출했다. **domain 생략 성공**, EPSG:4326, MultiPolygon 1개, ring 1개, 좌표 1,762개. 원본·키·요청 URL은 검증 산출물에 저장하지 않았다. 이는 데모 지역을 서초구로 결정한 것이 아니다.

## 검증

| 검사 | 결과 | 범위 |
| --- | --- | --- |
| Android privacy JVM + AndroidTest 컴파일 | PASS, **624개/46 suite** | 기존 599 + 기하 24 + 0m 확정 회귀 1 |
| Android privacy Lint | PASS | 최종 소스 정적 검사 |
| 서버 | PASS, **191개** | 실제 네트워크를 쓰지 않는 자동 테스트 |
| 호스트 도구 | PASS, **122개** | CLI·기존 검증 도구 회귀 |
| VWorld 실제 조회 | PASS | 사용자 키, domain 생략, 공개 시군구 1회 |

[집계](../../artifacts/milestone-17/verification.json). 첫 컴파일 이후 0m 확정 조건을 수정하고 최종 JVM 검사를 다시 실행했다. 새 APK 빌드·설치나 기기 회귀를 이번 결과에 포함하지 않는다. 기존 dev.17 APK의 기기 검증은 milestone-16에 남아 있다. 빌드 전에 해당 Pleos 에뮬레이터를 정상 종료해 Gradle과 동시에 실행하지 않았다.

## 실제 연결에 남은 입력

1. [공식 경계 파일](https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=MK&dsId=30252)의 `N3A_G0100000.zip`과 `연속수치지형도_데이터_설명서.pdf`. 로그인 후 `.local/regions/source/`에 저장한다. 페이지의 개별 CC BY 2.0 KR 표시를 확인했다. 아직 파일 내부의 법정 시군구 코드 필드·좌표·실제 기준일은 확인하지 않았다. EPSG:5179→4326 변환과 hash·코드 매핑·출처 검토, versioned import는 후속이다. API 키는 이 파일 방식의 필수 조건이 아니다.
2. Pleos `timestampMillis`와 `confidence`의 공식 의미. 최신 동적 본문과 설치 AAR도 단위·시간 원점을 설명하지 않아 `UNKNOWN` 유지. Android LocationManager로 조용히 바꾸지 않았다.
3. 활성 Pleos 경로와 일치하는 도로 기하·지역별 예상 통과 시간. 별도 외부 길찾기 결과나 목적지 총 ETA 비례 보간은 이 조건을 충족하지 않는다.

[공식 자료·환경 설정](../reference/regions/README.md), [미전송 문의 초안](pleos-location-route-support-request.md)을 남겼다. 브라우저 제어 도구의 연결 오류로 로그인 세션/다운로드까지 진행하지 못했다. 오류 우회나 로그인 정보 추출을 시도하지 않았다.

제품 기본 `RegionBoundaryLookup.NONE`, `LocationMeaningAdapter.UNKNOWN`, `RegionalRouteForecastSource.UNAVAILABLE`은 유지한다. UI·실제 마이크·Virtual Console·위치 동의 설정은 변경하지 않았다. 지금 사용자가 새로 수행할 앱/발화 시험은 없다.
