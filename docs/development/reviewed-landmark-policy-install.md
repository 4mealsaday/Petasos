# 검토한 랜드마크 정책 최초 설치 계약

dev.28에 적용하고 빌드·JVM·기기 저장소 검사를 통과했다. [최종 검증](2026-11-09-landmark-install-and-bundled-playback.md)을 따른다. 실제 장소 정책 승인, 임계값 선정, APK 승인 catalog 연결은 포함하지 않는다.

## v0.8와 기존 이야기 보존

[DATA-11](../Petasos_데이터_API_계약.md)은 `place.landmark_policy_json`을 랜드마크 자동 안내 정책으로 규정하고 `story.place_id`를 선택 필드로 둔다. 따라서 검토한 정책 자체에 명시된 `storyId`와 독립 `associationFactRevisionId`를 연결의 권한으로 사용한다. 기존 story 행·stable fact·대본·청취 이력·카드 ID를 변경하거나 복제하지 않는다. 새 Room 테이블이나 migration도 필요하지 않다.

새 정책은 기존 `story.place_id`가 NULL 또는 해당 `placeId`와 동일할 때만 허용한다. 다른 장소를 이미 가리키는 이야기는 거부한다. 기존 exact 11-key 정책 호환은 **story.place_id가 반드시 해당 장소와 동일한 경우**만 유지한다. NULL 이야기를 연결하려면 새 pinned envelope가 필요하다. marked envelope가 손상되어도 legacy 해석으로 돌아가지 않는다.

## 입력과 저장 형식

단일 review는 기존 11개 flat 정책 키에 `formatVersion=1`, `placeId`, `placeMetadataSha256`, `evidenceSha256`를 더한 exact 15-key JSON이다. DATA-11 반경·통과·방향·cooldown·버전과 기존 qualityAdapterVersion/storyId/associationFactRevisionId/verifiedAt/reviewBasis는 그대로다. 설치 입력의 reviewStatus는 verified만 허용한다. 저장소 독자는 verified/rejected를 해석한다.

배치는 exact `{ "formatVersion": 1, "reviews": [...] }`이며 최대 512건이다. 빈 목록은 no-op이고, 중복 placeId 또는 storyId는 전체 거부한다. StrictJson의 기존 정수·256KiB 한도, 중복 JSON 키·잘못된 타입·unknown key·UTF-8 검사를 유지한다. 반경은 양의 정수이며 Double 변환으로 값이 달라지면 거부한다. cooldown은 0 이상이다. 숫자나 규칙의 default는 없다.

`placeMetadataSha256`는 정책을 제외한 place_id/region_id/name/aliases_json/latitude/longitude/coordinate_source_revision_id의 canonical hash다. 위경도는 **SQLite에서 읽은 정확한 유한 Double을 decimal string으로 인코딩**한다. decimal 원문 표현의 해시나 측량 정확도 증명이 아니다. 설치와 런타임이 같은 `LandmarkPlaceMetadata`를 사용하므로 정책 검토 후 좌표·출처·장소 메타데이터가 달라지면 기존/신규 후보 모두 거부한다. source revision과 fact revision은 기존 TX-05의 불변성 계약을 유지한다.

`evidenceSha256`는 오프라인 검토 자료를 지정하는 해시다. codec/installer가 해당 파일을 열거나 증거의 진실을 검사했다고 주장하지 않는다. 검토자는 좌표 출처와 이야기 연결 및 DATA-11 임계값의 근거를 별도로 검토한다. 문자열·좌표·장소명에서 관계를 자동 추론하지 않는다.

## 설치·재설치·철회

`RoomLandmarkPolicyInstaller.install(bytes|reviews)`는 전체 형식 검증 후 하나의 Room transaction에서 처리한다. 장소·이야기·연결 사실이 이미 존재해야 하며 source/fact/좌표를 삽입하지 않는다. 좌표 출처와 모든 association fact 출처가 현재 verified이고 allowDisplay/allowDistribution을 허용해야 한다. allowGeneration은 요구하지 않으며 새로 부여하지도 않는다. 연결 사실은 verified_fact이고, 좌표 출처·연결 사실·연결 사실 출처의 검토 시각은 정책 검토 시각 이하, 정책은 현재 시각 이하여야 한다.

최신 published story의 대본 해시·출처 권한도 기존 TX-05 reader로 확인한다. 전역 완료·세션 제외는 설치 허가와 별개이며 자동 후보를 발급할 때 공용 `RoomAutomaticStoryEligibility`로 적용한다. 생성된 대본이 association fact를 생략해도 정책이 지정한 revision과 출처를 매번 별도로 검사한다.

- 최초 설치는 기존 `landmark_policy_json IS NULL`일 때만 compare-and-set으로 쓴다.
- canonical JSON이 정확히 같고 현재 근거도 유효한 재설치는 unchanged다.
- 다른 review, rejected marker, malformed/noncanonical 기존 정책은 덮어쓰지 않는다.
- 실패한 배치는 앞에서 바뀐 정책까지 rollback한다.
- `revoke(review)`는 exact verified JSON을 같은 review의 rejected JSON으로 바꾼다. 같은 철회를 재실행하면 no-op이며, 재설치로 rejected를 verified로 바꾸지 않는다.
- 수동 SQL로 JSON 전체를 NULL로 삭제하면 미설치와 구분할 수 없다. 이 삭제는 지원하는 철회 경로가 아니며, 그 상태에서 옛 review 재설치까지 차단한다고 주장하지 않는다.

TX-05의 place 재설치는 정책을 제외한 기존 geometry/metadata를 exact 비교한다. 같으면 verified/rejected/NULL/손상된 기존 정책값 모두 보존하고, 새 장소에만 NULL을 넣는다. 좌표나 출처 변경은 여전히 immutable conflict다. 이것은 기존 content bundle이 승인 정책을 덮거나 되살리는 것을 막으며 정책 변경 절차를 추가하지 않는다.

장소당 정책 하나, 이야기당 정책 장소 하나다. 설치기는 DB의 기존 reviewed/rejected 연결까지 대조한다. 런타임도 해석 가능한 정책 두 개가 같은 storyId를 지정하면 그 이야기의 후보를 모두 거부한다. 이름 정렬로 하나를 선택하거나 앞 64개로 자르지 않는다.

## 파일 책임과 검증 경계

`ReviewedLandmarkPolicy.kt`는 DTO·엄격한 단일/배치 codec·retained rejection을, `LandmarkPlaceMetadata.kt`는 정책 제외 identity hash를 담당한다. `RoomLandmarkPolicyStore.kt`는 공용 현재 근거 검사를, `RoomLandmarkPolicyInstaller.kt`는 atomic 첫 확정·철회를 담당한다. `RoomLandmarkCandidates.kt`는 정책의 storyId에서 공용 이야기 eligibility를 조회하여 현재 후보를 발급한다. `RoomContentInstaller.kt`의 변경은 place 재설치 시 정책을 보존하는 부분뿐이다.

순수 JVM 테스트 16개는 형식/타입/중복/시각/숫자 정밀도/상태 구분/metadata pin을 다룬다. JVM 검사와 Android 기기 검사는 PASS다. `LandmarkPolicyStorageProbe.kt`는 합성 TX-05 입력과 별도 disposable Room에서 다음을 검사하도록 작성했다: NULL story FK 유지한 연결; legacy NULL 거부/기존 nonnull 호환; exact 재설치 및 geometry reload 보존; 좌표·source·fact·권한·시각 변경 거부; generated omission 뒤 독립 근거 철회; 다른 장소 FK와 중복 story 정책 거부; 후반 실패 rollback; rejected 재설치 차단; 기존 카드·history 보존과 location·consent·대화 0건 변경. 실제 public-data place draft를 설치하거나 해당 좌표를 승인하는 시험은 아니다. 완료 attempt/card/proof는 명시적으로 만든 합성 이력이며 실제 음성 출력 증거로 표시하지 않는다.

Runner와 host 등록을 적용했다. `landmark-policy-storage`는 privacy 지원·90초 host 제한이며 private review/외부 fixture를 받지 않는다. 호스트 probe 전체 70개와 최종 privacy APK의 실제 Room probe가 PASS다. 이 검사 자체는 합성 데이터이고 별도 고정 콘텐츠 probe가 실제 두 장소를 확인한다.

이 단계의 callable installer는 입력을 자동 승인하는 제품 기능이 아니다. 실제 정책 catalog/호출자·실제 반경 근거가 없으므로 앱에서 실제 랜드마크를 활성화했다고 보고하지 않는다. 다음 자료 검토는 [첫 두 장소 계획](p0-landmark-data-plan.md)의 문화역 서울 284 및 엑스포 공원 좌표·공식 경로·합성 오차/시간 시퀀스를 구분하여 진행한다. 보조 경로가 근처에 있다는 이유로 Pleos 활성 경로 membership을 승인하지 않는다.
