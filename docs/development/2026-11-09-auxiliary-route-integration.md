# dev.26: Pleos 목적지와 네이버 보조 경로

상태: 2026-11-09 KST, dev.26 privacy 앱/검증 APK 빌드·Lint, Debug JVM **964개/75 suite**, 알려진 앱 SDK 로그 sink 정적 검사 PASS. 기기 보조 경로·내비·자동 편성·지역 계층·미디어 세션 5종 PASS. 실제 위치 회귀는 아래 실패를 보존하며 원인을 조사 중이다.

## 입력과 구조

Pleos NaviHelper의 실제 내비 상태·revision을 기준으로 `getDestinationInfo()`와 `getWaypointInfo()`를 읽는다. 좌표는 별도의 `NavigationAuxiliaryInput`에만 둔다. 기존 NavigationSnapshot·Room에는 목적지 좌표를 추가하지 않는다. 경로 변경·취소·시뮬레이션·권한 철회 시 입력을 폐기한다. 공식 SDK가 요청 ID를 주지 않으므로 알려진 진행 중 요청은 모두 비운 뒤 새 읽기를 시작하며, 나중 lifecycle의 임의 중복 방송을 식별한다고 주장하지 않는다.

| 계층 | 책임 |
| --- | --- |
| `capability/navigation` | 목적지·경유지의 불변 메모리 모델, 읽기 세대·현재성 |
| `platform/pleos/navigation` | 공개 SDK 콜백과 위 입력 모델의 연결. 내비 변경 명령 없음 |
| `planning/auxiliary` codec/client/transport | 기존 기기 HTTPS 인증으로 `/v1/routes/auxiliary` 호출. 제공자 키는 서버에만 보관 |
| `location` polyline lookup | 실제 NGII 전체 경계와 경로 선분 교차·구멍·중첩을 검사 |
| `planning/auxiliary` timeline/anchor | guide 시간 구간을 지역 구간으로 바꾸고 유효한 Android 측정 위치에 맞춰 절대 예상시각 계산 |
| `planning/auxiliary` session | 단일 현재 경로, 제한된 비동기 작업, 취소·갱신·출처 권한 |
| `PetasosSessionHost` | 사용자 위치 동의·가시성·내비·실제 재생 의도와 기존 편성기의 연결 |

## 예상시간 계산

[네이버 Directions 5](https://api.ncloud-docs.com/docs/application-maps-directions5)의 전체 `path`와 `guide.pointIndex`·`guide.duration`을 사용한다. 일부 주요 도로만 담는 `section`이나 총 ETA를 균등하게 나누지 않는다. 시작부터 끝까지 시간 구간 연결·합계가 맞아야 한다.

한 guide의 전체 선분이 하나의 검토된 시·군·구 leaf 안에 있는 경우만 지역 시간창을 만든다. 경계를 넘는 guide, 격리 기하, 모호한 중첩, 처리 예산 초과 구간은 unknown이다. 기본 기하 조회 예산은 64개 guide다. unknown 시간을 이전 지역 체류로 채우지 않는다.

실제 위치는 Android의 측정 단조 시각·정확도·수신 나이와 원본 메타데이터를 대조한다. 경로 전체에서 가능한 연결 구간을 찾아 서로 떨어진 후보가 있으면 보류한다. guide 내부의 거리에 시간 비례를 적용하지 않고 가능한 guide 시간 범위를 그대로 유지한다. 지역 시간창은 그 불확실성 범위에서 공통으로 유지되는 구간만 사용한다. Android의 정확도·시각 불확실성은 통계 추정이며 실제 위치의 확정 오차 한계가 아니다.

경로 이탈이나 명백한 역진행은 즉시 기존 예상 권한을 폐기하고 새로운 출발지로 조회한다. 개발 기본 갱신 주기는 최대 60초, 실패/이탈의 최소 재조회 간격은 10초다. 이는 방문 정책이나 정확도 보증이 아니다. 신선한 위치, 세션·시계, 내비 세대, 요청 세대, 실제 경계·계층 identity가 모두 일치할 때만 기존 길이 기반 편성기에 공급한다. 권한·가시성·동의 철회는 worker 처리를 기다리지 않고 허용을 취소한다.

## 검증 및 운영

- Debug JVM 964개/75 suite PASS, 실패·오류·생략 0. [집계](../../artifacts/milestone-26/jvm-test-summary.json).
- 실제 localhost HTTPS endpoint에서 기기 Bearer와 공식 공개 예제 좌표로 네이버 조회 PASS. 응답 좌표 4,203개, guide 29개, 시간 연결·합계 일치. [메타데이터](../../artifacts/milestone-26/naver-https-official-example.json). 공개 실시간 경로는 호출 시 달라질 수 있다.
- 실제 Android HTTPS에서 공개 예제 경로 **4,138개 좌표/27개 guide** 수신 PASS. 실제 NGII 경계 투영은 71ms, 지역 시간창 7개·unknown guide 18개였다. 명시적인 합성 Android anchor로 미래 구간 2개와 출처 만료·철회를 검증했다. 실제 사용자 위치나 활성 Pleos 경로를 시험한 결과가 아니다. [기기 결과](../../artifacts/milestone-26/auxiliary-route/auxiliary-route-privacy.json).
- 같은 APK의 읽기 전용 내비 SDK 연결은 PASS였으나 경로 상태 콜백은 제공되지 않았다. 자동 편성·계층·미디어 세션 회귀도 PASS다.
- 실제 위치 회귀는 첫 화면에서 유효 표본 2개를 저장하고, Activity 종료 후 구간 닫힘·저장 중단까지 확인했다. 검사 종료 시 누적 유효 표본은 11개였으나 복귀 후 새 구간 UPDATE는 확인하지 못했고, 검사 코드의 `no_live_location_after_return`과 cleanup 실패로 전체 결과는 **FAIL(47,911ms)**이다. Home/background 전환은 이번에도 미검증이며 실패를 성공으로 합산하지 않는다. [원본 결과](../../artifacts/milestone-26/app-location-live/app-location-live-privacy.json). 원인은 후속 단계에서 조사한다.
- HTTPS 응답은 `no-store`, 제공자·보조 출처를 명시한다. 요청 좌표·응답 geometry·제공자 비밀키를 로그나 경로 캐시에 남기지 않는다. 원본 응답 바이트는 decode 뒤 지운다.
- 네트워크는 2개 실제 worker와 15초 상한, 투영은 1 worker/1 대기 작업으로 제한한다. 취소 시 플랫폼 disconnect가 다른 요청의 timeout이나 UI를 막지 않도록 별도 제한 실행기로 처리한다.
- 기존 사용자 DB·기기 토큰·위치 기록 동의를 보존했다. 기존 토큰의 개발 만료일만 갱신하고 서버 근거 파일은 기존 2개 사실을 보존한 20개 사실로 병합했다. 설정 백업은 `.local/server/checkpoints/p0-dev25-20261109/`에 있다.
- 현재 서버는 `python -m petasos_server`의 기존 loopback TLS 진입점을 사용한다. OpenRouter 검토 기한은 만료 상태 그대로이며 생성 요청 전 검증이 계속 거부한다. 경로 검증은 모델을 호출하지 않는다. 계정 설정의 변경 여부는 사용자 답변 대기다.

이 경로는 **네이버가 계산한 보조 예상**이다. 동일 목적지라도 활성 Pleos 도로 경로와 같다고 간주하지 않으며, 지역 카드·실제 방문·도로 수의 근거로 저장하지 않는다. 실제 Pleos 목적지와 실제 위치를 함께 사용하는 기기 검수, 방문 정책 검토, 마이크·차량 검수, 최종 UI·데모는 별도로 남아 있다.
