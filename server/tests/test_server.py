import asyncio
import copy
import json
import tempfile
import time
import unittest
from pathlib import Path

import httpx

from petasos_server.asgi import GenerationApp, MAX_BODY
from petasos_server.auth import TokenRegistry
from petasos_server.contracts import Failure, VALIDATORS
from petasos_server.evidence import EvidenceRegistry
from petasos_server.provider import OpenRouterConfig, OpenRouterProvider, ProviderResult, UnavailableProvider
from petasos_server.service import GenerationService
from tests.fixtures import TOKEN_A, TOKEN_B, SyntheticProvider, evidence_document, request, token_row


class ServerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.provider = SyntheticProvider()
        self.tokens = TokenRegistry([token_row(), token_row(TOKEN_B, "other-device")])
        self.service = GenerationService(EvidenceRegistry(evidence_document()), self.provider, b"k" * 32)
        self.app = GenerationApp(self.tokens, self.service)
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(self.app), base_url="https://test")

    async def asyncTearDown(self):
        await self.client.aclose()
        await self.service.close()

    async def post(self, value=None, token=TOKEN_A):
        return await self.client.post("/v1/generations", json=value or request(), headers={"Authorization": "Bearer " + token})

    async def cancel(self, request_id="request-1", token=TOKEN_A):
        return await self.client.post("/v1/generations/" + request_id + "/cancel", headers={"Authorization": "Bearer " + token})

    async def test_health_contains_no_provider_or_keys(self):
        r = await self.client.get("/v1/health")
        self.assertEqual(r.json(), {"status": "ok", "schemaVersion": "0.8"})
        self.assertEqual(r.headers["cache-control"], "no-store")

    async def test_plain_http_and_forwarded_header_do_not_enable_tls(self):
        r = await self.client.get("http://test/v1/health", headers={"X-Forwarded-Proto": "https"})
        self.assertEqual(r.status_code, 403)

    async def test_auth_missing_invalid_expired_revoked(self):
        r = await self.client.post("/v1/generations", json=request())
        self.assertEqual(r.status_code, 401)
        for row in (token_row(expires=1), token_row(revoked=True)):
            self.tokens.tokens = (row,)
            self.assertEqual((await self.post()).status_code, 401)
        self.assertEqual(self.provider.calls, 0)

    async def test_external_registry_revocation_applies_without_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tokens.json"
            row = {"deviceId": "test-device", "tokenHash": token_row().token_hash, "expiresAt": 9999999999, "revoked": False}
            path.write_text(json.dumps([row]))
            self.tokens.path = path
            self.assertEqual((await self.post()).status_code, 200)
            row["revoked"] = True
            path.write_text(json.dumps([row]))
            self.assertEqual((await self.post()).status_code, 401)

    async def test_answer_metadata_is_server_bound_and_keys_are_server_owned(self):
        value = request()
        r = await self.post(value)
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertTrue(VALIDATORS["SuccessResponse"].is_valid(body))
        for key in ("requestId", "sessionId", "turnId", "turnVersion", "cancelEpoch", "routeDependency"):
            self.assertEqual(body[key], value[key])
        self.assertTrue(body["result"]["paragraphs"][0]["paragraphKey"].startswith("p-"))
        self.assertIsNone(body["metadata"]["inputTokens"])

    async def test_retries_reuse_identical_result_without_regeneration(self):
        first = await self.post()
        second = await self.post(dict(reversed(list(request().items()))))
        self.assertEqual(first.json(), second.json())
        self.assertEqual(self.provider.calls, 1)

    async def test_same_input_hash_cannot_hide_different_body(self):
        await self.post()
        changed = request()
        changed["payload"]["userText"] = "다른 합성 발화"
        r = await self.post(changed)
        self.assertEqual((r.status_code, r.json()["code"]), (409, "IDEMPOTENCY_CONFLICT"))

    async def test_transient_failure_same_id_retries_then_caches_success_and_keeps_fingerprint(self):
        calls = 0
        original = self.provider.generate
        async def transient(*args):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise Failure("UPSTREAM_UNAVAILABLE", 503, True)
            return await original(*args)
        self.provider.generate = transient
        self.assertEqual((await self.post()).status_code, 503)
        old_entry = self.service.entries[("test-device", "request-1")]
        changed = request(); changed["payload"]["userText"] = "다른 합성 발화"
        self.assertEqual((await self.post(changed)).status_code, 409)
        retried = await self.post()
        self.assertEqual(retried.status_code, 200)
        # A timer for the failed attempt cannot remove a successful replacement.
        self.service._expire(("test-device", "request-1"), old_entry)
        self.assertEqual((await self.post()).json(), retried.json())
        self.assertEqual((await self.post(changed)).status_code, 409)
        self.assertEqual(calls, 2)

    async def test_transient_retry_after_deadline_is_respected(self):
        now = [100.0]
        self.service.clock = lambda: now[0]
        calls = 0
        original = self.provider.generate
        async def transient(*args):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise Failure("UPSTREAM_UNAVAILABLE", 503, True, 2000)
            return await original(*args)
        self.provider.generate = transient
        self.assertEqual((await self.post()).json()["retryAfterMs"], 2000)
        now[0] += 1
        early = await self.post()
        self.assertEqual((early.status_code, early.json()["retryAfterMs"]), (503, 1000))
        self.assertEqual(calls, 1)
        now[0] += 1
        self.assertEqual((await self.post()).status_code, 200)
        self.assertEqual(calls, 2)

    async def test_retry_cannot_bypass_start_rate_limit_and_cancelled_failure_never_retries(self):
        async def transient(*args):
            raise Failure("GENERATION_TIMEOUT", 504, True)
        self.provider.generate = transient
        self.assertEqual((await self.post()).status_code, 504)
        self.service.starts["test-device"].extend([self.service.clock()] * 59)
        self.assertEqual((await self.post()).status_code, 429)
        self.assertEqual((await self.cancel()).status_code, 204)
        self.assertEqual((await self.post()).json()["code"], "CANCELLED")

    async def test_retry_cannot_bypass_two_inflight_limit(self):
        original = self.provider.generate
        release = asyncio.Event(); entered = asyncio.Event(); calls = 0
        async def transient_then_held(*args):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise Failure("UPSTREAM_UNAVAILABLE", 503, True)
            if calls == 3:
                entered.set()
            await release.wait()
            return await original(*args)
        self.provider.generate = transient_then_held
        self.assertEqual((await self.post()).status_code, 503)
        two = asyncio.create_task(self.post(request(request_id="request-2")))
        three = asyncio.create_task(self.post(request(request_id="request-3")))
        await entered.wait()
        self.assertEqual((await self.post()).status_code, 429)
        self.assertEqual(calls, 3)
        release.set()
        self.assertTrue(all(r.status_code == 200 for r in await asyncio.gather(two, three)))
        self.assertEqual((await self.post()).status_code, 200)
        self.assertEqual(calls, 4)

    async def test_owner_isolation_for_cache_and_cancellation(self):
        first = await self.post()
        self.assertEqual((await self.cancel(token=TOKEN_B)).status_code, 404)
        second = await self.post(token=TOKEN_B)
        self.assertEqual(second.status_code, 200)
        self.assertNotEqual(first.json()["result"], second.json()["result"])
        self.assertEqual(self.provider.calls, 2)

    async def test_cancel_suppresses_completed_cached_response(self):
        await self.post()
        self.assertEqual((await self.cancel()).status_code, 204)
        self.assertEqual((await self.post()).json()["code"], "CANCELLED")
        self.assertEqual((await self.cancel()).status_code, 204)

    async def test_unknown_cancel_and_body_are_rejected(self):
        self.assertEqual((await self.cancel()).status_code, 404)
        r = await self.client.post("/v1/generations/request-1/cancel", json={}, headers={"Authorization": "Bearer " + TOKEN_A})
        self.assertEqual(r.status_code, 400)

    async def test_strict_schema_unknown_fields_integer_and_limit(self):
        cases = []
        for key, value in (("unexpected", True), ("cancelEpoch", 1.0), ("cancelEpoch", 2**63), ("turnId", None)):
            body = request(); body[key] = value; cases.append(body)
        body = request(); body["payload"]["userText"] = "x" * 4001; cases.append(body)
        body = request(); body["payload"]["sourceRevisionIds"] = ["x", "x"]; cases.append(body)
        for body in cases:
            self.assertEqual((await self.post(body)).status_code, 400)
        self.assertEqual(self.provider.calls, 0)

    async def test_duplicate_json_keys_and_nonfinite_are_rejected(self):
        for data in (b'{"schemaVersion":"0.8","schemaVersion":"0.8"}', b'{"value":NaN}', b'[]'):
            r = await self.client.post("/v1/generations", content=data,
                headers={"Authorization": "Bearer " + TOKEN_A, "Content-Type": "application/json"})
            self.assertEqual(r.status_code, 400)

    async def test_oversize_stream_rejected_without_provider(self):
        async def chunks():
            for _ in range(5):
                yield b"x" * (MAX_BODY // 4)
        r = await self.client.post("/v1/generations", content=chunks(),
                headers={"Authorization": "Bearer " + TOKEN_A, "Content-Type": "application/json"})
        self.assertEqual(r.status_code, 400)
        self.assertEqual(self.provider.calls, 0)

    async def test_unknown_and_unauthorized_evidence_rejected(self):
        value = request(); value["payload"]["sourceRevisionIds"] = ["test-source-r1"]
        self.assertEqual((await self.post(value, TOKEN_B)).json()["code"], "NO_EVIDENCE")
        value["payload"]["sourceRevisionIds"] = ["unreviewed-source"]
        self.assertEqual((await self.post(value)).json()["code"], "NO_EVIDENCE")
        self.assertEqual(self.provider.calls, 0)

    async def test_story_authorized_evidence_and_server_version(self):
        body = (await self.post(request("story"))).json()
        self.assertEqual(body["result"]["storyId"], "test-story")
        self.assertTrue(body["result"]["storyVersionId"].startswith("sv-"))

    async def test_story_excluded_fact_cannot_be_regenerated(self):
        value = request("story"); value["payload"]["excludedFactIds"] = ["test-fact"]
        self.assertEqual((await self.post(value)).json()["code"], "NO_EVIDENCE")

    async def test_public_story_rejects_personal_transition_and_missing_links(self):
        for index, (kind, empty) in enumerate((("personal", None), ("transition", None),
                                             ("fact", "sourceRevisionIds"), ("fact", "factRevisionIds"))):
            paragraph = {"text": "합성 공개 이야기 문단입니다.", "kind": kind,
                         "sourceRevisionIds": ["test-source-r1"], "factRevisionIds": ["test-fact-r1"]}
            if empty:
                paragraph[empty] = []
            self.provider.result = {"paragraphs": [paragraph], "estimatedDurationMs": 2000}
            response = await self.post(request("story", "story-rejected-" + str(index)))
            self.assertEqual((response.status_code, response.json()["code"]), (422, "INVALID_MODEL_OUTPUT"))

    async def test_public_story_accepts_matching_fact_and_tradition_without_promoting_kind(self):
        for kind in ("fact", "tradition"):
            document = evidence_document()
            document["facts"][0]["kind"] = kind
            self.service.evidence = EvidenceRegistry(document)
            self.provider.result = {"paragraphs": [{"text": "공개 합성 근거 문단입니다.", "kind": kind,
                "sourceRevisionIds": ["test-source-r1"], "factRevisionIds": ["test-fact-r1"]}], "estimatedDurationMs": 2000}
            response = await self.post(request("story", "story-" + kind))
            self.assertEqual(response.status_code, 200)
            body = response.json()
            self.assertEqual(body["result"]["paragraphs"][0]["kind"], kind)
            self.assertEqual(body["metadata"]["promptVersion"], "petasos-v0.8-5")
            self.assertTrue(body["result"]["storyVersionId"].startswith("sv-"))
            self.provider.result["paragraphs"][0]["kind"] = "tradition" if kind == "fact" else "fact"
            wrong = await self.post(request("story", "story-kind-mismatch-" + kind))
            self.assertEqual((wrong.status_code, wrong.json()["code"]), (422, "INVALID_MODEL_OUTPUT"))

    async def test_public_story_rejects_duplicate_stable_facts_with_same_or_different_revisions(self):
        document = evidence_document()
        document["facts"].append(document["facts"][0] | {"factRevisionId": "test-fact-r2"})
        self.service.evidence = EvidenceRegistry(document)
        paragraph = {"text": "합성 근거 문단입니다.", "kind": "fact",
                     "sourceRevisionIds": ["test-source-r1"], "factRevisionIds": ["test-fact-r1"]}
        cases = ([paragraph, copy.deepcopy(paragraph)],
                 [paragraph, paragraph | {"factRevisionIds": ["test-fact-r2"]}],
                 [paragraph | {"factRevisionIds": ["test-fact-r1", "test-fact-r2"]}])
        for index, paragraphs in enumerate(cases):
            self.provider.result = {"paragraphs": paragraphs, "estimatedDurationMs": 2000}
            response = await self.post(request("story", "story-duplicate-" + str(index)))
            self.assertEqual((response.status_code, response.json()["code"]), (422, "INVALID_MODEL_OUTPUT"))

    async def test_public_story_requires_exact_fact_source_union_but_accepts_complete_multi_fact_graph(self):
        document = evidence_document()
        document["sources"].append(document["sources"][0] | {"sourceRevisionId": "test-source-r2"})
        document["facts"].append(document["facts"][0] | {"factRevisionId": "test-fact-r2", "factId": "test-fact-two",
                                                       "sourceRevisionIds": ["test-source-r2"]})
        self.service.evidence = EvidenceRegistry(document)
        paragraph = {"text": "여러 합성 근거 문단입니다.", "kind": "fact",
                     "sourceRevisionIds": ["test-source-r1", "test-source-r2"], "factRevisionIds": ["test-fact-r1"]}
        value = request("story", "story-extra-source")
        value["payload"]["sourceRevisionIds"] = ["test-source-r1", "test-source-r2"]
        self.provider.result = {"paragraphs": [paragraph], "estimatedDurationMs": 2000}
        rejected = await self.post(value)
        self.assertEqual((rejected.status_code, rejected.json()["code"]), (422, "INVALID_MODEL_OUTPUT"))
        paragraph["factRevisionIds"].append("test-fact-r2")
        value["requestId"] = "story-complete-source-union"
        accepted = await self.post(value)
        self.assertEqual(accepted.status_code, 200)
        self.assertEqual(accepted.json()["result"]["paragraphs"][0]["sourceRevisionIds"], ["test-source-r1", "test-source-r2"])

    async def test_public_story_dedup_and_exact_union_do_not_change_answer_permissions(self):
        document = evidence_document()
        document["sources"].append(document["sources"][0] | {"sourceRevisionId": "test-source-r2"})
        self.service.evidence = EvidenceRegistry(document)
        paragraph = {"text": "합성 답변 근거 문단입니다.", "kind": "fact",
                     "sourceRevisionIds": ["test-source-r1", "test-source-r2"], "factRevisionIds": ["test-fact-r1"]}
        self.provider.result = {"paragraphs": [paragraph, copy.deepcopy(paragraph)],
                                "action": "continue", "targetContentId": None}
        value = request()
        value["payload"]["sourceRevisionIds"] = ["test-source-r1", "test-source-r2"]
        self.assertEqual((await self.post(value)).status_code, 200)

    async def test_factual_output_without_fact_evidence_rejected(self):
        self.provider.result = {"paragraphs": [{"text": "합성", "kind": "fact", "sourceRevisionIds": [], "factRevisionIds": []}],
                                "action": "continue", "targetContentId": None}
        self.assertEqual((await self.post()).json()["code"], "INVALID_MODEL_OUTPUT")

    async def test_model_cannot_assign_paragraph_keys_or_unknown_action_target(self):
        value = {"paragraphs": [{"text": "합성", "kind": "personal", "sourceRevisionIds": [], "factRevisionIds": []}],
                 "action": "continue", "targetContentId": "not-authorized"}
        self.provider.result = value
        self.assertEqual((await self.post()).json()["code"], "INVALID_MODEL_OUTPUT")
        value["targetContentId"] = None; value["paragraphs"][0]["paragraphKey"] = "model-key"
        self.assertEqual((await self.post(request(request_id="request-2"))).json()["code"], "INVALID_MODEL_OUTPUT")

    async def test_model_cannot_choose_unadvertised_playback_actions_with_null_target(self):
        for action in ("next", "music", "stop", "replay"):
            self.provider.result = {"paragraphs": [{"text": "합성", "kind": "transition",
                "sourceRevisionIds": [], "factRevisionIds": []}], "action": action, "targetContentId": None}
            response = await self.post(request(request_id="action-" + action))
            self.assertEqual((response.status_code, response.json()["code"]), (422, "INVALID_MODEL_OUTPUT"))

    async def test_supported_answer_actions_pass_to_current_host(self):
        for action in ("continue", "unsupported"):
            self.provider.result = {"paragraphs": [{"text": "합성", "kind": "transition",
                "sourceRevisionIds": [], "factRevisionIds": []}], "action": action, "targetContentId": None}
            response = await self.post(request(request_id="action-" + action))
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["result"]["action"], action)

    async def test_summary_exact_completed_and_partial_sets(self):
        value = request("summary")
        value["payload"]["completedResponses"] = [{"paragraphKey": "played-1", "text": "완료 합성 답변", "sourceRevisionIds": []}]
        value["payload"]["partialUnknownKeys"] = ["interrupted-1"]
        body = (await self.post(value)).json()
        self.assertEqual(body["result"]["completedResponseKeys"], ["played-1"])
        self.provider.result = body["result"]
        self.provider.result["completedResponseKeys"] = ["interrupted-1"]
        value["requestId"] = "request-2"
        self.assertEqual((await self.post(value)).json()["code"], "INVALID_MODEL_OUTPUT")

    async def test_summary_duplicate_and_overlap_input_keys_rejected(self):
        value = request("summary")
        value["payload"]["completedResponses"] = [{"paragraphKey": "played-1", "text": "합성", "sourceRevisionIds": []}] * 2
        self.assertEqual((await self.post(value)).status_code, 400)
        value["payload"]["completedResponses"].pop()
        value["payload"]["partialUnknownKeys"] = ["played-1"]
        self.assertEqual((await self.post(value)).status_code, 400)

    async def test_summary_does_not_promote_tradition_to_verified_fact(self):
        document = evidence_document()
        document["facts"][0]["kind"] = "tradition"
        self.service.evidence = EvidenceRegistry(document)
        value = request("summary")
        value["payload"]["completedResponses"] = [{"paragraphKey": "played-1", "text": "합성 전승", "sourceRevisionIds": ["test-source-r1"]}]
        self.provider.result = {"summaryText": "합성 요약", "userClaims": [], "verifiedFactRevisionIds": ["test-fact-r1"],
            "unresolvedQuestions": [], "completedResponseKeys": ["played-1"], "partialUnknownKeys": []}
        self.assertEqual((await self.post(value)).json()["code"], "INVALID_MODEL_OUTPUT")

    async def test_resume_requires_exact_candidate_pair(self):
        self.provider.result = {"action": "resume", "targetContentId": "story-a", "paragraphId": "wrong-p",
                                "transitionText": None, "reason": "합성"}
        value = request("resume")
        value["payload"]["candidates"] = [{"contentId": "story-a", "paragraphId": "p-a", "kind": "story"}]
        self.assertEqual((await self.post(value)).json()["code"], "INVALID_MODEL_OUTPUT")

    async def test_resume_rejects_transition_narration_even_for_authorized_candidate(self):
        value = request("resume")
        value["payload"]["candidates"] = [{"contentId": "story-a", "paragraphId": "p-a", "kind": "story"}]
        self.provider.result = {"action": "resume", "targetContentId": "story-a", "paragraphId": "p-a",
                                "transitionText": "실행 권한 없는 합성 전환 문장입니다.", "reason": "합성 재개입니다."}
        rejected = await self.post(value)
        self.assertEqual((rejected.status_code, rejected.json()["code"]), (422, "INVALID_MODEL_OUTPUT"))
        self.provider.result["transitionText"] = None
        value["requestId"] = "resume-no-narration"
        accepted = await self.post(value)
        self.assertEqual(accepted.status_code, 200)
        self.assertEqual(accepted.json()["result"]["targetContentId"], "story-a")
        self.assertIsNone(accepted.json()["result"]["transitionText"])

    async def test_resume_wait_accepts_only_null_transition_and_no_target(self):
        self.provider.result = {"action": "wait", "targetContentId": None, "paragraphId": None,
                                "transitionText": "합성 대기 전환 문장입니다.", "reason": "합성 대기입니다."}
        rejected = await self.post(request("resume", "resume-wait-text"))
        self.assertEqual((rejected.status_code, rejected.json()["code"]), (422, "INVALID_MODEL_OUTPUT"))
        self.provider.result["transitionText"] = None
        accepted = await self.post(request("resume", "resume-wait-null"))
        self.assertEqual(accepted.status_code, 200)
        self.assertIsNone(accepted.json()["result"]["transitionText"])

    async def test_provider_failure_contains_no_private_exception(self):
        async def broken(*args):
            raise RuntimeError("SECRET_PRIVATE_UTTERANCE")
        self.provider.generate = broken
        with self.assertNoLogs("petasos_server"):
            response = await self.post()
        self.assertEqual(response.json()["code"], "UPSTREAM_UNAVAILABLE")
        self.assertNotIn("SECRET", response.text)

    async def test_unconfigured_provider_returns_unavailable(self):
        self.service.provider = UnavailableProvider()
        self.assertEqual((await self.post()).json()["code"], "UPSTREAM_UNAVAILABLE")

    async def test_timeout_is_sanitized_and_retryable(self):
        self.service.timeout = 0.01
        async def slow(*args):
            await asyncio.sleep(1)
        self.provider.generate = slow
        response = await self.post()
        self.assertEqual(response.status_code, 504)
        self.assertTrue(response.json()["retryable"])

    async def test_cached_private_result_is_eagerly_evicted(self):
        self.service.ttl = 0.01
        await self.post()
        await asyncio.sleep(0.025)
        self.assertFalse(self.service.entries)
        await self.post()
        self.assertEqual(self.provider.calls, 2)

    async def test_concurrent_join_cancel_and_two_active_limit(self):
        entered = asyncio.Event(); release = asyncio.Event()
        original = self.provider.generate
        async def held(*args):
            entered.set(); await release.wait(); return await original(*args)
        self.provider.generate = held
        one = asyncio.create_task(self.post())
        await entered.wait()
        same = asyncio.create_task(self.post())
        two = asyncio.create_task(self.post(request(request_id="request-2")))
        await asyncio.sleep(0)
        three = await self.post(request(request_id="request-3"))
        self.assertEqual(three.status_code, 429)
        self.assertEqual((await self.cancel()).status_code, 204)
        self.assertEqual((await one).json()["code"], "CANCELLED")
        self.assertEqual((await same).json()["code"], "CANCELLED")
        release.set()
        self.assertEqual((await two).status_code, 200)
        await asyncio.sleep(0)
        self.assertEqual((await self.post()).json()["code"], "CANCELLED")
        self.assertEqual(self.provider.calls, 2)

    async def test_per_device_sixty_per_minute(self):
        for index in range(60):
            self.assertEqual((await self.post(request(request_id="r-" + str(index)))).status_code, 200)
        response = await self.post(request(request_id="r-60"))
        self.assertEqual(response.status_code, 429)
        self.assertGreater(response.json()["retryAfterMs"], 0)
        self.assertEqual((await self.post(request(request_id="r-60"), TOKEN_B)).status_code, 200)


class OpenRouterTests(unittest.IsolatedAsyncioTestCase):
    async def test_privacy_and_structured_fields_enforced_on_actual_http_adapter(self):
        captured = []
        def transport(req):
            captured.append(json.loads(req.content))
            return httpx.Response(200, json={"model": "configured/model", "provider": "ApprovedProvider", "choices": [
                {"finish_reason": "stop", "message": {"content": '{"value":1}'}}]})
        provider = OpenRouterProvider(OpenRouterConfig("synthetic-key", "configured/model", "approved-slug", ("ApprovedProvider",),
                                     time.time() + 60, True), httpx.MockTransport(transport))
        try:
            result = await provider.generate("answer", {"userText": "합성"}, EvidenceRegistry().resolve("test", request()), {"type": "object"})
            self.assertIsNone(result.input_tokens)
            body = captured[0]
            self.assertFalse(body["stream"])
            self.assertEqual(body["provider"], {"only": ["approved-slug"], "order": ["approved-slug"],
                "allow_fallbacks": False, "require_parameters": True, "data_collection": "deny", "zdr": True})
            self.assertTrue(body["response_format"]["json_schema"]["strict"])
            self.assertNotIn("tools", body)
        finally:
            await provider.close()

    async def test_unapproved_provider_response_rejected(self):
        provider = OpenRouterProvider(OpenRouterConfig("synthetic-key", "model", "slug", ("Approved",), time.time() + 60, True),
            httpx.MockTransport(lambda _: httpx.Response(200, json={"model": "model", "provider": "Unexpected"})))
        try:
            with self.assertRaisesRegex(Exception, "INVALID_MODEL_OUTPUT"):
                await provider.generate("answer", {}, EvidenceRegistry().resolve("test", request()), {})
        finally:
            await provider.close()


    async def test_upstream_retry_after_is_preserved_and_missing_is_unknown(self):
        for header, expected in (("120", 120000), (None, None)):
            provider = OpenRouterProvider(OpenRouterConfig("synthetic-key", "model", "slug", ("Approved",), time.time() + 60, True),
                httpx.MockTransport(lambda _: httpx.Response(429, headers={"Retry-After": header} if header else {})))
            try:
                with self.assertRaisesRegex(Exception, "RATE_LIMITED") as raised:
                    await provider.generate("answer", {}, EvidenceRegistry().resolve("test", request()), {})
                self.assertEqual(raised.exception.retry_after_ms, expected)
            finally:
                await provider.close()


if __name__ == "__main__":
    unittest.main()
