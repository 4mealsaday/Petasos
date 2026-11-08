# Pleos 플랫폼 연동 확인 요청

작성·공식 문서 확인일: 2026-11-08. 상태: **미전송 초안**.

이 문서는 실제 이동에 따른 지역 이야기와 음성 대화를 연결하기 위해 공급자 답변이 필요한 항목을 모은다. 계정·키·토큰·프로젝트 식별자·사용자 좌표·이동 이력·개인 발화·원문 로그는 포함하지 않는다. 이 문서 작성 과정에서 외부 연락을 하지 않았다.

## 문의 제목

Pleos Connect 일반 앱의 위치 품질·활성 경로·음성 원문 로그·Suspend 연동 계약 확인 요청

## 환경과 확인 범위

| 항목 | 관측한 환경 |
| --- | --- |
| 에뮬레이터 | Pleos Connect `release_connect_v2.1.0`, Android API 34, ARM64 |
| 위치 SDK | `ai.pleos.playground:FusedLocation:1.1.3` |
| 내비 SDK | `ai.pleos.playground:NaviHelper:3.0.5`, `navi-data:3.0.4` |
| 음성 SDK | `ai.pleos.playground:SpeechToText:3.3.1`, `TextToSpeech:3.3.1` |
| 연결된 음성 서비스 | `ai.pleos.playground.caas/.service.speech.SpeechService` |
| 관측한 서비스 버전 | `4.2.10-coral-aosp`, versionCode `4002010` |
| 앱 검증 기준 | Petasos `0.8.0-dev.19`; 공개 SDK 연결 및 합성 데이터 시험 |

위치·내비는 문서와 SDK 선언/source를 조사했다. 음성은 비개인 합성 PCM·TTS 시험에서 앱과 서비스 프로세스를 구분해 집계했다. 실제 사람 발화, 실차 주행, 실제 Suspend 진입·복귀를 검증했다는 의미가 아니다. 상세 재현과 집계는 아래 개별 문의 문서를 따른다.

## 1. 실제 위치의 측정 시각과 정확도

`LocationData.timestampMillis`의 시간 원점, 측정/전송 시각 구분, last-known 캐시의 원래 측정 시각 보존 여부가 필요하다. `confidence`의 단위·범위·신뢰수준·결측 표현과 공식 수평 정확도 변환 방식도 확인 부탁드린다.

이 값을 직접 해석하기 어렵다면, 동등한 품질 정보를 제공하는 지원 API를 안내해 주시기 바란다. Android `LocationManager` 병용이 지원되는 경우 provider·권한·백그라운드 사용·차량 개인정보 차단과의 관계가 필요하다. 현재 앱은 확인되지 않은 값을 임의로 정확도 미터나 epoch 시각으로 변환하지 않는다.

세부 질문과 공식 source 조사: [위치 의미·경로 기하 문의](pleos-location-route-support-request.md).

## 2. 활성 내비 경로의 도로 형상과 통과 예상시간

현재 선택되어 안내 중인 **전체 도로 polyline 및 도로 구간별 예상 통과 시간**을 읽는 지원 API가 필요하다. 지역 경계와 교차해 앞으로 통과할 지역을 구하고, 생성된 이야기와 음악의 길이를 반영해 다음 지역을 선택하려는 용도다.

`TripSummary.routePoints`의 목적지·경유지를 도로 형상으로 해석하지 않고 있다. 지원되는 공급자, 필요한 SDK·권한, 경로 revision과 재탐색 완료 시점, 취소/오류 후 늦은 응답을 구분하는 식별자·순서 계약을 확인 부탁드린다. 외부 길찾기를 사용하는 경우 활성 Pleos 경로와 동일한지 확인할 수 있는 지원 방법도 필요하다.

세부 질문과 공식 API 조사: [위치 의미·경로 기하 문의](pleos-location-route-support-request.md).

## 3. 음성 원문 로그 비활성화와 실시간 PCM 입력

ON_DEVICE 합성 시험에서 앱에 포함된 알려진 SDK 로그 경로를 억제한 뒤에도, 별도 SpeechService에서 인식/합성 시험 문장이 관측됐다. 개인 대화 활성화 전에 클라이언트와 서비스 양쪽의 원문 로그 **발생 자체를 끄는** 공식 설정 또는 수정된 SDK·서비스·에뮬레이터 조합이 필요하다. 정상·오류·취소, 재연결·재시작·사용자 전환 후 적용 범위도 확인 부탁드린다.

외부 PCM의 `initialize()` 성공이 입력 준비 완료를 보장하는지, EOF 없는 스트림에서 EPD callback과 `completeAudioSend()`의 권장 순서, cold 초기화·최종 결과 지연, 취소 후 입력 종료 확인 계약을 함께 요청드린다. 현재 첫 발화 5초와 EPD 종료 후 최종 결과 500 ms 요구를 임의로 늘리지 않고 있다.

관측 수치·재현 방식·서비스 산출물 식별·세부 질문: [음성 서비스 기술 문의](pleos-speech-support-request.md). 해당 문서의 내부 실행 JSON 전체를 그대로 전송하지 않고 필요한 환경·집계만 선택한다.

## 4. 일반 앱의 실제 Suspend/Resume 훅

현재 앱은 공개 `CarPowerManager`의 AUDIO/MICROPHONE 전원 정책을 구독한다. 하지만 컴포넌트 전원 정책 변경을 실제 Suspend 진입/Resume으로 해석하지 않는다. 다음 지원 계약을 확인 부탁드린다.

1. 일반 배포 앱이 실제 Suspend 진입과 Resume을 관측할 수 있는 공개 SDK API 또는 protected broadcast가 있는가? 필요한 SDK 버전·권한·지원 플랫폼을 알려주시기 바란다.
2. callback 스레드, 진입 알림과 실제 정지 사이의 순서, 처리 완료 ACK 방식·시간 제한, 최초 상태 조회와 서비스 재연결 시 동작은 어떻게 되는가?
3. `CarPowerPolicy`의 AUDIO/MICROPHONE 변경을 실제 Suspend 진입/Resume과 동등하게 간주할 수 있는 공식 보장이 있는가? 없다면 별도의 상태 공급자가 필요하다.
4. 출력 중단·포커스 반환·재생 위치 저장·일시 리소스 해제를 검증할 에뮬레이터 및 실차 절차가 있는가? 해당 절차가 실제 Suspend인지 단순 화면 꺼짐/오디오 정책 변경인지 구분해 주시기 바란다.

확인한 공식 근거:

- [Pleos Suspend 모드 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus)는 진입 전 출력 중단·포커스 반환과 복귀 시 재평가를 요구한다. 예제의 `onSuspendEntering()`/`onResumeFromSuspend()`는 일반 함수이며 실제 이벤트 등록 API는 제시하지 않는다.
- [Android 공개 CarPowerManager](https://developer.android.com/reference/android/car/hardware/power/CarPowerManager)는 `addPowerPolicyListener()`, `getCurrentPowerPolicy()`, `removePowerPolicyListener()`를 제공한다. 이는 컴포넌트 전원 정책 API다.
- [AOSP 전원 관리](https://source.android.com/docs/automotive/power/power)의 power-state listener 설명은 `@SystemApi`/`@hide` 및 privileged 앱 대상으로 명시돼 있다. 이를 일반 앱의 지원 계약으로 대신 적용하지 않는다.

## 답변 및 후속 확인

각 항목에 대해 지원 여부, 공식 문서/API 식별자, 적용 가능한 버전·권한, 알려진 제약과 검증 절차를 요청드린다. 미지원이라면 일반 앱에서 사용할 수 있는 공식 대안과 향후 지원 범위를 알려주시기 바란다.

기존 프로젝트 담당 채널이 있으면 해당 채널을 우선한다. 공개 [Pleos 개발자 FAQ](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq) 하단에 `partnership@pleos.ai`가 표시돼 있으나 SDK 기술 전용 창구라는 보장은 확인하지 못했다. 담당 채널이 없다면 먼저 적절한 기술 지원팀 연결을 문의할 수 있다.

답변이나 수정 버전을 확보한 뒤 해당 계약만 어댑터에 반영하고 합성·기기 검증을 수행한다. 사람 마이크와 실제 주행/Suspend 시험은 별도 단계이며, 현재의 Virtual Console 보류 결정은 유지한다.
