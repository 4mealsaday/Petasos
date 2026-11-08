"""Synthetic protocol fixtures; no live Maps call or stored provider route."""
from copy import deepcopy
from dataclasses import FrozenInstanceError
from io import BytesIO
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit

from petasos_server.naver_routes import (
    ENDPOINT, MAX_REQUEST_BYTES, MAX_RESPONSE_BYTES, NaverDirectionsClient, NaverRouteError,
    NaverRouteRequest, _NoRedirect, decode_naver_request, decode_naver_route,
)


def fixture():
    # Invented small path and timings, not a retained API response.
    return {"code": 0, "message": "synthetic", "currentDateTime": "2026-11-09T00:00:00",
            "route": {"traoptimal": [{
                "summary": {"start": {"location": [127, 37]}, "goal": {"location": [127.003, 37]},
                            "distance": 270, "duration": 30000},
                "path": [[127, 37], [127.001, 37], [127.002, 37], [127.003, 37]],
                "guide": [{"pointIndex": 0, "distance": 0, "duration": 0},
                          {"pointIndex": 1, "distance": 90, "duration": 8000},
                          {"pointIndex": 3, "distance": 180, "duration": 22000}],
                # This deliberately describes just part of the path.
                "section": [{"pointIndex": 1, "pointCount": 2, "distance": 90, "speed": 36}],
            }]}}


def raw(document):
    return json.dumps(document, ensure_ascii=False).encode()


def route_request(**kwargs):
    return NaverRouteRequest((127, 37), (127.003, 37), **kwargs)


def selected(document):
    return document["route"]["traoptimal"][0]


class Reply:
    status = 200

    def __init__(self, data):
        self.data, self.read_limit, self.closed = data, None, False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.closed = True

    def read(self, limit):
        self.read_limit = limit
        return self.data[:limit]


class Opener:
    def __init__(self, value):
        self.value, self.calls = value, []

    def open(self, request, timeout):
        self.calls.append((request, timeout))
        if isinstance(self.value, Exception):
            raise self.value
        return self.value


class NaverRouteTests(unittest.TestCase):
    def test_new_maps_origin_and_two_credentials_in_headers_not_query(self):
        client = NaverDirectionsClient("synthetic-id", "synthetic-secret")
        request = route_request(waypoints=[(127.001, 37), (127.002, 37)], option="trafast")
        prepared = client.prepare_request(request)
        parts = urlsplit(prepared.full_url)
        self.assertEqual(parts.scheme + "://" + parts.netloc + parts.path, ENDPOINT)
        self.assertEqual(parts.netloc, "maps.apigw.ntruss.com")
        self.assertEqual(prepared.get_method(), "GET")
        self.assertEqual(prepared.get_header("X-ncp-apigw-api-key-id"), "synthetic-id")
        self.assertEqual(prepared.get_header("X-ncp-apigw-api-key"), "synthetic-secret")
        self.assertEqual(parse_qs(parts.query), {"start": ["127,37"], "goal": ["127.003,37"],
            "waypoints": ["127.001,37|127.002,37"], "option": ["trafast"], "lang": ["ko"]})
        for value in ("synthetic-id", "synthetic-secret", "naveropenapi"):
            self.assertNotIn(value, prepared.full_url)
            self.assertNotIn(value, repr(client))

    def test_no_waypoints_and_default_option(self):
        query = parse_qs(urlsplit(NaverDirectionsClient("id", "secret").prepare_request(route_request()).full_url).query)
        self.assertNotIn("waypoints", query)
        self.assertEqual(query["option"], ["traoptimal"])

    def test_coordinate_encoding_preserves_float_precision(self):
        origin = (127.12345678901234, 37.123456789012345)
        prepared = NaverDirectionsClient("id", "secret").prepare_request(NaverRouteRequest(origin, (127, 37)))
        query = parse_qs(urlsplit(prepared.full_url).query)
        self.assertEqual(tuple(map(float, query["start"][0].split(","))), origin)

    def test_input_lists_are_copied_to_immutable_positions(self):
        origin, waypoints = [127, 37], [[127.001, 37]]
        request = NaverRouteRequest(origin, [127.003, 37], waypoints)
        origin[0], waypoints[0][0] = 128, 128
        self.assertEqual(request.origin, (127, 37))
        self.assertEqual(request.waypoints, ((127.001, 37),))
        with self.assertRaises(FrozenInstanceError):
            request.option = "trafast"
        self.assertNotIn("127", repr(request))

    def test_local_request_contract_and_all_official_single_options(self):
        default = decode_naver_request(raw({"origin": [127, 37], "destination": [127.003, 37]}))
        self.assertEqual(default, route_request())
        for option in ("trafast", "tracomfort", "traoptimal", "traavoidtoll", "traavoidcaronly"):
            request = decode_naver_request(raw({"origin": [127, 37], "destination": [127.003, 37],
                "waypoints": [[127.001, 37]], "option": option}))
            self.assertEqual(request.option, option)
            self.assertEqual(request.waypoints, ((127.001, 37),))

    def test_request_rejects_alternatives_injection_unknown_and_missing_fields(self):
        documents = [{}, [], {"origin": [127, 37]},
            {"origin": [127, 37], "destination": [127.003, 37], "url": "https://evil.invalid"},
            {"origin": [127, 37], "destination": [127.003, 37], "option": "trafast:traoptimal"},
            {"origin": [127, 37], "destination": "127,37:128,38"},
            {"origin": [127, 37], "destination": [127.003, 37], "waypoints": None}]
        for document in documents:
            with self.subTest(document=document), self.assertRaisesRegex(NaverRouteError, "^INVALID_REQUEST$"):
                decode_naver_request(raw(document))

    def test_request_json_duplicate_nonfinite_deep_invalid_utf8_and_limits(self):
        for body in (b'{"origin":[],"origin":[]}', b'{"origin":NaN}', b'\xff', b'{',
                     b'[' * 1100 + b'0' + b']' * 1100):
            with self.assertRaisesRegex(NaverRouteError, "^INVALID_REQUEST$"):
                decode_naver_request(body)
        with self.assertRaisesRegex(NaverRouteError, "^REQUEST_TOO_LARGE$"):
            decode_naver_request(b" " * (MAX_REQUEST_BYTES + 1))

    def test_request_coordinate_axes_type_crs_and_waypoint_limit(self):
        for position in ((37, 127), (14000000, 4000000), (float("nan"), 37), (127, float("inf")),
                         (True, 37), (127, False), ("127", 37), (127,), (10**400, 37), None):
            with self.assertRaisesRegex(NaverRouteError, "^INVALID_REQUEST$"):
                NaverRouteRequest(position, (127, 37))
        for kwargs in ({"waypoints": [(127, 37)] * 6}, {"option": []}, {"option": "unknown"}):
            with self.assertRaisesRegex(NaverRouteError, "^INVALID_REQUEST$"):
                route_request(**kwargs)

    def test_path_and_guide_index_spans_keep_provider_milliseconds(self):
        route = decode_naver_route(raw(fixture()), route_request())
        self.assertEqual(route.path[2], (127.002, 37.0))
        self.assertEqual([(s.start_point_index, s.end_point_index, s.duration_ms) for s in route.timing_segments],
                         [(0, 0, 0), (0, 1, 8000), (1, 3, 22000)])
        self.assertEqual(route.total_duration_ms, 30000)
        self.assertTrue(route.timing_coverage_complete)
        self.assertTrue(route.timing_totals_match)
        self.assertTrue(route.timing_usable)
        self.assertEqual(route.source_mode, "AUXILIARY")

    def test_first_nonzero_index_covers_path_origin_without_inventing_an_origin_guide(self):
        document = fixture()
        selected(document)["guide"].pop(0)
        route = decode_naver_route(raw(document), route_request())
        self.assertEqual(route.timing_segments[0].start_point_index, 0)
        self.assertEqual(route.timing_segments[0].end_point_index, 1)
        self.assertTrue(route.timing_usable)

    def test_output_does_not_retain_names_instructions_time_strings_or_keys(self):
        document = fixture()
        selected(document)["guide"][1]["instructions"] = "private-guidance"
        route = decode_naver_route(raw(document), route_request())
        metadata = route.safe_metadata()
        for marker in ("127", "private", "2026", "secret"):
            self.assertNotIn(marker, json.dumps(metadata))
            self.assertNotIn(marker, repr(route))
            self.assertNotIn(marker, repr(route.timing_segments[0]))
        for key in ("storedGeometry", "productEnabled", "activePleosRouteVerified", "collectionEvidence"):
            self.assertFalse(metadata[key])
        self.assertEqual(metadata["reviewStatus"], "not_reviewed")
        self.assertFalse(hasattr(route, "current_date_time"))
        with self.assertRaises(TypeError):
            route.path[0][0] = 128

    def test_zero_origin_guide_is_allowed_but_zero_travel_time_is_unusable(self):
        route = decode_naver_route(raw(fixture()), route_request())
        self.assertEqual(route.safe_metadata()["zeroDurationSegmentCount"], 1)
        self.assertEqual(route.safe_metadata()["zeroDurationTravelSegmentCount"], 0)
        document = fixture()
        selected(document)["guide"][1]["duration"] = 0
        selected(document)["summary"]["duration"] = 22000
        route = decode_naver_route(raw(document), route_request())
        self.assertTrue(route.timing_coverage_complete)
        self.assertTrue(route.timing_totals_match)
        self.assertEqual(route.timing_segments[1].duration_ms, 0)
        self.assertEqual(route.safe_metadata()["zeroDurationTravelSegmentCount"], 1)
        self.assertFalse(route.timing_usable)

    def test_same_index_wait_is_preserved_but_not_interpolated(self):
        document = fixture()
        selected(document)["guide"][0]["duration"] = 500
        selected(document)["summary"]["duration"] = 30500
        route = decode_naver_route(raw(document), route_request())
        self.assertTrue(route.timing_totals_match)
        self.assertEqual(route.safe_metadata()["stationaryTimedSegmentCount"], 1)
        self.assertFalse(route.timing_usable)

    def test_missing_tail_stays_untimed_even_when_summary_and_section_can_fill_it(self):
        document = fixture()
        selected(document)["guide"].pop()
        selected(document)["section"] = [{"pointIndex": 1, "pointCount": 3, "distance": 180,
                                          "speed": 30, "duration": 22000}]
        route = decode_naver_route(raw(document), route_request())
        self.assertFalse(route.timing_coverage_complete)
        self.assertFalse(route.timing_usable)
        self.assertEqual(route.safe_metadata()["unknownTailPointCount"], 2)
        self.assertEqual(len(route.timing_segments), 2)
        self.assertEqual(route.timing_segments[-1].duration_ms, 8000)

    def test_missing_tail_with_matching_summary_is_still_unusable(self):
        document = fixture()
        selected(document)["guide"][-1]["pointIndex"] = 2
        route = decode_naver_route(raw(document), route_request())
        self.assertTrue(route.timing_totals_match)
        self.assertFalse(route.timing_coverage_complete)
        self.assertFalse(route.timing_usable)
        self.assertEqual(route.safe_metadata()["unknownTailPointCount"], 1)

    def test_empty_or_absent_guides_leave_all_timing_unknown(self):
        for missing in (True, False):
            document = fixture()
            if missing:
                selected(document).pop("guide")
            else:
                selected(document)["guide"] = []
            route = decode_naver_route(raw(document), route_request())
            self.assertEqual(route.timing_segments, ())
            self.assertFalse(route.timing_usable)
            self.assertEqual(route.safe_metadata()["unknownTailPointCount"], 3)

    def test_mismatched_summary_is_reported_without_redistributing_eta(self):
        document = fixture()
        selected(document)["summary"].update(duration=31000, distance=271)
        route = decode_naver_route(raw(document), route_request())
        self.assertTrue(route.timing_coverage_complete)
        self.assertFalse(route.timing_totals_match)
        self.assertFalse(route.timing_usable)
        self.assertFalse(route.safe_metadata()["distanceTotalsMatch"])
        self.assertEqual(route.timing_segments[-1].duration_ms, 22000)

    def test_positive_timing_on_degenerate_subpath_is_not_usable(self):
        document = fixture()
        selected(document)["path"][1] = [127, 37]
        route = decode_naver_route(raw(document), route_request())
        self.assertTrue(route.timing_coverage_complete)
        self.assertTrue(route.timing_totals_match)
        self.assertFalse(route.timing_usable)

    def test_nonmonotonic_negative_out_of_range_or_wrong_type_index_rejected(self):
        for index in (-1, 4, True, 1.0, "1"):
            document = fixture()
            selected(document)["guide"][1]["pointIndex"] = index
            with self.assertRaisesRegex(NaverRouteError, "^INVALID_TIMING$"):
                decode_naver_route(raw(document), route_request())
        document = fixture()
        selected(document)["guide"].append({"pointIndex": 1, "distance": 1, "duration": 1000})
        with self.assertRaisesRegex(NaverRouteError, "^INVALID_TIMING$"):
            decode_naver_route(raw(document), route_request())

    def test_negative_or_nonnumeric_guide_time_and_distance_rejected(self):
        for field in ("duration", "distance"):
            for value in (-1, "10", 10.5, True, 10**100):
                document = fixture()
                selected(document)["guide"][1][field] = value
                with self.assertRaisesRegex(NaverRouteError, "^INVALID_TIMING$"):
                    decode_naver_route(raw(document), route_request())

    def test_structurally_bad_guides_are_not_silently_skipped(self):
        for guides in (None, {}, [None], [{"pointIndex": 1}]):
            document = fixture()
            selected(document)["guide"] = guides
            with self.assertRaises(NaverRouteError):
                decode_naver_route(raw(document), route_request())

    def test_route_option_or_alternative_mismatch_is_not_silently_selected(self):
        mutations = [lambda d: d["route"].update(trafast=deepcopy(d["route"]["traoptimal"])),
                     lambda d: d["route"]["traoptimal"].append(deepcopy(selected(d))),
                     lambda d: d["route"].update(traoptimal=[])]
        for mutate in mutations:
            document = fixture()
            mutate(document)
            with self.assertRaisesRegex(NaverRouteError, "^INVALID_RESPONSE$"):
                decode_naver_route(raw(document), route_request())
        with self.assertRaisesRegex(NaverRouteError, "^INVALID_RESPONSE$"):
            decode_naver_route(raw(fixture()), route_request(option="trafast"))

    def test_upstream_failure_and_boolean_success_code_are_not_success(self):
        document = fixture()
        document.update(code=3, message="private-upstream")
        with self.assertRaisesRegex(NaverRouteError, "^UPSTREAM_ERROR$"):
            decode_naver_route(raw(document), route_request())
        document["code"] = False
        with self.assertRaisesRegex(NaverRouteError, "^INVALID_RESPONSE$"):
            decode_naver_route(raw(document), route_request())

    def test_bad_geometry_is_not_guessed_or_repaired(self):
        for path in ([], [[127, 37]], [[127, 37], [127, 37]], [[37, 127], [37, 128]],
                     [[127, 37], [128, True]], [[127, 37], [float("nan"), 37]],
                     [[127, 37], [10**400, 37]], [[127, 37, 0], [128, 37]], "private"):
            document = fixture()
            selected(document)["path"] = path
            with self.assertRaises(NaverRouteError):
                decode_naver_route(raw(document), route_request())

    def test_response_json_limits_and_duplicates_are_sanitized(self):
        for body in (b'{"code":0,"code":0}', b'{"code":NaN}', b'private', b'\xff',
                     b'[' * 1100 + b'0' + b']' * 1100):
            with self.assertRaisesRegex(NaverRouteError, "^INVALID_RESPONSE$"):
                decode_naver_route(body, route_request())
        with self.assertRaisesRegex(NaverRouteError, "^RESPONSE_TOO_LARGE$"):
            decode_naver_route(raw(fixture()), route_request(), maximum_bytes=10)

    def test_points_and_guides_have_separate_limits(self):
        with patch("petasos_server.naver_routes.MAX_POINTS", 3), self.assertRaisesRegex(NaverRouteError, "^GEOMETRY_LIMIT$"):
            decode_naver_route(raw(fixture()), route_request())
        with patch("petasos_server.naver_routes.MAX_GUIDES", 2), self.assertRaisesRegex(NaverRouteError, "^INVALID_TIMING$"):
            decode_naver_route(raw(fixture()), route_request())

    def test_summary_requires_positive_bounded_integer_duration_and_distance(self):
        for field in ("duration", "distance"):
            for value in (0, -1, True, "10", 10.5, 10**100):
                document = fixture()
                selected(document)["summary"][field] = value
                with self.assertRaisesRegex(NaverRouteError, "^INVALID_RESPONSE$"):
                    decode_naver_route(raw(document), route_request())

    def test_one_bounded_request_closes_reply_without_retry(self):
        reply = Reply(raw(fixture()))
        opener = Opener(reply)
        route = NaverDirectionsClient("id", "secret", opener=opener).lookup(route_request())
        self.assertTrue(route.timing_usable)
        self.assertEqual(len(opener.calls), 1)
        self.assertEqual(reply.read_limit, MAX_RESPONSE_BYTES + 1)
        self.assertTrue(reply.closed)

    def test_response_size_limit_after_read(self):
        reply = Reply(b" " * 101)
        with self.assertRaisesRegex(NaverRouteError, "^RESPONSE_TOO_LARGE$"):
            NaverDirectionsClient("id", "secret", maximum_bytes=100, opener=Opener(reply)).lookup(route_request())
        self.assertEqual(reply.read_limit, 101)
        self.assertTrue(reply.closed)

    def test_network_http_redirect_and_rate_limit_errors_do_not_disclose_inputs(self):
        for error in (URLError("private-url"), TimeoutError("private-url"),
                      HTTPError("https://private.invalid", 302, "private-message", {}, BytesIO(b"private-body"))):
            opener = Opener(error)
            with self.assertRaises(NaverRouteError) as raised:
                NaverDirectionsClient("private-id", "private-secret", opener=opener).lookup(route_request())
            self.assertNotIn("private", str(raised.exception))
            self.assertEqual(len(opener.calls), 1)
        reply = Reply(b"private-body")
        reply.status = 429
        with self.assertRaisesRegex(NaverRouteError, "^HTTP_ERROR$"):
            NaverDirectionsClient("id", "secret", opener=Opener(reply)).lookup(route_request())
        self.assertIsNone(reply.read_limit)

    def test_default_transport_has_no_redirect_or_environment_proxy(self):
        client = NaverDirectionsClient("id", "secret")
        redirects = [handler for handler in client._opener.handlers if isinstance(handler, _NoRedirect)]
        self.assertEqual(len(redirects), 1)
        self.assertIsNone(redirects[0].redirect_request(None, None, 302, "private", {}, "https://evil.invalid"))
        self.assertFalse(any(handler.__class__.__name__ == "ProxyHandler" and handler.proxies
                             for handler in client._opener.handlers))

    def test_invalid_configuration_and_error_sanitization(self):
        for credential in ("", "private\nheader", "private value", "한글", None):
            for pair in ((credential, "secret"), ("id", credential)):
                with self.assertRaisesRegex(NaverRouteError, "^INVALID_CONFIGURATION$"):
                    NaverDirectionsClient(*pair)
        for options in ({"timeout": 0}, {"timeout": True}, {"timeout": float("nan")}, {"timeout": 10**400},
                        {"maximum_bytes": True}, {"maximum_bytes": MAX_RESPONSE_BYTES + 1}):
            with self.assertRaisesRegex(NaverRouteError, "^INVALID_CONFIGURATION$"):
                NaverDirectionsClient("id", "secret", **options)
        self.assertEqual(str(NaverRouteError("private")), "INVALID_RESPONSE")


if __name__ == "__main__":
    unittest.main()
