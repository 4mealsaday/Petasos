# 실제 API 키 입력과 연결

기준: 2026-11-07. 프로젝트 루트의 `.env`에 사용자가 키를 입력하고, 서버·연결 검사 도구가 직접 읽는다. `.env.example`은 빈 양식이다. 키 입력·인증·관광 API 조회가 완료됐고, dev.10에서 계정 보관 설정을 확인해 실제 Sonnet/Bedrock 답변·요약 생성도 통과했다. [실제 인증·데이터 연결 결과](./2026-11-07-live-apis.md)를 함께 읽는다.

## 지금 입력할 값

입력 파일: `~/Documents/Projects/HD_Pleos/.env`

```dotenv
PETASOS_OPENROUTER_API_KEY=여기에_OpenRouter_API_키
PETASOS_DATA_GO_KR_SERVICE_KEY=여기에_공공데이터포털_일반_인증키

# 두 서비스에 서로 다른 인증키가 발급된 경우에만 입력한다.
PETASOS_ODII_SERVICE_KEY=
PETASOS_TOURAPI_SERVICE_KEY=
```

`=` 뒤에 실제 값만 한 줄로 붙여 넣는다. 같은 줄에 설명을 덧붙이지 않는다. 선택 항목은 공통 키를 덮어쓰므로 공통 키를 쓰는 경우 비워 둔다. 이 파일은 Git 제외 대상이며 소유자만 읽고 쓸 수 있는 권한 `600`으로 생성했다. 편집기가 권한을 바꿨다면 프로젝트 루트에서 `chmod 600 .env`로 복구한다. 키를 채팅·Android 리소스·`local.properties`·문서에 복사하지 않는다.

Python 로더는 값을 그대로 읽고 쉘 명령·변수 확장을 실행하지 않는다. `source .env`는 필요 없다. 같은 이름의 **비어 있지 않은 프로세스 환경변수**가 있으면 그것이 `.env`보다 우선한다. 없는/빈 환경변수만 파일 값으로 채운다. 현재는 위 네 이름만 허용하므로 다른 설정은 이 파일에 임의로 추가하지 않는다.

### OpenRouter

1. [API Keys](https://openrouter.ai/keys)에서 Petasos용 **일반 생성 API 키**를 발급한다. 관리용 키는 사용하지 않는다. 원하는 사용 한도를 설정한다.
2. [Credits](https://openrouter.ai/settings/credits)에서 유료 모델을 사용할 수 있는 잔액을 확인한다. 코드는 자동 충전이나 결제를 수행하지 않는다.
3. 발급한 키를 `PETASOS_OPENROUTER_API_KEY`에 입력한다.
4. 실제 모델에 대화를 보내기 전에 다음 설정을 확인한다. [Observability](https://openrouter.ai/workspaces/default/observability)의 **Input & Output Logging은 OFF**로 둔다. [Privacy](https://openrouter.ai/settings/privacy)의 무료·유료 모델 입력/출력 학습 이용과 공개 게시를 OFF로 두고, **Allow 1% workspace data** 할인을 OFF로 둔다. 사용할 모델 그룹의 **Zero Data Retention**을 활성화하고, 선택 가능한 그룹은 **ZDR only**를 선택한다. 현재 UI는 모델 그룹별 정책과 작업공간의 관찰 로그 설정을 따로 제공한다.

현재 공식 문서에서 요청의 `zdr=true`는 계정 설정에 따라 Safety Retention endpoint도 허용할 수 있다. Petasos의 기존 무보관 정책을 지키려면 Safety Retention을 허용하는 선택과 ZDR only를 구분해야 한다. 계정 설정은 API 키 인증 성공만으로 확인할 수 없다. [공식 데이터 정책](https://openrouter.ai/docs/guides/privacy/data-collection), [ZDR 설명](https://openrouter.ai/docs/guides/features/zdr), [provider routing](https://openrouter.ai/docs/guides/routing/provider-selection#zero-data-retention-enforcement).

2026-11-07 인증된 계정 UI에서 default 작업공간의 입력/출력 로깅 OFF, 무료·유료 학습/공개 게시 OFF, 1% 데이터 제공 할인 OFF를 확인했다. 사용자 승인에 따라 Anthropic·OpenAI·Google·SpaceXAI·기타 모델 5개 그룹의 ZDR을 활성화하고 Google·기타 모델의 **ZDR only** 저장 완료를 확인했다. 공개 API를 다시 조회해 `anthropic/claude-sonnet-4.6` + `amazon-bedrock/global`의 구조화 출력 지원과 ZDR 목록의 `safety_retention=false`를 확인한 뒤 해당 단일 경로만 활성화했다. 계정 설정 변경과 개별 endpoint 선정은 구분하며 실제 생성 경로는 Sonnet/Bedrock으로 유지한다. 실제 답변·요약·캐시 검사는 통과했으며 최종 모델 품질 평가는 별개다. [Sonnet endpoint 목록](https://openrouter.ai/api/v1/models/anthropic/claude-sonnet-4.6/endpoints), [ZDR endpoint 목록](https://openrouter.ai/api/v1/endpoints/zdr).

현재 로컬 `config.json`의 `privacyReviewedUntil`은 **2026-11-08 12:47:34 UTC(한국 시각 21:47:34)**까지다. 만료 뒤에는 자동 연장하거나 다른 경로로 우회하지 않고 요청을 차단한다. 운영자는 계정·선택 endpoint의 현재 보관 조건을 다시 검토한 뒤 이 값을 갱신해야 한다. 검토/실행 메타데이터는 `artifacts/milestone-9/provider-review.json`, `live-generation.json`에 있으며 키·입력·생성 본문은 포함하지 않는다.

### 공공데이터포털: 오디·국문 관광정보

두 API는 이미 활용 신청했다는 사용자 확인이 있으므로 다시 신청하지 않는다. [공공데이터포털](https://www.data.go.kr/) 로그인 후 **마이페이지 → 데이터 활용/Open API → 승인된 서비스 → 서비스정보**에서 인증키와 승인 상태를 확인한다. 두 항목 모두 호출할 수 있어야 한다.

| 승인된 서비스 | 용도 | 현재 연결 검사 |
| --- | --- | --- |
| [한국관광공사 오디](https://www.data.go.kr/data/15101971/openapi.do) | 해설 콘텐츠·위치·음성 메타데이터 | `Odii/themeBasedList`, 한국어 1건 |
| [한국관광공사 국문 관광정보](https://www.data.go.kr/data/15101578/openapi.do) | 장소·좌표·설명 | `KorService2/areaBasedList2`, 1건 |

**일반 인증키(Decoding)**를 `PETASOS_DATA_GO_KR_SERVICE_KEY`에 넣는다. 화면에 인코딩 구분 없이 ‘일반 인증키’만 보이면 그 원문을 넣는다. 코드가 URL 인코딩을 수행하며 이미 인코딩된 포털 키도 한 번 정규화한다. 개인 계정의 공통 인증키를 우선 쓰되 서비스별로 다른 키가 있으면 선택 항목을 채운다. [포털 공식 인증키 가이드 7쪽](https://www.data.go.kr/images/biz/swagger-guide/gw/gateway_swagger_guide.pdf), [정부 인증키 안내](https://www.fairdata.go.kr/ext/data/openApiGuidance.do).

실제 수집에서는 현행 KorService2의 `lDongRegnCd`/`lDongSignguCd`/`lclsSystm1~3`를 기준으로 매핑한다. 공식 명세에서 미사용·삭제 예정인 예전 `areaCode`/`sigunguCode`/`cat1~3`를 새 필터로 채택하지 않는다. 현재 연결 검사는 필터 없는 1행 요청으로 키와 응답 구조만 확인한다. 콘텐츠 이용 조건·사진별 공공누리·음성/대본 재사용 검토와 TX-05 설치는 별도다. [공식 국문 관광정보 명세](https://www.data.go.kr/data/15101578/openapi.do).

## 저장 후 실행할 명령

프로젝트 루트에서 실행한다. 이 검사에는 Python 표준 라이브러리만 필요하다.

```sh
# 값의 유무만 검사. 네트워크 요청 없음.
python3 scripts/check-live-apis.py

# 실제 OpenRouter 인증 GET + 오디 1건 + 국문 관광정보 1건 요청.
python3 scripts/check-live-apis.py --check

# 한 서비스만 확인할 때
python3 scripts/check-live-apis.py --check --service openrouter
python3 scripts/check-live-apis.py --check --service odii
python3 scripts/check-live-apis.py --check --service tourapi
```

결과에는 서비스명·설정 변수명·HTTP 상태·정규화된 결과 코드·수신 개수만 출력한다. 키·요청 전체 URL·사용자/조직 ID·대본·공급자 원본 오류는 출력하지 않는다. HTTPS 인증서 검증을 사용하고 리다이렉트·환경 프록시를 따라가지 않는다.

- `MISSING`: 해당 키가 아직 없음.
- `PASS`: OpenRouter는 `GET /api/v1/key` 인증 성공, 공공데이터는 `resultCode=00` 또는 현행 실제 응답의 `0000`과 데이터 수신 성공. OpenRouter PASS가 계정 잔액·실제 모델 호출·개인정보 설정 검증을 의미하지 않는다. [공식 Key API](https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key).
- `GENERATION_KEY_REQUIRED`: OpenRouter 관리용 키 대신 일반 생성 API 키 필요.
- `HTTP_ERROR` + 401/403: 키 또는 권한 확인.
- `API_ERROR` + 20/30/31: 공공데이터 접근 권한/등록/유효기간 확인. 22/23은 호출 한도, 10은 요청 파라미터, 12는 서비스 주소를 확인한다.
- `NO_DATA`/`API_ERROR` + 03: 이번 요청에서 데이터가 없어 1건 수신 검증 미완료.
- `NETWORK_ERROR`: 접속·TLS·timeout 문제. 원본 예외에 키가 포함될 수 있어 상세 문자열은 출력하지 않는다.

위 `check-live-apis.py` 검사에는 생성 POST가 없으므로 모델을 호출하지 않는다. 별도 `server/probe-live-generation.py`는 승인된 provider로 비개인 고정 시험 문장의 실제 생성 POST를 수행한다. dev.10에서는 답변·캐시·요약 3개 검사를 통과했다. 실제 관광 사실의 답변 품질·Android 실대화 검수는 이 연결 검사와 별도다.

서버를 실행할 때는 `--env-file .env`를 전달한다. 모델/승인된 provider·검토 유효기간·인증서·기기 토큰은 별도 서버 설정이며 [서버 실행 안내](../../server/README.md)를 따른다. `.env`에 키를 넣는 것만으로 provider나 공개 배포가 자동 활성화되지는 않는다. dev.10의 provider 활성화는 위 계정·endpoint 검토 후 명시적으로 수행했다. 공공데이터 키는 실제 연결 검사·전체 23개 API 클라이언트·후보 수집기에 사용한다. [지원 API 전체와 호출 예제](../reference/public-data/README.md)를 따른다. 검토된 가거도 최소 콘텐츠의 TX-05 설치는 dev.7에서 완료했다.

디버그 앱과 실제 provider 사이에는 `server/dev-local-https.py`의 Mac loopback HTTPS + adb reverse를 사용한다. 개발 CA의 공개 인증서만 debug APK의 localhost trust에 포함하고, AndroidTest 프로비저닝으로 별도 기기 bearer token을 Keystore에 암호화한다. OpenRouter 키는 서버에만 남는다. 준비·실행·종료·`generation-provision`/`generation-live` 명령은 [개발 HTTPS 연결 안내](../../server/README.md#디버그-앱에서-실제-로컬-https-서버-연결)를 따른다. 공개 서버 배포나 시스템 CA 설치는 필요하지 않다.

dev.10 최초 Android 실제 생성 검사에서 canonical 응답 형식에는 맞지만 현재 앱이 허가하지 않은 action 제안을 발견했다. 서버의 모델용 answer schema/prompt를 `continue`/`unsupported`, 대상 null로 제한했으며 클라이언트의 동작 허가는 유지했다. promptVersion은 `petasos-v0.8-2`이고 이 코드 변경을 적용하려면 개발 서버를 재시작한다. TLS 설정이나 API 키를 다시 발급할 문제는 아니며 [진단·검증 기록](./2026-11-07-live-apis.md#dev10-android-https-연결과-동작-권한-보정)을 따른다.

서버 재시작 뒤 Pleos에서 **실제 Android HTTPS 답변·동일 요청 재전송 결과 일치·요약 검증이 모두 PASS**했다(전체 10,584 ms). 증거는 `artifacts/milestone-9/generation-live-final/`이고 최초 실패 기록도 보존했다. 이 검사는 비개인 고정 입력을 사용하며 마이크·TTS·사용자의 완료 청취나 요약 DB 저장을 포함하지 않는다.

## 지금 추가 등록이 필요하지 않은 항목

Pleos 프로젝트는 이미 생성했고 현재 ON_DEVICE 음성·위치 검증은 추가 서버 키 없이 동작했다. 국가유산·한국민족문화대백과는 자료 보강 단계에서, Naver Maps는 지도 연결 단계에서 필요한 등록/키를 확인한다. Naver 검색은 P1이며 그 결과를 생성 입력으로 사용하지 않는 결정은 유지한다. Virtual Console과 실제 발화도 계속 보류한다.

설정 도구를 준비한 최초 검증은 서버 47개·호스트 26개였다. 이후 사용자가 입력한 키로 세 API의 실제 인증/데이터 조회를 통과했다. 최초 설정 도구 검사는 Android 코드를 바꾸지 않았으며, dev.10의 Android 실제 HTTPS 검증은 별도 후속 증거다. 코드와 검사 수·범위는 [실제 연결 기록](./2026-11-07-live-apis.md)을 기준으로 한다.
