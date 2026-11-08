import copy
import hashlib
import json
from pathlib import Path

from petasos_server.auth import DeviceToken
from petasos_server.provider import ProviderResult

TOKEN_A = "a" * 43
TOKEN_B = "b" * 43


def token_row(token=TOKEN_A, owner="test-device", expires=9999999999, revoked=False):
    return DeviceToken(owner, hashlib.sha256(token.encode()).hexdigest(), expires, revoked)


def request(task="answer", request_id="request-1"):
    value = {"schemaVersion": "0.8", "requestId": request_id, "sessionId": "test-session", "cancelEpoch": 1,
             "routeDependency": None, "turnId": "test-turn", "turnVersion": 1, "task": task,
             "inputHash": "a" * 64, "payload": {"userText": "합성 시험 발화입니다.", "contextSummary": "",
                                                "heardParagraphIds": [], "sourceRevisionIds": []}}
    if task == "story":
        value.update(turnId=None, turnVersion=None, payload={"storyId": "test-story", "topicId": "test-topic",
                     "regionId": None, "sourceRevisionIds": ["test-source-r1"], "excludedFactIds": [], "interestTags": []})
    elif task == "summary":
        value["payload"] = {"userText": "합성 시험 발화입니다.", "completedResponses": [], "partialUnknownKeys": []}
    elif task == "resume":
        value.update(turnId=None, turnVersion=None, payload={"contextSummary": "", "candidates": []})
    return value


def evidence_document():
    return {"sources": [{"sourceRevisionId": "test-source-r1", "text": "합성 근거입니다. 실제 여행 정보가 아닙니다.",
                         "allowedDeviceIds": ["test-device"], "reviewed": True}],
            "facts": [{"factRevisionId": "test-fact-r1", "factId": "test-fact", "kind": "fact",
                       "text": "합성 사실 항목입니다.", "sourceRevisionIds": ["test-source-r1"]}]}


class SyntheticProvider:
    def __init__(self, result=None):
        self.calls = 0
        self.result = result

    async def generate(self, task, payload, evidence, schema):
        self.calls += 1
        if self.result is not None:
            result = copy.deepcopy(self.result)
        elif task == "answer":
            result = {"paragraphs": [{"text": "합성 서버 연결 시험 답변입니다.", "kind": "personal",
                                     "sourceRevisionIds": [], "factRevisionIds": []}],
                      "action": "continue", "targetContentId": None}
        elif task == "story":
            result = {"paragraphs": [{"text": "합성 사실 항목입니다.", "kind": "fact",
                                     "sourceRevisionIds": ["test-source-r1"], "factRevisionIds": ["test-fact-r1"]}],
                      "estimatedDurationMs": 2000}
        elif task == "summary":
            result = {"summaryText": "합성 시험 대화 요약입니다.", "userClaims": [payload["userText"]],
                      "verifiedFactRevisionIds": [], "unresolvedQuestions": [],
                      "completedResponseKeys": [p["paragraphKey"] for p in payload["completedResponses"]],
                      "partialUnknownKeys": payload["partialUnknownKeys"]}
        else:
            result = {"action": "wait", "targetContentId": None, "paragraphId": None,
                      "transitionText": None, "reason": "합성 시험 대기입니다."}
        return ProviderResult(result, "synthetic-test-only", "test-fixture")

    async def close(self):
        pass
