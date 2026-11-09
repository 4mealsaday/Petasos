# Pleos 내비 동반 UI와 지역 실루엣 앨범

> **dev.36 최신 정정:** 요청한 화면은 내비 위 Petasos 버튼·자막의 동시 표시다. 홈·내 여행 UI를 재구성했지만 현재 공식 표시 경로가 확인되지 않아 ON은 OFF를 유지한다. 아래 dev.34/35 내용과 검증은 당시 구현의 기록이며 현재 overlay 완료를 뜻하지 않는다. [현재 상태](companion-home-ui.md), [공식 지원 조사](pleos-navigation-overlay.md).

## 사용자 정정 — Pleos 내비와 함께 사용하는 앱

2026-11-09 dev.34 화면 확인 뒤 사용자가 역할을 명확히 했다. **목적지·경로 표시·길 안내는 Pleos Navigation이 맡고, Petasos는 함께 실행되는 지역 이야기·대화 앱이다. 자체 지도는 기록 화면에 사용한다.** dev.34의 듣기 화면에 네이버 도로 지도를 넣은 것은 개발 측의 잘못된 해석이다. 아래 dev.34 설명과 검사 결과는 구현 이력이며, 해당 듣기 화면 구성을 최종 제품 방향으로 적용하지 않는다. 정정 당시에는 문서·SDK·코드만 점검했다. 후속 dev.35 구현은 [주행 청취 연결](pleos-companion-session.md)을 따른다.

- Petasos에서 목적지를 선택하거나 경로를 보여달라고 요청하면 공식 NaviHelper를 통해 Pleos Navigation에 전달한다. 자체 길 안내 엔진이나 별도 활성 경로를 만들지 않는다. 기존 네이버 Directions 보조 예측은 실제 Pleos 선택 경로와 구분하는 기존 계약을 유지한다.
- `requestRoute`는 목적지 설정 요청, `requestNavigationScreen`은 Navigation 화면 전환 요청이다. 후자는 지도 View/Bitmap을 반환하거나 다른 앱에 Petasos UI를 삽입하는 API가 아니다. [NaviHelper 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro), [목적지 설정](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions).
- Pleos는 앱 영역에 최대2개 앱을 함께 표시하는 멀티윈도우를 지원한다. 실제 배치는 차량/디스플레이 및 사용자의 window 조작에 따른다. 이는 내비 위 임의의 모자 버튼·하단 자막 오버레이를 지원한다는 뜻은 아니다. [공식 화면 모델](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model).
- `PleosNavigationSource`는 정보 읽기 전용으로 유지하고 화면 요청 어댑터를 분리했다. dev.35는 명시 재생으로 시작한 foreground location 세션이 내비 전환 뒤 위치 수신·기록을 유지하도록 구현한다. 실제 검증 결과는 별도 집계를 따른다.
- 자막·모자 버튼은 Petasos Activity 안에서만 표시한다. dev.35는 실제 포커스 GAIN과 출력 종료 확인 후 복귀를 연결한다. 기존 주행 화면 제한·마이크 보류를 임의 해제하지 않는다.

다음 구현 순서는 내비 화면/목적지 명령 계층 → 명시적 주행 세션이 소유하는 위치 수명주기 → 내비 음성 우선·포커스 복귀 → 멀티윈도우와 실제 앱 전환 검증이다. 자막/모자 버튼의 외부 표시 방식은 공식 지원 범위를 확인한 뒤 구성하며, 자체 지도 화면으로 대체하지 않는다. 기존 관심사·지역 실루엣 앨범·기록 지도·대본/편성 로직은 이 역할 정정과 분리해 유지한다.

## dev.34 구현 이력

2026-11-09 사용자 합의에 따라 Android 제품 화면을 파랑·하늘색 중심으로 변경했다. 마스코트 대신 실제 지역 경계의 실루엣을 사용한다. 수집되지 않은 지역은 어두운 실루엣, 수집된 지역은 파란색으로 표시하며 기존 수집 판정·정책·proof 규칙을 유지한다.

## 화면 흐름

- 처음 실행: 소개 → 위치 이용 설정 → 큰 관심사 격자 → 명시 저장 성공 후 듣기 화면. ‘먼저 둘러보기’로 건너뛸 수 있다. 소개 확인 표시는 UI 설정이며 위치 동의나 권한을 자동으로 바꾸지 않는다.
- 듣기: 지도 위 원형 날개 모자 버튼, 지도 아래 가로 재생·자막 바. 현재 재생 내용만 표시한다. 다음 이야기 후보·제목·시간·마커는 노출하지 않는다.
- 내 기록: 나의 지도 / 지역 앨범 / 지난 여정. 지역 상세 안에 끝까지 들은 이야기와 그 지역을 처음 적립한 여정을 연결한다. 여정 상세에서 실제 기록 경로와 저장 요약을 본다. 이야기만의 독립 앨범 탭은 없다.
- 설정: 위치 기록·권한, 12개 관심사와 직접 입력, 자막, 명시 이야기 선택, 출처, 여정 종료. 처음 설정과 같은 관심사 컴포넌트를 재사용한다.

관심사 타일은 역사, 문화·전통, 건축, 자연·생태, 지리·지명, 과학·기술, 음식, 스포츠, 기업·산업, 예술, 지역의 삶, 인물이다. 선택은 최대20개 저장 규칙 안에서 기존 키워드와 합산하며 기본 선택이나 태그 확장을 하지 않는다. 새 관심사 선택 가능 여부와 해당 분야의 검토 콘텐츠 보유량은 다르다.

## 지도와 기록의 의미

제품의 네이버 지도는 공식 Android SDK의 도로 배경과 Petasos가 이미 확인한 현재 위치를 표시한다. 네이버 SDK의 별도 위치 공급자·추적 모드는 연결하지 않는다. [기존 Application의 공개 Client ID](naver-route-setup.md)를 재사용하며 Secret은 APK에 넣지 않는다. 도로 지도 표시는 Pleos 선택 경로·회전 안내와 별개다. SDK 인증·네트워크 실패 시 공식 경계 기반 오프라인 지도에 머문다.

지역 실루엣261개는 기존 NGII 공개 경계의 display-only 단순화 결과이며 격리된3개는 제외했다. `scripts/build_records_map.py`가 원본 pgeo 해시를 확인하고 재생성한다. 이 그림은 실제 지역 판정이나 정책 활성화 입력으로 사용하지 않는다. 원출처·라이선스는 [지역 자료 정리](../reference/regions/README.md)를 따른다.

기록 지도는 저장된 실제 표본만 잇는다. 표본 순서·시각·수신 품질·제공자·epoch·경로revision·기록segment·clockscope가 불연속이면 선을 끊는다. 관측 간격10초 초과도 연결하지 않는다. 전체 경로는 연한 하늘색, 선택 여정은 진한 파란색이다. 최초 적립proof만 저장된 지역의 관련 여정을 전체 재방문 목록처럼 표시하지 않는다.

화면 조회는 읽기 전용이며 수집·요약·관심사 저장 규칙을 바꾸지 않는다. 지도는 최신 최대30,000개 표본으로 표시하고 한계를 안내한다. 여정20개, 앨범12개 단위로 페이지를 나눈다. 지역 상세는 이야기50개·최초 적립 여정20개 한도를 표시하며, 전체 여정은 지난 여정 페이지에서 탐색한다.

## 자막·대화와 데모

제품 자막은 실제 TTS `STARTED` 이벤트가 확인된 현재 문단을 메모리에만 둔다. 단어별 시간 동기화는 아니다. 긴 문단은 가로 바 안에서 스크롤해 읽을 수 있다. 화면 숨김·정지·만료·철회 때 지우며, 이전 콜백이 새 문단을 덮거나 복원하지 못한다.

별도 `Petasos Demo`는 외부SDK/권한/제품DB와 분리한 무음 시나리오다. 명시 재생 후 가상 시각이 자동 진행하고, 음악 재생 바처럼 탐색할 수 있다. 탐색은 기존 시연의 저장 종료를 확인한 뒤 합성fixture와 명령 이력을 해당 시각까지 재연하며 탐색 후 정지한다. 되감은 뒤 조작하면 기존 미래 분기를 폐기한다. 경로 변경·위치 입력 중단은 동적 재편성 검토용이다.

데모의 문장은 공개 준비 대본 미리보기이며 실제 오디오 자막이 아니다. 실시간 마이크·모델 대화는 연결되지 않았다. 모자 버튼은 이 상태를 안내하며 녹음·전송하거나 가짜 대답을 생성하지 않는다. 별도 SpeechService 원문 로그 문제와 실제 대화 검수는 기존 미완료 범위로 유지한다. Virtual Console도 보류한다.

## 파일 책임

| 위치 | 책임 |
| --- | --- |
| `res/layout/activity_main.xml`, `view_trip_controls.xml` | 제품 화면·설정 배치 |
| `res/values/colors.xml`, `styles_journey.xml`, `strings_journey*.xml` | 색·스타일·고정 문구 |
| `presentation/JourneyShell.kt` | 페이지 전환·첫 설정·표시 수명주기 |
| `presentation/interests/` | 타일 UI와 명시 저장 전 draft |
| `presentation/records/` | 지도·실루엣·앨범·읽기 전용 화면 모델 |
| `storage/RoomRecordsAtlasStore.kt` | 저장된 proof·여정·표본 조회 |
| `presentation/map/`, `src/productMap/` | 지도 표시 인터페이스와 제품용 네이버 어댑터 |
| `runtime/CaptioningRuntimeSpeech.kt` | 음성 실행을 변경하지 않는 현재 문단 관찰 |
| `src/demo/` | 격리 시나리오·탐색·데모 화면 |

스타일·배치·아이콘 교체는 지역 선택 알고리즘이나 수집 저장 로직과 분리돼 있다. 제품은 기존 CarUx의 정차 화면 제한을 유지한다. 운전 중 오버레이 허용을 새로 획득한 구현은 아니다.

## 검증

dev.34 제품·demo 앱/검증 APK 빌드와 설치·실행을 완료했다. 기존 제품 데이터·동의·키워드를 초기화하지 않았다. [검증 집계](../../artifacts/milestone-34/verification.json)를 따른다.

- 최종 제품 JVM 1,046개/85 suite, demo JVM 1,065개/88 suite: 실패·오류·생략0. 두 변형의 공통 테스트를 합산하지 않는다.
- 제품·demo Lint PASS. 이후 검토에서 기록 다시 듣기의 화면 전환→재생 순서, 연결 카드 문구, demo 취소된 tick의 Stop 이력 누락을 보완했고 최종 빌드/JVM을 다시 통과했다. Lint 보고서는 이 작은 후속 수정 전 결과임을 보존한다.
- 실제 Pleos API34/user10, 같은 최종 제품 APK의 `car-policy-ui` 55ms, `trip-results` 420ms, `journey-ui` 5,610ms, `app-playback` 5,431ms PASS. 별도 demo UI 검사는 9,894ms PASS.
- `journey-ui`: 실제 MainActivity/CarUx, 관심사12개 큰 타일, 읽기 전용 기록 지도, 실루엣261개/22페이지와 지역 상세, 탐색 후 동일 관심사, 네이버 SDK 첫 지도 READY를 확인했다. 관심사 저장·동의·권한은 변경하지 않았다. 기존 공개 Client ID 재사용이 실제 인증에 성공했다.
- `trip-results`: 임시 Room에서 수집 근거·완료 이야기 연결, 조회 시 무변경, 경로 단절·페이지 제한을 확인했다. `app-playback`: 제품의 공개 이야기 실제 TTS 완료·저장·정지 회귀이며 Activity 자막의 실제 시간 동기화 검사가 아니다.
- demo: 실제 UI 자동 tick·탐색·경로 변경·되감기 후 분기·화면 이탈 취소를 확인했다. 추가 JVM 회귀는 tick 취소/출력 선행 정지에서도 STOP을 한 번 남기고, 실패한 정지는 기록하지 않는 것을 확인한다.
- 제품 APK의 설정된 비밀값3개 부재와 speech 알려진 로그 sink 검사를 통과했다. demo APK에는 네이버 SDK/native/key resource가 없고 권한0이다. 이는 별도 SpeechService의 원문 로그 문제 해결을 뜻하지 않는다.
- 실제 2560×1440 화면의 소개·듣기·설정·기록 지도·앨범·demo 배치를 직접 확인했다. [듣기 화면](../../artifacts/milestone-34/product-listen.png), [지역 앨범](../../artifacts/milestone-34/product-album.png), [데모 화면](../../artifacts/milestone-34/demo-playing.png). 현재 위치가 확인되기 전에는 기본 지도와 안내를 표시한다.

네이버 지도는 실제 인증/첫 타일 로딩까지 검증했으며 실제 Pleos 선택 경로·주행 중 오버레이·위치 오차 수용·지도 장애 주입 검사를 대신하지 않는다. 실제 대화·사람 발화와 Virtual Console은 기존 보류 범위를 유지한다.
