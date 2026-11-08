# 공식 지역 경계·경로 자료

확인일: 2026-11-08. v0.8의 법정 시·군·구 판정에 필요한 자료를 정리한다. 대표 좌표·관광 지역코드는 행정구역 폴리곤을 대신하지 않는다.

## VWorld 2D 시군구 API

[공식 레퍼런스](https://www.vworld.kr/dev/v4dv_2ddataguide2_s002.do?svcIde=adsigg) · [인증키 발급](https://www.vworld.kr/dev/apiKey_i001.do) · [키 관리](https://www.vworld.kr/dev/apiKey_s001.do)

- GET `https://api.vworld.kr/req/data`, `service=data`, `version=2.0`, `request=GetFeature`, `data=LT_C_ADSIGG_INFO`.
- `key`는 VWorld 전용 키이며 data.go.kr 서비스 키와 별개다. `domain`은 선택 항목이며 필요하면 발급 시 등록한 URL을 그대로 전달한다. **2026-11-08 실제 서버 호출은 domain 생략으로 성공했다.** 임의로 localhost를 채울 필요가 없다.
- 코드 단독 검색: `attrFilter=sig_cd:=:11650`. 공식 속성 표에서 `sig_cd`는 단일 검색을 지원하므로 `geomFilter`를 생략한다.
- `format=json`, `errorFormat=json`, `geometry=true`, `attribute=true`, `crs=EPSG:4326`을 명시한다. 현재 문서의 기본 CRS도 EPSG:4326이다.
- 시군구 속성은 `sig_cd`, `full_nm`, `sig_kor_nm`, `sig_eng_nm`. 상태는 `OK/NOT_FOUND/ERROR`, 개수는 `record.total/current`, 페이지는 `page.total/current/size`다.
- 실제 호출에서 `result.featureCollection`, `FeatureCollection`, `MultiPolygon` 응답을 확인했다. GeoJSON 좌표 순서는 경도·위도다. 이 확인은 위치 측정·사용자의 실제 경로·미래 통과 지역 검증을 뜻하지 않는다.

CLI는 정확한 코드 한 건을 조회한다. 자동 페이지 조회·재시도·리다이렉트·프록시는 사용하지 않으며 8MiB/200,000좌표를 넘으면 실패한다. 응답의 형식·좌표 순서·닫힌 ring·유일 코드·페이지 완결성을 검사한다. 출력에는 개수와 상태만 남기고 원본 geometry는 메모리에서만 다룬다. 기하 위상과 이용권한 검토는 별도다.

```sh
python3 scripts/region-boundaries.py describe
python3 scripts/region-boundaries.py check --code 11650
```

`.env`의 `PETASOS_VWORLD_API_KEY`만으로 서버 검증이 가능하다. `PETASOS_VWORLD_DOMAIN`은 선택 사항이다. 키는 APK·OpenRouter·로그에 넣지 않는다.

## 앱 배포용 경계 파일

[공식 행정경계(시군구), dataset 30252](https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=MK&dsId=30252)는 국토지리정보원 연속수치지형도 자료이며, 페이지에 [CC BY 2.0 KR](https://creativecommons.org/licenses/by/2.0/kr/)가 개별 표시돼 있다. 페이지에서 확인한 파일은 다음과 같다.

| 파일 | 페이지 표시 | 용도 |
| --- | --- | --- |
| `N3A_G0100000.zip` | 약 89MB, 갱신 2026-09-21 | 전국 SHP, EPSG:5179 |
| `연속수치지형도_데이터_설명서.pdf` | 약 738KB, 갱신 2026-09-11 | 실제 속성·코드 매핑 검토 |

다운로드는 로그인이 필요하다. 저장할 위치는 프로젝트의 `.local/regions/source/`다. 이 방식에는 API 키가 필요 없다. 사용자가 ZIP/PDF를 제공했고 실제 내부 파일·코드 필드·CRS 검토와 앱 연결을 마쳤다. [dev.18 설치 기록](../../development/2026-11-08-official-region-boundary-import.md)을 따른다. 264개 중 261개 형상을 사용하고 원본 위상 오류가 있는 3개는 격리한다. 위치 품질·방문 정책은 여전히 미확정이다. 페이지 갱신일을 행정구역의 실제 기준일로 단정하지 않는다.

[현행 이용약관](https://www.vworld.kr/v4po_prcint_a001.do) 제19조는 제공 데이터 저장 제한과 다운로드 자료의 개별 라이선스 예외를 구분한다. [API 카탈로그](https://www.data.go.kr/data/15059008/openapi.do)의 이용허락 표시만으로 API 원본을 APK 배포 데이터로 승격하지 않는다. 공식 ZIP·설명서의 검토 결과는 [고정 출처 검토 입력](ngii-source-review.json)에 기록했고 별도 geometry revision으로 설치한다. 현재 자료의 법정 경계 기준일은 미확인이다.

## 도로 경로와 시간

[카카오모빌리티 자동차 길찾기](https://developers.kakaomobility.com/guide/navi-api/directions)는 `roads[].vertexes`와 도로별 `duration`을 제공한다. 그러나 별도로 계산한 경로와 Pleos가 선택한 경로의 동일성은 확인되지 않았다. 이번 단계에서는 Kakao 키를 추가하거나 결과를 Pleos 실제 경로로 취급하지 않았다.

Pleos 공개 SDK에서 확인한 목적지·경유지의 남은 시간이나 `TripSummary.routePoints`만으로 전체 도로 polyline을 복원하지 않는다. [위치·경로 문의 초안](../../development/pleos-location-route-support-request.md)과 [위치 계약](../../development/location-navigation-contract.md)을 따른다.
