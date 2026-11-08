# 현대자동차그룹 차량용 앱 개발 공모전 — 세션 참고 요약

**개발 착수 인계 — 2026-11-06:** [DEVELOPMENT_HANDOFF.md](./DEVELOPMENT_HANDOFF.md)를 먼저 읽는다. Pleos Connect 2.1.0 에뮬레이터는 사용자 제공 화면에서 홈 화면 실행을 확인했다. Virtual Console 연결은 사용자가 보류했다. 아래의 과거 설치 진행·아이디어 미결정 상태를 최신 상태로 적용하지 않는다. 앱 설치·위치·TTS/STT·차량 이벤트는 아직 실행 검증 전이다.

기준일: **2026-11-04 (KST)**. 사용자와 친구가 함께 지원할 예정이며, 아래 본문은 당시의 **아이디어 탐색 단계** 기록이다. 이 문서는 Notion 기록과 연결된 공식 문서를 검토한 요약이며, SDK 설치·계정별 권한·실제 실행까지 검증한 결과는 아니다.

**2026-11-05 최신 결정:** 브랜드는 **Petasos**다. 최신 기획은 [Petasos 기획서](./Petasos_기획서.md)와 [Notion 원본](https://app.notion.com/p/40fcf80747f2801fb55ec86bc8349426)을 우선한다. 지역 이야기를 듣다가 버튼으로 참여하며, 현재 문단과 유도 문장 이후 입력을 받는다. 단순 지역 변경은 이야기를 끊지 않으며 주요 랜드마크는 예외 삽입할 수 있다. 음악에서 이야기로 자동 전환할 때는 현재 곡을 완료한다. 통화·정지는 재개 가능하며 이야기 복귀 위치는 LLM이 판단한다. 정밀 이동 경로와 지난 대화의 LLM 요약을 저장·활용하되 원음은 저장하지 않는다. 지역·이야기 수집은 포함하고 주행 중 조작 게임·경쟁형 미션은 제외한다. ccNC 테마는 확장안이며 데모에서 제외한다.

**조사와 개발 명세:** [데이터 조사·추가 결정·개발 명세](./exa-results/petasos-2026-11-05/개발명세_및_데이터조사.md)에 근거 자료, 네이버 연계, Gleo 검증과 OpenRouter 대체안, 미결정 정책, 데이터 모델과 실행 검증 항목을 정리했다. Gleo의 앱 주도 자유 생성 기능은 공개 문서에서 확인하지 못했으며 실행 검증 전이다. 구 기획서와 아래 4~5절은 이전 기록이므로 최신 사용자 결정과 충돌하면 적용하지 않는다.

**기획 의도 보강:** 사용자가 일본·대만 당일 투어에서 가이드의 지역 유래·재미있는 이야기·여행 팁 덕분에 이동 시간을 즐겼던 경험을 공유했다. 핵심은 국내에서 무심히 지나치는 지역을 알아가며 이동 자체를 즐기는 경험이다. 운전자와 함께 탄 아이·어르신 등 동승자를 함께 고려하고, 버튼 대화·관심사·음악은 이를 돕는 기능으로 둔다. 출퇴근의 반복 경로 대응은 유지한다. 개인적인 청취 경험을 졸음운전 방지 효과로 주장하지 않는다.

**후속 기본안 확정:** 버튼 1회당 발화 1회 후 청취 복귀, 음악 중 말하기 요청은 일시정지, 선정한 랜드마크는 문단 뒤 삽입을 채택했다. 사용자 발화 중 지나친 랜드마크 자동 안내는 대기열에서 폐기하며 뒤늦게 안내하지 않는다. 이 폐기는 여행 기록 삭제가 아니다. 정밀 경로·대화 요약·청취·수집 기록은 기기에 보관 기한 없이 전부 유지하고 자동 삭제하지 않는다. 데모는 기록 삭제 기능과 클라우드 동기화를 제외하며 원음은 저장하지 않는다. 기억은 차량/세션 공용으로 둔다.

**게이미피케이션 확정:** 데모는 통과 지역·들은 이야기 카드를 자동 수집한다. 칭호·캐릭터·도로 수·거리 배지는 확장안으로 둔다. 기존 ‘게임 제외’를 수집 요소까지 금지하는 것으로 적용하지 않는다. 데모는 경로 1개·장소 5곳·이야기 10개 확보 목표, 내부 음원·화면 버튼을 기본으로 한다. 실제 장소와 판정 수치는 실행 단계에서 정한다.

**네이버 후속 결정:** [NAVER API HUB 약관 개정 공지](https://www.ncloud.com/support/notice/all/2243)에서도 검색 결과의 AI 입력 금지를 확인했다. 음식점·카페는 사용자 요청형 P1 확장 기능으로 확정했으며 첫 데모에서 제외한다. 별도 검색 결과·원문 링크로 제공하고 결과를 LLM·임베딩·대화 요약에 전달하지 않는다. Maps와 검색 API의 계약은 구분한다.

**기술 명세 v0.8 — 2026-11-06:** [현행 명세 안내](./Petasos_기획서.md)를 기준으로 제품·동작·데이터/API·검수·조사 기록을 분리했다. Notion 메인도 같은 구조로 재구성했고 이전 v0.7과 사용자 댓글을 보존했다. Pleos 프로젝트 생성·오디/국문 관광정보 활용신청 완료, 에뮬레이터 설치 진행 중이다. 요약 전 사용자 텍스트와 개인 대화 답변·연결 문장은 암호화 임시 보관하고, 현재 동결 turnVersion의 요약 커밋과 함께 정리한다. 대화 ID·메타데이터·영구 요약은 남긴다. 이야기 완료는 같은 대본 버전 안에서 이동 간 누적을 허용하고 다른 버전끼리 섞지 않는다. UTC 시각과 단조 시계·상태 순번을 분리했다. 참조 SQL·JSON 계약 검사는 앱/SDK 실행 검증이 아니다. 과거 문서의 가입 전·신청 전 상태와 14개 검수 계획은 현행 상태로 취급하지 않는다.

## 1. 공모전의 핵심

- **이름 / 주최:** 현대자동차그룹 차량용 앱 개발 공모전 / 현대자동차그룹. 슬로건은 “Code your App into Pleos App Market”.
- **목표:** 차량 데이터·기능·이동 상황을 활용해 운전자 또는 동승자의 문제를 해결하는 Pleos Connect 앱 개발. 출퇴근, 여행, 충전, 주차, 가족·반려동물 이동, 휴식, 업무 등을 자유롭게 다룰 수 있다.
- **참가:** 대학(원)생·일반인·스타트업, 개인 또는 대표자 포함 최대 5인 팀. 시상은 대학생 / 일반 부문으로 구분된다.
- **혜택:** 본상 총상금 5,100만 원, 선정 10팀은 2027년 2월 행사에서 전시·시연. Pleos App Market 탑재 준비 지원은 별도 검증·심사를 거치며 입점 보장은 아니다.

출처: [공식 개요](https://appdevchallenge.auto-hmg.io/guide/overview), [심사·시상](https://appdevchallenge.auto-hmg.io/guide/judging-awards).

## 2. 일정·제출·평가

| 단계 | 공식 일정 (KST) |
| --- | --- |
| 접수 | 2026-10-21 ~ **2026-11-15** |
| 1차 결과 | 2026-11-23 |
| 2차 최종 PT | 2026-12-03 ~ 12-04, 오프라인 |
| 최종 선정 결과 | 2026-12-18 |
| 전시·최종 순위 발표·시상 | 2027년 2월 |

마감 날짜는 확인했지만 **마감 시각은 확인한 안내에 명시되어 있지 않다**. 일정 변경은 [공식 일정](https://appdevchallenge.auto-hmg.io/guide/schedule)과 공지사항을 재확인한다.

- **1차 필수:** 공식 접수양식 자체가 기획서. 팀원 전원 동의·서명 후 대표자가 팀원을 CC하여 `appdevchallenge@hyundai-partners.com`으로 제출한다.
- **1차 가산점:** 시연 영상(목업·프로토타입·화면 녹화 포함), GitHub 소스 코드 및 APK 등 개발 산출물. 기획서만으로도 지원 가능하다.
- **2차:** PT 전까지 실제 동작하는 앱 개발과 개발 산출물 제출이 필요하다. 1차 이후 고도화할 수 있으나, 최종 선정 이후의 기간은 주로 전시 준비·구동 안정화용이다.
- **GitHub 제출:** 소스 코드와 실행 가능한 release APK를 함께 제공. 기본 APK 위치는 `apk/`, 파일명은 `팀명_앱이름_release.apk`. README에 실행 방법·테스트 환경·설정·APK 위치를 적는다.
- **숏폼:** 선택 사항이며 본상 가산점은 없다. 별도 숏폼 어워드는 1팀 50만 원.

출처: [지원·제출 방법](https://appdevchallenge.auto-hmg.io/guide/submission), [FAQ](https://appdevchallenge.auto-hmg.io/faq).

**1차 필수 평가 4항목:** 차량특화성(모바일 대비 차량에서의 가치), 창의성(명확한 문제와 독창적 해결), 기술 활용성(SDK가 핵심 경험에 기여), 사업성(타깃·확보·반복 사용·확장 가능성). 세부 배점과 2차 평가 기준을 추정하지 않는다. [공식 심사 기준](https://appdevchallenge.auto-hmg.io/guide/judging-awards)

## 3. 개발 환경과 SDK

Pleos Connect는 **Android Automotive OS(AAOS) 기반 차량 인포테인먼트 플랫폼**, Pleos Playground는 개발자 포털이다. 공식 환경은 **Android Studio, Java/Kotlin, minSdk API 28 이상, Gradle 8.0 이상**이다. 확인한 설치 문서는 **Emulator 2.1.0 / API 34 시스템 이미지 / RAM 4 GB / Cold boot**를 안내한다. minSdk 28과 에뮬레이터 이미지 API 34를 구분한다. Mac용 시스템 이미지는 ARM64 경로가 안내되어 있다. [SDK 개요](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/), [설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)

**Virtual Console:** 차량 상태·이벤트를 바꿔 앱 반응과 데모를 검증하는 도구. 에뮬레이터 실행 후 `adb forward tcp:8088 tcp:8088`, 브라우저에서 `http://127.0.0.1:8088/` 접속. 콘솔에서 상태를 시뮬레이션할 수 있다는 사실이 앱에 같은 제어 API가 허용된다는 뜻은 아니다. [콘솔 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)

| SDK | 문서상 기능 | 확인한 문서 기준 버전 |
| --- | --- | --- |
| [Vehicle](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro) | 차량 상태 조회, 일부 편의 기능 제어, 상태 변화 구독 | SDK 2.3.2 / Service 2.3.21 |
| [NaviHelper](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro) | 목적지·경유지 설정, 경로 옵션·재탐색, 내비 정보·주행 이벤트, 내비 화면 요청 | 3.0.5 |
| [FusedLocation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro) | 마지막 위치, 위치 업데이트, 지정 영역 진입·이탈 알림 | 1.1.3 |
| [TextToSpeech](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro) | 텍스트 음성 합성·재생 | 3.3.1 |
| [SpeechToText](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro) | 음성을 텍스트로 변환. 명령 실행·AI 대화 자체를 제공하는 것은 아님 | 3.3.1 |

**Vehicle 도메인:** Brake, CarInfo, Display, Door, DrivingMode, EvBattery, Fuel, HVAC, Light, Odometer, Safety, Seat, SideMirror, Steeringwheel, Tire, Toll, TurnSignal, Window, Wiper. 활용 소재는 배터리·충전·연비·차량 경고·주행 거리 등의 조회, 시트·공조·조명·창문 등 편의 기능 제어다. Brake와 DrivingMode의 소개는 상태 **조회** 중심이므로 크루즈 가감속 알고리즘 변경이나 주차 브레이크 제어가 가능하다고 전제하지 않는다. [Vehicle 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)

**음성 모드:** TTS/STT 모두 HYBRID(서버 우선, 연결 실패 시 온디바이스), SERVER(서버 전용), ON_DEVICE(오프라인) 방식. 서버 사용은 My Project 자격 증명으로 앱 등록이 필요하며, ON_DEVICE만 사용할 때는 해당 서버용 앱 등록이 필요 없다. 이는 모든 SDK 이용 조건이 면제된다는 뜻은 아니다. [TTS](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [STT](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)

### 아이디어 확정 전 확인할 기술 조건

- **공식 문서 간 제공 상태 차이:** SDK별 기능 문서와 별개로 [SDK 사용 신청 안내](https://document.pleos.ai/docs/pleos-only/create-my-project)는 Vehicle State·NaviHelper를 신청 없이 사용 가능, FusedLocation·TTS·STT·Vehicle Control을 준비 중으로 표시한다. 현재 계정의 My Project 상태와 에뮬레이터 실행으로 확인해야 하며, 모두 즉시 사용 가능하거나 모두 불가능하다고 단정하지 않는다.
- **차량별 지원·권한:** Vehicle의 `checkXXXCapabilities`와 필요한 퍼미션을 확인한다. App Market 배포 시 사전 협의·승인 범위가 적용된다. FusedLocation도 `isSupported()` 확인이 필요하다. [API 정책](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/), [위치 지원 조건](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro)
- **추가 구현 의존성:** 생성형 AI 대화·요약, 관광 해설 콘텐츠, 이용자 연결, 주차 잔여면·충전소·가격 데이터 등은 이번에 확인한 SDK만으로 해결된다고 볼 근거가 없다. 별도 데이터·모델·서버 필요 여부를 검토한다.

## 4. 기존 브레인스토밍 — 팀 기록, 미확정

**문제 묶음:** 정체·명절·충전 중 긴 대기와 아이들의 지루함 / 여행 계획·주차 특성·대체 주차장 탐색 / 충전소·급속·완속·주유 가격 탐색 / 센서 성에·제상·온도 조절 / 초보 운전자의 차폭·전방주시·속도·안전거리 / 부자연스러운 크루즈 출발·제동 / 차박·반려동물·아이 탑승 때의 좌석 설정 / 난폭운전 신고용 번호판 기록.

| 방향 | 기록된 아이디어 |
| --- | --- |
| 이동 맥락 콘텐츠 | 주변 풍경 AI 해설·여행 가이드, 상황에 반응하는 콘텐츠, 충전 완료 시간에 맞춘 엔터테인먼트 |
| 가는길 라디오 | 여행 가이드에 직업·이동 경로가 비슷한 사람들의 연결·사연을 결합해 지루함 해소. **원문에서 굵게 강조됐지만 최종 선택 기록은 없음** |
| 어린이·가족 | 아이와 차량 AI의 토론·학습, 운전 중 놓친 아이의 말 요약 |
| 승하차·픽업 | 차량·탑승자 맞춤 승하차 지원 ‘내려드림’, 픽업 예상·위치 공유(친구·학원 등) |
| 개인화 | 차량 계정별 시트·온도 설정, 차박·반려동물·아이 동승 시 설정 저장·복원 |
| 운전 경험 | 운전습관 교정·점수와 보험 할인 연계, 운전 지도 기록·배지 수집 |

남은 팀 논의는 **운전자와 동승자 중 누구를 우선할지**, **크루즈·시트 등 실제 옵션과 제어 범위가 어디까지인지**다. 보험 할인은 아이디어이며 제휴 확보 여부는 기록되어 있지 않다. 출처: [Notion 브레인스토밍](https://app.notion.com/p/a12cf80747f28398a26a016b435ae3de).

## 5. 다음 세션을 위한 판단 메모 — 검토 의견

- 핵심 질문은 **“왜 이 경험이 차량의 데이터·기능·이동 맥락과 결합될 때 더 유용한가?”**다. 원문에도 “Pleos여야 가능한 것이면 좋겠다”는 방향이 있다.
- ‘가는길 라디오’는 기존 기록에서 눈에 띄는 후보다. 구체화한다면 특정 타깃·이동 상황을 하나 정하고 경로/차량 상태에 따라 콘텐츠가 어떻게 달라지는지 설명해야 한다. 이것은 검토 의견이며 팀의 채택 결정이 아니다.
- 두 사람이 개발할 범위에서는 **필요 API 실제 동작 확인 → 한 가지 핵심 경험 구현 → 짧은 시연** 순서로 범위를 잡는 것이 합리적이다. 주차·충전 정보나 커뮤니티처럼 외부 의존성이 큰 후보는 데이터 확보와 운영 부담도 비교한다.
- 다음 결정 항목: 우선 타깃, 반복되는 문제 하나, 모바일 대비 이점, 핵심 SDK/API, 1차 제출용 데모 범위. 새 세션에서는 본 문서를 읽고 이 미결정 항목부터 이어간다.

## 원본과 검토 범위

- [현대자동차 공모전 — 부모 페이지](https://app.notion.com/p/16acf80747f283259d6481938eae3e02): 개요·심사 기준·메모·첨부 이미지 확인.
- [개발 환경 — 하위 페이지](https://app.notion.com/p/ce7cf80747f283d4a3848106d478aabe): SDK별 토글과 Vehicle 도메인 표, 공식 문서 링크 확인.
- [브레인스토밍 — 하위 페이지](https://app.notion.com/p/a12cf80747f28398a26a016b435ae3de): 문제·아이디어·논의점 확인.
- 위 3개 Notion 페이지에서 확인한 하위 페이지는 개발 환경과 브레인스토밍 두 개다. 공식 개요·일정·제출·심사·FAQ 및 연결된 SDK/설치 문서로 보완했다. 이후 변동 가능한 일정·버전·권한은 실행 또는 제출 전에 원문을 재확인한다.
