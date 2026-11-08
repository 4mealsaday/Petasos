# dev14 설치 콘텐츠의 지역·관심사 자동편성

작성: 2026-11-08. **0.8.0-dev.14 최종 privacy 앱·테스트 APK 빌드·lint, JVM 393개/31 suite, 호스트 112개, 기기 12종과 알려진 앱 SDK 로그 sink 정적 검사가 통과했다. 기본 debug 호환·privacy 설치/실행 smoke 및 최종 기기·소스 확인도 완료했다.** [최종 집계](../../artifacts/milestone-13/verification.json)는 `COMPLETE_WITH_SPEECH_PRIVACY_BLOCKER`이며 별도 서비스 로그 문제가 해결됐다는 뜻은 아니다. 명시 재생 시작과 SDK Started보다 먼저 접수되는 Next의 자동 후보 제외 경합을 수정한 최종 소스 결과다. 초기 387개/12종 및 중간 390개 결과는 아래에서 별도로 보존하며 최종 숫자와 합산하지 않는다.

이번 범위는 이미 설치하고 검토한 공개 콘텐츠를 로컬에서 고르는 것이다. 외부 모델로 자동 대본을 생성하거나, 실제 랜드마크 접근 정책을 활성화하거나, 음성 명령의 `answer.action` 지원 범위를 늘리지 않는다. v0.8 명세·Room schema·생성 API 계약을 변경하지 않는다.

## 계약과 구현 범위

[동작명세](../Petasos_동작명세.md)의 RUN-02/05/07, [데이터 계약](../Petasos_데이터_API_계약.md)의 DATA-10/12, [검수명세](../Petasos_검수명세.md)의 A03/A04/A14가 기준이다. 사용자의 명시적 콘텐츠 선택은 일반 자동편성보다 우선하며, 자동 후보가 생겨도 진행 중인 곡을 끊지 않는다.

| 경계 | 이번 구현 |
| --- | --- |
| 편성 순서 | 유효하고 신선한 현재지역의 미청취 콘텐츠 → 사용자가 직접 저장한 관심 태그와 일치하는 콘텐츠 → 허가된 음악 또는 대기 |
| 관심사 | `preference.keywords_json`과 `story.interest_tags_json`의 정확한 문자열 교집합. 대소문자·공백·유사어를 추정해 합치지 않고 발화나 요약으로 관심사를 자동 변경하지 않음 |
| 위치가 없거나 unknown | 지역 후보를 제외. 관심사 후보는 위치 권한·동의·현재지역과 독립적으로 선택 가능하며 위치 기록을 시작하지 않음 |
| 버전과 권리 | 각 story의 최신 `published` 버전만 자동 후보로 사용. 현재 만료·공개 상태·문단 해시·출처의 검토 및 display/distribution 권한을 재검사. 로컬 공개 콘텐츠 재생에 generation 권한까지 요구하지 않음 |
| 반복 방지 | 전체 차량 DB에서 한 문단이라도 completed attempt가 있는 story를 제외하고, 완료 문단에 연결된 stable `factId`도 제외. story 전체 완료·카드 획득 조건과 다르며 새 story version 또는 다른 story의 다른 fact revision에도 적용 |
| 중단과 재생 선택 | interrupted 이력만으로 전역 완료를 주장하지 않음. 명시적 다시 듣기와 콘텐츠 선택에는 자동편성 반복 필터를 적용하지 않음 |
| 자동 후보 수명 | 선택 당시 snapshot은 권한의 영구 보증이 아님. 교체·철회된 offer와 늦은 Started callback을 구별하며 실제 시작된 stable story는 현재 bridge 세션에서 다시 자동 제안하지 않음 |
| 읽기 부작용 | 후보 조회로 preference/trip/session/재생 시도/위치점/개인 대화 payload를 만들지 않음 |

## 저장소와 실행 연결

[RoomAutomaticStoryStore](../../app/src/main/java/com/petasos/app/storage/RoomAutomaticStoryStore.kt)는 `candidates(currentRegionId, excludedStoryIds = emptySet())`와 `isCurrent(candidate, currentRegionId)`를 제공한다. 현재지역 후보를 먼저 정렬하고 각 그룹 안에서는 story ID 순서를 사용한다. 최대 64개는 세션에서 실제 시작했거나 명시 Next로 건너뛴 stable story ID를 제외하고 태그·권리 검사까지 통과한 결과에 적용한다. 먼저 64개 DB 행을 자른 뒤 태그를 검사하거나, 64개 결과를 받은 뒤 시작 이력을 제외하여 65번째 유효 후보가 계속 누락되는 방식을 사용하지 않는다. Bridge는 실제 Started 또는 접수된 Next에 따른 stable ID 제외 집합을 변경 불가능한 복사본으로 전달하고 저장소도 자기 복사본을 사용한다. 실제 Started에는 자동 offer뿐 아니라 명시 선택·명시 재생과 이미 승인된 이어 듣기도 포함한다. 자동 offer의 시작은 정확한 offer token으로 확인하고, offer 없는 실제 story 시작은 별도 observer 경로로 세션 제외 집합과 대기 후보를 갱신한다. 따라서 완료 문단이 생기기 전 Next를 눌러도 방금 시작한 이야기가 자동 후보로 다시 선택되지 않도록 한다. 명시적인 다시 듣기는 계속 허용한다. SDK Started가 아직 처리되지 않은 동안 사용자가 Next를 눌렀다면 그 실제 시작을 추정하지 않는다. 접수된 Next 자체를 별도의 세션 자동후보 제외 사유로 전달하며, 이는 completed 청취·카드 획득 증거를 만들지 않는다. `onStorySkipped`를 상태 전이 observer의 재조회보다 먼저 처리하고 대기 중 같은 이야기를 철회한다. 거부된 Next나 늦은 callback에 동일한 권한을 주지 않으며 해당 최종 JVM 회귀는 통과했다. 새 `RuntimeAutomaticSkipTest`는 Connected/Started 전 Next 후 다음 이야기 선택과 명시 replay, 대기 중 Started의 fence 거부, 거부된 Next의 무변경을 각각 검사한다.

후보의 `eligibilityKey`는 story 메타데이터·버전 상태를 묶은 opaque fingerprint다. INTEREST 후보에는 명시 keywords와 preference revision도 포함한다. 설정 revision이나 태그·메타데이터가 바뀌면 기존 key는 무효이며, CURRENT_REGION 후보의 key에는 관심 설정을 넣지 않는다. 손상되거나 삭제된 관심 설정은 관심 후보를 허가하지 않는다. 유효한 지역 후보는 그 설정과 독립적으로 유지한다. 저장소 오류는 빈 후보 또는 false로 닫고 오류 내용·관심 문자열을 로그에 남기지 않는다.

`isCurrent`는 현재 버전과 메타데이터뿐 아니라 현재 완료 문단 이력과 출처 권리도 다시 조회한다. 공개 대본의 권리·해시 검사는 기존 `RoomContentInstaller.publishedStory`를 재사용한다. 개인 대화의 pending 원문·저장 요약·정밀 위치점을 편성 입력으로 읽지 않는다. 기존 `RoomLocationStore.currentRegionStories`는 이 조회에 위임해 지역용 반복 방지 SQL을 중복 유지하지 않는다.

[AutomaticStoryPlaybackBridge](../../app/src/main/java/com/petasos/app/planning/AutomaticStoryPlaybackBridge.kt)는 검증된 후보를 Coordinator의 offer/withdraw 명령에 연결한다. DB 조회 결과를 직접 재생하지 않는다. 지역은 검증된 같은 시계 범위의 위치가 10초 이내일 때만 전달하고, 최종 권한 검사에서도 다시 신선도를 확인한다. 관심 후보에는 위치가 오래됐다는 이유만으로 지역 신선도 조건을 추가하지 않는다.

Host는 명시 관심사 저장, 완료 재생 커밋, 위치·내비·상태 변화 후 편성을 새로 조회한다. 관심·위치 설정 변경은 worker의 저장 순서를 기다리기 전에 ingress에서 기존 offer 권한을 즉시 철회한다. 저장 성공 또는 실패 후 현재 상태로 다시 조회하므로 변경 대기 중의 옛 권한으로 출력하지 않는다. 관심 설정을 저장해도 재생 의도가 없는 앱에서 자동으로 새 세션이나 출력을 시작하지 않는다. offer 교체 시에도 기존 권한을 먼저 철회하며, 출력 직전에는 정확한 offer/story/version/paragraph와 현재 DB 적격성을 함께 검사한다.

## 음악 간격과 SDK 연결 대기 경계

Coordinator에는 이야기의 정상 완료 뒤 다음 자동 이야기를 허용하기 전에 음악 한 곡의 **정상 완료 커밋**을 요구하는 gate를 연결했다. 음악을 건너뛰거나 중단한 것을 정상 완료로 취급하지 않는다. 이미 음악을 시작한 경우에도 정상 완료 전에는 이 gate를 해제하지 않는다. 미완료 이야기의 명시 Next는 별도 사용자 요청으로 다른 허용 후보를 선택할 수 있다. 명시적 콘텐츠 선택·다시 듣기는 별도 사용자 의도로 유지한다. 실제 production 음악 catalog는 아직 비어 있으므로 이번 구현만으로 실제 음악 구간이 재생되지는 않는다. 허가된 음악이 없으면 자동 이야기를 연속으로 재생하여 이 간격을 우회하지 않고 대기한다. 최종 코어 합성 회귀는 통과했으며 실제 제품 음악 자산은 여전히 없다.

음악의 정상 완료가 focus 차단이나 대화 처리 중에 커밋되어 복귀 checkpoint가 없는 경우에는 다음 편성으로 넘어갈 수 있다. 반대로 실제 복귀 후보가 있었는데 모델이 `wait`를 선택한 경우, 늦게 도착한 자동 offer로 그 대기를 덮어쓰지 않는다. 빈 후보의 결정적 fallback과 유효 후보에 대한 명시적인 `wait`를 구분한다. 복구 checkpoint를 넣어 초기화했을 때 음악 간격을 요구하는 회귀는 **코어 생성자 테스트 범위**다. 실제 Room 복구를 거친 음악 간격의 지속까지 검증했다고 주장하지 않는다.

SDK가 연결될 때까지 기다리는 동안 설정이나 Stop이 바뀔 수 있으므로 출력은 `Connected → worker에서 최신 본문·권리·offer 현재성 읽기 → main thread에서 가벼운 permit 검사 → speak` 순서로 연결했다. 연결 전에 읽은 본문을 보관해 나중에 출력하지 않는다. `RuntimeSpeechPermit`의 마지막 검사에는 해당 시도의 허가, Stop fence, 닫힘·차단 상태, 정확한 offer와 지역 신선도가 포함된다. main thread에서는 원자적/volatile 상태와 가벼운 offer 검사만 읽으며 Room이나 네트워크를 호출하지 않는다. Connected 자체는 출력 허가가 아니다.

Stop 또는 설정 변경으로 권한이 철회되면 연결 callback이나 준비된 permit이 뒤늦게 와도 `speak`를 호출하지 않는다. 초기화만 끝났고 실제 출력 요청을 제출하지 않은 시도는 그 사실에 근거해 정지 처리한다. 이미 출력한 시도를 타이머나 연결 종료만으로 조용해졌다고 판단하는 규칙으로 확장하지 않는다. 이 연결 코드의 초기 합성 지연·철회 JVM 및 runtime TTS 기기 회귀는 통과했지만 실제 SDK 연결 대기 중 모든 변경을 주입한 검증으로 확대하지 않는다. 최종 JVM과 runtime TTS 기기 회귀는 통과했다.

## 초기 검증과 최종 재검증 상태

초기 검증 후 검토에서 명시 선택한 이야기 A가 아직 완료 문단을 만들기 전에 Next를 누르면 대기 중인 자동 후보 A가 다시 선택될 수 있음을 발견했다. 자동 재생만이 아니라 모든 유효한 story Started를 세션 제외에 반영하고 같은 stable story의 대기 후보를 철회하도록 보완했다. 중복·늦은 Started, 다른 버전의 동일 이야기, 명시 replay를 다루는 회귀를 추가했으며 최종 실행 결과는 아래 초기 결과와 분리한다. 두 경합 수정 뒤 동결한 225개 소스는 [최종 소스 snapshot](../../artifacts/milestone-13/source-sha256.json), 명시 Started 보완의 6개 파일 차이는 [시작 이력 수정 목록](../../artifacts/milestone-13/explicit-start-fix-source-changes.json), 이어진 Next 보완은 [Next 경합 수정 목록](../../artifacts/milestone-13/next-race-fix-source-changes.json)에 기록했다. 최종 pipeline은 2026-11-08 03:18:18Z에 시작했으며 privacy 빌드 → 정적 감사 → 기기 12종 → debug 호환 → privacy 설치·실행 smoke 순서로 검증했다. 최종 JVM XML은 393개/31 suite, failures/errors/skipped 0으로 확인했다. privacy 빌드 전체·lint, 정적 감사와 기기 12종, debug 호환·smoke·최종 상태 확인 모두 통과했다.

다음 초기 검증표의 PASS는 모두 **명시 재생 시작 이력 수정 전** 결과다. 초기 증거는 `artifacts/milestone-13/before-explicit-start-fix/`, APK는 `.local/builds/dev14/privacy-before-explicit-start-fix/`에 보존한다. 최종 소스에 이 숫자를 그대로 적용하지 않는다.

| 초기 검증 | 결과와 범위 |
| --- | --- |
| canonical SQLite schema의 조회 SQL 2개 prepare | PASS. Android Room 실행을 대신하는 검사가 아님 |
| privacy 앱·instrumentation APK·JVM·lint | PASS, JVM 387 tests / 30 suites, failures/errors/skipped 모두 0, lint issue 0 |
| 기본 debug 앱·instrumentation APK·lint | PASS. 초기 debug 빌드의 결과이며 debug JVM 재실행을 뜻하지 않음 |
| `automatic-planning` | PASS, 고정 mark 9개. 실제 Room과 합성 콘텐츠·scripted runtime이며 실제 오디오/GPS/마이크/모델 없음 |
| `location-storage`, `vehicle-preferences` | PASS. 실제 Room의 합성 위치·경계·proof 및 명시 설정/CAS. 실제 위치 SDK나 수집 동의 변경 시험 아님 |
| `runtime-scripted`, `resume-context`, `session-recovery` | PASS. 실제 Room/Keystore와 합성 입력. 실제 OS Suspend나 음악 간격의 Room 복구 지속 검증 아님 |
| `runtime-tts`, `runtime-tts-stop` | PASS. 합성 공개 대본의 실제 Pleos TTS 완료/Stopped callback과 Room 커밋. 음향적 무음 검증 아님 |
| `music-runtime` | PASS. 실제 Media3/Room과 시험용 합성 음원. 제품 음악 자산 검증 아님 |
| `app-trip-lifecycle` | PASS. 제품 서비스에서 공개 이야기의 Started 저장, 정지 ACK 후 이동 종료, 다음 명시 Play의 새 이동, 결과 읽기와 설정 보존. 가청 품질·음향적 무음·위치·사람 발화·모델 시험 아님 |
| `car-policy-ui`, `media-session` | PASS. 실제 Car UX 읽기와 합성 visibility, 실제 MediaController 명령 전달·제품 browse와 입력 gate. 실주행·실오디오 검증 아님 |
| 알려진 앱 SDK 로그 sink 정적 검사 | PASS. 알려진 DEBUG/stacktrace 경로 부재, STT raw INFO helper 호출 0, 모델/제어 참조 보존. 별도 서비스·전체 taint·기능 실행 증명이 아님 |

초기 [빌드 결과](../../artifacts/milestone-13/before-explicit-start-fix/build-result.json), [빌드 로그](../../artifacts/milestone-13/before-explicit-start-fix/build.log), [12회 기기 실행 목록](../../artifacts/milestone-13/before-explicit-start-fix/probes/run-results.json), [앱 SDK 정적 감사](../../artifacts/milestone-13/before-explicit-start-fix/privacy-apk-audit.json)를 읽어 확인했다. 12개 개별 JSON은 같은 앱·테스트 APK 조합을 기록했다.

| 초기 APK | SHA-256 |
| --- | --- |
| privacy 앱 | `f752b83491fa9da7b6236d143acf2de1dcc1b62d43ab1c22024479191fc8e04c` |
| privacy 테스트 | `33b995bfb087fee7138e5bb238ea106411b4ff2435002730d9a9878fa6cb3996` |

명시 Started 보완 뒤의 중간 privacy 빌드도 exit 0이며 JVM 390개/30 suite, failures/errors/skipped 0과 lint PASS를 확인했다. 이는 다음 Next 경합 보완 **이전** 결과로 [중간 빌드 기록](../../artifacts/milestone-13/before-next-race-fix/build-result.json)과 `before-next-race-fix/junit/`, `.local/builds/dev14/privacy-before-next-race-fix/`에 보존했다. 중간 APK에서는 기기 probe를 실행하지 않았다.

별도 SpeechService 로그는 이번 12회에서 다시 감사하지 않았다. dev13의 서비스 원문 로그 blocker는 그대로이며 정적 앱 검사 PASS로 해제하지 않는다. localhost HTTPS는 기기 probe와 별도로 기존 설정으로 재시작하고 2026-11-08 03:12:54Z에 TLS health 200을 확인했다([상태 확인](../../artifacts/milestone-13/local-server-check.json), [재시작](../../artifacts/milestone-13/server-restart.json)). 새 모델 호출이나 provider 재승인 검증이 아니며 키·토큰·설정·검토 만료를 변경하지 않았다. 최종 03:25:49Z 확인에서도 HTTPS health 200과 기존 localhost:8443 adb reverse를 확인했다([최종 기기 상태](../../artifacts/milestone-13/final-device-state.json)).

최종 수정본의 현재 판정은 초기 결과와 별도로 다음과 같다. [최종 빌드 결과](../../artifacts/milestone-13/build-result.json), [APK 보존 기록](../../artifacts/milestone-13/final-apks.json)은 초기·중간 빌드와 구별하며 최종 privacy APK 쌍은 `.local/builds/dev14/privacy-final/`에 보존한다.

| 최종 검증 | 상태 |
| --- | --- |
| 시작/Next 수정 뒤 JVM | PASS: 393 tests / 31 suites, failures/errors/skipped 모두 0 |
| privacy 전체 빌드·lint | PASS, 2026-11-08 03:21:53Z 완료. lint issue 0 |
| 정적 감사·기기 12종 | PASS. 아래 최종 APK 쌍에서 12/12, 알려진 앱 SDK sink·reflection/제어 참조 검사 통과. 서비스 로그 재감사는 아님 |
| 호스트 도구 회귀 | PASS: 112 tests, 원본 stdout 보존. Android·서버 테스트와 별도 집계 |
| 기본 debug 앱·instrumentation APK·lint | PASS, 03:24:30Z 완료. debug JVM은 별도 재실행하지 않음 |
| privacy 설치·실행 smoke | PASS, 03:24:33Z 완료. 최종 앱 APK 설치·전면 실행과 crash 지표 0 확인; 통합 SDK 시험은 별도 |
| 최종 기기·소스 상태 | PASS, 03:25:49Z 확인. 설치 APK 해시 일치·앱 전면, 앱/서비스 active recording configuration 각각 0, reverse·HTTPS health 200. 동결 소스 225개 변경·미기록 파일 0 |

최종 [기기 실행 목록](../../artifacts/milestone-13/probes/run-results.json)과 각 JSON은 초기와 같은 12종을 같은 최종 APK 조합으로 검사했음을 기록한다. [automatic-planning](../../artifacts/milestone-13/probes/automatic-planning/automatic-planning-privacy.json)은 9개 고정 mark를 통과했다. [최종 앱 SDK 정적 검사](../../artifacts/milestone-13/privacy-apk-audit.json)는 알려진 sink와 모델/제어 참조를 확인한 범위이며 별도 서비스·전체 taint·모든 SDK 모드의 실행 검증이 아니다. runtime-tts-stop의 probe 전체 소요시간을 제품 stop 지연이나 음향적 무음 확인 시간으로 해석하지 않는다.

| 최종 privacy APK | SHA-256 |
| --- | --- |
| 앱 | `a0b8c888d8885273626733c32faa35075d60f1b9d13b1b94d097bae45161bcac` |
| 테스트 | `33b995bfb087fee7138e5bb238ea106411b4ff2435002730d9a9878fa6cb3996` |

[AutomaticPlanningProbe](../../app/src/androidTest/java/com/petasos/app/storage/AutomaticPlanningProbe.kt)는 별도 임시 DB에서 다음을 확인하도록 작성했다. production DB·키를 지우지 않으며 마이크·실제 스피커·GPS SDK·네트워크·모델을 사용하지 않는다. 출력은 고정 mark와 판정뿐이다.

- 관심 설정이 없는 조회의 무변경, 현재지역 우선, 정확한 태그 일치, 위치 없는 관심 후보.
- 관심 설정의 변경·삭제·손상, 태그·메타데이터·지역 분류 변경에 따른 이전 offer 무효화.
- 권리 철회, 만료·retire, 문단 해시 변경, 최신 published version 교체.
- 이전 trip/session의 completed story 및 다른 fact revision의 같은 stable fact 제외, interrupted 이력과 명시 재생의 분리.
- 64개보다 많은 앞선 비일치 콘텐츠 뒤의 유효 태그 후보 발견.
- 앞의 적격 64개가 세션 Started 제외 집합에 있을 때 65번째부터 반환하고, 그 상태에서도 태그·권리 검사를 유지.
- 실제 Room의 `registerOutput` 뒤 설정·권리·완료 이력·태그를 바꾼 네 경합에서 합성 `speech.startOutput` 0회, 준비 중 시도의 terminal 처리와 정상 세션 종료.

명시 Started 보완 후 빌드 역시 SDK Started 반영 전 Next 경합 보완 이전의 중간 결과로 구분한다. 최종 [debug 호환](../../artifacts/milestone-13/debug-build-result.json), [privacy smoke](../../artifacts/milestone-13/smoke-result.json)와 [설치·실행 확인](../../artifacts/milestone-13/privacy-smoke/verification.txt), [기기 상태](../../artifacts/milestone-13/final-device-state.json), [225개 소스 일치](../../artifacts/milestone-13/final-source-check.json), [호스트 112개](../../artifacts/milestone-13/host-tests.json)를 별도로 기록했다. 기기의 녹음 구성 0은 해당 시점의 읽기 관측이며 요청별 입력 종료 ACK나 연속 감시 증거로 사용하지 않는다. 서버 177개·참조 계약 13개는 dev11 기록을 보존하고 이번에 재실행하지 않았다. 초기 dev14 또는 과거 dev13 검증을 최종 변경의 검증 결과로 재사용하지 않는다.

## 유지하는 제한과 후속 범위

- 외부 story 생성은 기존 정차 상태의 명시적인 “새로 만들어 듣기” 경로만 유지한다. 이번 자동편성은 이미 설치된 콘텐츠만 대상으로 한다.
- 실제 지역 경계·위치 품질 의미와 랜드마크 접근 정책이 검증되지 않은 환경에서는 해당 자동 지역/랜드마크 경로를 활성화하지 않는다. 임의 반경·방향·통과 규칙을 검증 완료 값으로 넣지 않는다.
- canonical `answer.action` 중 현재 제품의 `continue/unsupported` 이외 명령 라우팅과 별도 개인 연결 문장 출력 수명은 후속이다. 새로운 `intent` API task를 만들지 않는다.
- 실제 음악 음원은 권리·해시가 검증된 catalog가 필요하다. 현재 비어 있는 catalog를 합성 음원으로 대체하지 않는다.
- 별도 SpeechService 원문 로그 문제 때문에 제품 마이크 capability는 계속 비활성이다. [dev13 감사](2026-11-08-speech-client-audit.md)의 제한과 첫 발화 5초/Final 500 ms 계약을 유지한다. 사람 발화 시험을 요청하지 않는다.
- Virtual Console 연결은 보류하며, UI 외형·스타일·데모 가상 시나리오는 이번 단계에서 변경하지 않는다.
