# 오디오 포커스·미디어·Suspend

확인일: 2026-11-06. 플랫폼 가이드의 요구와 Petasos 재생 의도를 함께 적용한다.

## 포커스

출력 전에 AudioAttributes와 AudioFocusRequest를 설정하고 요청 결과를 확인한다. LOSS/LOSS_TRANSIENT/CAN_DUCK/GAIN을 구분하며, 반환 시 기존 음량을 복구한다. Gleo 호출로 일시 상실하면 출력을 중단한다. 공식 상호작용 표는 R(거부)·E(선점)·C(동시)와 R > E > C 우선순위를 설명한다. [오디오 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development)

Petasos 적용 권고:

- 이야기 오디오를 실제 용도에 맞게 분류한다. 위치 설명이라는 이유만으로 내비 길 안내 스트림으로 지정하지 않는다.
- 플랫폼의 포커스 결정을 받아 처리한다. 일반 앱이 차량 전체의 오디오 중재기를 대체 구현한다고 가정하지 않는다.
- GAIN은 사용자 재생 의도를 바꾸지 않는다. 사용자가 정지했다면 통화 종료 뒤에도 정지 상태를 유지한다.
- TTS 서비스와 내부 음악이 별개로 출력하더라도 앱의 단일 재생 상태에 연결한다.

위 네 항목은 공식 정책을 Petasos에 적용한 설계 해석이며 실제 경쟁 앱과 시험해야 한다. [동작 명세](../../../Petasos_동작명세.md)

## Suspend

진입 시 출력 중단, 포커스 반환, 재생 위치·상태 저장, 일시 리소스 정리를 수행한다. Resume 후 현재 조건과 사용자 의도를 평가하고 필요할 때 다시 포커스를 요청한다. 문서 예제의 `onSuspendEntering()`은 설명용 함수다. 앱에 자동 전달되는 시스템 콜백으로 착각하지 말고 실제 연결 경로를 확인한다. [Suspend 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/suspend-mode-audio-focus)

## Music/Audio 연동

공식 가이드는 시스템 콘텐츠 탐색과 재생 제어 연동을 요구하며 MediaBrowserService와 MediaSession을 설명한다. 같은 문서에서 Media3의 MediaLibraryService/MediaSession을 권장한다. 새 구현은 선택한 서비스가 차량 미디어 브라우저·검색·하드키와 호환되는지 검증한다. 서비스 연결이나 프로필 변경만으로 자동 재생하지 않는다. [미디어 정책](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide)

Petasos 권고: 지역 이야기·내부 음악을 구분해 탐색 구조와 메타데이터를 제공한다. 시스템 정지 명령은 음악만이 아니라 진행 중인 TTS·대화 입력까지 명세대로 중단시킨다. 표준 재생/정지 하드키 지원과 사용자 정의 ‘말하기 버튼’ 재매핑은 다른 기능이다. 후자가 P1이라고 표준 미디어 제어까지 제외하지 않는다.

## 음성 첫 음절과 묵음

가이드는 새 음성 출력 시작에 150~200 ms 선행 묵음을 권장하며 PCM/WAV 패딩 등 데이터 단계 적용을 우선한다. 자연스럽게 이어지는 발화마다 불필요하게 추가하지 않고, 새 스트림·세션 시작은 다시 검토한다. 단순 타이머 지연은 출력 경로 준비를 보장하지 않는다. [선행 묵음](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide)

현재 Pleos TTS의 서비스 직접 출력 경로에서 묵음 삽입을 앱이 제어할 수 있는지는 미확인이다. 먼저 실제 청취로 첫 음절과 지연을 측정하고, 제공 API의 범위 안에서 처리한다. 조사만으로 스피커 출력 품질을 PASS 처리하지 않는다.

## 필요한 실행 확인

내비와 동시 재생/duck, 통화·Gleo 중단, 사용자 정지 후 focus 복귀, 시스템 stop, Suspend 복귀, 음성 입력 중 미디어 요청을 각각 재현한다. 현재 SDK 연결 probe와 이 통합 시험은 다르다. [실행 기록](../../../development/2026-11-06-sdk-probes.md)
