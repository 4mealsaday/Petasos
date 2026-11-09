# Petasos

지금 지나가는 지역의 이야기를 함께 듣고, 원할 때 대화에 참여하는 Pleos Android 앱.

최신 dev.22: [주요 랜드마크 후보와 음악 간격 복원](./docs/development/2026-11-08-landmark-and-recovery.md). 검토 근거·실제 접근 조건을 만족하는 후보 하나를 기존 자동 편성에 연결하고, 앱 재시작 후 이야기 사이 음악 조건을 복원한다. JVM **768개**, 호스트 **158개**, privacy 빌드/Lint·동일 APK 기기 **10종**·설치/실행 PASS다([집계](./artifacts/milestone-22/verification.json)). 실제 위치 의미·정책/콘텐츠·활성 경로 공급과 사람 마이크 검수는 남아 있다.

이전 dev.21: [공식 ON_DEVICE EOF 검증](./docs/development/2026-11-08-speech-eof-verification.md). 실제 EOF·완료 요청·SDK 콜백 순서 2회와 합성 STT 4회 기능/시간 기준을 확인했다. JVM **725개**, 호스트 **156개**, privacy 빌드/Lint·기기 회귀 **4종**·설치/실행 PASS다. 별도 SpeechService 문장 로그는 남아 있어 사람 마이크는 보류한다([집계](./artifacts/milestone-21/verification.json)).

이전 dev.20: [검토된 이야기–지역 연결](./docs/development/2026-11-08-reviewed-regional-content.md). bundle v2, 연결 근거의 현재 권한 검사와 데이터 보존형 Room 1→2 마이그레이션을 구현했다. JVM **725개**, 서버 **208개**, 호스트 **143개**, privacy 빌드/Lint·동일 APK 기기 **9종**·설치/실행 PASS다([집계](./artifacts/milestone-20/verification.json)). 실제 지역 seed·방문 정책·실경로 통합은 남아 있다. 위치·음성은 [공식 API 진행 경로](./docs/development/2026-11-08-official-only-platform-path.md)로 이어가며 업체 연락을 전체 개발의 선행 조건으로 요구하지 않는다.

이전 dev.19: [위치 신선도와 경로 변경 권한](./docs/development/2026-11-08-location-route-freshness.md). 오래된 측정값이 수신 후 새 유효 시간을 얻지 않도록 수정했고, 오류 후 저장 대기 데이터나 늦은 경로 콜백이 자동 편성을 되살리는 경합을 막았다. JVM **714개/52 suite**, privacy 빌드/Lint·기기 5종·설치/실행이 통과했으며 dev.19를 에뮬레이터에 실행해 두었다([집계](./artifacts/milestone-19/verification.json)). 공식 위치 의미·전체 활성 도로 경로·방문 정책이 남아 있어 실제 지역 자동 청취는 아직 보류다.

dev.18: [공식 지역 경계 설치](./docs/development/2026-11-08-official-region-boundary-import.md). 제공된 NGII ZIP/PDF를 검토해 264개 시·군·구를 변환하고 실제 앱에 연결했다. 261개 형상이 정상이며 원본 오류 3개는 격리한다. 전국 좌표 전체를 포함한 JVM **662개**, 호스트 **137개** 검사를 통과했다. privacy 빌드/Lint·기기 6종·설치/실행·출처 UI도 통과했고 당시 dev.18 설치·실행을 확인했다([집계](./artifacts/milestone-18/verification.json)). 실제 위치 품질·방문 정책·활성 경로 계약이 남아 있어 실주행 자동 지역 안내는 아직 활성화하지 않았다. 기존 출처 화면에 자료 출처·CC BY 링크·변환 안내를 추가했다.

제품 구현 기준은 [v0.8 명세](./Petasos_기획서.md)다. 현재 앱은 정차 중 확인하는 개발 프리뷰이며 설치 이야기와 재생·정지·다음·이전·말하기 제어를 단일 Coordinator에 연결한다. 앱 시작·미디어 목록 조회는 재생·마이크·위치 기록을 시작하지 않는다. UI 디자인과 데모 시나리오는 보류하고 기능 계층을 먼저 개발한다.

dev.17: [이야기 길이 기반 지역 편성](./docs/development/2026-11-08-duration-aware-regional-planning.md)을 추가했다. 생성 길이·현재 문단 잔여·음악 길이로 다음 지역을 고르고 대화, 지연, 경로/실제 위치 변화에 따라 재계산한다. 검토된 seed의 자동 생성·설치·후보 갱신을 연결했고 기본 Play의 첫 이야기 강제 선택을 제거했다. 실제 경로 예측 공급자와 위치 품질·지역 경계가 아직 준비되지 않아 실경로 자동 재생은 보류 상태다. JVM **599개**, 호스트 **117개**, 기기 회귀 **15종**, privacy·debug 빌드/Lint와 설치·실행이 통과했다([집계](./artifacts/milestone-16/verification.json)). 메모리 부족 후 단일 저메모리 빌드로 복구했으며 빌드와 에뮬레이터를 함께 실행하지 않는다. 제품 마이크와 Virtual Console 상태는 유지한다.

dev.16: [검토 음악과 자동 공급](./docs/development/2026-11-08-reviewed-music.md)을 연결했다. CC BY 4.0의 「Prelude No. 1」 원본 1곡, 출처 표시, 실제 이야기→음악 전환·음악 정지/위치 재개·정상 종료 후 다음 출력과 재생 직전 권리 재검사를 구현했다. 최종 privacy·debug 앱/테스트 APK 빌드와 Lint, JVM **502개/40 suite**, 호스트 **117개**, 동일 최종 APK의 기기 회귀 **13종**, 알려진 앱 SDK 로그 sink 정적 검사, privacy 설치·실행 smoke와 실제 출처 창 표시/비가시화 닫힘을 통과했다. 247개 소스와 최종 기기 상태는 [집계](./artifacts/milestone-15/verification.json)에 남겼다. Spotify 공식 Pleos Connect 지원과 Petasos의 직접 제어는 별개이며 P1 외부 음악 복귀로 유지한다. 제품 마이크는 계속 비활성이다.

dev.15: [명시 재생 명령의 로컬 처리](./docs/development/2026-11-08-explicit-playback-commands.md)를 추가했다. 저장·입력 종료 확인을 마친 명확한 전체 발화에 한해 정지/계속/건너뛰기/다시 듣기/음악을 처리한다. 사용자-only 요약 정책과 권리 재검사를 유지하며 대상 없음은 안내 후 정지한다. UI와 서버 API 계약은 유지한다. 최종 privacy·debug 빌드/Lint, JVM **435개/34 suite**, 호스트 **115개**, 기기 **12종**과 설치·실행 smoke가 통과했다. 230개 소스와 최종 기기 상태도 확인했다([집계](./artifacts/milestone-14/verification.json)). **제품 마이크는 기존 서비스 로그 문제로 계속 비활성**이며 실제 사람 발화 시험은 포함하지 않는다.

dev.14: [설치 콘텐츠의 지역·관심사 자동편성](./docs/development/2026-11-08-automatic-story-planning.md)을 추가했다. 현재지역 다음에 명시 관심 태그가 정확히 일치하는 콘텐츠를 고르고, 최신 버전·권리·완료 story/fact와 세션 시작·명시 건너뛰기 이력을 출력 직전까지 재검사한다. SDK Connected 뒤 최신 본문을 읽고 Stop·설정 변경을 다시 검사한다. 이야기 정상 완료 후 다음 자동 이야기는 음악의 정상 완료가 필요하며, 음원 카탈로그가 비어 있으면 대기한다. 시작/Next 경합까지 수정한 최종 privacy 빌드·Lint, JVM **393개/31 suite**, 기기 **12종**을 통과했다. 호스트 **112개**, 기본 debug 앱·테스트 APK·Lint, privacy 설치/실행 smoke와 최종 기기·225개 소스 일치도 확인했다. [최종 집계](./artifacts/milestone-13/verification.json)에 APK와 시험 범위를 구분했다. 외부 자동 생성·실제 랜드마크 정책은 후속이며 제품 마이크 차단·기존 UI 외형을 유지한다.

dev.13: [음성 privacy 빌드·합성 검증](./docs/development/2026-11-08-speech-privacy-verification.md)을 마쳤다. opt-in R8 빌드에서 확인한 앱 내 Speech SDK 로그 경로를 제거했고 빌드·Lint, JVM 348개/27 suite·호스트 108개·기기 회귀 19종·설치/실행 smoke를 통과했다. 별도 합성 STT/TTS 8회는 기능 PASS였지만 **Pleos Speech 서비스에서 시험 문장이 검출되어 제품 마이크는 계속 차단**한다. 기본 debug 빌드를 유지하며 localhost 개발 CA는 debug와 opt-in privacy에만 적용한다. 최종 테스트 APK 변경 범위와 관측 한계는 [집계 기록](./artifacts/milestone-12/verification.json)에 있다.

dev.12: [기록 설정·이동 종료·결과 조회](./docs/development/2026-11-08-trip-records.md)를 추가했다. 위치 동의와 플랫폼 권한을 분리하고 수동 관심사·최근 이동·저장된 요약과 수집 증명을 연결했다. 이동 종료는 실제 정지 확인과 저장 이후 완료하며 다음 재생은 새 이동에서 시작한다. 빌드·Lint, JVM 348개/27 suite·호스트 55개·기기 10종·설치/실행 smoke를 통과했다. 9종과 마지막 `trip-lifecycle`의 테스트 APK 차이, 보존 APK와 이전 실패는 [검증 기록](./artifacts/milestone-11/verification.json)에 구분했다. 서버 177개·참조 계약 13개는 dev.11 기검증이며 이번에는 재실행하지 않았다. dev.12 기능과 당시 APK/검증 기록을 보존하며 실제 마이크는 계속 비활성이다.

dev.11: [공개 이야기 생성·대화 후 복귀](./docs/development/2026-11-07-story-resume-generation.md)를 연결했다. 정차 화면의 **새로 만들어 듣기**는 실제 OpenRouter 대본을 새 버전으로 저장한 뒤 재생하며, 준비 중 정지로 취소한다. 복귀는 허용된 같은 이야기/음악 후보와 3초 제한을 유지한다. 빌드·Lint, JVM 340개·서버 177개·호스트 55개와 기기 11종/12회 검증을 마쳤다. 최신 상태와 남은 단계는 [개발 인계](./DEVELOPMENT_HANDOFF.md)를 따른다.

2026-11-07 dev.10: [앱 통합·실제 생성 연결](./docs/development/2026-11-07-app-integration.md). 표준 Media3 세션, 음악 종료/위치 checkpoint, CarUxRestrictions·공개 전원 정책, TX-04 시작 복구, 위치/내비 기록과 후보 편성을 추가했다. OpenRouter의 승인된 ZDR 설정을 적용하고 실제 답변·요약을 호출했다. 개발 HTTPS는 localhost 전용이며 debug와 dev.13의 opt-in privacy APK만 개발 CA를 신뢰한다. 제품 시간 제한을 포함한 연속 PCM·사람 발화, 실제 주행/Suspend, 음악 자산·경계 데이터·완전한 편성 연결은 각각 별도 검증/준비 범위다. 당시 결과는 dev.10 기록에 보존하며 최신 상태는 [개발 인계](./DEVELOPMENT_HANDOFF.md)와 dev.14 기록을 우선한다.

**현재 실제 마이크 대화는 비활성이다.** privacy 앱의 한정된 관측에서 원문 일치는 0건이었지만 별도 Speech 서비스의 원문 로그 문제가 남아 있다. warm 합성 입력 성공은 cold 시작·실제 EOF·사람 발화·제품 전체 대화 시간 검증을 대신하지 않는다. 공식 설정/수정 버전/문의 창구를 확인 중이며 [문의 초안](./docs/development/pleos-speech-support-request.md)은 미전송이다. 서비스 로그와 입력 조건을 해결하기 전 사람 발화나 개인 답변 TTS를 시험하지 않는다. [최신 결과와 한계](./docs/development/2026-11-08-speech-privacy-verification.md)를 따른다.

2026-11-07 dev.9: [콘텐츠 거부 후 정리·요약 근거 재검증](./docs/development/2026-11-07-content-rights.md)을 추가했다. 콘텐츠 거부와 저장소 장애를 구분해 중단·turn 동결·세션 종료 기록을 남긴다. 요약은 현재 출처 권한과 전체 fact 연결을 생성 전후·저장에서 재검사하며, 권한 차단 시 암호문을 보존하고 명시적인 재검토 후에만 재개한다.

2026-11-07 dev.8: [설치 콘텐츠 재생 준비·답변 생성 문맥](./docs/development/2026-11-07-content-runtime.md)을 연결했다. 현재 세션의 완료 문단에서 검토된 근거를 만들고 재생/HTTP 직전과 응답 수락·저장에서 현재 권한을 재검사한다. 서버용 실제 근거도 등록 기기 범위로 내보냈으며 provider는 기존 비활성 상태다. 일반 UI는 프리뷰를 유지한다.

2026-11-07 dev.7: [실제 콘텐츠 검토·TX-05 설치](./docs/development/2026-11-07-content-install.md)를 추가했다. [출처 검토](./docs/development/2026-11-07-content-source-review.md)를 거친 가거도 최소 텍스트를 컴파일하고, 기존 revision 보존·재설치·원자 롤백과 준비 시 해시·권한 검사를 구현했다. UI는 그대로이며 좌표 미확인 콘텐츠를 자동 위치 안내에 사용하지 않는다.

2026-11-07 dev.6: [생성 연결·요약 TX-03·WorkManager 구현과 검증](./docs/development/2026-11-07-generation-summary.md)을 추가했다. 화면은 기존 프리뷰를 유지하며 실제 발화와 Virtual Console은 계속 보류한다. 서버 구성과 로컬 HTTPS fixture 준비는 [서버 README](./server/README.md)를 참고한다.

2026-11-07 dev.5: [실행 host·암호화 저장소와 통합 검증](./docs/development/2026-11-07-runtime-storage.md)을 추가했다. 실제 발화와 Virtual Console은 계속 보류한다.

2026-11-07 dev.4: [공식 API 전체 대조와 수정](./docs/development/2026-11-07-official-api-audit.md), [PlaybackCoordinator 구현 범위](./docs/development/2026-11-07-playback-coordinator.md)를 추가했다. STT Ended와 마이크 종료를 구분하고 TTS 해제·포커스 처리 및 CCP 선언을 보완했다.

2026-11-06: 최소 앱 실행, 위치 콜백, TTS 재생·중지, STT 합성 인식·취소·해제를 검증했다. STT의 비동기 stop/release 재연결 문제를 보완했으며 사람의 실제 발화는 사용자 요청으로 보류했다. [최초 실행](./docs/development/2026-11-06-bootstrap.md), [SDK 초기 검증](./docs/development/2026-11-06-sdk-probes.md), [STT 후속 검증과 한계](./docs/development/2026-11-06-stt-verification.md), [파일 구조와 UI 교체 경계](./docs/development/architecture.md)를 참고한다.

## 개발 환경

| 항목 | 고정값 |
| --- | --- |
| JDK | 17 |
| Gradle Wrapper | 8.11.1, 공식 배포 SHA-256 검증 |
| Android Gradle Plugin | 8.10.1 |
| Kotlin | 2.2.21 |
| KSP / Room | 2.2.21-2.0.5 / 2.8.5 |
| WorkManager | 2.12.0 |
| Media3 / NaviHelper | 1.11.1 / 3.0.5 |
| compileSdk / Build Tools | 36 / 35.0.0 |
| minSdk / targetSdk | 28 / 34 |
| 실행 대상 | Pleos Connect 2.1.0, Android 14 / API 34, ARM64 |
| applicationId | `com.petasos.app` |

설치된 SDK 36으로 컴파일하고 Pleos API 34를 동작 기준으로 삼는다. 이 조합은 [AGP 호환표](https://developer.android.com/build/releases/agp-8-10-0-release-notes)와 [Kotlin 호환표](https://kotlinlang.org/docs/gradle-configure-project.html)를 확인해 선택했다. [Pleos 공식 시작 가이드](https://document.pleos.ai/docs/pleos-only/getting-started)처럼 앱 빌드를 먼저 확인하고 SDK를 연결한다.

Lint는 경고도 실패로 처리한다. 고정 버전에 관한 `ExpiredTargetSdkVersion`, `OldTargetApi`, `AndroidGradlePluginVersion` 알림을 제외한다. `app/lint.xml`에는 APK에 포함하지 않는 특정 JNA x86_64 바이너리와 지원 대상이 아닌 ChromeOS ABI 검사에 한정한 예외가 있다. 현재 APK는 ARM64만 포함한다. SDK·이미지 교체 또는 지원 ABI 확장 시 예외를 다시 검토한다.

## 빌드

프로젝트 루트의 비추적 `local.properties`에 Android SDK 경로를 지정한다.

```properties
sdk.dir=~/Library/Android/sdk
```

macOS Homebrew JDK 17이 설치되어 있다면 다음 명령을 사용한다.

```sh
./scripts/gradle.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

다른 JDK 17 경로는 `PETASOS_JAVA_HOME`으로 지정한다.

```sh
PETASOS_JAVA_HOME=/path/to/jdk17 ./scripts/gradle.sh :app:assembleDebug
```

스크립트는 시스템 기본 Java 대신 JDK 17을 사용한다. 의존성은 `.gradle-user-home/`, debug signing 키는 `.local/android/`에 보관한다. 둘 다 Git 제외 대상이다. 첫 빌드에는 공식 저장소 연결이 필요하다. 생성 APK는 `app/build/outputs/apk/debug/app-debug.apk`다. opt-in `privacy`는 non-debuggable이며 별도 빌드 명령과 지원 probe는 [dev.13 재실행 안내](./docs/development/2026-11-08-speech-privacy-verification.md#재실행과-다음-조건)를 따른다. 기본 debug와 일반 release 설정을 대체하지 않는다.

Android Studio에서는 이 루트 폴더를 열고 **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**를 JDK 17로 선택한다. 이 머신의 Studio 내장 JBR 25나 시스템 Java 26으로 고정된 Gradle 8.11.1을 실행하지 않는다. Studio와 CLI는 동일한 `.local/android/debug.keystore`를 사용한다. 이 키를 교체하면 기존 앱의 업데이트가 거부될 수 있으므로 개발 중 보존한다.

## Pleos 설치·실행

실제 모델·공공데이터 연결에 필요한 키는 루트 `.env`에 입력한다. [키 입력 위치·공식 등록 화면·실제 API 검사](./docs/development/live-api-setup.md)를 따른다. `python3 scripts/check-live-apis.py`는 값 유무만, `--check`를 붙이면 OpenRouter 인증과 오디·국문 관광정보 1건을 실제 조회한다. 키 값이나 원문 응답을 출력하지 않는다.

오디 8개·국문 관광정보 15개 API의 [공식 전체 입출력 계약과 호출 방법](./docs/reference/public-data/README.md)을 정리했다. `python3 scripts/public-data-api.py list`로 지원 목록을, `describe`로 입력·출력 필드를 확인하며 `call`은 JSON/XML을 선택해 실제 GET 1회를 실행한다. [실제 23개 API 검증 결과](./docs/development/2026-11-07-live-apis.md)와 수집 후보는 제품 콘텐츠 설치·모델 품질 검증과 구분한다.

Android Studio Device Manager에서 기존 **Pleos Connect v2.1.0** 에뮬레이터를 부팅하고 실행한다.

```sh
./scripts/smoke-pleos.sh emulator-5554
```

스크립트는 Pleos/API 34 에뮬레이터인지 확인한 뒤 현재 사용자에 APK를 업데이트 설치하고, Activity 실행·프로세스·전면 화면을 검사한다. 스크린샷과 앱 로그는 기본적으로 `artifacts/milestone-1/`에 남긴다. 이전 증거를 보존하려면 `PETASOS_ARTIFACTS_DIR`에 다른 경로를 지정한다. `--build-type privacy`는 privacy APK를 사용하고 스크린샷/원본 로그 없이 crash count만 기록한다. Virtual Console 연결이나 `adb forward`는 필요하지 않다.

내비 위 Petasos overlay(버튼·카드)는 `SYSTEM_ALERT_WINDOW` 특수 권한이 필요하다. 에뮬레이터에는 이 권한을 끄고 켜는 설정 화면이 없으므로 설치 후 다음 명령으로 직접 허용한다(현재 사용자 ID가 10이 아니면 `--user`를 그 값으로 바꾼다):

```sh
adb shell appops set --user 10 com.petasos.app SYSTEM_ALERT_WINDOW allow
adb shell pm grant --user 10 com.petasos.app android.permission.SYSTEM_ALERT_WINDOW
```

이 Pleos 이미지에서는 `appops set`만으로는 부족할 수 있다 — `pm grant`까지 같이 실행해야 overlay가 확실히 뜬다.

## 위치·음성 SDK 검증

```sh
./scripts/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
python3 scripts/probe-pleos.py location
python3 scripts/probe-pleos.py tts-connect
python3 scripts/probe-pleos.py tts-speak
python3 scripts/probe-pleos.py tts-stop
python3 scripts/probe-pleos.py tts-close --artifacts-dir artifacts/milestone-3
python3 scripts/probe-pleos.py stt-connect
python3 scripts/probe-pleos.py stt-pcm
python3 scripts/probe-pleos.py stt-cancel
python3 scripts/probe-pleos.py stt-close
```

각 명령은 앱과 별도 테스트 APK를 현재 에뮬레이터 사용자에 설치하고 하나의 검증만 실행한다. `location`은 에뮬레이터에 위치 권한을 부여하고 두 합성 좌표를 주입한다. `tts-speak`/`tts-stop`은 시험 문장을 재생한다. `stt-pcm`은 마이크 대신 테스트 APK의 합성 PCM을 직접 전송한다. `stt-cancel`/`stt-close`는 게스트 입력을 잠깐 열어 취소·해제를 확인하며 호스트 마이크를 활성화하지 않는다. 같은 앱 PID가 살아 있는 동안 녹음 세션·서비스 연결을 검사한다. `stt-listen`과 `stt-owned-listen`은 사람 마이크를 여는 진단 도구이므로 **현재 실행 보류**다. 앱과 별도 Speech 서비스의 원문 로그 해결 및 입력 계약 검토 뒤 사용자 발화 준비를 확인한 경우에만 실행한다. `stt-ready`는 과거 Ended 콜백 가정을 재현하는 진단용이며 일반 통과 검사에서 제외한다.

`tts-close`는 재생 시작 후 close를 호출하고 동일 PID가 살아 있는 동안 CaaS 서비스 연결 해제를 세 차례 확인한다. 실제 스피커의 무음이나 PlayerActivityMonitor 종료까지 검증하지는 않는다.

결과는 기본 `artifacts/milestone-2/`의 probe별 TXT/JSON에 기록하며 재실행하면 같은 probe의 파일을 교체한다. `--artifacts-dir artifacts/milestone-3`처럼 경로를 지정해 이전 증거를 보존할 수 있다. 빌드와 probe를 동시에 실행하지 않는다. 결과에는 APK 해시와 이벤트·녹음/연결 상태 메타데이터만 남기고 실제 좌표·발화 원문·원음은 저장하지 않는다. PASS는 해당 검사 경로의 성공이며 음성 품질이나 전체 제품 검수의 성공을 의미하지 않는다. 타임아웃·실패 시 SDK 해제를 시도하고 대상 앱 프로세스를 종료한다.

## 실행 host·저장소 통합 검증

```sh
./scripts/gradle.sh :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
python3 scripts/generate-room-schema.py --check
python3 scripts/probe-pleos.py storage --artifacts-dir artifacts/milestone-4
python3 scripts/probe-pleos.py runtime-scripted --artifacts-dir artifacts/milestone-4
python3 scripts/probe-pleos.py runtime-tts --artifacts-dir artifacts/milestone-4
python3 scripts/probe-pleos.py runtime-tts-stop --artifacts-dir artifacts/milestone-4
```

`storage`는 실제 Room/SQLite·Android Keystore에서 제약 조건, 암호화, 실패 시 트랜잭션 rollback, 재열기, 키 손실을 검증한다. `runtime-scripted`는 실제 저장소와 시험용 음성·생성 결과를 연결한다. `runtime-tts`와 `runtime-tts-stop`은 실제 Pleos TTS 완료·중지 콜백과 DB 반영을 연결한다. 네 검사는 마이크나 생성 서버를 사용하지 않으며, 시험 APK의 합성 대본과 임시 DB·키만 사용한다. 시험 DB·키는 종료 시 정리한다.

Room 스키마는 `app/schemas/`에 내보내고, `SchemaConstraints.kt`의 버전별 제약 DDL을 함께 관리한다. `scripts/generate-room-schema.py`는 참조 SQL에서 entity·제약 매핑을 생성한다. 배포 후 스키마 변경에는 명시적 Migration이 필요하며 destructive fallback은 허용하지 않는다.

## 생성·요약 통합 검증

```sh
./scripts/gradle.sh :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain
python3 scripts/probe-pleos.py summary-storage --artifacts-dir artifacts/milestone-5
python3 scripts/probe-pleos.py summary-work --artifacts-dir artifacts/milestone-5
python3 scripts/probe-pleos.py summary-rights --artifacts-dir artifacts/milestone-8/summary-rights
```

`summary-storage`는 실제 Room·Keystore에서 동결된 입력의 claim, 완료된 응답만 요약에 포함하는 규칙, stale 결과 거부, 재시도·재시작 복구와 TX-03을 검사한다. TX-03은 요약 저장·saved 상태 반영·임시 암호문 삭제를 하나의 트랜잭션으로 처리한다. `summary-work`는 실제 WorkManager test driver와 Room을 연결해 고유 작업·재시도 예약·최종 저장을 검사하며, 생성 결과와 시계 진행은 시험용이다. Android의 실제 대기 시간이나 절전 중 실행 시점을 보장하는 검사는 아니다.

[서버 README의 검증 절차](./server/README.md#검증)로 로컬 HTTPS fixture를 실행한 상태에서 다음 명령을 사용한다.

```sh
python3 scripts/probe-pleos.py generation-https \
  --https-fixture-ready /private/tmp/petasos-tls-ready.json \
  --artifacts-dir artifacts/milestone-5
```

`generation-https`는 Android HTTPS 클라이언트에서 로컬 서버까지 실제 TLS로 연결한다. 기기 설정 암호화, 인증·응답 계약·멱등 재시도·취소와 HTTPS 요약 결과의 Room TX-03 반영을 검사한다. 스크립트가 일회용 CA·토큰을 앱의 시험 전용 경로에 전달하고 해당 fixture 포트의 `adb reverse`를 생성·정리한다. 제품 인증서 검증을 우회하지 않으며 이 연결은 Virtual Console과 무관하다. 생성 응답은 합성 provider가 만들고 외부 모델이나 마이크는 사용하지 않는다. 임시 DB·키·토큰은 종료 시 정리하며, 실행 결과와 한계는 [dev.6 보고서](./docs/development/2026-11-07-generation-summary.md)에 기록한다.

## 구조와 다음 순서

- `app/src/main/res/`: 임시 화면의 색상·타이포·치수·배치를 분리한 리소스.
- `app/src/main/java/com/petasos/app/PetasosApplication.kt`: WorkManager 설정과 동결된 요약 작업의 시작 복구·예약.
- `app/src/main/java/com/petasos/app/playback/`: 순수 Kotlin 상태 전이, 이벤트·효과·확인 응답 계약.
- `app/src/main/java/com/petasos/app/runtime/`: 직렬 효과 실행, 타이머, 취소·저장 확인, 플랫폼 독립 포트.
- `app/src/main/java/com/petasos/app/presentation/`: 프리뷰 표시·허용 화면·사용자 명령 전달. UI 디자인과 실행 계층 분리.
- `app/src/main/java/com/petasos/app/records/`: 차량 설정과 읽기 전용 이동 결과 DTO·검증. 화면 binding과 Room 저장은 각각 presentation/storage에 둔다.
- `app/src/main/java/com/petasos/app/location/`, `planning/`, `music/`: 위치 품질·batch·지역 proof, 지역·명시 관심사 자동편성/복귀 검사, 검토 음악 카탈로그.
- `app/src/main/java/com/petasos/app/generation/`: HTTPS 클라이언트, strict JSON·스키마·상관관계 검증, 암호화된 기기 설정, runtime·summary 연결 어댑터.
- `app/src/main/java/com/petasos/app/summary/`: 플랫폼 독립 실행·저장소 계약·재시도 정책과 시작 복구 gate.
- `app/src/main/java/com/petasos/app/content/`: 검토 콘텐츠 묶음 계약·참조/허가/해시 검증.
- `app/src/main/java/com/petasos/app/storage/`: Room 스키마·Keystore 암호화·TX-01~05, 콘텐츠/위치/내비·현재 권한 조회와 시작 복구.
- `app/src/main/java/com/petasos/app/capability/`: UI와 SDK 타입에 독립적인 위치·음성 인터페이스.
- `app/src/main/java/com/petasos/app/platform/`: Pleos SDK·앱 소유 PCM 입력, Android 실행 host·WorkManager, `media/`의 세션/음악, `automotive/`의 차량 정책.
- `app/src/main/resources/generation/`: 앱에 포함하는 현행 생성 JSON 계약.
- `app/src/androidTest/`: 별도 검증 APK와 합성 fixture. 제품 화면에 진단 버튼을 추가하지 않는다.
- `app/src/test/`: Coordinator·runtime·음성 종료·암호화·생성 계약·요약 실행 JVM 회귀 검사.
- `server/`: 인증·계약·근거 검증, 멱등 처리·취소·제한과 생성 provider 경계를 갖춘 Python ASGI 서버. 합성 provider는 시험 코드에만 둔다.
- `scripts/`: 빌드, Pleos 실행 확인, SDK/저장소 검증, 스키마 매핑 검사.
- `docs/`: 현행 명세와 개발 증거.
- `spec/`: SQL·JSON 참조 계약과 검사 코드. 앱·서버 구현과 구분한다.

`PetasosSessionHost`가 실제 저장소·음성·생성·요약·편성을 조립한다. `PetasosApplication`과 `SummaryWorker`는 같은 복구 gate를 통과한다. WorkManager에는 turn ID만 저장한다. 설정이 없으면 생성 성공을 만들지 않고 요약을 blocked로 남긴다. 권한으로 차단된 요약은 설정 변경이나 앱 재시작으로 풀리지 않으며, 명시적 근거 재검토 후 `SummaryBackground.evidenceChanged()`로 재검증·예약한다. 서버 evidence는 파일 변경과 요청 경계에서 철회를 재검사한다. 이 관리 기능의 제품 화면, 라이브 품질 검수와 공개 배포는 남아 있다.

새 음성 입력은 앱이 소유한 AudioRecord의 stop·STOPPED·release 증거로 요청별 종료를 확인한다. SDK Ended·close 반환·1초 해제 유예는 이 증거를 대신하지 않는다. 합성 PCM, EOF 없는 연속 입력, 실제 사람 발화와 제품 타이머 준수는 별개다. [입력/차량 정책 문서](./docs/development/2026-11-07-owned-input-and-vehicle-policy.md)를 따른다.

본 디자인은 사용자와 별도 논의한다. 현재 기능 제어도 CarUxRestrictions 확인 전 숨기며 `distractionOptimized=true`를 선언하지 않는다. 기록 설정·수동 관심사·이동 종료/결과 조회는 dev.12에, 설치된 지역·관심사 콘텐츠의 로컬 자동선정은 dev.14에 연결했다. 실제 Suspend/Resume·주행 검수, 위치 의미/경계·추가 음악 자산·이름/대상 기반 의도/랜드마크·외부 자동 생성 편성은 남아 있다. 현행 백업/전송 제외 설정은 유지하며 실제 기기 복원과 향후 버전 Migration 검수는 남아 있다.
