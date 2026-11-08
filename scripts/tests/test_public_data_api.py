import contextlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("public_data_api_cli", Path(__file__).resolve().parents[1] / "public-data-api.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class PublicDataApiCliTests(unittest.TestCase):
    def invoke(self, *arguments):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = cli.main(list(arguments))
        self.assertEqual(stderr.getvalue(), "")
        return code, json.loads(stdout.getvalue()), stdout.getvalue()

    def response(self, **updates):
        values = {
            "items": ({"title": "PRIVATE_RECORD_TEXT", "EXTRA_PRIVATE_FIELD": "PRIVATE_FIELD_VALUE"},),
            "num_of_rows": 1, "page_no": 1, "total_count": 456,
            "result_code": "0000", "result_message": "PRIVATE_PROVIDER_MESSAGE",
            "response_format": "json", "schema_issues": (), "extra_field_count": 1,
        }
        values.update(updates)
        return SimpleNamespace(**values)

    def call_args(self, *extras):
        return ("call", "--service", "tourapi", "--operation", "areaBasedList2", *extras)

    def test_list_covers_both_catalogs_without_environment_or_network(self):
        with patch.object(cli, "load_environment") as environment, patch.object(cli, "TourismClient") as client:
            code, result, _ = self.invoke("list")
        self.assertEqual(code, 0)
        self.assertEqual(result["mode"], "catalog_only_no_network")
        self.assertEqual(len(result["operations"]), 23)
        self.assertEqual({row["service"] for row in result["operations"]}, {"odii", "tourapi"})
        environment.assert_not_called()
        client.assert_not_called()

    def test_list_filter_and_describe_preserve_current_official_inputs(self):
        with patch.object(cli, "load_environment") as environment, patch.object(cli, "TourismClient") as client:
            code, listing, _ = self.invoke("list", "--service", "tourapi")
            described_code, result, _ = self.invoke("describe", "--service", "tourapi", "--operation", "searchStay2")
        self.assertEqual((code, described_code), (0, 0))
        self.assertEqual(len(listing["operations"]), 15)
        self.assertIn("lDongRegnCd", result["requiredParameters"])
        self.assertTrue(next(p for p in result["parameters"] if p["name"] == "lDongRegnCd")["required"])
        self.assertIn("contentid", result["itemSchema"]["properties"])
        environment.assert_not_called()
        client.assert_not_called()

    def test_one_call_uses_service_override_and_prints_metadata_only(self):
        environment = {"PETASOS_TOURAPI_SERVICE_KEY": "PRIVATE_OVERRIDE_KEY", "PETASOS_DATA_GO_KR_SERVICE_KEY": "PRIVATE_SHARED_KEY"}
        with patch.object(cli, "load_environment", return_value=environment), patch.object(cli, "TourismClient") as client:
            client.return_value.request.return_value = self.response()
            code, result, text = self.invoke(*self.call_args("--params", '{"contentTypeId":"12","numOfRows":1}'))
        client.assert_called_once_with("tourapi", "PRIVATE_OVERRIDE_KEY")
        client.return_value.request.assert_called_once_with("areaBasedList2", {"contentTypeId": "12", "numOfRows": 1}, response_format="json")
        self.assertEqual(code, 0)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["itemCount"], 1)
        self.assertEqual(result["schemaIssueCount"], 0)
        self.assertEqual(result["extraFieldCount"], 1)
        self.assertNotIn("PRIVATE", text)
        self.assertNotIn("body.items", text)
        self.assertFalse(result["outputSaved"])

    def test_xml_format_and_common_key_are_passed_once(self):
        with patch.object(cli, "load_environment", return_value={"PETASOS_DATA_GO_KR_SERVICE_KEY": "private"}), patch.object(cli, "TourismClient") as client:
            client.return_value.request.return_value = self.response(items=(), response_format="xml")
            code, result, _ = self.invoke(*self.call_args("--format", "xml"))
        client.assert_called_once_with("tourapi", "private")
        client.return_value.request.assert_called_once_with("areaBasedList2", {}, response_format="xml")
        self.assertEqual(code, 0)
        self.assertEqual(result["status"], "NO_DATA")
        self.assertEqual(result["responseFormat"], "xml")

    def test_schema_and_format_drift_are_visible_failures_without_raw_data(self):
        cases = [
            ({"schema_issues": ("body.items.item.title",)}, "SCHEMA_DRIFT", 1),
            ({"response_format": "xml"}, "FORMAT_DRIFT", 0),
            ({"response_format": "xml", "schema_issues": ("body.items.item.title",)}, "FORMAT_DRIFT", 1),
        ]
        for updates, expected, issue_count in cases:
            with self.subTest(status=expected, issues=issue_count), patch.object(cli, "load_environment", return_value={"PETASOS_DATA_GO_KR_SERVICE_KEY": "PRIVATE_KEY"}), patch.object(cli, "TourismClient") as client:
                client.return_value.request.return_value = self.response(**updates)
                code, result, text = self.invoke(*self.call_args())
            self.assertEqual(code, 1)
            self.assertEqual(result["status"], expected)
            self.assertEqual(result["schemaIssueCount"], issue_count)
            self.assertEqual(result["requestedFormat"], "json")
            self.assertNotIn("PRIVATE", text)
            self.assertNotIn("body.items", text)
            client.return_value.request.assert_called_once()

    def test_cli_keys_duplicate_json_and_nonobjects_fail_before_environment(self):
        invalid = [
            '{"serviceKey":"PRIVATE_KEY"}', '{"ServiceKey":"PRIVATE_KEY"}',
            '{"keyword":"PRIVATE_A","keyword":"PRIVATE_B"}', '[]',
            '{"numOfRows":NaN}', '{"numOfRows":Infinity}', 'PRIVATE_BAD_JSON',
        ]
        with patch.object(cli, "load_environment") as environment, patch.object(cli, "TourismClient") as client:
            for parameters in invalid:
                with self.subTest(parameters=parameters):
                    code, result, text = self.invoke(*self.call_args("--params", parameters))
                    self.assertEqual(code, 2)
                    self.assertEqual(result["status"], "INVALID_PARAMETERS")
                    self.assertNotIn("PRIVATE", text)
        environment.assert_not_called()
        client.assert_not_called()

    def test_unknown_arguments_operation_and_service_are_not_echoed(self):
        cases = [
            ("call", "--service", "PRIVATE_SERVICE", "--operation", "PRIVATE_OPERATION"),
            ("describe", "--service", "tourapi", "--operation", "PRIVATE_OPERATION"),
            ("list", "--PRIVATE_KEY", "PRIVATE_VALUE"), (),
        ]
        with patch.object(cli, "load_environment") as environment, patch.object(cli, "TourismClient") as client:
            for arguments in cases:
                code, result, text = self.invoke(*arguments)
                self.assertEqual(code, 2)
                self.assertIn(result["status"], {"INVALID_ARGUMENTS", "INVALID_OPERATION"})
                self.assertNotIn("PRIVATE", text)
        environment.assert_not_called()
        client.assert_not_called()

    def test_environment_and_missing_key_fail_without_network(self):
        with patch.object(cli, "load_environment", side_effect=ValueError("PRIVATE_ENV_DETAIL")), patch.object(cli, "TourismClient") as client:
            code, result, text = self.invoke(*self.call_args())
        self.assertEqual((code, result["status"]), (2, "LOCAL_ENV_INVALID"))
        self.assertNotIn("PRIVATE", text)
        client.assert_not_called()
        with patch.object(cli, "load_environment", return_value={}), patch.object(cli, "TourismClient") as client:
            code, result, _ = self.invoke(*self.call_args())
        self.assertEqual((code, result["status"]), (2, "MISSING_SERVICE_KEY"))
        client.assert_not_called()

    def test_api_error_only_reports_sanitized_metadata(self):
        error = cli.TourismApiError("API_ERROR", result_code="30", http_status=200)
        error.args = ("https://example.invalid/?serviceKey=PRIVATE_KEY",)
        with patch.object(cli, "load_environment", return_value={"PETASOS_DATA_GO_KR_SERVICE_KEY": "PRIVATE_KEY"}), patch.object(cli, "TourismClient") as client:
            client.return_value.request.side_effect = error
            code, result, text = self.invoke(*self.call_args())
        self.assertEqual(code, 1)
        self.assertEqual(result, {"status": "API_ERROR", "resultCode": "30", "httpStatus": 200})
        self.assertNotIn("PRIVATE", text)
        client.return_value.request.assert_called_once()

    def test_unexpected_exception_does_not_print_its_message_or_traceback(self):
        with patch.object(cli, "load_environment", return_value={"PETASOS_DATA_GO_KR_SERVICE_KEY": "PRIVATE_KEY"}), patch.object(cli, "TourismClient") as client:
            client.return_value.request.side_effect = RuntimeError("PRIVATE_URL_AND_KEY")
            code, result, text = self.invoke(*self.call_args())
        self.assertEqual((code, result), (1, {"status": "REQUEST_FAILED"}))
        self.assertNotIn("PRIVATE", text)

    def test_private_output_preserves_all_fields_without_promoting_review(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            private_root = root / ".local"
            output_root = private_root / "content" / "api"
            destination = output_root / "sample.json"
            with patch.object(cli, "ROOT", root), patch.object(cli, "PRIVATE_ROOT", private_root), patch.object(cli, "OUTPUT_ROOT", output_root), patch.object(cli, "load_environment", return_value={"PETASOS_DATA_GO_KR_SERVICE_KEY": "PRIVATE_KEY"}), patch.object(cli, "TourismClient") as client:
                client.return_value.request.return_value = self.response()
                code, result, text = self.invoke(*self.call_args("--output", ".local/content/api/sample.json"))
            self.assertEqual(code, 0)
            self.assertTrue(result["outputSaved"])
            self.assertNotIn("PRIVATE", text)
            document = json.loads(destination.read_text())
            self.assertEqual(document["response"]["body"]["items"]["item"][0]["EXTRA_PRIVATE_FIELD"], "PRIVATE_FIELD_VALUE")
            self.assertEqual(document["response"]["header"]["resultMsg"], "PRIVATE_PROVIDER_MESSAGE")
            self.assertEqual(document["review_status"], "pending")
            self.assertFalse(document["allowGeneration"])
            self.assertFalse(document["allowDisplay"])
            self.assertFalse(document["allowDistribution"])
            self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(destination.parent.stat().st_mode), 0o700)

    def test_output_outside_private_subdirectory_fails_before_request(self):
        with patch.object(cli, "load_environment") as environment, patch.object(cli, "TourismClient") as client:
            for destination in ("PRIVATE_FILE.json", ".local/content/api/../PRIVATE_FILE.json", ".local/content/api/PRIVATE_FILE.txt"):
                code, result, text = self.invoke(*self.call_args("--output", destination))
                self.assertEqual((code, result["status"]), (2, "OUTPUT_DIRECTORY_NOT_PRIVATE"))
                self.assertNotIn("PRIVATE_FILE", text)
        environment.assert_not_called()
        client.assert_not_called()


if __name__ == "__main__":
    unittest.main()
