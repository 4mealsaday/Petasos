# 네이버 보조 경로 조회 준비

확인: 2026-11-09 KST. 기존 Pleos NaviHelper는 실제 내비 상태의 기준이다. 네이버 Directions 5는 외부에서 계산한 **보조 예상 경로**를 공급하는 후보이며, 동일한 목적지·옵션을 썼다는 이유로 현재 Pleos 선택 경로와 같다고 간주하지 않는다. 카카오 가입/키는 필요 없다.

## 등록과 입력 위치

[공식 Application 안내](https://guide.ncloud-docs.com/docs/application-maps-app-vpc)에 따라 네이버 클라우드 콘솔의 VPC → Services → Application Services → Maps → Application에서 앱을 등록하고 **Directions 5**를 선택한다. 인증 정보의 Client ID·Client Secret을 프로젝트 루트의 비공개 `.env`에 입력한다. 계정 Access Key/Secret Key나 네이버 검색 API 키와 혼동하지 않는다.

```dotenv
PETASOS_NAVER_MAPS_CLIENT_ID=
PETASOS_NAVER_MAPS_CLIENT_SECRET=
```

값은 채팅·소스·APK에 넣지 않는다. 파일은 기존처럼 소유자만 읽는 0600을 유지한다. Directions 5만 사용하는 단계에 Dynamic Map의 도메인/Android 패키지 등록을 임의로 추가하지 않는다. API 사용 설정과 계정 이용 조건은 콘솔에서 확인한다.

[현행 Maps 공통 계약](https://api.ncloud-docs.com/docs/application-maps-overview)은 `maps.apigw.ntruss.com`과 `x-ncp-apigw-api-key-id`/`x-ncp-apigw-api-key`를 사용한다. 구 AI·NAVER API 문서의 `naveropenapi.apigw.ntruss.com` 호스트로 자동 전환하지 않는다. Directions 5를 선택하지 않으면 문서상 429가 발생할 수 있다.

## 실행

아래 describe는 파일·키·네트워크를 사용하지 않는다.

```sh
.local/server-venv/bin/python scripts/naver-routes.py describe
```

키 준비 후 첫 인증·응답 검사는 공식 문서의 공개 예제 좌표를 사용한다. 사용자의 현재 위치나 저장된 이동 기록을 읽지 않으며 한 번만 호출한다.

```sh
.local/server-venv/bin/python scripts/naver-routes.py check-official-example
```

다른 경로는 소유자만 읽는 JSON 파일을 명시한다. 예시는 입력 형식이며 시연 경로의 승인 좌표가 아니다. 좌표는 경도·위도 순서의 EPSG:4326이다.

```json
{"origin":[127.1058342,37.359708],"destination":[129.075986,35.179470],"option":"traoptimal"}
```

```sh
.local/server-venv/bin/python scripts/naver-routes.py check --request .local/content/route-request.json
```

출력은 좌표/경로 이름/키/원문을 제외한 검증 메타데이터다. 응답 geometry와 요청 내용은 파일 캐시나 로그로 내보내지 않는다. 응답 최대 8MiB, 10초 timeout, 고정 HTTPS 호스트이며 리다이렉트·환경 프록시·자동 재시도를 사용하지 않는다.

## 해석과 현재 연결 상태

[Directions 5](https://api.ncloud-docs.com/docs/application-maps-directions5)의 `path` 전체 좌표와 `guide.pointIndex`/`guide.duration`(밀리초)을 대응한다. `section`은 일부 주요 도로이므로 전체 시간을 채우는 데 사용하지 않는다. 마지막 구간 누락, 이동 구간 0ms, 합계 불일치를 원값 그대로 표시하며 총 ETA를 임의로 나누지 않는다. `timingUsable=true`도 예상시간의 정확성이나 실제 Pleos 경로 일치의 증거는 아니다.

서버 독립 모듈과 개발용 CLI의 합성 파서/전송 테스트 32개, CLI 테스트 8개를 통과했다. 2026-11-09 사용자가 입력한 인증 정보로 공식 공개 예제 좌표를 실제 호출하여 PASS했다. 좌표 4,067개·시간 구간 29개, 전체 경로의 시간 연결과 합계 일치, `timingUsable=true`를 확인했다. 사용자 위치를 읽거나 응답 좌표열을 저장하지 않았다([검증 메타데이터](../../artifacts/p0-completion/naver-official-example.json)).

Android 자동 편성 연결은 아직 하지 않았다. 기기에서 받은 유효 위치만 지역 방문 카드의 근거가 될 수 있다. 보조 경로가 현재 위치/내비 상태와 어긋나면 예측을 철회하는 연결과 지역별 통과 시간 검수가 다음 구현이다.
