"""Build the v0.8 JSON contract and data dictionary from explicit local definitions."""
from pathlib import Path
import json
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "spec"

def obj(properties, required=None):
    return {"type": "object", "properties": properties,
            "required": list(properties) if required is None else required,
            "additionalProperties": False}

def arr(item, maximum=50, minimum=0, unique=False):
    return {"type": "array", "items": item, "minItems": minimum,
            "maxItems": maximum, "uniqueItems": unique}

ID = {"type": "string", "minLength": 1, "maxLength": 128, "pattern": "^[A-Za-z0-9][A-Za-z0-9._:-]*$"}
NID = {"oneOf": [ID, {"type": "null"}]}
TEXT = {"type": "string", "minLength": 1, "maxLength": 4000}
SHORT = {"type": "string", "minLength": 1, "maxLength": 256}
NAT = {"type": "integer", "minimum": 0}
POS = {"type": "integer", "minimum": 1}
NULL = {"type": "null"}
ROUTE = {"oneOf": [NULL, obj({"routeRevision": NAT})]}
IDS = arr(ID, unique=True)
PARAGRAPH = obj({
    "paragraphKey": ID, "text": {"type": "string", "minLength": 1, "maxLength": 240},
    "kind": {"enum": ["fact", "tradition", "personal", "transition"]},
    "sourceRevisionIds": IDS, "factRevisionIds": IDS
})
base = {
    "schemaVersion": {"const": "0.8"}, "requestId": ID, "sessionId": ID,
    "cancelEpoch": NAT, "routeDependency": ROUTE,
    "turnId": NID, "turnVersion": {"oneOf": [POS, NULL]},
}
payloads = {
    "story": obj({"storyId": ID, "topicId": ID, "regionId": NID,
                  "sourceRevisionIds": arr(ID, 50, 1, True), "excludedFactIds": IDS,
                  "interestTags": arr(SHORT, 20, unique=True)}),
    "answer": obj({"userText": TEXT, "contextSummary": {"type": "string", "maxLength": 4000},
                   "heardParagraphIds": arr(ID, 64, unique=True), "sourceRevisionIds": IDS}),
    "resume": obj({"contextSummary": {"type": "string", "maxLength": 2000},
                   "candidates": arr(obj({"contentId": ID, "paragraphId": NID,
                       "kind": {"enum": ["story", "music"]}}), 20)}),
    "summary": obj({"userText": TEXT,
                    "completedResponses": arr(obj({"paragraphKey": ID, "text": TEXT,
                        "sourceRevisionIds": IDS}), 32),
                    "partialUnknownKeys": IDS})
}
results = {
    "story": obj({"storyId": ID, "storyVersionId": ID,
                  "paragraphs": arr(PARAGRAPH, 12, 1),
                  "estimatedDurationMs": POS}),
    "answer": obj({"paragraphs": arr(PARAGRAPH, 8, 1),
                   "action": {"enum": ["continue", "next", "music", "stop", "replay", "unsupported"]},
                   "targetContentId": NID}),
    "resume": obj({"action": {"enum": ["resume", "skip", "switch", "wait"]},
                   "targetContentId": NID, "paragraphId": NID,
                   "transitionText": {"oneOf": [{"type": "string", "minLength": 1, "maxLength": 240}, NULL]}, "reason": SHORT}),
    "summary": obj({"summaryText": TEXT, "userClaims": arr(TEXT, 20),
                    "verifiedFactRevisionIds": IDS, "unresolvedQuestions": arr(SHORT, 20),
                    "completedResponseKeys": IDS, "partialUnknownKeys": IDS})
}
requests, successes = [], []
for task in payloads:
    typed = dict(base)
    if task in ("answer", "summary"):
        typed.update(turnId=ID, turnVersion=POS)
    else:
        typed.update(turnId=NULL, turnVersion=NULL)
    requests.append(obj({**typed, "task": {"const": task},
                         "inputHash": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
                         "payload": payloads[task]}))
    successes.append(obj({**typed, "task": {"const": task}, "status": {"const": "ok"},
                          "result": results[task],
                          "metadata": obj({"modelId": SHORT, "provider": SHORT,
                                           "promptVersion": SHORT, "inputTokens": {"oneOf":[NAT,NULL]},
                                           "outputTokens": {"oneOf":[NAT,NULL]}})}))
errors = ["INVALID_REQUEST", "UNSUPPORTED_SCHEMA", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND",
          "IDEMPOTENCY_CONFLICT", "STALE_CONTEXT", "NO_EVIDENCE",
          "INVALID_MODEL_OUTPUT", "RATE_LIMITED", "UPSTREAM_UNAVAILABLE",
          "GENERATION_TIMEOUT", "CANCELLED"]
schema = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": "urn:petasos:generation:0.8",
    "title": "Petasos generation transport contract v0.8",
    "$defs": {
        "Request": {"oneOf": requests},
        "SuccessResponse": {"oneOf": successes},
        "ErrorResponse": obj({"schemaVersion": {"const": "0.8"},
            "requestId": NID, "status": {"const": "error"},
            "code": {"enum": errors}, "retryable": {"type": "boolean"},
            "retryAfterMs": {"oneOf": [NAT, NULL]}})
    },
    "oneOf": [{"$ref": "#/$defs/Request"}, {"$ref": "#/$defs/SuccessResponse"},
              {"$ref": "#/$defs/ErrorResponse"}]
}
(SPEC / "generation-v0.8.schema.json").write_text(
    json.dumps(schema, ensure_ascii=False, indent=2) + "\n")

DESCRIPTIONS = {
 "trip_id": "이동 기록 ID. 다른 모델에서는 Trip 참조",
 "started_at": "시작을 관찰한 UTC 시각",
 "ended_at": "정상 종료를 관찰한 UTC 시각. 비정상 종료는 null",
 "last_observed_at": "마지막으로 관찰·커밋한 위치의 UTC 시각",
 "interruption_detected_at": "복구 과정에서 중단을 발견한 UTC 시각",
 "status": "모델별 처리 상태. 허용 enum은 CHECK 참조",
 "end_reason": "user_end/service_end/suspend/permission_lost 등 관찰한 종료 이유",
 "revision": "낙관적 동시성 검사에 사용하는 증가 버전",
 "session_id": "Coordinator 실행 세대 ID",
 "closed_at": "세션 또는 대화 상태를 닫은 UTC 시각",
 "cancel_epoch": "관련 실시간 요청 무효화 세대. 음수가 아님",
 "state_revision": "수용된 상태 변경 순번. 시각 대신 최신 상태 비교",
 "route_revision": "내비 경로 변경 버전. GPS 수신마다 증가하지 않음",
 "segment_id": "위치 기록 구간 ID",
 "point_id": "수신 위치 기록 ID",
 "sequence": "구간 안 수신 순번. 위치 시각과 별개",
 "provider": "원천 또는 생성 제공자 식별자",
 "provider_epoch": "제공자 event ID의 유효 범위. 모르면 event ID와 함께 null",
 "source_event_id": "제공자가 중복 식별을 보장하는 ID. 임의 좌표 해시를 넣지 않음",
 "raw_timestamp": "변환 전 제공자 timestamp의 문자열 표현",
 "source_time_unit": "epoch_ms/epoch_s/elapsed_ms/other/unknown",
 "observed_at": "검증된 측정 시각의 UTC 변환. 의미를 모르면 null",
 "received_at": "앱이 위치를 수신한 UTC 시각",
 "received_elapsed_ms": "수신 시 단조 시각, ms. 같은 clockScopeId에서만 비교",
 "clock_scope_id": "단조 시계를 함께 비교할 프로세스 실행 범위 ID",
 "latitude": "정규화한 WGS84 위도, degree. 범위 밖 원본은 raw 컬럼에 보존",
 "longitude": "정규화한 WGS84 경도, degree",
 "raw_coordinates_json": "수신 원본 좌표만 보존한 JSON. 다른 개인정보는 복제하지 않음",
 "accuracy_meters": "제공자가 의미를 보장한 수평 정확도, m. 미지원이면 null",
 "provider_confidence_json": "제공자 confidence 원값과 단위·어댑터 버전",
 "quality_status": "valid/stale/invalid/unknown. valid만 안내·수집 후보",
 "source_revision_id": "내용 해시로 고정한 출처 revision 참조",
 "source_id": "같은 출처 항목의 안정된 내부 ID",
 "source_url": "원문 URL. 생성 API가 임의로 따라가는 입력이 아님",
 "title": "사용자에게 표시할 제목",
 "fetched_at": "이 revision을 최초 수집한 UTC 시각",
 "source_updated_at": "원문이 명시한 정확한 수정 시각. 모르면 null",
 "source_updated_raw": "날짜만 있는 표기 등 원문의 갱신 정보",
 "verified_at": "자료·매핑·대본을 검토한 UTC 시각",
 "content_hash": "허용된 정규화 내용의 SHA-256. revision 동일성 확인",
 "license_json": "라이선스 종류·원문 링크·자산별 조건",
 "allowed_uses_json": "allowGeneration/allowDisplay/allowDistribution boolean 및 검토 근거",
 "attribution": "배포·표시에 필요한 출처 문구",
 "review_status": "draft/verified/rejected",
 "region_id": "동일 지역을 나타내는 안정된 내부 ID",
 "region_revision_id": "판정 당시의 지역 코드·경계 데이터·정책 revision",
 "code": "선정한 법정 시·군·구 코드",
 "dataset_version": "지역 판정 원천의 버전",
 "name": "표시 이름",
 "policy_json": "DATA-11 RegionPolicy. 검증 전 자동 적립 금지",
 "place_id": "동일 장소의 내부 ID",
 "aliases_json": "별칭 문자열 배열",
 "coordinate_source_revision_id": "좌표를 확인한 출처 revision",
 "landmark_policy_json": "DATA-11 LandmarkPolicy. null이면 자동 랜드마크 제외",
 "external_id": "제공자 안에서의 장소 ID",
 "fact_revision_id": "특정 내용·근거에 고정된 사실 revision",
 "fact_id": "표현이 달라도 같은 사실을 묶는 ID",
 "version": "해당 대상의 증가 revision. DB/API 버전과 별개",
 "statement": "검증한 사실 또는 전승의 명제",
 "kind": "모델별 구분 enum. SQL CHECK와 DATA-07 참조",
 "story_id": "대본 버전과 무관한 이야기 ID. 카드 유일성 기준",
 "topic_id": "주제 분류 ID",
 "core_question": "이야기의 핵심 질문",
 "interest_tags_json": "관심사 태그 문자열 배열",
 "story_version_id": "청취 완료를 합산할 대본 버전 ID",
 "created_at": "해당 레코드를 최초 커밋한 UTC 시각",
 "valid_until": "변동 정보의 유효 기한 UTC. null은 영구 최신 보장이 아님",
 "estimated_duration_ms": "예상 음성 길이, ms. 실제 재생 시간과 별개",
 "paragraph_id": "고정된 대본 버전의 문단 ID",
 "ordinal": "0부터 시작하는 버전 내 문단 순서",
 "required": "이야기 완료에 필수이면 1, 선택 문단이면 0",
 "text": "검토된 일반 대본. 개인 대화 원문 저장 금지",
 "audio_ref": "권리가 확인되고 실제 존재하는 준비 음성 참조",
 "turn_id": "접수된 말하기 한 번의 영구 ID",
 "utterance_finalized_at": "최종 인식 텍스트 확정·커밋 시각",
 "turn_version": "요약 입력 snapshot의 버전",
 "lifecycle": "open/closed/cancelled/interrupted/failed",
 "frozen": "입력·재생 결과 확정 후 1. 동결 뒤 새 발화는 새 turn",
 "summary_state": "no_text/pending/running/retry_wait/saved/blocked",
 "attempt_count": "해당 생성·요약의 누적 시도 수",
 "retry_batch_count": "현재 자동 요약 재시도 묶음의 횟수",
 "last_attempt_at": "마지막 요약 시도 UTC 시각",
 "next_retry_at": "재시도 예정 UTC 시각. OS 실행 보장 아님",
 "summary_saved_at": "요약 트랜잭션의 커밋 시각",
 "last_error_code": "최근 정규화 오류 코드. 원문/비밀값 없음",
 "payload_version": "암호화 본문이 표현하는 turnVersion",
 "ciphertext": "AES-GCM 암호문과 인증 태그. 평문 복제 없음",
 "nonce": "암호화마다 새로운 12-byte nonce",
 "key_version": "Keystore 키 버전 참조",
 "payload_hash": "평문 동일성을 검사하는 비밀키 기반 HMAC",
 "updated_at": "이 가변 레코드를 마지막 커밋한 UTC 시각",
 "summary_id": "영구 요약 ID",
 "summary_text": "요약된 대화 내용. 원문 전문 아님",
 "provenance_json": "DATA-09 주장·검증 사실·완료/중단 응답 구분",
 "summary_schema_version": "요약 결과 구조 버전",
 "model_id": "실제 생성에 사용한 모델 ID",
 "prompt_version": "생성 또는 요약 프롬프트 버전",
 "attempt_id": "문단/음악 재생 시도 ID",
 "transient_paragraph_key": "암호화 임시 본문 안의 문단 키. 본문 삭제 후 메타데이터만 유지",
 "asset_id": "AssetManifest의 음악·고정 안내·준비 음성 ID",
 "state": "prepared/playing/completed/interrupted/failed",
 "completed_at": "정상 완료를 확인한 UTC 시각. 중단에는 null",
 "interrupted_at": "중단을 관찰한 UTC 시각",
 "ended_elapsed_ms": "종료 단조 시각, ms. started와 같은 scope일 때만 duration 계산",
 "started_elapsed_ms": "출력 시작 단조 시각, ms",
 "position_ms": "음악 플레이어 위치, ms. TTS 정확한 seek 용도로 사용하지 않음",
 "interruption_reason": "user_stop/focus_lost/suspend/error 등의 중단 이유",
 "mode": "checkpoint 종류: story/music/idle",
 "last_completed_paragraph_id": "복귀 이야기와 같은 버전에서 마지막 완료한 문단",
 "music_position_ms": "재개할 음악 위치, ms",
 "play_intent": "청취 지속 의도 0/1. 복구 시 자동 재생 권한은 아님",
 "blockers_json": "출력을 막는 이유 문자열 집합",
 "collection_id": "획득 카드 ID",
 "collection_type": "region/story",
 "rule_version": "획득 판정 규칙 버전. 카드 중복 키에는 포함하지 않음",
 "earned_at": "획득 조건이 충족된 사건의 UTC 시각",
 "proof_key": "문단 또는 지역 판정 샘플을 구별하는 근거 키",
 "scope_id": "P0는 vehicle 하나. 사용자 신원/서버 인증 수단 아님",
 "keywords_json": "사용자가 선택한 키워드 배열, DATA-12",
 "voice_json": "locale/voiceId/rate, DATA-12",
 "story_frequency_json": "mode/betweenStoriesMusicTracks, DATA-12",
 "location_enabled": "명시적 위치 사용 설정 0/1",
 "request_id": "한 논리 생성 요청 ID. 같은 입력 재시도는 ID 유지",
 "task": "story/answer/resume/summary",
 "input_hash": "정규화 요청 본문의 기기 비밀키 기반 HMAC",
 "schema_version": "API 구조 버전",
 "finished_at": "요청 terminal 처리 UTC 시각",
 "error_code": "정규화한 API 오류 코드",
 "usage_json": "inputTokens/outputTokens/latencyMs 등 사용량 메타데이터"
}
MODELS = {
 "trip":"이동 단위. 영구 보관; Coordinator가 상태 변경.",
 "session":"Coordinator 실행 세대. 이전 응답 재사용 방지용 영구 메타데이터.",
 "recording_segment":"연속 위치 수신 구간. 수신 공백을 실제 주행으로 연결하지 않음.",
 "route_point":"위치 수신 이력. append-only; 어댑터가 원본·정규화 값을 분리.",
 "source_evidence":"출처의 고정 revision. 콘텐츠 설치기가 검토 후 등록.",
 "region":"동일 지역의 안정된 ID. 카드 중복 기준.",
 "region_revision":"지역 코드·원천·판정 정책의 고정 revision. 과거 통과 근거 보존.",
 "place":"장소와 좌표 근거. 좌표 변경은 검토하고 관련 콘텐츠 revision 보존.",
 "place_external_id":"제공자 ID를 내부 장소로 연결. 이름만으로 자동 동일시하지 않음.",
 "fact_revision":"사실 내용의 고정 버전. 안정된 factId로 반복 방지.",
 "fact_source":"사실 revision과 출처 revision의 다대다 연결.",
 "story":"이야기의 안정된 정체성. 카드 지급 단위.",
 "story_version":"발행된 대본 버전. 내용 수정은 새 버전 추가.",
 "paragraph":"버전에 귀속된 일반 대본 문단. 사용자 개인 답변을 저장하지 않음.",
 "paragraph_fact":"문단별 사실·전승의 근거 연결.",
 "conversation_turn":"원문 정리 뒤에도 유지되는 대화 상태·식별자.",
 "pending_conversation":"요약 전 암호화 payload. REC-04 성공 시에만 정리.",
 "conversation_summary":"성공적으로 커밋된 영구 요약. 작업 실패 상태는 Turn에서 관리.",
 "playback_attempt":"재생 시도 이력. terminal 상태 이후 결과 변경 금지.",
 "playback_checkpoint":"이동별 최근 복귀 포인터. 전체 청취 이력의 대체가 아님.",
 "collection_record":"지역·이야기 카드. 대상당 1개.",
 "collection_proof":"카드의 실제 위치 또는 문단 완료 근거.",
 "preference":"차량 공용 명시적 설정. 과거 요약을 설정으로 자동 승격하지 않음.",
 "generation_request":"호출·취소·사용량 메타데이터. 원문 프롬프트·응답은 저장하지 않음."
}
db = sqlite3.connect(":memory:")
db.executescript((SPEC/"petasos-v0.8.sql").read_text())
parts = []
tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY rowid")]
for number, table in enumerate(tables, 1):
    parts += [f"### 8.{number}. {table}", "", MODELS[table], "",
              "| 필드 | 타입 · 필수 | 기본값 | 의미·단위 |",
              "| --- | --- | --- | --- |"]
    for _, name, dtype, required, default, pk in db.execute(f"PRAGMA table_info({table})"):
        assert name in DESCRIPTIONS, (table, name)
        parts.append(f"| {name} | {dtype} · {'필수' if required or pk else '선택(null)'}"
                     f" | {default if default is not None else '없음'} | {DESCRIPTIONS[name]} |")
    parts += [""]
doc = ROOT / "docs/Petasos_데이터_API_계약.md"
prefix = doc.read_text().split("<!-- FIELD_DICTIONARY -->")[0]
doc.write_text(prefix + "<!-- FIELD_DICTIONARY -->\n\n" + "\n".join(parts) + "\n")
print(json.dumps({"tables":len(tables), "dictionary_fields":
    sum(len(list(db.execute(f"PRAGMA table_info({t})"))) for t in tables),
    "schema_written":"spec/generation-v0.8.schema.json"}, ensure_ascii=False))
