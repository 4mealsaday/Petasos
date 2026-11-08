# 검토된 콘텐츠의 서버 evidence 병합

`scripts/merge-content-evidence.py`는 기존 서버 근거 registry를 유지하면서 검토된 v1/v2 콘텐츠 번들의 생성용 출처·사실을 추가한다. 콘텐츠 검수, 기기 인증 발급, provider 보관 설정 검수, 서버 배포를 대신하지 않는다. 실행에는 `server/requirements.txt`가 설치된 Python 환경을 사용한다.

## 입력과 보존 계약

- `--base`: `.local/` 아래의 기존 private evidence registry. 전체 문서를 현재 `EvidenceRegistry` 계약으로 검사한다.
- `--bundle`: `.local/content/` 아래의 검토된 콘텐츠 번들. 기존 `build_evidence_registry`를 재사용하므로 번들 전체를 검증한 뒤 `allowGeneration=true`인 출처와 그 출처로 모두 뒷받침되는 사실만 내보낸다.
- `--allow-device-id`: 신규 출처에 허용할 등록 기기를 명시한다. 여러 기기는 옵션을 반복한다. 기존 기기의 ID를 선택하는 것은 토큰의 유효 기간을 연장하는 작업이 아니다.
- `--output`: `.local/content/` 아래의 **새 파일**. 기존 파일 덮어쓰기와 symlink/traversal을 거부한다. 디렉터리 0700, 파일 0600으로 저장한다.

기존 출처·사실 행의 값과 배열 순서, 기기 허용 목록을 보존하며 신규 revision만 뒤에 추가한다. 원본 파일의 바이트는 변경하지 않는다. 새 파일 전체는 canonical JSON으로 직렬화하므로 원본의 공백·줄바꿈 형식을 복사하는 기능은 아니다.

동일 `sourceRevisionId`의 본문 변경 또는 동일 `factRevisionId`의 stable fact ID·종류·본문·출처 집합 변경은 `EVIDENCE_REVISION_CONFLICT`다. 기존 source의 기기 허용 집합이 다르면 `EVIDENCE_GRANT_CONFLICT`다. 이 도구는 grant를 합치거나 축소하거나 복원하지 않는다. 특히 빈 grant로 철회한 기존 출처를 재수입하여 활성화할 수 없다. 출처 참조와 grant의 순서만 다른 경우는 같은 의미로 인정하되 기존 행의 순서를 유지한다. 별도의 정상 revision ID 추가는 허용한다.

각 입력의 소유자 전용 권한·정규 파일·크기·읽는 동안의 파일 변경 여부를 검사하고 중복 JSON 키를 거부한다. 최종 파일은 서버의 8 MiB 한도 안이어야 한다. stdout에는 고정 상태 코드, 개수, 결과 SHA-256만 출력하며 입력 본문, revision ID, grant, 토큰, 경로를 출력하지 않는다. 네트워크와 모델 호출은 없다.

```sh
# DEVICE_ID는 이미 등록한 기기의 실제 ID로 대체한다. 토큰 문자열이 아니다.
.local/server-venv/bin/python scripts/merge-content-evidence.py \
  --base .local/content/evidence/existing.json \
  --bundle .local/content/bundles/reviewed.json \
  --allow-device-id DEVICE_ID \
  --output .local/content/evidence/merged-new.json
```

동일 번들을 다시 병합하면 기존 행은 그대로이며 추가 개수는 0이다. 이 경우에도 새 출력 경로를 요구한다. 서버는 story 목록이나 지역 binding을 이 registry에 저장하지 않는다. Android가 가진 이야기의 출처·사실 요청을 서버에서 검증할 수 있게 하는 자료다.

## 2026-11-09 준비 결과

[P0 콘텐츠 검수 근거](p0-content-review-evidence.md)의 10개 이야기 번들로 별도 통합 파일을 생성했다.

| 항목 | 결과 |
| --- | --- |
| 새 파일 | `.local/content/evidence/p0-plus-gageodo-v1-20261109.json` |
| 원본 보존 | 기존 2개 출처·2개 사실 및 grant 그대로, 원본 바이트 불변 |
| 신규 추가 | 12개 출처·18개 사실 |
| 최종 개수 | 14개 출처·20개 사실 |
| 파일 권한 | 0600 |
| SHA-256 | `4163963e807777a36a853fc5f1fb24e86243367edbf12881cc2c93658242897e` |
| 로컬 근거 조회 | 신규 10개 이야기의 문단 사실·출처 조회 모두 통과 |
| 자동 검사 | 신규 병합 검사 17개 포함 서버 전체 305개 통과 |
| 검사 기록 | `artifacts/p0-completion/evidence-merge-server-tests.log` |
| 비공개 검증 기록 | `.local/content/review/p0-evidence-merge-validation-20261109.json` |

이 준비 단계에서는 config, 원본 evidence, 토큰, 기기 설정, 실행 중 서버를 변경하지 않았다. 실제 모델도 호출하지 않았다. 로컬 `EvidenceRegistry.resolve` 성공은 인증 토큰의 유효성이나 외부 모델의 응답을 검증한 결과가 아니다.

## 실제 활성화의 별도 작업

서버의 `evidenceFile`을 새 파일로 바꾸는 경우 해당 config로 재시작해야 한다. 같은 기존 경로를 교체하면 요청 경계에서 다시 읽지만, 이번에는 기존 원본을 보존하기 위해 별도 파일을 준비했다. 배포 담당자가 최종 파일과 설정을 검토하고 연결한다.

준비 당시 로컬 기기 토큰과 provider의 보관 설정 검토 기한이 모두 만료되어 있었다. 토큰은 새 로컬 난수로 갱신하고 `scripts/probe-pleos.py generation-provision --device-token-file ...`로 앱 Keystore에 다시 전달할 수 있다. 기존 `server/provision-local.py`는 **새 디렉터리 생성 전용**이므로 기존 config/evidence/fingerprint 키를 일괄 교체하는 갱신 도구로 사용하면 안 된다. provider 검토 기한은 실제 계정·제공자 보관 설정을 다시 확인한 뒤에만 갱신한다. 사용자의 공공 API/OpenRouter 키를 다시 요청할 필요는 없다.

보관 설정과 인증의 현행 상태를 확인한 뒤 별도의 실제 생성 검증을 수행해야 한다. 서버 동작·재시작·기기 전달 방법은 [서버 안내](../../server/README.md)를 따른다.
