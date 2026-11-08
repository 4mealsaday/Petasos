# dev.7 — 검토 콘텐츠 컴파일과 TX-05 설치

기준일: 2026-11-07. 이번 단계는 실제 공공데이터에서 검토한 최소 텍스트를 만들고, Android Room에 원자적으로 설치하는 경로다. 화면·자동 위치 안내·사용자 발화·실제 모델 호출은 연결하지 않는다. 제품 기준은 v0.8 DATA-10/TX-05이며 DB 스키마는 기존 version 1을 유지한다.

## 구성과 신뢰 경계

1. 공공 API 후보는 `.local/content/incoming/`에 pending으로 남는다. 수집 성공만으로 허가값을 바꾸지 않는다.
2. [출처 검토 기록](./2026-11-07-content-source-review.md)은 사실·허용 텍스트·출처·개별 권리·좌표 검토를 기록한다. 입력은 `.local/content/review/source-review.json`이다.
3. `server/petasos_server/content_bundle.py`와 `scripts/build-content-bundle.py`가 명시적으로 검토한 입력을 결정적 UUID·해시를 가진 콘텐츠 묶음으로 변환한다. 기존 파일은 덮어쓰지 않는다.
4. 앱 `content/ContentBundleCodec.kt`는 256KiB 이내의 엄격한 JSON과 모든 참조·권한·해시·필수 문단을 다시 검증한다.
5. `storage/RoomContentInstaller.kt`가 하나의 Room 트랜잭션에서 설치한다. 설치 또는 재설치가 끝난 뒤 `publishedStory()`가 유효한 PreparedStory를 반환한다. 이 메서드가 재생을 시작하지는 않는다.

이 경로는 개발자가 검토한 로컬 파일을 명시적으로 전달하는 설치기다. `reviewStatus=verified` 문자열만으로 외부 작성자의 신원을 인증하는 원격 배포 API는 아니다. 다운로드·서명 검증·서버 배포는 별도 경계이며 현재 앱 UI에서 임의 파일을 가져오는 기능도 없다. 설치 코어와 검토 DTO는 화면·스타일·SDK 구현과 분리되어 있다.

## 묶음 계약

형식 `formatVersion=1`은 v0.8 DB 버전과 구분하는 로컬 전달 형식이다. 최상위 필드는 `sources`, `facts`, `places`, `stories`, `versions`이며 각 version에 `paragraphs`가 들어간다. 필드명은 앱 DTO의 camelCase다. 정확한 필드·타입은 [ContentBundle.kt](../../app/src/main/java/com/petasos/app/content/ContentBundle.kt)를 기준으로 한다.

| 항목 | 검사·저장 원칙 |
| --- | --- |
| 출처 | 검토 시각·출처 URL·텍스트 한정 license·검토 근거·attribution 필수. 표시/배포 허가 true 필수. 생성 허가는 독립 boolean |
| 출처 해시 | 정확한 `normalizedText` UTF-8 바이트의 SHA-256. 원본 API 응답 전체 해시와 구분 |
| 사실 | `verified_fact`/`tradition` 구분, 안정된 factId와 증가 version, 존재하는 검토 source revision 연결 |
| 문단 | 버전별 ordinal 연속, 최소 한 개 필수 문단, 모든 문단에 fact 연결. 같은 버전의 stable factId 중복 금지 |
| 대본 해시 | `{storyId, version, paragraphs}`의 canonical JSON SHA-256. 객체 키 정렬·UTF-8·공백 없음. factRevisionIds만 집합 의미로 정렬하고 문단 순서는 유지 |
| 시간 | epoch milliseconds 정수. 정확한 원천 갱신 순간이 불명확하면 sourceUpdatedAt=null, 원래 표기는 sourceUpdatedRaw에 보존 |
| 위치 | 검토한 WGS84 좌표와 출처·검토 근거만 허용. 좌표가 미확인일 때 관련 다섯 필드는 모두 null |
| 지역·랜드마크 | 이번 묶음에는 RegionPolicy/지역 revision을 넣지 않으며 DB region 및 landmark policy는 null. 자동 적립하지 않음 |
| 준비 음성 | `audioRef=null`만 허용. AssetManifest/실물·해시·자산별 권리 검증은 별도 구현 |

내용·검토 시각·허가·연결이 같은 기존 revision만 재사용한다. 동일 ID의 다른 본문/메타데이터/근거 또는 동일 자연키를 다른 revision ID로 등록하려는 요청은 거부한다. 같은 원문을 다시 가져왔다는 이유로 최초 fetchedAt을 바꾸지 않고 기존 검토 묶음을 재사용한다. 수정은 새 revision으로 추가하며 기존 카드·청취 이력의 참조를 보존한다.

## TX-05 원자성

네트워크 조회와 자료 검토는 DB 트랜잭션 밖에서 끝낸다. DB 안에서는 source → place/외부 ID → fact/source 연결 → story → draft version → paragraph/fact 연결을 삽입하고, 완전한 version만 마지막에 published로 전환한다. 중간 SQL 오류나 불변성 충돌이 나면 새 source를 포함한 모든 삽입을 롤백한다. 부분 실패를 draft 찌꺼기로 남기거나 기존 행을 replace/update하지 않는다.

같은 묶음을 재설치하면 신규 version 0개이며 기존 데이터는 그대로다. 새로운 version은 이전 version과 문단·청취 attempt·카드 proof를 보존하며 추가한다. `publishedStory()`는 상태·만료·필수 문단·문단 순서·전체 대본 해시·duration·현재 출처 권한과 근거 연결을 확인한다. DB에서 마지막 문단을 지우거나 본문만 바꿔도 준비를 거부한다.

출처 본문은 별도 DB 컬럼으로 추가하지 않는다. DB는 source revision 해시·권리·attribution, 검토 fact 및 대본을 보관하고 검토용 원문 파일은 호스트의 비공개 로컬 영역에 남긴다. 서버용 EvidenceRegistry 출력은 allowGeneration=true인 출처와 그 출처로 온전히 뒷받침되는 fact만 포함하며 기기 허용 목록이 명시되어야 한다.

## 기기 실행 경로

```sh
python3 scripts/build-content-bundle.py \
  --review .local/content/review/source-review.json \
  --output .local/content/bundles/gageodo-v1.json

./scripts/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest

# 합성 오류 시나리오와 실제 검토 묶음을 임시 DB에서 검사
python3 scripts/probe-pleos.py content-install \
  --content-bundle .local/content/bundles/gageodo-v1.json \
  --artifacts-dir artifacts/milestone-6/reviewed

# 명시적으로 실제 앱 petasos.db에 설치하며 기존 행은 유지
python3 scripts/probe-pleos.py content-install-local \
  --content-bundle .local/content/bundles/gageodo-v1.json \
  --artifacts-dir artifacts/milestone-6/installed
```

원문 묶음은 adb 명령 인수나 로그에 넣지 않고 stdin으로 앱 private cache에 전달한다. 전달 파일은 성공/실패 후 삭제한다. `content-install-local`은 묶음 인자 없이 실행할 수 없으며 제품 DB를 삭제하지 않는다. 두 경로 모두 테스트 APK에서 명시적으로 호출하고 오디오·마이크·위치·모델·Virtual Console을 시작하지 않는다.

## 현재 한계와 다음 연결

일반 앱은 프리뷰 화면이다. 콘텐츠가 설치되어도 자동으로 화면에 표시하거나 재생하지 않는다. `publishedStory()`와 제품 PlaybackRuntime의 연결, 재생 직전 및 준비 후 권한 변경을 처리하는 게이트는 후속 작업이다. 기존 RuntimePersistence는 아직 관계·published·만료 중심으로 검사하므로 설치 준비 API의 추가 검증을 기존 runtime 전체에 적용했다고 해석하지 않는다.

첫 place의 좌표는 두 공식 자료 사이의 대표 지점 차이를 해소하지 못해 null이다. 기존 place는 설치기에서 불변 비교하므로 추후 좌표/지역 검토가 끝나면 별도의 명시적 장소 메타데이터 갱신 경로가 필요하다. 외부 ID를 다른 place에 매핑하거나 새 place ID로 우회하지 않는다. 지역 코드·경계 판정과 실제 경로 시연은 이번 범위가 아니다.

## 실행 결과

`0.8.0-dev.7` / versionCode 7을 Pleos Connect 2.1.0, Android API 34의 `emulator-5554` 사용자 10에서 검증했다. 최종 증거 요약은 `artifacts/milestone-6/verification.json`이다.

| 검사 | 결과 | 증거 |
| --- | --- | --- |
| 앱·검증 APK 빌드, Lint | PASS | `artifacts/milestone-6/build-lint-tests.log` |
| JVM 단위 검사 | 129개 PASS, 실패·오류·skip 0 | `app/build/test-results/testDebugUnitTest/` |
| Python 서버 검사 | 133개 PASS | `artifacts/milestone-6/server-tests.log` |
| 호스트 도구 검사 | 46개 PASS | `artifacts/milestone-6/host-tests.log` |
| 실제 Room TX-05·변조 거부·실제 묶음 임시 설치 | PASS, 13개 이벤트 | `artifacts/milestone-6/reviewed/content-install.json` 및 `.txt` |
| 실제 앱 `petasos.db` 설치 | PASS, 신규 version 1개·기존 0개, 이후 재설치 무변경 | `artifacts/milestone-6/installed/content-install-local.json` 및 `.txt` |
| 설치 후 앱 시작·전면 Activity·충돌 로그 | PASS | `artifacts/milestone-6/launch/verification.txt` |

최종 앱 APK SHA-256은 `fb57410076169e0009385c4a622227431524ebaabad284c72ca5cd8ebdbe25c6`이다. 설치 묶음은 5,588 bytes, SHA-256 `6e9bda6aa7b8e9a7803d095d0056c7b86fa3e7a829bdaf1b41276c5b297630c1`이며 출처 2개·사실 2개·장소 1개·이야기/버전 1개·필수 문단 1개다. 단일 문단의 예상 재생 시간 7,000ms는 편집값이며 TTS 실측값은 아니다. 실제 묶음과 검토 파일은 호스트 `.local/content/`의 0600 파일로 보관한다.

기기 검사는 원자 롤백, 동일 재설치, revision/외부 ID 불변성, 이전 버전·청취·카드 보존, DB 재개방, 출처 권한/검토 상태/만료 재검사와 전체 대본 해시를 검증했다. 마지막 필수 문단 삭제 후 총 시간을 맞춰도 준비를 거부한다. 실행 중 추가 공공 API 호출·모델 호출·마이크·TTS·Virtual Console 사용은 없다. 이 결과는 TX-05 구현 범위의 성공이며 실제 대화·주행·제품 화면 검수의 성공은 아니다.

이번 단계의 필수 사용자 시험은 없다. 다음 구현은 설치 콘텐츠를 근거 snapshot과 재생 준비에 연결하고, 준비 이후 권한 변경까지 제품 runtime에서 차단하는 경로다. 본 UI 연결 전 디자인을 논의한다.
