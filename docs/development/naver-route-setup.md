# 네이버 보조 경로 조회 준비

확인: 2026-11-09 KST. 기존 Pleos NaviHelper는 실제 내비 상태의 기준이다. dev.26은 네이버 Directions 5의 **보조 예상 경로**를 서버 HTTPS와 Android 예측 편성에 연결했다. 동일한 목적지·옵션을 썼다는 이유로 현재 Pleos 선택 경로와 같다고 간주하지 않는다. 카카오 가입/키는 필요 없다.

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

서버 독립 모듈·CLI 검사에 이어 dev.26의 Android 연결을 구현했다. Pleos 공개 API에서 읽은 목적지·경유지는 별도 메모리 입력으로 유지하고 기존 기기 Bearer로 고정 `/v1/routes/auxiliary`를 호출한다. 네이버 키는 서버에만 있다. Android 호출은 최대 2개 worker·전체 15초로 제한하며 자동 재시도·원본 경로 저장을 하지 않는다. 경로 변경·입력 철회·위치 이탈·만료는 기존 예측을 폐기한다. [통합 구조](2026-11-09-auxiliary-route-integration.md).

지역 시간창은 **guide 전체 선분이 하나의 검토된 NGII 지역 안에 있는 구간**에서만 만든다. 경계를 넘거나 확정할 수 없는 구간은 unknown으로 남긴다. 실제 위치와 연결하는 앵커도 가능한 전체 경로 구간과 측정 시각·정확도를 검사하며 guide 내부 시간을 거리 비례로 나누지 않는다.

2026-11-09 공식 공개 예제 실호출 기록은 다음과 같다. 호출 시각의 교통·경로 계산에 따라 점·guide 개수가 달라질 수 있다.

| 검사 | 확인한 결과 | 증거 |
| --- | --- | --- |
| 개발 CLI → 네이버 | 4,067점·29 guide, 시간 연결·합계 일치, `timingUsable=true` | [초기 기록](../../artifacts/p0-completion/naver-official-example.json) |
| 등록 기기 인증 → localhost HTTPS endpoint → 네이버 | 4,203점·29 guide, `timingUsable=true` | [서버 경로 기록](../../artifacts/milestone-26/naver-https-official-example.json) |
| 최종 privacy APK의 `auxiliary-route` | 4,138점·27 guide, 실제 NGII/계층 투영 71ms, 지역 시간창 7개·unknown guide 18개. 공개 경로 위 **합성 Android 점·시계**의 앵커가 passage 2개를 생성하고 출처 만료·철회를 거부 | [기기 결과](../../artifacts/milestone-26/auxiliary-route/auxiliary-route-privacy.json), [개수·시간 기록](../../artifacts/milestone-26/auxiliary-route/auxiliary-route-privacy.txt) |

기기 probe는 기존 암호화 config로 공개 예제 요청을 한 번 보내고 폐기용 Room을 사용했다. 전체 1,202ms, 기하 조회 예산 초과 없음이었다. 사용자 위치·내비 입력·이동 이력을 읽거나 제품 여행·방문 카드·정책을 생성하지 않았다. 원본 경로 파일, 좌표 출력, 오디오·모델 호출도 없다. 7개 시간창과 18개 unknown은 서로 다른 집계 단위이며 합쳐 guide 수를 계산하지 않는다.

최종 Debug JVM **964개/75 suite**(실패·오류·생략 0), privacy 앱·검증 APK 빌드, Lint와 APK 정적 감사가 통과했다. [JVM 집계](../../artifacts/milestone-26/jvm-test-summary.json), [빌드](../../artifacts/milestone-26/build.log), [Lint](../../artifacts/milestone-26/lint.log), [정적 감사](../../artifacts/milestone-26/client-dex-audit.json). 정적 감사는 별도 SpeechService의 동작·개인정보 안전성 검증을 대신하지 않는다.

**실제 Pleos 선택 목적지와 실제 유효 위치를 함께 사용하는 기기 전체 흐름은 아직 미검증**이다. 이번 공개 예제의 합성 앵커를 실제 주행이나 활성 Pleos 경로 일치로 표시하지 않는다. 방문 카드에는 기기에서 받은 유효 위치와 별도 검토된 지역 정책이 필요하며 현재 승인된 지역·랜드마크 정책은 없다.
