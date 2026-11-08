# Pleos 공식 문서 로컬 참고집

공식 자료 수집일: **2026-11-06** · 문서 검수일: **2026-11-07**. Petasos 개발에서 필요한 공식 가이드와 API 정보를 검색하기 위한 문서다.

시작점은 사용자가 지정한 [Notion 개발 환경](https://app.notion.com/p/ce7cf80747f283d4a3848106d478aabe)의 직접 링크 5개다. 이 링크에서 SDK 5종의 가이드·핵심 API, 앱 공통 정책과 테스트 문서로 범위를 확장했다. 총 150개 URL을 수집했고, 그중 동적 API 본문 67개를 별도로 읽었다. 전체 사이트 또는 모든 API 상세 페이지를 복제한 자료는 아니다.

## 문서 찾기

| 필요한 작업 | 문서 |
| --- | --- |
| 설치·Cold boot·마이크·Virtual Console | [환경](guides/01-environment.md) |
| SDK 버전·Maven·서비스 권한 | [공통 연동](guides/02-sdk-lifecycle-permissions.md) |
| 위치·근접 알림·timestamp/confidence | [FusedLocation](guides/03-fused-location.md) |
| 이야기 출력·완료·중지 | [TTS](guides/04-tts.md) |
| 발화·최종 텍스트·PCM·종료 | [STT](guides/05-stt.md) |
| 목적지·TBT·잔여 시간·경유지 | [NaviHelper](guides/06-navihelper.md) |
| 차량 19개 도메인·지원 여부·제어 | [Vehicle](guides/07-vehicle.md) |
| 포커스·시스템 미디어 제어·Suspend | [오디오](guides/08-audio-and-media.md) |
| 주행 UI·CCP·폼 팩터·심사 | [UI·테스트·배포](guides/09-ui-testing-release.md) |
| Gleo 함수 호출·스키마 | [Gleo](guides/10-gleo.md) |
| 문서 불일치·현재 실행 한계 | [호환성 메모](research/compatibility-notes.md) |
| 출처·확인 시각·버전 추적 | [출처 목록](source-index.md), [메타데이터](sources.json) |

## API 검색

선택한 클래스·모델 49개에서 메서드 이름 584개를 추출했다. 수는 클래스별 이름 기준이며 오버로드는 합쳤다. 전체 SDK의 public API 총수나 실행 검증 수가 아니다.

- [FusedLocation](api/fused-location.md)
- [TextToSpeech](api/tts.md)
- [SpeechToText](api/stt.md)
- [NaviHelper](api/navihelper.md)
- [Vehicle: 19개 도메인 포함](api/vehicle.md)
- [JSON 색인](api/symbol-index.json)

예: 프로젝트 루트에서 `rg 'onEndedRecognition|remainTime|timestampMillis' docs/reference/pleos`로 찾는다. 실제 구현 때는 연결된 API 참조와 사용 중인 SDK의 선언을 대조한다.

## 읽는 기준

1. **공식 문서 사실:** 가까운 원문 링크로 출처를 표시했다. 문서 확인일은 원문 발행일과 다르다.
2. **Petasos 적용 권고:** 제품에 연결한 해석이며 승인된 SDK 기능이나 구현 완료를 뜻하지 않는다.
3. **실행 관찰:** 기존 개발 기록을 연결했다. 이번 작업에서 앱·SDK 실행을 재시험하지 않았다.
4. **미확인:** 시간축·단위·계정 권한·실차·문서와 다른 콜백은 추정하지 않고 표시했다.

제품 요구사항은 [현행 v0.8 명세](../../../Petasos_기획서.md)를 유지한다. 이 참고집은 명세의 보충 근거다. 개발 진행 상태는 [SDK 실행 기록](../../development/2026-11-06-sdk-probes.md)과 [STT 후속 검증](../../development/2026-11-06-stt-verification.md)을 확인한다. 이번 정리에서는 제품 코드를 변경하거나 Virtual Console을 연결하지 않았다.

후속 구현: 2026-11-07에 참고집을 기준으로 앱 코드를 대조하고 dev.4의 음성 종료·포커스·CCP 선언을 보완했다. 최신 결과와 미구현 경계는 [공식 API 감사](../../development/2026-11-07-official-api-audit.md), [Coordinator 첫 구현](../../development/2026-11-07-playback-coordinator.md)에 기록한다. 참고집 수집 작업과 후속 코드 검증은 별개다.

새 개발 채팅에서는 이 README → 작업할 SDK의 가이드 → 해당 API 색인 순서로 읽는다. 호출을 구현하기 전에는 [호환성 메모](research/compatibility-notes.md)도 확인한다.

## 갱신 방법

- 의존성·에뮬레이터·서비스를 변경하거나 공식 문서와 동작이 다르면 해당 모듈과 릴리스 노트를 다시 읽는다.
- `sources.json`의 URL·확인 시각·해시와 비교한다. 문서 자산 해시 변화만으로 의미가 바뀌었다고 단정하지 않는다.
- 변경된 가이드·API 색인·호환성 메모를 함께 갱신한다. 과거 시험 결과를 새 버전의 성공으로 복사하지 않는다.
- 원문을 다시 찾을 수 있도록 출처 URL을 유지한다. 키·시크릿·실제 발화 전문·정밀 경로는 이 문서에 넣지 않는다.

문서 수집에서 발견한 Playground 릴리스 링크 하나는 기대한 릴리스 본문이 아닌 안내 화면을 반환했다. 해당 링크는 출처 목록에 따로 표시했고 버전 근거로 사용하지 않았다.
