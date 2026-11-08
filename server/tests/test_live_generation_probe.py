"""Probe control flow with real ASGI/provider adapters and a mocked upstream only."""
import contextlib
import importlib.util
import io
import json
import logging
import stat
import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

import httpx

from petasos_server.asgi import GenerationApp
from petasos_server.auth import TokenRegistry
from petasos_server.evidence import EvidenceRegistry
from petasos_server.provider import OpenRouterConfig, OpenRouterProvider, UnavailableProvider
from petasos_server.service import GenerationService
from tests.fixtures import TOKEN_A, token_row


SCRIPT = Path(__file__).resolve().parents[1] / "probe-live-generation.py"
MODULE_SPEC = importlib.util.spec_from_file_location("petasos_live_generation_probe_test", SCRIPT)
PROBE = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(PROBE)

MODEL = "anthropic/claude-sonnet-4.6"
CANONICAL_MODEL = "anthropic/claude-4.6-sonnet-20260217"
PRIVATE_API_KEY = "synthetic-private-openrouter-key-do-not-log"
ANSWER_TEXT = "합성 답변 본문 canary 값입니다."
SUMMARY_TEXT = "합성 요약 본문 canary 값입니다."


class LiveGenerationProbeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.token_file = self.directory / "device.token"
        self.token_file.write_text(TOKEN_A)
        self.environment = {"PETASOS_OPENROUTER_API_KEY": PRIVATE_API_KEY}
        self.calls = []
        self.logger_state = {name: logging.getLogger(name).disabled
                             for name in ("httpx", "httpcore", "uvicorn.access", "uvicorn.error")}

    def tearDown(self):
        for name, disabled in self.logger_state.items():
            logging.getLogger(name).disabled = disabled
        self.temporary.cleanup()

    def upstream(self, request):
        body = json.loads(request.content)
        self.calls.append(body)
        self.assertEqual(request.headers["Authorization"], "Bearer " + PRIVATE_API_KEY)
        data = json.loads(body["messages"][1]["content"])
        payload = data["payload"]
        if data["task"] == "answer":
            result = {"paragraphs": [{"text": ANSWER_TEXT, "kind": "personal",
                                      "sourceRevisionIds": [], "factRevisionIds": []}],
                      "action": "continue", "targetContentId": None}
        else:
            self.assertEqual(data["task"], "summary")
            self.assertEqual(payload["completedResponses"][0]["text"], ANSWER_TEXT)
            result = {"summaryText": SUMMARY_TEXT, "userClaims": [payload["userText"]],
                      "verifiedFactRevisionIds": [], "unresolvedQuestions": [],
                      "completedResponseKeys": [p["paragraphKey"] for p in payload["completedResponses"]],
                      "partialUnknownKeys": payload["partialUnknownKeys"]}
        return httpx.Response(200, json={"model": CANONICAL_MODEL, "provider": "Amazon Bedrock", "choices": [
            {"finish_reason": "stop", "message": {"content": json.dumps(result, ensure_ascii=False)}}],
            "usage": {"prompt_tokens": 101, "completion_tokens": 32}})

    def provider(self, **changes):
        config = OpenRouterConfig(PRIVATE_API_KEY, MODEL, "amazon-bedrock/global", ("Amazon Bedrock",),
                                  time.time() + 60, True, accepted_model_ids=(CANONICAL_MODEL,))
        return OpenRouterProvider(replace(config, **changes), httpx.MockTransport(self.upstream))

    def run_probe(self, provider, timeout=14, report=False):
        app = GenerationApp(TokenRegistry([token_row()]), GenerationService(EvidenceRegistry(), provider, b"k" * 32))
        argv = [str(SCRIPT), "--env-file", str(self.directory / "never-read.env"),
                "--config", str(self.directory / "never-read-config.json"), "--token-file", str(self.token_file),
                "--diagnostic-timeout-seconds", str(timeout)]
        report_path = self.directory / "report.json"
        if report:
            argv.extend(["--report", str(report_path)])
        output = io.StringIO()
        with patch.object(PROBE, "load_environment", return_value=self.environment) as load, \
             patch.object(PROBE, "build_app", return_value=app) as build, \
             patch("sys.argv", argv), contextlib.redirect_stdout(output):
            exit_code = PROBE.main()
        load.assert_called_once_with(self.directory / "never-read.env")
        build.assert_called_once_with(self.directory / "never-read-config.json", self.environment)
        text = output.getvalue()
        for private_value in (PRIVATE_API_KEY, TOKEN_A, PROBE.TEST_TEXT, ANSWER_TEXT, SUMMARY_TEXT):
            self.assertNotIn(private_value, text)
        result = json.loads(text)
        if report:
            self.assertEqual(report_path.read_text(), text)
            self.assertEqual(stat.S_IMODE(report_path.stat().st_mode), 0o600)
        return exit_code, result, app

    def test_answer_cached_replay_and_summary_use_two_upstream_calls_and_safe_report(self):
        provider = self.provider()
        code, result, app = self.run_probe(provider, report=True)
        self.assertEqual(code, 0)
        self.assertEqual(result["outcome"], "PASS")
        self.assertEqual([r["task"] for r in result["results"]], ["answer", "answer_cached_replay", "summary"])
        self.assertEqual(result["upstreamHttpStatuses"], [200, 200])
        self.assertEqual(len(self.calls), 2)
        self.assertEqual(result["results"][1]["additionalUpstreamCalls"], 0)
        self.assertTrue(result["productionDeadlineValidated"])
        self.assertEqual(app.service.timeout, 14)
        self.assertTrue(provider.client.is_closed)
        self.assertFalse(app.service.entries)

    def test_diagnostic_timeout_is_reported_without_claiming_product_deadline(self):
        provider = self.provider()
        code, result, app = self.run_probe(provider, timeout=60)
        self.assertEqual(code, 0)
        self.assertEqual(result["diagnosticTimeoutSeconds"], 60)
        self.assertFalse(result["productionDeadlineValidated"])
        self.assertEqual(app.service.timeout, 60)
        self.assertEqual(len(self.calls), 2)

    def test_unconfigured_provider_blocks_before_any_upstream_call(self):
        code, result, _ = self.run_probe(UnavailableProvider())
        self.assertEqual((code, result), (1, {"outcome": "BLOCKED", "code": "LOCAL_CONFIGURATION_NOT_READY"}))
        self.assertFalse(self.calls)

    def test_invalid_privacy_approval_blocks_before_any_upstream_call(self):
        for changes in ({"privacy_reviewed_until": 1}, {"account_content_logging_disabled": False}):
            with self.subTest(changes=changes):
                provider = self.provider(**changes)
                code, result, _ = self.run_probe(provider)
                self.assertEqual((code, result), (1, {"outcome": "BLOCKED", "code": "LOCAL_CONFIGURATION_NOT_READY"}))
                self.assertFalse(self.calls)
                self.assertTrue(provider.client.is_closed)

    def test_upstream_error_body_never_enters_report_and_summary_is_not_called(self):
        def upstream(request):
            self.calls.append(request)
            return httpx.Response(503, text=PRIVATE_API_KEY + ANSWER_TEXT + SUMMARY_TEXT)
        with patch.object(self, "upstream", side_effect=upstream):
            code, result, _ = self.run_probe(self.provider())
        self.assertEqual(code, 1)
        self.assertEqual(result["outcome"], "FAIL")
        self.assertEqual(result["upstreamHttpStatuses"], [503])
        self.assertEqual(len(result["results"]), 1)
        self.assertEqual(result["results"][0]["code"], "UPSTREAM_UNAVAILABLE")
        self.assertEqual(len(self.calls), 1)


if __name__ == "__main__":
    unittest.main()
