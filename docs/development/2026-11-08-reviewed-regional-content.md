# dev.20: 검토된 이야기–지역 연결

2026-11-08. 지역별 콘텐츠를 준비할 수 있도록 검토 입력·컴파일·설치·런타임 검증을 연결했다. 이전 설치기는 이야기의 `region_id`를 항상 NULL로 기록했으므로, 검토한 자료가 있어도 지역 후보로 공급할 수 없었다. 이번 변경은 **검토된 소속 근거를 가진 콘텐츠를 설치하는 기반**이며, 실제 지역별 이야기를 추가하거나 실제 이동 기반 자동 청취를 활성화한 결과는 아니다.

## 구현 범위

콘텐츠 bundle v2는 기존 필드를 유지하고 `regionBindings`만 추가한다. 한 이야기의 지역 ID·정확한 지역 revision·코드·dataset·이름·geometry 출처와, 소속을 입증하는 사실 revision·검토 시각·근거를 명시한다. 컴파일러와 Android codec은 같은 참조·시간·형식·권한 조건을 검사한다. 이름·관광 API 코드·좌표 근접성으로 소속을 추론하지 않으며, 전승을 행정구역 소속 사실로 사용하지 않는다. 상세 입력은 [v2 계약](./regional-content-bundle-v2.md)을 따른다.

v1의 기존 출력·UUID·본문 해시와 stable source/fact/story ID 생성 규칙은 유지한다. v2로 저장한다는 이유만으로 기존 ID를 새로 발급하지 않는다. 생성 API와 서버 evidence의 `sources`·`facts` 계약도 유지하며, 지역 geometry 출처와 검토 메모가 자동으로 모델 입력에 추가되지 않는다. 표시·배포와 생성 허가는 계속 분리한다.

Room 버전은 1에서 2로 올리고 `story_region_binding` 메타데이터 테이블과 제약을 추가했다. 새 스키마는 제품 테이블 **25개**, 제약 트리거 **155개**다. canonical v0.8의 생성된 기존 테이블·사용자 기록 구조는 변경하지 않는다. 마이그레이션은 기존 행을 유지하는 추가 방식이며 파괴적 재생성으로 대체하지 않는다. 설치기는 기존 지역 revision의 ID·코드·dataset·이름·출처와 연결 근거를 대조하고, 콘텐츠와 소속 메타데이터를 하나의 트랜잭션으로 저장한다.

지역 소속 검토는 `RoomRegionalContentBindings`에서 처리한다. 지역 후보 조회와 출력 직전 재검사, 생성 snapshot·설치에서 현재 근거를 확인한다. 연결이 철회되거나 지역·사실·출처 또는 현재 사용 권한이 달라지면 예전 후보와 생성 결과를 그대로 승인하지 않는다. 이후 생성 버전의 발화 문단에서 소속 사실을 생략해도 별도 연결 테이블의 근거와 현재 권한을 계속 검사한다.

후보 조회는 검토된 지역 적격성을 확인한 뒤 지역 우선순위와 최대 **64개** 제한을 적용한다. 단순히 `story.region_id`만 채운 미검토 후보가 먼저 나열돼 검토된 지역 이야기를 밀어내는 경우를 방지한다. 관심사 후보, 완료 이야기·stable fact 반복 방지와 세션 제외 조건은 유지한다.

설치·메타데이터 검증·편성은 파일과 책임을 분리했다.

| 계층 | 변경 위치·역할 |
|---|---|
| 검토 자료 컴파일 | `server/petasos_server/content_bundle.py`: v1 호환, 명시적 v2 연결과 참조 검증 |
| Android 입력 계약 | `content/ContentBundle.kt`, `ContentBundleCodec.kt`: 동일한 필드·범위·검토 조건 |
| DB 확장 | `storage/RegionalContentSchema.kt`, `StorageDatabase.kt`: 추가 테이블·제약·1→2 마이그레이션 |
| 설치·현재 근거 | `storage/RoomContentInstaller.kt`, `RoomRegionalContentBindings.kt`: 원자적 설치·현재 연결 검증 |
| 사용 지점 | `RoomAutomaticStoryStore`, `RoomStoryGenerationStore`: 현재 연결을 후보·생성 권한에 반영 |
| 기기 검증 도구 | `regional-content`: 폐기 가능한 DB와 합성 콘텐츠의 마이그레이션·설치·권한 검사 |

UI·스타일·편성 시간 계산·실제 위치 공급자에는 이 연결 형식을 직접 넣지 않았다. 향후 지역 자료를 추가할 때는 검토 bundle을 준비하며 편성 알고리즘을 다시 수정할 필요가 없다.

## 검증 상태

아래는 이 기록 작성 시 완료된 결과다. 초기·중간 실행을 합산하지 않는다.

| 검증 | 결과·근거 |
|---|---|
| Android JVM | **725개 / 53 suite PASS**, 실패·오류·skip 0. [집계](../../artifacts/milestone-20/android-jvm-results.json), [실행 로그](../../artifacts/milestone-20/android-jvm-compile.log) |
| 서버 | **208개 PASS**. [로그](../../artifacts/milestone-20/server-tests.log) |
| 호스트 도구 | **143개 PASS**. GIS 의존성이 있는 `.local/region-venv` 사용. [로그](../../artifacts/milestone-20/host-tests.log) |
| privacy 앱·검증 APK | **빌드 PASS**. [로그](../../artifacts/milestone-20/android-privacy-build.log) |
| Lint | **PASS**. [로그](../../artifacts/milestone-20/android-privacy-lint.log) |
| 기기·마이그레이션 관측 | **동일 최종 APK 9종 PASS**: regional-content, storage, automatic-planning, location-storage, story-generation-storage, session-recovery, region-storage, media-session, app-playback. 실제 Room 1→2 데이터 보존과 공개 이야기 TTS 재생·완료 저장 확인. |
| 설치·실행 및 최종 APK 확인 | **PASS**. dev.20 전면 실행, 설치 해시 일치·활성 녹음 0·기존 HTTPS health 200. [최종 집계](../../artifacts/milestone-20/verification.json) |

신규 자동 검사는 v1 출력의 기존 해시, v2 결정적 컴파일, 잘못된 버전·추가/누락 필드·참조·중복 연결·시각·전승 근거 거부, 생성 허가의 독립성, CLI 원본 바이트 보존과 privacy probe의 합성 검증 범위를 확인한다. `regional-content` 기기 probe의 결과에는 실제 위치·오디오·모델 호출·동의 변경을 포함하지 않는다.

최종 소스 541개가 snapshot과 일치한다. APK 쌍은 `.local/builds/dev20/privacy-final/`에 보존했다. 앱 SDK의 알려진 로그 sink 정적 검사는 통과했으며 별도 SpeechService 로그 문제가 해결됐다는 뜻은 아니다. 기본 debug 빌드·Lint, 새 외부 모델/공공데이터 호출, 사람 발화·실제 위치 수집은 이번 범위가 아니다.

## 명확히 남은 범위

- **실제 지역 seed는 이번에 설치하지 않았다.** 기존 가거도 이야기와 장소의 NULL 지역·좌표를 유지한다.
- 기존 stable story ID의 NULL 지역을 나중에 바꾸는 재매핑은 아직 지원하지 않는다. 동일 ID의 metadata 변경은 immutable 충돌로 거부한다. 이 제한을 우회하려고 같은 이야기를 다른 ID로 중복 등록하는 작업도 하지 않았다. 기존 자료의 재매핑에는 별도의 명시적 검토·갱신 계약이 필요하다.
- 소속 연결은 **RegionPolicy 승인이나 방문 증명과 다르다.** 기존 지역 `verified_at`, 방문·안내 정책, 지역 카드, 장소 좌표·랜드마크 정책을 승인하지 않았다.
- 실제 위치의 품질과 경로별 진입·이탈 예상시간이 필요한 자동 지역 청취의 통합 검수는 남아 있다. 전체 도로 경로를 목적지·경유지 직선으로 대신하지 않는다.
- 마이크 실발화와 UI·데모 확정은 이번 변경 범위가 아니다. Virtual Console 보류와 기존 동의·권한 설정을 유지한다.

위치·음성의 다음 작업은 [공식 API로 진행할 연결 경로](./2026-11-08-official-only-platform-path.md)를 따른다. 공식 Android 제공자 능력을 좌표 수집 없이 점검하고, 사용 가능한 범위를 독립 어댑터로 구현하며 ON_DEVICE 음성의 합성 수명주기·시간 검증을 이어갈 수 있다. 업체 문의 답변이 모든 개발의 선행 조건은 아니다. 실제 수집·발화가 필요한 단계에서는 기존 동의·개인정보 조건을 별도로 충족해야 한다.
