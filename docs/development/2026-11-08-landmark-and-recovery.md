# dev.22: 주요 랜드마크 후보와 재시작 후 음악 간격

2026-11-08. v0.8 PR-06/LOC-04의 주요 랜드마크를 기존 자동 편성에 연결했다. 단일 후보·이야기 연속성·이야기 사이 음악 정상 완료 조건을 유지한다. 공식 FusedLocation·ON_DEVICE 음성 SDK, 위치 동의, 실제 정책, UI 외형은 변경하지 않았다.

## 구현

- `location/LandmarkApproach`: 검토된 좌표·반경·정책·품질 어댑터와 실제 측정 두 점으로 접근/통과를 판정한다. 수신 콜백 수나 이름·목적지 직선으로 접근을 추정하지 않는다. 측정 시각과 수신 시각, 위치 오차를 함께 검사한다.
- `planning/LandmarkCandidateSession`: 후보 하나와 세션 내 통과·만료·재접근 이력을 소유한다. 대화/일시정지/차단 중에도 지나간 후보를 폐기한다. 같은 장소의 새 후보는 cooldown 이후 실제 반경 이탈과 새로운 접근이 필요하며 이전 ID를 되살리지 않는다.
- `storage/RoomLandmarkCandidates`: 현재 published 콘텐츠, 전역 완료 story/stable fact 이력, 세션 제외, 검토 정책과 독립 좌표·이야기 연결 근거를 확인한다. 연결 사실을 생성 문단에서 생략해도 철회를 검사한다. 일반 후보의 적격성 코드는 `RoomAutomaticStoryEligibility`로 공유한다.
- `AutomaticStoryPlaybackBridge`: 일반 이야기와 랜드마크를 하나의 planner/offer/최종 출력 권한으로 합친다. 랜드마크는 다음 허용 자동 슬롯의 우선 후보다. 현재 이야기를 중단하거나 음악을 건너뛰는 별도 재생 경로를 만들지 않았다.
- `PetasosSessionHost`: 실제 위치의 유효성과 미래 지역 계획의 준비 여부를 분리했다. 일반 지역 이야기는 기존 길이 기반 대상이 준비되어야 한다. 내비 상태 UNKNOWN/INACTIVE/ACTIVE를 구분하며 명시적으로 확인한 INACTIVE에서만 현재 위치 모드를 허용한다. 활성 경로에는 별도 경로 포함 근거가 필요하다.

위치 batch에서는 현재 입력 permit을 가진 마지막 저장 점만 사용한다. 이전 batch의 실제 저장 점과 비교하므로 한 batch 안에서 끝난 짧은 접근을 놓칠 수 있지만 오래된 점을 현재 위치로 재생하지 않는다. [상세 설계](./2026-11-08-landmark-encounter-design.md), [검토 정책 11필드](./reviewed-landmark-policy.md).

## 재시작 복원 보완

마지막 이야기 문단을 저장한 직후 음악 시작 전에 프로세스가 종료되면 기존 Room의 idle checkpoint가 음악 간격 조건을 잃을 수 있었다. 이제 같은 이동의 직렬 등록된 playback attempt에서 마지막 문단 완료와 음악 시도를 읽어 이 조건을 복원한다. 실제 시작·완료·단조 시각·재생 위치 증거가 있는 정상 음악 완료만 조건을 해제한다. 중단·건너뛰기·prepared 상태는 완료가 아니다.

벽시계 순서, 카드, checkpoint의 마지막 문단 참조만으로 완료를 추정하지 않는다. 새 세션에 출력이 없어도 같은 이동의 조건을 유지하고 다른 이동에는 상속하지 않는다. 콘텐츠 권한이 철회되어도 과거 청취 사실은 남는다. 복원은 자동 재생·녹음의 권한이 아니며 명시적 Play가 필요하다. Room schema 2와 canonical 테이블/제약은 그대로다.

등록 전 메모리에만 존재하고 실제 출력도 시작하지 않은 음악 선택은 복원 근거가 없다. 이번 수정은 커밋된 마지막 문단과 등록된 음악 시도의 복원 범위다.

## 검증

- JVM **768개 / 57 suite**, 호스트 **158개**, privacy 앱·검증 APK 빌드와 Lint PASS.
- 동일 최종 APK 기기 **10종 PASS**: landmark-storage, recovery-music-gap, automatic-planning, regional-content, session-recovery, location-storage, story-generation-storage, navigation, media-session, app-playback.
- 새로운 Room 시험은 독립 근거 철회·생성 문단 생략·65개 후보와 음악 전/후 crash window·중단/skip·벽시계 역행·이동 격리를 확인했다. 실제 읽기 전용 NaviHelper 연결/상태 콜백과 공개 이야기의 공식 TTS 재생도 확인했다.
- 알려진 앱 SDK 로그 sink 정적 검사, 설치/실행 smoke, 소스 **552개** 일치 PASS. dev.22 전면 실행·설치 APK 해시 일치·활성 녹음 0·기존 HTTPS health 200을 확인했다.

최종 APK는 `.local/builds/dev22/privacy-final/`, 해시와 시험 범위는 [집계](../../artifacts/milestone-22/verification.json)를 따른다. 서버 208개는 무변경 dev.20 결과이며 이번 단계에 다시 실행하지 않았다. debug 빌드/Lint, 새 외부 모델·공공데이터 요청, 사람 발화·실위치 주행은 수행하지 않았다.

초기 통합 테스트의 음악 완료 이벤트에 필수 position이 빠진 fixture 오류를 보완했다. 제품의 정상 완료 조건을 완화하지 않았다. 이 초기 실패와 복구 보완 전 760개 결과는 별도 로그에 보존하며 최종 768개와 합산하지 않는다.

## 실제 활성화와 구분

실제 랜드마크 좌표·접근 정책을 승인하거나 정책 설치 도구를 추가하지 않았다. 기존 데이터의 NULL 정책은 유지한다. 실제 FusedLocation 시각/정확도 의미 어댑터, 검토된 지역·랜드마크 정책과 콘텐츠, 활성 경로의 지역 통과 시간/랜드마크 포함 공급은 별도 준비가 필요하다. 합성 좌표·Room 검증은 실제 주행 수락을 대신하지 않는다.

STT는 dev.21에서 합성 공식 ON_DEVICE EOF와 시간 기준을 검증했다. 별도 SpeechService의 문장 로그가 남아 있어 제품 마이크는 여전히 비활성이다. 사람 발화, Virtual Console, 실제 Suspend 및 최종 UI/데모 검수는 수행하지 않았다. 업체 연락을 전체 개발의 선행 조건으로 요구하지 않는다.
