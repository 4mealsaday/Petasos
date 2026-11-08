"""Check reference SQL, JSON contract fixtures, and documentation consistency.

The JSON checker implements only the keywords used by this repository's schema.
It is not a general JSON Schema implementation or an application runtime test.
"""
from pathlib import Path
import copy
import datetime
import json
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "spec"
REPORT = []

def check(name, action):
    action()
    REPORT.append({"name": name, "status": "pass", "scope": "reference_contract"})

def expect_failure(action):
    try:
        action()
    except (sqlite3.IntegrityError, ValueError):
        return
    raise AssertionError("Expected rejection")

def database():
    db = sqlite3.connect(":memory:")
    db.executescript((SPEC/"petasos-v0.8.sql").read_text())
    insert(db, "trip", trip_id="t1", started_at=1000, status="active")
    insert(db, "trip", trip_id="t2", started_at=1000, ended_at=2000,
           status="closed", end_reason="user_end")
    insert(db, "session", session_id="x1", trip_id="t1", started_at=1000, status="active")
    insert(db, "session", session_id="x2", trip_id="t1", started_at=500, status="closed", closed_at=900)
    insert(db, "recording_segment", segment_id="g1", trip_id="t1", session_id="x1",
           started_at=1000, status="active")
    insert(db, "story", story_id="s1", topic_id="history", title="가상 이야기", core_question="가상의 질문")
    insert(db, "story", story_id="s2", topic_id="history", title="다른 이야기", core_question="다른 질문")
    for version in (1, 2):
        insert(db, "story_version", story_version_id=f"v{version}", story_id="s1",
               version=version, content_hash=f"hash{version}", created_at=1000,
               verified_at=1000, estimated_duration_ms=20000, status="published")
        for number in (1, 2):
            insert(db, "paragraph", paragraph_id=f"v{version}p{number}",
                   story_version_id=f"v{version}", ordinal=number-1,
                   text="가상의 검수 문단", estimated_duration_ms=10000)
    db.commit()
    return db

def insert(db, table, **values):
    db.execute(f"INSERT INTO {table} ({','.join(values)}) VALUES ({','.join('?' for _ in values)})",
               list(values.values()))

def attempt(db, id, paragraph, session="x1", state="completed"):
    insert(db, "playback_attempt", attempt_id=id, session_id=session,
           kind="story", paragraph_id=paragraph, state=state,
           started_at=1000, completed_at=2000 if state=="completed" else None,
           interrupted_at=1500 if state=="interrupted" else None,
           clock_scope_id=session)

def card(db, id="c1", version="v1", story="s1"):
    insert(db, "collection_record", collection_id=id, collection_type="story",
           story_id=story, story_version_id=version, rule_version="0.8", earned_at=2000, created_at=2001)

def turn(db):
    insert(db, "conversation_turn", turn_id="u1", session_id="x1", created_at=1000,
           utterance_finalized_at=1100, closed_at=2000, turn_version=2,
           lifecycle="closed", frozen=1, summary_state="pending")
    insert(db, "pending_conversation", turn_id="u1", payload_version=2,
           ciphertext=b"synthetic-ciphertext-not-real-encryption",
           nonce=b"123456789012", key_version="test-key", payload_hash="hmac-fixture",
           created_at=1100, updated_at=2000)
    db.commit()

def summarize(db, version=2, fail_after_insert=False):
    with db:
        eligible=db.execute("""SELECT 1 FROM conversation_turn t JOIN pending_conversation p USING(turn_id)
            WHERE t.turn_id='u1' AND t.frozen=1 AND t.turn_version=? AND p.payload_version=?
              AND t.summary_state<>'saved'""",(version,version)).fetchone()
        if not eligible:
            return False
        insert(db, "conversation_summary", summary_id="sum1", turn_id="u1", turn_version=version,
               summary_text="가상 요약", provenance_json="{}", summary_schema_version="0.8",
               model_id="fixture", prompt_version="0.8", created_at=3000)
        if fail_after_insert:
            raise ValueError("injected transaction failure")
        db.execute("UPDATE conversation_turn SET summary_state='saved',summary_saved_at=3000 WHERE turn_id='u1'")
        db.execute("DELETE FROM pending_conversation WHERE turn_id='u1' AND payload_version=?",(version,))
        return True

def sql_foreign_key():
    db=database()
    expect_failure(lambda: attempt(db,"a1","missing"))
    expect_failure(lambda: insert(db,"recording_segment",segment_id="bad",trip_id="t2",
                                session_id="x1",started_at=1000,status="closed",ended_at=2000,end_reason="user_end"))
    assert not db.execute("PRAGMA foreign_key_check").fetchall()

def sql_terminal():
    db=database(); attempt(db,"a1","v1p1",state="interrupted")
    expect_failure(lambda: db.execute("UPDATE playback_attempt SET state='completed',completed_at=2000,interrupted_at=NULL WHERE attempt_id='a1'"))

def sql_cross_version():
    db=database(); attempt(db,"a1","v1p1"); attempt(db,"a2","v2p2")
    expect_failure(lambda: card(db,version="v1"))
    expect_failure(lambda: card(db,version="v2"))
    attempt(db,"a3","v2p1"); card(db,version="v2")
    assert db.execute("SELECT COUNT(*) FROM collection_record").fetchone()[0]==1

def sql_cross_session():
    db=database(); attempt(db,"a1","v1p1",session="x2"); attempt(db,"a2","v1p2")
    card(db)
    expect_failure(lambda: card(db,id="c2"))
    attempt(db,"a3","v2p1"); attempt(db,"a4","v2p2")
    expect_failure(lambda: card(db,id="c3",version="v2"))

def sql_collection_proof():
    db=database(); attempt(db,"a1","v1p1"); attempt(db,"a2","v1p2"); attempt(db,"a3","v2p1")
    card(db)
    insert(db,"collection_proof",collection_id="c1",proof_key="v1p1",attempt_id="a1")
    expect_failure(lambda: insert(db,"collection_proof",collection_id="c1",proof_key="v2p1",attempt_id="a3"))

def sql_stale_summary():
    db=database(); turn(db)
    assert summarize(db,version=1) is False
    assert db.execute("SELECT COUNT(*) FROM pending_conversation").fetchone()[0]==1
    assert summarize(db) is True
    assert summarize(db) is False
    assert db.execute("SELECT COUNT(*) FROM conversation_turn").fetchone()[0]==1
    assert db.execute("SELECT COUNT(*) FROM conversation_summary").fetchone()[0]==1
    assert db.execute("SELECT COUNT(*) FROM pending_conversation").fetchone()[0]==0
    expect_failure(lambda: db.execute("UPDATE conversation_turn SET turn_version=3 WHERE turn_id='u1'"))

def sql_summary_rollback():
    db=database(); turn(db)
    expect_failure(lambda: summarize(db,fail_after_insert=True))
    assert db.execute("SELECT COUNT(*) FROM conversation_summary").fetchone()[0]==0
    assert db.execute("SELECT COUNT(*) FROM pending_conversation").fetchone()[0]==1
    assert db.execute("SELECT summary_state FROM conversation_turn").fetchone()[0]=="pending"

def sql_checkpoint_time():
    db=database()
    insert(db,"playback_checkpoint",trip_id="t1",session_id="x1",state_revision=5,mode="story",
           paragraph_id="v1p1",play_intent=1,blockers_json="[]",updated_at=9000)
    db.execute("UPDATE playback_checkpoint SET state_revision=6,updated_at=1000")
    expect_failure(lambda: db.execute("UPDATE playback_checkpoint SET state_revision=4"))
    assert db.execute("SELECT state_revision,updated_at FROM playback_checkpoint").fetchone()==(6,1000)

def sql_trip_interruption():
    db=database()
    db.execute("UPDATE trip SET status='interrupted',interruption_detected_at=9000 WHERE trip_id='t1'")
    assert db.execute("SELECT ended_at FROM trip WHERE trip_id='t1'").fetchone()[0] is None
    expect_failure(lambda: db.execute("UPDATE trip SET status='closed' WHERE trip_id='t1'"))

def sql_location():
    db=database()
    values=dict(segment_id="g1",provider="fixture",source_time_unit="unknown",
                received_at=1000,received_elapsed_ms=20,clock_scope_id="boot-fixture",
                latitude=37.0,longitude=127.0,raw_coordinates_json='{"lat":37,"lng":127}',
                quality_status="unknown",route_revision=0)
    insert(db,"route_point",point_id="p1",sequence=1,**values)
    insert(db,"route_point",point_id="p2",sequence=2,**values)
    insert(db,"route_point",point_id="p3",sequence=3,provider_epoch="ep1",source_event_id="event1",**values)
    expect_failure(lambda: insert(db,"route_point",point_id="p4",sequence=4,
                                   provider_epoch="ep1",source_event_id="event1",**values))
    assert db.execute("SELECT COUNT(*) FROM route_point").fetchone()[0]==3

SCHEMA=json.loads((SPEC/"generation-v0.8.schema.json").read_text())
SUPPORTED={"$schema","$id","title","$defs","$ref","type","properties","required","additionalProperties",
           "oneOf","const","enum","items","minItems","maxItems","uniqueItems","minimum","minLength","maxLength","pattern"}
def validate(value, schema):
    if "$ref" in schema:
        target=SCHEMA
        for part in schema["$ref"][2:].split("/"): target=target[part]
        return validate(value,target)
    if "oneOf" in schema:
        passed=0
        for alternative in schema["oneOf"]:
            try: validate(value,alternative); passed+=1
            except ValueError: pass
        if passed!=1: raise ValueError("oneOf")
    if "const" in schema and value!=schema["const"]: raise ValueError("const")
    if "enum" in schema and value not in schema["enum"]: raise ValueError("enum")
    dtype=schema.get("type")
    types={"null":lambda v:v is None,"string":lambda v:isinstance(v,str),
           "integer":lambda v:isinstance(v,int) and not isinstance(v,bool),
           "boolean":lambda v:isinstance(v,bool),"object":lambda v:isinstance(v,dict),
           "array":lambda v:isinstance(v,list)}
    if dtype and not types[dtype](value): raise ValueError("type")
    if dtype=="object":
        if not set(schema.get("required",[]))<=set(value): raise ValueError("required")
        if schema.get("additionalProperties") is False and not set(value)<=set(schema["properties"]): raise ValueError("extra")
        for key,item in value.items():
            if key in schema["properties"]: validate(item,schema["properties"][key])
    if dtype=="array":
        if not schema.get("minItems",0)<=len(value)<=schema.get("maxItems",10**9): raise ValueError("array size")
        if schema.get("uniqueItems") and len({json.dumps(x,sort_keys=True) for x in value})!=len(value): raise ValueError("duplicate")
        for item in value: validate(item,schema["items"])
    if dtype=="string":
        if not schema.get("minLength",0)<=len(value)<=schema.get("maxLength",10**9): raise ValueError("string size")
        if "pattern" in schema and not re.search(schema["pattern"],value): raise ValueError("pattern")
    if dtype=="integer" and value<schema.get("minimum",-10**20): raise ValueError("minimum")

def schema_keywords():
    def walk(s):
        assert set(s)<=SUPPORTED, set(s)-SUPPORTED
        for group in ("properties","$defs"):
            for value in s.get(group,{}).values(): walk(value)
        for alt in s.get("oneOf",[]): walk(alt)
        if "items" in s: walk(s["items"])
    walk(SCHEMA)

def json_contract():
    base={"schemaVersion":"0.8","requestId":"req-1","sessionId":"x1","cancelEpoch":1,
          "routeDependency":None,"turnId":"u1","turnVersion":2,"task":"answer",
          "inputHash":"a"*64,"payload":{"userText":"가상의 개인 경험입니다.",
          "contextSummary":"","heardParagraphIds":[],"sourceRevisionIds":[]}}
    validate(base,SCHEMA["$defs"]["Request"])
    bad=copy.deepcopy(base); bad["turnVersion"]=None
    expect_failure(lambda: validate(bad,SCHEMA["$defs"]["Request"]))
    bad=copy.deepcopy(base); bad["executeShell"]="not-allowed"
    expect_failure(lambda: validate(bad,SCHEMA["$defs"]["Request"]))
    bad=copy.deepcopy(base); bad["cancelEpoch"]=True
    expect_failure(lambda: validate(bad,SCHEMA["$defs"]["Request"]))
    bad=copy.deepcopy(base); bad["payload"]["userText"]="x"*4001
    expect_failure(lambda: validate(bad,SCHEMA["$defs"]["Request"]))
    response={k:v for k,v in base.items() if k not in ("inputHash","payload")}
    response.update(status="ok",result={"paragraphs":[{"paragraphKey":"r1","text":"그런 경험이 있었군요.",
       "kind":"personal","sourceRevisionIds":[],"factRevisionIds":[]}],
       "action":"continue","targetContentId":None},metadata={"modelId":"fixture","provider":"fixture",
       "promptVersion":"0.8","inputTokens":1,"outputTokens":1})
    validate(response,SCHEMA["$defs"]["SuccessResponse"])
    response["result"]["action"]="delete_records"
    expect_failure(lambda: validate(response,SCHEMA["$defs"]["SuccessResponse"]))
    payloads={
      "story":{"storyId":"s1","topicId":"history","regionId":None,"sourceRevisionIds":["source-1"],
               "excludedFactIds":[],"interestTags":[]},
      "resume":{"contextSummary":"","candidates":[]},
      "summary":{"userText":"가상의 경험","completedResponses":[],"partialUnknownKeys":[]}
    }
    for task,payload in payloads.items():
        request=copy.deepcopy(base); request["task"]=task; request["payload"]=payload
        if task!="summary": request["turnId"]=request["turnVersion"]=None
        validate(request,SCHEMA["$defs"]["Request"])
    (SPEC/"request-example.json").write_text(json.dumps(base,ensure_ascii=False,indent=2)+"\n")

def docs():
    files=list((ROOT/"docs").glob("Petasos_*.md"))
    assert len(files)==5
    text="\n".join(p.read_text() for p in files)
    for path in files:
        assert "버전 0.8" in path.read_text()
        for link in re.findall(r"\]\(([^)]+)\)",path.read_text()):
            if link.startswith(("./","../")):
                assert (path.parent/link.split("#")[0]).exists(),(path,link)
    for n in range(1,13): assert f"PR-{n:02}" in text
    test=(ROOT/"docs/Petasos_검수명세.md").read_text()
    assert all(f"A{n:02}" in test for n in range(1,16))
    assert all(f"R{n:02}" in test for n in range(1,7))
    assert all(f"D{n:02}" in test for n in range(1,10))
    assert all(f"N{n:02}" in test for n in range(1,6))
    assert "사용자는 Pleos 가입·프로젝트 생성 전" not in text
    assert "선택(null)" in text

for name,fn in [
 ("foreign_keys_and_trip_session_match",sql_foreign_key),
 ("terminal_callback_cannot_change_attempt",sql_terminal),
 ("story_versions_not_mixed",sql_cross_version),
 ("cross_session_completion_and_single_card",sql_cross_session),
 ("collection_proof_matches_version",sql_collection_proof),
 ("stale_and_duplicate_summary_keep_current_data",sql_stale_summary),
 ("summary_transaction_rollback",sql_summary_rollback),
 ("wall_clock_rollback_not_state_rollback",sql_checkpoint_time),
 ("interruption_detection_not_end_time",sql_trip_interruption),
 ("same_position_distinct_observations",sql_location),
 ("schema_keyword_coverage",schema_keywords),
 ("transport_contract_fixtures",json_contract),
 ("documentation_links_and_traceability",docs)
]:
    check(name,fn)
checked_at = datetime.datetime.now(datetime.timezone.utc)
output={"date":checked_at.date().isoformat(),"checked_at_utc":checked_at.isoformat(),"spec_version":"0.8","checks":REPORT,
        "reference_checks_passed":len(REPORT),"app_runtime_tests_executed":0,
        "pleos_runtime_tests_executed":0,
        "json_validation_scope":"Only the schema keyword subset used in this repository; not a general validator.",
        "remaining":"This reference checker does not execute SDK, encryption, authentication, app transactions, migration or UI/audio tests; see docs/development for separate implementation evidence."}
(SPEC/"validation-report.json").write_text(json.dumps(output,ensure_ascii=False,indent=2)+"\n")
print(json.dumps(output,ensure_ascii=False,indent=2))
