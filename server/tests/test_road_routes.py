"""Synthetic Kakao protocol fixtures, never a recorded route or a live API claim."""
from copy import deepcopy
from dataclasses import FrozenInstanceError
from io import BytesIO
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit

from petasos_server.road_routes import (
    ENDPOINT, MAX_REQUEST_BYTES, MAX_RESPONSE_BYTES, KakaoRoadRouteClient, RoadRouteError,
    RoadRouteRequest, _NoRedirect, decode_road_route, decode_route_request,
)


def fixture():
    # Small invented coordinates and durations; no downloaded response is retained.
    return {"trans_id": "synthetic-request", "routes": [{"result_code": 0, "result_msg": "synthetic",
        "summary": {"origin": {"x": 127.0, "y": 37.0},
                    "destination": {"x": 127.002, "y": 37.0}, "waypoints": [],
                    "priority": "RECOMMEND", "distance": 180, "duration": 20},
        "sections": [{"distance": 180, "duration": 20, "roads": [
            {"name": "synthetic road A", "distance": 90, "duration": 8,
             "vertexes": [127.0, 37.0, 127.001, 37.0]},
            {"name": "synthetic road B", "distance": 90, "duration": 12,
             "vertexes": [127.001, 37.0, 127.002, 37.0]},
        ]}]}]}


def raw(document):
    return json.dumps(document, ensure_ascii=False).encode()


def route_request(**kwargs):
    return RoadRouteRequest((127, 37), (127.002, 37), **kwargs)


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


class RoadRouteTests(unittest.TestCase):
    def test_exact_fixed_endpoint_authorization_header_and_lon_lat_order(self):
        client = KakaoRoadRouteClient("synthetic-secret")
        request = route_request(waypoints=[(127.001, 37)], priority="TIME", avoid=["toll", "ferries"], origin_angle=270)
        prepared = client.prepare_request(request)
        parts = urlsplit(prepared.full_url)
        self.assertEqual(parts.scheme + "://" + parts.netloc + parts.path, ENDPOINT)
        self.assertEqual(prepared.get_method(), "GET")
        self.assertEqual(prepared.get_header("Authorization"), "KakaoAK synthetic-secret")
        self.assertEqual(parse_qs(parts.query), {
            "origin": ["127,37,angle=270"], "destination": ["127.002,37"],
            "waypoints": ["127.001,37"], "priority": ["TIME"], "avoid": ["toll|ferries"],
            "summary": ["false"], "alternatives": ["false"], "road_details": ["false"]})
        self.assertNotIn("secret", prepared.full_url)
        self.assertNotIn("secret", repr(client))
        self.assertNotIn("127", repr(request))

    def test_optional_query_values_are_omitted(self):
        query = parse_qs(urlsplit(KakaoRoadRouteClient("secret").prepare_request(route_request()).full_url).query)
        self.assertNotIn("waypoints", query)
        self.assertNotIn("avoid", query)
        self.assertNotIn("angle", query["origin"][0])

    def test_request_coordinates_round_trip_without_decimal_truncation(self):
        origin = (127.12345678901234, 37.123456789012345)
        prepared = KakaoRoadRouteClient("secret").prepare_request(RoadRouteRequest(origin, (127.002, 37)))
        encoded = parse_qs(urlsplit(prepared.full_url).query)["origin"][0]
        self.assertEqual(tuple(map(float, encoded.split(","))), origin)

    def test_request_normalizes_nested_mutable_inputs(self):
        origin, waypoints, avoid = [127, 37], [[127.001, 37]], ["toll"]
        request = RoadRouteRequest(origin, [127.002, 37], waypoints, avoid=avoid)
        origin[0], waypoints[0][0], avoid[0] = 128, 128, "ferries"
        self.assertEqual(request.origin, (127, 37))
        self.assertEqual(request.waypoints, ((127.001, 37),))
        self.assertEqual(request.avoid, ("toll",))
        with self.assertRaises(FrozenInstanceError):
            request.priority = "TIME"

    def test_local_request_format_defaults_and_named_options(self):
        request = decode_route_request(raw({"origin": [127, 37], "destination": [127.002, 37]}))
        self.assertEqual(request, route_request())
        request = decode_route_request(raw({"origin": [127, 37], "destination": [127.002, 37],
            "waypoints": [[127.001, 37]], "priority": "DISTANCE", "avoid": ["motorway"], "originAngle": 360}))
        self.assertEqual(request.origin_angle, 360)
        self.assertEqual(request.waypoints, ((127.001, 37),))

    def test_request_rejects_unknown_missing_malformed_or_duplicate_fields(self):
        documents = [{}, {"origin": [127, 37]}, [],
                     {"origin": [127, 37], "destination": [127.002, 37], "endpoint": "https://evil.invalid"},
                     {"origin": [127, 37], "destination": [127.002, 37], "waypoints": None}]
        for document in documents:
            with self.subTest(document=document), self.assertRaisesRegex(RoadRouteError, "^INVALID_REQUEST$"):
                decode_route_request(raw(document))
        for body in (b'{"origin":[],"origin":[]}', b'{"origin":NaN}', b'\xff', b'{', b'[' * 1100 + b'0' + b']' * 1100):
            with self.assertRaisesRegex(RoadRouteError, "^INVALID_REQUEST$"):
                decode_route_request(body)
        with self.assertRaisesRegex(RoadRouteError, "^REQUEST_TOO_LARGE$"):
            decode_route_request(b" " * (MAX_REQUEST_BYTES + 1))

    def test_coordinate_validation_rejects_swapped_axes_projection_nonfinite_and_booleans(self):
        for position in ((37, 127), (14000000, 4000000), (float("nan"), 37), (127, float("inf")),
                         (True, 37), (127, False), ("127", 37), (127,), (10**400, 37), None):
            with self.subTest(position=position), self.assertRaisesRegex(RoadRouteError, "^INVALID_REQUEST$"):
                RoadRouteRequest(position, (127, 37))

    def test_request_enum_count_and_angle_bounds(self):
        for options in ({"waypoints": [(127, 37)] * 6}, {"priority": "TIME&summary=true"},
                        {"priority": []}, {"avoid": ["toll", "toll"]}, {"avoid": ["unknown"]},
                        {"avoid": "toll"}, {"origin_angle": -1}, {"origin_angle": 361},
                        {"origin_angle": True}, {"origin_angle": 90.0}):
            with self.subTest(options=options), self.assertRaisesRegex(RoadRouteError, "^INVALID_REQUEST$"):
                route_request(**options)

    def test_response_preserves_road_times_without_total_eta_redistribution(self):
        response = decode_road_route(raw(fixture()), route_request())
        self.assertEqual(response.sections[0].roads[0].duration_ms, 8000)
        self.assertEqual(response.sections[0].roads[1].duration_ms, 12000)
        self.assertEqual(response.total_duration_ms, 20000)
        self.assertEqual(response.sections[0].roads[1].coordinates, ((127.001, 37.0), (127.002, 37.0)))
        self.assertEqual(response.source_mode, "AUXILIARY")
        metadata = response.safe_metadata()
        self.assertTrue(metadata["timingTotalsMatch"])
        self.assertTrue(metadata["distanceTotalsMatch"])
        for field in ("storedGeometry", "productEnabled", "activePleosRouteVerified", "collectionEvidence"):
            self.assertFalse(metadata[field])
        self.assertNotIn("synthetic", json.dumps(metadata))
        self.assertNotIn("127", json.dumps(metadata))
        for value in (response, response.sections[0], response.sections[0].roads[0]):
            self.assertNotIn("127", repr(value))

    def test_waypoint_sections_stay_ordered_and_separate(self):
        document = fixture()
        selected = document["routes"][0]
        roads = selected["sections"][0]["roads"]
        selected["summary"]["waypoints"] = [{"x": 127.001, "y": 37}]
        selected["sections"] = [{"distance": 90, "duration": 8, "roads": roads[:1]},
                                 {"distance": 90, "duration": 12, "roads": roads[1:]}]
        response = decode_road_route(raw(document), route_request(waypoints=[(127.001, 37)]))
        self.assertEqual(len(response.sections), 2)
        self.assertEqual(response.provider_waypoints, ((127.001, 37),))
        self.assertEqual(response.sections[1].roads[0].duration_ms, 12000)

    def test_zero_road_duration_is_not_replaced_with_a_guessed_time(self):
        document = fixture()
        document["routes"][0]["sections"][0]["roads"][0]["duration"] = 0
        response = decode_road_route(raw(document), route_request())
        self.assertEqual(response.sections[0].roads[0].duration_ms, 0)
        self.assertEqual(response.safe_metadata()["zeroDurationRoadCount"], 1)
        self.assertFalse(response.safe_metadata()["timingTotalsMatch"])
        self.assertEqual(response.total_duration_ms, 20000)

    def test_aggregate_rounding_differences_are_visible_without_adjusting_links(self):
        document = fixture()
        document["routes"][0]["summary"].update(distance=181, duration=21)
        response = decode_road_route(raw(document), route_request())
        self.assertFalse(response.safe_metadata()["timingTotalsMatch"])
        self.assertFalse(response.safe_metadata()["distanceTotalsMatch"])
        self.assertEqual(response.sections[0].duration_ms, 20000)
        self.assertEqual(response.sections[0].roads[0].duration_ms, 8000)

    def test_optional_provider_details_are_not_retained(self):
        document = fixture()
        document["routes"][0]["sections"][0]["roads"][0].update(traffic_speed=12.4, traffic_state=1,
            road_details={"private": "do-not-retain"})
        response = decode_road_route(raw(document), route_request())
        self.assertFalse(hasattr(response.sections[0].roads[0], "road_details"))

    def test_bad_status_is_not_success_or_raw_message(self):
        document = fixture()
        document["routes"][0].update(result_code=104, result_msg="secret-upstream")
        with self.assertRaisesRegex(RoadRouteError, "^UPSTREAM_ERROR$"):
            decode_road_route(raw(document), route_request())
        document["routes"][0]["result_code"] = False
        with self.assertRaisesRegex(RoadRouteError, "^INVALID_RESPONSE$"):
            decode_road_route(raw(document), route_request())

    def test_alternative_priority_waypoint_and_section_mismatch_fail_closed(self):
        mutations = [lambda d: d["routes"].append(deepcopy(d["routes"][0])),
                     lambda d: d["routes"][0]["summary"].update(priority="TIME"),
                     lambda d: d["routes"][0]["summary"].update(waypoints=[{"x": 127, "y": 37}]),
                     lambda d: d["routes"][0].update(sections=[]),
                     lambda d: d["routes"][0]["sections"].append(deepcopy(d["routes"][0]["sections"][0]))]
        for mutate in mutations:
            document = fixture()
            mutate(document)
            with self.assertRaisesRegex(RoadRouteError, "^INVALID_RESPONSE$"):
                decode_road_route(raw(document), route_request())

    def test_duration_and_distance_types_limits_and_negative_values(self):
        for field in ("distance", "duration"):
            for value in (-1, True, "10", 10.0, 10**100):
                document = fixture()
                document["routes"][0]["sections"][0]["roads"][0][field] = value
                with self.subTest(field=field, value=value), self.assertRaisesRegex(RoadRouteError, "^INVALID_RESPONSE$"):
                    decode_road_route(raw(document), route_request())
        for field in ("distance", "duration"):
            document = fixture()
            document["routes"][0]["summary"][field] = 0
            with self.assertRaises(RoadRouteError):
                decode_road_route(raw(document), route_request())

    def test_invalid_vertexes_reject_without_reprojecting_or_repairing(self):
        for vertexes in ([], [127, 37], [127, 37, 127.001], [37, 127, 37, 127.001],
                         [127, 37, True, 37], [127, 37, float("nan"), 37], [127, 37, 127, 37],
                         [127, 37, 10**400, 37], "secret"):
            document = fixture()
            document["routes"][0]["sections"][0]["roads"][0]["vertexes"] = vertexes
            with self.subTest(vertexes=vertexes), self.assertRaises(RoadRouteError):
                decode_road_route(raw(document), route_request())

    def test_disconnected_roads_are_not_joined_by_a_straight_line(self):
        document = fixture()
        document["routes"][0]["sections"][0]["roads"][1]["vertexes"][0] = 127.00101
        with self.assertRaisesRegex(RoadRouteError, "^DISCONTINUOUS_GEOMETRY$"):
            decode_road_route(raw(document), route_request())

    def test_malformed_duplicate_nonfinite_deep_or_oversize_response_is_sanitized(self):
        for body in (b'{"routes":[],"routes":[]}', b'{"private":Infinity}', b'secret', b'\xff',
                     b'[' * 1100 + b'0' + b']' * 1100):
            with self.assertRaisesRegex(RoadRouteError, "^INVALID_RESPONSE$"):
                decode_road_route(body, route_request())
        with self.assertRaisesRegex(RoadRouteError, "^RESPONSE_TOO_LARGE$"):
            decode_road_route(raw(fixture()), route_request(), maximum_bytes=10)

    def test_missing_geometry_is_not_accepted_as_summary_only(self):
        for mutate in (lambda d: d["routes"][0]["sections"][0].pop("roads"),
                       lambda d: d["routes"][0]["sections"][0]["roads"][0].pop("duration"),
                       lambda d: d["routes"][0]["sections"][0]["roads"][0].pop("vertexes")):
            document = fixture()
            mutate(document)
            with self.assertRaisesRegex(RoadRouteError, "^INVALID_RESPONSE$"):
                decode_road_route(raw(document), route_request())

    def test_point_and_road_limits_apply_to_all_sections(self):
        with patch("petasos_server.road_routes.MAX_POINTS", 3), self.assertRaisesRegex(RoadRouteError, "^GEOMETRY_LIMIT$"):
            decode_road_route(raw(fixture()), route_request())
        with patch("petasos_server.road_routes.MAX_ROADS", 1), self.assertRaisesRegex(RoadRouteError, "^GEOMETRY_LIMIT$"):
            decode_road_route(raw(fixture()), route_request())

    def test_one_bounded_request_without_retry_and_response_closed(self):
        reply = Reply(raw(fixture()))
        opener = Opener(reply)
        response = KakaoRoadRouteClient("secret", opener=opener).lookup(route_request())
        self.assertEqual(response.source_mode, "AUXILIARY")
        self.assertEqual(len(opener.calls), 1)
        self.assertEqual(reply.read_limit, MAX_RESPONSE_BYTES + 1)
        self.assertTrue(reply.closed)

    def test_response_limit_is_enforced_after_bounded_read(self):
        reply = Reply(b" " * 101)
        with self.assertRaisesRegex(RoadRouteError, "^RESPONSE_TOO_LARGE$"):
            KakaoRoadRouteClient("secret", maximum_bytes=100, opener=Opener(reply)).lookup(route_request())
        self.assertEqual(reply.read_limit, 101)
        self.assertTrue(reply.closed)

    def test_network_http_and_redirect_errors_are_fixed_codes_only(self):
        for error in (URLError("secret-url"), TimeoutError("secret-url"),
                      HTTPError("https://secret.invalid", 302, "secret-message", {}, BytesIO(b"secret-body"))):
            opener = Opener(error)
            with self.assertRaises(RoadRouteError) as raised:
                KakaoRoadRouteClient("secret", opener=opener).lookup(route_request())
            self.assertNotIn("secret", str(raised.exception))
            self.assertEqual(len(opener.calls), 1)
        reply = Reply(b"secret-body")
        reply.status = 429
        with self.assertRaisesRegex(RoadRouteError, "^HTTP_ERROR$"):
            KakaoRoadRouteClient("secret", opener=Opener(reply)).lookup(route_request())
        self.assertIsNone(reply.read_limit)

    def test_default_transport_disables_redirects_and_environment_proxies(self):
        client = KakaoRoadRouteClient("secret")
        redirect = [handler for handler in client._opener.handlers if isinstance(handler, _NoRedirect)]
        self.assertEqual(len(redirect), 1)
        self.assertIsNone(redirect[0].redirect_request(None, None, 302, "secret", {}, "https://evil.invalid"))
        self.assertFalse(any(handler.__class__.__name__ == "ProxyHandler" and handler.proxies
                             for handler in client._opener.handlers))

    def test_configuration_and_unknown_error_codes_do_not_echo_inputs(self):
        for key in ("", "secret\nheader", "secret value", "한글", None):
            with self.assertRaisesRegex(RoadRouteError, "^INVALID_CONFIGURATION$"):
                KakaoRoadRouteClient(key)
        for kwargs in ({"timeout": 0}, {"timeout": True}, {"timeout": float("nan")}, {"timeout": 10**400},
                       {"maximum_bytes": True}, {"maximum_bytes": MAX_RESPONSE_BYTES + 1}):
            with self.assertRaisesRegex(RoadRouteError, "^INVALID_CONFIGURATION$"):
                KakaoRoadRouteClient("secret", **kwargs)
        self.assertEqual(str(RoadRouteError("secret-upstream")), "INVALID_RESPONSE")


if __name__ == "__main__":
    unittest.main()
