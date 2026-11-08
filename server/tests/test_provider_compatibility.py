"""No network calls: exercise provider projection and unchanged service validation."""
import copy
import json
import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

import httpx
from jsonschema import Draft202012Validator

from petasos_server.contracts import Failure, SCHEMA, model_schema
from petasos_server.evidence import EvidenceContext, EvidenceRegistry
from petasos_server.provider import PROMPT_VERSION, SYSTEM_PROMPT, OpenRouterConfig, OpenRouterProvider
from petasos_server.provider_schema import DEFERRED_CONSTRAINTS, provider_schema
from petasos_server.service import GenerationService
from tests.fixtures import request


MODEL = "anthropic/claude-sonnet-4.6"
CANONICAL_MODEL = "anthropic/claude-4.6-sonnet-20260217"


def configuration(**changes):
    value = OpenRouterConfig("synthetic-key", MODEL, "amazon-bedrock/global", ("Amazon Bedrock",),
                             time.time() + 60, True)
    return replace(value, **changes)


def answer():
    return {"paragraphs": [{"text": "공개 시험 답변입니다.", "kind": "personal",
                            "sourceRevisionIds": [], "factRevisionIds": []}],
            "action": "continue", "targetContentId": None}


def completion(result, model=MODEL):
    return httpx.Response(200, json={"model": model, "provider": "Amazon Bedrock", "choices": [
        {"finish_reason": "stop", "message": {"content": json.dumps(result, ensure_ascii=False)}}]})


class ProviderSchemaTests(unittest.TestCase):
    def test_public_story_model_graph_limits_preserve_canonical_contract(self):
        original = model_schema("story")
        projected = provider_schema(MODEL, original)
        wire = next(s for s in SCHEMA["$defs"]["SuccessResponse"]["oneOf"]
                    if s["properties"]["task"]["const"] == "story")["properties"]["result"]
        wire_paragraph = wire["properties"]["paragraphs"]["items"]["properties"]
        self.assertEqual(wire_paragraph["kind"]["enum"], ["fact", "tradition", "personal", "transition"])
        for key in ("sourceRevisionIds", "factRevisionIds"):
            self.assertEqual(wire_paragraph[key]["minItems"], 0)
            self.assertEqual(original["properties"]["paragraphs"]["items"]["properties"][key]["minItems"], 1)
            self.assertEqual(projected["properties"]["paragraphs"]["items"]["properties"][key]["minItems"], 1)
        for kind in ("fact", "tradition", "personal", "transition"):
            for empty in (None, "sourceRevisionIds", "factRevisionIds"):
                paragraph = {"text": "공개 합성 문단입니다.", "kind": kind,
                             "sourceRevisionIds": ["source-r1"], "factRevisionIds": ["fact-r1"]}
                if empty:
                    paragraph[empty] = []
                result = {"paragraphs": [paragraph], "estimatedDurationMs": 1000}
                expected = kind in ("fact", "tradition") and empty is None
                self.assertEqual(Draft202012Validator(original).is_valid(result), expected)
                self.assertEqual(Draft202012Validator(projected).is_valid(result), expected)

    def test_resume_model_null_transition_preserves_canonical_nullable_text(self):
        original = model_schema("resume")
        projected = provider_schema(MODEL, original)
        wire = next(s for s in SCHEMA["$defs"]["SuccessResponse"]["oneOf"]
                    if s["properties"]["task"]["const"] == "resume")["properties"]["result"]
        for transition in (None, "합성 전환 문장입니다."):
            result = {"action": "wait", "targetContentId": None, "paragraphId": None,
                      "transitionText": transition, "reason": "합성 대기입니다."}
            self.assertTrue(Draft202012Validator(wire).is_valid(result))
            self.assertEqual(Draft202012Validator(original).is_valid(result), transition is None)
            self.assertEqual(Draft202012Validator(projected).is_valid(result), transition is None)

    def test_answer_model_actions_match_current_host_without_changing_wire_enum(self):
        original = model_schema("answer")
        projected = provider_schema(MODEL, original)
        wire = next(s for s in SCHEMA["$defs"]["SuccessResponse"]["oneOf"]
                    if s["properties"]["task"]["const"] == "answer")["properties"]["result"]
        self.assertEqual(wire["properties"]["action"]["enum"], ["continue", "next", "music", "stop", "replay", "unsupported"])
        for action in wire["properties"]["action"]["enum"]:
            result = answer() | {"action": action}
            expected = action in ("continue", "unsupported")
            self.assertEqual(Draft202012Validator(original).is_valid(result), expected)
            self.assertEqual(Draft202012Validator(projected).is_valid(result), expected)

    def test_all_task_projections_leave_canonical_schema_unchanged(self):
        canonical_before = copy.deepcopy(SCHEMA)
        for task in ("story", "answer", "summary", "resume"):
            original = model_schema(task)
            before = copy.deepcopy(original)
            projected = provider_schema(MODEL, original)
            self.assertEqual(original, before)
            self.assertEqual(SCHEMA, canonical_before)
            Draft202012Validator.check_schema(projected)

            def check(node):
                if isinstance(node, dict):
                    self.assertFalse(DEFERRED_CONSTRAINTS & node.keys())
                    self.assertNotIn("oneOf", node)
                    if "minItems" in node:
                        self.assertIn(node["minItems"], (0, 1))
                    for value in node.values():
                        check(value)
                elif isinstance(node, list):
                    for value in node:
                        check(value)
            check(projected)

        text = provider_schema(MODEL, model_schema("answer"))["properties"]["paragraphs"]["items"]["properties"]["text"]
        self.assertIn('"maxLength": 240', text["description"])
        self.assertIn('"minLength": 1', text["description"])

    def test_nullable_anyof_preserves_disjoint_string_and_null_semantics(self):
        original = model_schema("answer")["properties"]["targetContentId"]
        projected = provider_schema(MODEL, original)
        self.assertIn("anyOf", projected)
        self.assertEqual(projected["anyOf"][0]["pattern"], original["oneOf"][0]["pattern"])
        for value, allowed in ((None, True), ("content-1", True), (2, False), ([], False), ({}, False)):
            self.assertEqual(Draft202012Validator(original).is_valid(value), allowed)
            self.assertEqual(Draft202012Validator(projected).is_valid(value), allowed)

    def test_overlapping_oneof_is_not_relaxed(self):
        with self.assertRaisesRegex(Failure, "UPSTREAM_UNAVAILABLE"):
            provider_schema(MODEL, {"oneOf": [{"type": "string"}, {"type": "string", "pattern": "^a$"}]})

    def test_projection_does_not_assume_future_model_compatibility(self):
        original = model_schema("summary")
        for model in ("other/model", "anthropic/claude-sonnet-future"):
            result = provider_schema(model, original)
            self.assertEqual(result, original)
            self.assertIsNot(result, original)
        self.assertEqual(provider_schema(MODEL, original), provider_schema(CANONICAL_MODEL, original))

    def test_config_rejects_malformed_alias_allowlists(self):
        for aliases in ([], "model", ("",), ("model", "model"), (" model",), ("with\nnewline",),
                        (None,), ("a" * 257,), tuple(str(index) for index in range(17))):
            with self.subTest(aliases=aliases), self.assertRaisesRegex(Failure, "UPSTREAM_UNAVAILABLE"):
                configuration(accepted_model_ids=aliases).validate()
        configuration().validate()
        configuration(accepted_model_ids=(CANONICAL_MODEL,)).validate()

    def test_server_config_reads_only_explicit_alias_list(self):
        from petasos_server.__main__ import build_app

        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            fingerprint = directory / "fingerprint.key"
            fingerprint.write_bytes(b"k" * 32)
            tokens = directory / "tokens.json"
            tokens.write_text("[]")
            path = directory / "config.json"
            config = {"fingerprintKeyFile": str(fingerprint), "tokensFile": str(tokens),
                      "provider": {"type": "openrouter", "model": MODEL, "providerSlug": "amazon-bedrock/global",
                                   "acceptedProviderNames": ["Amazon Bedrock"], "privacyReviewedUntil": 9999999999,
                                   "accountContentLoggingDisabled": True, "acceptedModelIds": [CANONICAL_MODEL]}}
            for aliases, expected in (([CANONICAL_MODEL], (CANONICAL_MODEL,)), (None, ())):
                if aliases is None:
                    config["provider"].pop("acceptedModelIds")
                else:
                    config["provider"]["acceptedModelIds"] = aliases
                path.write_text(json.dumps(config))
                with patch("petasos_server.__main__.OpenRouterProvider") as provider:
                    build_app(path, environment={"PETASOS_OPENROUTER_API_KEY": "synthetic-key"})
                    self.assertEqual(provider.call_args.args[0].accepted_model_ids, expected)
            config["provider"]["acceptedModelIds"] = CANONICAL_MODEL
            path.write_text(json.dumps(config))
            with self.assertRaisesRegex(ValueError, "INVALID_PROVIDER_CONFIGURATION"):
                build_app(path, environment={})


class ProviderCompatibilityTests(unittest.IsolatedAsyncioTestCase):
    async def test_story_and_resume_http_projection_and_prompt_match_supported_runtime(self):
        captured = []
        def transport(req):
            captured.append(json.loads(req.content))
            return completion({})
        provider = OpenRouterProvider(configuration(), httpx.MockTransport(transport))
        try:
            for task in ("story", "resume"):
                await provider.generate(task, {}, EvidenceContext(), model_schema(task))
            self.assertEqual(PROMPT_VERSION, "petasos-v0.8-3")
            for task, sent in zip(("story", "resume"), captured):
                self.assertEqual(sent["messages"][0], {"role": "system", "content": SYSTEM_PROMPT})
                self.assertIn("public fact/tradition paragraphs only", sent["messages"][0]["content"])
                self.assertIn("stable factId at most once", sent["messages"][0]["content"])
                self.assertIn("sourceRevisionIds must exactly equal the union", sent["messages"][0]["content"])
                self.assertIn("transitionText must be null", sent["messages"][0]["content"])
                self.assertEqual(sent["response_format"]["json_schema"]["schema"],
                                 provider_schema(MODEL, model_schema(task)))
                self.assertEqual(sent["model"], MODEL)
                self.assertEqual(sent["provider"]["only"], ["amazon-bedrock/global"])
                self.assertFalse(sent["provider"]["allow_fallbacks"])
        finally:
            await provider.close()

    async def test_http_adapter_uses_projection_without_mutating_input(self):
        captured = []
        def transport(req):
            captured.append(json.loads(req.content))
            return completion(answer())
        original = model_schema("answer")
        before = copy.deepcopy(original)
        provider = OpenRouterProvider(configuration(), httpx.MockTransport(transport))
        try:
            await provider.generate("answer", {}, EvidenceContext(), original)
            self.assertEqual(original, before)
            self.assertEqual(captured[0]["response_format"]["json_schema"]["schema"], provider_schema(MODEL, original))
            self.assertTrue(captured[0]["response_format"]["json_schema"]["strict"])
            self.assertFalse(captured[0]["provider"]["allow_fallbacks"])
            self.assertEqual(captured[0]["response_format"]["json_schema"]["schema"]["properties"]["action"]["enum"],
                             ["continue", "unsupported"])
        finally:
            await provider.close()

    async def test_response_model_must_equal_request_or_explicit_alias(self):
        cases = ((MODEL, (), True), (CANONICAL_MODEL, (), False),
                 (CANONICAL_MODEL, (CANONICAL_MODEL,), True),
                 ("anthropic/claude-4.6-sonnet-20260218", (CANONICAL_MODEL,), False),
                 ("other/model", (CANONICAL_MODEL,), False))
        for returned, aliases, allowed in cases:
            provider = OpenRouterProvider(configuration(accepted_model_ids=aliases),
                httpx.MockTransport(lambda req: completion(answer(), returned)))
            try:
                if allowed:
                    result = await provider.generate("answer", {}, EvidenceContext(), model_schema("answer"))
                    self.assertEqual(result.model_id, returned)
                else:
                    with self.assertRaisesRegex(Failure, "INVALID_MODEL_OUTPUT"):
                        await provider.generate("answer", {}, EvidenceContext(), model_schema("answer"))
            finally:
                await provider.close()

    async def test_original_service_validation_rejects_projected_oversize_output(self):
        invalid = answer()
        invalid["paragraphs"][0]["text"] = "가" * 241
        original = model_schema("answer")
        self.assertTrue(Draft202012Validator(provider_schema(MODEL, original)).is_valid(invalid))
        self.assertFalse(Draft202012Validator(original).is_valid(invalid))
        provider = OpenRouterProvider(configuration(), httpx.MockTransport(lambda req: completion(invalid)))
        service = GenerationService(EvidenceRegistry(), provider, b"k" * 32)
        try:
            status, body = await service.generate("test-device", request())
            self.assertEqual((status, body["code"]), (422, "INVALID_MODEL_OUTPUT"))
        finally:
            await service.close()

    async def test_original_service_validation_rejects_duplicate_summary_keys(self):
        invalid = {"summaryText": "공개 시험 요약입니다.", "userClaims": [], "verifiedFactRevisionIds": [],
                   "unresolvedQuestions": [], "completedResponseKeys": [], "partialUnknownKeys": ["partial-1", "partial-1"]}
        original = model_schema("summary")
        self.assertTrue(Draft202012Validator(provider_schema(MODEL, original)).is_valid(invalid))
        self.assertFalse(Draft202012Validator(original).is_valid(invalid))
        req = request("summary")
        req["payload"]["partialUnknownKeys"] = ["partial-1"]
        provider = OpenRouterProvider(configuration(), httpx.MockTransport(lambda _: completion(invalid)))
        service = GenerationService(EvidenceRegistry(), provider, b"k" * 32)
        try:
            status, body = await service.generate("test-device", req)
            self.assertEqual((status, body["code"]), (422, "INVALID_MODEL_OUTPUT"))
        finally:
            await service.close()


if __name__ == "__main__":
    unittest.main()
