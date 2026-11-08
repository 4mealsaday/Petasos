# 대표 지역 전환 시나리오

2026-11-09, dev.30. 기존 제품 planner·runtime·Room을 한 시나리오로 조립해 **이야기 길이로 다음 지역 선택 → 음악 → 정지/재개 → 경로 변경 → 새 지역 이야기**를 검사한다. 이전 제품 음악 probe의 ‘다음 출력이 시작됨’보다 정확한 story ID와 planning revision을 확인하는 보완이다.

`androidTest/storage/RepresentativeRouteRuntimeProbe.kt`는 별도 Room DB에 고정 공개 이야기를 설치한다. 위치·경로 시간창·시계·음성 완료 callback은 합성이다. 음악은 `synthetic-silent-boundary-track`이라는 명시적 10초 무음 시험 포트이며 실제 음원 길이나 재생을 주장하지 않는다. 실제 Host·MediaService·Activity·SDK·마이크·네트워크·제품 DB를 열지 않는다.

1. 서울284 이야기의 고정 예상 길이 34초와 음악 10초로 44초 뒤 안성 이야기를 준비한다.
2. 첫 이야기의 합성 완료 후 음악을 시작하고, 2초 위치에서 Stop하여 중단 checkpoint를 보존한다.
3. 정지 중 경로 revision을 교체해 이전 안성 후보를 철회한다. 경로 변경 자체로 재생 의도가 복구되지는 않는다.
4. 명시적 Play 뒤 음악 2초부터 재개한다. 정지 지연을 포함한 새 시작 시각과 대전 엑스포 대상 revision을 확인한다.
5. 음악 완료가 Room에 먼저 커밋된 후 exact 엑스포 story가 자동 시작되어야 한다. 안성 이야기의 시작·준비 횟수는 0이어야 한다.
6. 마지막 Stop 뒤 활성 출력이 없고 위치·음성·생성 입력이나 지역 카드가 없음을 확인하며 별도 DB/시험 키를 정리한다.

이 시나리오는 실제 네이버/Pleos 경로와 사용자 위치의 결합, 실제 TTS·음악 청취, 차량 Suspend/주행/백그라운드 수용 검사를 대체하지 않는다. 공개 이야기의 실제 재생은 dev.28, 실제 네이버 경로 조회는 dev.29 등의 별도 결과를 따른다. 최종 UI 데모 화면은 이 입력 분리를 유지한 별도 후속 작업이다.

실행: `python3 scripts/probe-pleos.py representative-route-runtime --build-type privacy --artifacts-dir artifacts/milestone-30/representative-route-runtime`. 동일 최종 dev.30 privacy APK에서 **221ms PASS**했다. exact story·revision·음악 완료 저장 순서·2초 Stop/재개·제품 입력 격리 marker를 모두 확인했다. [기기 결과](../../artifacts/milestone-30/representative-route-runtime/representative-route-runtime-privacy.json), [전체 검증](../../artifacts/milestone-30/verification.json).
