"""Strict wire validation; validation errors never contain private input."""
import copy
import json
import re
from pathlib import Path

from jsonschema import Draft202012Validator

SCHEMA = json.loads((Path(__file__).resolve().parents[2] / "spec/generation-v0.8.schema.json").read_text())
ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")
META_KEYS = ("schemaVersion", "requestId", "sessionId", "cancelEpoch", "routeDependency", "turnId", "turnVersion", "task")
VALIDATORS = {name: Draft202012Validator(value) for name, value in SCHEMA["$defs"].items()}
RESULT_SCHEMAS = {x["properties"]["task"]["const"]: x["properties"]["result"] for x in SCHEMA["$defs"]["SuccessResponse"]["oneOf"]}
# API-04 currently carries no client action capabilities or authorized target candidates.
# Match the installed host's authority; keep the complete v0.8 wire enum unchanged.
SUPPORTED_ANSWER_ACTIONS = ("continue", "unsupported")


class Failure(Exception):
    def __init__(self, code, status=400, retryable=False, retry_after_ms=None):
        super().__init__(code)
        self.code, self.status, self.retryable, self.retry_after_ms = code, status, retryable, retry_after_ms

    def body(self, request_id=None):
        return {"schemaVersion": "0.8", "requestId": request_id, "status": "error", "code": self.code,
                "retryable": self.retryable, "retryAfterMs": self.retry_after_ms}


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result
    def constant(_):
        raise ValueError("nonfinite number")
    try:
        return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)
    except (UnicodeError, ValueError, RecursionError):
        raise Failure("INVALID_REQUEST") from None


def validate_request(value):
    if isinstance(value, dict) and value.get("schemaVersion") != "0.8":
        raise Failure("UNSUPPORTED_SCHEMA")
    if not VALIDATORS["Request"].is_valid(value):
        raise Failure("INVALID_REQUEST")
    # Bound transport integers to the Android Long representation, without coercing floats/bools.
    integers = [value["cancelEpoch"], value["turnVersion"]]
    if value["routeDependency"] is not None:
        integers.append(value["routeDependency"]["routeRevision"])
    if any(x is not None and (type(x) is not int or x > 2**63 - 1) for x in integers):
        raise Failure("INVALID_REQUEST")
    task, payload = value["task"], value["payload"]
    if task in ("answer", "summary") and not payload["userText"].strip():
        raise Failure("INVALID_REQUEST")
    if task == "summary":
        keys = [p["paragraphKey"] for p in payload["completedResponses"]]
        if len(keys) != len(set(keys)) or set(keys) & set(payload["partialUnknownKeys"]):
            raise Failure("INVALID_REQUEST")
    if task == "resume":
        keys = [(c["contentId"], c["paragraphId"], c["kind"]) for c in payload["candidates"]]
        if len(keys) != len(set(keys)):
            raise Failure("INVALID_REQUEST")


def model_schema(task):
    """The model cannot assign transport IDs, paragraph keys, or story versions."""
    schema = copy.deepcopy(RESULT_SCHEMAS[task])
    if task in ("story", "answer"):
        paragraph = schema["properties"]["paragraphs"]["items"]
        del paragraph["properties"]["paragraphKey"]
        paragraph["required"].remove("paragraphKey")
    if task == "answer":
        schema["properties"]["action"] = {"enum": list(SUPPORTED_ANSWER_ACTIONS)}
    if task == "story":
        # Installed public stories require a complete reviewed fact/source graph.
        # This deployment supports no personal or ungrounded transition paragraphs;
        # keep the broader canonical v0.8 contract unchanged.
        paragraph["properties"]["kind"] = {"enum": ["fact", "tradition"]}
        for key in ("sourceRevisionIds", "factRevisionIds"):
            paragraph["properties"][key]["minItems"] = 1
        for key in ("storyId", "storyVersionId"):
            del schema["properties"][key]
            schema["required"].remove(key)
    if task == "resume":
        # The current runtime selects an offered candidate but has no authorized
        # output/commit lifecycle for a separately generated transition.
        schema["properties"]["transitionText"] = {"type": "null"}
    return schema
