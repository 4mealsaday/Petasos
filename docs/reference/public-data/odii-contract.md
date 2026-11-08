# 오디(Odii) 공식 API 입출력 계약

2026-11-07 확인. 범위는 공공데이터포털 데이터셋 `15101971`의 현행 공개 Swagger 전체다. 인증키·계정 설정은 읽지 않았으며 이 문서 수집 과정에서 인증된 API 호출을 하지 않았다.

- 원천: [한국관광공사 관광지 오디오 가이드정보_GW](https://www.data.go.kr/data/15101971/openapi.do)
- 수집 시각(UTC): `2026-11-07T05:53:21Z`
- 기계 판독 원본: [odii.openapi.json](./odii.openapi.json)
- 원문 HTML·추출 JSON·저장 JSON의 SHA-256과 추출 방식: [odii-source-metadata.json](./odii-source-metadata.json)
- 공개 HTML의 `const swaggerJson`을 JSON으로 파싱하여 들여쓰기만 변경했다. `swaggerOprtinVOs` 확장도 보존했다. 잘못되거나 불완전해 보이는 타입·설명도 임의 수정하지 않았다.

## 서비스와 전송

- Swagger `2.0`, info.version `1.0.0`.
- HTTPS 기본 주소: `https://apis.data.go.kr/B551011/Odii`.
- 원문은 `https`와 `http`를 모두 기재한다. Petasos는 HTTPS를 사용한다.
- 8개 operation 모두 GET이며 모든 입력은 query parameter다. request body와 path parameter는 없다.
- 모든 operation의 consumes는 `application/json`, produces는 `application/json`, `application/xml`이다. consumes 표기를 GET JSON body 요구로 해석하지 않는다.
- `_type=json`으로 JSON을 요청한다. 설명에는 생략 시 XML이 기본이라고 되어 있으나 Swagger `default` 키로 선언되지는 않았다.
- 인증키는 일반 인증키(Decoding)를 query serializer에 원문으로 전달하고 한 번만 URL 인코딩한다. [공식 GW 사용 가이드](https://www.data.go.kr/images/biz/swagger-guide/gw/gateway_swagger_guide.pdf). 전체 요청 URL과 키는 로그에 남기지 않는다.

## 전체 operation 목록

| 메서드·경로 | operationId | 기능 | 입력 수 | 필수 입력 수 | item 필드 수 |
| --- | --- | --- | ---: | ---: | ---: |
| GET `/themeBasedList` | `themeBasedList` | 관광지 기본 정보 목록 조회 | 7 | 4 | 13 |
| GET `/themeLocationBasedList` | `themeLocationBasedList` | 관광지 위치기반 정보 목록 조회 | 10 | 7 | 13 |
| GET `/themeSearchList` | `themeSearchList` | 관광지 키워드 검색 목록 조회 | 8 | 5 | 13 |
| GET `/storyLocationBasedList` | `storyLocationBasedList` | 이야기 위치기반 정보 목록 조회 | 10 | 7 | 15 |
| GET `/storySearchList` | `storySearchList` | 이야기 키워드 검색 목록 조회 | 8 | 5 | 15 |
| GET `/themeBasedSyncList` | `themeBasedSyncList` | 관광지정보 동기화 목록 조회 | 9 | 4 | 14 |
| GET `/storyBasedSyncList` | `storyBasedSyncList` | 이야기정보 동기화 목록 조회 | 11 | 4 | 16 |
| GET `/storyBasedList` | `storyBasedList` | 이야기 기본 정보 목록 조회 | 9 | 4 | 15 |

## 입력 계약 해석

- 다음 표의 타입·필수 여부는 `paths.<path>.parameters` 원문 그대로다. optional은 생략 가능하며 빈 문자열을 전송하라는 뜻이 아니다.
- 모든 query parameter에서 `default`, `enum`, `deprecated` 키는 미정의다. 표에는 미정의를 명시한다. operation 수준의 `deprecated`도 미정의이며 폐기되었다는 의미가 아니다.
- 서술형 제약은 별도로 적용한다: `MobileOS` 설명의 IOS/AND/WIN/ETC, `langCode`의 ko/en/cn1/jp, `radius`의 최대 20000m, `syncStatus`의 A/U/D, `modifiedtime`의 YYYYMMDD.
- `storyBasedList`, `storyBasedSyncList`에서 `tid`와 `tlid`는 각각 optional이지만 특정 관광지 조회 시 함께 입력하라고 설명한다. 스키마의 독립 required 값만으로 이 의존관계를 놓치지 않는다.
- `numOfRows`, `pageNo`는 일부 operation에서 string, 일부에서 number로 기재된다. 이 차이를 원본에서 정규화하지 않았다. 호출 계층은 실제 query 문자열로 직렬화하되 페이지·개수 값의 검증을 따로 한다.
- `swaggerOprtinVOs.reqList.paramtrBassValue`는 포털 실행 UI의 샘플이다. 예를 들어 numOfRows=10/pageNo=1/MobileOS=ETC/MobileApp=AppTest/langCode=ko/syncStatus=A는 서버 기본값의 증거가 아니다.

### GET /themeBasedList

operationId: `themeBasedList`. 관광지 기본정보 목록을 조회하는 기능입니다.

| 이름 | 위치 | 타입 | 필수 | default | enum | deprecated | 공식 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `numOfRows` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 한페이지결과수 |
| `pageNo` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 페이지번호 |
| `MobileOS` | query | string | 예 | 미정의 | 미정의 | 미정의 | OS 구분 : IOS (아이폰), AND (안드로이드), WIN (윈도우폰), ETC(기타) |
| `MobileApp` | query | string | 예 | 미정의 | 미정의 | 미정의 | 서비스명(어플명) |
| `serviceKey` | query | string | 예 | 미정의 | 미정의 | 미정의 | 인증키(서비스키) |
| `_type` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `langCode` | query | string | 예 | 미정의 | 미정의 | 미정의 | 언어 (ko : 국문, en : 영문, cn1 : 중문간체 , jp : 일문) |

### GET /themeLocationBasedList

operationId: `themeLocationBasedList`. 내주변 좌표를 기반으로 관광지 정보목록을 조회하는 기능입니다.

| 이름 | 위치 | 타입 | 필수 | default | enum | deprecated | 공식 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `numOfRows` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 한페이지결과수 |
| `pageNo` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 페이지번호 |
| `MobileOS` | query | string | 예 | 미정의 | 미정의 | 미정의 | OS 구분 : IOS (아이폰), AND (안드로이드), WIN (윈도우폰), ETC(기타) |
| `MobileApp` | query | string | 예 | 미정의 | 미정의 | 미정의 | 서비스명(어플명) |
| `serviceKey` | query | string | 예 | 미정의 | 미정의 | 미정의 | 인증키(서비스키) |
| `_type` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `mapX` | query | string | 예 | 미정의 | 미정의 | 미정의 | 경도 : GPS X좌표(WGS84 경도 좌표) |
| `mapY` | query | string | 예 | 미정의 | 미정의 | 미정의 | 위도 : GPS Y좌표(WGS84 위도 좌표) |
| `radius` | query | string | 예 | 미정의 | 미정의 | 미정의 | 거리 반경 : 거리 반경(단위:m) ,Max값 20000m=20Km |
| `langCode` | query | string | 예 | 미정의 | 미정의 | 미정의 | 언어 (ko : 국문, en : 영문, cn1 : 중문간체 , jp : 일문) |

### GET /themeSearchList

operationId: `themeSearchList`. 키워드로 검색을 하여 관광지정보 목록을 조회하는 기능입니다.

| 이름 | 위치 | 타입 | 필수 | default | enum | deprecated | 공식 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `numOfRows` | query | number | 아니요 | 미정의 | 미정의 | 미정의 | 한페이지결과수 |
| `pageNo` | query | number | 아니요 | 미정의 | 미정의 | 미정의 | 페이지번호 |
| `MobileOS` | query | string | 예 | 미정의 | 미정의 | 미정의 | OS 구분 : IOS (아이폰), AND (안드로이드), WIN (윈도우폰), ETC(기타) |
| `MobileApp` | query | string | 예 | 미정의 | 미정의 | 미정의 | 서비스명(어플명) |
| `serviceKey` | query | string | 예 | 미정의 | 미정의 | 미정의 | 인증키(서비스키) |
| `_type` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `keyword` | query | string | 예 | 미정의 | 미정의 | 미정의 | 요청 키워드 : (인코딩 필요) |
| `langCode` | query | string | 예 | 미정의 | 미정의 | 미정의 | 언어 (ko : 국문, en : 영문, cn1 : 중문간체 , jp : 일문) |

### GET /storyLocationBasedList

operationId: `storyLocationBasedList`. 내주변 좌표를 기반으로 이야기 정보 목록을 조회하는 기능입니다.

| 이름 | 위치 | 타입 | 필수 | default | enum | deprecated | 공식 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `numOfRows` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 한페이지결과수 |
| `pageNo` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 페이지번호 |
| `MobileOS` | query | string | 예 | 미정의 | 미정의 | 미정의 | OS 구분 : IOS (아이폰), AND (안드로이드), WIN (윈도우폰), ETC(기타) |
| `MobileApp` | query | string | 예 | 미정의 | 미정의 | 미정의 | 서비스명(어플명) |
| `serviceKey` | query | string | 예 | 미정의 | 미정의 | 미정의 | 인증키(서비스키) |
| `_type` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `mapX` | query | string | 예 | 미정의 | 미정의 | 미정의 | 경도 : GPS X좌표(WGS84 경도 좌표) |
| `mapY` | query | string | 예 | 미정의 | 미정의 | 미정의 | 위도 : GPS Y좌표(WGS84 위도 좌표) |
| `radius` | query | string | 예 | 미정의 | 미정의 | 미정의 | 거리 반경 : 거리 반경(단위:m) ,Max값 20000m=20Km |
| `langCode` | query | string | 예 | 미정의 | 미정의 | 미정의 | 언어 (ko : 국문, en : 영문, cn1 : 중문간체 , jp : 일문) |

### GET /storySearchList

operationId: `storySearchList`. 키워드로 검색을 하여 이야기 정보 목록을 조회하는 기능입니다.

| 이름 | 위치 | 타입 | 필수 | default | enum | deprecated | 공식 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `numOfRows` | query | number | 아니요 | 미정의 | 미정의 | 미정의 | 한페이지결과수 |
| `pageNo` | query | number | 아니요 | 미정의 | 미정의 | 미정의 | 페이지번호 |
| `MobileOS` | query | string | 예 | 미정의 | 미정의 | 미정의 | OS 구분 : IOS (아이폰), AND (안드로이드), WIN (윈도우폰), ETC(기타) |
| `MobileApp` | query | string | 예 | 미정의 | 미정의 | 미정의 | 서비스명(어플명) |
| `serviceKey` | query | string | 예 | 미정의 | 미정의 | 미정의 | 인증키(서비스키) |
| `_type` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `keyword` | query | string | 예 | 미정의 | 미정의 | 미정의 | 요청 키워드 : (인코딩 필요) |
| `langCode` | query | string | 예 | 미정의 | 미정의 | 미정의 | 언어 (ko : 국문, en : 영문, cn1 : 중문간체 , jp : 일문) |

### GET /themeBasedSyncList

operationId: `themeBasedSyncList`. 관광지정보 동기화 목록을 조회하는 기능

| 이름 | 위치 | 타입 | 필수 | default | enum | deprecated | 공식 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `numOfRows` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 한페이지결과수 |
| `pageNo` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 페이지번호 |
| `MobileOS` | query | string | 예 | 미정의 | 미정의 | 미정의 | OS 구분 : IOS (아이폰), AND (안드로이드), WIN (윈도우폰), ETC(기타) |
| `MobileApp` | query | string | 예 | 미정의 | 미정의 | 미정의 | 서비스명(어플명) |
| `serviceKey` | query | string | 예 | 미정의 | 미정의 | 미정의 | 인증키(서비스키) |
| `_type` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `langCode` | query | string | 예 | 미정의 | 미정의 | 미정의 | 언어 (ko : 국문, en : 영문, cn1 : 중문간체 , jp : 일문) |
| `syncStatus` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 컨텐츠상태 (A=신규,U=수정,D=삭제) |
| `modifiedtime` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 수정일 (형식 : YYYYMMDD) |

### GET /storyBasedSyncList

operationId: `storyBasedSyncList`. 이야기정보 동기화 목록을 조회하는 기능

| 이름 | 위치 | 타입 | 필수 | default | enum | deprecated | 공식 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `numOfRows` | query | number | 아니요 | 미정의 | 미정의 | 미정의 | 한페이지결과수 |
| `pageNo` | query | number | 아니요 | 미정의 | 미정의 | 미정의 | 페이지번호 |
| `MobileOS` | query | string | 예 | 미정의 | 미정의 | 미정의 | OS 구분 : IOS (아이폰), AND (안드로이드), WIN (윈도우폰), ETC(기타) |
| `MobileApp` | query | string | 예 | 미정의 | 미정의 | 미정의 | 서비스명(어플명) |
| `serviceKey` | query | string | 예 | 미정의 | 미정의 | 미정의 | 인증키(서비스키) |
| `_type` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `langCode` | query | string | 예 | 미정의 | 미정의 | 미정의 | 언어 (ko : 국문, en : 영문, cn1 : 중문간체 , jp : 일문) |
| `tid` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 관광지아이디( key값으로 해당정보 조회시 tlid와 동시에 입력시 조회 가능함. ) |
| `tlid` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 관광지언어아이디( key값으로 해당정보 조회시 tid와 동시에 입력시 조회 가능함. ) |
| `syncStatus` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 컨텐츠상태(A=신규,U=수정,D=삭제) |
| `modifiedtime` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 수정일 (형식 : YYYYMMDD) |

### GET /storyBasedList

operationId: `storyBasedList`. 이야기 기본정보 목록을 조회하는 기능입니다.

| 이름 | 위치 | 타입 | 필수 | default | enum | deprecated | 공식 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `numOfRows` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 한페이지결과수 |
| `pageNo` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 페이지번호 |
| `MobileOS` | query | string | 예 | 미정의 | 미정의 | 미정의 | OS 구분 : IOS (아이폰), AND (안드로이드), WIN (윈도우폰), ETC(기타) |
| `MobileApp` | query | string | 예 | 미정의 | 미정의 | 미정의 | 서비스명(어플명) |
| `serviceKey` | query | string | 예 | 미정의 | 미정의 | 미정의 | 인증키(서비스키) |
| `_type` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 응답메세지 형식 : REST방식의 URL호출 시 json값 추가(디폴트 응답메세지 형식은XML) |
| `langCode` | query | string | 예 | 미정의 | 미정의 | 미정의 | 언어 (ko : 국문, en : 영문, cn1 : 중문간체 , jp : 일문) |
| `tid` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 관광지아이디 ( key값으로 해당정보 조회시 tlid와 동시에 입력시 조회 가능함. ) |
| `tlid` | query | string | 아니요 | 미정의 | 미정의 | 미정의 | 관광지언어아이디(  key값으로 해당정보 조회시 tid와 동시에 입력시 조회 가능함.  ) |

## 공통 출력 구조와 cardinality

모든 operation의 HTTP 200 schema는 object이고 `header`, `body` property를 참조한다. XML 루트 이름은 `response`다. 공식 Swagger는 JSON 최상위 `response` wrapper를 별도 property로 선언하지 않는다. 실제 호출 파서는 wire JSON의 `response.header/body` 구조를 처리하되, 문서 원본에 wrapper를 추가하지 않는다.

| 경로(XML은 response 아래) | 원문 타입 | 필수·반복 수 |
| --- | --- | --- |
| `header` | object `$ref: #/definitions/<operationId>_header` | required 미정의 |
| `header.resultCode` | string | required/enum 미정의 |
| `header.resultMsg` | string | required 미정의 |
| `body` | object `$ref: #/definitions/<operationId>_body` | required 미정의 |
| `body.numOfRows` | number | required 미정의 |
| `body.pageNo` | number | required 미정의 |
| `body.totalCount` | number | required 미정의 |
| `body.items` | object `$ref: #/definitions/<operationId>_items` | required 미정의 |
| `body.items.item` | object `$ref: #/definitions/<operationId>_item` | 원문은 array가 아님; minItems/maxItems/반복 수 미정의 |

모든 definition은 object이고 required 배열이 없다. 따라서 “항상 모든 필드가 있다”거나 “item이 정확히 1개다”라고 보장할 수 없다. 이 참조 계약의 cardinality 부족을 보완하는 구현 정책은 JSON item object/array를 목록으로 정규화하고 XML의 반복 item을 수집하며 빈 목록을 별도로 처리하는 것이다. 누락·공백 좌표를 0으로 채우지 않는다.

이야기 대본·음성·사진 URL은 값으로만 읽는다. 연결 검사 단계에서 자산 URL을 자동 다운로드하거나 연결 성공을 콘텐츠 권리 검증 완료로 간주하지 않는다. 모든 item 필드는 원문상 string이며 ID, 날짜, 좌표, 재생 시간도 string이다.

## 전체 operation별 item 출력 필드

공통 envelope의 header/body 필드는 앞 절을 모든 operation에 동일하게 적용한다. 아래 표는 각 `<operationId>_item` definition의 모든 필드를 빠짐없이 나열한다. 모든 필드의 required, enum, default, min/max cardinality는 미정의다.

### themeBasedList 응답 item

참조: `#/definitions/themeBasedList_item`.

| 필드 | 타입 | 공식 설명 |
| --- | --- | --- |
| `tid` | string | 관광지아이디 |
| `tlid` | string | 관광지언어아이디 |
| `themeCategory` | string | 테마유형 |
| `addr1` | string | 주소 |
| `addr2` | string | 주소상세 |
| `title` | string | 관광지명 |
| `mapX` | string | 경도(X) |
| `mapY` | string | 위도(Y) |
| `langCheck` | string | 제공언어 |
| `langCode` | string | 언어 |
| `imageUrl` | string | 관광지이미지URL |
| `createdtime` | string | 등록일 |
| `modifiedtime` | string | 수정일 |

### themeLocationBasedList 응답 item

참조: `#/definitions/themeLocationBasedList_item`.

| 필드 | 타입 | 공식 설명 |
| --- | --- | --- |
| `langCheck` | string | 제공언어 |
| `langCode` | string | 언어 |
| `imageUrl` | string | 관광지이미지URL |
| `createdtime` | string | 등록일 |
| `modifiedtime` | string | 수정일 |
| `tid` | string | 관광지아이디 |
| `tlid` | string | 관광지언어아이디 |
| `themeCategory` | string | 테마유형 |
| `addr1` | string | 주소 |
| `addr2` | string | 주소상세 |
| `title` | string | 관광지명 |
| `mapX` | string | 경도(X) |
| `mapY` | string | 위도(Y) |

### themeSearchList 응답 item

참조: `#/definitions/themeSearchList_item`.

| 필드 | 타입 | 공식 설명 |
| --- | --- | --- |
| `tid` | string | 관광지아이디 |
| `tlid` | string | 관광지언어아이디 |
| `themeCategory` | string | 테마유형 |
| `addr1` | string | 주소 |
| `addr2` | string | 주소상세 |
| `title` | string | 관광지명 |
| `mapX` | string | 경도(X) |
| `mapY` | string | 위도(Y) |
| `langCheck` | string | 제공언어 |
| `langCode` | string | 언어 |
| `imageUrl` | string | 관광지이미지URL |
| `createdtime` | string | 등록일 |
| `modifiedtime` | string | 수정일 |

### storyLocationBasedList 응답 item

참조: `#/definitions/storyLocationBasedList_item`.

| 필드 | 타입 | 공식 설명 |
| --- | --- | --- |
| `tid` | string | 관광지아이디 |
| `tlid` | string | 관광지언어아이디 |
| `stid` | string | 이야기아이디 |
| `stlid` | string | 이야기언어아이디 |
| `title` | string | 관광지명 |
| `mapX` | string | 경도(X) |
| `mapY` | string | 위도(Y) |
| `audioTitle` | string | 오디오타이틀 |
| `script` | string | 대본 |
| `playTime` | string | 재생시간 |
| `audioUrl` | string | 오디오파일URL |
| `langCode` | string | 언어 |
| `imageUrl` | string | 관광지이미지URL |
| `createdtime` | string | 등록일 |
| `modifiedtime` | string | 수정일 |

### storySearchList 응답 item

참조: `#/definitions/storySearchList_item`.

| 필드 | 타입 | 공식 설명 |
| --- | --- | --- |
| `tid` | string | 관광지아이디 |
| `tlid` | string | 관광지언어아이디 |
| `stid` | string | 이야기아이디 |
| `stlid` | string | 이야기언어아이디 |
| `title` | string | 관광지명 |
| `mapX` | string | 경도(X) |
| `mapY` | string | 위도(Y) |
| `audioTitle` | string | 오디오타이틀 |
| `script` | string | 대본 |
| `playTime` | string | 재생시간 |
| `audioUrl` | string | 오디오파일URL |
| `langCode` | string | 언어 |
| `imageUrl` | string | 관광지이미지URL |
| `createdtime` | string | 등록일 |
| `modifiedtime` | string | 수정일 |

### themeBasedSyncList 응답 item

참조: `#/definitions/themeBasedSyncList_item`.

| 필드 | 타입 | 공식 설명 |
| --- | --- | --- |
| `syncStatus` | string | 콘텐츠상태 |
| `tid` | string | 관광지아이디 |
| `tlid` | string | 관광지언어아이디 |
| `themeCategory` | string | 테마유형 |
| `addr1` | string | 주소 |
| `addr2` | string | 주소상세 |
| `title` | string | 관광지명 |
| `mapX` | string | 경도(X) |
| `mapY` | string | 위도(Y) |
| `langCheck` | string | 제공언어 |
| `langCode` | string | 언어 |
| `imageUrl` | string | 관광지이미지URL |
| `createdtime` | string | 등록일 |
| `modifiedtime` | string | 수정일 |

### storyBasedSyncList 응답 item

참조: `#/definitions/storyBasedSyncList_item`.

| 필드 | 타입 | 공식 설명 |
| --- | --- | --- |
| `mapX` | string | 경도(X) |
| `title` | string | 관광지명 |
| `mapY` | string | 위도(Y) |
| `audioTitle` | string | 오디오타이틀 |
| `script` | string | 대본 |
| `playTime` | string | 재생시간 |
| `audioUrl` | string | 오디오파일URL |
| `langCode` | string | 언어 |
| `imageUrl` | string | 관광지이미지URL |
| `createdtime` | string | 등록일 |
| `modifiedtime` | string | 수정일 |
| `syncStatus` | string | 콘텐츠상태 |
| `tid` | string | 관광지아이디 |
| `tlid` | string | 관광지언어아이디 |
| `stid` | string | 이야기아이디 |
| `stlid` | string | 이야기언어아이디 |

### storyBasedList 응답 item

참조: `#/definitions/storyBasedList_item`.

| 필드 | 타입 | 공식 설명 |
| --- | --- | --- |
| `tid` | string | 관광지아이디 |
| `tlid` | string | 관광지언어아이디 |
| `stid` | string | 이야기아이디 |
| `stlid` | string | 이야기언어아이디 |
| `title` | string | 관광지명 |
| `mapX` | string | 경도(X) |
| `mapY` | string | 위도(Y) |
| `audioTitle` | string | 오디오타이틀 |
| `script` | string | 대본 |
| `playTime` | string | 재생시간 |
| `audioUrl` | string | 오디오파일URL |
| `langCode` | string | 언어 |
| `imageUrl` | string | 관광지이미지URL |
| `createdtime` | string | 등록일 |
| `modifiedtime` | string | 수정일 |

## 정상·오류 응답

8개 operation 모두 아래와 같은 response code 집합을 기재한다. 공식 Swagger `responses`에 업무 결과 코드와 HTTP 200이 함께 들어 있지만 `00`, `03` 등을 HTTP 상태코드로 해석하지 않는다.

| 원문 responses 키 | 공식 설명 |
| --- | --- |
| `10` | 잘못된요청파라메터에러(INVALID_REQUEST_PARAMETER_ERROR) |
| `11` | 필수요청파라메터가없음(NO_MANDATORY_REQUEST_PARAMETERS_ERROR) |
| `21` | 일시적으로사용할수없는서비스키(TEMPORARILY_DISABLE_THE_SERVICEKEY_ERROR) |
| `33` | 서명되지않은호출(UNSIGNED_CALL_ERROR) |
| `200` | 성공 |
| `00` | 정상(NORMAL_CODE) |
| `02` | 데이터베이스에러(DB_ERROR) |
| `03` | 데이터없음에러(NODATA_ERROR) |
| `05` | 서비스연결실패에러(SERVICETIMEOUT_ERROR) |

`resultCode` property 자체에는 enum이 없다. 2026-11-07 프로젝트의 별도 실제 연결 검사에서는 정상 코드 `0000`이 관찰됐다는 실행 결과가 있으므로, 문서의 `00`과 실응답의 `0000`을 구분한다. 두 정상 문자열 허용은 Petasos 파서의 명시적 호환 규칙이며 이 공개 Swagger를 수정하거나 새로운 공식 enum을 만들어 넣는 근거가 아니다. 이 계약 수집 작업은 실제 인증 호출을 수행하지 않았다.

공식 포털 페이지의 공통 GW 에러 안내는 Swagger 외에 01 내부 오류, 04 HTTP 오류, 05 타임아웃, 10 매개변수, 12 서비스 없음/폐기, 20 키 없음·권한 거부, 22 일일 한도, 23 초당 한도, 29 차단 IP, 30 미등록 키, 31 사용기간 만료를 제공한다. 동일 숫자 20에도 여러 오류명이 있으므로 안전한 분류에는 코드와 정규화된 오류명을 함께 고려한다.

오류는 요청한 JSON 형식과 다르게 XML 또는 텍스트일 수 있다. `OpenAPI_ServiceResponse/cmmMsgHeader/returnReasonCode` 같은 GW XML envelope를 방어적으로 처리하되, 원문 HTML의 공식 Swagger에는 그 XML 구조가 정의되어 있지 않다는 점을 유지한다. HTTP 상태, 본문 결과 코드, 목록 수신 여부를 따로 판정하고 예외 원문·키 포함 URL을 출력하지 않는다.

## 출처별 확인 범위

- [데이터셋 및 내장 Swagger](https://www.data.go.kr/data/15101971/openapi.do): HTTPS 기본 주소, 8개 GET의 모든 입력/출력 타입·설명, 응답 코드, 무료, 개발 자동승인/운영 심의승인, 공개 개발 호출량 1,000, 갱신·이용 조건. 페이지의 최종 수정일은 2026-02-25이며 로컬 수집 시각과 구분한다.
- [공공데이터포털 GW 활용 가이드](https://www.data.go.kr/images/biz/swagger-guide/gw/gateway_swagger_guide.pdf): HTTP 상태와 제공기관 응답 코드의 별도 확인, serviceKey에 일반 인증키(Decoding)를 입력하는 방식.
- [공정위 공식 OpenAPI 안내](https://www.fairdata.go.kr/ext/data/openApiGuidance.do): 개인 인증키 발급 및 마이페이지 조회 안내. 실제 서비스별 승인·유효기간·한도는 해당 계정 화면을 따른다.

이 파일은 공식 스키마의 현행 스냅샷과 구현 시 해석을 분리한 참조 문서다. 실제 전체 기능 호출 성공, 콘텐츠 설치, 대본 생성 및 자산별 권리 검증을 완료했다는 기록은 아니다.
