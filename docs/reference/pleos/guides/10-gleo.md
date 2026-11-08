# Gleo Interface와 생성 엔진의 경계

확인일: 2026-11-06. Petasos에서 Gleo 앱 명령 연동은 P1이며 이야기 생성 API와 분리한다.

## 제공하는 구조

Gleo가 사용자의 명령을 해석해 앱에 등록된 함수를 호출하고, 앱이 callback으로 처리 결과를 돌려준다. 이 구조만으로 앱이 임의 프롬프트를 넣어 자유 텍스트를 생성하는 API가 있다고 판단할 수 없다. [개념](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)

2.1.0 릴리스는 LLM SDK 제공 종료를 명시한다. 현재 데모의 앱 주도 생성 경로는 별도 생성 서버/OpenRouter 계획과 구분해 구현한다. 과거 샘플에 LLM/Search SDK라는 표현이 남아 있어도 현재 제공 근거로 사용하지 않는다. [릴리스](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note), [라디오 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/use-cases/radio-app)

## App Schema

`packageName`, `appDescription`, `version`, `actionCalls`로 앱과 호출 기능을 정의한다. 각 함수에는 이름·설명·parameters가 있고 component는 activity 또는 service다. actionCalls는 최대 3개로 명시돼 있다. 함수명은 대문자와 밑줄을 사용한다. 필수 입력을 required에 적어도 실제 수신값은 앱에서 검증한다. JSON Schema 전체 기능이 지원된다고 가정하지 않는다. [스키마](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)

파라미터 속성의 지원 type은 string, number, boolean, array로 제한된다. 여러 type을 배열로 지정하는 union과 null은 지원하지 않으며, enum 값은 문자열만 허용한다. parameters를 감싸는 object와 개별 속성의 지원 type을 구분한다. description은 영어로 간결하게 작성한다. 실제 승인 스키마와 입력값 검증은 별도로 확인한다. [스키마 작성 규칙](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)

## 전달·응답

파일 이름과 위치는 `assets/schema.json`이다. 함수별 intent filter, 검토·승인과 등록 갱신을 확인한다. 가이드의 component=service 예시는 startService가 아니라 broadcast를 사용한다. [등록](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema), [Intent 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)

| 응답 필드 | 의미 |
| --- | --- |
| functionName | 요청 함수와 일치 |
| status | success / asking / fail |
| message | 처리 결과를 설명하는 영어 문장 |
| mode | voice(기본), beep, silent |
| data | 필요한 결과 객체 배열을 담은 JSON 문자열 |

문서의 Action 제한은 7초다. 장시간 콘텐츠 생성 전체를 동기 응답 안에 넣는 설계를 피하고, 완료·접수·실패 의미를 구분해야 한다. 자체 TTS/음악 출력 시 silent로 Gleo의 추가 음성을 막을 수 있지만, 실제 오디오 포커스까지 자동 해결되는 것은 아니다. [Callback 스키마](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)

## 아직 확인할 부분

예제 설명에는 각 parameter를 extra로 넣는다고 쓰였지만 코드에서는 ACTION_PARAM JSON 문자열을 사용한다. 설명만으로 수신 형식을 확정하지 않고 실제 전달 Intent를 확인한다. 명령 action을 허용 목록으로 제한하고 불완전한 입력을 처리한다. 현재 Petasos에서 Gleo 통합을 실행 검증한 것은 아니다. [호환성 메모](../research/compatibility-notes.md)
