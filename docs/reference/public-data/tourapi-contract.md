# TourAPI KorService2 공식 입출력 계약

등록 서비스 **한국관광공사_국문 관광정보 서비스_GW**의 현행 공개 Swagger 전체를 보존하고 읽기 쉽게 정리한 문서다. 구형 KorService/KorService1의 파라미터를 섞지 않았다.

- 공식 출처: [https://www.data.go.kr/data/15101578/openapi.do](https://www.data.go.kr/data/15101578/openapi.do)
- 수집 시각(UTC): `2026-11-07T05:52:19+00:00`
- 원문 값 보존본: [tourapi.openapi.json](tourapi.openapi.json)
- 출처·추출 방식·해시: [tourapi-source-metadata.json](tourapi-source-metadata.json)
- 저장 파일 SHA-256: `4902633965e480a5ed1beda9891068a0ef0a7b69d4e6c97c85ca2ca8c3d150a2`
- HTML 내 원래 Swagger 문자열 SHA-256: `da0c6611c711ca838bb24b8f57396a81dfa773f01a494faaa6b97b87531a2cd5`

## 범위와 해석 원칙

- 현행 공식 계약은 **15개 경로, 15개 GET 작업, 15개 응답 definition, 엔드포인트별 입력 191개(공통 입력 중복 포함), item 필드 401개(엔드포인트별 중복 포함)**다.
- 스냅샷은 HTML의 `const swaggerJson`에 들어 있는 JSON을 실행 없이 파싱하고 들여쓰기만 적용했다. 공식 오탈자, 비표준 확장, 누락, 오래된 필드 설명도 수정하지 않았다.
- 원문 `host`는 `apis.data.go.kr/B551011/KorService2`, `basePath`는 빈 문자열이다. Swagger 2.0의 일반적인 host/basePath 분리와 다르므로 계약을 도구에 직접 로딩할 때 주의한다. 실제 HTTPS 기준 주소는 `https://apis.data.go.kr/B551011/KorService2`다.
- 원문 `schemes`는 `https`, `http`를 모두 나열하지만 Petasos는 HTTPS만 사용한다. 모든 입력은 URL query다. 모든 작업이 `consumes: application/json`, `produces: application/json, application/xml`을 선언하며 GET 요청 본문은 정의하지 않는다.
- 입력은 path-level `parameters`에 있다. 공식 `required`를 아래 표에 그대로 표시한다. **모든 입력에 정식 `default`, `enum`, `minimum`, `maximum`, `pattern`, `deprecated` 키가 없다.** 허용 코드·형식·상호 의존·삭제 예정은 설명 문자열에만 있다. 표의 설명에 있는 기본값과 코드도 그대로 보존한다.
- 비표준 `swaggerOprtinVOs`는 `searchFestival2` 하나의 UI 설명 자료만 담는다. 여기의 `paramtrBassValue`는 UI 시험 입력 값이며 정식 스키마의 서버 기본값으로 해석하지 않는다.
- `serviceKey`는 필수 query 인증키다. 저장한 공식 계약에는 실제 사용자 키가 없다. 키가 들어 있는 요청 URL·원본 오류 메시지는 출력하지 않는다.

## 공통 응답 경로와 명세의 빈 부분

공식 200 응답 schema의 루트 속성은 `header`, `body`이고 XML 루트 이름은 `response`다. 실제 확인된 JSON은 `response` 객체로 감싼 구조다. 따라서 소비자 경로는 다음과 같이 대응한다. 모든 작업의 실제 응답을 검증했다는 의미는 아니며, endpoint별 실제 검증 결과는 별도 실행 기록을 따른다.

| 항목 | 공식 schema 상대 경로 | JSON 소비자 경로 | XML 경로 | 선언 타입 |
|---|---|---|---|---|
| 결과 코드 | `header.resultCode` | `$.response.header.resultCode` | `/response/header/resultCode` | `string` |
| 결과 메시지 | `header.resultMsg` | `$.response.header.resultMsg` | `/response/header/resultMsg` | `string` |
| 한 페이지 결과 수 | `body.numOfRows` | `$.response.body.numOfRows` | `/response/body/numOfRows` | `number` |
| 페이지 | `body.pageNo` | `$.response.body.pageNo` | `/response/body/pageNo` | `number` |
| 전체 결과 수 | `body.totalCount` | `$.response.body.totalCount` | `/response/body/totalCount` | `number` |
| 목록 컨테이너 | `body.items` | `$.response.body.items` | `/response/body/items` | `object` |
| 항목 | `body.items.item` | `$.response.body.items.item` | `/response/body/items/item` | **`object`** |

- 모든 15개 작업은 HTTP 응답 `200`만 명세에 선언한다. 오류 HTTP 상태·게이트웨이 오류 XML은 이 Swagger에서 구체화하지 않는다. HTTP 200과 애플리케이션 성공 코드는 별개다.
- `resultCode`의 `enum`은 없다. 실제 연동에서 확인된 `0000`과 공공데이터 정상 코드 `00`을 명시적으로 성공 호환 값으로 취급하며 임의의 다른 문자열을 성공으로 간주하지 않는다.
- 모든 응답 object에 `required` 목록이 없고 모든 응답 필드에 `nullable`/`x-nullable` 표시도 없다. 스키마상 필수 지정 없음은 실제 API가 항상 생략한다는 뜻이 아니며, null 허용 여부도 문서만으로 확정할 수 없다.
- 모든 `item`은 공식 스키마에서 단일 `object`로 선언되며 `type: array`, `items`, `minItems`/`maxItems`가 없다. 실제 JSON 목록의 반복 배열과 차이가 있다. XML은 반복 `<item>`을 읽어야 한다. 개수는 스키마에 명확히 선언되지 않았으므로 배열 크기를 발명하지 않는다.
- 아래 항목 필드는 공통 접두사 `body.items.item.` 뒤의 **정확한 필드명**이다. JSON 배열 항목이라면 `$.response.body.items.item[*].<필드>`, XML이라면 `/response/body/items/item/<필드>`에 대응한다. 단일 객체·배열·빈 목록 처리는 클라이언트 호환 계층에서 수행한다.
- 아래 모든 출력 필드는 필수 여부 **미지정**, null 허용 **미기재**, occurrence 수 **미기재**다. 문자열 필드라도 빈 문자열·숫자 문자열·HTML을 포함할 수 있으므로 타입과 의미를 임의 변환하거나 모든 필드를 LLM 근거로 승격하지 않는다.
- `detailIntro2`, `detailInfo2`의 타입별 필드는 하나의 평면 object에 합쳐져 있다. 어떤 `contentTypeId`에서 어떤 필드가 나오는지에 대한 공식 `oneOf`/discriminator/조건부 required는 없다. 설명과 실제 반환 필드를 보존한다.

## 전체 작업 목록

| HTTP | 경로 / operationId | 기능 | 필수 query | item 필드 수 |
|---|---|---|---|---|
| GET | `/areaCode2` / `areaCode2` | 지역코드조회 | `MobileOS`, `MobileApp`, `serviceKey` | 3 |
| GET | `/detailPetTour2` / `detailPetTour2` | 반려동물 동반 여행 정보 | `serviceKey`, `MobileOS`, `MobileApp` | 10 |
| GET | `/categoryCode2` / `categoryCode2` | 서비스분류코드조회 | `MobileOS`, `MobileApp`, `serviceKey` | 3 |
| GET | `/areaBasedList2` / `areaBasedList2` | 지역기반 관광정보조회 | `MobileOS`, `MobileApp`, `serviceKey` | 25 |
| GET | `/locationBasedList2` / `locationBasedList2` | 위치기반 관광정보조회 | `MobileOS`, `MobileApp`, `mapX`, `mapY`, `radius`, `serviceKey` | 26 |
| GET | `/searchKeyword2` / `searchKeyword2` | 키워드 검색 조회 | `MobileOS`, `MobileApp`, `keyword`, `serviceKey` | 25 |
| GET | `/searchFestival2` / `searchFestival2` | 행사정보조회 | `MobileOS`, `MobileApp`, `eventStartDate`, `serviceKey` | 29 |
| GET | `/searchStay2` / `searchStay2` | 숙박정보조회 | `MobileOS`, `MobileApp`, `serviceKey`, `lDongRegnCd` | 25 |
| GET | `/detailCommon2` / `detailCommon2` | 공통정보조회 | `MobileOS`, `MobileApp`, `contentId`, `serviceKey` | 28 |
| GET | `/detailIntro2` / `detailIntro2` | 소개정보조회 | `MobileOS`, `MobileApp`, `contentId`, `contentTypeId`, `serviceKey` | 125 |
| GET | `/detailInfo2` / `detailInfo2` | 반복정보조회 | `MobileOS`, `MobileApp`, `contentId`, `contentTypeId`, `serviceKey` | 53 |
| GET | `/detailImage2` / `detailImage2` | 이미지정보조회 | `MobileOS`, `MobileApp`, `contentId`, `serviceKey` | 6 |
| GET | `/lclsSystmCode2` / `lclsSystmCode2` | 분류체계 코드조회 | `serviceKey`, `MobileOS`, `MobileApp` | 9 |
| GET | `/areaBasedSyncList2` / `areaBasedSyncList2` | 관광정보 동기화 목록 조회 | `MobileOS`, `MobileApp`, `serviceKey` | 27 |
| GET | `/ldongCode2` / `ldongCode2` | 법정동코드조회 | `serviceKey`, `MobileOS`, `MobileApp` | 7 |

## 현행 계약에서 특히 주의할 항목

- `areaCode2`와 `categoryCode2` 작업 설명 자체가 **미사용 기능·삭제 예정**이다. 전체 계약 호환을 위해 보존하되 신규 지역/분류 조회는 `ldongCode2`, `lclsSystmCode2`를 사용한다.
- 관광정보 검색 작업의 `areaCode`, `sigunguCode`, `cat1`, `cat2`, `cat3`도 설명상 미사용·삭제 예정이며 `lDongRegnCd`, `lDongSignguCd`, `lclsSystm1`, `lclsSystm2`, `lclsSystm3`로 대체된다. 단, 옛 코드 조회 작업의 입력 이름이 같다고 일괄 제거하지 않는다.
- `searchStay2.lDongRegnCd`는 **필수**로 선언되어 있다. `detailPetTour2.contentId`는 **선택**으로 선언되어 있다. 관습과 다르더라도 스냅샷을 바꾸지 않는다.
- `searchKeyword2`에는 `contentTypeId` 입력이 없다. 과거 버전의 파라미터를 추가하지 않는다.
- `detailCommon2`에는 과거 `defaultYN`, `firstImageYN`, `areacodeYN`, `catcodeYN`, `addrinfoYN`, `mapinfoYN`, `overviewYN`이 없다.
- 조회 좌표 입력은 `mapX`/`mapY`, 응답은 `mapx`/`mapy`다. 모든 파라미터·필드 대소문자를 보존한다.
- `areaBasedSyncList2.cpyrhtDivCd` 설명이 `NO_MANDATORY_REQUEST_PARAMETERS_ERROR`로 되어 있는 등 원문 설명 오류가 있다. 다른 endpoint의 권리 설명을 이 원문에 덮어쓰지 않는다.

## 엔드포인트별 전체 입력·출력

### GET /areaCode2 — 지역코드조회

`operationId: areaCode2` · 성공 schema definition: `areaCode2_response`

미사용 기능 (삭제예정-법정동 시도코드 대체)

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `areaCode` | 선택 | `string` | 지역코드 |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `code` | `string` | 코드 : 지역코드또는시군구코드 |
| `name` | `string` | 코드명 : 지역명또는시군구명 |
| `rnum` | `string` | 일련번호 |

### GET /detailPetTour2 — 반려동물 동반 여행 정보

`operationId: detailPetTour2` · 성공 schema definition: `detailPetTour2_response`

타입별 반려동물 동반 여행 정보를 조회하는 기능입니다.

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `serviceKey` | 필수 | `string` | 공공데이터포털에서 받은 인증키 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `numOfRows` | 선택 | `number` | 한 페이지 결과 수 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명 |
| `contentId` | 선택 | `string` | 콘텐츠ID(옵션,미기입시 전체목록조회) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `contentid` | `string` | 콘텐츠ID |
| `acmpyPsblCpam` | `string` | 동반가능동물 |
| `relaRntlPrdlst` | `string` | 관련 렌탈 품목 |
| `acmpyNeedMtr` | `string` | 동반시 필요사항 |
| `relaFrnshPrdlst` | `string` | 관련 비치 품목 |
| `etcAcmpyInfo` | `string` | 기타 동반정보 |
| `relaPurcPrdlst` | `string` | 관련 구매 품목 |
| `relaAcdntRiskMtr` | `string` | 관련 사고 대비사항 |
| `acmpyTypeCd` | `string` | 동반유형코드(동반구분) |
| `relaPosesFclty` | `string` | 관련 구비 시설 |

### GET /categoryCode2 — 서비스분류코드조회

`operationId: categoryCode2` · 성공 schema definition: `categoryCode2_response`

미사용 기능 (삭제예정-분류체계 코드로 대체)

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `numOfRows` | 선택 | `string` | 한페이지결과수 |
| `pageNo` | 선택 | `string` | 페이지번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `contentTypeId` | 선택 | `string` | 관광타입(12:관광지, 14:문화시설, 15:축제공연행사, 25:여행코스, 28:레포츠, 32:숙박, 38:쇼핑, 39:음식점) ID |
| `cat1` | 선택 | `string` | 대분류코드 |
| `cat2` | 선택 | `string` | 중분류코드 |
| `cat3` | 선택 | `string` | 소분류코드 |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `name` | `string` | 코드명 : 대,중,소분류코드명 |
| `rnum` | `string` | 일련번호 |
| `code` | `string` | 코드 : 대,중,소분류코드 |

### GET /areaBasedList2 — 지역기반 관광정보조회

`operationId: areaBasedList2` · 성공 schema definition: `areaBasedList2_response`

지역기반 관광정보파라미터 타입에 따라서 제목순,수정일순,등록일순 정렬검색목록을 조회하는 기능

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `arrange` | 선택 | `string` | 정렬구분 (A=제목순, C=수정일순, D=생성일순) 대표이미지가반드시있는정렬(O=제목순, Q=수정일순, R=생성일순) |
| `contentTypeId` | 선택 | `string` | 관광타입(12:관광지, 14:문화시설, 15:축제공연행사, 25:여행코스, 28:레포츠, 32:숙박, 38:쇼핑, 39:음식점) ID |
| `areaCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `sigunguCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `cat1` | 선택 | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | 선택 | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | 선택 | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `modifiedtime` | 선택 | `string` | 수정일(형식 :YYYYMMDD) |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |
| `lDongRegnCd` | 선택 | `string` | 법정동 시도 코드(법정동코드조회 참고) |
| `lDongSignguCd` | 선택 | `string` | 법정동 시군구 코드(법정동코드조회 참고, lDongRegnCd 필수입력) |
| `lclsSystm1` | 선택 | `string` | 분류체계 1Deth(분류체계코드조회 참고) |
| `lclsSystm2` | 선택 | `string` | 분류체계 2Deth(분류체계코드조회 참고, lclsSystm1 필수입력) |
| `lclsSystm3` | 선택 | `string` | 분류체계 3Deth(분류체계코드조회 참고, lclsSystm1/lclsSystm2 필수입력) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `lclsSystm3` | `string` | 분류체계 3Deth |
| `firstimage` | `string` | 대표이미지(원본) |
| `firstimage2` | `string` | 대표이미지(썸네일) |
| `mapx` | `string` | GPS X좌표 |
| `mapy` | `string` | GPS Y좌표 |
| `mlevel` | `string` | Map Level |
| `addr2` | `string` | 상세주소 |
| `areacode` | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `modifiedtime` | `string` | 수정일 |
| `cpyrhtDivCd` | `string` | 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `cat1` | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `sigungucode` | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `tel` | `string` | 전화번호 |
| `title` | `string` | 제목 |
| `addr1` | `string` | 주소 |
| `cat2` | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `contentid` | `string` | 콘텐츠ID |
| `contenttypeid` | `string` | 콘텐츠타입ID |
| `createdtime` | `string` | 등록일 |
| `zipcode` | `string` | 우편번호 |
| `lDongRegnCd` | `string` | 법정동 시도 코드 |
| `lDongSignguCd` | `string` | 법정동 시군구 코드 |
| `lclsSystm1` | `string` | 분류체계 1Deth |
| `lclsSystm2` | `string` | 분류체계 2Deth |

### GET /locationBasedList2 — 위치기반 관광정보조회

`operationId: locationBasedList2` · 성공 schema definition: `locationBasedList2_response`

위치기반 관광정보파라미터 타입에 따라서 제목순,수정일순,등록일순,거리순 정렬검색목록을 조회하는 기능

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `arrange` | 선택 | `string` | (A=제목순,C=수정일순, D=생성일순, E=거리순) 대표이미지가 반드시 있는 정렬 (O=제목순, Q=수정일순, R=생성일순,S=거리순) |
| `mapX` | 필수 | `string` | GPS X좌표(WGS84 경도좌표) |
| `mapY` | 필수 | `string` | GPS Y좌표(WGS84 위도좌표) |
| `radius` | 필수 | `string` | 거리반경(단위:m) , Max값 20000m=20Km |
| `contentTypeId` | 선택 | `string` | 관광타입(12:관광지, 14:문화시설, 15:축제공연행사, 25:여행코스, 28:레포츠, 32:숙박, 38:쇼핑, 39:음식점) ID |
| `modifiedtime` | 선택 | `string` | 수정일(형식 :YYYYMMDD) |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |
| `lDongRegnCd` | 선택 | `string` | 법정동 시도 코드(법정동코드조회 참고) |
| `lDongSignguCd` | 선택 | `string` | 법정동 시군구 코드(법정동코드조회 참고, lDongRegnCd 필수입력) |
| `lclsSystm1` | 선택 | `string` | 분류체계 1Deth(분류체계코드조회 참고) |
| `lclsSystm2` | 선택 | `string` | 분류체계 2Deth(분류체계코드조회 참고, lclsSystm1 필수입력) |
| `lclsSystm3` | 선택 | `string` | 분류체계 3Deth(분류체계코드조회 참고, lclsSystm1/lclsSystm2 필수입력) |
| `areaCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시도 코드로 대체) |
| `sigunguCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `cat1` | 선택 | `string` | 미사용항목(삭제예정-분류체계 1Deth대체) |
| `cat2` | 선택 | `string` | 미사용항목(삭제예정-분류체계 2Deth대체) |
| `cat3` | 선택 | `string` | 미사용항목(삭제예정-분류체계 3Deth대체) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `zipcode` | `string` | 우편번호 |
| `lclsSystm3` | `string` | 분류체계 3Deth |
| `contentid` | `string` | 콘텐츠ID |
| `addr2` | `string` | 상세주소 |
| `firstimage2` | `string` | 대표이미지(썸네일) |
| `cpyrhtDivCd` | `string` | 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `addr1` | `string` | 주소 |
| `contenttypeid` | `string` | 콘텐츠타입ID |
| `createdtime` | `string` | 등록일 |
| `dist` | `string` | 거리 |
| `firstimage` | `string` | 대표이미지(원본) |
| `areacode` | `string` | 미사용항목(삭제예정-법정동 시도 코드로 대체) |
| `mapx` | `string` | GPS X좌표 |
| `mapy` | `string` | GPS Y좌표 |
| `mlevel` | `string` | Map Level |
| `modifiedtime` | `string` | 수정일 |
| `sigungucode` | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `tel` | `string` | 전화번호 |
| `title` | `string` | 제목 |
| `cat1` | `string` | 미사용항목(삭제예정-분류체계 1Deth대체) |
| `cat2` | `string` | 미사용항목(삭제예정-분류체계 2Deth대체) |
| `cat3` | `string` | 미사용항목(삭제예정-분류체계 3Deth대체) |
| `lDongRegnCd` | `string` | 법정동 시도 코드 |
| `lDongSignguCd` | `string` | 법정동 시군구 코드 |
| `lclsSystm1` | `string` | 분류체계 1Deth |
| `lclsSystm2` | `string` | 분류체계 2Deth |

### GET /searchKeyword2 — 키워드 검색 조회

`operationId: searchKeyword2` · 성공 schema definition: `searchKeyword2_response`

키워드로 검색을하며 전체별 타입정보별 목록을 조회한다

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `arrange` | 선택 | `string` | 정렬구분 (A=제목순, C=수정일순, D=생성일순) 대표이미지가반드시있는정렬(O=제목순, Q=수정일순, R=생성일순) |
| `keyword` | 필수 | `string` | 검색요청할키워드 : (국문=인코딩필요) |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |
| `areaCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `sigunguCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `cat1` | 선택 | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | 선택 | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | 선택 | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `lDongRegnCd` | 선택 | `string` | 법정동 시도코드(법정동코드조회 참고) |
| `lDongSignguCd` | 선택 | `string` | 법정동 시군구코드(법정동코드조회 참고, lDongRegnCd 필수입력) |
| `lclsSystm1` | 선택 | `string` | 분류체계 1Deth(분류체계코드조회 참고) |
| `lclsSystm2` | 선택 | `string` | 분류체계 2Deth(분류체계코드조회 참고, lclsSystm1 필수입력) |
| `lclsSystm3` | 선택 | `string` | 분류체계 3Deth(분류체계코드조회 참고, lclsSystm1/lclsSystm2 필수입력) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `lclsSystm3` | `string` | 분류체계 3Deth |
| `cpyrhtDivCd` | `string` | 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `firstimage` | `string` | 대표이미지(원본) |
| `sigungucode` | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `cat1` | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `contentid` | `string` | 콘텐츠ID |
| `tel` | `string` | 전화번호 |
| `title` | `string` | 제목 |
| `addr1` | `string` | 주소 |
| `areacode` | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `mapy` | `string` | GPS Y좌표 |
| `mlevel` | `string` | Map Level |
| `modifiedtime` | `string` | 수정일 |
| `firstimage2` | `string` | 대표이미지(썸네일) |
| `mapx` | `string` | GPS X좌표 |
| `contenttypeid` | `string` | 콘텐츠타입ID |
| `addr2` | `string` | 상세주소 |
| `createdtime` | `string` | 등록일 |
| `zipcode` | `string` | 우편번호 |
| `lDongRegnCd` | `string` | 법정동 시도코드 |
| `lDongSignguCd` | `string` | 법정동 시군구코드 |
| `lclsSystm1` | `string` | 분류체계 1Deth |
| `lclsSystm2` | `string` | 분류체계 2Deth |

### GET /searchFestival2 — 행사정보조회

`operationId: searchFestival2` · 성공 schema definition: `searchFestival2_response`

행사정보목록을 조회한다. 컨텐츠 타입이 ‘행사’일 경우에만 유효하다

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `arrange` | 선택 | `string` | 정렬구분 (A=제목순, C=수정일순, D=생성일순) 대표이미지가반드시있는정렬(O=제목순, Q=수정일순, R=생성일순) |
| `eventStartDate` | 필수 | `string` | 행사시작일(형식 :YYYYMMDD) |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |
| `eventEndDate` | 선택 | `string` | 행사종료일(형식 :YYYYMMDD) |
| `areaCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `sigunguCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `cat1` | 선택 | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | 선택 | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | 선택 | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `lDongRegnCd` | 선택 | `string` | 법정동 시도코드(법정동코드조회 참고) |
| `lDongSignguCd` | 선택 | `string` | 법정동 시군구코드(법정동코드조회 참고, lDongRegnCd 필수입력) |
| `lclsSystm1` | 선택 | `string` | 분류체계 1Deth(분류체계코드조회 참고) |
| `lclsSystm2` | 선택 | `string` | 분류체계 2Deth(분류체계코드조회 참고, lclsSystm1 필수입력) |
| `lclsSystm3` | 선택 | `string` | 분류체계 3Deth(분류체계코드조회 참고, lclsSystm1/lclsSystm2 필수입력) |
| `modifiedtime` | 선택 | `string` | 수정일(형식 :YYYYMMDD) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `lclsSystm2` | `string` | 분류체계 2Deth |
| `lclsSystm3` | `string` | 분류체계 3Deth |
| `tel` | `string` | 전화번호 |
| `title` | `string` | 제목 |
| `addr1` | `string` | 주소 |
| `addr2` | `string` | 상세주소 |
| `areacode` | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `cat1` | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `contentid` | `string` | 콘텐츠ID |
| `contenttypeid` | `string` | 콘텐츠타입ID |
| `createdtime` | `string` | 등록일 |
| `eventstartdate` | `string` | 행사시작일(형식 : YYYYMMDD) |
| `cpyrhtDivCd` | `string` | 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `eventenddate` | `string` | 행사종료일(형식 : YYYYMMDD) |
| `firstimage` | `string` | 대표이미지(원본) |
| `firstimage2` | `string` | 대표이미지(썸네일) |
| `mapx` | `string` | GPS X좌표 |
| `mapy` | `string` | GPS Y좌표 |
| `mlevel` | `string` | Map Level |
| `modifiedtime` | `string` | 수정일 |
| `sigungucode` | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `zipcode` | `string` | 우편번호 |
| `progresstype` | `string` | 축제형태(취소,행사연기) |
| `festivaltype` | `string` | 진행형태(상시,온라인,격년제) |
| `lDongRegnCd` | `string` | 법정동 시도코드 |
| `lDongSignguCd` | `string` | 법정동 시군구코드 |
| `lclsSystm1` | `string` | 분류체계 1Deth |

### GET /searchStay2 — 숙박정보조회

`operationId: searchStay2` · 성공 schema definition: `searchStay2_response`

숙박정보 검색목록을 조회한다. 컨텐츠 타입이 ‘숙박’일 경우에만 유효하다.

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `arrange` | 선택 | `string` | 정렬구분 (A=제목순, C=수정일순, D=생성일순) 대표이미지가반드시있는정렬(O=제목순, Q=수정일순, R=생성일순) |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |
| `areaCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `sigunguCode` | 선택 | `string` |  미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `cat1` | 선택 | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | 선택 | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | 선택 | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `lDongRegnCd` | 필수 | `string` | 법정동 시도코드(법정동코드조회 참고) |
| `lDongSignguCd` | 선택 | `string` | 법정동 시군구코드(법정동코드조회 참고, lDongRegnCd 필수입력) |
| `lclsSystm1` | 선택 | `string` | 분류체계 1Deth(분류체계코드조회 참고) |
| `lclsSystm2` | 선택 | `string` | 분류체계 2Deth(분류체계코드조회 참고, lclsSystm1 필수입력) |
| `lclsSystm3` | 선택 | `string` | 분류체계 3Deth(분류체계코드조회 참고, lclsSystm1/lclsSystm2 필수입력) |
| `modifiedtime` | 선택 | `string` | 수정일(형식 :YYYYMMDD) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `lclsSystm3` | `string` | 분류체계 3Deth |
| `addr1` | `string` | 주소 |
| `cpyrhtDivCd` | `string` | 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `mapy` | `string` | GPS Y좌표 |
| `mlevel` | `string` | Map Level |
| `modifiedtime` | `string` | 수정일 |
| `sigungucode` | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `tel` | `string` | 전화번호 |
| `title` | `string` | 제목 |
| `contentid` | `string` | 콘텐츠ID |
| `contenttypeid` | `string` | 콘텐츠타입ID |
| `createdtime` | `string` | 등록일 |
| `firstimage` | `string` | 대표이미지(원본) |
| `firstimage2` | `string` | 대표이미지(썸네일) |
| `mapx` | `string` | GPS X좌표 |
| `addr2` | `string` | 상세주소 |
| `areacode` | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `cat1` | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `zipcode` | `string` | 우편번호 |
| `lDongRegnCd` | `string` | 법정동 시도코드 |
| `lDongSignguCd` | `string` | 법정동 시군구코드 |
| `lclsSystm1` | `string` | 분류체계 1Deth |
| `lclsSystm2` | `string` | 분류체계 2Deth |

### GET /detailCommon2 — 공통정보조회

`operationId: detailCommon2` · 성공 schema definition: `detailCommon2_response`

타입별공통 정보기본정보,약도이미지,대표이미지,분류정보,지역정보,주소정보,좌표정보,개요정보,길안내정보,이미지정보,연계관광정보목록을 조회하는 기능

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `contentId` | 필수 | `string` | 콘텐츠ID |
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `lclsSystm3` | `string` | 분류체계 3Deth |
| `overview` | `string` | 개요 |
| `contentid` | `string` | 콘텐츠ID |
| `sigungucode` | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `cat1` | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `addr1` | `string` | 주소 |
| `addr2` | `string` | 상세주소 |
| `zipcode` | `string` | 우편번호 |
| `mapx` | `string` | GPS X좌표 |
| `mapy` | `string` | GPS Y좌표 |
| `mlevel` | `string` | Map Level |
| `cpyrhtDivCd` | `string` | 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `contenttypeid` | `string` | 콘텐츠타입ID |
| `createdtime` | `string` | 등록일 |
| `homepage` | `string` | 홈페이지주소 |
| `modifiedtime` | `string` | 수정일 |
| `tel` | `string` | 전화번호 |
| `telname` | `string` | 전화번호명 |
| `title` | `string` | 콘텐츠명(제목) |
| `firstimage` | `string` | 대표이미지(원본) |
| `firstimage2` | `string` | 대표이미지(썸네일) |
| `areacode` | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `lDongRegnCd` | `string` | 법정동 시도코드 |
| `lDongSignguCd` | `string` | 법정동 시군구코드 |
| `lclsSystm1` | `string` | 분류체계 1Deth |
| `lclsSystm2` | `string` | 분류체계 2Deth |

### GET /detailIntro2 — 소개정보조회

`operationId: detailIntro2` · 성공 schema definition: `detailIntro2_response`

상세소개 쉬는날, 개장기간 등 내역을 조회하는 기능

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `contentId` | 필수 | `string` | 콘텐츠ID |
| `contentTypeId` | 필수 | `string` | 관광타입(12:관광지, 14:문화시설, 15:축제공연행사, 25:여행코스, 28:레포츠, 32:숙박, 38:쇼핑, 39:음식점) ID |
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `chkpetculture` | `string` | 문화시설(14) : 애완동물동반가능정보 |
| `eventhomepage` | `string` | 행사공연축제(15) : 행사홈페이지 |
| `eventplace` | `string` | 행사공연축제(15) : 행사장소 |
| `parkingleports` | `string` | 레포츠(28) : 주차시설 |
| `reservation` | `string` | 레포츠(28) : 예약안내 |
| `restdateleports` | `string` | 레포츠(28) : 쉬는날 |
| `eventstartdate` | `string` | 행사공연축제(15) : 행사시작일 |
| `festivalgrade` | `string` | 행사공연축제(15) : 축제등급 |
| `karaoke` | `string` | 숙박(32) : 노래방여부 |
| `discountinfofood` | `string` | 음식점(39) : 할인정보 |
| `firstmenu` | `string` | 음식점(39) : 대표메뉴 |
| `infocenterfood` | `string` | 음식점(39) : 문의및안내 |
| `kidsfacility` | `string` | 음식점(39) : 어린이놀이방여부 |
| `opendatefood` | `string` | 음식점(39) : 개업일 |
| `opentimefood` | `string` | 음식점(39) : 영업시간 |
| `packing` | `string` | 음식점(39) : 포장가능 |
| `parkingfood` | `string` | 음식점(39) : 주차시설 |
| `reservationfood` | `string` | 음식점(39) : 예약안내 |
| `chkcreditcardculture` | `string` | 문화시설(14) : 신용카드가능정보 |
| `scaleleports` | `string` | 레포츠(28) : 규모 |
| `usefeeleports` | `string` | 레포츠(28) : 입장료 |
| `discountinfofestival` | `string` | 행사공연축제(15) : 할인정보 |
| `chkcreditcardfood` | `string` | 음식점(39) : 신용카드가능정보 |
| `eventenddate` | `string` | 행사공연축제(15) : 행사종료일 |
| `playtime` | `string` | 행사공연축제(15) : 공연시간 |
| `chkbabycarriageculture` | `string` | 문화시설(14) : 유모차대여정보 |
| `roomcount` | `string` | 숙박(32) : 객실수 |
| `reservationlodging` | `string` | 숙박(32) : 예약안내 |
| `reservationurl` | `string` | 숙박(32) : 예약안내홈페이지 |
| `roomtype` | `string` | 숙박(32) : 객실유형 |
| `scalelodging` | `string` | 숙박(32) : 규모 |
| `subfacility` | `string` | 숙박(32) : 부대시설 (기타) |
| `barbecue` | `string` | 숙박(32) : 바비큐장여부 |
| `beauty` | `string` | 숙박(32) : 뷰티시설정보 |
| `beverage` | `string` | 숙박(32) : 식음료장여부 |
| `bicycle` | `string` | 숙박(32) : 자전거대여여부 |
| `campfire` | `string` | 숙박(32) : 캠프파이어여부 |
| `fitness` | `string` | 숙박(32) : 휘트니스센터여부 |
| `placeinfo` | `string` | 행사공연축제(15) : 행사장위치안내 |
| `parkinglodging` | `string` | 숙박(32) : 주차시설 |
| `pickup` | `string` | 숙박(32) : 픽업서비스 |
| `publicbath` | `string` | 숙박(32) : 공용샤워실여부 |
| `opendate` | `string` | 관광지(12) : 개장일 |
| `parking` | `string` | 관광지(12) : 주차시설 |
| `restdate` | `string` | 관광지(12) : 쉬는날 |
| `usetimeleports` | `string` | 레포츠(28) : 이용시간 |
| `foodplace` | `string` | 숙박(32) : 식음료장 |
| `infocenterlodging` | `string` | 숙박(32) : 문의및안내 |
| `restdatefood` | `string` | 음식점(39) : 쉬는날 |
| `scalefood` | `string` | 음식점(39) : 규모 |
| `seat` | `string` | 음식점(39) : 좌석수 |
| `smoking` | `string` | 음식점(39) : 금연/흡연여부 |
| `treatmenu` | `string` | 음식점(39) : 취급메뉴 |
| `lcnsno` | `string` | 음식점(39) : 인허가번호 |
| `contentid` | `string` | 기본정보 : 콘텐츠ID |
| `contenttypeid` | `string` | 기본정보 : 콘텐츠타입ID |
| `accomcount` | `string` | 관광지(12) : 수용인원 |
| `chkbabycarriage` | `string` | 관광지(12) : 유모차대여정보 |
| `chkcreditcard` | `string` | 관광지(12) : 신용카드가능정보 |
| `chkpet` | `string` | 관광지(12) : 애완동물동반가능정보 |
| `expagerange` | `string` | 관광지(12) : 체험가능연령 |
| `expguide` | `string` | 관광지(12) : 체험안내 |
| `heritage1` | `string` | 관광지(12) : 세계문화유산유무 |
| `heritage2` | `string` | 관광지(12) : 세계자연유산유무 |
| `heritage3` | `string` | 관광지(12) : 세계기록유산유무 |
| `infocenter` | `string` | 관광지(12) : 문의및안내 |
| `taketime` | `string` | 여행코스(25) : 코스총소요시간 |
| `theme` | `string` | 여행코스(25) : 코스테마 |
| `accomcountleports` | `string` | 레포츠(28) : 수용인원 |
| `chkbabycarriageleports` | `string` | 레포츠(28) : 유모차대여정보 |
| `chkcreditcardleports` | `string` | 레포츠(28) : 신용카드가능정보 |
| `chkpetleports` | `string` | 레포츠(28) : 애완동물동반가능정보 |
| `expagerangeleports` | `string` | 레포츠(28) : 체험가능연령 |
| `infocenterleports` | `string` | 레포츠(28) : 문의및안내 |
| `openperiod` | `string` | 레포츠(28) : 개장기간 |
| `parkingfeeleports` | `string` | 레포츠(28) : 주차요금 |
| `program` | `string` | 행사공연축제(15) : 행사프로그램 |
| `spendtimefestival` | `string` | 행사공연축제(15) : 관람소요시간 |
| `sponsor1` | `string` | 행사공연축제(15) : 주최자정보 |
| `sponsor1tel` | `string` | 행사공연축제(15) : 주최자연락처 |
| `discountinfo` | `string` | 문화시설(14) : 할인정보 |
| `infocenterculture` | `string` | 문화시설(14) : 문의및안내 |
| `parkingculture` | `string` | 문화시설(14) : 주차시설 |
| `parkingfee` | `string` | 문화시설(14) : 주차요금 |
| `restdateculture` | `string` | 문화시설(14) : 쉬는날 |
| `usefee` | `string` | 문화시설(14) : 이용요금 |
| `usetimeculture` | `string` | 문화시설(14) : 이용시간 |
| `scale` | `string` | 문화시설(14) : 규모 |
| `spendtime` | `string` | 문화시설(14) : 관람소요시간 |
| `agelimit` | `string` | 행사공연축제(15) : 관람가능연령 |
| `bookingplace` | `string` | 행사공연축제(15) : 예매처 |
| `useseason` | `string` | 관광지(12) : 이용시기 |
| `usetime` | `string` | 관광지(12) : 이용시간 |
| `accomcountculture` | `string` | 문화시설(14) : 수용인원 |
| `sponsor2` | `string` | 행사공연축제(15) : 주관사정보 |
| `sponsor2tel` | `string` | 행사공연축제(15) : 주관사연락처 |
| `subevent` | `string` | 행사공연축제(15) : 부대행사 |
| `usetimefestival` | `string` | 행사공연축제(15) : 이용요금 |
| `distance` | `string` | 여행코스(25) : 코스총거리 |
| `infocentertourcourse` | `string` | 여행코스(25) : 문의및안내 |
| `schedule` | `string` | 여행코스(25) : 코스일정 |
| `publicpc` | `string` | 숙박(32) : 공용 PC실여부 |
| `sauna` | `string` | 숙박(32) : 사우나실여부 |
| `seminar` | `string` | 숙박(32) : 세미나실여부 |
| `sports` | `string` | 숙박(32) : 스포츠시설여부 |
| `refundregulation` | `string` | 숙박(32) : 환불규정 |
| `chkbabycarriageshopping` | `string` | 쇼핑(38) : 유모차대여정보 |
| `chkcreditcardshopping` | `string` | 쇼핑(38) : 신용카드가능정보 |
| `chkpetshopping` | `string` | 쇼핑(38) : 애완동물동반가능정보 |
| `culturecenter` | `string` | 쇼핑(38) : 문화센터바로가기 |
| `fairday` | `string` | 쇼핑(38) : 장서는날 |
| `infocentershopping` | `string` | 쇼핑(38) : 문의및안내 |
| `opendateshopping` | `string` | 쇼핑(38) : 개장일 |
| `opentime` | `string` | 쇼핑(38) : 영업시간 |
| `parkingshopping` | `string` | 쇼핑(38) : 주차시설 |
| `restdateshopping` | `string` | 쇼핑(38) : 쉬는날 |
| `restroom` | `string` | 쇼핑(38) : 화장실설명 |
| `saleitem` | `string` | 쇼핑(38) : 판매품목 |
| `saleitemcost` | `string` | 쇼핑(38) : 판매품목별가격 |
| `scaleshopping` | `string` | 쇼핑(38) : 규모 |
| `shopguide` | `string` | 쇼핑(38) : 매장안내 |
| `checkintime` | `string` | 숙박(32) : 입실시간 |
| `checkouttime` | `string` | 숙박(32) : 퇴실시간 |
| `chkcooking` | `string` | 숙박(32) : 객실내취사여부 |
| `accomcountlodging` | `string` | 숙박(32) : 수용가능인원 |

### GET /detailInfo2 — 반복정보조회

`operationId: detailInfo2` · 성공 schema definition: `detailInfo2_response`

추가 관광정보 상세내역을 조회한다. 상세반복정보를 안내URL의 국문관광정보 상세 매뉴얼 문서를 참고하시기 바랍니다.

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `contentId` | 필수 | `string` | 콘텐츠ID |
| `contentTypeId` | 필수 | `string` | 관광타입(12:관광지, 14:문화시설, 15:축제공연행사, 28:레포츠, 32:숙박, 38:쇼핑, 39:음식점) ID |
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `roomoffseasonminfee1` | `string` | 숙박(32) : 비수기주중최소 |
| `roomimg4` | `string` | 숙박(32) : 객실사진4 |
| `roomtoiletries` | `string` | 숙박(32) : 세면도구여부 |
| `roomsofa` | `string` | 숙박(32) : 소파여부 |
| `roomcook` | `string` | 숙박(32) : 취사용품여부 |
| `roomtable` | `string` | 숙박(32) : 테이블여부 |
| `roomimg5alt` | `string` | 숙박(32) : 객실사진5 설명 |
| `contentid` | `string` | 기본정보 : 콘텐츠ID |
| `contenttypeid` | `string` | 기본정보 : 콘텐츠타입ID |
| `fldgubun` | `string` | 숙박/여행코스제외타입 : 일련번호 |
| `infoname` | `string` | 숙박/여행코스제외타입 : 제목 |
| `infotext` | `string` | 숙박/여행코스제외타입 : 내용 |
| `serialnum` | `string` | 숙박/여행코스제외타입 : 반복일련번호 |
| `subcontentid` | `string` | 여행코스(25) : 하위콘텐츠ID |
| `subdetailalt` | `string` | 여행코스(25) : 코스이미지설명 |
| `subdetailimg` | `string` | 여행코스(25) : 코스이미지 |
| `subdetailoverview` | `string` | 여행코스(25) : 코스개요 |
| `subname` | `string` | 여행코스(25) : 코스명 |
| `subnum` | `string` | 여행코스(25) : 반복일련번호 |
| `roomcode` | `string` | 숙박(32) : 객실코드 |
| `roomtitle` | `string` | 숙박(32) : 객실명칭 |
| `roomsize1` | `string` | 숙박(32) : 객실크기(평) |
| `roomcount` | `string` | 숙박(32) : 객실수 |
| `roombasecount` | `string` | 숙박(32) : 기준인원 |
| `roommaxcount` | `string` | 숙박(32) : 최대인원 |
| `roomoffseasonminfee2` | `string` | 숙박(32) : 비수기주말최소 |
| `roompeakseasonminfee1` | `string` | 숙박(32) : 성수기주중최소 |
| `roompeakseasonminfee2` | `string` | 숙박(32) : 성수기주말최소 |
| `roomintro` | `string` | 숙박(32) : 객실소개 |
| `roombathfacility` | `string` | 숙박(32) : 목욕시설여부 |
| `roombath` | `string` | 숙박(32) : 욕조여부 |
| `roomhometheater` | `string` | 숙박(32) : 홈시어터여부 |
| `roomaircondition` | `string` | 숙박(32) : 에어컨여부 |
| `roomtv` | `string` | 숙박(32) : TV 여부 |
| `roompc` | `string` | 숙박(32) : PC 여부 |
| `roomcable` | `string` | 숙박(32) : 케이블설치여부 |
| `roominternet` | `string` | 숙박(32) : 인터넷여부 |
| `roomrefrigerator` | `string` | 숙박(32) : 냉장고여부 |
| `roomimg5` | `string` | 숙박(32) : 객실사진5 |
| `roomimg3` | `string` | 숙박(32) : 객실사진3 |
| `roomimg4alt` | `string` | 숙박(32) : 객실사진4 설명 |
| `roomimg3alt` | `string` | 숙박(32) : 객실사진3 설명 |
| `roomhairdryer` | `string` | 숙박(32) : 드라이기여부 |
| `roomsize2` | `string` | 숙박(32) : 객실크기(평방미터) |
| `roomimg2alt` | `string` | 숙박(32) : 객실사진2 설명 |
| `roomimg1` | `string` | 숙박(32) : 객실사진1 |
| `roomimg1alt` | `string` | 숙박(32) : 객실사진1 설명 |
| `roomimg2` | `string` | 숙박(32) : 객실사진2 |
| `cpyrhtDivCd1` | `string` | 숙박(32) : 객실사진 1 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `cpyrhtDivCd2` | `string` | 숙박(32) : 객실사진 2 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `cpyrhtDivCd3` | `string` | 숙박(32) : 객실사진 3 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `cpyrhtDivCd4` | `string` | 숙박(32) : 객실사진 4 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `cpyrhtDivCd5` | `string` | 숙박(32) : 객실사진 5 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |

### GET /detailImage2 — 이미지정보조회

`operationId: detailImage2` · 성공 schema definition: `detailImage2_response`

관광정보에 매핑되는 서브이미지목록 및 이미지 저작권 공공누리유형을 조회하는 기능

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `contentId` | 필수 | `string` | 콘텐츠ID |
| `imageYN` | 선택 | `string` | 이미지조회1 : Y=콘텐츠이미지조회 N=”음식점”타입의음식메뉴이미지 |
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `cpyrhtDivCd` | `string` | 저작권 유형 (Type1:제1유형(출처표시-권장), Type3:제3유형(제1유형+변경금지) |
| `contentid` | `string` | 기본정보 : 콘텐츠ID |
| `imgname` | `string` | 이미지명 |
| `originimgurl` | `string` | 원본이미지 |
| `serialnum` | `string` | 이미지일련번호 |
| `smallimageurl` | `string` | 썸네일이미지 |

### GET /lclsSystmCode2 — 분류체계 코드조회

`operationId: lclsSystmCode2` · 성공 schema definition: `lclsSystmCode2_response`

분류체계코드목록을 1Deth, 2Deth, 3Deth 코드별 조회하는 기능

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `serviceKey` | 필수 | `string` | 공공데이터포털에서 받은 인증키 |
| `numOfRows` | 선택 | `number` | 한 페이지 결과 수 |
| `pageNo` | 선택 | `number` | 페이지 번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은 XML) |
| `lclsSystm1` | 선택 | `string` | 분류체계 1Depth 코드 |
| `lclsSystm2` | 선택 | `string` | 분류체계 2Depth 코드(lclsSystm1 필수) |
| `lclsSystm3` | 선택 | `string` | 분류체계 3Depth 코드(lclsSystm1,lclsSystm2 필수) |
| `lclsSystmListYn` | 선택 | `string` | 분류체계 목록조회 여부(N:코드조회 , Y:전체목록조회) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `lclsSystm3Nm` | `string` | 분류체계 소분류명(lclsSystmListYn : Y 일때 표출) |
| `name` | `string` | 코드:1Depth,2Depth,3Depth 코드명(lclsSystmListYn : N 일때 표출) |
| `rnum` | `string` | 일련번호 |
| `code` | `string` | 코드:1Depth,2Depth,3Depth 코드(lclsSystmListYn : N 일때 표출) |
| `lclsSystm1Cd` | `string` | 분류체계 대분류코드(lclsSystmListYn : Y 일때 표출) |
| `lclsSystm1Nm` | `string` | 분류체계 대분류명(lclsSystmListYn : Y 일때 표출) |
| `lclsSystm2Cd` | `string` | 분류체계 중분류코드(lclsSystmListYn : Y 일때 표출) |
| `lclsSystm2Nm` | `string` | 분류체계 중분류명(lclsSystmListYn : Y 일때 표출) |
| `lclsSystm3Cd` | `string` | 분류체계 소분류코드(lclsSystmListYn : Y 일때 표출) |

### GET /areaBasedSyncList2 — 관광정보 동기화 목록 조회

`operationId: areaBasedSyncList2` · 성공 schema definition: `areaBasedSyncList2_response`

지역기반 관광정보파라미터 타입에 따라서 제목순,수정일순,등록일순 정렬검색목록을 조회하는 기능

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `numOfRows` | 선택 | `number` | 한페이지결과수 |
| `pageNo` | 선택 | `number` | 페이지번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `serviceKey` | 필수 | `string` | 인증키(서비스키) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `showflag` | 선택 | `string` | 컨텐츠표출여부(1=표출, 0=비표출) |
| `arrange` | 선택 | `string` | 정렬 구분 (A=제목순, C=수정일순, D=생성일순) 대표이미지가 반드시 있는 정렬 (O=제목순, Q=수정일순, R=생성일순) |
| `contentTypeId` | 선택 | `string` | 관광타입(12:관광지, 14:문화시설, 15:축제공연행사, 25:여행코스, 28:레포츠, 32:숙박, 38:쇼핑, 39:음식점) ID |
| `areaCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `sigunguCode` | 선택 | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `cat1` | 선택 | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | 선택 | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | 선택 | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `lDongRegnCd` | 선택 | `string` | 법정동 시도코드(법정동코드조회 참고) |
| `lDongSignguCd` | 선택 | `string` | 법정동 시군구코드(법정동코드조회 참고, lDongRegnCd 필수입력) |
| `lclsSystm1` | 선택 | `string` | 분류체계 1Deth(분류체계코드조회 참고) |
| `lclsSystm2` | 선택 | `string` | 분류체계 2Deth(분류체계코드조회 참고, lclsSystm1 필수입력) |
| `lclsSystm3` | 선택 | `string` | 분류체계 3Deth(분류체계코드조회 참고, lclsSystm1/lclsSystm2 필수입력) |
| `modifiedtime` | 선택 | `string` | 수정일(형식 :YYYYMMDD) |
| `oldContentid` | 선택 | `string` | 이전 컨텐츠 ID ( DB 저장 동기화시 이전 KEY 값으로 조회 용도  ) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `oldContentid` | `string` | 이전 컨텐츠 ID ( DB 저장 동기화시 이전 KEY 값으로 조회 용도  ) |
| `lclsSystm3` | `string` | 분류체계 3Deth |
| `mapx` | `string` | GPS X좌표(WGS84 경도좌표) 응답 |
| `mapy` | `string` | GPS Y좌표(WGS84 위도좌표) 응답 |
| `mlevel` | `string` | Map Level 응답 |
| `modifiedtime` | `string` | 콘텐츠수정일 |
| `showflag` | `string` | 표출여부(0:비표출,1:표출) |
| `sigungucode` | `string` | 미사용항목(삭제예정-법정동 시군구 코드 대체) |
| `tel` | `string` | 전화번호 |
| `title` | `string` | 콘텐츠제목 |
| `addr1` | `string` | 주소(예, 서울중구다동)를응답 |
| `addr2` | `string` | 상세주소 |
| `areacode` | `string` | 미사용항목(삭제예정-법정동 시도코드 대체) |
| `cat1` | `string` | 미사용항목(삭제예정-분류체계 1Deth 코드로 대체) |
| `cat2` | `string` | 미사용항목(삭제예정-분류체계 2Deth 코드로 대체) |
| `cat3` | `string` | 미사용항목(삭제예정-분류체계 3Deth 코드로 대체) |
| `contentid` | `string` | 콘텐츠ID |
| `contenttypeid` | `string` | 관광타입(관광지, 숙박등) ID |
| `createdtime` | `string` | 콘텐츠최초등록일 |
| `cpyrhtDivCd` | `string` | 필수요청파라메터가없음(NO_MANDATORY_REQUEST_PARAMETERS_ERROR) |
| `firstimage` | `string` | 원본대표이미지 (약 500*333 size) URL 응답 |
| `firstimage2` | `string` | 썸네일대표이미지 (약 150*100 size) URL 응답 |
| `zipcode` | `string` | 우편번호 |
| `lDongRegnCd` | `string` | 법정동 시도코드 |
| `lDongSignguCd` | `string` | 법정동 시군구코드 |
| `lclsSystm1` | `string` | 분류체계 1Deth |
| `lclsSystm2` | `string` | 분류체계 2Deth |

### GET /ldongCode2 — 법정동코드조회

`operationId: ldongCode2` · 성공 schema definition: `ldongCode2_response`

법정동코드 목록을 시도,시군구 코드별 조회하는 기능

입력: 모든 행은 `in: query`; 정식 default/enum은 전부 미지정. 아래 설명은 공식 문자열을 그대로 옮겼다.

| 입력 이름 | 필수 | 타입 | 공식 설명 |
|---|---|---|---|
| `serviceKey` | 필수 | `string` | 공공데이터포털에서 받은 인증키 |
| `numOfRows` | 선택 | `number` | 한 페이지 결과 수 |
| `pageNo` | 선택 | `number` | 페이지 번호 |
| `MobileOS` | 필수 | `string` | OS 구분 : IOS (아이폰), AND (안드로이드), WEB (웹), ETC(기타) |
| `MobileApp` | 필수 | `string` | 서비스명(어플명) |
| `_type` | 선택 | `string` | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은 XML) |
| `lDongRegnCd` | 선택 | `string` | 법정동 시도코드 ( lDongRegnCd 해당되는 법정동 시군구코드 조회 , 입력이 없을시 전체 시도목록 호출  ) |
| `lDongListYn` | 선택 | `string` | 법정동 목록조회 여부(N:코드조회 , Y:전체목록조회) |

출력: 공통 header/body 경로는 위 표를 따른다. 아래는 `body.items.item`의 전체 하위 필드이며 필수·null·개수 조건은 모두 미기재다.

| 출력 필드 | 타입 | 공식 설명 |
|---|---|---|
| `lDongSignguNm` | `string` | 법정동 시군구명 (lDongListYn : Y일때 표출) |
| `name` | `string` | 법정동 : 시도명, 시군구명 (lDongListYn : N일때 표출) |
| `rnum` | `string` | 일련번호 |
| `code` | `string` | 법정동 : 시도코드, 시군구코드 (lDongListYn : N일때 표출) |
| `lDongRegnCd` | `string` | 법정동 시도코드 (lDongListYn : Y일때 표출) |
| `lDongRegnNm` | `string` | 법정동 시도명 (lDongListYn : Y일때 표출) |
| `lDongSignguCd` | `string` | 법정동 시군구코드 (lDongListYn : Y일때 표출) |

## 이용 조건과 콘텐츠 검토 경계

공식 카탈로그는 이용허락범위를 **제한 없음**으로 표시하며, 자유롭게 활용 가능한 관광정보를 선별 제공한다고 설명한다. 동시에 사진은 공공누리 1·3유형이라고 명시한다. 이 안내와 개별 응답의 `cpyrhtDivCd`, 이미지별 `cpyrhtDivCd1`~`cpyrhtDivCd5`를 함께 보존한다. 사진의 명예훼손·인격권 침해 용도 및 기업 CI/BI 이용 제한도 카탈로그 설명에 있다.

카탈로그의 일반 이용 조건을 개별 콘텐츠의 사실 검증 완료나 모든 이미지·음성의 재사용 승인으로 자동 해석하지 않는다. 공개 문서에는 외부 LLM 입력을 별도 명시한 문구가 없으므로 수집 데이터는 출처·수집시각·원본 수정시각·권리 정보를 갖춘 검토 후보로 보관한다. 미디어 URL 조회는 미디어 다운로드·재생·재배포를 뜻하지 않는다.

출처: [국문 관광정보 공식 카탈로그](https://www.data.go.kr/data/15101578/openapi.do), [공공데이터포털 이용 정책](https://www.data.go.kr/ugs/selectPortalPolicyView.do), [관광공사 새 지역·분류 코드 안내](https://api.visitkorea.or.kr/#/cmsNoticeDetail?no=207).

## 검증 범위

이 문서는 공식 공개 Swagger의 전체 필드 보존·해석 문서다. 인증키를 읽거나 실제 인증 요청을 수행하지 않고 작성했다. 모든 operation의 실서비스 동작, JSON/XML 차이, 빈 결과, 항목별 사용 권한은 별도 실제 연동 검증 기록을 확인해야 한다. 원문 계약의 불완전한 array/nullable 정의를 임의로 정밀한 계약인 것처럼 고치지 않았다.
