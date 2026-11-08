# 개발 환경·에뮬레이터

확인일: 2026-11-06. 공식 설치 기준과 프로젝트 실행 기록을 구분한다.

## 공식 기준

| 항목 | 기준 |
| --- | --- |
| 앱 최소 Android API | 28 이상 |
| 빌드 도구 | Gradle 8 이상, Java 또는 Kotlin |
| 에뮬레이터 | Pleos Connect 2.1.0, Android 14 / API 34 |
| Mac 시스템 이미지 | ARM64 |
| AVD | Automotive → Pleos Connect, RAM 4 GB, Cold boot |

minSdk, compileSdk, targetSdk와 이미지 API는 서로 다른 값이다. 특정 JDK·AGP 조합은 실제 프로젝트 설정과 빌드로 확인한다. [공식 설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)

Mac용 SDK Update Site는 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`이다. SDK Manager의 API 34에서 이미지 상세 항목을 선택한다. 설치와 앱의 Maven 의존성 추가는 별도 단계다.

## 실행 전 설정

- Cold boot를 유지한다. 설정 가이드는 Quick boot에서 VHAL을 사용할 수 없다고 안내한다.
- 사람의 실제 마이크 발화 시험 전에 Extended Controls → Microphone에서 호스트 입력을 연결한다. 앱의 서비스 권한과 Mac의 마이크 접근도 각각 확인한다. 합성 PCM 입력 시험은 호스트 마이크를 연결하지 않는 별도 경로다.
- GPU 사양 표만으로 Mac 지원 여부를 부정하지 않는다. 설치 가이드는 Mac ARM64 이미지를 별도로 제공한다. 실제 그래픽 동작은 대상 환경에서 확인한다.
- 2.1.0의 릴리스 노트에는 LLM SDK, CRN, Bluetooth 동글 연동 제공 종료가 명시돼 있다. 과거 설치 지침을 그대로 적용하지 않는다. [설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator), [2.1.0 변경 사항](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note)

## Virtual Console

차량 상태를 바꾸는 개발 도구다. 현재 사용자는 연결을 보류했으며 앱 개발의 선행 조건으로 다시 요구하지 않는다. 차량 이벤트 시험 단계에서 실행한다.

```bash
adb devices
adb -s emulator-5554 forward tcp:8088 tcp:8088
```

위 serial은 현재 개발 기록의 예시다. 실제 출력과 대조한 뒤 PC 브라우저에서 `http://127.0.0.1:8088/`을 연다. Motion, Safety, Energy 등 영역에서 상태를 바꿀 수 있다. 콘솔의 조작 가능 범위가 일반 앱의 제어 권한을 뜻하지는 않는다. [콘솔 연결](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator), [콘솔 기능](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)

## Petasos에서 이미 확인한 것

에뮬레이터 홈 화면과 최소 앱 실행은 기존 개발 기록을 따른다. 이번 문서 조사에서는 에뮬레이터·마이크·차량 값을 변경하지 않았다. [부트스트랩 기록](../../../development/2026-11-06-bootstrap.md), [SDK 시험 기록](../../../development/2026-11-06-sdk-probes.md)

초기 실행 실패 로그는 userdata 생성에 12,288 MB가 필요하다고 표시했다. 당시 디스크 부족이 원인이었으며 사용자가 공간 확보 후 실행했다. 이 값은 해당 실행의 관찰치이고 모든 AVD의 일반 최소 공간으로 사용하지 않는다. 재발하면 최신 로그와 실제 여유 공간부터 확인한다.

## 폼 팩터 시험

별도 창으로 실행한 Emulator, Platform-Tools, Go 1.22 이상과 8095·8096 포트를 준비한다. 공식 Host Display Bridge와 FormFactorConsole에서 보조 디스플레이를 생성해 가로·세로 레이아웃을 시험한다. 문서의 v2.0 이름은 예시이므로 실제 `adb emu avd name` 결과를 사용한다. 디스플레이 제거는 실행 중인 앱을 종료할 수 있다. [폼 팩터 절차](https://document.pleos.ai/docs/pleos-only/app-test-guide/form-factor-test-guide)
