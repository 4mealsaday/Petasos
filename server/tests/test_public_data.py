import hashlib
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

from petasos_server.public_data import (
    ENDPOINTS, MAX_RESPONSE_BYTES, PublicDataError, collect_candidates, decode_records,
    get_records, normalize_record, request_url, write_private_candidates,
)
from petasos_server.tourism_api import TourismApiError

NOW = "2026-11-07T00:00:00Z"
ODII = {"tid": "1", "tlid": "2", "stid": "3", "stlid": "4", "title": "공개 이야기",
        "audioTitle": "이야기 제목", "script": "제공자 본문 <br/> 원문을 데이터로 보존합니다.",
        "langCode": "ko", "mapX": "126.978", "mapY": "37.566"}
TOUR = {"contentid": "1234", "contenttypeid": "12", "title": "공개 장소", "overview": "공개 장소 설명",
        "mapx": "127.1", "mapy": "36.2", "lDongRegnCd": "11", "lDongSignguCd": "110",
        "lclsSystm1": "A01", "lclsSystm2": "A0101", "lclsSystm3": "A010101"}


def response(row=None, code="0000"):
    return json.dumps({"response": {"header": {"resultCode": code, "resultMsg": "untrusted secret"},
                                    "body": {"items": {"item": [row] if row is not None else []}}}}).encode()


class PublicDataTests(unittest.TestCase):
    def test_decoding_gateway_success_codes_only_and_single_row(self):
        for code in ("00", "0000"):
            self.assertEqual(decode_records(response(ODII, code)), [ODII])
        self.assertEqual(decode_records(response()), [])
        body = json.loads(response(ODII))
        body["response"]["body"]["items"]["item"] = ODII
        self.assertEqual(decode_records(json.dumps(body).encode()), [ODII])
        for code in ("0", "000", "00000", 0, "secret"):
            with self.subTest(code=code), self.assertRaisesRegex(PublicDataError, "^INVALID_RESPONSE$"):
                decode_records(response(ODII, code))

    def test_json_xml_gateway_errors_return_only_numeric_codes(self):
        inputs = [response(code="30"), b"<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>secret-key</errMsg>"
                  b"<returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>",
                  b"<response><header><resultCode>0030</resultCode><resultMsg>secret</resultMsg></header></response>"]
        for raw in inputs:
            with self.subTest(raw=raw), self.assertRaises(PublicDataError) as raised:
                decode_records(raw)
            self.assertEqual(raised.exception.code, "API_ERROR")
            self.assertIn(raised.exception.result_code, {"30", "0030"})
            self.assertNotIn("secret", str(raised.exception))

    def test_malformed_duplicate_excessive_xml_entity_and_large_response_rejected(self):
        inputs = [b"{", b"\xff", b'{"response":{},"response":{}}', b'{"response":NaN}',
                  b"<!DOCTYPE x [<!ENTITY y 'secret'>]><response>&y;</response>",
                  b'{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[{},{}]}}}}']
        for raw in inputs:
            with self.subTest(raw=raw), self.assertRaisesRegex(PublicDataError, "^INVALID_RESPONSE$"):
                decode_records(raw)
        with self.assertRaisesRegex(PublicDataError, "^RESPONSE_TOO_LARGE$"):
            decode_records(b" " * (MAX_RESPONSE_BYTES + 1))

    def test_xml_success_and_empty_are_supported_but_sampler_rejects_multiple_rows(self):
        prefix = b"<response><header><resultCode>0000</resultCode><resultMsg>OK</resultMsg></header><body>"
        suffix = b"<numOfRows>1</numOfRows><pageNo>1</pageNo><totalCount>1</totalCount></body></response>"
        one = b"<item><contentid>001234</contentid><title>test</title></item>"
        self.assertEqual(decode_records(prefix + b"<items>" + one + b"</items>" + suffix), [{"contentid": "001234", "title": "test"}])
        self.assertEqual(decode_records(prefix + b"<items/>" + suffix), [])
        with self.assertRaisesRegex(PublicDataError, "^INVALID_RESPONSE$"):
            decode_records(prefix + b"<items>" + one + one + b"</items>" + suffix)

    def test_false_numeric_item_containers_are_not_empty_success(self):
        for invalid in (False, True, 0, 1):
            for location in ("items", "item"):
                document = json.loads(response())
                if location == "items":
                    document["response"]["body"]["items"] = invalid
                else:
                    document["response"]["body"]["items"]["item"] = invalid
                with self.subTest(value=invalid, location=location), self.assertRaisesRegex(PublicDataError, "^INVALID_RESPONSE$"):
                    decode_records(json.dumps(document).encode())

    def test_keys_are_decoded_once_literal_plus_preserved_and_detail_id_validated(self):
        for key in ("a+b/c=", "a%2Bb%2Fc%3D"):
            url = request_url("odii", key)
            parts = urlsplit(url)
            self.assertEqual(parts.scheme, "https")
            self.assertEqual(parts.netloc, "apis.data.go.kr")
            self.assertEqual(parse_qs(parts.query)["serviceKey"], ["a+b/c="])
            self.assertEqual(parse_qs(parts.query)["langCode"], ["ko"])
        self.assertEqual(parse_qs(urlsplit(request_url("odii", "a%252Bb")).query)["serviceKey"], ["a%2Bb"])
        list_params = parse_qs(urlsplit(request_url("tourapi_list", "key")).query)
        self.assertEqual(list_params["contentTypeId"], ["12"])
        detail_params = parse_qs(urlsplit(request_url("tourapi_detail", "key", "1234")).query)
        self.assertEqual(detail_params["contentId"], ["1234"])
        self.assertFalse(any(name.endswith("YN") for name in detail_params))
        for name, content_id in (("https://attacker.test", None), ("tourapi_detail", "1&serviceKey=secret"), ("tourapi_detail", None)):
            with self.assertRaises(PublicDataError):
                request_url(name, "key", content_id)

    def test_normalized_record_is_pending_complete_not_published_or_evidence(self):
        record = normalize_record("odii", {**ODII, "audioUrl": "https://media.test/file.mp3", "imageUrl": "https://media.test/file.jpg",
                                           "serviceKey": "secret", "unknown": "not-whitelisted"}, NOW)
        self.assertEqual(record["review_status"], "pending")
        for flag in ("allowGeneration", "allowDisplay", "allowDistribution"):
            self.assertIs(record[flag], False)
        self.assertEqual(record["completeness"], {"status": "complete", "missingFields": []})
        self.assertEqual(record["fields"]["script"], ODII["script"])
        self.assertEqual(record["source"]["endpoint"], ENDPOINTS["odii"])
        self.assertEqual(record["coordinates"]["status"], "pending")
        self.assertEqual(record["coordinates"]["datum"], "unverified")
        serialized = json.dumps(record)
        for excluded in ("secret", "serviceKey", "media.test", "not-whitelisted", "?", "audioUrl", "imageUrl"):
            self.assertNotIn(excluded, serialized)

    def test_revision_depends_on_whitelist_data_not_fetch_time_or_extra_fields(self):
        first = normalize_record("odii", ODII, NOW)
        same = normalize_record("odii", {**ODII, "ignored": "new"}, "later")
        changed = normalize_record("odii", {**ODII, "script": "새로운 본문"}, NOW)
        self.assertEqual(first["recordRevisionSha256"], same["recordRevisionSha256"])
        self.assertNotEqual(first["recordRevisionSha256"], changed["recordRevisionSha256"])

    def test_missing_narrative_is_explicit_incomplete_without_fabrication(self):
        for service, row, field in (("odii", ODII, "script"), ("tourapi", TOUR, "overview")):
            record = normalize_record(service, {**row, field: ""}, NOW)
            self.assertEqual(record["fields"][field], "")
            self.assertEqual(record["completeness"], {"status": "incomplete", "missingFields": [field]})
            self.assertFalse(record["allowGeneration"])

    def test_required_ids_titles_text_bounds_and_invalid_unicode(self):
        cases = [{**ODII, "stid": None}, {**ODII, "stlid": "../../secret"}, {**ODII, "title": " "},
                 {**ODII, "title": "x" * 501}, {**ODII, "script": "x" * 64001}, {**ODII, "script": "\ud800"},
                 {**ODII, "script": "a\x00b"}, {**ODII, "stid": True}]
        for row in cases:
            with self.subTest(case=cases.index(row)), self.assertRaises(PublicDataError):
                normalize_record("odii", row, NOW)

    def test_coordinates_are_never_claimed_verified_and_bad_coordinates_discarded(self):
        for longitude, latitude in (("nan", "37"), ("181", "37"), ("127", "-91"), (True, "37"), ("127", None), ("bad", "37")):
            coordinate = normalize_record("odii", {**ODII, "mapX": longitude, "mapY": latitude}, NOW)["coordinates"]
            self.assertEqual(coordinate, {"status": "invalid", "datum": "unverified", "longitude": None, "latitude": None})
        coordinate = normalize_record("odii", {**ODII, "mapX": "", "mapY": ""}, NOW)["coordinates"]
        self.assertEqual(coordinate["status"], "missing")

    def test_tourism_current_classifications_are_whitelisted_without_license_upgrade(self):
        record = normalize_record("tourapi", {**TOUR, "cpyrhtDivCd": "Type1", "cat1": "OLD", "areaCode": "OLD"}, NOW)
        self.assertEqual(record["fields"]["lDongRegnCd"], "11")
        self.assertEqual(record["fields"]["lclsSystm3"], "A010101")
        self.assertEqual(record["fields"]["cpyrhtDivCd"], "Type1")
        self.assertFalse(record["allowGeneration"])
        self.assertFalse(record["allowDistribution"])
        self.assertNotIn("OLD", json.dumps(record))

    def test_collect_is_bounded_to_three_calls_and_service_override_keys(self):
        calls = []

        def fetch(endpoint, key, content_id=None):
            calls.append((endpoint, key, content_id))
            return [ODII if endpoint == "odii" else TOUR]

        document, statuses = collect_candidates({"PETASOS_DATA_GO_KR_SERVICE_KEY": "common", "PETASOS_ODII_SERVICE_KEY": "override"}, NOW, fetch=fetch)
        self.assertEqual(calls, [("odii", "override", None), ("tourapi_list", "common", None), ("tourapi_detail", "common", "1234")])
        self.assertEqual(len(document["records"]), 2)
        self.assertTrue(all(row["status"] == "PENDING_REVIEW" for row in statuses))
        self.assertNotIn("override", json.dumps(document))
        self.assertNotIn("common", json.dumps(statuses))

    def test_no_key_no_data_api_failure_and_detail_identity_mismatch(self):
        _, statuses = collect_candidates({}, NOW, fetch=lambda *_: self.fail("unexpected request"))
        self.assertTrue(all(row["status"] == "MISSING_KEY" for row in statuses))
        environment = {"PETASOS_DATA_GO_KR_SERVICE_KEY": "key"}
        _, statuses = collect_candidates(environment, NOW, fetch=lambda *_: [])
        self.assertTrue(all(row["status"] == "NO_DATA" for row in statuses))

        def fetch(endpoint, *_):
            if endpoint == "odii":
                raise PublicDataError("API_ERROR", "30")
            return [TOUR if endpoint == "tourapi_list" else {**TOUR, "contentid": "5678"}]

        document, statuses = collect_candidates(environment, NOW, fetch=fetch)
        self.assertEqual(document["records"], [])
        self.assertEqual(statuses[0], {"service": "odii", "status": "API_ERROR", "resultCode": "30", "recordCount": 0})
        self.assertEqual(statuses[1]["status"], "RECORD_ID_MISMATCH")
        with self.assertRaisesRegex(PublicDataError, "^INVALID_SERVICE$"):
            collect_candidates(environment, NOW, services=("odii", "odii"), fetch=lambda *_: self.fail("unexpected request"))

    def test_sampler_delegates_all_three_operations_to_contract_client(self):
        cases = [
            ("odii", None, "odii", "storyBasedList", {"numOfRows": 1, "pageNo": 1}),
            ("tourapi_list", None, "tourapi", "areaBasedList2", {"numOfRows": 1, "pageNo": 1, "contentTypeId": "12"}),
            ("tourapi_detail", "1234", "tourapi", "detailCommon2", {"numOfRows": 1, "pageNo": 1, "contentId": "1234"}),
        ]
        for endpoint, content_id, service, operation, parameters in cases:
            with self.subTest(endpoint=endpoint), patch("petasos_server.public_data.TourismClient") as client:
                client.return_value.request.return_value = SimpleNamespace(items=(ODII,))
                self.assertEqual(get_records(endpoint, "secret-key", content_id), [ODII])
                client.assert_called_once_with(service, "secret-key", max_response_bytes=MAX_RESPONSE_BYTES)
                client.return_value.request.assert_called_once_with(operation, parameters, response_format="json")

    def test_shared_client_errors_are_sanitized_and_multirow_is_rejected_at_sampler_boundary(self):
        with patch("petasos_server.public_data.TourismClient") as client:
            client.return_value.request.return_value = SimpleNamespace(items=(ODII, ODII))
            with self.assertRaisesRegex(PublicDataError, "^INVALID_RESPONSE$"):
                get_records("odii", "secret-key")
            for code, result_code in (("NETWORK_ERROR", None), ("HTTP_ERROR", None), ("API_ERROR", "30"), ("RESPONSE_TOO_LARGE", None)):
                client.return_value.request.side_effect = TourismApiError(code, result_code=result_code)
                with self.assertRaises(PublicDataError) as raised:
                    get_records("odii", "secret-key")
                self.assertEqual(raised.exception.code, code)
                self.assertEqual(raised.exception.result_code, result_code)
                self.assertNotIn("secret", str(raised.exception))

    def test_private_file_hash_exclusive_write_and_no_symlink_traversal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / ".local"
            directory = root / "content" / "incoming"
            document = {"records": [normalize_record("odii", ODII, NOW)]}
            path, digest = write_private_candidates(directory, "sample.json", document, private_root=root)
            self.assertEqual(json.loads(path.read_text()), document)
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), digest)
            if os.name == "posix":
                self.assertEqual(path.stat().st_mode & 0o777, 0o600)
                for component in (root, root / "content", directory):
                    self.assertEqual(component.stat().st_mode & 0o777, 0o700)
            with self.assertRaisesRegex(PublicDataError, "^OUTPUT_ALREADY_EXISTS$"):
                write_private_candidates(directory, "sample.json", {}, private_root=root)
            link = root / "linked"
            link.symlink_to(directory, target_is_directory=True)
            with self.assertRaisesRegex(PublicDataError, "^OUTPUT_DIRECTORY_NOT_PRIVATE$"):
                write_private_candidates(link / "incoming", "other.json", {}, private_root=root)
            self.assertFalse((directory / "incoming").exists())

    @unittest.skipUnless(os.name == "posix", "POSIX permissions")
    def test_public_output_directory_is_rejected_and_not_silently_changed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve() / ".local"
            root.mkdir(mode=0o755)
            with self.assertRaisesRegex(PublicDataError, "^OUTPUT_DIRECTORY_NOT_PRIVATE$"):
                write_private_candidates(root / "incoming", "sample.json", {}, private_root=root)
            self.assertEqual(root.stat().st_mode & 0o777, 0o755)


if __name__ == "__main__":
    unittest.main()
