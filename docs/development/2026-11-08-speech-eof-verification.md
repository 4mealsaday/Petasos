# dev.21: 공식 ON_DEVICE PCM의 실제 EOF 검증

2026-11-08. STT·TTS는 이미 Pleos 공식 SDK의 `ON_DEVICE` 모드다. 이 경로에는 Speech 서버 등록이나 Client ID/Secret이 필요 없다. 업체 답변을 모든 음성 검증의 전제로 삼지 않고, 실제 마이크를 사용하지 않는 합성 입력 시험을 계속했다. [공식 API 진행 방침](2026-11-08-official-only-platform-path.md).

## 변경

이전 유한 파일 시험은 SDK 종료 콜백이 파일 끝보다 먼저 도착했다. 따라서 실제 EOF 처리는 검증하지 못했다. 새 `stt-owned-eof`는 기존 합성 PCM 72,313 samples 중 끝의 **정확히 0인 16,150 samples**만 제거한다. 진폭 기준이나 음성 감지로 발화 내용을 자르지 않는다. 남은 56,163 samples를 모두 공급하고 실제 EOF를 확인한다.

제품 어댑터에 기본 no-op인 내부 관찰자를 추가했다. enum과 단조 시각만 전달하며 텍스트·음성은 전달하지 않는다. EOF, 캡처 정리 완료, `completeAudioSend()` 진입/반환, SDK Ended/Final의 원래 콜백 시각을 구분한다. 관찰자 예외가 제품 동작을 바꾸지 않게 했으며 public 생성자·인식 제어·5초/500ms 제한은 유지했다.

`completeAudioSend()` 반환은 동기 메서드 반환일 뿐 SDK flush나 입력 종료 ACK가 아니다. 입력 종료 증거는 기존 recorder stop/release 결과다. 같은 밀리초는 엄격한 선후관계 증거로 사용하지 않으며, 누락·중복 콜백이나 검증 완료 표식 누락도 EOF PASS로 처리하지 않는다. 기능 성공·EOF 순서·제품 시간 기준·서비스 로그 관측을 별도로 집계한다.

## 실제 기기 관측

동일 최종 privacy APK의 실제 Pleos SDK에 합성 PCM만 입력했다. 재부팅 후 첫 시험 전에 이 부팅의 다른 STT 시험은 없었다. 서비스 내부 인식 엔진의 cold 상태까지 관측했다고 주장하지 않는다.

| 시험 | Ready→Started | Ended→Final | 실제 EOF 검증 |
|---|---:|---:|---|
| 재부팅 후 첫 EOF | 1,257 ms | 4 ms | PASS |
| 반복 EOF | 931 ms | 5 ms | PASS |
| EOF 없는 연속 입력 | 922 ms | 4 ms | 해당 없음 |
| 1.5초 선행 무음 | 2,432 ms | 9 ms | 해당 없음 |

첫 EOF는 시험 시작 후 3,612ms에 관측됐고 캡처 정리·완료 호출 반환도 같은 ms였다. SDK Ended는 3,643ms, SDK Final은 3,646ms였다. 두 번째 EOF/완료 반환은 3,651ms, SDK Final은 3,680ms였다. 두 번 모두 실제 EOF가 SDK 콜백보다 앞섰다. 작은 합성 표본의 결과이며 사람 발화나 일반적인 지연 보장을 뜻하지 않는다.

**기능 4회 PASS / 별도 SpeechService의 시험 문장 로그 4회 MATCH_FOUND**다. 앱과 unknown process의 원문/payload/stacktrace 일치는 0건이었다. 서비스 로그 문제는 해결되지 않았으므로 제품 `supportsLiveInput()`은 false를 유지한다. 원본 logcat·인식 전문·원음은 새로 저장하지 않았고 고정 코드·시간·횟수만 남겼다.

## 빌드와 회귀

- JVM **725개 / 53 suite**, 호스트 **156개**, privacy 앱/검증 APK 빌드·Lint PASS.
- 동일 최종 APK의 `runtime-scripted`, `runtime-tts-stop`, `media-session`, `app-playback` **4종 PASS**. 공개 이야기 실제 TTS 및 완료 저장을 확인했다.
- 알려진 앱 SDK 로그 sink 정적 검사와 설치/실행 smoke PASS. 소스 541개 일치, dev.21 전면 실행·설치 해시 일치·활성 녹음 0·기존 HTTPS health 200을 확인했다.
- 서버 208개와 지역 연결·마이그레이션은 무변경 dev.20 결과를 따른다. 이번 단계에서 debug 빌드, 실제 위치·사람 발화·외부 모델 호출은 하지 않았다.

최종 APK는 `.local/builds/dev21/privacy-final/`, 해시·기능/시간/로그 구분은 [집계](../../artifacts/milestone-21/verification.json)를 따른다. 처음 155개 호스트 결과와 관측 증거 강화 전 테스트 APK 빌드는 별도 로그로 보존하고 최종 결과와 합산하지 않는다.

실제 대화는 서비스 로그 조건과 사용자 마이크 시험이 남는다. FusedLocation·Virtual Console·현재 위치 동의·UI 디자인 방침은 변경하지 않았다. 다음 독립 개발은 명세 PR-06/LOC-04의 주요 랜드마크 후보 연결이며, 실제 활성화 전 검토된 좌표·정책·유효 위치가 필요하다.
