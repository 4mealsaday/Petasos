# 최소 Android 앱 실행 검증

검증일: 2026-11-06, Asia/Seoul. 최종 설치·실행 검사 시작: 16:30:25 KST (`2026-11-06T07:30:25Z`).

**결과: 첫 목표인 APK 빌드 → Pleos 에뮬레이터 설치 → 시작 화면 실행을 완료했다.** 실제 에뮬레이터에서 수행했으며 모의 실행 결과가 아니다. P0 제품 기능·Pleos SDK 연결 완료를 뜻하지 않는다.

## 기준과 환경

먼저 `DEVELOPMENT_HANDOFF.md`를 읽고 로컬 v0.8 제품·동작·데이터/API·검수·조사 문서를 확인했다. 연결된 [Notion 제품 명세](https://app.notion.com/p/40fcf80747f2801fb55ec86bc8349426)와 네 하위 명세도 v0.8임을 조회했다. 기존 요구사항·참조 계약은 수정하지 않았다.

착수 시 Android Gradle 프로젝트와 Git 저장소는 없었다. 기존 문서·조사 산출물·`spec/`을 보존하며 루트에 `app` 모듈을 추가했다. Git 초기화는 수행하지 않았다.

| 항목 | 확인값 |
| --- | --- |
| 호스트 | macOS ARM64 |
| JDK | Homebrew OpenJDK 17.0.15 |
| Gradle / AGP / Kotlin | 8.11.1 / 8.10.1 / 2.2.21 |
| compileSdk / Build Tools | 36 / 35.0.0, 기존 설치본 사용 |
| minSdk / targetSdk | 28 / 34 |
| 앱 | `com.petasos.app`, `0.8.0-dev.1` / versionCode 1 |
| 실행 기기 | `emulator-5554`, Pleos Connect 2.1.0, Android 14 / API 34 |
| 이미지 fingerprint | `Pleos/crp_avd_arm64/crp_avd_arm64:14/RELEASE.release_connect_v2.1.0/release_connect_v2.1.0:user/test-keys` |
| Android 사용자 | 10 |
| 에뮬레이터 시간대 | `America/Los_Angeles` — 호스트 KST와 다르며 변경하지 않음 |
| 화면 | 2560×1440, density 160 |
| 디스크 여유 | 착수 약 8.4 GiB, 빌드 후 약 8.3 GiB |
| Pleos SDK / DB / 서버 | 아직 구현·연결하지 않음. API 0.8은 문서 계약만 존재 |

시스템 기본 Java 26과 Android Studio 내장 JBR 25 대신 JDK 17을 명시했다. 도구 조합은 [AGP 8.10 호환표](https://developer.android.com/build/releases/agp-8-10-0-release-notes), [Kotlin 호환표](https://kotlinlang.org/docs/gradle-configure-project.html), [Pleos 시작 가이드](https://document.pleos.ai/docs/pleos-only/getting-started)를 확인해 고정했다. Gradle Wrapper JAR 및 배포 ZIP은 공식 SHA-256과 대조했다.

## 구현 범위

- Kotlin `MainActivity`와 XML 리소스로 Petasos 개발 프리뷰 화면을 표시한다.
- Automotive 대상과 표준 런처 진입점을 선언했다. 정차 중 사용하는 최소 화면이며 `distractionOptimized=true`를 선언하지 않았다.
- 위치·마이크·인터넷 권한이나 백그라운드 서비스는 아직 없다. 실행·복구 시 자동 녹음·재생이 없다.
- 앱 데이터 백업·기기 전송 제외 XML과 `allowBackup=false`를 포함했다. 실제 데이터 복원 검증은 미실행이다.
- `scripts/gradle.sh`는 JDK 17과 프로젝트 내 캐시를 사용한다. Studio와 CLI는 동일한 비추적 debug keystore를 사용한다.
- `scripts/smoke-pleos.sh`는 Pleos/API 34 에뮬레이터를 확인하고 현재 Android 사용자에 설치한다. 앱 데이터 삭제·로그 전체 초기화·Virtual Console 연결은 수행하지 않는다.

## 실행 결과

기존 A/R/D/N 제품 시나리오와 구분한 기반 검증 ID다. 제품 요구사항 전체의 통과로 합산하지 않는다. 초기 DB·fixture·외부 API 입력은 없다.

| caseId | 실제 확인 | 결과·증거 |
| --- | --- | --- |
| BOOT-01 | `assembleDebug`로 debug APK 생성 | PASS, 최종 빌드 `BUILD SUCCESSFUL` |
| BOOT-02 | `lintDebug` | PASS, 활성 검사 기준 issue 0개 |
| BOOT-03 | 현재 사용자 10에 신규 설치 및 최종 APK `install -r` 업데이트 | PASS, `Success` |
| BOOT-04 | `am start -W`, 해당 사용자 앱 프로세스와 resumed Activity | PASS, `Status: ok`, `LaunchState: COLD`, `.MainActivity` 전면 표시 |
| BOOT-05 | 최종 실행 이후 앱 UID로 제한한 main/system/crash 로그 | PASS, crash buffer 0 bytes, `FATAL EXCEPTION`·`Fatal signal`·`Abort message` 없음 |
| BOOT-06 | 최종 스크린샷 육안 확인 | PASS, Pleos 화면 안에 한글·앱명·개발 프리뷰 표시, 잘림 없음 |
| BOOT-07 | MAIN/LAUNCHER intent resolution | PASS, `com.petasos.app/.MainActivity` |

최종 launch `TotalTime=287ms`는 한 번의 실행 관찰값이며 성능 보장 수치가 아니다. 실행 검사는 짧은 시작 구간에 한정된다. 로그·스크린샷의 기기 시각은 에뮬레이터 시간대에 따른다. 검증 기준일은 위 KST/UTC 시각이다.

APK 크기: 2,468,909 bytes. SHA-256:

```text
b599ea52d118181fa26cc5cdcb439c47ab061f7dc9e7bd58d5254f8c984a51a1
```

최초 Lint의 레이아웃 중복 배경·불필요한 컨테이너는 수정했다. 고정 버전에 대한 `ExpiredTargetSdkVersion`, `OldTargetApi`, `AndroidGradlePluginVersion` 알림만 명시적으로 제외했다. 나머지 경고는 오류로 처리한다. SDK 메타데이터 XML v4를 구형 파서가 읽는다는 경고는 남아 있지만 빌드·Lint·설치·실행을 막지 않았다. 이미지·도구 업데이트 시 재점검한다.

## 재현과 산출물

```sh
./scripts/gradle.sh :app:assembleDebug :app:lintDebug --console=plain
./scripts/smoke-pleos.sh emulator-5554
```

- [개발 안내](../../README.md)
- [APK](../../app/build/outputs/apk/debug/app-debug.apk)
- [최종 실행 결과](../../artifacts/milestone-1/verification.txt)
- [빌드 로그](../../artifacts/milestone-1/build.log)
- [Lint 보고서](../../app/build/reports/lint-results-debug.html)
- [실행 화면](../../artifacts/milestone-1/petasos-emulator.png)
- [APK 메타데이터](../../artifacts/milestone-1/apk-metadata.json)

APK·빌드 로그·이미지는 재생성 가능한 로컬 산출물로 Git 제외 대상이다. 이 문서에는 검증 결과와 식별값을 남긴다.

## 다음 단계

위치·TTS·STT의 실제 지원·권한·콜백을 각각 작은 동작으로 검증한다. 이후 단일 `PlaybackCoordinator`를 중심으로 이야기 한 편의 청취 → 버튼 → 현재 문단·유도 문장 완료 → 발화 1회 → 답변 → 청취 복귀를 구현한다. 저장·복구·수집과 CarUxRestrictions/미디어 검수는 이어서 진행한다.

기존 A/R/D/N 검수, 실제 SDK 호출, 위치 경로 전달, 음성 품질, 오디오 포커스, suspend, 주행 화면 제한, 백업 복원은 **unrun**이다. Virtual Console은 사용자 결정대로 차량 상태 시험 단계까지 보류한다. 이번 단계에서는 계정 키나 추가 제품 결정이 필요하지 않았다.
