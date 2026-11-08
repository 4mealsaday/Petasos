# Petasos — 현행 명세 안내

버전 0.8 · 2026-11-06

[Notion 제품 명세](https://app.notion.com/p/40fcf80747f2801fb55ec86bc8349426)

기존 22절 문서를 다섯 문서로 재구성했다. 현재 구현 기준은 아래 문서다.

- [제품 명세](./docs/Petasos_제품명세.md): 목적·범위·PR-01~12
- [동작 명세](./docs/Petasos_동작명세.md): 상태 전이·음성·위치·복구
- [데이터·API 계약](./docs/Petasos_데이터_API_계약.md): 시간·필드·제약·트랜잭션·요청/응답
- [검수 명세](./docs/Petasos_검수명세.md): 35개 앱 시나리오와 요구사항 추적
- [조사·결정 기록](./docs/Petasos_조사_결정기록.md): 출처·준비 상태·미확인 사항

[이전 v0.7](./docs/archive/v0.7_Petasos_기획서.md)은 보관 기록이며 현재 규칙으로 사용하지 않는다.

[참조 SQL](./spec/petasos-v0.8.sql), [API JSON Schema](./spec/generation-v0.8.schema.json), [합성 요청 예시](./spec/request-example.json), [계약 검사 결과](./spec/validation-report.json)를 함께 관리한다. 이 파일들은 문서의 참조 계약이며 Android 앱 또는 배포 서버 구현이 아니다.
