# dev.23: 공식 Android 위치 의미와 전면 수신 연결

2026-11-08. v0.8 LOC-01/02, TIME-03과 기존 위치 기록 동의를 유지하며 공개 Android LocationManager 어댑터를 추가한다. Pleos FusedLocation의 미확인 confidence/timestamp 의미를 Android 값으로 대체하지 않는다. 실제 수집 동의·권한·주행 및 지역 정책 검증은 별개다.

## 구현 경계

- `platform/android/location/AndroidLocationSource`: 실제 제공자 이름, epoch 측정 시각, 측정/수신 elapsedRealtimeNanos, hasAccuracy 기반 수평 정확도, mock 여부를 같은 Android Location에서 복사한다. 공개 메타데이터로 활성 fused → gps → network 중 하나를 선택하며 접근 거부 뒤 다른 제공자로 우회하지 않는다.
- `location/AndroidLocationMeaningAdapter`: 정확도 단위와 측정 시간의 의미만 해석한다. 신선도는 부팅 후 단조 시간의 측정 나이와 제공된 시간 불확실성을 사용한다. raw epoch는 그대로 보존하며 미래 시각·mock·정확도 결측은 유효한 위치로 만들지 않는다. 100m 품질 상한은 개발 시작 설정이며 지역·랜드마크 정책의 실측 검증을 뜻하지 않는다.
- `location/PleosAuthorizedLocationSource`: 현재 Pleos UPDATE 수신 신호를 접근 조건으로 유지하면서 Android 측정만 기록 계층에 전달한다. Pleos 좌표·정확도·측정 시각을 Android 표본과 혼합하지 않는다. Pleos 오류·실시간 null·권한 거부·접근 신호 만료 시 Android 구독과 이전 권한을 철회한다. 캐시 조회 실패·null만으로 첫 live fix를 기다리는 구독을 닫지 않는다.
- `LocationAccess`와 기존 설정 화면: 저장된 기록 동의, Pleos 권한, Android fine 또는 coarse 권한을 각각 확인한다. 사용자가 버튼을 누를 때 Pleos 권한과 Android fine/coarse 요청을 별도 단계로 진행한다. coarse-only 허용 여부를 배열 첫 원소 대신 현재 OS 권한으로 확인한다. 동의 ON이나 권한 허용을 자동 수행하지 않는다.
- `PetasosSessionHost`: Activity onStart/onStop의 가시성을 주차 화면·마이크 가시성과 분리한다. 전면에서만 수신하며 화면을 떠날 때 즉시 source와 자동 후보 권한을 철회하고 수신 gap을 기록한다. 이동 종료나 실제 Suspend로 표시하지 않는다. 기록 OFF ingress와 이전 DB 읽기/ON 저장의 경합은 `LocationConsentAuthority`에서 막는다.
- 기존 JSON 열에 Android 원본 메타데이터와 측정 나이를 보존한다. Room schema 2는 유지한다. 다시 읽을 때 원본 단조 시간과 저장된 나이가 다르면 valid 권한을 잃으며 벽시계 신선도로 우회하지 않는다.

## 공식 근거

[Android Location](https://developer.android.com/reference/android/location/Location)은 epoch time, elapsedRealtimeNanos, hasAccuracy와 68% 수평 정확도 반경을 정의한다. [LocationManager](https://developer.android.com/reference/android/location/LocationManager)의 공개 제공자 조회·구독·해제와 [위치 권한](https://developer.android.com/develop/sensors-and-location/location/permissions)의 foreground 조건을 사용한다. 현재 mediaPlayback 서비스는 location foreground service가 아니므로 백그라운드 수신을 완료로 주장하지 않는다.

[Notion v0.8 동작 명세](https://app.notion.com/p/40fcf80747f281e0bde7d1638339550f)를 다시 확인했다. 오래되거나 모르는 품질의 기록은 자동 안내·수집에서 제외하고, RegionPolicy는 검증 전 보류한다.

## 검증

최종 privacy 앱/기기 검증 APK 빌드·Lint, JVM **810개/60 suite**, 호스트 **158개(생략 0)**, 알려진 앱 SDK 로그 sink 정적 검사를 통과했다. 소스 564개의 동결 해시가 일치한다. 동일 최종 APK 쌍의 기기 **8종**(android-location-contract, location-storage, automatic-planning, landmark-storage, session-recovery, car-policy-ui, media-session, app-playback)과 설치·실행 smoke도 통과했다. 실제 공개 이야기 TTS와 정상 완료 저장을 재확인했다. 최종 설치 버전·해시 일치와 전면 실행, 기존 localhost HTTPS health 200 및 adb reverse 복원을 확인했다. [집계](../../artifacts/milestone-23/verification.json), [최종 APK](../../.local/builds/dev23/privacy-final/). `android-location-contract`는 공개 제공자 메타데이터와 합성 Android Location/Room 자료만 검사하며 실제 좌표 조회·구독·권한 변경을 하지 않는다. 실제 위치와 화면 전환 수락 시험을 대신하지 않는다.

초기 기기 메타데이터 검사는 활성 `fused` 제공자와 권한 미허용 상태에서 수행했다. 이후 사용자가 앱 위치 기록 ON과 권한 허용을 확인했다. 2026-11-08 12:18Z 현재 사용자 10의 FINE/COARSE/Pleos 권한과 FINE AppOp foreground를 읽기 전용으로 확인했다([권한 기록](../../artifacts/milestone-23/live-location/permission-state.json)). 정확한 위치는 이미 허용되어 추가 사용자 설정이 필요 없다. 후속 실제 수신과 Activity 닫기/재열기 검증은 아래 별도 결과를 따른다. 초기 통합 중 786개 검사 결과는 최종 810개와 합산하지 않는다. 최종 소스는 564개 모두 일치한다.

## 남는 작업과 순서

1. 사용자 동의/OS 권한과 에뮬레이터 실제 표본·Activity 닫기/재열기는 확인했다. Home/background 전환 및 실주행 정확도 검수는 별도다. Virtual Console 위치 주입은 수행하지 않는다.
2. 실제 경로 형상·구간 ETA 공급은 여전히 미확정이다. 목적지/경유지를 직선 경로로 간주하지 않는다.
3. 검토된 RegionPolicy 설치 흐름과 시연 자료 검증이 필요하다. 현재 `RoomRegionInstaller`는 geometry provenance와 pending policy를 함께 고정 비교하므로 정책 설치 시에는 immutable geometry와 정책의 변경 계약을 먼저 분리해야 한다. 단순 DB UPDATE로 정책을 승인하면 다음 catalog load에서 경계가 무효화될 수 있다.
4. 지역별 콘텐츠·랜드마크 정책은 기존 검토된 연결 계약을 따라 확충한다. 기존 NULL 정책과 가거도 story의 지역 NULL을 임의 변경하지 않는다.

사람 발화·음성 서비스 원문 로그 문제, 실제 Suspend와 백그라운드 수신, 최종 UI·데모는 이번 완료 범위가 아니다.

## 사용자 허용 후 실제 에뮬레이터 검증

2026-11-08 12:33Z, test-only `app-location-live`가 PASS했다. 실제 제품 Activity·Host·Room을 사용하고 저장된 동의와 OS 권한을 먼저 검사했다. 제품 APK는 기존 dev.23과 동일하다. 전면 첫 관측은 valid 표본 2개(실시간 UPDATE 1개), 종료·재열기까지 누적 관측은 valid 15개(UPDATE 13개)다. 좌표 원문은 보고서에 넣지 않았다.

Pleos에서 Home와 `moveTaskToBack(true)`만으로 onStop이 발생하지 않아 초기 두 검사는 실패 기록으로 보존했다. 최종 검사는 실제 `Activity.finish()`의 onStop/onDestroy, recording segment 닫힘, 2.5초간 저장 개수 불변, 같은 Host/session의 새 Activity 및 새 segment 유효 UPDATE를 확인했다. **이는 Activity 닫기/재열기 검증이며 Home/background 전환 성공이나 vendor 구독 해제 ACK를 뜻하지 않는다.** 기존 기록·동의·권한을 보존했고 마이크·모델·Virtual Console·위치 주입은 사용하지 않았다.

후속 검증 APK 빌드, 관련 JVM 24개, 호스트 probe 도구 57개가 통과했다. 기존 전체 810개/8종 검증과 합산하거나 새 검증 APK에서 전체 재실행한 것으로 표시하지 않는다. [실제 위치 집계](../../artifacts/milestone-23/live-location/verification.json), [검증 코드 변경](../../artifacts/milestone-23/live-location/test-only-change.json)을 따른다.

Pleos UPDATE 접근 lease는 공식 허가 토큰이 아닌 보수적인 앱 정책이다. 콜백 침묵만으로 개인정보 차단을 판정할 수 없으며 공개 SDK의 전달 제약과 병용 preflight 미확인은 [별도 감사](./2026-11-08-location-access-gate-audit.md)에 보존했다. 실제 위치 수신 성공이 이 SDK 계약을 새로 보장하지는 않는다.
