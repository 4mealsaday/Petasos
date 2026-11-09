# 네이버 보조 경로와 Android 지도 설정

확인: 2026-11-09 KST. 기존 Pleos NaviHelper는 실제 내비 상태의 기준이다. dev.26은 네이버 Directions 5의 **보조 예상 경로**를 서버 HTTPS와 Android 예측 편성에 연결했다. 동일한 목적지·옵션을 썼다는 이유로 현재 Pleos 선택 경로와 같다고 간주하지 않는다. 카카오 가입/키는 필요 없다.

## 등록과 입력 위치

[공식 Application 안내](https://guide.ncloud-docs.com/docs/application-maps-app-vpc)에 따라 네이버 클라우드 콘솔의 VPC → Services → Application Services → Maps → Application에서 앱을 등록하고 **Directions 5**를 선택한다. 인증 정보의 Client ID·Client Secret을 프로젝트 루트의 비공개 `.env`에 입력한다. 계정 Access Key/Secret Key나 네이버 검색 API 키와 혼동하지 않는다.

```dotenv
PETASOS_NAVER_MAPS_CLIENT_ID=
PETASOS_NAVER_MAPS_CLIENT_SECRET=
```

Client Secret은 채팅·소스·APK에 넣지 않고 서버에만 둔다. 공개 Client ID도 값 자체를 채팅이나 추적되는 소스에 기록하지 않는다. 아래 Android 지도 기능을 사용하면 공개 ID만 제품 APK에 포함된다. `.env` 파일은 기존처럼 소유자만 읽는 0600을 유지한다. Directions 5만 사용할 때는 Dynamic Map이나 Android 패키지 등록이 필요하지 않다. Android 지도를 함께 사용할 때의 등록은 다음 별도 절차를 따른다.

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

서버 독립 모듈·CLI 검사에 이어 dev.26의 Android 연결을 구현했다. Pleos 공개 API에서 읽은 목적지·경유지는 별도 메모리 입력으로 유지하고 기존 기기 Bearer로 고정 `/v1/routes/auxiliary`를 호출한다. Directions 5의 Secret과 인증 요청은 서버에만 있다. Android 호출은 최대 2개 worker·전체 15초로 제한하며 자동 재시도·원본 경로 저장을 하지 않는다. 경로 변경·입력 철회·위치 이탈·만료는 기존 예측을 폐기한다. [통합 구조](2026-11-09-auxiliary-route-integration.md).

지역 시간창은 **guide 전체 선분이 하나의 검토된 NGII 지역 안에 있는 구간**에서만 만든다. 경계를 넘거나 확정할 수 없는 구간은 unknown으로 남긴다. 실제 위치와 연결하는 앵커도 가능한 전체 경로 구간과 측정 시각·정확도를 검사하며 guide 내부 시간을 거리 비례로 나누지 않는다.

2026-11-09 공식 공개 예제 실호출 기록은 다음과 같다. 호출 시각의 교통·경로 계산에 따라 점·guide 개수가 달라질 수 있다.

| 검사 | 확인한 결과 | 증거 |
| --- | --- | --- |
| 개발 CLI → 네이버 | 4,067점·29 guide, 시간 연결·합계 일치, `timingUsable=true` | [초기 기록](../../artifacts/p0-completion/naver-official-example.json) |
| 등록 기기 인증 → localhost HTTPS endpoint → 네이버 | 4,203점·29 guide, `timingUsable=true` | [서버 경로 기록](../../artifacts/milestone-26/naver-https-official-example.json) |
| 최종 privacy APK의 `auxiliary-route` | 4,138점·27 guide, 실제 NGII/계층 투영 71ms, 지역 시간창 7개·unknown guide 18개. 공개 경로 위 **합성 Android 점·시계**의 앵커가 passage 2개를 생성하고 출처 만료·철회를 거부 | [기기 결과](../../artifacts/milestone-26/auxiliary-route/auxiliary-route-privacy.json), [개수·시간 기록](../../artifacts/milestone-26/auxiliary-route/auxiliary-route-privacy.txt) |

기기 probe는 기존 암호화 config로 공개 예제 요청을 한 번 보내고 폐기용 Room을 사용했다. 전체 1,202ms, 기하 조회 예산 초과 없음이었다. 사용자 위치·내비 입력·이동 이력을 읽거나 제품 여행·방문 카드·정책을 생성하지 않았다. 원본 경로 파일, 좌표 출력, 오디오·모델 호출도 없다. 7개 시간창과 18개 unknown은 서로 다른 집계 단위이며 합쳐 guide 수를 계산하지 않는다.

최종 Debug JVM **964개/75 suite**(실패·오류·생략 0), privacy 앱·검증 APK 빌드, Lint와 APK 정적 감사가 통과했다. [JVM 집계](../../artifacts/milestone-26/jvm-test-summary.json), [빌드](../../artifacts/milestone-26/build.log), [Lint](../../artifacts/milestone-26/lint.log), [정적 감사](../../artifacts/milestone-26/client-dex-audit.json). 정적 감사는 별도 SpeechService의 동작·개인정보 안전성 검증을 대신하지 않는다.

같은 최종 APK의 기기 probe 6종은 **5 PASS, 1 FAIL**이다. `auxiliary-route`, `navigation`, `automatic-planning`, `region-hierarchy`, `media-session`은 PASS다. `app-location-live`는 47,911ms 후 복귀한 새 segment의 live UPDATE를 확인하지 못했고 cleanup도 실패했다. 로그의 `returned_valid_11`은 세션 누적 11개이며 복귀 후 새 수신 11개를 뜻하지 않는다. [실패 결과](../../artifacts/milestone-26/app-location-live/app-location-live-privacy.json), [관측 기록](../../artifacts/milestone-26/app-location-live/app-location-live-privacy.txt)를 보존한다. 이후 같은 APK 설치·실행 smoke PASS는 이 위치 실패를 해결하거나 대체하지 않는다([smoke](../../artifacts/milestone-26/final-smoke/verification.txt)).

**dev.26에서 실제 Pleos 선택 목적지와 실제 유효 위치를 함께 사용하는 기기 전체 흐름은 미검증**이었다. 이 공개 예제의 합성 앵커를 실제 주행이나 활성 Pleos 경로 일치로 표시하지 않는다. 통과 카드에는 기기에서 받은 유효 위치와 별도 검토된 지역 정책이 필요하며 dev.26 시점에는 승인된 지역·랜드마크 정책이 없었다. 이후 정책 검토 상태는 [개발 인계](../../DEVELOPMENT_HANDOFF.md)를 따른다.

## Android 도로 지도 표시 — 2026-11-09 dev.34 첫 로딩 검증

이 절은 위 dev.26 Directions 검증과 별개의 UI 구현·검증 상태다. 사용자는 기존 Maps Application에 **Dynamic Map**을 추가했고, 기존 공개 Client ID를 Android 지도에도 사용하는 것을 허용했다. dev.34 최종 제품 APK를 Pleos에 설치해 기존 공개 ID로 실제 Naver SDK 인증·첫 지도 로딩 `READY`를 확인했다. 제품/데모 최종 빌드와 APK 분리 검사도 통과했다. 이 결과는 실제 선택 경로의 표시나 실주행 수용을 의미하지 않는다. [실제 지도 검사](../../artifacts/milestone-34/journey-ui/journey-ui-privacy.json).

| 기능 | 역할 | 사용하는 인증 |
| --- | --- | --- |
| Pleos NaviHelper | 차량 내비의 실제 선택 목적지·경로 상태 기준 | 기존 공식 Pleos SDK 권한 |
| Naver Directions 5 | 별도 서버가 조회하는 보조 예상 경로와 시간창 | 서버의 Client ID + Client Secret |
| Naver Android Map SDK | Petasos 화면 안의 도로 배경과 검증된 현재 위치 표시 | APK에 포함 가능한 공개 Key ID만 사용 |

Android 지도는 Pleos 앱을 임베드하거나 그 앱 위에 자유롭게 오버레이하는 구현이 아니다. 길 안내·실제 선택 경로·다음 이야기 후보를 SDK 지도에서 만들지 않는다. 현재 SDK 어댑터는 배경 지도와 현재 위치만 표시한다.

### 콘솔 등록과 로컬 입력

[공식 Android SDK 시작 안내](https://navermaps.github.io/android-map-sdk/guide-ko/1.html)에 따라 Maps Application에서 **Dynamic Map**을 선택하고 Android 앱 패키지에 **`com.petasos.app`**을 등록한다. debug/privacy/release 제품은 같은 패키지를 사용한다. `com.petasos.app.demo`는 네트워크 지도를 사용하지 않으므로 이 통합을 위해 등록하지 않는다. Dynamic Map 선택만으로 패키지 등록까지 완료되었다고 간주하지 않는다. 공식 문서는 잘못된 패키지 등록 등에 401, 미선택/사용량 제한 등에 429 인증 실패가 발생할 수 있다고 안내한다.

```dotenv
# 전용 Android 공개 Key ID가 있을 때만 입력. 비어 있으면 기존 공개 Client ID 사용.
PETASOS_NAVER_MAPS_ANDROID_KEY_ID=
```

빌드 시 우선순위는 `PETASOS_NAVER_MAPS_ANDROID_KEY_ID` → `PETASOS_NAVER_MAPS_CLIENT_ID`다. 각 항목은 비어 있지 않은 프로세스 환경변수를 우선하고, 없으면 프로젝트의 `.env`를 읽는다. 모두 없으면 오프라인 지역 지도만 표시한다. 기존 `PETASOS_NAVER_MAPS_CLIENT_SECRET`을 Android Key ID 칸에 복사하지 않는다. 빌드 설정은 공개 ID 두 항목만 추출하며 Secret을 리소스·BuildConfig·manifest에 넣지 않는다. 이번 앱 어댑터가 추가한 진단에는 공개 ID 값이나 SDK 예외 원문을 기록하지 않는다. SDK 내부의 기기 출력은 최종 실기 검증과 구분한다.

### SDK·화면 수명주기와 격리

공식 Maven 저장소의 `com.naver.maps:map-sdk:3.24.0`을 고정했다. `debugImplementation`, `privacyImplementation`, `releaseImplementation`에만 의존성을 추가하고 제품 구현은 `src/productMap/java`에 둔다. 제품별 manifest의 `com.naver.maps.map.NCP_KEY_ID`는 제품별 공개 ID 리소스를 참조한다. 지도 캐시는 공식 `com.naver.maps.map.CACHE_LOCATION=cache` 설정을 사용한다. demo는 별도의 SDK 없는 factory를 컴파일한다. 최종 demo APK에서 Naver native library·DEX·공개 키 리소스가 없고 권한 수가 0임을 확인했다. 제품 APK의 설정된 비밀값 3개 검사도 모두 미포함이었다. [demo 격리 결과](../../artifacts/milestone-34/demo-apk-isolation.json), [제품 비밀값 검사](../../artifacts/milestone-34/product-secret-scan.json).

공통 `ProductJourneyMap`은 `FrameLayout`이며 생성·레이아웃 inflate만으로 SDK를 시작하지 않는다. 표시 허용된 화면의 `onStart/onResume`에서 SDK를 만들고 시작한다. `MapView.onCreate(null)`부터 pause/stop/destroy 및 저메모리 콜백까지 전달한다. Activity의 상태 저장 시에는 `saveTransientState()`로 SDK 콜백을 전달하되 카메라 Bundle을 폐기해 위치를 Activity 복원 상태로 저장하지 않는다. 정지하면 SDK View도 파괴하며 늦은 이전 콜백은 세대 검사로 무시한다. [공식 MapView 생명주기](https://navermaps.github.io/android-map-sdk/guide-ko/2-1.html).

`LocationSource`와 SDK 자체 위치 추적·위치 버튼은 사용하지 않는다. 기존 제품 위치 어댑터가 허용한 신선한 좌표를 `setPosition`으로 전달받아 표시할 뿐이다. 재개하지 않은 화면의 새 위치는 받지 않으며 `null`, 잘못된 좌표, pause/stop에서 기존 점을 지운다. 인증 실패·SDK 초기화 예외·최초 지도 로딩 20초 초과는 `RegionalMapView` 오프라인 배경으로 전환한다. 자동 재시도는 없고 명시 `retry()`만 제공한다. [공식 위치 API](https://navermaps.github.io/android-map-sdk/guide-ko/4-2.html).

네이버 로고와 기본 법적 공지/라이선스 클릭을 유지한다. 현재 로고 위치는 지도 오른쪽 아래 16dp 여백이며 말걸기 버튼·재생 바·상태 문구로 가리지 않아야 한다. [공식 UI 설정 안내](https://navermaps.github.io/android-map-sdk/guide-ko/4-1.html).

dev.34 최종 제품·demo 빌드와 JVM 검사(제품 **1,046개/85 suite**, demo **1,065개/88 suite**, 실패·오류·생략 0)를 통과했다. 제품/데모 Lint는 후속 재생 순서·문구 수정 및 데모 Stop 경합 검사 3개 추가 전에 통과했으며, 그 후 최종 빌드와 JVM을 재검증했다. [제품 JVM](../../artifacts/milestone-34/jvm-privacy.json), [demo JVM](../../artifacts/milestone-34/jvm-demo.json).

동일한 최종 제품 APK의 `journey-ui` 검사는 **5,610ms PASS**다. 실제 MainActivity·CarUx 화면 이동, 관심사 큰 타일 12개, 읽기 전용 기록 지도·지역 실루엣 261개, 지역 안의 완료 이야기 영역과 **Naver 첫 로딩 READY**를 확인하고 Activity를 종료했다. 선호·동의·권한 변경, 재생·마이크·모델 명령은 보내지 않았다. 제품 SHA-256은 `d4918ebafa7ae9fbca96095e647e5faea64b5ebe1b74de714c4d1335dbdb7f61`이다. [검사 이벤트와 범위](../../artifacts/milestone-34/journey-ui/journey-ui-privacy.txt), [최종 APK](../../artifacts/milestone-34/product-apk.json).

현재 위치 표시·철회의 전체 흐름, 네트워크/인증 장애 주입과 오프라인 복귀, 실제 차량 주행·선택 경로의 수용은 이번 첫 지도 로딩 검사 범위 밖이다. 기존 Directions 5 공개 예제 PASS와 Android 지도 첫 로딩 PASS도 서로 별도의 결과다.

### 최초 R8 빌드 실패와 선택적 위치 헬퍼 처리

최초 privacy 빌드에서는 `com.google.android.gms.tasks.Task` 누락으로 R8이 실패했다. Naver `map-sdk:3.24.0`의 공식 POM에는 Google Play Services 의존성이 없고, AAR의 consumer 규칙은 선택적 위치 기능의 `common.api`와 `location`만 경고 제외한다. `classes.jar` 전체를 검사한 결과 Task 참조는 `FusedLocationSource$b` 한 클래스의 위치 업데이트 등록·해제 반환형 두 곳에만 있다. 해당 헬퍼 계열을 참조하는 SDK 클래스도 그 클래스와 내부 클래스에 한정된다. 앱에는 이 헬퍼를 사용하는 코드가 없으며 지도 어댑터는 `locationSource=null`을 유지한다.

진단 빌드의 `-dontshrink`로 남은 미사용 선택 기능을 위해 제품에만 연결한 `app/naver-map-rules.pro`에 **정확히 `-dontwarn com.google.android.gms.tasks.Task` 하나**를 추가했다. 새 Google 위치 의존성이나 포괄 경고 제외는 추가하지 않았다. 수정 후 위 dev.34 최종 제품·demo 빌드와 제품의 실제 SDK 첫 로딩을 통과했다. 최초 실패 기록은 이 후속 성공과 구분해 보존한다. SDK를 변경하거나 `FusedLocationSource`를 실제 사용할 경우 이 예외를 재검토하고 [공식 위치 가이드](https://navermaps.github.io/android-map-sdk/guide-ko/4-2.html)의 의존성을 추가해야 한다. 검토한 `classes.jar` SHA-256은 `1002e92ba08bb5e532c3e0d14cd3308392aca3af44161d6261fe2e1ef47e0918`이다.
