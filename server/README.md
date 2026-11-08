# Petasos generation server — dev.10

v0.8 [API-01..09](../docs/Petasos_데이터_API_계약.md#6-petasos-서버-api)를 실행하는 Python 3.11+ ASGI 서버다. dev.10에서 계정 보관 설정 확인 후 실제 Sonnet/Bedrock 답변·요약 연결을 검증했다. 공개 배포는 수행하지 않았다. 합성 provider는 `tests/`에만 있으며 미설정 제품 기본값은 `UPSTREAM_UNAVAILABLE`이다. 로컬 승인 결과와 만료 시각은 [실제 연결 기록](../docs/development/2026-11-07-live-apis.md)을 따른다.

## 설치와 실행

```sh
python3 -m venv .local/server-venv
.local/server-venv/bin/pip install -r server/requirements.txt
python3 server/provision-local.py --directory .local/server
PYTHONPATH=server .local/server-venv/bin/python -m petasos_server \
  --config .local/server/config.json --env-file .env \
  --cert /absolute/path/server.pem --key /absolute/path/server.key
```

설정 생성기는 기존 디렉터리를 덮어쓰지 않는다. 새 디렉터리 0700, 모든 설정/키/초기 전달 토큰 0600을 사용한다. stdout에 자격 증명을 출력하지 않는다. `.local/server/device-provisioning.token`은 기기 암호화 설정으로 전달한 뒤 삭제할 초기 전달 파일이다. 인증 저장소 `tokens.json`에는 SHA-256 토큰 해시, 기기 ID, Unix 초 만료 시각, 철회 상태만 남긴다. 초기 토큰은 32바이트 난수이며 24시간 후 만료한다. 토큰 파일은 요청마다 다시 읽으므로 철회/만료가 재시작 없이 적용된다. 설정/키/토큰은 버전 관리에 넣지 않는다.

CLI는 기본 `127.0.0.1:8443`, 단일 프로세스, 직접 TLS 1.2 이상으로 실행한다. 인증서/키는 필수다. 프록시 헤더를 신뢰하지 않고 HTTP를 거부한다. API 내용 캐시가 메모리뿐이므로 여러 worker/process를 동시에 띄우지 않는다. 공개 배포는 인증서·토큰 발급/회수·프록시의 본문 로그 미사용 등 운영 설정과 별도 검증이 필요하다.

실제 키 입력은 [로컬 API 설정 안내](../docs/development/live-api-setup.md)를 따른다. `--env-file .env`는 소유자 전용 파일을 리터럴로 읽으며 shell 확장을 수행하지 않는다. 비어 있지 않은 프로세스 환경변수가 우선하고, 파일 값은 없는/빈 항목에만 적용한다. 설정 없이 provider를 활성화하거나 외부 생성 호출을 수행하지 않는다.

### 디버그 앱에서 실제 로컬 HTTPS 서버 연결

`dev-local-https.py`는 이미 검토·활성화한 `.local/server/config.json`과 `.env`를 사용하는 개발 전용 진입점이다. `prepare`는 `.local/server/dev-tls`(0700)에 CA·서버 키/인증서(0600)를 만들고 **공개 CA만** `app/src/debug/res/raw/dev_local_generation_ca.pem`에 복사한다. CA는 30일, localhost leaf는 7일이며 기존 유효 파일은 재사용한다. 부분 누락·만료·서로 다른 키/인증서는 차단한다. 교체가 필요한 경우 운영자가 기존 개발 인증서 디렉터리를 별도 보관하고 새로 준비한 뒤 debug APK를 다시 빌드한다. 실행 중 자동 갱신은 없다.

```sh
PYTHONPATH=server .local/server-venv/bin/python server/dev-local-https.py prepare
# 공개 CA가 포함되도록 debug 앱과 androidTest APK를 빌드한다.
PYTHONPATH=server .local/server-venv/bin/python server/dev-local-https.py serve --port 8443
```

서버는 `127.0.0.1`만 listen하고 foreground에서 실행한다. **Ctrl-C 또는 해당 프로세스에 SIGTERM**으로 중지한다. 인증서/현재 provider 검토 기한을 확인한 뒤 `.local/server/dev-https-ready.json`(0600)에 포트·origin·PID·공개 CA/전달 토큰 파일 경로만 쓴다. 종료 시 ready 파일을 지우며 개인키/토큰/원문은 출력하지 않는다. 준비 상태가 실제 생성 성공을 뜻하지는 않는다.

별도 터미널에서 다음을 실행하면 현재 Pleos 사용자에게 debug APK를 설치하고, private token을 `adb run-as`의 stdin으로 앱 cache에 전달한 뒤 **AndroidTest 전용** 코드가 제품 `DeviceGenerationConfigStore`에 Keystore 암호문으로 저장한다. 저장·재읽기 검증과 평문 삭제를 마친 뒤 `SummaryBackground.configurationChanged`를 호출해 기존 설정 오류로 차단한 요약의 재예약을 요청한다. 근거 차단을 임의 해제하지 않는다. token 내용은 argv·intent·APK·stdout에 넣지 않으며 cache 평문은 성공/실패 모두 삭제한다. 토큰 파일 경로를 바꾸려면 `generation-provision`에 `--device-token-file`을 지정한다.

```sh
python3 scripts/probe-pleos.py generation-provision \
  --development-server-ready .local/server/dev-https-ready.json --artifacts-dir artifacts/milestone-9
python3 scripts/probe-pleos.py generation-live \
  --development-server-ready .local/server/dev-https-ready.json --artifacts-dir artifacts/milestone-9
```

`generation-live`는 비개인 고정 시험 문장의 실제 답변·동일 요청 재전송·요약을 기본 `HttpsGenerationTransport`로 검사한다. 테스트별 원문은 로그/디스크에 저장하지 않는다. 외부 호출은 답변과 요약 각 1회이며 동일 ID 재전송은 서버 캐시 계약을 따른다. 120초는 전체 계측의 외부 제한이고 제품 answer/서버의 기존 15초/14초 기한은 변경하지 않는다. 마이크·TTS·실제 청취 완료/요약 DB 저장·답변 품질 검수와는 별도다.

dev.10의 최종 Pleos Android 실호출은 **PASS**, 전체 10,584 ms로 답변 검증·동일 요청 재전송 결과 일치·요약 검증을 완료했다. 실제 모델 경로를 사용했으며 원문/생성 본문은 기록하지 않았다. 최종 증거는 `artifacts/milestone-9/generation-live-final/generation-live.json`/`generation-live.txt`다. 최초 action 권한 거부 실패는 `artifacts/milestone-9/generation-live/`에 별도 보존한다. 이 결과는 마이크·TTS나 실제 완료 청취 기록을 의미하지 않는다.

프로비저닝 후 제품의 암호화 설정과 `adb reverse tcp:8443 tcp:8443`은 앱 테스트를 위해 유지한다. 같은 reverse가 있으면 재사용하고 다른 목적지가 있으면 덮어쓰지 않는다. 종료할 때 `adb -s emulator-5554 reverse --remove tcp:8443`으로 제거한다. 기기 설치 검증을 마친 초기 host 전달 토큰은 삭제할 수 있으며, 서버 인증 저장소에는 해시만 남는다. token 만료/철회 및 provider 검토 기한은 자동 연장하지 않는다.

추가 CA 신뢰는 **debug source set의 localhost 도메인에만** 선언했다. 다른 호스트는 시스템 CA를 유지하고 release에는 이 manifest/리소스가 포함되지 않는다. 시스템 CA 설치·trust-all·hostname verifier 교체·HTTP 허용·redirect 허용은 없다. [Android Network Security Configuration 공식 문서](https://developer.android.com/privacy-and-security/security-config).

## 경계와 처리

- `GET /v1/health`: 프로세스 준비 상태만 반환한다. 모델 연결 성공을 뜻하지 않는다.
- `POST /v1/generations`: strict JSON + canonical [schema](../spec/generation-v0.8.schema.json), 256 KiB 스트리밍 본문 상한, 중복 JSON key/미지원 필드/Android Long 범위/중복 ID 검증.
- `POST /v1/generations/{requestId}/cancel`: 소유 요청 204, 다른 기기/알 수 없는 요청 404. 취소 후 늦은 결과와 캐시 성공을 모두 억제한다. 상위 호출 과금 중단은 보장하지 않으며 활성 요청의 14초 제한 안에서 마무리한다.
- 기기+requestId 멱등 키, 실제 정규화 본문의 서버 HMAC으로 충돌 판정. 진행 중 같은 요청은 합류하고 완료 결과는 최대 60초 메모리에만 유지한다. 후속 요청이 없어도 타이머가 제거한다. 재시작은 캐시를 지운다.
- dev.10은 새 요청·동일 요청 합류/캐시 조회·provider 시작 직전·provider 완료 반영·응답 전달 직전에 현재 근거를 다시 검사한다. 권한 철회는 진행 중 응답과 캐시 성공을 모두 차단한다. 이미 시작한 외부 호출의 중단·과금 취소는 보장하지 않으며, 응답을 차단해도 진행 중 호출 수를 조기에 줄이지 않는다.
- 재시도 가능한 오류는 같은 본문·ID의 다음 요청에서 실제 provider를 다시 호출한다. `retryAfterMs`가 있으면 단조 시계 기준 대기 기한을 지키고, 아직 이른 요청에는 남은 시간을 반환한다. 재시도도 시작 횟수/동시 실행 제한을 적용하며 성공·취소 캐시는 재호출하지 않는다.
- 기기별 새 생성 60건/분, 진행 중 2건. 같은 요청의 합류/캐시 재사용은 새 생성으로 세지 않는다. 취소되어 아직 실행 중인 상위 호출도 동시 실행 한도에 포함한다.
- transport metadata, 문단 키, story/version ID를 서버가 결합한다. 생성 모델은 결과 본문만 만든다. 현재 answer 요청에는 실행 동작 권한이나 대상 후보 목록이 없으므로 모델용 action은 현재 앱이 허용한 `continue`/`unsupported`로 제한하고 `targetContentId`는 null만 허용한다. 모델용 schema와 서버 응답 재검증에 같은 제한을 적용한다. canonical v0.8 wire enum은 유지하며 앱의 동작 허가를 넓히지 않는다. resume은 후보의 content/paragraph 쌍을 그대로 검증한다.
- summary의 완료/부분 청취 키는 요청 집합과 정확히 같아야 한다. `verifiedFactRevisionIds`는 검토된 fact 분류만 허용하며 tradition을 사실로 승격하지 않는다. fact/tradition 문단은 허용된 revision·분류·source 연결을 검증한다. 텍스트가 근거를 정확히 표현하는지는 이 구조 검증만으로 증명되지 않으며, 실제 모델 품질/환각 검수는 별도다.
- 응답/오류는 `Cache-Control: no-store`. 서버는 원문, 토큰, 공급자 원문 오류를 기록하지 않는다. CLI는 Uvicorn access/error 및 HTTPX/httpcore 로그를 끈다. 메타데이터는 검증된 응답에 포함되며 외부 진단 집계 sink는 아직 없다. 원문/응답 디스크 캐시는 없다.

## 검토된 근거 저장소

`evidence.json`은 운영자가 검토한 자료만 제공한다. 클라이언트가 전달한 URL을 가져오는 코드가 없다. 각 source는 기기 허용 목록에 명시되어야 한다. 빈 저장소에서는 개인/연결 응답과 개인 대화 요약만 가능하며 근거 기반 story 요청은 `NO_EVIDENCE`다.

```json
{
  "sources": [{
    "sourceRevisionId": "reviewed-source-r1",
    "text": "검토한 자료의 본문",
    "allowedDeviceIds": ["registered-device-id"],
    "reviewed": true
  }],
  "facts": [{
    "factRevisionId": "reviewed-fact-r1",
    "factId": "stable-fact-id",
    "kind": "fact",
    "text": "검토한 사실 본문",
    "sourceRevisionIds": ["reviewed-source-r1"]
  }]
}
```

이 예시는 구조 설명이며 실제 승인 자료가 아니다. 실제 가거도 최소 텍스트는 별도 출처 검토·TX-05 설치를 완료했고 dev.8에서 등록 기기 범위의 evidence로 내보냈다. 서버는 요청에 지정된 source와 그 source로 온전히 뒷받침되는 fact만 모델에 제공하며 story의 제외 stable factId도 적용한다.

dev.10의 `FileEvidenceRegistry`는 설정된 `evidenceFile`을 각 검사 경계에서 다시 읽는다. 파일은 서버 실행 사용자 소유의 일반 파일이며 소유자 읽기 권한이 있고 그룹·다른 사용자 권한은 없어야 한다(보통 0600). 심볼릭 링크·8 MiB 초과·읽는 도중 교체/수정된 파일·중복 JSON 키·잘못된 형식·미검토 자료는 거부한다. 파일이 없거나 잘못되어도 마지막 승인본으로 되돌리지 않으며 근거 없는 요청을 포함해 `NO_EVIDENCE`로 차단한다. 시작 때 잘못된 파일은 서버 설정 오류다.

운영자는 별도 파일에 전체 승인 자료를 완성·검토하고 0600으로 저장한 뒤 같은 디렉터리에서 원자 교체한다. 기존 파일을 부분 수정하며 제공하지 않는다. 기기 권한 철회는 해당 source의 `allowedDeviceIds`에서 기기를 제거한다. source 자체를 제거할 때는 이를 참조하는 fact도 함께 제거하거나 새 revision으로 검토한다. `reviewed=false`는 부분 승인 상태가 아니라 전체 파일 거부다. 변경 뒤 재시작은 필요 없으며, 검사는 요청/응답 경계에서 수행한다. 백그라운드 파일 감시나 Android로의 철회 push는 제공하지 않는다.

서버는 현재 프로세스에서 관찰한 source 본문·fact 본문/분류/연결의 revision 지문만 메모리에 보존한다. 같은 revision을 제거했다 다시 넣어도 내용을 바꿀 수 없고, 본문 변경은 새 revision이어야 한다. 기기 허용 목록 변경과 새 revision 추가는 가능하다. 이 지문 이력은 재시작 후에도 남는 감사 저장소가 아니므로 운영자는 DATA-03의 원본 revision 불변성을 배포 이력에서 유지해야 한다. 클라이언트별 요청에 사용한 근거 snapshot이 바뀌면 `STALE_CONTEXT`, 자료/권한이 없어지거나 파일 검증에 실패하면 `NO_EVIDENCE`다. 한번 거부한 requestId는 남은 메모리 캐시 수명 동안 권한을 복구해도 다시 승인하지 않으며, 현재 근거로 새 요청이 필요하다. 관련 없는 revision 추가나 다른 기기의 허용 목록 변경은 기존 응답을 무효화하지 않는다.

## Gleo 확인과 OpenRouter 어댑터

2026-11-07 공식 문서 재확인: [Gleo 개념](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)은 앱 action 호출 구조이며 임의 프롬프트 생성 API 제공 근거가 아니다. [Pleos Connect 2.1.0 릴리스](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note)는 LLM SDK 제공 종료를 명시한다. 따라서 확인되지 않은 Gleo 자유 생성 API를 만들지 않고 별도 provider 경계를 구현했다. 향후 공식 자유 생성 수단이 제공되면 동일 경계로 비교할 수 있다.

OpenRouter는 `https://openrouter.ai/api/v1/chat/completions`에만 요청한다. 리다이렉트/환경 프록시/도구/웹 검색을 사용하지 않는다. `provider` 설정을 실제로 활성화하려면 아래 값을 운영자가 검증하고 제공해야 한다.

```json
{
  "type": "openrouter",
  "model": "<검증한 model ID>",
  "providerSlug": "<검증한 provider slug>",
  "acceptedProviderNames": ["<응답 metadata의 검증한 provider 이름>"],
  "privacyReviewedUntil": 0,
  "accountContentLoggingDisabled": false,
  "maxOutputTokens": 4096
}
```

`PETASOS_OPENROUTER_API_KEY` 환경 변수는 서버에만 주입한다. 위 예시는 활성화할 수 없는 기본값이다. 모델/제공자 선택, 계정의 입력·출력 로깅 미사용, 보관 조건 검토 유효기간이 채워져야 호출한다. 기한 만료/미설정/응답 제공자 불일치 시 실패한다. 기기 bearer token과 OpenRouter key는 서로 다르다.

항상 `response_format=json_schema`/`strict=true`, `require_parameters=true`, `data_collection=deny`, `zdr=true`, 단일 `only`/`order`, `allow_fallbacks=false`를 보낸다. 허용 경로가 없으면 조건을 완화하지 않는다. 실제 계정 설정과 endpoint 정책은 운영자가 현재 상태를 검증해야 하며 설정 boolean 자체가 원격 정책을 증명하지 않는다. 토큰 사용량이 없으면 null을 유지한다.

2026-11-07 재확인: `zdr=true`는 계정 정책에 따라 Safety Retention 경로도 허용할 수 있으므로 [Privacy](https://openrouter.ai/settings/privacy)의 **ZDR only**와 OpenRouter 입력/출력 이용 OFF, [Observability](https://openrouter.ai/workspaces/default/observability)의 Input & Output Logging OFF를 확인한다. `privacyReviewedUntil`은 이 계정 설정 및 선택한 endpoint 검토도 포함해야 한다. [현행 ZDR 정책](https://openrouter.ai/docs/guides/features/zdr). Key API 인증 성공만으로 이 설정을 검증했다고 표시하지 않는다.

공식 근거: [구조화 출력](https://openrouter.ai/docs/guides/features/structured-outputs), [provider routing/보관 조건](https://openrouter.ai/docs/guides/routing/provider-selection), [ZDR 설명](https://openrouter.ai/blog/insights/zero-data-retention/). 라이브 호출 없이 HTTPX mock transport로 전송 필드와 응답 거부를 검증했다.

선택적인 `acceptedModelIds`는 검토한 응답 모델의 정확한 alias 목록이다. 응답 모델이 요청 ID나 이 목록에 없으면 거부하며 다른 모델로 fallback하지 않는다. 현재 준비한 Sonnet/Bedrock 후보에는 `provider_schema.py`가 공급자에게 보내는 스키마 복사본만 변환한다. canonical v0.8 응답 후 검증과 제품 시간 제한은 유지한다. `probe-live-generation.py`의 실제 생성 시험은 계정 보관 설정 확인 후 수행하며 자세한 범위는 [실제 API 연결 기록](../docs/development/2026-11-07-live-apis.md)을 따른다.

dev.10 Android 실제 연결의 최초 `INVALID_RESPONSE`를 진단하면서 HTTP 200/schema 유효 응답의 `action=stop`이 현재 앱의 동작 허가 밖임을 재현했다. `contracts.model_schema(answer)`는 모델에게 `continue`/`unsupported`만 제시하고 서버도 같은 schema로 결과를 검사한다. promptVersion은 `petasos-v0.8-2`로 갱신했다. 원본 wire enum·Android의 허가 gate·provider 선택·14초 서버 기한은 변경하지 않았다. 코드 변경 후에는 서버를 재시작한다. 비밀값/본문 없는 진단은 `artifacts/milestone-9/live-generation-contract-diagnostic.json`에 보존한다.

## 공공데이터 조회

별도 도로 경로 공급은 [네이버 보조 경로 준비](../docs/development/naver-route-setup.md)를 따른다. `naver_routes.py`와 `scripts/naver-routes.py`는 아직 제품 endpoint에 연결하지 않은 메모리 전용 조회이며, Pleos의 현재 선택 경로를 대신하지 않는다. 네이버 키도 서버 비공개 `.env`에만 둔다.

`tourism_contract.py`는 보존한 공식 Swagger에서 오디 8개·KorService2 15개 operation과 입력·출력 계약을 읽는다. `tourism_api.py`는 해당 계약으로 검증한 한 번의 HTTPS GET, JSON/XML 성공·빈 결과·오류와 페이지 정보를 처리한다. `public_data.py`는 이 공통 클라이언트 위에서 후보를 정규화하며 아직 검토되지 않은 콘텐츠를 모델 근거로 승인하지 않는다. 배포 시 `docs/reference/public-data/*.openapi.json`도 프로젝트 상대 경로 그대로 포함해야 한다.

[지원 API 전체·공식 입력/출력·CLI 사용법](../docs/reference/public-data/README.md)을 따른다. `scripts/public-data-api.py`의 `list`/`describe`는 키와 네트워크 없이 작동하고, `call`은 `.env`의 키로 실제 조회한다. 원자료는 명시한 비공개 파일에만 저장한다. 실제 23개 API×JSON/XML 조회와 발견한 `rnum` 타입 차이는 [실행 기록](../docs/development/2026-11-07-live-apis.md)에 남겼다.

## 검토 콘텐츠 컴파일

`content_bundle.py`는 출처·권리·사실을 명시적으로 검토한 로컬 자료를 Android TX-05 입력으로 컴파일한다. 입력 검토 상태를 자동 승인하지 않는다. 안정된 UUID와 본문/대본 해시를 계산하고 앱과 같은 필수 문단·참조·허가·좌표 조건을 검사한다. `build_evidence_registry(bundle, allowed_device_ids)`는 명시적으로 생성 사용을 허용한 source와 그 출처로 온전히 뒷받침되는 fact만 기존 서버 근거 형식으로 변환한다. 실제 서버 설정을 자동 수정하거나 모델을 호출하지 않는다.

```sh
python3 scripts/build-content-bundle.py \
  --review .local/content/review/source-review.json \
  --output .local/content/bundles/gageodo-v1.json
```

기존 검토 bundle의 생성 허가 자료만 서버 근거로 내보내려면 다음을 사용한다. `--allow-device-id`는 반복할 수 있으며 인증 토큰이 아닌 등록 기기 ID다.

```sh
python3 scripts/build-content-bundle.py \
  --evidence-from-bundle .local/content/bundles/gageodo-v1.json \
  --allow-device-id <등록된-기기-ID> \
  --output .local/content/evidence/gageodo-v1.json
```

CLI는 서버 설정이나 provider를 자동 변경하지 않는다. 이번 로컬 개발에서는 생성한 실제 근거 파일을 `.local/server/config.json`의 evidenceFile에 명시적으로 연결하고 설정 로드·기기별 resolve를 확인했다. dev.8 당시 provider는 null이었으며 dev.10에서 별도 계정/endpoint 검토 후 단일 Sonnet/Bedrock 경로를 활성화했다. dev.10부터 같은 evidenceFile 경로의 자료/기기 허용 변경은 요청·응답 경계에서 반영하며 재시작이 필요 없다. evidenceFile 경로 자체나 provider 설정의 변경은 여전히 재시작이 필요하다. 최초 연결은 [dev.8 연결 기록](../docs/development/2026-11-07-content-runtime.md)을 따른다.

위 출력이 이미 있으면 덮어쓰지 않고 실패한다. 같은 검토 자료의 재빌드는 같은 ID·해시를 만들며 기존 묶음을 재사용한다. 검토 입력·출력은 `.local/content` 아래 소유자 전용 파일이어야 한다. [출처 검토](../docs/development/2026-11-07-content-source-review.md) 및 [앱 설치·실행 기록](../docs/development/2026-11-07-content-install.md)을 따른다.

## 검증

```sh
PYTHONPATH=server .local/server-venv/bin/python -m unittest discover -s server/tests -v
PYTHONPATH=server .local/server-venv/bin/python -m tests.tls_fixture \
  --ready-file /private/tmp/petasos-tls-ready.json --port 0
```

두 번째 명령은 loopback의 임의 포트에서 실제 HTTPS + 합성 provider를 제공한다. 일회용 토큰/CA/키를 생성하고 ready JSON에 `port`, `tokenFile`, `caFile` 경로만 쓴다. 토큰을 stdout/명령 인수로 넘기지 않는다. Android 시험은 임시 CA를 명시적으로 신뢰하고 `adb reverse`를 통해 연결한다. 제품 trust-all 인증서 검증 우회는 사용하지 않는다. 프로세스 종료 시 임시 파일을 지운다.

현재 서버 **167개** 자동 검사는 기존 135개에 근거 갱신·철회 23개, 개발 TLS 설정 6개, 현재 호스트의 answer action 허가 3개를 추가했다. 신규 검사는 새 요청·지연 응답·합류·캐시·응답 전달 직전의 철회, 형식/파일 오류의 이전 승인 재사용 차단, source/fact revision 불변성, 명시적 권한 복원 뒤 새 요청, 정상 멱등·취소·동시 실행 제한 유지를 확인한다. TLS 검사는 localhost 인증서·키 권한·공개 CA만 복사·부분 파일 거부·준비 정보/종료 정리를 확인한다. 모델용 action 제한은 canonical schema를 유지하면서 지원 동작만 생성·수신하는지 검사한다. 기존 135개는 생성 계약 36개·로컬 환경 파일 11개·공급자 스키마 호환 10개·생성 시험 도구 5개·후보 수집 18개·관광 계약 20개·JSON/XML 클라이언트 15개·콘텐츠 컴파일/근거 내보내기 20개다. 호스트 도구 **53개**는 기존 46개에 개발 서버/토큰 입력 검증과 reverse 재사용·덮어쓰기 거부 등 7개를 추가했다. 실행 로그는 `artifacts/milestone-9/server-tests.log`, `host-tests.log`에 있다. 이전 앱 설치 증거는 [dev.7 실행 기록](../docs/development/2026-11-07-content-install.md), 전체 공공데이터 조회는 [API 연결 기록](../docs/development/2026-11-07-live-apis.md)에 있다. 합성 생성 응답은 실제 모델 가용성/품질·STT·공개 운영의 검증이 아니다.

사용 라이브러리 공식 문서: [Uvicorn TLS/설정](https://uvicorn.dev/settings/), [HTTPX async](https://www.python-httpx.org/async/), [jsonschema Draft202012Validator](https://python-jsonschema.readthedocs.io/en/stable/validate/).

## dev.11 story/resume 연결

현재 모델 프롬프트는 `petasos-v0.8-3`이다. story의 모델용 schema는 fact/tradition과 필수 fact/source 연결만 받으며 한 대본의 stable fact 중복·불필요한 source를 거부한다. resume은 정확한 제공 후보 또는 wait이며 transitionText는 null-only다. 정식 v0.8 schema는 변경하지 않았다. 실제 Android 생성·설치·복귀 관측과 현재 localhost 서버 상태는 [dev.11 실행 기록](../docs/development/2026-11-07-story-resume-generation.md)을 따른다.
