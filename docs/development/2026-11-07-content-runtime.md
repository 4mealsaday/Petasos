# dev.8 — 설치 콘텐츠 재생 준비와 답변 생성 문맥

기준일: 2026-11-07. v0.8의 revision·완료 청취·생성 근거 계약을 유지하면서 dev.7 TX-05 설치 결과를 runtime에 연결한다. 일반 화면은 프리뷰이며 사람의 발화와 Virtual Console은 보류한다.

## 구현 경계

- `PlaybackRuntime.playInstalledStory(versionId)`가 전용 작업 큐에서 `RoomRuntimePersistence.prepareStory()`를 호출해 설치한 대본을 가져온다. 준비 전후 Stop/Suspend/Block 또는 close가 들어오면 늦은 재생을 시작하지 않는다.
- `RoomContentContext`는 `RoomContentInstaller.publishedStory()`의 전체 대본 해시·필수 문단·만료·현재 출처 검토/표시/배포 허가 검사를 재사용한다. runtime의 등록 시점과 SDK 호출 직전 본문 조회에서 다시 검사한다.
- `RoomRuntimePersistence.answerContext(request, isRuntimeCurrent)`가 실제 DB와 암호화된 확정 발화를 이용해 `AnswerGenerationContext`를 만든다. 현재 session·turn·turnVersion·cancelEpoch·발화 일치가 필요하다.
- 같은 세션에서 completed로 커밋된 이야기 문단만 후보로 삼는다. 최근 최대 64개 중 유효 대본과 생성/표시/배포 허가가 있는 문단을 선택한다. 문단을 자르지 않고 전체 문단 단위로 contextSummary 4,000자·출처 50개 제한을 지킨다. 문맥은 선택한 공개 대본의 시간순 연결이며 모델 요약이나 과거 개인 대화가 아니다. 근거가 없으면 생성을 요청하지 않는다.
- context는 문단·대본 hash·사실 내용/분류/연결·출처 메타데이터/허가·turnVersion·암호문 HMAC을 fingerprint로 고정한다. `isCurrent`는 동일 snapshot과 runtime 요청 token/attempt/fence를 다시 확인한다. fingerprint나 발화 본문은 로그로 출력하지 않는다.
- `GenerationClient`는 준비 시점, HTTP 전송 직전, 응답 수락 시점에 현재 문맥을 검사한다. 모델의 사실/전승 분류도 검토된 fact 종류와 일치해야 한다. 종류 정보가 없는 fact는 허용하지 않는다. 현재 지원하는 action은 continue/unsupported와 null target이다.
- 생성된 답변의 출처 권한은 Room 저장·출력 등록·출력 본문 조회에서 다시 검사한다. 저장소의 checkpoint/정리 메서드는 ID 관계만 검사하도록 분리했다. 단, runtime은 권한 거부도 기존 storageFailed 정책으로 처리하므로 즉시 정리 쓰기는 생략될 수 있으며 다음 세션 복구가 prepared 이력을 interrupted로 바꾼다.

코드는 `runtime/`, `generation/`, `storage/`에 있으며 View·레이아웃·색상 리소스를 변경하지 않는다. `MainActivity`는 runtime을 생성하지 않는다. 위 API의 통합 경로는 테스트 APK가 조립한다.

## 서버 근거 연결

기존 검토 bundle에서 명시적으로 허용한 등록 기기용 EvidenceRegistry를 내보낼 수 있다.

```sh
python3 scripts/build-content-bundle.py \
  --evidence-from-bundle .local/content/bundles/gageodo-v1.json \
  --allow-device-id <등록된-기기-ID> \
  --output .local/content/evidence/gageodo-v1.json
```

원본 bundle을 다시 검증하며 생성 허가가 있는 출처와 그 출처들로 온전히 뒷받침되는 fact만 포함한다. 검토 상태·허가를 자동 승격하지 않는다. 입력과 출력은 `.local/content/` 아래 비공개 파일이며 기존 출력은 덮어쓰지 않는다. 명령행과 stdout에는 토큰·API 키·원문을 넣지 않는다. 기기 ID는 인증 토큰과 별개다.

실제 가거도 자료의 출처 2개·사실 2개를 기존 등록 기기 1개에 한정해 내보냈다. `.local/server/config.json`의 evidenceFile을 새 파일로 연결하고 서버 설정 로드·기기별 근거 resolve를 검사했다. 기존 빈 evidence 파일은 보존했다. provider 설정은 null이며 외부 모델 호출은 없다. 이미 실행 중인 서버에 이 파일 변경을 실시간 반영하는 기능은 없으므로 새 설정은 다음 서버 시작부터 적용한다.

## 검증 실행

```sh
./scripts/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest
python3 scripts/probe-pleos.py content-runtime \
  --content-bundle .local/content/bundles/gageodo-v1.json \
  --artifacts-dir artifacts/milestone-7/content-runtime
```

content-runtime은 임시 DB와 테스트 전용 Keystore 키를 사용하며 실제 앱의 청취/카드 이력을 만들지 않는다. 실제 검토 bundle의 설치·준비·문맥을 사용하되 음성 callback과 모델 transport는 명시적인 합성 구현이다. 본문/발화/자격 증명은 검사 출력에 포함하지 않는다.

## 한계와 다음 작업

아래는 dev.8 실행 시점의 기록이다. 권한 거부 후 runtime 정리와 요약의 현재 출처 검사는 후속 [dev.9](./2026-11-07-content-rights.md)에서 보완했다. 서버의 원격 근거 철회 반영은 계속 별도다.

- 권한 거부와 실제 DB 장애를 runtime issue에서 아직 구분하지 않는다. 거부 이후 해당 runtime은 재생을 차단하며, prepared attempt의 즉시 terminal 저장/동일 runtime에서 재시작은 별도 개선이다. 다음 세션을 열 때 기존 복구가 중단 이력을 남긴다.
- HTTP 전송 직전과 SDK 호출 직전의 현재 DB 권한을 검사한다. 원격 이용권 변경을 자동 수집하거나 이미 재생 중인 문단을 권한 변경 이벤트로 즉시 중단하는 기능은 아니다.
- 이번 근거 snapshot은 답변 생성 경로다. WorkManager 요약의 현재 출처 허가 변경 정책과 서버가 이미 보유한 evidence의 철회 배포는 별도로 연결해야 한다.
- 좌표가 검토되지 않은 가거도 콘텐츠는 자동 위치 안내/지역 적립에 사용하지 않는다. 경로 맥락·다른 세션/여행의 개인 대화 요약을 임의로 넣지 않는다.
- 제품 화면 연결은 디자인 방향 논의가 선행한다. 실제 대화 출력에는 요청별 STT 입력 종료 확인이 필요하며, 실제 모델 호출에는 기존 OpenRouter 보관 설정 확인과 품질 검수가 남아 있다.

## 실행 결과

`0.8.0-dev.8` / versionCode 8을 Pleos Connect 2.1.0, Android API 34의 `emulator-5554` 사용자 10에서 설치·실행했다. 최종 증거 요약은 `artifacts/milestone-7/verification.json`이다.

| 검사 | 결과 | 증거 |
| --- | --- | --- |
| 앱·검증 APK, Lint | PASS | `artifacts/milestone-7/build-lint-tests.log` |
| JVM | 146개 PASS, 실패·오류·skip 0 | `app/build/test-results/testDebugUnitTest/` |
| Python 서버 / 호스트 도구 | 135개 / 46개 PASS | `artifacts/milestone-7/server-tests.log`, `host-tests.log` |
| 새 콘텐츠 runtime 경로 | PASS, 9개 이벤트 | `artifacts/milestone-7/content-runtime/content-runtime.json` 및 `.txt` |
| 저장소·runtime-scripted·summary-storage·summary-work | 모두 PASS | `artifacts/milestone-7/regression/`의 각 probe JSON/TXT |
| 실제 loopback HTTPS 회귀 | PASS, 임시 CA/서버 및 합성 provider | `artifacts/milestone-7/regression/generation-https.json` |
| 기존 실제 콘텐츠 유지 | PASS, 신규 0개·기존 1개, 재설치 무변경 | `artifacts/milestone-7/installed/content-install-local.json` 및 `.txt` |
| 최종 앱 실행 | PASS, 전면 Activity·프로세스·충돌 로그 확인 | `artifacts/milestone-7/launch/verification.txt` |
| 실제 서버 근거 연결 | 출처 2개·사실 2개·등록 기기 1개, resolve/설정 로드 PASS | `artifacts/milestone-7/evidence-export.json` |

최종 APK SHA-256은 `4d26d33cc3be06f8736d8f74e08484baedf5af536a64c4c4e6730e72ef0b8f1a`다. 모든 기기 probe가 같은 APK/검증 APK를 사용한 것을 확인했다. 실제 데이터 bundle은 dev.7의 `6e9bda6aa7b8e9a7803d095d0056c7b86fa3e7a829bdaf1b41276c5b297630c1` 그대로다. 서버 evidence export SHA-256은 `4f9d4d15e1e8c1e05fc2e71865f6d33f01b0df5236fab49aa2dbabe9c9dfb295`이며 파일은 0600이다.

JVM 검사는 HTTP 직전 문맥 철회/조회 실패 시 transport 0회, 사실 종류 누락·변경 거부, 준비/출력 대기 중 Stop/close, 정확한 generation token·attempt 유효성을 포함한다. 기기 검사는 다른 세션/미완료 문단 제외, 생성 금지 자료의 표시 허용과 전송 금지, 권한 철회 후 늦은 응답 거부, 답변 저장·출력 권한 검사, session/turn 변경, 설치 본문 변조 차단과 실제 자료 전체 연결을 포함한다.

마이크·실제 TTS·외부 모델·Virtual Console은 사용하지 않았다. 새 콘텐츠 runtime의 음성/모델 부분은 합성이고, 별도 HTTPS 회귀만 실제 로컬 TLS 통신이다. 테스트 DB/키와 임시 HTTPS 서버는 정리했으며 실제 앱 DB의 기존 콘텐츠는 보존했다. 이번 단계의 필수 사용자 시험은 없다.
