import importlib.util
import json
from pathlib import Path
import unittest
from urllib.parse import parse_qs, urlsplit

spec = importlib.util.spec_from_file_location("live_api_setup", Path(__file__).resolve().parents[1] / "check-live-apis.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class LiveApiSetupTests(unittest.TestCase):
    def test_missing_or_presence_check_never_calls_network(self):
        def no_network(*args):
            self.fail("No network expected")
        self.assertEqual(probe.inspect({}, ["openrouter"], True, no_network)[0]["status"], "MISSING")
        self.assertEqual(probe.inspect({"PETASOS_OPENROUTER_API_KEY": "private"}, ["openrouter"], False, no_network)[0]["status"], "SET")

    def test_key_plus_slash_and_encoded_portal_key_are_encoded_once(self):
        for key in ["key+/=", "key%2B%2F%3D"]:
            query = parse_qs(urlsplit(probe.public_url("odii", key)).query)
            self.assertEqual(query["serviceKey"], ["key+/="])
            self.assertEqual(query["langCode"], ["ko"])
            self.assertEqual(query["numOfRows"], ["1"])

    def test_public_service_override_and_common_fallback(self):
        urls = []
        def fetch(url):
            urls.append(url)
            return 200, b'{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[{}]}}}}', None
        result = probe.inspect({"PETASOS_DATA_GO_KR_SERVICE_KEY": "shared", "PETASOS_ODII_SERVICE_KEY": "override"}, ["odii", "tourapi"], True, fetch)
        self.assertEqual([parse_qs(urlsplit(u).query)["serviceKey"][0] for u in urls], ["override", "shared"])
        self.assertEqual([r["status"] for r in result], ["PASS", "PASS"])

    def test_openrouter_does_not_output_labels_ids_or_key(self):
        secret = "synthetic-private-key"
        def fetch(url, headers):
            self.assertEqual(url, "https://openrouter.ai/api/v1/key")
            self.assertEqual(headers["Authorization"], "Bearer " + secret)
            return 200, json.dumps({"data": {"label": secret, "workspace_id": secret, "limit_remaining": 0}}).encode(), None
        result = probe.inspect({"PETASOS_OPENROUTER_API_KEY": secret}, ["openrouter"], True, fetch)
        self.assertNotIn(secret, json.dumps(result))
        self.assertEqual(result[0]["scope"], "authentication_only_not_model_or_privacy_validation")

    def test_management_key_not_generation_key(self):
        self.assertEqual(probe.openrouter_result(b'{"data":{"is_management_key":true}}')["status"], "GENERATION_KEY_REQUIRED")

    def test_public_error_json_and_gateway_xml_are_not_http_success(self):
        for raw in [b'{"response":{"header":{"resultCode":"30","resultMsg":"SECRET"}}}',
                    b'<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode><errMsg>SECRET</errMsg></cmmMsgHeader></OpenAPI_ServiceResponse>']:
            self.assertEqual(probe.public_result(raw), {"status": "API_ERROR", "resultCode": "30"})

    def test_xml_success_count_and_empty_data(self):
        self.assertEqual(probe.public_result(b'<response><header><resultCode>00</resultCode></header><body><items><item><title>PRIVATE</title></item></items></body></response>'),
                         {"status": "PASS", "resultCode": "00", "receivedItems": 1})
        self.assertEqual(probe.public_result(b'{"response":{"header":{"resultCode":"00"},"body":{"items":""}}}')["status"], "NO_DATA")

    def test_current_tourism_gateway_four_digit_code_requires_data(self):
        self.assertEqual(probe.public_result(b'{"response":{"header":{"resultCode":"0000"},"body":{"items":{"item":[{}]}}}}'),
                         {"status": "PASS", "resultCode": "0000", "receivedItems": 1})
        self.assertEqual(probe.public_result(b'<response><header><resultCode>0000</resultCode></header><body><items/></body></response>')["status"], "NO_DATA")
        self.assertEqual(probe.public_result(b'{"response":{"header":{"resultCode":"0003"}}}'),
                         {"status": "API_ERROR", "resultCode": "0003"})
        for code in [0, "0", "000", "00000"]:
            self.assertEqual(probe.public_result(json.dumps({"response":{"header":{"resultCode":code}}}).encode())["status"], "INVALID_RESPONSE")

    def test_malformed_and_entity_payloads_are_sanitized(self):
        for raw in [b'SECRET', b'{"response":{"header":{"resultCode":"SECRET"}}}',
                    b'<!DOCTYPE x [<!ENTITY y "SECRET">]><response/>']:
            self.assertEqual(probe.public_result(raw), {"status": "INVALID_RESPONSE"})


if __name__ == "__main__":
    unittest.main()
