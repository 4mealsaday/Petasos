# 문서 차이·실행 확인 사항

공식 자료 수집일: 2026-11-06 · 개발 기록 대조·검수일: 2026-11-07. 아래는 조사 결과와 개발 기록을 연결한 메모다. 제품 명세나 구현 코드를 자동으로 바꾸는 결정 기록은 아니다.

| 항목 | 확인한 차이 | 개발 시 처리 |
| --- | --- | --- |
| LLM SDK | Emulator 2.1.0에서 제공 종료 | 과거 생성 SDK 예제를 현재 기능으로 채택하지 않음. Gleo 함수 호출과 앱 주도 생성 분리 |
| Navi 좌표 | 3.0.5는 위도→경도. Routes Java 예제 일부는 경도→위도 값 | 실제 SDK 선언·현재 API 참조와 대조, Kotlin named argument 권장 |
| Navi 잔여 값 | DestinationInfo에 remainDistance/remainTime 존재 | 필드 부재로 기록하지 않음. 실제 콜백 전달은 별도 시험 |
| Navi 경로 좌표 | TripSummary.routePoints는 경유지·목적지로 설명 | 도로 전체 polyline 제공으로 확대하지 않음 |
| LocationData | timestampMillis·confidence의 타입만으로 시간축·척도 미확정 | 원시값과 앱 수신 시각을 분리, 임의 환산 보류 |
| STT 중지 | dev.2에서 Ready 직후 stop 후 Ended 미수신. dev.3 바이트코드·실행 조사에서 EPD 종료와 무발화 취소를 구분 | Ended를 모든 stop의 완료 응답으로 사용하지 않음. 후속 취소·해제 시험과 실제 발화 검증을 구분 |
| STT 해제 순서 | 비동기 stop 직후 release 시 재연결 재현, dev.3에서 지연 release 우회책 적용 | 1초 대기를 SDK 보장으로 일반화하지 않음. SDK 변경·부하·중단 환경에서 재검증 |
| PCM 입력 | sendAudio 경로는 Ready/RMS를 보내지 않음 | 마이크 입력용 probe 기준과 분리 |
| SDK 신청 | 포털 안내의 준비 중 표기와 로컬 ON_DEVICE 실행 결과가 공존 | 서버 승인·실차 사용을 추가 확인 |
| Vehicle Service | 소개 2.3.21, 샘플 2.3.22 | 실제 설치 서비스 버전으로 시험 결과 관리 |
| 샘플 minSdk | 샘플은 23, 배포 제품 최소는 28 | 샘플 빌드 설정을 제품에 그대로 복사하지 않음 |
| Notion 차량 표 | Brake 주차 브레이크, CarInfo 크기·연식, Display 설정 등 개괄 설명 | 현행 참조의 실제 함수 존재를 확인. 현재 확인한 Brake는 페달, CarInfo는 VIN/CRN, Display는 온도 단위 조회 중심 |
| Gleo 함수 수 | App Schema는 최대 3개, 미디어 소개에는 약 3~5개 표현 | 현재 구체적인 schema 제한을 기준으로 검토·승인 확인 |
| Gleo Intent | 설명의 개별 extra와 예제 ACTION_PARAM JSON이 다름 | 실제 전달 형식 검증 |
| 음성 선행 묵음 | 정책은 권장하지만 직접 출력 SDK의 제어 방법 미확인 | 첫 음절·지연을 측정하고 지원 경로 확인 |
| 미디어 버튼 | 표준 재생 제어와 사용자 정의 말하기 버튼은 다른 기능 | P1 말하기 버튼 보류가 표준 MediaSession 제어 제외를 뜻하지 않음 |

근거와 상세 설명은 [환경](../guides/01-environment.md), [SDK 공통](../guides/02-sdk-lifecycle-permissions.md), [위치](../guides/03-fused-location.md), [STT](../guides/05-stt.md), [내비](../guides/06-navihelper.md), [차량](../guides/07-vehicle.md), [오디오](../guides/08-audio-and-media.md), [Gleo](../guides/10-gleo.md)에 연결했다. 이 표는 별도 미검증 주장 목록이 아니라 각 문서의 판단을 짧게 모은 것이다.

## 현재 프로젝트 기록과 구분

`0.8.0-dev.2`의 [SDK 검증 기록](../../../development/2026-11-06-sdk-probes.md)은 합성 위치 업데이트, TTS 초기화·완료·중지, STT 초기화를 확인했다. `0.8.0-dev.3`의 [STT 후속 검증](../../../development/2026-11-06-stt-verification.md)은 합성 PCM의 최종 인식 결과, 무발화 취소, 입력 중 해제를 별도 시험해 통과했다. 최초 BLOCKED 기록을 소급해 PASS로 바꾼 것은 아니다.

사람의 실제 마이크 발화, TTS 청취 품질, 오디오 포커스 경쟁, 통합 재생·복구는 별도로 검증해야 한다. 해당 개발 채팅에서 결과가 갱신되면 실행 기록이 이 요약보다 우선한다.

후속 dev.4 감사에서는 STT Ended 뒤 stop 생략을 수정하고, TTS에도 비동기 stop과 동기 release의 순서 보완을 적용했다. 정상 TTS Done/Stop과 오류를 구분하며 지연 시간을 실제 종료 응답으로 취급하지 않는다. 구체적인 수정과 새 실행 증거는 [2026-11-07 공식 API 감사](../../../development/2026-11-07-official-api-audit.md)를 따른다.

이번 작업은 공식 페이지 조사와 로컬 문서 정리다. API를 앱에서 실행하거나 SDK를 업그레이드한 시험이 아니다. 사용자 보류 중인 Virtual Console도 연결하지 않았다.
