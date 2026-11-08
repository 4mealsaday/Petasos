import importlib.util
from contextlib import redirect_stdout, redirect_stderr
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("public_data_probe", ROOT / "scripts" / "probe-public-data.py")
probe_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe_module)
from petasos_server.tourism_api import ApiResponse, TourismApiError
from petasos_server.tourism_contract import operations


class PublicDataProbeTests(unittest.TestCase):
    def test_top_level_failures_are_private_and_existing_report_prevents_any_calls(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            self.assertEqual(probe_module.main(["--unknown-private-key"]), 2)
            with patch.object(probe_module, "load_environment", return_value={}), patch.object(
                    probe_module, "probe", side_effect=RuntimeError("private-url-key")):
                self.assertEqual(probe_module.main(["--check"]), 2)
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "existing.json"
                path.write_text("previous-evidence")
                with patch.object(probe_module, "probe") as network:
                    self.assertEqual(probe_module.main(["--check", "--report", str(path)]), 2)
                    network.assert_not_called()
                self.assertEqual(path.read_text(), "previous-evidence")
        self.assertNotIn("private-", stdout.getvalue() + stderr.getvalue())
        self.assertEqual(stderr.getvalue(), "")
        self.assertIn("REQUEST_FAILED", stdout.getvalue())

    def test_all_operations_both_formats_real_seed_and_private_data_stay_out_of_report(self):
        calls = []

        class Client:
            def __init__(self, service, key):
                self.service = service

            def request(self, name, params, response_format="json"):
                calls.append((self.service, name, params, response_format))
                return ApiResponse("0000", "private-message", ({"contentid": "00123", "title": "private-title"},), 1, 1, 5, response_format)

        result = probe_module.probe({"PETASOS_DATA_GO_KR_SERVICE_KEY": "private-key"}, Client)
        self.assertEqual(len(calls), 47)
        self.assertEqual(len(result["results"]), 46)
        self.assertEqual({(x["service"], x["operation"], x["requestedFormat"]) for x in result["results"]},
                         {(op.service, op.name, kind) for op in operations() for kind in ("json", "xml")})
        for _, name, params, _ in calls:
            self.assertEqual(params["numOfRows"], 1)
            if name.startswith("detail"):
                self.assertEqual(params["contentId"], "00123")
        self.assertNotIn("private-", json.dumps(result))
        self.assertNotIn("00123", json.dumps(result))

    def test_seed_quota_error_stops_service_without_retries(self):
        calls = []

        class Client:
            def __init__(self, *args):
                pass

            def request(self, *args, **kwargs):
                calls.append(args)
                raise failure

        for failure in (TourismApiError("API_ERROR", "22"), TourismApiError("HTTP_ERROR", http_status=429)):
            calls.clear()
            result = probe_module.probe({"PETASOS_TOURAPI_SERVICE_KEY": "private-key"}, Client)
            self.assertEqual(len(calls), 1)
            self.assertEqual(result["counts"], {"MISSING_KEY": 16, "SKIPPED_QUOTA": 30})

    def test_no_seed_skips_required_details_but_allows_pet_list(self):
        calls = []

        class Client:
            def __init__(self, *args):
                pass

            def request(self, name, *args, response_format="json", **kwargs):
                calls.append(name)
                return ApiResponse("0000", "", (), 1, 1, 0, response_format)

        result = probe_module.probe({"PETASOS_TOURAPI_SERVICE_KEY": "private-key"}, Client)
        self.assertEqual(result["counts"]["SKIPPED_NO_DETAIL_SEED"], 8)
        self.assertEqual(calls.count("detailPetTour2"), 2)
        self.assertNotIn("detailCommon2", calls)


if __name__ == "__main__":
    unittest.main()
