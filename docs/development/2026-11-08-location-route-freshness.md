# dev.19 — 위치 신선도와 경로 변경 권한

현행 v0.8과 사용자가 지정한 **곧 통과할 지역 → 생성된 이야기 길이와 음악 → 다음 지역, 변화 시 동적 재편성**을 유지한다. 이 단계는 실제 위치·경로 연결 전에 발견한 만료와 비동기 처리 오류를 수정했다. 실주행 자동 지역 청취를 완성한 단계는 아니다.

## 바뀐 동작

1. **수신으로 측정 나이를 초기화하지 않는다.** 이전에는 normalizer가 측정 나이를 검사해도 후속 계획이 수신 후 10초를 다시 허용할 수 있었다. `LocationFreshness`는 수신 때 소비한 나이를 제외한 남은 시간을 단조 시각 deadline으로 전달한다. 9초 지난 위치라면 수신 후 1초만 남는다. current/upcoming region, landmark, 자동 생성, 출력 직전이 같은 한계를 따른다. 측정·수신 원본값과 Room schema는 보존한다.
2. **오류 뒤 저장되는 과거 표본은 현재 권한이 아니다.** NoLocation/권한 오류/단절이 pending batch를 flush해도 자동 편성을 복구할 수 없다. main 콜백별 고유 permit을 정확한 point ID에 묶어 재시도까지 유지하고, 계획 문맥에는 그 표본의 불변 검증 함수를 넣는다. 같은 밀리초의 새 표본이나 새 세션이 이전 토큰을 대신할 수 없다. 최종 worker 근거 조회와 main SDK 직전에도 철회 여부를 검사한다. 위치가 필요 없는 명시 관심사 경로는 유지한다.
3. **경로 변경 시 기존 후보를 즉시 철회한다.** 목적지·옵션·경유지·재탐색 이벤트를 반영하고 완료 후 새로운 상태/identity를 기다린다. 취소·오류 뒤 늦은 완료/identity로 경로를 복구하지 않는다. 새 경로 시작 또는 새 source가 필요하다. 경유지 도착·잔여 시간 변화는 경로 변경과 구분한다. 시뮬레이션 route는 실제 이동 예측에 쓰지 않는다.
4. **내비 ingress와 worker 사이의 지연을 검사한다.** main에서 새 이벤트를 받는 즉시 세대 번호를 증가시킨다. 이전 worker가 뒤늦게 갱신해도 그 번호에 묶인 생성·출력 권한은 거절된다. 이미 시작한 일반 이야기의 연속 재생과 Coordinator의 문단/음악 정상 완료 경계는 유지한다.

## 공식 자료 재확인

- [위치 감사](../../artifacts/milestone-19/official-location-contract-audit.json): 공식 Maven 최신 FusedLocation 1.1.3, source/Javadoc 및 전송 모델까지 확인했다. timestamp/confidence는 원점·측정 기준·정확도 단위 보장 없이 복사된다. 표준 Android Location 정의를 Pleos 제공자 계약으로 대신 적용하지 않는다.
- [내비 감사](../../artifacts/milestone-19/official-navigation-contract-audit.json): NaviHelper 3.0.5와 navi-data 3.0.4, 공식 동적 본문을 대조했다. 목적지/경유지와 traffic 비율은 전체 도로 polyline/구간 ETA가 아니다. 임의 직선·총 ETA 비례 보간으로 미래 지역을 만들지 않는다.
- [문의 초안](pleos-location-route-support-request.md)에 시간·정확도·전체 경로·콜백 순서 계약을 정리했다. **미전송**이다.

## 검증과 한계

JVM **714개 / 52 suite**가 실패·오류·skip 없이 통과했다. 이전 662개에서 52개 회귀를 추가했으며, 실제 `LocationRecorder`를 사용한 pending flush·재시도·콜백 순서 검사 7개를 포함한다. Android 검증 코드는 실제 Room 후보를 사용해 오래된 측정값이 worker/최종 출력 검사에서 정확히 만료되고 관심사 후보가 유지되는지 검사한다. 합성 위치이며 차량 GPS를 주입하거나 위치 동의를 바꾸지 않는다.

최종 privacy 앱/검증 APK 빌드·Lint·알려진 앱 SDK 로그 sink 검사와 동일 APK의 기기 **5종**(자동 편성, 위치 저장, 읽기 전용 내비, MediaSession, 실제 제품 TTS)이 PASS다. 내비는 실제 연결과 경로 상태 콜백 수신을 확인했다. 설치·실행 smoke도 통과했고 dev.19를 전면에 두었다. 535개 소스 해시가 일치하며 06:42:59Z에 설치 APK 해시 일치·앱/서비스 활성 녹음 각각 0·기존 HTTPS health 200과 reverse를 확인했다. 녹음 수치는 시점 관측이다.

해시와 시험 범위는 [집계](../../artifacts/milestone-19/verification.json)를 따른다. 서버 191개는 milestone-17, 호스트 137개는 milestone-18의 무변경 코드 기검증이며 이번에는 재실행하지 않았다. debug 빌드·과거 전체 기기 suite·실제 모델 호출·사람 발화·위치/차량 변경도 이번 범위가 아니다. APK 쌍은 `.local/builds/dev19/privacy-final/`에 보존했다.

실제 위치 의미는 `UNKNOWN`, 전체 도로 경로 공급자는 `UNAVAILABLE`이다. dev.18 공식 경계 261개 정상/3개 격리와 모든 지역 방문 정책 `pending`도 유지한다. 경계 기준일·방문 정책·지역별 콘텐츠 연결 검토, 공식 위치/활성 경로 공급 계약을 확보한 뒤 실제 지역 최초 생성→길이 반영→음악 후 다음 지역의 통합 검증이 필요하다. 마이크와 Virtual Console 보류, UI 디자인 후순위 결정은 유지한다.
