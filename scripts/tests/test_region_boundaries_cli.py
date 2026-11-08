import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("boundary_cli", ROOT / "scripts/region-boundaries.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class BoundaryCliTests(unittest.TestCase):
    def invoke(self, arguments):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = cli.main(arguments)
        return status, json.loads(output.getvalue())

    def test_describe_is_offline_and_does_not_read_credentials(self):
        with patch.object(cli, "load_environment") as load, patch.object(cli, "VWorldBoundaryClient") as client:
            status, result = self.invoke(["describe"])
        self.assertEqual(status, 0)
        self.assertFalse(result["offlineRequiresApiKey"])
        self.assertEqual(result["geometryStorage"], "disabled")
        load.assert_not_called()
        client.assert_not_called()

    def test_missing_optional_key_never_uses_public_data_key(self):
        with patch.object(cli, "load_environment", return_value={"PETASOS_DATA_GO_KR_SERVICE_KEY": "secret"}), \
                patch.object(cli, "VWorldBoundaryClient") as client:
            status, result = self.invoke(["check", "--code", "11650"])
        self.assertEqual(status, 2)
        self.assertEqual(result["errorCode"], "MISSING_VWORLD_CONFIGURATION")
        client.assert_not_called()

    def test_invalid_arguments_and_env_errors_never_echo_inputs(self):
        for arguments in (["check", "--secret-key", "secret-value"], ["invalid-secret-command"]):
            status, result = self.invoke(arguments)
            self.assertEqual(status, 2)
            self.assertNotIn("secret", json.dumps(result))
        with patch.object(cli, "load_environment", side_effect=ValueError("secret-file-line")):
            self.assertEqual(self.invoke(["check", "--code", "11650"])[1]["errorCode"], "LOCAL_ENV_INVALID")

    def test_configured_key_with_blank_optional_domain_can_check(self):
        with patch.object(cli, "load_environment", return_value={"PETASOS_VWORLD_API_KEY": "secret",
                                                                "PETASOS_VWORLD_DOMAIN": ""}), \
                patch.object(cli, "VWorldBoundaryClient") as client:
            client.return_value.lookup.return_value.safe_metadata.return_value = {"status": "PASS"}
            status, _ = self.invoke(["check", "--code", "11650"])
            self.assertEqual(status, 0)
            client.assert_called_once_with("secret", "")

    def test_check_prints_only_safe_metadata_without_geometry_or_files(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(cli, "load_environment", return_value={"PETASOS_VWORLD_API_KEY": "secret",
                                                                    "PETASOS_VWORLD_DOMAIN": "http://localhost"}), \
                patch.object(cli, "VWorldBoundaryClient") as client:
            client.return_value.lookup.return_value.safe_metadata.return_value = {"status": "PASS", "storedGeometry": False}
            status, result = self.invoke(["check", "--code", "11650", "--env-file", str(Path(directory) / ".env")])
            self.assertEqual(list(Path(directory).iterdir()), [])
        self.assertEqual(status, 0)
        self.assertEqual(result, {"status": "PASS", "storedGeometry": False})
        client.return_value.lookup.assert_called_once_with("11650")


if __name__ == "__main__":
    unittest.main()
