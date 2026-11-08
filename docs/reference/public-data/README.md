# 공공데이터 API 참조와 실행 방법

Petasos에 등록한 한국관광공사 **오디 8개 + 국문 관광정보 KorService2 15개**, 총 **23개 GET 작업**의 공식 입출력 계약을 보존한다. 두 서비스의 현행 공개 Swagger를 기준으로 입력 검증, JSON/XML 응답 해석, 조회 CLI를 구현했다. 특정 작업의 실제 서버 응답까지 일치하는지는 실행 기록에서 별도로 확인한다.

## 공식 계약

| 서비스 | 전체 입력·출력 문서 | 원문 값 보존 JSON | 출처·수집시각·해시 |
|---|---|---|---|
| 오디 `Odii` · 8개 | [odii-contract.md](odii-contract.md) | [odii.openapi.json](odii.openapi.json) | [odii-source-metadata.json](odii-source-metadata.json) |
| 국문 관광정보 `KorService2` · 15개 | [tourapi-contract.md](tourapi-contract.md) | [tourapi.openapi.json](tourapi.openapi.json) | [tourapi-source-metadata.json](tourapi-source-metadata.json) |

공식 출처는 [오디 데이터셋 15101971](https://www.data.go.kr/data/15101971/openapi.do)과 [국문 관광정보 데이터셋 15101578](https://www.data.go.kr/data/15101578/openapi.do)다. HTML에 내장된 `const swaggerJson`을 실행 없이 JSON으로 읽고 들여쓰기만 적용했다. 원문 설명, 타입, 비표준 부가 필드와 누락을 임의로 고치지 않았다.

| 저장 파일 | 수집 시각(UTC) | 저장 파일 SHA-256 |
|---|---|---|
| `odii.openapi.json` | `2026-11-07T05:53:21Z` | `a12b1b6db38e7f3aca080d3f076b9108ece502879a2a93a7ae41e6cefa6fb652` |
| `tourapi.openapi.json` | `2026-11-07T05:52:19+00:00` | `4902633965e480a5ed1beda9891068a0ef0a7b69d4e6c97c85ca2ca8c3d150a2` |

원래 HTML 또는 추출한 Swagger 문자열의 해시는 각 metadata 파일에 따로 기록했다. 저장 파일 해시와 원문 문자열 해시는 공백 차이 때문에 다를 수 있다.

## 키 입력 위치

프로젝트 루트의 `.env`를 사용한다. 키를 명령 인자, 검색 파라미터, 채팅, 로그에 넣지 않는다. `.env`는 본인 소유의 일반 파일이며 권한 `0600`이어야 한다. 기존 설정 안내는 [실제 API 설정 안내](../../development/live-api-setup.md)를 따른다.

| 환경 변수 | 용도 |
|---|---|
| `PETASOS_DATA_GO_KR_SERVICE_KEY` | 두 공공데이터 서비스의 공통 **일반 인증키(Decoding)** |
| `PETASOS_ODII_SERVICE_KEY` | 오디 키가 다를 때만 지정하는 서비스별 재정의 |
| `PETASOS_TOURAPI_SERVICE_KEY` | 국문 관광정보 키가 다를 때만 지정하는 서비스별 재정의 |
| `PETASOS_OPENROUTER_API_KEY` | 생성 모델용; 아래 공공데이터 CLI는 사용하지 않음 |

값이 있는 서비스별 키가 공통 키보다 우선한다. 같은 환경 변수의 값이 프로세스 환경에 있으면 `.env` 값보다 우선한다. 키는 query serializer가 한 번만 인코딩하며, URL 인코딩된 포털 키도 한 번 해제 후 처리한다. 전체 인증 요청 URL은 출력하지 않는다.

## CLI

명령은 프로젝트 루트에서 실행한다. 목록과 명세 조회는 `.env`를 읽지 않고 네트워크도 사용하지 않는다.

```sh
python3 scripts/public-data-api.py list
python3 scripts/public-data-api.py list --service tourapi
python3 scripts/public-data-api.py describe --service odii --operation storySearchList
python3 scripts/public-data-api.py describe --service tourapi --operation searchKeyword2
```

`describe`는 해당 작업의 정확한 필수·선택 query, 공식 설명과 전체 item schema를 출력한다. 목록의 `deprecated`는 공식 `deprecated` 키뿐 아니라 “미사용 기능”이라는 원문 설명도 반영한다.

다음 명령은 각각 **실제 GET 1회**를 수행한다. 예시는 공개 관광지명으로 1페이지 1건만 요청한다.

```sh
python3 scripts/public-data-api.py call \
  --service odii --operation storySearchList \
  --params '{"keyword":"경복궁","numOfRows":1,"pageNo":1}' \
  --format json

python3 scripts/public-data-api.py call \
  --service tourapi --operation searchKeyword2 \
  --params '{"keyword":"경복궁","numOfRows":1,"pageNo":1}' \
  --format xml
```

`--params`에는 공식 입력만 넣는다. `serviceKey`는 허용하지 않으며 `.env` 또는 프로세스 환경에서 주입한다. ID와 코드 값은 문자열로 전달한다. 예를 들어 `contentId`, `contentTypeId`, `lDongRegnCd`를 숫자로 바꾸면 앞자리 0 등이 손실될 수 있다. 현행 `searchKeyword2`에는 `contentTypeId` 입력이 없으므로 다른 작업의 입력을 가져와 붙이지 않는다.

stdout은 서비스·작업명, 성공 코드, 실제/요청 형식, 건수, 페이지 정보, 스키마 차이 개수만 보여준다. 본문, 제목, 원본 오류 메시지, 키, 인증 URL은 출력하지 않는다. 성공해도 항목 필드 타입이 계약과 다르면 `SCHEMA_DRIFT`, 요청한 형식과 다르면 `FORMAT_DRIFT`로 표시하고 종료 코드 1을 반환한다. 두 차이가 동시에 있으면 `FORMAT_DRIFT`와 `schemaIssueCount`를 함께 확인한다. 정상 결과가 비어 있으면 `NO_DATA`다.

검토용 응답을 남기려면 새 출력 파일을 명시한다.

```sh
python3 scripts/public-data-api.py call \
  --service tourapi --operation searchKeyword2 \
  --params '{"keyword":"경복궁","numOfRows":1,"pageNo":1}' \
  --format json \
  --output .local/content/api/gyeongbokgung-tourapi.json
```

출력은 `.local/content/api` 아래에만 허용한다. 디렉터리는 `0700`, 파일은 `0600`이며 기존 파일·심볼릭 링크는 덮어쓰지 않는다. JSON/XML 응답 모두 반환 필드를 보존한 **정규화 JSON**으로 저장하므로 원래 XML 바이트의 복제본은 아니다. 저장 상태는 `review_status=pending`, `allowGeneration/allowDisplay/allowDistribution=false`다. 스키마 차이가 있어도 검토용 보관은 가능하지만 성공으로 보고하지 않는다.

## 서버 Python에서 사용

구현은 [관광 계약 모듈](../../../server/petasos_server/tourism_contract.py)과 [관광 API 클라이언트](../../../server/petasos_server/tourism_api.py)에 있다. 아래 예시는 프로젝트 루트에서 `PYTHONPATH=server`로 실행하는 서버 코드다.

```python
from pathlib import Path

from petasos_server.local_env import load_environment
from petasos_server.tourism_api import TourismClient
from petasos_server.tourism_contract import operation, operations

# 계약 조회에는 키나 네트워크가 필요 없다.
all_operations = operations()  # 23개
contract = operation("tourapi", "searchKeyword2")

environment = load_environment(Path(".env"))
key = (environment.get("PETASOS_TOURAPI_SERVICE_KEY")
       or environment.get("PETASOS_DATA_GO_KR_SERVICE_KEY"))
client = TourismClient("tourapi", key)
result = client.request(
    "searchKeyword2",
    {"keyword": "경복궁", "numOfRows": 1, "pageNo": 1},
    response_format="json",
)
print(result.safe_metadata())  # 본문·resultMsg·키를 출력하지 않음
if result.response_format != "json":
    raise SystemExit("FORMAT_DRIFT")
if result.schema_issues:
    raise SystemExit("SCHEMA_DRIFT")
# result.items는 반환 필드를 보존한 dict들의 tuple이다.
# 실제 사용은 별도의 콘텐츠 검토·권리 확인 후 진행한다.
```

`TourismApiError`는 고정 오류 코드, 검증된 숫자형 결과 코드와 HTTP 상태만 제공한다. 응답의 `result_message`, `items`, `to_document()`는 원자료이므로 일반 로그에 쓰지 않는다. 요청 객체에는 인증 URL이 있으므로 `prepare_request()` 결과도 로그에 남기지 않는다.

## 기본값과 공식 계약의 차이

- `MobileOS=ETC`, `MobileApp=Petasos`는 Petasos의 앱 기본값이다. `TourismClient`는 오디 `langCode=ko`도 기본 지정한다. 계약 검증 함수 `prepare_parameters` 자체가 오디 언어를 서버 기본값으로 가정하는 것은 아니다.
- 클라이언트와 CLI의 기본 요청 형식은 JSON이다. 공식 설명은 `_type` 생략 시 **XML 기본**이라고 적고 있다. 따라서 Petasos는 형식을 명시해 요청하며 실제 수신 형식도 기록한다.
- 공식 입력 대부분에 formal `default`/`enum`/범위 정보가 없다. 설명에 명시된 허용 코드, 날짜 형식, 조건부 필수 관계를 검증한다. UI 시험 입력의 `paramtrBassValue`를 서버 기본값으로 자동 적용하지 않는다.
- `numOfRows`와 `pageNo`는 호출자가 지정한다. CLI 예시와 전체 검증기는 명시적으로 1건·1페이지를 요청하며 자동 페이지 순회나 자동 재시도는 없다.
- 기본 자원 한도는 **응답 2 MiB, 최대 1,000개 항목, 네트워크 timeout 10초**다. 이는 Petasos의 로컬 한도이며 제공기관의 공식 최대 건수·요금·일일 할당량을 뜻하지 않는다. 요청한 `numOfRows`와 수신 항목 수 모두 한도를 확인한다.
- HTTPS 인증서 검증을 사용하고 HTTP 전환, 리다이렉트, 환경 프록시는 허용하지 않는다. API가 반환한 외부 URL을 추가로 방문하지 않는다.

공식 응답 schema는 `items.item`을 단일 object로 적고 있어 실제 JSON 배열·단일 객체·빈 결과와 차이가 있다. 소비 계층에서 이를 tuple로 정규화하고 XML의 반복 `<item>`도 처리한다. pagination은 정수로 해석하지만 **항목의 ID·문자열·타입 불일치는 임의로 강제 변환하지 않는다**. 원래 값은 유지하고 알려진 필드의 타입 차이를 `schema_issues`로, 미정의 필드 수를 `extra_field_count`로 노출한다. 알 수 없는 필드 이름과 원문 값은 안전 메타데이터에 포함하지 않는다.

현행 실응답의 성공 코드는 `0000`이며 이전 정상 표기 `00`도 허용한다. HTTP 200만으로 성공이라고 판단하지 않는다. `resultCode`의 다른 값, 인증 오류, 잘못된 payload는 오류로 다룬다. 응답 필드의 `required`, nullable, 반복 개수가 공식 문서에 없다는 사실도 보존하며 실제 응답의 누락을 임의 추측으로 채우지 않는다.

`areaCode2`, `categoryCode2`와 옛 지역·분류 입력은 현행 명세에 남아 있어 계약상 호출을 지원하지만 미사용·삭제 예정 설명을 표시한다. 새 구현은 `ldongCode2`, `lclsSystmCode2`와 법정동·신분류 입력을 우선한다. 실제 서비스가 구형 작업을 거부하는 경우도 검증 결과에 그대로 남긴다.

## 전체 실제 연동 검증

다음 명령은 인증키를 사용해 23개 작업의 JSON/XML 조합을 순차 조회한다. 최대 **47회 GET**으로, 46개 조합과 실제 상세 조회용 관광지 ID를 얻는 초기 목록 조회 1회다. 각 요청은 1페이지 1건이며 같은 항목의 모든 선택 입력 조합이나 모든 콘텐츠 타입까지 검증하는 것은 아니다.

```sh
python3 scripts/probe-public-data.py
python3 scripts/probe-public-data.py --check \
  --report artifacts/live-api/full-contract-probe.json
```

첫 명령은 실행 계획만 출력하고 네트워크를 사용하지 않는다. `--check`가 있어야 실제 호출한다. 상세 ID를 못 얻으면 해당 작업을 건너뛰고, 할당량 초과가 확인되면 그 서비스의 후속 검증을 중단한다. 보고서는 원문·키·인증 URL 없이 상태와 메타데이터만 저장하며 기존 파일을 덮어쓰지 않는다.

실제 결과는 [전체 계약 검증 보고서](../../../artifacts/live-api/full-contract-probe.json)와 [실제 API 개발 기록](../../development/2026-11-07-live-apis.md)을 확인한다. 이 참조 문서 자체가 전체 실서비스 성공을 보장하는 것은 아니다.

## 콘텐츠·권리 경계

API 조회와 스키마 검증은 콘텐츠의 사실 검증이나 배포 승인이 아니다. 반환된 데이터는 출처·수집시각·원본 ID·수정시각·권리 정보를 가진 검토 후보로 취급한다. `cpyrhtDivCd`와 이미지별 권리 필드도 보존하며 실제 LLM 입력·화면 표시·배포는 검토 상태와 별도로 연결한다.

이미지·오디오 필드는 **URL 문자열만** 조회·보관한다. 이 클라이언트는 미디어를 다운로드·재생·변환·재배포하지 않는다. 공식 카탈로그의 일반 이용 조건과 개별 사진의 공공누리 1·3유형은 각 계약 문서의 권리 설명 및 [공식 이용 정책](https://www.data.go.kr/ugs/selectPortalPolicyView.do)을 함께 확인한다.
