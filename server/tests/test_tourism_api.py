import io
import json
from pathlib import Path
import ssl
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import HTTPSHandler, HTTPRedirectHandler, ProxyHandler

from petasos_server.tourism_api import TourismApiError, TourismClient, decode_response


def envelope(items, *, code="0000", message="OK", count=1, rows=1, page=1):
    return json.dumps({"response": {"header": {"resultCode": code, "resultMsg": message},
                                   "body": {"items": items, "numOfRows": rows, "pageNo": page,
                                            "totalCount": count}}}, ensure_ascii=False).encode("utf-8")


def xml_envelope(items, *, code="0000", count=1, rows=1, page=1):
    return (f"<response><header><resultCode>{code}</resultCode><resultMsg>OK</resultMsg></header>"
            f"<body>{items}<numOfRows>{rows}</numOfRows><pageNo>{page}</pageNo>"
            f"<totalCount>{count}</totalCount></body></response>").encode()


class Reply:
    def __init__(self, raw, status=200):
        self.raw, self.status, self.read_sizes, self.closed = raw, status, [], False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.closed = True

    def read(self, size):
        self.read_sizes.append(size)
        return self.raw[:size]


class FakeOpener:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = []

    def open(self, request, timeout):
        self.calls.append((request, timeout))
        if not self.results:
            raise AssertionError("unexpected retry or automatic pagination")
        result = self.results.pop(0)
        if isinstance(result, BaseException):
            raise result
        return result


def captured_operation_fields():
    """Expected names come directly from the captured Swagger, independently of registry."""
    directory = Path(__file__).resolve().parents[1] / "petasos_server" / "reference"
    for service in ("odii", "tourapi"):
        document = json.loads((directory / f"{service}.openapi.json").read_text())

        def resolve(schema):
            while "$ref" in schema:
                target = document
                for component in schema["$ref"].split("/")[1:]:
                    target = target[component]
                schema = target
            return schema

        for path in document["paths"].values():
            definition = path["get"]
            schema = resolve(definition["responses"]["200"]["schema"])
            for name in ("body", "items", "item"):
                schema = resolve(schema["properties"][name])
            yield service, definition["operationId"], schema["properties"]


class TourismApiTests(unittest.TestCase):
    def test_all_23_operations_preserve_all_documented_json_and_xml_fields(self):
        definitions = list(captured_operation_fields())
        self.assertEqual(len(definitions), 23)
        self.assertEqual(sum(service == "odii" for service, _, _ in definitions), 8)
        for service, operation, fields in definitions:
            expected = {name: "0001" for name in fields}
            xml_fields = "".join(f"<{name}>0001</{name}>" for name in fields)
            for kind, raw in (("json", envelope({"item": expected})),
                              ("xml", xml_envelope(f"<items><item>{xml_fields}</item></items>"))):
                with self.subTest(service=service, operation=operation, response_format=kind):
                    result = decode_response(raw, service, operation)
                    self.assertEqual(result.items, (expected,))
                    self.assertEqual(result.response_format, kind)
                    self.assertEqual(result.schema_issues, ())
                    self.assertEqual(result.extra_field_count, 0)
                    self.assertEqual((result.num_of_rows, result.page_no, result.total_count), (1, 1, 1))

    def test_json_xml_multiple_and_empty_cardinality_and_pagination(self):
        expected = ({"contentid": "001"}, {"contentid": "002"})
        for raw in (envelope({"item": list(expected)}, count="20", rows="2", page="3"),
                    xml_envelope("<items><item><contentid>001</contentid></item><item><contentid>002</contentid></item></items>", count=20, rows=2, page=3)):
            result = decode_response(raw)
            self.assertEqual(result.items, expected)
            self.assertEqual((result.num_of_rows, result.page_no, result.total_count), (2, 3, 20))
        for container in (None, "", {}, {"item": None}, {"item": ""}, {"item": []}, {"item": {}}):
            with self.subTest(container=container):
                self.assertEqual(decode_response(envelope(container, count=0)).items, ())
        self.assertEqual(decode_response(xml_envelope("<items/>", count=0)).items, ())
        self.assertEqual(decode_response(xml_envelope("<items></items>", count=0)).items, ())

    def test_numeric_or_boolean_containers_and_nonobjects_never_mean_empty_success(self):
        for value in (False, True, 0, 1, 1.5, "bad", ["bad"]):
            for container in (value, {"item": value}):
                with self.subTest(container=container), self.assertRaisesRegex(TourismApiError, "^INVALID_RESPONSE$"):
                    decode_response(envelope(container, count=0))
        for raw in (b"null", b"[]", b"false", b'"string"', b"{}"): 
            with self.subTest(raw=raw), self.assertRaisesRegex(TourismApiError, "^INVALID_RESPONSE$"):
                decode_response(raw)

    def test_unknown_fields_retained_and_null_numeric_type_drift_reported_without_coercion(self):
        item = {"contentid": 123, "title": None, "overview": "text", "futureField": {"nested": ["data", 7]}}
        result = decode_response(envelope({"item": [item, item]}, count=2, rows=2), "tourapi", "detailCommon2")
        self.assertEqual(result.items, (item, item))
        self.assertEqual(result.extra_field_count, 2)
        self.assertEqual(result.schema_issues, ("item.contentid:TYPE_MISMATCH", "item.title:TYPE_MISMATCH"))
        self.assertEqual(result.safe_metadata()["schemaIssueCount"], 2)
        self.assertEqual(result.to_document()["response"]["body"]["items"]["item"], [item, item])

    def test_safe_representations_never_include_keys_messages_urls_or_record_text(self):
        key = "private+token/abc="
        client = TourismClient("odii", key, opener=FakeOpener())
        request = client.prepare_request("storySearchList", {"keyword": "private_query_text"})
        result = decode_response(envelope({"item": {"title": "private_record_text"}}, message="private_result_message"))
        printable = repr(client) + repr(request) + repr(result) + json.dumps(result.safe_metadata())
        for secret in (key, "private_query_text", "private_record_text", "private_result_message", "serviceKey", "apis.data.go.kr"):
            self.assertNotIn(secret, printable)
        self.assertEqual(result.result_message, "private_result_message")
        self.assertEqual(result.to_document()["response"]["header"]["resultMsg"], "private_result_message")

    def test_service_key_encoding_once_and_literal_plus_keyword_preserved(self):
        for key in ("a+b/c=", "a%2Bb%2Fc%3D"):
            request = TourismClient("odii", key, opener=FakeOpener()).prepare_request("storySearchList", {"keyword": "경복궁 + %2B"})
            url = urlsplit(request.full_url)
            parameters = parse_qs(url.query)
            self.assertEqual((url.scheme, url.netloc, url.path), ("https", "apis.data.go.kr", "/B551011/Odii/storySearchList"))
            self.assertEqual(parameters["serviceKey"], ["a+b/c="])
            self.assertEqual(parameters["keyword"], ["경복궁 + %2B"])
            self.assertEqual(parameters["langCode"], ["ko"])
            self.assertEqual(parameters["_type"], ["json"])
        request = TourismClient("odii", "a%252Bb", opener=FakeOpener()).prepare_request("storyBasedList")
        self.assertEqual(parse_qs(urlsplit(request.full_url).query)["serviceKey"], ["a%2Bb"])

    def test_body_timeout_paging_and_row_limits_are_local_and_no_retry(self):
        response = Reply(envelope({"item": {"title": "one"}}, count=100, rows=1))
        opener = FakeOpener(response)
        client = TourismClient("odii", "key", opener=opener, timeout=4, max_rows=2, max_response_bytes=1024)
        result = client.request("storyBasedList", {"numOfRows": 1})
        self.assertEqual(len(result.items), 1)
        self.assertEqual(len(opener.calls), 1)
        self.assertEqual(opener.calls[0][1], 4)
        self.assertEqual(response.read_sizes, [1025])
        self.assertTrue(response.closed)
        with self.assertRaisesRegex(TourismApiError, "^CLIENT_ROW_LIMIT$"):
            client.request("storyBasedList", {"numOfRows": 3})
        self.assertEqual(len(opener.calls), 1)
        with self.assertRaisesRegex(TourismApiError, "^CLIENT_ROW_LIMIT$"):
            TourismClient("odii", "key", opener=FakeOpener(Reply(envelope({"item": [{"title": "one"}, {"title": "two"}]}))), max_rows=1).request("storyBasedList")
        with self.assertRaisesRegex(TourismApiError, "^RESPONSE_TOO_LARGE$"):
            TourismClient("odii", "key", opener=FakeOpener(Reply(b"x" * 101)), max_response_bytes=100).request("storyBasedList")

    def test_integral_numeric_query_representations_do_not_raise_untyped_value_error(self):
        client = TourismClient("odii", "key", opener=FakeOpener(), max_rows=10)
        for value in (1, 1.0, "1.0", "1e1", "+1"):
            with self.subTest(value=value):
                request = client.prepare_request("storyBasedList", {"numOfRows": value})
                self.assertIn("numOfRows", parse_qs(urlsplit(request.full_url).query))
        with self.assertRaisesRegex(TourismApiError, "^CLIENT_ROW_LIMIT$"):
            client.prepare_request("storyBasedList", {"numOfRows": "1e10"})

    def test_http_gateway_error_preserves_numeric_reason_and_http_status_only(self):
        message = b"<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>private-key-and-url</errMsg><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>"
        stream = io.BytesIO(message)
        opener = FakeOpener(HTTPError("https://private-url?serviceKey=private-key", 403, "private-error", {}, stream))
        with self.assertRaises(TourismApiError) as raised:
            TourismClient("odii", "private-key", opener=opener).request("storyBasedList")
        self.assertEqual((raised.exception.code, raised.exception.result_code, raised.exception.http_status), ("HTTP_ERROR", "30", 403))
        self.assertNotIn("private", repr(raised.exception))
        self.assertEqual(len(opener.calls), 1)
        self.assertTrue(stream.closed)

    def test_http_200_business_errors_json_xml_and_success_codes(self):
        for raw, code in ((envelope(None, code="31"), "31"), (xml_envelope("<items/>", code="0031"), "0031")):
            with self.subTest(code=code), self.assertRaises(TourismApiError) as raised:
                TourismClient("odii", "key", opener=FakeOpener(Reply(raw))).request("storyBasedList")
            self.assertEqual((raised.exception.code, raised.exception.result_code), ("API_ERROR", code))
        for code in ("00", "0000"):
            self.assertEqual(decode_response(envelope(None, code=code, count=0)).result_code, code)
        for code in ("0", "000", "00000", 0, False, None, "private"):
            with self.subTest(code=code), self.assertRaisesRegex(TourismApiError, "^INVALID_RESPONSE$"):
                decode_response(envelope(None, code=code, count=0))

    def test_invalid_xml_entities_duplicate_fields_and_json_constants_are_rejected(self):
        raws = [b"\xff", b"<response>", b"<unexpected/>",
                b"<!DOCTYPE response [<!ENTITY x SYSTEM 'file:///private/key'>]><response>&x;</response>",
                b'<response unsafe="value"/>', b'{"response":{},"response":{}}',
                envelope({"item": {"title": float("nan")}}), envelope({"item": {"title": float("inf")}}),
                b'{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":{"title":"\\ud800"}}}}}',
                xml_envelope("<items><item><title>a</title><title>b</title></item></items>"),
                b"<response><header><resultCode>00</resultCode><resultCode>00</resultCode></header><body><items/></body></response>"]
        for index, raw in enumerate(raws):
            with self.subTest(index=index), self.assertRaisesRegex(TourismApiError, "^INVALID_RESPONSE$"):
                decode_response(raw)

    def test_pagination_is_finite_nonnegative_integer_and_preserves_large_safe_counts(self):
        for value in (False, True, -1, 1.0, "1.5", "1e3", "-1", "private", [], {}):
            with self.subTest(value=value), self.assertRaisesRegex(TourismApiError, "^INVALID_RESPONSE$"):
                decode_response(envelope(None, count=value))
        result = decode_response(envelope(None, count=None, rows=None, page=None))
        self.assertEqual((result.num_of_rows, result.page_no, result.total_count), (None, None, None))
        for raw in (envelope({"item": {"title": "one"}}, count=10**18),
                    xml_envelope("<items><item><title>one</title></item></items>", count=10**18)):
            self.assertEqual(decode_response(raw).total_count, 10**18)

    def test_network_errors_and_redirects_are_sanitized_without_retry(self):
        for error in (URLError("private-key"), TimeoutError("private-key"), OSError("private-key"),
                      HTTPError("https://private?key=secret", 302, "private-key", {"Location": "https://attacker.test"}, io.BytesIO(b"private-body"))):
            opener = FakeOpener(error)
            with self.subTest(error=type(error).__name__), self.assertRaises(TourismApiError) as raised:
                TourismClient("odii", "private-key", opener=opener).request("storyBasedList")
            self.assertNotIn("private", repr(raised.exception))
            self.assertEqual(len(opener.calls), 1)

    def test_default_transport_has_no_proxy_redirect_and_verified_tls(self):
        with patch("petasos_server.tourism_api.build_opener") as build:
            TourismClient("odii", "key")
        handlers = build.call_args.args
        proxy = next(handler for handler in handlers if isinstance(handler, ProxyHandler))
        tls = next(handler for handler in handlers if isinstance(handler, HTTPSHandler))
        redirect = next(handler for handler in handlers if isinstance(handler, HTTPRedirectHandler))
        self.assertEqual(proxy.proxies, {})
        self.assertEqual(tls._context.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(tls._context.check_hostname)
        self.assertIsNone(redirect.redirect_request(None, None, 302, "message", {}, "https://attacker.test"))

    def test_invalid_limits_service_key_and_query_fail_before_network(self):
        for kwargs in ({"timeout": True}, {"timeout": 0}, {"timeout": float("nan")}, {"max_rows": 0}, {"max_rows": True},
                       {"max_response_bytes": 0}, {"max_response_bytes": False}):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(TourismApiError, "^INVALID_CLIENT_LIMIT$"):
                TourismClient("odii", "key", opener=FakeOpener(), **kwargs)
        for key in ("", "bad\nkey", "bad%0Akey", None):
            with self.subTest(key=key), self.assertRaisesRegex(TourismApiError, "^INVALID_SERVICE_KEY$"):
                TourismClient("odii", key, opener=FakeOpener())
        opener = FakeOpener()
        client = TourismClient("odii", "key", opener=opener)
        for operation, parameters, format_ in (("https://attacker.test", None, "json"),
                                               ("storyBasedList", {"serviceKey": "override"}, "json"),
                                               ("storyBasedList", {"_type": "xml"}, "json"),
                                               ("storyBasedList", {"unknown": "private"}, "json"),
                                               ("storyBasedList", None, "html")):
            with self.subTest(operation=operation, parameters=parameters), self.assertRaises(TourismApiError):
                client.request(operation, parameters, format_)
        self.assertEqual(opener.calls, [])


if __name__ == "__main__":
    unittest.main()
