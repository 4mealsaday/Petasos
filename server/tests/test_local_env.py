import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from petasos_server.local_env import EnvFileError, MAX_ENV_BYTES, load_environment, read_private_env

KEY = "PETASOS_OPENROUTER_API_KEY"


class LocalEnvTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / ".env"

    def write(self, text):
        self.path.write_text(text)
        self.path.chmod(0o600)
        return self.path

    def test_blank_comments_quotes_and_empty_values(self):
        self.write(f"\n # comment\n{KEY} = 'literal-value'\nPETASOS_ODII_SERVICE_KEY=\n"
                   'PETASOS_TOURAPI_SERVICE_KEY="tour-key"\nPETASOS_DATA_GO_KR_SERVICE_KEY=common-key\n')
        self.assertEqual(read_private_env(self.path), {
            KEY: "literal-value", "PETASOS_ODII_SERVICE_KEY": "",
            "PETASOS_TOURAPI_SERVICE_KEY": "tour-key", "PETASOS_DATA_GO_KR_SERVICE_KEY": "common-key"})

    def test_shell_and_variable_syntax_is_literal_never_executed(self):
        sentinel = Path(self.directory.name) / "must-not-exist"
        value = f"$(touch {sentinel}) `touch {sentinel}` $HOME ${{KEY}} \\n # literal"
        self.write(f'{KEY}="{value}"\n')
        self.assertEqual(read_private_env(self.path)[KEY], value)
        self.assertFalse(sentinel.exists())

    def test_bad_format_unknown_and_duplicate_keys_have_sanitized_errors(self):
        cases = ["secret-value-without-equals", "export " + KEY + "=secret-value",
                 "UNKNOWN_SECRET=secret-value", f"{KEY}='secret-value\n", f'{KEY}=secret-value"',
                 f"{KEY}=secret-value\n{KEY}=another-secret", f"{KEY}=secret\x00value"]
        for content in cases:
            with self.subTest(case=cases.index(content)):
                self.write(content)
                with self.assertRaises(EnvFileError) as raised:
                    read_private_env(self.path)
                self.assertNotIn("secret", str(raised.exception))
                self.assertNotIn(str(self.path), str(raised.exception))
                self.assertRegex(str(raised.exception), r"^ENV_FILE_[A-Z_]+$")

    def test_size_and_invalid_utf8_are_rejected(self):
        self.write("#" * (MAX_ENV_BYTES + 1))
        with self.assertRaisesRegex(EnvFileError, "^ENV_FILE_TOO_LARGE$"):
            read_private_env(self.path)
        self.path.write_bytes(b"\xff")
        with self.assertRaisesRegex(EnvFileError, "^ENV_FILE_INVALID_ENCODING$"):
            read_private_env(self.path)

    @unittest.skipUnless(os.name == "posix", "POSIX permission contract")
    def test_group_other_access_or_missing_owner_read_is_rejected(self):
        self.write(f"{KEY}=value")
        for mode in (0o644, 0o640, 0o601, 0o200):
            self.path.chmod(mode)
            with self.subTest(mode=mode), self.assertRaises(EnvFileError):
                read_private_env(self.path)
        self.path.chmod(0o400)
        self.assertEqual(read_private_env(self.path)[KEY], "value")

    @unittest.skipUnless(os.name == "posix", "POSIX ownership contract")
    def test_different_file_owner_is_rejected(self):
        self.write(f"{KEY}=value")
        with patch("petasos_server.local_env.os.geteuid", return_value=os.geteuid() + 1):
            with self.assertRaisesRegex(EnvFileError, "^ENV_FILE_NOT_PRIVATE$"):
                read_private_env(self.path)

    def test_symlink_and_directory_are_rejected(self):
        self.write(f"{KEY}=value")
        link = self.path.with_name("link.env")
        link.symlink_to(self.path)
        for path in (link, self.path.parent):
            with self.assertRaisesRegex(EnvFileError, "^ENV_FILE_NOT_REGULAR$"):
                read_private_env(path)

    def test_missing_file_has_no_path_in_error(self):
        with self.assertRaisesRegex(EnvFileError, "^ENV_FILE_UNREADABLE$"):
            read_private_env(self.path)

    def test_environment_priority_and_no_mutation(self):
        self.write(f"{KEY}=file-value\nPETASOS_ODII_SERVICE_KEY=file-odii\n")
        original = {KEY: "process-value", "PETASOS_ODII_SERVICE_KEY": "", "UNRELATED": "unchanged"}
        result = load_environment(self.path, original)
        self.assertEqual(result, {KEY: "process-value", "PETASOS_ODII_SERVICE_KEY": "file-odii", "UNRELATED": "unchanged"})
        self.assertEqual(original["PETASOS_ODII_SERVICE_KEY"], "")
        self.assertIsNot(load_environment(None, original), original)

    def test_optional_boundary_credentials_are_separate_from_public_data_key(self):
        self.write("PETASOS_VWORLD_API_KEY=vworld-value\nPETASOS_VWORLD_DOMAIN=http://localhost:8443\n"
                   "PETASOS_DATA_GO_KR_SERVICE_KEY=public-data-value\n")
        result = load_environment(self.path, {})
        self.assertEqual(result["PETASOS_VWORLD_API_KEY"], "vworld-value")
        self.assertEqual(result["PETASOS_VWORLD_DOMAIN"], "http://localhost:8443")
        self.assertEqual(result["PETASOS_DATA_GO_KR_SERVICE_KEY"], "public-data-value")

    def test_default_environment_is_copied(self):
        with patch.dict(os.environ, {KEY: "process-value"}, clear=True):
            self.assertEqual(load_environment(None), {KEY: "process-value"})

    def test_optional_routing_key_does_not_replace_other_service_credentials(self):
        routing = "PETASOS_KAKAO_MOBILITY_REST_API_KEY"
        self.write(f"{routing}=\n{KEY}=model-value\nPETASOS_DATA_GO_KR_SERVICE_KEY=public-value\n")
        values = load_environment(self.path, {routing: "routing-process-value"})
        self.assertEqual(values[routing], "routing-process-value")
        self.assertEqual(values[KEY], "model-value")
        self.assertEqual(values["PETASOS_DATA_GO_KR_SERVICE_KEY"], "public-value")

    def test_naver_maps_pair_is_literal_and_separate_from_other_keys(self):
        client_id = "PETASOS_NAVER_MAPS_CLIENT_ID"
        secret = "PETASOS_NAVER_MAPS_CLIENT_SECRET"
        self.write(f"{client_id}=maps-id\n{secret}='literal-maps-secret'\n{KEY}=model-key\n")
        values = load_environment(self.path, {client_id: "process-id"})
        self.assertEqual(values[client_id], "process-id")
        self.assertEqual(values[secret], "literal-maps-secret")
        self.assertEqual(values[KEY], "model-key")

    def test_server_provider_receives_explicit_environment_mapping(self):
        from petasos_server.__main__ import build_app

        directory = self.path.parent
        fingerprint = directory / "fingerprint.key"
        fingerprint.write_bytes(b"k" * 32)
        tokens = directory / "tokens.json"
        tokens.write_text("[]")
        config = directory / "config.json"
        config.write_text(json.dumps({
            "fingerprintKeyFile": str(fingerprint), "tokensFile": str(tokens),
            "provider": {"type": "openrouter", "model": "test-model", "providerSlug": "test-provider",
                         "acceptedProviderNames": ["Test Provider"], "privacyReviewedUntil": 9999999999,
                         "accountContentLoggingDisabled": True}}))
        with patch.dict(os.environ, {KEY: "global-value"}), patch("petasos_server.__main__.OpenRouterProvider") as provider:
            build_app(config, environment={KEY: "explicit-value"})
            self.assertEqual(provider.call_args.args[0].api_key, "explicit-value")
            self.assertEqual(os.environ[KEY], "global-value")


if __name__ == "__main__":
    unittest.main()
