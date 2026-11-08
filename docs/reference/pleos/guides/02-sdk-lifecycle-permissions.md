# SDK 공통 연동·권한·버전

확인일: 2026-11-06. 아래 버전은 공식 소개·릴리스 문서 기준이며 계정 승인 또는 실차 지원을 보장하지 않는다.

| SDK | 문서 버전 | 현재 앱 의존성 | 공식 가져오기 |
| --- | --- | --- | --- |
| FusedLocation | 1.1.3 | 선언됨 | [Maven](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup) |
| TextToSpeech | 3.3.1 | 선언됨 | [Maven](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/importing-sdk) |
| SpeechToText | 3.3.1 | 선언됨 | [Maven](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/importing-sdk) |
| NaviHelper | 3.0.5 | 조사 시 미선언 | [Maven](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk) |
| Vehicle | 2.3.2 | 조사 시 미선언 | [Maven](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup) |

앱 선언 상태는 조사 시점의 `app/build.gradle.kts` 관찰이다. 진행 중인 개발에서 달라질 수 있다. SDK 버전과 기기에 설치된 서비스 버전은 별도로 기록한다. [공식 버전·변경 목록](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)

## 가져오기

저장소는 `https://nexus-playground.pleos.ai/repository/maven-releases/`, group은 `ai.pleos.playground`다. 현재 프로젝트는 해당 group에만 이 저장소를 사용하도록 제한했다. 버전을 명시한 Maven 의존성을 유지하며 분석용 AAR을 앱의 별도 중복 의존성으로 추가하지 않는다.

현재 `.local/sdk-inspection/`에 위치·음성 AAR이 있다. 이 문서 묶음은 바이너리를 다시 복사하거나 다운로드하지 않는다.

## 권한은 기능별로 확인

| 기능 | 대표 manifest 권한 | 추가 확인 |
| --- | --- | --- |
| 위치 | `pleos.car.permission.FUSED_LOCATION` | 서비스 존재, 기기 권한·위치 접근 상태 |
| TTS | `pleos.car.permission.TTS_SERVICE` | 선택 모드, 초기화, 서버 사용 시 등록 |
| STT | `pleos.car.permission.STT_SERVICE` | 위 조건과 실제 마이크 경로 |
| 내비 정보 | `pleos.car.permission.NAVI_ROUTE` | 해당 조회·요청의 콜백 |
| 내비 경로 변경 | `pleos.car.permission.NAVI_ROUTE_SEARCH` | P0 정보 읽기와 분리 |
| 내비 화면 요청 | `pleos.car.permission.NAVI_CUSTOM_MAP` | 요청 기능의 필요성 |
| 차량 조회/제어 | API별 `CAR_*` / `CONTROL_CAR_*` | 각 함수의 권한과 capability |

근거: [위치](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions), [TTS](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions), [STT](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions), [내비](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions), [차량](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)

## 연결의 완료 기준

객체 생성, 초기화 호출 반환, 초기화 성공 콜백, 실제 데이터 수신은 구분한다. 제공되는 성공 콜백을 기다리고, 리스너는 요청 전에 연결한다. 해제는 같은 리스너 인스턴스로 수행한다. 위치·내비·음성의 초기화 서명은 서로 다르므로 공통 함수 모양을 추정해 복사하지 않는다. 각 SDK 문서의 수명주기 절차를 사용한다.

## 계정 상태와 공개 문서의 차이

신청 안내에는 위치·TTS·STT·Vehicle Control이 준비 중으로, Vehicle State·NaviHelper가 사용 가능으로 표시돼 있다. 이 안내와 개별 SDK 가이드가 함께 존재한다. 프로젝트 생성은 사용자 확인 사항이지만 모든 SDK 승인이나 서버 인증 성공으로 바꾸어 기록하지 않는다. Emulator 2.1.0 이후 CRN 입력 불필요 안내도 확인했다. [프로젝트·SDK 신청 안내](https://document.pleos.ai/docs/pleos-only/create-my-project)

Petasos의 위치·ON_DEVICE 음성 시험 결과는 [개별 SDK 실행 기록](../../../development/2026-11-06-sdk-probes.md)과 [STT 후속 검증](../../../development/2026-11-06-stt-verification.md)에 있다. 로컬 성공만으로 서버 모드·실차·App Market 권한까지 검증됐다고 보지 않는다. [호환성 메모](../research/compatibility-notes.md)
