# Petasos — 조사·결정 기록

버전 0.8 · 갱신일 2026-11-06 · [제품 명세](./Petasos_제품명세.md)

이 문서는 조사 근거와 판단 이력이다. 구현 규칙은 제품·동작·데이터/API·검수 문서에 둔다. 아래 SDK·서비스 조사는 2026-11-05 공식 문서 확인 기준이며 계정 승인·실행 성공을 뜻하지 않는다.

## 1. 결정 이력

| 날짜 | 결정·확인 | 현재 반영 |
| --- | --- | --- |
| 2026-11-05 | 지역 이야기 기본, 버튼으로 질문·경험 참여 | PR-01~03 |
| 2026-11-05 | 단순 지역 변경은 이야기 유지, 주요 랜드마크 예외 | LOC-03/04 |
| 2026-11-05 | 사용자 발화 중 지난 랜드마크 자동 안내 폐기 | LOC-04 |
| 2026-11-05 | 음악 자동 전환은 곡 종료 뒤, 명시적 말하기는 일시정지 | RUN-05 |
| 2026-11-05 | 정밀 경로·요약·청취·수집 로컬 무기한 보관, 원음 미저장 | REC-01~08 |
| 2026-11-05 | 요약 전 텍스트 암호화 임시 보관, 성공 후 정리 | REC-02~05 |
| 2026-11-05 | 음식점·카페 P1, 네이버 검색 결과 AI 입력 제외 | PR 범위, API-04 |
| 2026-11-05 | 비용보다 품질·응답 속도 우선, 필요한 API 발급 가능 | 모델 비교 및 호출 준비 |
| 2026-11-05 | Pleos 프로젝트 생성·오디/국문 관광정보 활용신청 완료 | 사용자 확인. 실제 연결은 미검증 |
| 2026-11-06 | Pleos 설치 진행 중 | 사용자 확인. 설치 완료로 표시하지 않음 |
| 2026-11-06 | 지적한 문서·데이터·정합성 보완 전체 요청 | v0.8 구현 규칙과 참조 계약 추가 |
| 2026-11-06 | 같은 대본 버전 내 청취 누적, 버전 간 혼합 금지 | COL-02. 보완 요청에 따라 추천안 채택 |
| 2026-11-06 | 개인 대화 답변·연결 문장도 암호화 임시 영역으로 분리 | DATA-07~09. 영구 기록은 요약·메타데이터 |

## 2. Pleos 개발 조사

사용자 제공 [개발 환경](https://app.notion.com/p/ce7cf80747f283d4a3848106d478aabe)과 [공모전 정보](https://app.notion.com/p/16acf80747f283259d6481938eae3e02)를 확인했다.

| 영역 | 확인한 문서·범위 | 남은 실행 확인 |
| --- | --- | --- |
| 설치 | [공식 설치](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator): Mac ARM64 이미지, API 34, Pleos 2.1.0, Automotive, RAM 4 GB | 부팅·빈 앱 설치, 선택한 JDK/AGP/Gradle 호환 |
| 에뮬레이터 설정 | [설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator): Cold boot, 호스트 마이크, 해당 버전 Bluetooth 동글 미지원 | 실제 호스트 그래픽·음성 동작 |
| 차량 상태 | [Virtual Console](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator), adb forward tcp:8088 tcp:8088 | 127.0.0.1:8088에서 이벤트 전달 |
| SDK 계정 상태 | [프로젝트 신청 안내](https://document.pleos.ai/docs/pleos-only/create-my-project): 개별 문서와 준비 중 표기가 공존 | 생성한 프로젝트의 실제 SDK 상태 |
| 위치 | [FusedLocation](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/intro), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions) | 지원·권한·timestamp/confidence 의미·주기 |
| 내비 | [NaviHelper](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions) | P0 읽기 범위. polyline·잔여 시간 제공 여부 |
| 음성 출력 | [TTS 시작·종료](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/event-listeners), [등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/app-registration) | 문단 완료·중지·늦은 콜백, ON_DEVICE/서버 한국어 품질 |
| 음성 입력 | [STT 시작·종료](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/start-and-stop), [이벤트](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/app-registration) | request/최종 결과/종료 순서, 소음·동시 발화 |
| 미디어 | [Pleos 미디어 가이드](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide), [Media3](https://developer.android.com/media/media3/session/background-playback) | MediaLibraryService 호환, 시스템 명령→TTS/음악 일치 |
| 오디오·수명주기 | [Focus](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development), [Suspend](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/suspend-mode-audio-focus) | 내비 duck/동시 출력, 등록 가능한 suspend 훅 |
| 화면 | [주행 제한](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide) | 실제 ActiveRestrictions 전달과 화면 적용 |

SDK 문서상 버전은 FusedLocation 1.1.3, NaviHelper 3.0.5, TTS/STT 3.3.1, Vehicle 2.3.2·Service 2.3.21을 조사했다. 실제 프로젝트에서 버전을 고정하기 전 설치된 서비스와 맞춰 확인한다. minSdk 28 이상·Gradle 8 이상이라는 안내만으로 특정 AGP/JDK 조합을 확정하지 않는다.

서버 음성은 프로젝트 자격 증명으로 등록한다. ON_DEVICE만 사용하면 서버 등록이 필요 없다는 안내는 SDK 지원·권한 보장이 아니다. P0 내비 정보 읽기를 위해 경로 변경 권한을 기본 요구하지 않는다. 핸들 버튼 재매핑은 P1 검증이며 열선·각도 API만으로 구현 가능하다고 보지 않는다.

Android Emulator의 [GPX/KML 경로 재생](https://developer.android.com/studio/run/emulator-extended-controls)이 Pleos 위치 SDK로 전달되는지는 미검증이다. 모의 어댑터를 쓰면 시연에서 구분한다. Bluetooth 휴대전화 통화와 포커스 모의 중단도 같은 성공으로 처리하지 않는다.

## 3. 생성 엔진과 서버

[Gleo 개념](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)은 Gleo가 앱 함수를 호출하는 구조다. 앱이 먼저 근거·요약을 넣어 자유 생성하고 구조화 응답을 받는 기능은 아직 확인되지 않았다. 앱 주도 생성, 근거 입력, 텍스트 반환, 요청 식별·취소, 에뮬레이터 권한을 확인하고 요구를 충족하지 못하면 OpenRouter로 개발한다. 담당자에게 문의를 발송한 것은 아니다.

Gleo 앱 명령은 별도 P1이다. [App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)의 함수·파라미터 제한, [callback](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)의 7초 제한·silent 응답, [스키마 전달](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)의 등록 절차를 따른다.

[OpenRouter 시작](https://openrouter.ai/docs/quickstart), [구조화 출력](https://openrouter.ai/docs/guides/features/structured-outputs), [제공자 선택](https://openrouter.ai/docs/guides/routing/provider-selection), [ZDR](https://openrouter.ai/docs/guides/features/zdr)을 확인했다. 서버 키는 앱에 넣지 않는다. 모델·endpoint의 구조화 출력 및 보관 조건을 검사한다. zdr 설정 하나로 모든 경로의 무보관을 보장하지 않는다.

비교 후보는 [Claude Sonnet 4.6](https://openrouter.ai/anthropic/claude-sonnet-4.6)과 [Gemini 2.5 Flash](https://openrouter.ai/google/gemini-2.5-flash)다. 동일 근거로 이야기 10건·질문 10건·경험 5건·복귀 5건을 비교한다. 재미·이해도는 청취 평가, 근거·ID·JSON은 계약 검사, 지연은 음성 출력까지 측정한다. 아직 비교하지 않았으며 주 모델도 미확정이다.

## 4. 자료·네이버 활용

| 원천 | 용도 | 상태·주의 |
| --- | --- | --- |
| [오디](https://www.data.go.kr/data/15101971/openapi.do) | 해설 대본·음성·사진·위치 | 활용신청 완료. 실제 응답 필드·키·권리 확인 필요 |
| [국문 관광정보](https://www.data.go.kr/data/15101578/openapi.do) | 장소·좌표·설명 | 활용신청 완료. 사진은 자산별 공공누리 조건 확인 |
| [국가유산 API](https://www.data.go.kr/data/15034324/openapi.do), [공식 명세](https://www.khs.go.kr/html/HtmlPage.do?mn=NS_04_04_03&pg=%2Fpublicinfo%2Fpbinfo3_0202.jsp) | 문화유산 보강 | 해당 제공자의 결측 좌표 규칙 적용 |
| [민족문화대백과 API](https://encykorea.aks.ac.kr/Guide/OpenApiUse), [이용 안내](https://www.aks.ac.kr/com/cmm/EgovContentView.do?menuNo=4010164000) | 지명·생활사 보강 | 별도 신청·항목/자산 이용 조건 확인 |
| [스토리텔링 CSV](https://www.data.go.kr/data/3041768/fileData.do) | 초기 대본 구조 참고 | 2016년 기준 1,624행. 현재 한국어 고유 이야기 수로 해석하지 않음 |
| 향토문화대전·지자체 | 세부 지역 사실 교차 확인 | 공개 열람과 생성 DB 복제 권리를 구분 |

오디·TourAPI는 개발 자동승인·운영 심의 안내를 확인했다. 실제 계정 한도와 응답 성공은 별도다. 최신 확인일과 원문 갱신일을 구분하고 변동 정보는 재검토한다.

네이버는 [지도 SDK](https://navermaps.github.io/android-map-sdk/guide-ko/0.html)와 [Reverse Geocoding](https://api.ncloud-docs.com/docs/application-maps-reversegeocoding)을 지도·지역명 확인에 검토한다. [Directions 5](https://api.ncloud-docs.com/docs/application-maps-directions5)는 차량 내비 경로와 다른 보조 경로일 수 있다.

음식점·카페 [지역 검색](https://api.ncloud-docs.com/docs/naver-api-hub-search-local)은 P1이다. 확인한 응답에는 리뷰 본문·평점·메뉴 가격·영업시간·대기시간이 없으며 GPS 반경 검색으로 취급하지 않는다. [개발자 약관](https://developers.naver.com/products/terms/)과 [NAVER API HUB 공지](https://www.ncloud.com/support/notice/all/2243)의 검색 데이터 AI 입력 제한을 적용한다. 검색 결과는 별도 화면·원문 링크로 제공하며 LLM·임베딩·요약·개인화에 넣지 않는다. Maps 계약과 검색 계약은 별개다.

## 5. 대표 경로의 10개 소재

서울역 주변→경부고속도로 축→대전 엑스포과학공원 주변을 개발 기준안으로 한다. 아래는 근거를 찾은 소재이며 대본·실제 경로·좌표·랜드마크 범위가 확정된 것은 아니다.

| 지역·장소 묶음 | 소재 2개 | 근거 |
| --- | --- | --- |
| 서울역 | S01 역이 문화공간으로 / S02 서울284의 숫자 | [문화역서울284 연혁](https://www.seoul284.org/cms/content/view/253) |
| 용인 | S03 용구+처인 / S04 양지·백암 명칭 변화 | [역사](https://www.yongin.go.kr/home/yiIf/yiIfProd/yiIfProd01.jsp), [연혁](https://www.yongin.go.kr/home/yiIf/yiIfProd/yiIfProd02.jsp) |
| 안성 | S05 안성맞춤 유기 / S06 교통로·장터·수공업 | [안성맞춤박물관](https://www.anseong.go.kr/tourPortal/museum/contents.do?mId=0302010000) |
| 천안 | S07 호두과자와 여행 / S08 천안삼거리의 길 | [문화대전](https://cheonan.grandculture.net/cheonan/toc/GC04500016), [천안시](https://www.cheonan.go.kr/prog/tuTrsm/tour/sub01_02_03/view.do?mngNo=13) |
| 대전 | S09 철도와 도시 / S10 1993년 엑스포 | [시 연혁](https://daejeon.go.kr/drh/DrhContentsHtmlView.do?menuSeq=1719), [엑스포 회고](https://www.daejeon.go.kr/its/ItsdjNormalboardView.do?boardGubun=itsdj01&boardSeq=3841&menuSeq=5930&pageIndex=1) |

용인 통합 연도는 공식 연혁의 1414년과 다른 표현의 차이를 검토한다. 호두 전래는 전승과 사실을 구분한다. 엑스포 참가·방문 수처럼 원문끼리 다른 숫자는 원자료 확인 전 제외한다. 향토문화대전 본문 재사용 조건은 추가 확인이 필요하다.

5개 소재 묶음이 5개 지역 카드와 같지는 않다. 실제 통과한 코드로만 적립한다. 도로에서 떨어진 장소를 보인다고 설명하지 않는다. 빠른 위치 재생·영상 편집은 실제 연속 주행 검증과 구분한다.

음악 후보는 Kevin MacLeod의 Carefree다. [공식 라이선스](https://web.incompetech.com/music/royalty-free/licenses/)와 [곡 상세](https://www.incompetech.com/music/royalty-free/index.html?isrc=USUAN1400037)를 확인하고 실제 파일·곡별 조건·크레딧을 대조한 뒤 채택한다. 동적 곡 본문을 완전히 확인하지 못했으므로 권리 검증 완료로 표시하지 않는다.

## 6. 경쟁·심사·사용자 평가 기록

공개 소개 기준 [Roadio](https://github.com/WhoisHOO/pleos-app-challenge), [Road Docent](https://github.com/728haven-oss/road-docent), [Passenger Seat](https://www.passengerseat.app/features), [HearHere History](https://www.hearherehistory.com/)와 위치 해설·질문·반복 방지 기능이 겹친다. 실제 제품 성능을 평가한 결과는 아니다.

차별화 검증은 함께 듣고 싶은 대본, 개인 경험 참여 이후 전개 변화, 이동·내비·미디어에 맞춘 재개, 음악으로 물러나는 경험에 둔다. 가이드라는 동기만으로 독창성을 주장하지 않는다.

[공식 심사](https://appdevchallenge.auto-hmg.io/guide/judging-awards)의 차량특화성·창의성·기술 활용성·사업성을 통합 시연과 재사용 이유로 설명한다. 공개되지 않은 배점을 만들지 않는다. 반복 이용·지불 의사 검증 후 콘텐츠 팩·구독을 검토하며 제휴·MAU를 확보한 실적으로 표현하지 않는다. 경험 평가 절차는 검수 명세 8절에 둔다.

## 7. 남은 확인과 다음 순서

| 순서 | 확인 항목 | 완료 증거 |
| --- | --- | --- |
| 1 | Pleos 이미지·빈 앱·계정별 SDK 상태 | 버전·APK 실행·권한 상태 |
| 2 | 위치·TTS·STT·미디어 최소 연결 | 실제 콜백 순서·품질·중단 로그 |
| 3 | API 키 설정과 데이터 응답 | 비밀값 없는 연결 결과·필드 매핑 |
| 4 | 경로·지역 경계·랜드마크·원천 권리·대본 | 검증된 seed 데이터와 출처 revision |
| 5 | 생성 모델·서버 인증·프로바이더 보관 조건 | 동일 평가 묶음·인증·취소·오류 시험 |
| 6 | 복구·수집·마이그레이션·경험 | 검수 항목별 실행 결과 |

계정·API 신청은 다시 요청하지 않는다. 실제 키는 비추적 로컬 설정으로 입력하고 값을 출력하지 않는 연결 검사를 한다. OpenRouter는 생성 연결 시, Maps는 지도 연결 시, 검색 API는 P1 때 준비한다.

## 8. 재구성 이력

| v0.7 내용 | v0.8 위치 |
| --- | --- |
| 1~5·9·16 제품 경험과 범위 | 제품 명세 PR-01~12 |
| 8·20.3·21.2~21.5 재생·위치 | 동작 명세 RUN/LOC/COL |
| 7·20.2·21.6·22.4 데이터·복구 | 데이터/API DATA/TIME/TX/INV, 동작 REC |
| 18.2·20.4·21.7 생성 | 데이터/API API-01~09 |
| 13·21.8·22.4 검수 | 검수 A/R/D/N와 요구사항 추적 |
| 6·11·17·18·22 조사 | 이 문서와 v0.7 보관본 |

[v0.7 이전 명세](https://app.notion.com/p/40fcf80747f2813d82f9d8922620b584)는 보관본이다. [로컬 보관본](./archive/Petasos_v0.7_notion_2026-11-06.md)도 유지한다. 기존 메인 페이지의 사용자 댓글은 제품 참여 설명에 유지한다. 현행 요구사항은 이 기록의 과거 문장보다 제품·동작·데이터/API 계약을 우선한다.

기술 근거: [WorkManager 고유 작업](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work), [Keystore](https://developer.android.com/privacy-and-security/keystore), [백업](https://developer.android.com/identity/data/autobackup), [Media3 이벤트](https://developer.android.com/media/media3/exoplayer/listening-to-player-events), [FGS 유형](https://developer.android.com/develop/background-work/services/fgs/service-types).
