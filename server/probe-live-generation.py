#!/usr/bin/env python3
"""Real configured model, fixed nonpersonal test input, no transcript artifacts.

This runs the real ASGI generation contract in process and calls OpenRouter over
HTTPS. It does not replace Android/TLS or subjective answer-quality acceptance.
It never changes provider privacy approval, chooses a fallback, or buys credits.
"""
import argparse
import asyncio
import hashlib
import json
import logging
import os
from pathlib import Path
import time
import uuid

import httpx

from petasos_server.__main__ import build_app
from petasos_server.contracts import Failure, VALIDATORS
from petasos_server.local_env import load_environment
from petasos_server.provider import OpenRouterProvider

ROOT = Path(__file__).resolve().parents[1]
TEST_TEXT = "실제 API 연결을 확인하는 개발 시험입니다. 짧게 인사해 주세요."


def request(task, payload):
    return {"schemaVersion": "0.8", "requestId": str(uuid.uuid4()), "sessionId": "live-development-probe",
            "cancelEpoch": 1, "routeDependency": None, "turnId": "live-probe-turn", "turnVersion": 1,
            "task": task, "inputHash": hashlib.sha256(b"nonpersonal-development-probe-only").hexdigest(),
            "payload": payload}


async def probe(args):
    environment = load_environment(args.env_file)
    app = build_app(args.config, environment)
    service = app.service
    results = []
    upstream_statuses = []
    try:
        if not isinstance(service.provider, OpenRouterProvider):
            raise ValueError("PROVIDER_NOT_CONFIGURED")
        service.provider.config.validate()
        token = args.token_file.read_text().strip()
        if len(token) < 32 or len(token) > 4096:
            raise ValueError("INVALID_DEVICE_TOKEN")
        if args.diagnostic_timeout_seconds != 14:
            # Only this in-process diagnostic instance changes. Production deadlines stay fixed.
            service.timeout = args.diagnostic_timeout_seconds
            service.provider.client.timeout = httpx.Timeout(args.diagnostic_timeout_seconds)

        async def response_status(response):
            upstream_statuses.append(response.status_code)

        service.provider.client.event_hooks["response"].append(response_status)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="https://local-probe.invalid",
                                     headers={"Authorization": "Bearer " + token}) as client:
            async def post(body):
                start = time.monotonic()
                response = await client.post("/v1/generations", json=body)
                value = response.json()
                result = {"task": body["task"], "httpStatus": response.status_code,
                          "elapsedMs": round((time.monotonic() - start) * 1000), "outcome": "FAIL"}
                if response.status_code == 200 and VALIDATORS["SuccessResponse"].is_valid(value):
                    result.update(outcome="PASS", **value["metadata"])
                else:
                    # Only server-defined enum error codes pass through this validator.
                    if VALIDATORS["ErrorResponse"].is_valid(value):
                        result["code"] = value["code"]
                results.append(result)
                return value if result["outcome"] == "PASS" else None

            answer_request = request("answer", {"userText": TEST_TEXT, "contextSummary": "",
                                                 "heardParagraphIds": [], "sourceRevisionIds": []})
            answer = await post(answer_request)
            if answer:
                before = len(upstream_statuses)
                replay = await client.post("/v1/generations", json=answer_request)
                cached = replay.status_code == 200 and replay.json() == answer and len(upstream_statuses) == before
                results.append({"task": "answer_cached_replay", "outcome": "PASS" if cached else "FAIL",
                                "additionalUpstreamCalls": len(upstream_statuses) - before})
                completed = [{"paragraphKey": p["paragraphKey"], "text": p["text"], "sourceRevisionIds": p["sourceRevisionIds"]}
                             for p in answer["result"]["paragraphs"]]
                # Development-only completion list; this is not evidence of user playback.
                await post(request("summary", {"userText": TEST_TEXT, "completedResponses": completed, "partialUnknownKeys": []}))
        passed = len(results) == 3 and all(r["outcome"] == "PASS" for r in results)
        return {"outcome": "PASS" if passed else "FAIL",
                "scope": "real_OpenRouter_with_nonpersonal_test_input_in_process_ASGI_not_Android_or_playback",
                "diagnosticTimeoutSeconds": args.diagnostic_timeout_seconds,
                "productionDeadlineValidated": passed and args.diagnostic_timeout_seconds == 14,
                "upstreamHttpStatuses": upstream_statuses, "results": results}
    finally:
        await service.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--config", type=Path, default=ROOT / ".local/server/config.json")
    parser.add_argument("--token-file", type=Path, default=ROOT / ".local/server/device-provisioning.token")
    parser.add_argument("--diagnostic-timeout-seconds", type=int, choices=[14, 60], default=14)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    for name in ("httpx", "httpcore", "uvicorn.access", "uvicorn.error"):
        logging.getLogger(name).disabled = True
    try:
        result = asyncio.run(probe(args))
    except (OSError, ValueError, Failure):
        result = {"outcome": "BLOCKED", "code": "LOCAL_CONFIGURATION_NOT_READY"}
    except Exception:
        result = {"outcome": "FAIL", "code": "PROBE_FAILED"}
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        try:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            fd = os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as stream:
                stream.write(encoded)
        except OSError:
            print("REPORT_NOT_WRITTEN")
    print(encoded, end="")
    return 0 if result["outcome"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
