"""Synthetic ASGI/transport tests only; no live NAVER call or retained route."""
import asyncio
from dataclasses import replace
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import httpx

from petasos_server.asgi import GenerationApp
from petasos_server.auth import TokenRegistry
from petasos_server.auxiliary_routes import (
    AuxiliaryRouteService, MAX_WIRE_BYTES, NaverDirectionsProvider, serialize_auxiliary_route,
)
from petasos_server.contracts import Failure
from petasos_server.naver_routes import ENDPOINT, MAX_REQUEST_BYTES, decode_naver_route
from tests.fixtures import TOKEN_A, TOKEN_B, token_row
from tests.test_naver_routes import fixture, raw, route_request, selected


def request_body():
    return {"origin": [127, 37], "destination": [127.003, 37]}


class FakeProvider:
    def __init__(self):
        self.calls = 0
        self.closed = False
        self.received = None

    async def lookup(self, request):
        self.calls += 1
        self.received = request
        return decode_naver_route(raw(fixture()), request)

    async def close(self):
        self.closed = True


class FakeGeneration:
    def __init__(self):
        self.closed = False

    async def close(self):
        self.closed = True


class RouteSerializationTests(unittest.TestCase):
    def test_fixed_provenance_and_exact_path_segment_units(self):
        request = route_request()
        route = decode_naver_route(raw(fixture()), request)
        result = json.loads(serialize_auxiliary_route(route, request))
        self.assertEqual(result["path"], selected(fixture())["path"])
        self.assertEqual(result["timingSegments"], [
            {"startPointIndex": 0, "endPointIndex": 0, "durationMs": 0, "distanceMeters": 0},
            {"startPointIndex": 0, "endPointIndex": 1, "durationMs": 8000, "distanceMeters": 90},
            {"startPointIndex": 1, "endPointIndex": 3, "durationMs": 22000, "distanceMeters": 180},
        ])
        self.assertEqual(result["totalDurationMs"], 30000)
        self.assertEqual(result["sourceMode"], "AUXILIARY")
        self.assertFalse(result["activePleosRouteVerified"])
        self.assertFalse(result["collectionEvidence"])
        self.assertTrue(result["timingUsable"])
        for field in ("routeId", "routeRevision", "sessionId", "request", "origin", "destination", "metadata", "key"):
            self.assertNotIn(field, result)

    def test_missing_tail_and_zero_time_travel_remain_explicitly_unusable(self):
        for mutation in (lambda route: route["guide"].pop(),
                         lambda route: route["guide"][1].update(duration=0)):
            document = fixture(); mutation(selected(document))
            route = decode_naver_route(raw(document), route_request())
            result = json.loads(serialize_auxiliary_route(route, route_request()))
            self.assertFalse(result["timingUsable"])
            self.assertFalse(result["timingTotalsMatch"])
            self.assertEqual(result["path"], selected(document)["path"])
            self.assertEqual(len(result["timingSegments"]), len(selected(document)["guide"]))

    def test_wrong_request_invalid_type_and_nonfinite_are_redacted(self):
        request = route_request()
        route = decode_naver_route(raw(fixture()), request)
        for value, wanted in (({"private": "SYNTHETIC_SECRET"}, request),
                              (route, route_request(option="trafast")),
                              (replace(route, path=((127, 37), (float("nan"), 37))), request)):
            with self.assertRaises(Failure) as raised:
                serialize_auxiliary_route(value, wanted)
            self.assertEqual(raised.exception.code, "INVALID_ROUTE_RESPONSE")
            self.assertNotIn("SECRET", str(raised.exception))

    def test_wire_point_and_guide_caps_reject_whole_response_without_truncation(self):
        request = route_request(); route = decode_naver_route(raw(fixture()), request)
        for constant, limit in (("MAX_WIRE_BYTES", 10), ("MAX_POINTS", 3), ("MAX_GUIDES", 2)):
            with patch("petasos_server.auxiliary_routes." + constant, limit), self.assertRaises(Failure):
                serialize_auxiliary_route(route, request)
        self.assertEqual(len(route.path), 4)


class RouteAsgiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.provider = FakeProvider()
        self.service = AuxiliaryRouteService(self.provider)
        self.generation = FakeGeneration()
        self.tokens = TokenRegistry([token_row(), token_row(TOKEN_B, "other-device")])
        self.app = GenerationApp(self.tokens, self.generation, self.service)
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(self.app), base_url="https://test")

    async def asyncTearDown(self):
        await self.client.aclose()
        await self.service.close()

    async def post(self, value=None, token=TOKEN_A, **kwargs):
        headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        if "content" not in kwargs:
            kwargs["json"] = request_body() if value is None else value
        return await self.client.post("/v1/routes/auxiliary", headers=headers, **kwargs)

    async def test_authenticated_https_no_store_and_no_result_cache(self):
        first = await self.post(); second = await self.post()
        self.assertEqual(first.status_code, 200)
        self.assertEqual(first.json(), second.json())
        self.assertEqual(first.headers["cache-control"], "no-store")
        self.assertEqual(first.headers["x-content-type-options"], "nosniff")
        self.assertEqual(int(first.headers["content-length"]), len(first.content))
        self.assertLessEqual(len(first.content), MAX_WIRE_BYTES)
        self.assertEqual(self.provider.calls, 2)
        self.assertFalse(self.service.active)
        self.assertFalse(self.service.tasks)
        self.assertFalse(hasattr(self.service, "entries"))

    async def test_tls_auth_and_optional_configuration_fail_before_provider(self):
        insecure = await self.client.post("http://test/v1/routes/auxiliary", json=request_body(),
                                         headers={"X-Forwarded-Proto": "https", "Authorization": "Bearer " + TOKEN_A})
        self.assertEqual(insecure.status_code, 403)
        self.assertEqual((await self.client.post("/v1/routes/auxiliary", json=request_body())).status_code, 401)
        self.assertEqual((await self.post(token="invalid")).status_code, 401)
        self.app.route_service = None
        result = await self.post()
        self.assertEqual((result.status_code, result.json()["code"]), (503, "AUXILIARY_ROUTE_UNAVAILABLE"))
        self.assertFalse(result.json()["retryable"])
        self.assertEqual(self.provider.calls, 0)

    async def test_request_decoder_forbids_provider_native_identity_and_invalid_json(self):
        for value in (request_body() | {"provider": "SYNTHETIC_SECRET"}, request_body() | {"routeId": "native"},
                      request_body() | {"option": ["traoptimal", "trafast"]},
                      request_body() | {"origin": [37, 127]}, request_body() | {"waypoints": [[127, 37]] * 6}):
            result = await self.post(value)
            self.assertEqual((result.status_code, result.json()["code"]), (400, "INVALID_REQUEST"))
            self.assertNotIn("SECRET", result.text)
        for data in (b'{"origin":[127,37],"origin":[127,37],"destination":[127.003,37]}',
                     b'{"origin":[NaN,37],"destination":[127.003,37]}', b'\xff'):
            self.assertEqual((await self.post(content=data)).status_code, 400)
        self.assertEqual(self.provider.calls, 0)

    async def test_request_option_and_waypoints_keep_existing_exact_contract(self):
        # The provider in this test supplies the selected option under the proper key.
        async def selected_provider(request):
            document = fixture()
            document["route"][request.option] = document["route"].pop("traoptimal")
            self.provider.received = request
            return decode_naver_route(raw(document), request)
        self.provider.lookup = selected_provider
        result = await self.post(request_body() | {"waypoints": [[127.001, 37]], "option": "trafast"})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(self.provider.received.waypoints, ((127.001, 37.0),))
        self.assertEqual(self.provider.received.option, "trafast")

    async def test_body_cap_is_applied_before_provider_with_or_without_content_length(self):
        self.assertEqual((await self.post(content=b"x" * (MAX_REQUEST_BYTES + 1))).status_code, 400)
        async def chunks():
            yield b"x" * MAX_REQUEST_BYTES
            yield b"x"
        self.assertEqual((await self.post(content=chunks())).status_code, 400)
        self.assertEqual(self.provider.calls, 0)

    async def test_method_content_type_and_duplicate_sensitive_headers_are_rejected(self):
        self.assertEqual((await self.client.get("/v1/routes/auxiliary", headers={"Authorization": "Bearer " + TOKEN_A})).status_code, 404)
        self.assertEqual((await self.client.post("/v1/routes/auxiliary", json=request_body(),
            headers={"Authorization": "Bearer " + TOKEN_A, "Content-Type": "text/plain"})).status_code, 400)
        result = await self.client.post("/v1/routes/auxiliary", content=b"{}", headers=[
            ("Authorization", "Bearer " + TOKEN_A), ("Authorization", "Bearer " + TOKEN_B)])
        self.assertEqual(result.status_code, 400)
        self.assertEqual(self.provider.calls, 0)

    async def test_unexpected_exception_url_credentials_and_body_never_reach_response(self):
        async def broken(request):
            raise RuntimeError("https://example.invalid/SYNTHETIC_SECRET?origin=127,37 SYNTHETIC_BODY")
        self.provider.lookup = broken
        with patch("sys.stdout", new_callable=io.StringIO) as out, patch("sys.stderr", new_callable=io.StringIO) as err:
            response = await self.post()
        self.assertEqual((response.status_code, response.json()["code"]), (503, "UPSTREAM_UNAVAILABLE"))
        self.assertEqual(out.getvalue(), "")
        self.assertEqual(err.getvalue(), "")
        for secret in ("SYNTHETIC", "127", "example.invalid"):
            self.assertNotIn(secret, response.text)

    async def test_total_timeout_cancels_operation_and_releases_all_slots(self):
        cancelled = asyncio.Event()
        async def slow(request):
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
        self.provider.lookup = slow; self.service.timeout = 0.01
        response = await self.post()
        self.assertEqual((response.status_code, response.json()["code"]), (504, "ROUTE_TIMEOUT"))
        self.assertTrue(cancelled.is_set())
        self.assertFalse(self.service.active)
        self.assertFalse(self.service.tasks)

    async def test_owner_and_global_inflight_limits_reject_without_starting_extra_calls(self):
        release, entered = asyncio.Event(), asyncio.Event()
        calls = 0
        async def held(request):
            nonlocal calls
            calls += 1
            if calls == 2:
                entered.set()
            await release.wait()
            return decode_naver_route(raw(fixture()), request)
        self.provider.lookup = held
        self.service.maximum_active = 2
        tasks = [asyncio.create_task(self.post()), asyncio.create_task(self.post())]
        try:
            await entered.wait()
            for token in (TOKEN_A, TOKEN_B):
                result = await self.post(token=token)
                self.assertEqual(result.status_code, 429)
                self.assertEqual(result.headers["retry-after"], "1")
            self.assertEqual(calls, 2)
        finally:
            release.set()
            responses = await asyncio.gather(*tasks)
        self.assertTrue(all(response.status_code == 200 for response in responses))
        self.assertFalse(self.service.active)
        self.assertEqual((await self.post(token=TOKEN_B)).status_code, 200)

    async def test_start_rate_limit_expires_and_does_not_cache_coordinates(self):
        now = [100.0]; self.service.clock = lambda: now[0]
        self.service.maximum_starts_per_minute = 1
        self.assertEqual((await self.post()).status_code, 200)
        response = await self.post()
        self.assertEqual((response.status_code, response.json()["retryAfterMs"]), (429, 60000))
        self.assertEqual(list(self.service.starts["test-device"]), [100.0])
        now[0] += 60
        self.assertEqual((await self.post()).status_code, 200)

    async def test_lifespan_closes_both_independent_services(self):
        messages = iter(({"type": "lifespan.startup"}, {"type": "lifespan.shutdown"}))
        seen = []
        async def receive(): return next(messages)
        async def send(value): seen.append(value)
        await self.app({"type": "lifespan"}, receive, send)
        self.assertTrue(self.provider.closed)
        self.assertTrue(self.generation.closed)
        self.assertTrue(self.service.closed)
        self.assertEqual(seen[-1]["type"], "lifespan.shutdown.complete")

    async def test_cancelled_caller_does_not_leave_work_or_slots_running(self):
        started = asyncio.Event()
        async def held(request):
            started.set()
            await asyncio.Event().wait()
        self.provider.lookup = held
        task = asyncio.create_task(self.service.lookup("test-device", route_request()))
        await started.wait(); task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertFalse(self.service.active)
        self.assertFalse(self.service.tasks)


class NaverAsyncTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_fixed_https_credentials_and_no_redirect_or_environment_proxy(self):
        seen = []
        async def handler(request):
            seen.append(request)
            return httpx.Response(302, headers={"location": "https://example.invalid/SYNTHETIC_SECRET"})
        with patch.dict("os.environ", {"HTTPS_PROXY": "http://example.invalid:9999"}):
            provider = NaverDirectionsProvider("synthetic-id", "synthetic-secret", transport=httpx.MockTransport(handler))
        try:
            with self.assertRaises(Failure) as failure:
                await provider.lookup(route_request())
            self.assertEqual(failure.exception.code, "UPSTREAM_UNAVAILABLE")
            self.assertEqual(len(seen), 1)
            request = seen[0]
            self.assertEqual(str(request.url).split("?")[0], ENDPOINT)
            self.assertEqual(request.headers["x-ncp-apigw-api-key-id"], "synthetic-id")
            self.assertEqual(request.headers["x-ncp-apigw-api-key"], "synthetic-secret")
            self.assertNotIn("synthetic-secret", str(request.url))
            self.assertFalse(provider._client.trust_env)
            self.assertFalse(provider._client.follow_redirects)
            self.assertNotIn("synthetic", repr(provider))
        finally:
            await provider.close()

    async def test_strict_parser_and_response_cap_in_async_transport(self):
        for document, limit, expected in ((raw(fixture()), None, None),
                                          (b'{"code":0,"code":0}', None, "INVALID_ROUTE_RESPONSE"),
                                          (b'SYNTHETIC_SECRET_BODY', 4, "ROUTE_RESPONSE_TOO_LARGE")):
            async def handler(request): return httpx.Response(200, content=document)
            provider = NaverDirectionsProvider("id", "secret", transport=httpx.MockTransport(handler))
            try:
                with patch("petasos_server.auxiliary_routes.MAX_RESPONSE_BYTES", limit or 8 * 1024 * 1024):
                    if expected:
                        with self.assertRaises(Failure) as error:
                            await provider.lookup(route_request())
                        self.assertEqual(error.exception.code, expected)
                    else:
                        self.assertEqual((await provider.lookup(route_request())).source_mode, "AUXILIARY")
            finally:
                await provider.close()

    async def test_network_timeout_and_upstream_body_have_only_fixed_error_codes(self):
        for problem, expected in ((httpx.ReadTimeout("SYNTHETIC_SECRET_URL"), "ROUTE_TIMEOUT"),
                                  (httpx.ConnectError("SYNTHETIC_SECRET_URL"), "UPSTREAM_UNAVAILABLE"),
                                  (httpx.Response(401, content=b"SYNTHETIC_SECRET_BODY"), "UPSTREAM_UNAVAILABLE"),
                                  (httpx.Response(429, content=b"SYNTHETIC_SECRET_BODY"), "RATE_LIMITED")):
            async def handler(request):
                if isinstance(problem, Exception): raise problem
                return problem
            provider = NaverDirectionsProvider("id", "secret", transport=httpx.MockTransport(handler))
            try:
                with self.assertRaises(Failure) as failure:
                    await provider.lookup(route_request())
                self.assertEqual(failure.exception.code, expected)
                self.assertNotIn("SECRET", str(failure.exception))
            finally:
                await provider.close()


class RouteConfigurationTests(unittest.IsolatedAsyncioTestCase):
    async def test_optional_config_and_explicit_server_only_environment(self):
        from petasos_server.__main__ import build_app
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            key = root / "fingerprint.key"; key.write_bytes(b"k" * 32)
            tokens = root / "tokens.json"; tokens.write_text("[]")
            path = root / "config.json"
            config = {"fingerprintKeyFile": str(key), "tokensFile": str(tokens)}
            path.write_text(json.dumps(config))
            app = build_app(path, environment={})
            self.assertIsNone(app.route_service)
            await app.service.close()
            config["auxiliaryRoutes"] = {"type": "naver_maps"}; path.write_text(json.dumps(config))
            with patch("petasos_server.__main__.NaverDirectionsProvider", return_value=FakeProvider()) as provider:
                app = build_app(path, environment={"PETASOS_NAVER_MAPS_CLIENT_ID": "synthetic-id",
                                                   "PETASOS_NAVER_MAPS_CLIENT_SECRET": "synthetic-secret"})
                provider.assert_called_once_with("synthetic-id", "synthetic-secret")
                await app.service.close(); await app.route_service.close()
            for invalid in (None, {}, {"type": "kakao"}, {"type": "naver_maps", "endpoint": "SYNTHETIC_SECRET"}):
                config["auxiliaryRoutes"] = invalid; path.write_text(json.dumps(config))
                with self.assertRaisesRegex(ValueError, "^INVALID_ROUTE_CONFIGURATION$"):
                    build_app(path, environment={})


if __name__ == "__main__":
    unittest.main()
