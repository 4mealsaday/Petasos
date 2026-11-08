# 공식 시·군·구 경계 설치

기준: v0.8, `0.8.0-dev.18`. 사용자가 제공한 공식 ZIP/PDF를 검토하고, 지역 경계를 APK에 포함해 실제 Host에 연결했다. 기존 길이 기반 지역 편성·대화/이동/시간 변경에 따른 재편성 흐름은 유지한다. 이 단계는 경계 데이터 공급을 완료하며 실제 경로 예측이나 위치 품질 계약을 대신하지 않는다.

## 원본과 변환

- 원본: `.local/regions/source/N3A_G0100000.zip`, SHA-256 `d7a3751bd89f8eabd129eff603b15a55e61afe472e5255a9202ec5a2b01fe6fa`.
- 설명서: `연속수치지형도_데이터_설명서.pdf`, SHA-256 `a392799ba4a7cead1dadaad7fd1ede9c0ea6a87b467fa8f8f51be5d96081e8d7`. 136쪽 행정경계 속성 표와 실제 DBF/XML/PRJ를 대조했다.
- [NGII/VWorld dataset 30252](https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=MK&dsId=30252)의 개별 [CC BY 2.0 KR](https://creativecommons.org/licenses/by/2.0/kr/) 표기를 근거로 별도 출처 revision을 설치한다. API 응답을 배포 파일로 전용하지 않았다.
- DBF는 cp949, `BJCD` 10자리의 뒤 5자리가 모두 `00000`인 것을 확인했다. 앞 5자리를 법정 시·군·구 코드로 사용한다. `DIVI`/`SCLS`의 시·군·구 대응도 검사한다. 시와 소속 구의 중첩을 제거하거나 군 코드 접두사로 통합하지 않는다.
- PRJ와 XML 모두 EPSG:5179다. PROJ 네트워크를 끄고 `always_xy=True`, `allow_ballpark=False`, `only_best=True`로 EPSG:4326 경도·위도로 변환했다. 변환 연산의 **예상 정확도 1m는 확정 오차 상한이 아니다.** 런타임은 이를 내부 거리에서 차감하지만, 그것만으로 실제 지역 확인 정책을 승인하지 않는다.
- 원본은 264개 지역, 9,994,396개 좌표, 34,045개 ring이다. 단순화·양자화·도형 보정 없이 모든 꼭짓점, 중복 좌표, ring 순서를 보존한다. PyShp의 hole 재해석 오류와 변환 전후 GEOS 위상 오류는 격리한다.
- 파일 내부 DBF 날짜는 2025-09-10, XML 수정 날짜는 2025-09-09다. 카탈로그 갱신일 2026-09-21을 법정 경계의 효력 발생일로 사용하지 않는다. 실제 기준일은 미확인이다.

검토 입력은 [ngii-source-review.json](../reference/regions/ngii-source-review.json), 상세 변환 결과는 [region-dataset-audit.json](../../artifacts/milestone-18/region-dataset-audit.json)을 따른다. 데이터 디렉터리 이름의 날짜는 카탈로그 식별자이며 실제 행정구역 기준일이 아니다.

## 코드 책임

- `scripts/build-region-dataset.py`: 입력 SHA·ZIP 구성·속성·CRS·위상 검사, 지역별 `.pgeo`와 strict manifest 생성. 기존 결과는 덮어쓰지 않는다. 서버 Python과 분리한 `.local/region-venv`에 `scripts/requirements-regions.txt`의 고정 GIS 의존성을 설치한다.
- `location/RegionDatasetManifest.kt`: 별도로 고정한 manifest SHA, 형식·경로·CRS·개수·경계 범위 검증.
- `location/PackedRegionBoundaryLookup.kt`: bbox에 걸린 지역만 고정 8KiB 버퍼로 읽고, 매 조회마다 모든 좌표·ring·SHA·길이·EOF·내부 거리를 확인한다. 신안군의 약 242만 좌표도 배열 전체를 메모리에 올리지 않는다. 후보 하나라도 변조/권리 철회/오류가 있거나 격리 bbox와 겹치면 전체 결과를 보류한다.
- `storage/RoomRegionInstaller.kt`: 출처와 지역 revision만 원자적·멱등 설치한다. 불변 충돌은 전체 rollback하며 출처 철회를 재설치로 복원하지 않는다. 텍스트 콘텐츠 TX-05와 분리한다.
- `platform/android/location/BundledRegionCatalog.kt`: Host worker에서 설치·lookup 조립, 현재 출처 권한 재검사, 예상 변환 정확도 차감. 실패 시 NONE으로 보류한다.
- 기존 정차 출처 창을 **자료·음원 출처**로 확장했다. 원저작자·자료 URL·CC BY 링크·좌표 변환 안내를 표시하며, 출처 조회 자체로 DB 설치·세션·동의를 만들지 않는다. UI 레이아웃과 디자인 계층은 유지한다.

`.pgeo`는 `PTRG0001` magic, big-endian ring/point 개수, ring별 bbox·좌표 개수·경도/위도 double을 저장한다. 크기는 `16 + 36×rings + 16×points`다. 공식 원본 약 161MB를 포함하므로 APK 크기도 증가한다. 조회는 메모리를 제한하지만 매우 큰 해안 지역의 실행 시간은 별도 성능 측정이 필요하다.

## 지역 확인과 남은 입력

모든 `region_revision.verified_at`은 NULL이며 `policyStatus=pending`이다. 출처의 geometry 이용 허가와 방문 판정 정책은 별개다. 시·소속 구가 겹치면 다중 결과를 그대로 돌려주고 기존 단일 지역 확인 단계가 보류한다. 격리된 원본은 형상을 임의로 수정해 사용하지 않는다.

제품의 실제 위치 의미 어댑터는 `UNKNOWN`, 경로별 통과 예측은 `UNAVAILABLE`을 유지한다. 따라서 공식 경계를 넣었다고 실제 주행의 지역 자동 안내·카드 획득이 활성화되지는 않는다. 기존 가거도 story/place의 지역 연결도 변경하지 않았다. 다음에는 [위치·경로 계약 문의 초안](./pleos-location-route-support-request.md)의 timestamp/confidence 의미·실제 선택 경로 geometry/ETA를 확인하고, 현행 명세에 맞는 방문 정책과 지역별 콘텐츠 연결을 검토해야 한다.

마이크·Virtual Console은 보류 상태다. 이번 검증의 위치는 공개 합성 시험점이며 차량 위치를 주입하거나 사용자 위치 기록 동의를 변경하지 않는다.

## 재생성

```sh
.local/region-venv/bin/python scripts/build-region-dataset.py \
  --zip .local/regions/source/N3A_G0100000.zip \
  --description .local/regions/source/연속수치지형도_데이터_설명서.pdf \
  --output <존재하지-않는-검토용-출력-디렉터리> \
  --audit <검토-결과.json>
```

변경된 원본은 기존 검토 hash를 자동 갱신하지 않는다. 새 원본·변환 도구·결과를 검토하고 manifest pin과 독립 GEOS 시험점을 함께 검증해야 한다. 같은 immutable dataset revision에 다른 정책이나 형상을 덮어쓰지 않는다.

## 검증

최종 privacy 앱/검증 APK 빌드·Lint, JVM **662개/49 suite**, 호스트 **137개**가 PASS다. 호스트 검사는 GIS 컴파일러 15개를 포함하며 skip은 없다. 실제 정상 261개 전체의 약 998만 좌표를 독립 GEOS 내부 시험점과 대조해 스트리밍 hash·개수·범위·포함 판정을 검증했다. 수원시/장안구 중첩과 3개 격리 영역도 확인했다.

동일 최종 APK 쌍으로 Pleos 기기 **6종**(`region-storage`, `location-storage`, `automatic-planning`, `media-session`, `car-policy-ui`, `app-playback`)과 설치·실행 smoke가 PASS다. 공식 geometry/Room 검사는 임시 DB·공개 합성 좌표를 사용한다. 실제 제품 공개 이야기 TTS 시작·완료 저장·Stop도 통과했다. 기존 정차 출처 창의 NGII/라이선스/변환 안내·음악 출처 표시와 닫힘을 실제 UI에서 확인했다.

최종 앱은 `0.8.0-dev.18`, APK 148,039,559 bytes다. 최종 앱/시험 APK는 `.local/builds/dev18/privacy-final/`에 보존한다. 06:20:21Z에 설치 hash 일치·전면 실행·앱/서비스 활성 녹음 각각 0·기존 localhost reverse·HTTPS `/v1/health` 200을 확인했다. 녹음 값은 해당 시점 관측이며 입력 종료 ACK나 연속 감시가 아니다. source snapshot 530개가 일치한다.

서버 191개는 milestone-17의 기검증이며 서버 코드는 이번에 변경하지 않았다. 이번에는 privacy variant를 검증·설치했고 debug 빌드/Lint, 이전 전체 15종 기기 suite, 실제 외부 API/모델 호출·사람 발화·위치 주입은 재실행하지 않았다. 초기 공선 좌표 시험의 부동소수점 오차는 정확한 이진 좌표 fixture로 수정했다. PyShp 자동 해석 격리를 보완한 재변환 결과는 최초 manifest와 hash가 동일하다.

최종 빌드·시험·기기 결과와 정확한 범위는 [milestone-18](../../artifacts/milestone-18/verification.json)에 기록한다. 이전 dev.17 결과는 [milestone-16](../../artifacts/milestone-16/verification.json), APK 변경 없는 사전 경계 엔진 검증은 [milestone-17](../../artifacts/milestone-17/verification.json)과 구분한다.
