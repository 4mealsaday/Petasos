# dev.9 — 콘텐츠 거부 정리와 요약의 현재 근거 검사

기준일: 2026-11-07. v0.8의 TX-02/TX-03, REC-03~07과 기존 보관·재시도 정책을 유지한다. dev.8에서 남긴 콘텐츠 거부 후 정리와 요약 근거 권한 검사를 구현했다. UI 디자인·제품 화면 조립, 사람의 발화, Virtual Console과 외부 모델 호출은 이번 범위에 포함하지 않는다.

## runtime 정리

- `RuntimeContentUnavailable`과 `RuntimeIssue.CONTENT_UNAVAILABLE`로 현재 콘텐츠를 사용할 수 없는 경우를 저장 장애와 구분한다. SQLite·암호화·설치기 저장 오류는 기존 저장 장애 경로를 유지한다.
- 대본 준비 중 거부되면 새 재생 의도나 attempt를 만들지 않는다. 출력 등록 트랜잭션이 거부되면 `OutputNotRegistered`로 Coordinator의 대기 상태를 정리한다. DB에 없는 attempt를 성공 커밋한 것으로 응답하지 않으며 잘못된 이야기 참조를 checkpoint로 저장하지 않는다.
- 등록은 성공했지만 SDK 호출 직전 권한 검사가 거부되면 실제 등록된 attempt에 TX-02 `interrupted`를 기록한다. 완료 문단이나 카드 적립을 만들지 않는다. Stop/close가 거부 응답을 앞지르는 경우도 같은 경계를 따른다.
- 답변 저장 시 권한 거부는 `AnswerContentRejected`로 처리한다. 저장된 최종 사용자 텍스트의 암호문을 보존하고 turn을 동결한다. 실제로 저장하지 않은 답변을 저장 완료로 알리지 않는다.
- 콘텐츠 거부는 `storageFailed`를 설정하지 않아 checkpoint·동결·세션 종료 쓰기가 계속 가능하다. 권한을 회복한 뒤 같은 runtime에서 사용자가 명시적으로 재생할 수 있다. 실제 저장 장애는 기존 차단 상태를 유지한다.

## 요약 근거와 재개

`RoomSummaryStore`는 실제 재생 완료된 응답 문단의 출처만 읽고, 현재 검토 상태와 generation/display/distribution 허가를 검사한다. claim 전 거부는 요청 생성과 시도 횟수 증가 없이 `SUMMARY_EVIDENCE_BLOCKED`를 남긴다. 미재생·중단 본문과 그 출처는 전송하지 않는다.

사실을 승인하려면 verified_fact이며 연결된 **모든 출처**가 완료 응답 입력에 포함되어야 한다. 일부 출처만 겹치는 사실은 승인 목록에 넣지 않는다. 출처 메타데이터·허가, 사실 내용·종류와 전체 출처 연결을 fingerprint로 고정한다.

`SummaryWork.checkCurrent()`는 요청·turn 버전·시도 번호, 암호화 입력 HMAC과 근거 fingerprint를 재검사한다. 기본값은 저장 불가로 차단한다. 키 손실·암호문 손상은 `PAYLOAD_UNAVAILABLE`로 구분해 REC-07에 따라 즉시 blocked로 남기며, 일시적인 DB 조회 실패만 기존 재시도 정책으로 처리한다. 실행 시작, HTTP 송신 직전, 응답 수신 후 파싱 전, TX-03 저장에서 확인하며 실제 DB/키 오류와 권한 거부를 구분한다. TX-03에서는 같은 트랜잭션 안에서 다시 읽은 근거를 검증한 뒤 요약 저장·saved·임시 암호문 삭제를 처리한다.

권한 차단은 암호문을 보존하며 자동 재시도하지 않는다. 앱 재시작과 일반 설정 변경으로 해제하지 않는다. 근거를 명시적으로 재검토한 후 `RoomSummaryStore.unblockAfterEvidenceChange()`가 현재 권한을 재검사하며, Android의 `SummaryBackground.evidenceChanged(context)`는 허용된 작업만 다시 예약한다. 아직 제품 검토 화면과 자동 연결하지 않았다. 키/암호문 오류의 `SUMMARY_PAYLOAD_UNAVAILABLE`은 설정·근거 변경으로 해제하지 않는다. 새로운 묶음의 최대 3회, 30초/2분 대기·소진 후 10분 힌트 정책은 유지한다.

## 실행 검증

```sh
./scripts/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest
python3 scripts/probe-pleos.py content-runtime \
  --content-bundle .local/content/bundles/gageodo-v1.json \
  --artifacts-dir artifacts/milestone-8/content-runtime
python3 scripts/probe-pleos.py summary-rights \
  --artifacts-dir artifacts/milestone-8/summary-rights
```

기기 검사는 테스트 전용 DB·Keystore를 사용한다. Room·암호화와 트랜잭션은 실제 구현이고, 음성 callback·생성 응답은 합성이다. `summary-rights`는 프로세스 내부 transport이며 별도 `generation-https` 회귀만 실제 로컬 TLS 통신이다. 실제 사용자의 발화·외부 모델·오디오 출력은 사용하지 않는다.

## 실행 결과

`0.8.0-dev.9` / versionCode 9를 Pleos Connect 2.1.0, Android API 34의 `emulator-5554` 사용자 10에서 설치·실행했다. 최종 증거는 `artifacts/milestone-8/verification.json`이다.

| 검사 | 결과 | 증거 |
| --- | --- | --- |
| 앱·검증 APK, Lint | PASS | `artifacts/milestone-8/build-lint-tests.log` |
| JVM | 174개 PASS, 실패·오류·skip 0 | `app/build/test-results/testDebugUnitTest/` |
| Python 서버 / 호스트 도구 | 135개 / 46개 PASS | `artifacts/milestone-8/server-tests.log`, `host-tests.log` |
| 콘텐츠 runtime | PASS, 12개 이벤트 | `artifacts/milestone-8/content-runtime/content-runtime.json` 및 `.txt` |
| 요약 권한·키 손실 | PASS, 9개 이벤트 | `artifacts/milestone-8/summary-rights/summary-rights.json` 및 `.txt` |
| 저장소·runtime-scripted·summary-storage·summary-work | 모두 PASS | `artifacts/milestone-8/regression/`의 각 JSON/TXT |
| 실제 loopback HTTPS 회귀 | PASS, 합성 provider | `artifacts/milestone-8/regression/generation-https.json` |
| 기존 실제 콘텐츠 유지 | 신규 0개·기존 1개, 재설치 무변경 | `artifacts/milestone-8/installed/content-install-local.json` 및 `.txt` |
| 최종 앱 실행 | PASS, 전면 Activity·프로세스·충돌 확인 | `artifacts/milestone-8/launch/verification.txt` |

최종 APK SHA-256은 `777b6814a8a5195a4f1ace0a0628fdce2375449ebafa62158842d50a2ead3a28`이다. 기기 probe 8개가 모두 동일한 최종 앱·검증 APK를 사용했다. 실제 가거도 bundle과 서버 evidence 파일은 기존 해시 그대로다. 임시 TLS 서버를 종료했고 ready 파일과 probe의 reverse 연결·임시 입력은 정리했다.

등록 거부 직후 Stop/close의 잘못된 checkpoint 쓰기를 새 JVM 테스트 3개로 재현했고, 같은 worker 작업에서 거부를 먼저 처리한 빌드에서 모두 통과했다. 실제 Room에서도 Stop/close 두 경합을 확인했다. 요약 기기 검사는 네 가지 권한·검토 거부, 불완전한 fact 출처 연결, HTTP 직전·늦은 응답·TX-03 철회, 메타데이터 변경, 명시적 재개, 실제 AES 키 삭제와 닫힌 DB의 오류 구분을 포함한다. 키 손실 후 직접 TX-03 호출에서도 blocked 기록은 커밋되고 암호문은 보존됐다.

## 남은 경계

- 로컬 DB의 현재 권한 검사다. 서버의 시작 시점 evidence에 원격 철회를 동적으로 반영하거나 이미 재생 중인 문단을 권한 변경 이벤트로 즉시 멈추는 기능은 별도다.
- 일반 앱 화면은 프리뷰다. 이 runtime과 검토/재개 API를 제품 화면에 연결하기 전에 UI 디자인을 논의한다.
- 실제 대화 출력에는 요청별 STT 입력 종료 확인이 필요하다. 기존 OpenRouter 보관 설정 확인과 실제 모델 품질 검증도 남아 있다. 키 인증과 실제 생성 성공을 구분한다.
- 이번 단계의 필수 사용자 수동 시험은 없다. 사람의 발화와 Virtual Console은 계속 보류한다.
