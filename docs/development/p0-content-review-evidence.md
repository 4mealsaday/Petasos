# P0 10개 지역 이야기 seed 검토 근거

검토일: 2026-11-09(KST). [제품 명세](../Petasos_제품명세.md)와 [조사·결정 기록](../Petasos_조사_결정기록.md)의 **5개 묶음·S01~S10**을 유지하여 자체 작성한 짧은 대본 10개를 [content bundle v2](regional-content-bundle-v2.md)로 컴파일했다. 완료 범위는 사실·출처·이용 범위·지역 연결 검토와 오프라인 bundle 검사다. 제품 DB 설치, 실제 경로 통과, 지역 판정 정책, TTS 실측, 최종 시연 품질은 이후 검증 대상이다.

## 산출물과 검사

모든 데이터 파일은 저장소에서 제외되는 `.local/content` 아래에 있고 권한은 `0600`이다.

| 파일 | 역할 |
| --- | --- |
| `.local/content/review/p0-reviewed-20261109-v1.json` | 명시적 source review. 12개 출처, 18개 사실, 10개 이야기·문단·지역 연결 |
| `.local/content/bundles/p0-reviewed-v2-20261109.json` | 실제 builder가 만든 설치 입력, 48,165 bytes |
| `.local/content/review/p0-reviewed-source-evidence-20261109.json` | 공식 웹 조회 시각·응답 해시·짧은 확인 지점. 실패도 구분하여 보존 |
| `.local/content/review/p0-reviewed-source-evidence-supplement-20261109.json` | 관광 API license 객체와 문체부 개별 공공누리 표시 보완 |
| `.local/content/review/p0-reviewed-validation-20261109.json` | 컴파일 후 독립 검사와 해시 |

Review SHA-256: `ee153c0712f8738ea9189d2973dc0cde62e8830f7e6a406c319d11016059df00`.

Bundle SHA-256: `a87fb6d049e29e20044de8ad713ae07685020f489d964bb5105b4167b1111b8c`.

```sh
.local/server-venv/bin/python scripts/build-content-bundle.py \
  --review .local/content/review/p0-reviewed-20261109-v1.json \
  --output .local/content/bundles/p0-reviewed-v2-20261109.json
```

실행 결과 `BUILT`, formatVersion 2, sources 12 / facts 18 / places 0 / stories 10 / versions 10 / regionBindings 10이었다. 별도 검사에서 validator 통과, 두 번 재컴파일의 결정성, 10개 독립 spoken stable fact, 소속 증거와 문단 fact의 분리, NGII identity 완전 일치, 기존 가거도 ID와의 비충돌, private 권한과 크기 제한을 확인했다. 자동 검사 자체가 대본의 사실성·권리를 판정한 것은 아니다. 사실과 이용 범위는 원자료를 직접 읽고 아래처럼 선별했다.

각 version은 검토된 수동 seed로 `published`, 필수 문단 1개, `audioRef=null`이다. 예상 길이는 **문자 수÷5를 초 단위 올림**한 초기값으로 합계 333초다. TTS 실측·경로 ETA·시간 수용 시험 결과가 아니며, 이후 실제 발화로 보정할 대상이다. 긴 최종 대본이나 시연의 감성 품질까지 완성한 상태로 표시하지 않는다.

## 소재별 채택 범위

지역 이름은 manifest 원래 표기다. 코드·revision·dataset을 함께 고정하여 이름이 같은 다른 시군구와 구분한다.

| 소재 | 독립 필수 사실 | 지역 코드 | 근거 |
| --- | --- | --- | --- |
| S01 역이 문화공간으로 | 옛 서울역 건물 복원·문화공간 사용 | 11140 중구 | TourAPI 문화역 서울 284 `250364` |
| S02 서울284의 숫자 | 옛 서울역사의 사적 번호에서 온 이름 | 11140 중구 | 문체부 `pSeq=11498` |
| S03 용구+처인 | 두 지역 통합·용인 명칭의 실록 기록 | 41460 용인시 | 태종실록 `kca_11408021_004`; 현재 소속은 용인시 연혁 |
| S04 양지·백암 명칭 변화 | 1996-03-01 당시 내사면→양지면, 외사면→백암면 | 41460 용인시 | 용인시 연혁·당시 의회 기록 대조 |
| S05 안성맞춤 유기 | 장내기 유기와 주문 제작 맞춤 유기 | 41550 안성시 | Odii `5682/16680` |
| S06 교통로·장터·수공업 | 큰 장이 서던 안성과 지역 제작 유기의 거래 | 41550 안성시 | 같은 Odii 항목의 별도 거래 사실·TourAPI `130440` |
| S07 호두과자와 여행 | 팥을 쓰는 천안 호두과자, 아라리 팥 사용 사례 | 44130 천안시 | 농촌진흥청 2016-09-07 보도자료 |
| S08 천안삼거리의 길 | 한양·경상도·전라도 방향의 삼남대로 분기점 | 44131 동남구 | TourAPI `126922`, Odii `6698/18759` |
| S09 철도와 도시 | 대전의 철도 성장과 경부·호남선 분기 관계 | 30110 동구 | 국토교통부 브리핑; 역 동광장 소속은 TourAPI `2775503` |
| S10 1993년 엑스포 | 박람회 시설·부지를 과학교육에 활용한 공원 | 30200 유성구 | TourAPI `125994` |

S07은 여행 간식을 떠올리는 질문으로 시작하는 **재료 중심의 짧은 seed**다. 원래 소재를 다른 관광지로 바꾸지 않았지만 최초 제작자·발명 연도·기차 판매 역사를 확정한 대본은 아니다. S06도 장터·제작·거래 관계에 한정하며 검토되지 않은 도로 노선·시장 순위를 추가하지 않았다.

## 출처별 이용 범위

- **TourAPI·Odii:** [TourAPI 카탈로그](https://www.data.go.kr/catalog/15101578/openapi.json)와 [Odii 카탈로그](https://www.data.go.kr/catalog/15101971/openapi.json)의 license 객체는 `이용허락범위 제한 없음`이다. 검토한 짧은 사실·주소를 자체 문장으로 정리한 `normalizedText`만 허용한다. `cpyrhtDivCd`를 모든 매체의 일괄 허가로 해석하지 않으며 사진·성우 음성·연기 대사·전체 대본은 포함하지 않았다.
- **S02 문체부:** [개별 보도자료](https://www.mcst.go.kr/site/s_notice/press/pressView.jsp?pSeq=11498) 하단에서 해당 저작물의 공공누리 1유형 표시를 직접 확인했다. 숫자 유래만 선별하고 출처를 남긴다. 다른 문체부 페이지의 이용조건으로 대체하지 않았다.
- **S03 실록:** [직접 읽은 기사](https://sillok.history.go.kr/id/kca_11408021_004)의 용구·처인 통합 사실을 사용한다. [국사편찬위원회 실록원문 카탈로그](https://www.data.go.kr/catalog/15053647/fileData.json)는 출처표시 제1유형을 명시한다. 고전 원문의 짧은 사실을 자체 서술하며 현대 국역문 전체·이미지·DB 구조를 복제하지 않았다. 1413/1414년 표현 차이는 대본에서 연도를 생략하여 남겨둔다.
- **S03 소속·S04 행정명:** [용인시 연혁](https://www.yongin.go.kr/home/yiIf/yiIfProd/yiIfProd02.jsp)의 지명 대응·날짜·지역 관계를 읽고 [당시 의회 기록](https://council.yongin.go.kr/viewer/minutes.do?uid=425)과 대조했다. `INDEPENDENT_FACT_STATEMENTS_ONLY`는 외부 사이트 전체에 대한 라이선스 이름이 아니다. [저작권법](https://www.law.go.kr/lsInfoP.do?lsiSeq=285693)의 저작물 정의에 따라 창작적 표현과 단순 사실을 구분하여 **자체 작성한 사실문장만 검토한 범위**다. 원문 문장·표 배열·의원 발언·해설은 배포하지 않는다. 개별 KOGL 표시를 확인했다고 주장하지 않으며, 회의록의 조례안을 이미 공포된 법령으로 간주해 제7조를 적용하지 않았다.
- **S07 농촌진흥청:** [공식 보도자료](https://m.korea.kr/briefing/pressReleaseView.do?newsId=156153724&pWise=sub&pWiseSub=J2)는 텍스트에 공공누리 제1유형을 명시한다. 팥과 천안 호두과자의 관계만 사용하며 특정 팥 품종을 모든 제품의 현재 성분으로 확대하지 않는다.
- **S09 국토교통부:** [공식 브리핑](https://www.korea.kr/briefing/policyBriefingView.do?newsId=156615822)은 텍스트에 공공누리 제1유형을 명시한다. 철도 성장·분기 관계만 사용하고 미래 개발 사업의 실현·정치적 평가는 포함하지 않는다.

문화역 운영기관의 제한적 약관, 호두과자 관련 정책기자단 글의 표시 없음, 국토부 맛집 카드뉴스의 4유형을 허용 출처로 전용하지 않았다. 향토문화대전 본문·업소 사진도 bundle에 없다.

자동 수집 원본의 `review_status=pending`·허용값 false는 그대로다. 새 source review의 **12개 선별 normalizedText**만 검토하여 생성·표시·배포 허용값을 true로 기록했다. 생성 허용도 이 짧은 텍스트에 한정하며 원문 사이트 전체·첨부 매체를 모델에 보낼 허용이 아니다.

용인시 연혁은 web 도구가 반환한 공식 본문 표를 읽었다. 직접 urllib 재조회는 `URLError`여서 원본 HTTP 바이트 해시를 확보했다고 주장하지 않는다. 메타데이터는 이를 명시하고 의회 원자료의 실제 응답 해시를 따로 보존한다. 채택 API 응답은 기존 private 파일 SHA-256, 직접 조회한 공식 페이지는 조회 시각·응답 바이트 SHA-256을 기록했다.

## 지역 연결·반복 방지·한계

모든 binding은 `regions/ngii-2026-09-21-v1/manifest.json`의 exact identity를 사용한다. datasetVersion은 `ngii-d7a3751bd89f-wgs84-stream-v1`, regionSourceRevisionId는 `ngii-sigungu-d7a3751bd89f`다. 용인시 지명 역사와 천안시 먹거리는 parent 시, 삼거리공원은 동남구, 역 동광장은 동구, 엑스포공원은 유성구에 연결했다. parent binding 자체가 parent 방문 카드·leaf 정책·현재 위치 판정 권한을 주지 않는다.

10개 필수 문단은 서로 다른 stable fact를 쓴다. 공유 소속 증거는 문단 fact 목록 밖에 있어 S01 청취가 S02의 독립 사실을 반복으로 제거하지 않는다. 생성 대본이 소속 문장을 생략해도 기존 v2의 독립 소속 증거 검사를 유지한다. 기존 가거도 review/bundle/fact/story ID는 변경하지 않았고 새 10개는 원래 명세의 독립 소재다.

이번 bundle은 `places=[]`, `story.placeId=null`인 지역 이야기이며 좌표·장소 레코드·자동 랜드마크 정책이 없다. 추후 명명 장소를 연결할 때도 stable identity와 청취 이력을 보존하는 명시적 절차가 필요하며 우회용 복제 이야기 ID 발급을 권장하지 않는다. 확보한 장소 좌표는 private 후보 자료에 남아 있다.

남은 작업은 배포 catalog/초기 설치 연결과 disposable Room 검사, parent 콘텐츠·leaf 판정 결합, 실제 TTS 발음·길이 확인, 실제 도로 경로·지역 정책·음악 후 전환·UI 시연이다. 대본 개선은 같은 stable story/fact를 유지한 새 version으로 진행한다.
