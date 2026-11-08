import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("naver_cli", ROOT / "scripts/naver-routes.py")
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class NaverRoutesCliTests(unittest.TestCase):
    def invoke(self, arguments):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = cli.main(arguments)
        return code, json.loads(output.getvalue())

    def test_describe_has_no_file_or_network_access(self):
        with patch.object(cli, "load_environment") as env, patch.object(cli, "NaverDirectionsClient") as client, \
                patch.object(cli, "read_request") as read:
            code, result = self.invoke(["describe"])
        self.assertEqual(code, 0)
        self.assertEqual(result["sourceMode"], "AUXILIARY")
        self.assertFalse(result["activePleosRouteVerified"])
        self.assertFalse(result["storedGeometry"])
        self.assertFalse(result["collectionEvidence"])
        env.assert_not_called()
        client.assert_not_called()
        read.assert_not_called()

    def test_missing_configuration_never_falls_back_to_other_keys(self):
        for env in ({"PETASOS_KAKAO_MOBILITY_REST_API_KEY": "secret"},
                    {"PETASOS_DATA_GO_KR_SERVICE_KEY": "secret"},
                    {"PETASOS_NAVER_MAPS_CLIENT_ID": "secret"},
                    {"PETASOS_NAVER_MAPS_CLIENT_SECRET": "secret"}):
            with self.subTest(fields=tuple(env)), patch.object(cli, "load_environment", return_value=env), \
                    patch.object(cli, "NaverDirectionsClient") as client:
                code, result = self.invoke(["check-official-example"])
                self.assertEqual((code, result["errorCode"]), (2, "MISSING_NAVER_MAPS_CONFIGURATION"))
                client.assert_not_called()

    def test_check_writes_no_geometry_and_prints_only_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "private-request.json"
            path.write_text('{"origin":[127.1,37.4],"destination":[127.2,37.5]}')
            path.chmod(0o600)
            with patch.object(cli, "load_environment", return_value={"PETASOS_NAVER_MAPS_CLIENT_ID": "private-id",
                       "PETASOS_NAVER_MAPS_CLIENT_SECRET": "private-secret"}), patch.object(cli, "NaverDirectionsClient") as client:
                client.return_value.lookup.return_value.safe_metadata.return_value = {"status": "PASS", "storedGeometry": False}
                code, result = self.invoke(["check", "--request", str(path)])
                self.assertEqual(code, 0)
                self.assertEqual(result, {"status": "PASS", "storedGeometry": False})
                client.assert_called_once_with("private-id", "private-secret")
                self.assertEqual(client.return_value.lookup.call_args.args[0].origin, (127.1, 37.4))
            self.assertEqual(list(Path(directory).iterdir()), [path])

    def test_invalid_argument_and_env_messages_do_not_leak(self):
        for args in (["secret-command"], ["check", "--private-key", "secret-value"]):
            code, result = self.invoke(args)
            self.assertEqual((code, result["errorCode"]), (2, "INVALID_ARGUMENTS"))
            self.assertNotIn("secret", json.dumps(result))
        with patch.object(cli, "load_environment", side_effect=ValueError("private-file-content")):
            self.assertEqual(self.invoke(["check-official-example"])[1]["errorCode"], "LOCAL_ENV_INVALID")

    def test_unexpected_network_error_is_sanitized(self):
        with patch.object(cli, "load_environment", return_value={"PETASOS_NAVER_MAPS_CLIENT_ID": "id", "PETASOS_NAVER_MAPS_CLIENT_SECRET": "secret"}), \
                patch.object(cli, "NaverDirectionsClient", side_effect=RuntimeError("https://host/?private-coordinates-secret")):
            code, result = self.invoke(["check-official-example"])
        self.assertEqual((code, result["errorCode"]), (2, "NAVER_ROUTE_CHECK_FAILED"))

    def test_unsafe_or_invalid_request_is_rejected_before_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "request.json"
            for raw, mode in ((b'{}', 0o644), (b'x' * (cli.MAX_INPUT_BYTES + 1), 0o600),
                              (b'{"origin":[127.1,37.4],"origin":[127.1,37.4],"destination":[127.2,37.5]}', 0o600)):
                path.write_bytes(raw)
                path.chmod(mode)
                with patch.object(cli, "load_environment") as env, patch.object(cli, "NaverDirectionsClient") as client:
                    code, result = self.invoke(["check", "--request", str(path)])
                    self.assertEqual(code, 2)
                    self.assertNotIn(str(path), json.dumps(result))
                    env.assert_not_called()
                    client.assert_not_called()

    def test_symlink_and_nonregular_request_are_not_followed(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "private.json"
            target.write_text("private-content")
            target.chmod(0o600)
            symlink = Path(directory) / "link.json"
            symlink.symlink_to(target)
            fifo = Path(directory) / "fifo"
            os.mkfifo(fifo)
            for path in (symlink, fifo, Path(directory)):
                with self.subTest(kind=path.name), patch.object(cli, "load_environment") as env:
                    code, result = self.invoke(["check", "--request", str(path)])
                    self.assertEqual((code, result["errorCode"]), (2, "REQUEST_FILE_NOT_PRIVATE"))
                    env.assert_not_called()

    def test_official_example_does_not_read_device_location(self):
        with patch.object(cli, "load_environment", return_value={"PETASOS_NAVER_MAPS_CLIENT_ID": "id", "PETASOS_NAVER_MAPS_CLIENT_SECRET": "secret"}), \
                patch.object(cli, "NaverDirectionsClient") as client, patch.object(cli, "read_request") as read:
            client.return_value.lookup.return_value.safe_metadata.return_value = {"status": "PASS"}
            self.assertEqual(self.invoke(["check-official-example"])[0], 0)
            request = client.return_value.lookup.call_args.args[0]
            self.assertEqual(request.origin, (127.1058342, 37.359708))
            self.assertEqual(request.destination, (129.075986, 35.179470))
            read.assert_not_called()


if __name__ == "__main__":
    unittest.main()
