"""Current operator grants apply to new, joined, delayed and cached generation results."""
import asyncio
import copy
import json
import os
import tempfile
import unittest
from pathlib import Path

import httpx

from petasos_server.__main__ import build_app
from petasos_server.asgi import GenerationApp
from petasos_server.auth import TokenRegistry
from petasos_server.contracts import Failure, VALIDATORS
from petasos_server.evidence import EvidenceRegistry, FileEvidenceRegistry, MAX_EVIDENCE_BYTES
from petasos_server.service import GenerationService
from tests.fixtures import TOKEN_A, SyntheticProvider, evidence_document, request, token_row


def replace_private(path, document):
    candidate = path.with_suffix(".new")
    with candidate.open("wb") as stream:
        os.chmod(candidate, 0o600)
        stream.write(document if isinstance(document, bytes) else json.dumps(document).encode())
    candidate.replace(path)


def sourced_request(task="answer", request_id="request-1"):
    value = request(task, request_id)
    if task == "summary":
        value["payload"]["completedResponses"] = [{"paragraphKey": "played-1", "text": "완료 합성 답변",
                                                    "sourceRevisionIds": ["test-source-r1"]}]
    else:
        value["payload"]["sourceRevisionIds"] = ["test-source-r1"]
    return value


class RegistryRefreshTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "evidence.json"
        replace_private(self.path, evidence_document())
        self.registry = FileEvidenceRegistry(self.path)

    def resolve(self, value=None, owner="test-device"):
        return self.registry.resolve(owner, value or sourced_request())

    def assert_blocked(self, value=None):
        with self.assertRaises(Failure) as caught:
            self.resolve(value)
        self.assertEqual((caught.exception.code, caught.exception.status), ("NO_EVIDENCE", 422))
        self.assertEqual(str(caught.exception), "NO_EVIDENCE")

    def test_atomic_device_grant_removal_and_explicit_restore_are_live(self):
        self.assertTrue(self.resolve().sources)
        document = evidence_document()
        document["sources"][0]["allowedDeviceIds"] = []
        replace_private(self.path, document)
        self.assert_blocked()
        replace_private(self.path, evidence_document())
        self.assertTrue(self.resolve().sources)

    def test_wrong_owner_and_partially_supported_fact_are_never_exposed(self):
        document = evidence_document()
        second = copy.deepcopy(document["sources"][0])
        second.update(sourceRevisionId="test-source-r2", allowedDeviceIds=["other-device"])
        document["sources"].append(second)
        document["facts"][0]["sourceRevisionIds"].append("test-source-r2")
        replace_private(self.path, document)
        registry = FileEvidenceRegistry(self.path)
        self.assertFalse(registry.resolve("test-device", sourced_request()).facts)
        both = sourced_request()
        both["payload"]["sourceRevisionIds"].append("test-source-r2")
        with self.assertRaisesRegex(Failure, "^NO_EVIDENCE$"):
            registry.resolve("test-device", both)
        with self.assertRaisesRegex(Failure, "^NO_EVIDENCE$"):
            registry.resolve("other-device", sourced_request())

    def test_unreviewed_malformed_duplicate_and_nonfinite_replacements_fail_closed(self):
        invalid = [b'{"sources":', b'{"sources":[],"sources":[],"facts":[]}',
                   b'{"sources":[],"facts":[],"private":NaN}', b'\xff', b'null']
        document = evidence_document()
        document["sources"][0]["reviewed"] = False
        invalid.append(document)
        for content in invalid:
            with self.subTest(kind=type(content).__name__):
                replace_private(self.path, content)
                self.assert_blocked()
                # An unreadable registry blocks even a source-free request; no stale fallback.
                self.assert_blocked(request())
        replace_private(self.path, evidence_document())
        self.assertTrue(self.resolve().facts)

    def test_missing_nonprivate_symlink_and_oversized_file_fail_closed(self):
        self.path.unlink()
        self.assert_blocked()
        replace_private(self.path, evidence_document())
        self.path.chmod(0o644)
        self.assert_blocked()
        self.path.chmod(0o600)
        target = self.path.with_suffix(".target")
        self.path.replace(target)
        self.path.symlink_to(target)
        self.assert_blocked()
        self.path.unlink()
        replace_private(self.path, b" " * (MAX_EVIDENCE_BYTES + 1))
        self.assert_blocked()

    def test_existing_source_revision_cannot_be_rewritten_even_after_removal(self):
        replace_private(self.path, {"sources": [], "facts": []})
        self.assert_blocked()
        document = evidence_document()
        document["sources"][0]["text"] = "동일 revision에 다른 본문"
        replace_private(self.path, document)
        self.assert_blocked()
        replace_private(self.path, evidence_document())
        self.assertTrue(self.resolve().facts)

    def test_existing_fact_revision_cannot_change_kind_stable_identity_text_or_links(self):
        for field, value in (("kind", "tradition"), ("factId", "different-fact"),
                             ("text", "새 본문"), ("sourceRevisionIds", ["test-source-r2"])):
            with self.subTest(field=field):
                document = evidence_document()
                document["sources"].append(dict(document["sources"][0], sourceRevisionId="test-source-r2"))
                document["facts"][0][field] = value
                replace_private(self.path, document)
                self.assert_blocked()
        # An invalid candidate must not pin any newly encountered revisions.
        document = evidence_document()
        document["sources"].append(dict(document["sources"][0], sourceRevisionId="test-source-r2", text="검토된 새 본문"))
        replace_private(self.path, document)
        self.assertTrue(self.resolve().facts)

    def test_new_revisions_are_allowed_without_rewriting_the_old_ones(self):
        document = evidence_document()
        document["sources"].append(dict(document["sources"][0], sourceRevisionId="test-source-r2", text="새 출처 본문"))
        document["facts"].append(dict(document["facts"][0], factRevisionId="test-fact-r2",
                                      text="새 사실 본문", sourceRevisionIds=["test-source-r2"]))
        replace_private(self.path, document)
        value = sourced_request()
        value["payload"]["sourceRevisionIds"] = ["test-source-r2"]
        self.assertEqual(set(self.resolve(value).facts), {"test-fact-r2"})
        self.assertEqual(set(self.resolve().facts), {"test-fact-r1"})

    def test_registry_rows_and_model_input_are_detached_from_caller_mutation(self):
        document = evidence_document()
        registry = EvidenceRegistry(document)
        document["facts"][0]["kind"] = "tradition"
        document["sources"][0]["allowedDeviceIds"].clear()
        context = registry.resolve("test-device", sourced_request())
        context.model_input()["facts"][0]["text"] = "변조"
        context.facts["test-fact-r1"]["text"] = "변조"
        actual = registry.resolve("test-device", sourced_request())
        self.assertEqual(actual.facts["test-fact-r1"]["kind"], "fact")
        self.assertEqual(actual.facts["test-fact-r1"]["text"], evidence_document()["facts"][0]["text"])

    def test_invalid_registry_types_raise_only_fixed_configuration_error(self):
        values = [[], {}, {"sources": {}, "facts": []}, {"sources": [None], "facts": []}]
        for field, value in (("sourceRevisionId", None), ("allowedDeviceIds", ["test-device"] * 2),
                             ("text", "\ud800")):
            document = evidence_document()
            document["sources"][0][field] = value
            values.append(document)
        for value in values:
            with self.subTest(kind=type(value).__name__), self.assertRaisesRegex(ValueError, "^INVALID_EVIDENCE_REGISTRY$"):
                EvidenceRegistry(value)

    def test_production_factory_uses_file_backed_registry_and_rejects_bad_initial_file(self):
        key_path = self.path.with_suffix(".key")
        key_path.write_bytes(b"k" * 32)
        config_path = self.path.with_suffix(".config")
        config_path.write_text(json.dumps({"fingerprintKeyFile": str(key_path), "evidenceFile": str(self.path),
                                          "tokensFile": "unused-test-token-file", "provider": None}))
        app = build_app(config_path, environment={})
        self.assertIsInstance(app.service.evidence, FileEvidenceRegistry)
        replace_private(self.path, b"SYNTHETIC_PRIVATE_BAD_FILE")
        with self.assertRaisesRegex(ValueError, "^INVALID_EVIDENCE_REGISTRY$"):
            build_app(config_path, environment={})


class GenerationEvidenceRefreshTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "evidence.json"
        replace_private(self.path, evidence_document())
        self.provider = SyntheticProvider()
        self.registry = FileEvidenceRegistry(self.path)
        self.service = GenerationService(self.registry, self.provider, b"k" * 32)
        app = GenerationApp(TokenRegistry([token_row()]), self.service)
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="https://test")

    async def asyncTearDown(self):
        await self.client.aclose()
        await self.service.close()
        self.directory.cleanup()

    async def post(self, value=None):
        return await self.client.post("/v1/generations", json=value or sourced_request(),
                                      headers={"Authorization": "Bearer " + TOKEN_A})

    def revoke(self):
        document = evidence_document()
        document["sources"][0]["allowedDeviceIds"] = []
        replace_private(self.path, document)

    def assert_denied(self, response, code="NO_EVIDENCE", status=422):
        self.assertEqual((response.status_code, response.json()["code"]), (status, code))
        self.assertFalse(response.json()["retryable"])
        self.assertTrue(VALIDATORS["ErrorResponse"].is_valid(response.json()))
        self.assertEqual(response.headers["cache-control"], "no-store")

    async def test_revoked_new_request_never_calls_provider(self):
        self.revoke()
        self.assert_denied(await self.post())
        self.assertEqual(self.provider.calls, 0)
        self.assertFalse(self.service.entries)

    async def test_cached_success_is_revoked_and_same_request_cannot_resurrect_after_restore(self):
        self.assertEqual((await self.post()).status_code, 200)
        self.revoke()
        self.assert_denied(await self.post())
        replace_private(self.path, evidence_document())
        self.assert_denied(await self.post())
        self.assertEqual((await self.post(sourced_request(request_id="request-2"))).status_code, 200)
        self.assertEqual(self.provider.calls, 2)

    async def test_cached_success_with_missing_or_malformed_registry_never_falls_back(self):
        self.assertEqual((await self.post(request())).status_code, 200)
        self.path.unlink()
        self.assert_denied(await self.post(request()))
        replace_private(self.path, b"SYNTHETIC_PRIVATE_BAD_FILE")
        with self.assertNoLogs("petasos_server"):
            response = await self.post(request(request_id="request-2"))
        self.assert_denied(response)
        self.assertNotIn("SYNTHETIC_PRIVATE", response.text)
        self.assertEqual(self.provider.calls, 1)

    async def test_changed_relevant_fact_set_invalidates_cache_with_stale_context(self):
        self.assertEqual((await self.post()).status_code, 200)
        document = evidence_document()
        document["facts"].append(dict(document["facts"][0], factRevisionId="new-fact-r1", factId="new-fact"))
        replace_private(self.path, document)
        self.assert_denied(await self.post(), "STALE_CONTEXT", 409)
        self.assertEqual((await self.post(sourced_request(request_id="request-2"))).status_code, 200)
        self.assertEqual(self.provider.calls, 2)

    async def test_unrelated_revision_and_other_device_grant_changes_preserve_cache(self):
        first = await self.post()
        document = evidence_document()
        document["sources"][0]["allowedDeviceIds"].append("other-device")
        document["sources"].insert(0, dict(document["sources"][0], sourceRevisionId="other-source-r1"))
        document["facts"].insert(0, dict(document["facts"][0], factRevisionId="other-fact-r1", factId="other-fact",
                                        sourceRevisionIds=["other-source-r1"]))
        replace_private(self.path, document)
        self.assertEqual(first.json(), (await self.post()).json())
        self.assertEqual(self.provider.calls, 1)

    async def test_revocation_between_admission_and_provider_start_prevents_outbound(self):
        original = self.registry.resolve
        calls = 0
        def resolve_then_revoke(owner, value):
            nonlocal calls
            result = original(owner, value)
            calls += 1
            if calls == 1:
                self.revoke()
            return result
        self.registry.resolve = resolve_then_revoke
        self.assert_denied(await self.post())
        self.assertEqual(self.provider.calls, 0)
        self.assertFalse(self.service.active)

    async def test_delayed_response_cannot_be_accepted_after_source_revocation(self):
        original = self.provider.generate
        async def revoke_after_generate(*args):
            value = await original(*args)
            self.revoke()
            return value
        self.provider.generate = revoke_after_generate
        self.assert_denied(await self.post())
        self.assertEqual(self.provider.calls, 1)
        self.assertFalse(self.service.active)

    async def test_already_awakened_waiter_rechecks_before_delivery(self):
        original = self.provider.generate
        entered, release = asyncio.Event(), asyncio.Event()
        async def held(*args):
            entered.set()
            await release.wait()
            return await original(*args)
        self.provider.generate = held
        pending = asyncio.create_task(self.post())
        await entered.wait()
        entry = self.service.entries[("test-device", "request-1")]
        entry.future.add_done_callback(lambda _: self.revoke())
        release.set()
        self.assert_denied(await pending)
        self.assertEqual(self.provider.calls, 1)

    async def test_join_detects_revocation_releases_waiters_and_preserves_active_limit(self):
        original = self.provider.generate
        entered, release = asyncio.Event(), asyncio.Event()
        async def held(*args):
            entered.set()
            await release.wait()
            return await original(*args)
        self.provider.generate = held
        pending = asyncio.create_task(self.post())
        await entered.wait()
        try:
            self.revoke()
            self.assert_denied(await self.post())
            self.assert_denied(await asyncio.wait_for(pending, 1))
            # Upstream is still in flight; denying delivery cannot erase concurrency accounting.
            self.assertEqual(self.service.active["test-device"], 1)
        finally:
            release.set()
        await self.service.entries[("test-device", "request-1")].task
        self.assertFalse(self.service.active)
        self.assertEqual(self.provider.calls, 1)

    async def test_upstream_retryable_error_cannot_override_revocation(self):
        calls = 0
        async def revoke_and_fail(*args):
            nonlocal calls
            calls += 1
            self.revoke()
            raise Failure("UPSTREAM_UNAVAILABLE", 503, True, 1000)
        self.provider.generate = revoke_and_fail
        self.assert_denied(await self.post())
        replace_private(self.path, evidence_document())
        self.assert_denied(await self.post())
        self.assertEqual(calls, 1)
        self.assertIsNone(self.service.entries[("test-device", "request-1")].retry_at)

    async def test_summary_rechecks_completed_response_sources_after_generation(self):
        original = self.provider.generate
        async def revoke_after_generate(*args):
            value = await original(*args)
            self.revoke()
            return value
        self.provider.generate = revoke_after_generate
        self.assert_denied(await self.post(sourced_request("summary")))
        self.assertEqual(self.provider.calls, 1)

    async def test_fact_removal_blocks_cached_story_and_changed_fact_cannot_be_reapproved_in_place(self):
        self.assertEqual((await self.post(sourced_request("story"))).status_code, 200)
        document = evidence_document()
        document["facts"] = []
        replace_private(self.path, document)
        self.assert_denied(await self.post(sourced_request("story")))
        document = evidence_document()
        document["facts"][0]["kind"] = "tradition"
        replace_private(self.path, document)
        self.assert_denied(await self.post(sourced_request("story", "request-2")))
        self.assertEqual(self.provider.calls, 1)

    async def test_cancel_and_fingerprint_conflict_keep_existing_precedence(self):
        await self.post()
        self.revoke()
        changed = sourced_request()
        changed["payload"]["userText"] = "다른 합성 입력"
        self.assert_denied(await self.post(changed), "IDEMPOTENCY_CONFLICT", 409)
        cancelled = await self.client.post("/v1/generations/request-1/cancel",
                                           headers={"Authorization": "Bearer " + TOKEN_A})
        self.assertEqual(cancelled.status_code, 204)
        self.assert_denied(await self.post(), "CANCELLED", 409)
        self.assertEqual(self.provider.calls, 1)


if __name__ == "__main__":
    unittest.main()
