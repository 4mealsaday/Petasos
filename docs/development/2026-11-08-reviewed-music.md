# dev16 검토된 실제 음악과 자동 편성 연결

작성: 2026-11-08. 구현 버전은 **0.8.0-dev.16**이다. 최종 privacy·debug 앱/테스트 APK 빌드와 Lint, JVM **502개/40 suite**, 호스트 **117개**, 동일 최종 APK의 기기 회귀 **13종**, 알려진 앱 SDK 로그 sink 정적 검사, privacy 설치·실행 smoke와 실제 출처 창 표시/비가시화 닫힘을 통과했다. 최종 결과는 [milestone-15 집계](../../artifacts/milestone-15/verification.json)를 따른다. 이전 완료 결과는 [dev15 명시 명령 기록](./2026-11-08-explicit-playback-commands.md)에 보존한다.

이번 단계는 검토한 실제 음악 한 곡을 제품에 포함하고, 기존 Coordinator·Room·Media3 경로에 연결하는 작업이다. 설치된 공개 이야기의 정상 완료 뒤 음악을 재생하고, 실제 음악의 정상 완료 뒤 다음 자동 이야기를 허용하는 기존 편성 규칙을 사용한다. 제품 마이크는 별도 Pleos SpeechService의 원문 로그 문제 때문에 계속 비활성이다. Virtual Console은 보류하며 UI 외형이나 데모 시나리오를 다시 설계하지 않는다. **이번 단계만으로 데모나 전체 제품 개발이 완료된 것은 아니다.**

## 포함한 원본과 사용권

| 항목 | 검토한 값 |
| --- | --- |
| 곡 / 앨범 | `Prelude No. 1` / `Preludes` |
| 저작자·연주·제작 | Chris Zabriskie |
| 저작권·발행 | © 2009 Chris Zabriskie; You've Been a Wonderful Laugh Track (ASCAP) |
| 제품 asset ID | `music-chris-zabriskie-prelude-no-1` |
| 원본 형식 | M4A 컨테이너, AAC, stereo, 44,100 Hz |
| 원본 크기 | 1,429,535 bytes |
| 원본 SHA-256 | `f8721ff76cfa8e575747c3015bc00afffc500a4d3e73bcd5043339fd1fad7009` |
| 파일 길이 측정 | macOS `afinfo`: 68.545306초. 재생 metadata는 68,545 ms |
| 사용권 | Creative Commons Attribution 4.0 International, `CC-BY-4.0` |
| 변경 여부 | 공식 원본 파일을 그대로 포함. 변환·편집·음량 가공 없음 |

[권리자의 앨범 페이지](https://chriszabriskie.com/preludes/)는 곡·앨범·저작자·발행·저작권을 명시한다. [권리자의 사용 안내](https://chriszabriskie.com/use/)는 음악에 CC BY 4.0을 적용하고, 귀속표시를 조건으로 상업적 사용과 별도 서면 허가 없는 사용을 허용한다. [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)은 매체를 가리지 않는 복제·재배포를 허용하므로 이 조건을 지켜 앱에 포함한다. 음원에 앱 코드의 이용 제한을 덧붙이거나 DRM을 적용하지 않는다. 아티스트가 Petasos를 보증한다는 뜻도 아니다.

다운로드 주소는 권리자의 [공식 플레이어 코드](https://chriszabriskie.com/assets/player.js)가 사용하는 [공식 원본](https://audio.chriszabriskie.com/preludes/preludes-01.m4a)이다. HTTPS 검증, 응답 형식, 1,429,535-byte 정확한 크기 제한을 확인한 후 한 곡만 받았다. Spotify·YouTube에서 오디오를 추출하지 않았다. 앨범 페이지의 `1:09`는 표시용 반올림 값이며 제품 metadata는 실제 파일 측정을 사용한다.

검토한 정보는 [manifest](../../app/src/main/assets/music/manifest.json)와 [귀속표시](../../app/src/main/assets/music/CREDITS.txt)에 함께 보관한다. manifest의 `rightsValidUntilMs=Long.MAX_VALUE`는 이 곡의 기간 제한 없는 CC BY 허가를 표현한 것이다. 임의의 미래 만료일이나 매번 온라인에서 권리 상태를 확인했다는 의미가 아니다. 앱의 재생 허용은 별도 review snapshot으로 철회할 수 있다. 일반 사용자가 모델 응답이나 외부 URI로 음악을 등록하는 기능은 없다.

## 파일과 재생 경계

| 책임 | 파일 |
| --- | --- |
| 음악 참조 준비, 현재 파일·해시·권한·기간 검증 | [MusicCatalog](../../app/src/main/java/com/petasos/app/music/MusicCatalog.kt) |
| 제한된 로컬 manifest와 공개 출처 DTO | [ReviewedMusicBundle](../../app/src/main/java/com/petasos/app/music/ReviewedMusicBundle.kt) |
| 크기 제한 스트림·SHA 검사·원자 설치 | [ReviewedMusicInstaller](../../app/src/main/java/com/petasos/app/music/ReviewedMusicInstaller.kt) |
| Android assets와 private 파일 연결 | [BundledMusicCatalog](../../app/src/main/java/com/petasos/app/platform/android/music/BundledMusicCatalog.kt) |
| deterministic 순서의 다음 허가 음악 제안 | [AutomaticMusicPlaybackBridge](../../app/src/main/java/com/petasos/app/planning/AutomaticMusicPlaybackBridge.kt) |
| 실제 출력 전 permit, 위치·종료·정지 callback | [PlaybackRuntime](../../app/src/main/java/com/petasos/app/runtime/PlaybackRuntime.kt), [Media3RuntimeMusic](../../app/src/main/java/com/petasos/app/platform/media/Media3RuntimeMusic.kt) |
| 제품 session·자동 편성·공개 metadata 배선 | [PetasosSessionHost](../../app/src/main/java/com/petasos/app/platform/android/runtime/PetasosSessionHost.kt) |
| 정차 상태의 읽기 전용 출처 표시 | [MusicCreditsControls](../../app/src/main/java/com/petasos/app/presentation/MusicCreditsControls.kt) |

manifest는 앱에 함께 배포하는 검토 자료다. 64 KiB/16곡 상한, 정확한 필드 집합, 중복 ID·해시·파일명 거부, 허용된 license URL, HTTPS 출처, 상대 파일명, 크기·재생 길이·SHA 형식을 검사한다. 임의의 `royalty-free` 표기나 배포 허가가 없는 자료는 받지 않는다. 자동화된 문법 검사가 저작권자의 실제 허가 검토를 대신하지 않는다.

`BundledMusicCatalog` 생성자는 파일 I/O를 하지 않는다. 최초 worker 조회에서 manifest를 읽고 `noBackupFilesDir/reviewed-music` 아래 content hash 기반 파일을 설치한다. 임시 파일에 정확한 크기와 SHA를 확인하고 같은 디렉터리의 atomic rename으로 게시한다. 불완전하거나 초과한 stream은 게시하지 않고 임시 파일을 정리한다. 이미 일치하는 설치 파일은 다시 복사하지 않는다. 여러 곡 중 뒤의 설치가 실패하면 앞의 검증된 파일이 디스크에 남을 수 있지만, 전체 성공 전 catalog snapshot을 게시하지 않으므로 재생 후보가 되지 않는다. 파일 교체는 manifest·원본 자산 변경으로 가능하며 화면·Coordinator 코드를 바꿀 필요가 없다.

자동 후보 공급은 `automaticCandidates()`, 출처는 `credits()/creditsText()`, 공개 제목·아티스트·길이는 `trackInfo()`로 분리한다. 이 API는 worker에서 호출하고 화면/MediaSession용 값은 캐시한다. browse·출처 확인·metadata 준비만으로 session이나 출력이 시작되지는 않는다.

재생 전 worker에서 실제 파일·크기·해시·권한을 다시 확인한다. 최종 main-thread 시작 경계의 `RuntimeMusicPermit`은 현재 attempt/session/fence와 `RuntimeMusicSource.isCurrent()`를 검사한다. source의 가벼운 검사는 immutable review 객체의 동일성·재생 허용·만료만 읽으며 파일·DB I/O를 하지 않는다. 동일한 값으로 만든 새로운 review 객체도 이전 준비 권한을 무효화한다. Stop·blocker·세션 종료 또는 권한 철회 이후 대기 중인 준비 결과가 새 출력을 시작할 수 없다.

## 편성과 사용자 제어

Coordinator가 실제 출력 시점과 전이를 소유한다. 자동 음악 bridge는 현재 재생/상호작용 의도와 blocker를 보고 허가 자산을 제안하고, 사용된 후보 다음 순서를 준비한다. 매 Position callback마다 파일 해시를 다시 읽지 않는다. 초기 명시 이야기의 resume 후보를 음악 offer로 덮어쓰지 않으며, 준비 대상 없는 명시 Play는 기존 빈 후보 복귀 규칙을 통해 음악을 선택할 수 있다.

현재 한 곡뿐이므로 새 선택에서 같은 곡을 다시 고를 수 있다. **Media3 repeat mode는 OFF**이고, 실제 `STATE_ENDED`·정리 이후 정상 완료를 커밋한 다음 Coordinator가 **새 attempt**로 다음 출력을 시작한다. player 자체 무한 반복을 청취 완료로 간주하지 않는다. Next·Stop·focus 중단은 정상 곡 완료와 구분하고, 음악 skip을 이야기 사이의 정상 음악 완료로 계산하지 않는다.

일반 Play는 중단된 음악 checkpoint를 같은 위치에서 새 attempt로 재개한다. 사용자가 새 이야기 선택을 명시한 경우에만 해당 이야기 선택으로 대체한다. 이전 이야기가 UI dropdown에 남아 있다는 이유로 음악 재개를 이야기 처음부터 재생하는 명령으로 바꾸지 않는다. 이전/다음/정지의 실제 의미와 저장은 기존 Coordinator를 거치며 ExoPlayer를 외부 controller에 직접 노출하지 않는다. 자산 준비 실패는 의도를 멈추고 대기하며, 같은 잘못된 파일을 자동으로 계속 재시도하지 않는다.

음악 완료가 다음 자동 이야기의 gate를 푸는 현재 세션 동작과 음악 checkpoint 복구는 구분한다. **프로세스 재생성 뒤에도 이야기 사이 음악 cadence 전체가 Room에 영구 보존된다는 주장은 하지 않는다.** 실제 재생 attempt·중단 위치·정상 완료 기록의 저장 범위만 검증한다.

## Spotify와 별도 음악 앱의 범위

[현대자동차 공식 소개](https://www.hyundai.com/worldwide/ko/company/innovation/future-mobility)는 Pleos Connect에서 Spotify를 스마트폰 없이 이용하는 경험을 설명한다. 이는 모든 차종·국가·에뮬레이터에서 Spotify가 설치되어 있거나 Petasos가 해당 앱을 제어할 수 있다는 계약은 아니다. 현재 에뮬레이터에서 Spotify package가 발견되지 않은 읽기 결과는 [환경 관측](../../artifacts/milestone-15/spotify-emulator-check.json)에 남긴다.

[Spotify Android SDK 안내](https://developer.spotify.com/documentation/android/tutorials/getting-started)는 설치된 Spotify 앱과 사용자 인증을 전제로 한다. [PlayerApi](https://spotify.github.io/android-sdk/app-remote-lib/docs/com/spotify/android/appremote/api/PlayerApi.html)와 [PlayerState](https://spotify.github.io/android-sdk/app-remote-lib/docs/com/spotify/protocol/types/PlayerState.html)의 재생·정지 상태만으로 Petasos가 요구하는 자연 종료 사유와 물리적 출력 정지 ACK가 제공된다고 가정하지 않는다. 해당 SDK의 Pleos AAOS 호환성도 이번 단계에서 확인하지 않았다. Pleos 시스템/Gleo가 각 media app과 연동하는 것과 Petasos에서 Spotify로 직접 명령을 보내는 것은 별도 경계다.

[Spotify 개발자 정책](https://developer.spotify.com/policy)의 III.3 음성 비서/음성 제어 제한과 III.7 다른 오디오와의 연결·믹스·중첩 제한도 별도로 검토해야 한다. Spotify 음원을 내려받아 번들하거나, 이야기와 연결 재생할 수 있다고 추정하지 않는다. 이번 P0는 직접 검토한 내부 Media3 음악이며, 외부 음악 앱으로 복귀하는 P1은 후속 범위로 유지한다.

## 실제 음악과 fixture, 검증 상태

최종 실행은 모두 PASS다. JVM 502개/40 suite, 호스트 117개와 기기 probe 13종을 합산하지 않고 각각 기록했다. privacy·기본 debug 빌드와 Lint가 통과했고 debug JVM·서버 177개·참조 계약 13개는 이번에 재실행하지 않았다. 최종 APK 쌍은 `.local/builds/dev16/privacy-final/`에 보존한다. 앱 SHA는 `41b0639b15ab66d65ab469524e6415b67e5520294f9e96ccdad49ac20be9a33b`, 테스트 SHA는 `3c0de1b521752e50f9bf8a92ca91645acb4c18aca02f47fd57dbe8c7651b70ae`다. [APK 기록](../../artifacts/milestone-15/final-apks.json), [최종 247개 소스 확인](../../artifacts/milestone-15/final-source-check.json)을 따른다.

| 검증 대상 | 실제와 합성의 경계 | 상태 |
| --- | --- | --- |
| [MusicCatalogTest](../../app/src/test/java/com/petasos/app/music/MusicCatalogTest.kt), [ReviewedMusicBundleTest](../../app/src/test/java/com/petasos/app/music/ReviewedMusicBundleTest.kt) | 실제 번들 원본의 manifest/크기/SHA/credit 일치, 임시 파일과 합성 stream의 권리·크기·손상·경로·원자 설치 검사. 오디오 재생 없음 | PASS |
| 음악 bridge·Coordinator·runtime 순수 회귀 | 합성 상태·준비 callback의 순서, Stop/late result/권한 철회/정상 완료 gate. 실제 스피커·GPS·마이크 없음 | PASS |
| [music-runtime](../../app/src/androidTest/java/com/petasos/app/storage/MusicRuntimeProbe.kt) | 시험용으로 만든 합성 tone WAV의 실제 Media3·Room. 제품 음악이나 사람 청취 검증과 구분 | PASS |
| [app-music](../../app/src/androidTest/java/com/petasos/app/diagnostics/ProductMusicProbe.kt) | 제품 Host·MediaController·Room·실제 공개 이야기 TTS와 라이선스 음악 Media3. 출처/browse 무재생, 선택 이야기 모든 문단 완료 뒤 음악, 4초 이상 실제 위치에서 Stop, 새 Position callback의 위치 재개, 자연 종료·다음 출력, 최종 정지 검사 | PASS |
| privacy/debug 빌드·Lint·APK 감사·smoke·최종 source/device | 최종 앱과 테스트 쌍의 일치, 설치·전면 상태와 원문 로그 blocker 유지 확인. 별도 SpeechService 무로그 증거는 아님 | PASS |

초기 13종 실행 뒤 검증 코드 두 조건을 강화했다. 기존 checkpoint 값만으로 재개를 판정하지 않고 새 attempt의 첫 변경 Position을 관측하며, 선택한 이야기의 모든 문단 완료와 소진된 checkpoint를 함께 확인한다. 제품 APK는 동일했고 최종 테스트 APK로 13종을 전부 다시 통과했다. [변경 경계](../../artifacts/milestone-15/test-assertion-strengthening.json)와 `before-probe-strengthening/`의 초기 결과를 보존하며 합산하지 않는다.

실제 정차 화면에서 **음원 출처**를 열어 곡명·작곡자·라이선스가 표시되는 것을 확인했고 다른 Activity의 전면 진입을 관측한 뒤 Petasos로 복귀했을 때 창이 닫힌 상태임을 확인했다. [출처 UI 결과](../../artifacts/milestone-15/music-credits-ui.json). 첫 HOME→즉시 재실행 검사는 실제 비가시화를 관측하지 않은 채 닫힘 조건에 실패했고, [초기 결과](../../artifacts/milestone-15/music-credits-ui-before-pause-observation.json)를 보존했다. 제품 코드를 바꾸지 않고 전면 변경을 실제 관측하도록 검증 절차를 보완했다. 화면 원문이나 스크린샷은 저장하지 않았다.

04:34:26Z에 설치된 dev.16 privacy 앱의 SHA 일치·전면 상태와 앱/서비스의 활성 녹음 구성 각각 0, 기존 localhost:8443 reverse와 HTTPS health 200을 확인했다. [최종 기기 상태](../../artifacts/milestone-15/final-device-state.json). 녹음 수는 시점 관측이며 입력 종료 ACK나 연속 감시 증거가 아니고, 별도 SpeechService의 원문 로그 문제를 해결했다는 뜻도 아니다.

실제 파일의 다운로드 크기·SHA와 macOS 길이 측정은 자산 준비 증거이며 Android에서 실제로 끝까지 재생되었다는 증거가 아니다. `music-runtime`의 합성음을 제품 음악으로 표시하지 않는다. 제품 probe에서 실제 Media3를 사용하더라도 소리를 사람이 들었는지, 음향적으로 언제 완전히 무음이 됐는지, 장시간 재생 품질까지 검증했다고 표현하지 않는다. 마이크·실제 GPS·개인 답변·새 모델 요청은 이번 음악 검증 대상이 아니다.

## 남은 기능

별도 SpeechService 원문 로그 문제와 실제 입력 5초/Final 500 ms 계약은 [기존 음성 privacy 기록](./2026-11-08-speech-privacy-verification.md)을 유지한다. 제품 Talk gate를 열거나 실제 사람 발화를 요청하지 않는다. 외부 음악 앱 복귀, 실제 위치 품질·경계·랜드마크 정책, 외부 자동 story 생성, 모델 action 실행 권한, 개인 연결 문장 출력, 음악 선호·다곡 편성 정책은 각각 후속 검토 대상이다. UI 디자인과 자유로운 데모 시나리오 제작은 별도 단계다.
