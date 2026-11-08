"""Synthetic protocol fixtures; these are not real district geometry or an API success claim."""
from copy import deepcopy
from io import BytesIO
import json
import unittest
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit

from petasos_server.region_boundaries import BoundaryApiError, MAX_RESPONSE_BYTES, VWorldBoundaryClient, decode_boundary


def fixture():
    return {"response": {"status": "OK", "record": {"total": "1", "current": "1"},
                         "page": {"total": "1", "current": "1", "size": "2"},
                         "result": {"featureCollection": {"type": "FeatureCollection",
                           "crs": {"type": "name", "properties": {"name": "EPSG:4326"}},
                           "features": [{"type": "Feature", "properties": {"sig_cd": "11650", "sig_kor_nm": "시험"},
                                         "geometry": {"type": "Polygon", "coordinates": [
                                             [[127, 37], [127.1, 37], [127.1, 37.1], [127, 37]]
                                         ]}}]}}}}


def raw(document):
    return json.dumps(document, ensure_ascii=False).encode()


class Reply:
    status = 200
    def __init__(self, data):
        self.data = data
        self.read_limit = None
    def __enter__(self):
        return self
    def __exit__(self, *args):
        pass
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


class BoundaryApiTests(unittest.TestCase):
    def test_exact_query_separate_key_crs_and_no_coordinates(self):
        client = VWorldBoundaryClient("secret&value", "http://localhost:8443")
        request = client.prepare_request("11650")
        url = urlsplit(request.full_url)
        self.assertEqual((url.scheme, url.netloc, url.path), ("https", "api.vworld.kr", "/req/data"))
        query = parse_qs(url.query)
        self.assertEqual(query["key"], ["secret&value"])
        self.assertEqual(query["domain"], ["http://localhost:8443"])
        self.assertEqual(query["attrFilter"], ["sig_cd:=:11650"])
        self.assertEqual(query["crs"], ["EPSG:4326"])
        self.assertEqual(query["geometry"], ["true"])
        self.assertEqual(query["size"], ["2"])
        self.assertNotIn("geomFilter", query)
        self.assertNotIn("secret", repr(client))

    def test_invalid_codes_cannot_inject_filters(self):
        client = VWorldBoundaryClient("secret", "http://localhost")
        for code in ("", 11650, "11650|1", "11650&key=other", "116500", "１１６５０"):
            with self.subTest(code=code), self.assertRaisesRegex(BoundaryApiError, "^INVALID_REGION_CODE$"):
                client.prepare_request(code)

    def test_configuration_rejects_credentials_in_domain_and_bad_limits(self):
        for domain in (123, "http://user:secret@localhost", "file:///tmp", "https://localhost?key=secret",
                       "http://localhost:bad", "http://localhost/#secret", "http://local host"):
            with self.subTest(domain=domain), self.assertRaisesRegex(BoundaryApiError, "^INVALID_CONFIGURATION$"):
                VWorldBoundaryClient("secret", domain)
        for arguments in ({"timeout": True}, {"timeout": float("nan")}, {"timeout": 0}, {"timeout": 10**400},
                          {"maximum_bytes": MAX_RESPONSE_BYTES + 1}, {"maximum_bytes": True}):
            with self.subTest(arguments=arguments), self.assertRaises(BoundaryApiError):
                VWorldBoundaryClient("secret", "https://localhost", **arguments)

    def test_optional_domain_is_omitted_without_inventing_a_registered_url(self):
        for domain in (None, ""):
            client = VWorldBoundaryClient("secret", domain)
            query = parse_qs(urlsplit(client.prepare_request("11650").full_url).query)
            self.assertNotIn("domain", query)

    def test_polygon_normalized_to_immutable_redacted_response(self):
        document = fixture()
        result = decode_boundary(raw(document), "11650")
        self.assertEqual(result.code, "11650")
        self.assertEqual(result.polygons[0][0][1], (127.1, 37))
        self.assertEqual(result.safe_metadata()["pointCount"], 4)
        self.assertFalse(result.safe_metadata()["storedGeometry"])
        self.assertNotIn("시험", repr(result))
        self.assertNotIn("127", repr(result))
        with self.assertRaises(TypeError):
            result.polygons[0][0][0] = (0, 0)

    def test_multipart_and_holes_preserved_without_flattening(self):
        document = fixture()
        geometry = document["response"]["result"]["featureCollection"]["features"][0]["geometry"]
        shell = geometry["coordinates"][0]
        hole = [[127.02, 37.01], [127.03, 37.01], [127.03, 37.02], [127.02, 37.01]]
        island = [[128, 38], [128.1, 38], [128.1, 38.1], [128, 38]]
        geometry.update(type="MultiPolygon", coordinates=[[shell, hole], [island]])
        result = decode_boundary(raw(document), "11650")
        self.assertEqual(result.polygons[0][1][0], (127.02, 37.01))
        self.assertEqual(result.safe_metadata()["polygonCount"], 2)
        self.assertEqual(result.safe_metadata()["ringCount"], 3)

    def test_source_error_and_no_data_are_not_successful_empty_geometry(self):
        for status, code in (("ERROR", "UPSTREAM_ERROR"), ("NOT_FOUND", "REGION_NOT_FOUND"), ("weird", "INVALID_RESPONSE")):
            with self.subTest(status=status), self.assertRaisesRegex(BoundaryApiError, "^" + code + "$"):
                decode_boundary(raw({"response": {"status": status, "error": {"text": "secret"}}}), "11650")

    def test_wrong_region_or_truncated_pagination_fails_closed(self):
        mutations = [lambda r: r["record"].update(total="2"), lambda r: r["page"].update(total="2"),
                     lambda r: r["page"].update(current="2"), lambda r: r["record"].update(current="0"),
                     lambda r: r["result"]["featureCollection"]["features"][0]["properties"].update(sig_cd="11110"),
                     lambda r: r["result"]["featureCollection"]["features"].append(
                         deepcopy(r["result"]["featureCollection"]["features"][0]))]
        for mutate in mutations:
            document = fixture()
            mutate(document["response"])
            with self.assertRaises(BoundaryApiError):
                decode_boundary(raw(document), "11650")

    def test_wrong_coordinate_system_not_reprojected_or_assumed(self):
        document = fixture()
        document["response"]["result"]["featureCollection"]["crs"]["properties"]["name"] = "EPSG:900913"
        with self.assertRaisesRegex(BoundaryApiError, "^UNSUPPORTED_CRS$"):
            decode_boundary(raw(document), "11650")

    def test_unclosed_nonfinite_wrong_axis_and_degenerate_geometry_rejected(self):
        geometries = [
            {"type": "Point", "coordinates": [127, 37]},
            {"type": "Polygon", "coordinates": []},
            {"type": "Polygon", "coordinates": [[[127, 37], [128, 37], [128, 38], [127, 38]]]},
            {"type": "Polygon", "coordinates": [[[37, 127], [38, 127], [38, 128], [37, 127]]]},
            {"type": "Polygon", "coordinates": [[[127, 37], [127, 37], [127, 38], [127, 37]]]},
            {"type": "Polygon", "coordinates": [[[127, 37], [True, 37], [128, 38], [127, 37]]]},
            {"type": "Polygon", "coordinates": [[[127, 37], [float("nan"), 37], [128, 38], [127, 37]]]},
        ]
        for geometry in geometries:
            document = fixture()
            document["response"]["result"]["featureCollection"]["features"][0]["geometry"] = geometry
            with self.assertRaises(BoundaryApiError):
                decode_boundary(raw(document), "11650")

    def test_malformed_json_and_resource_limits_are_sanitized(self):
        for body in (b' {"response":{},"response":{}}', b'{"secret":NaN}', b"secret-not-json", b"\xff",
                     b"[" * 1100 + b"0" + b"]" * 1100):
            with self.assertRaisesRegex(BoundaryApiError, "^INVALID_RESPONSE$"):
                decode_boundary(body, "11650")
        with self.assertRaisesRegex(BoundaryApiError, "^RESPONSE_TOO_LARGE$"):
            decode_boundary(raw(fixture()), "11650", maximum_bytes=10)

    def test_one_bounded_request_without_retry(self):
        reply = Reply(raw(fixture()))
        opener = Opener(reply)
        result = VWorldBoundaryClient("secret", "http://localhost", opener=opener).lookup("11650")
        self.assertEqual(result.code, "11650")
        self.assertEqual(len(opener.calls), 1)
        self.assertEqual(reply.read_limit, MAX_RESPONSE_BYTES + 1)

    def test_network_http_and_redirect_errors_never_disclose_query_or_body(self):
        errors = [URLError("secret-url"), TimeoutError("secret-url"),
                  HTTPError("https://api.vworld.kr/?key=secret", 302, "secret-text", {}, BytesIO(b"secret-body"))]
        for error in errors:
            opener = Opener(error)
            with self.assertRaises(BoundaryApiError) as raised:
                VWorldBoundaryClient("secret", "http://localhost", opener=opener).lookup("11650")
            self.assertNotIn("secret", str(raised.exception))
            self.assertEqual(len(opener.calls), 1)


if __name__ == "__main__":
    unittest.main()
