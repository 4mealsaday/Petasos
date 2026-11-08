# dev.11 공개 이야기 생성과 대화 후 복귀

현행 v0.8 계약과 story 15초·resume 3초 제한을 유지한다. 정식 JSON/SQL schema, 실제 마이크 gate, Virtual Console 보류 및 UI 디자인 보류 결정은 변경하지 않았다. 실행 증거는 `artifacts/milestone-10/`에 보관한다.

## 명시적 새 이야기

정차 화면의 **새로 만들어 듣기**는 선택한 설치 이야기의 현재 근거로 공개 대본을 요청한다. 생성 중에는 선택/재생/다음/이전을 잠시 막고 정지로 취소할 수 있다. 성공한 대본은 새 immutable 버전으로 저장한 뒤 명시적 재생 의도를 Coordinator에 전달한다. 기존 버전과 사실·출처는 덮어쓰지 않는다. 앱 시작·목록 조회·prepare는 생성이나 재생을 시작하지 않는다.

`GenerationClient.submitStory`는 실제 HTTPS, 기존 기기 HMAC·strict schema·상관 ID·취소 경계를 공유한다. `RoomStoryGenerationStore.prepare`는 읽기만 수행하고, install은 세션/epoch/경로 의존성/선호 설정/최신 버전/근거 전체 fingerprint를 다시 검사한다. TX-05로 버전·문단·성공 요청 provenance를 원자적으로 추가한다. 저장 실패·stale·중복·권한 철회는 새 버전을 만들지 않는다.

현재 공개 이야기 저장소가 지원하는 fact/tradition 문단만 허용한다. 근거 없는 연결 문장과 개인 대화는 공개 대본에 넣지 않는다. 서버 모델용 schema와 v3 프롬프트도 같은 제약을 사용하며, 한 대본 안의 안정된 factId 중복과 근거 밖의 출처를 거부한다. 정식 v0.8 wire schema의 범위는 그대로다. 명시적 재생성은 같은 사실을 다시 들을 수 있다. 저장소는 같은 요청 출처에 연결된 알려진 사실 중 선택한 버전이 승인하지 않은 stable factId를 excludedFactIds에 추가한다. 첫 결과가 일부 사실만 사용해도 다음 요청의 서버 근거 범위가 앱 승인 범위를 벗어나지 않도록 한 것이다. 이 알려진 사실 집합도 fingerprint로 재검사하며 제외 목록 50개를 넘으면 요청하지 않는다. 서버에만 존재하는 미설치 사실은 여전히 응답 검증에서 거부하므로 검토된 양쪽 근거 목록을 함께 관리해야 한다. 미래 자동 생성은 이 제외 목록에 실제 청취한 안정된 factId도 포함해야 한다.

생성 시작 시 runtime의 owner/session/epoch/Stop fence에 고정한 `StoryPlaybackAuthority`를 보관한다. 모델 응답 도착 시 새 권한으로 바꾸지 않는다. 준비 job은 Coordinator의 재생 의도 수락 ACK까지 살아 있으며 Stop·선택 변경·화면 비가시화·전원 차단은 대기 작업을 취소한다. 저장 직후 Stop이 들어오면 공개 버전은 남을 수 있지만 재생은 시작하지 않는다. 이미 명시적 재생 의도가 수락된 배경 재생은 기존 미디어 정책을 따른다.

## 대화 후 복귀

`PlanResume.afterInteraction`은 대화 종료/취소/실패 후 복귀만 구분한다. 명시 Play·일반 재개는 고정 checkpoint를 사용한다. 대화 후에는 현재 이야기의 남은 문단 최대 20개 또는 원래 음악 위치 하나만 후보로 제공한다. 모델이 다른 이야기·음악·문단을 만들거나 임의 위치로 이동할 수 없다. 건너뛴 문단을 완료로 저장하지 않는다.

`RoomResumeGenerationContext`는 동결된 최종 사용자 발화와 **실제로 완료된 답변**만 암호화 임시 저장소에서 읽는다. 중단·미재생 본문은 전달하지 않는다. 최대 2,000자의 유효 JSON에 공개 후보 설명을 완전한 항목 단위로 추가한다. 필수 대화 맥락 자체가 한도를 넘으면 임의 절단 없이 기존 위치로 복귀한다. 음악은 호스트의 현재 자산 권리 검사도 통과해야 한다.

전송 전·응답 수락 전에 session/epoch/turn version/HMAC/완료 attempt/후보 hash/현재 출처·사실 fingerprint를 재검사한다. Summary Worker가 TX-03으로 임시 payload를 삭제하면 이 요청은 stale이며 고정 checkpoint로 복귀한다. 요약 처리와 복귀 처리의 경쟁 때문에 실제 API 선택이 매번 실행되거나 채택된다고 보장하지 않는다.

`ApiRuntimeResumePlanner`는 `ContentCheckedResumePlanner` 안에서만 사용한다. 오류·취소·stale·해석할 수 없는 응답 및 3초 만료는 기존 위치 복귀다. 정상 wait만 대기한다. 개인 대화 연결 문장을 저장한 뒤 출력하는 경로는 이번 단계에 연결하지 않았으므로 모델용 schema는 transitionText=null을 요구한다. 제품 마이크가 비활성인 동안 사람 대화 후 전체 복귀는 검수 완료로 표시하지 않는다.

## 저장 및 검증 범위

- 생성 대본의 verified_at는 자동 계약·근거 검사 시간이며 사람의 편집 검토를 의미하지 않는다. 실제 문장 품질·사실 충실도 검수는 별도다.
- 문단별 duration은 모델의 총 추정시간을 나눈 값이며 실제 TTS 측정시간이 아니다. 합계는 보존한다.
- 현행 schema에는 storyVersion→generationRequest 직접 FK가 없다. 생성 provenance는 요청·세션 단위이며 usage_json에는 토큰 사용량만 기록한다.
- UI 기능은 `presentation/PlaybackControls`, 배치는 layout, 문구·색상·스타일·치수는 각각 리소스에 유지한다. 생성/저장/복귀 정책은 View 코드에 넣지 않는다.
- 실제 마이크, 원문 로그 차단, 실주행·Suspend, 위치 의미/행정 경계, 음악 권리 자산, 관심사/랜드마크/자동 편성, 기록 설정·이동 종료 화면, 데모/UI 디자인은 별도 후속 범위다.

## 실행 결과

최종 `0.8.0-dev.11`의 앱/검증 APK 빌드·Lint PASS, JVM **340개/27 suite**, 서버 **177개**, 호스트 **55개**, 참조 계약 **13개**가 통과했다. 최종 APK와 검증 APK에서 **11종의 기기 probe 12회**를 실행했고 모두 각 범위에서 PASS다. 해시·개별 실행 범위·시간·marker는 `artifacts/milestone-10/verification.json`과 개별 JSON을 따른다. `source-sha256.json`으로 빌드 후 코드가 바뀌지 않았음을 검사했다.

| 검증 | 관측한 범위 |
| --- | --- |
| story-generation-storage | 실제 Room, 합성 자료: 설치 원자성/기존 버전 보존/취소·권한·선호·경로 변경/부분 사실 버전의 반복 생성 |
| resume-context | 실제 Room/Keystore, 합성 대화: completed-only/HMAC·버전·근거/2,000자/요약 TX-03 경합/HTTP 전후 철회 |
| generation-planning-live, 첫 호출 | 실제 HTTPS 이야기 설치·provenance 성공. 복귀는 **3,012ms에 RETRYABLE**로 완료해 유효한 대상 선택 없음. 공개 오류 분류만으로 네트워크·서버 일시 오류·client deadline을 더 세분하지 않음 |
| generation-planning-live, 두 번째 관측 | 실제 HTTPS 이야기 설치 성공. 복귀가 **1,481ms**에 정확한 허용 후보를 선택. 첫 결과도 그대로 보존했으며 두 번의 관측으로 장시간 성능을 보장하지 않음 |
| app-story-generation | 실제 제품 host: 명시 생성 즉시 Stop 시 무출력·무설치 → 실제 생성/설치/새 checkpoint/TTS Started 저장 → Stop·새 출력 없음. 공개 생성 버전은 제품 DB에 보존 |
| 기존 기능 회귀 | content-install, runtime-scripted, media-session, car-policy-ui, summary-rights, music-runtime, app-playback PASS. 실제 공개 이야기 완료 커밋 및 합성 음악을 각각 구분 |
| app-smoke | 최종 APK 설치/실행/크래시 검사 PASS. 새 버튼과 비활성 말하기가 표시되고 잘림 없음. 앱을 에뮬레이터 전면에 유지 |

첫 전체 JVM 실행에서 정지 직후의 새 명시 Play를 이전 epoch로 거부하는 회귀를 발견했다. 명시 Play는 요청 순간의 Stop fence를 고정하고 worker에서 최신 epoch를 확인하도록 수정했다. 비동기 생성 결과의 고정 authority는 그대로 유지하며 테스트를 약화하지 않았다. 실패 XML/빌드 로그와 수정 후 최종 결과를 함께 보존한다. 그보다 앞선 빌드 1회는 반복 생성 근거 범위 문제를 수정하려고 의도적으로 중단했다.

## 지금 직접 확인하기

개발 HTTPS 서버를 `https://localhost:8443`에서 실행해 두었고 앱도 Pleos 화면에 열어 두었다. 정차 상태에서 **정지 → 새로 만들어 듣기**로 공개 새 대본을 들을 수 있다. 다시 요청한 뒤 준비 중 **정지**를 눌러 늦게 재생되지 않는지도 확인할 수 있다. 실제 음질·대본 내용의 자연스러움은 사용자가 확인할 항목이며, 수동 확인 전에도 위 자동/기기 검증은 완료된 상태다.

서버가 종료된 경우 프로젝트 루트에서 다음 명령으로 재실행한다. 인증서·암호화 기기 설정·키는 보존돼 있다. provider 검토 만료는 **2026-11-08T12:47:34Z**이며 만료 후에는 기존 검토 절차를 다시 수행한다.

```sh
PYTHONPATH=server /private/tmp/petasos-server-venv/bin/python server/dev-local-https.py serve --port 8443
```

마이크 테스트는 아직 요청하지 않는다. 기존 SDK 원문 로그 및 입력 timing 문제를 해결한 뒤 사람 대화→답변→복귀→요약을 검증한다.
