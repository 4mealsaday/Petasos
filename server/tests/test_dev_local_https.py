"""Local CA isolation, private setup and sanitized readiness; no external model calls."""
import asyncio
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import ssl
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from petasos_server.asgi import GenerationApp
from petasos_server.auth import TokenRegistry
from petasos_server.evidence import EvidenceRegistry
from petasos_server.provider import UnavailableProvider
from petasos_server.service import GenerationService

SPEC = importlib.util.spec_from_file_location("dev_local_https", Path(__file__).parents[1] / "dev-local-https.py")
LOCAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LOCAL)


class LocalDevelopmentTlsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.tls = self.directory / "tls"
        self.public = self.directory / "debug" / "ca.pem"

    def tearDown(self):
        self.temporary.cleanup()

    def test_private_ca_and_localhost_leaf_public_copy_stable_on_reprepare(self):
        first = LOCAL.prepare(self.tls, self.public)
        original = {name: (self.tls / name).read_bytes() for name in LOCAL.TLS_FILES}
        second = LOCAL.prepare(self.tls, self.public)
        self.assertEqual(first, second)
        self.assertEqual(original, {name: (self.tls / name).read_bytes() for name in LOCAL.TLS_FILES})
        self.assertEqual(stat.S_IMODE(self.tls.stat().st_mode), 0o700)
        for name in LOCAL.TLS_FILES:
            self.assertEqual(stat.S_IMODE((self.tls / name).stat().st_mode), 0o600)
        self.assertEqual(self.public.read_bytes(), original["ca.pem"])
        self.assertNotIn(b"PRIVATE KEY", self.public.read_bytes())
        self.assertNotIn("PRIVATE KEY", json.dumps(first))
        self.assertEqual(LOCAL.validate_tls(self.tls).minimum_version, ssl.TLSVersion.TLSv1_2)
        with self.assertRaises(subprocess.CalledProcessError):
            LOCAL.openssl("verify", "-CAfile", self.tls / "ca.pem", "-verify_hostname", "other.invalid", self.tls / "server.pem")

    def test_partial_existing_tls_does_not_replace_any_key(self):
        self.tls.mkdir(mode=0o700)
        key = self.tls / "ca.key"
        key.write_bytes(b"local-key-canary")
        key.chmod(0o600)
        with self.assertRaisesRegex(ValueError, "INCOMPLETE_TLS_FILES"):
            LOCAL.prepare(self.tls, self.public)
        self.assertEqual(key.read_bytes(), b"local-key-canary")
        self.assertFalse(self.public.exists())

    def test_nonprivate_files_and_symlinks_are_rejected(self):
        path = self.directory / "secret"
        path.write_text("private-canary")
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            LOCAL.private_file(path)
        path.chmod(0o600)
        link = self.directory / "link"
        link.symlink_to(path)
        with self.assertRaises(ValueError):
            LOCAL.private_file(link)
        with self.assertRaises(ValueError):
            LOCAL.atomic_write(link, b"replacement")
        self.assertEqual(path.read_text(), "private-canary")

    def test_group_readable_private_directory_is_rejected(self):
        self.tls.mkdir(mode=0o755)
        self.tls.chmod(0o755)
        with self.assertRaises(ValueError):
            LOCAL.private_directory(self.tls)

    def test_serve_is_loopback_only_emits_metadata_and_removes_ready(self):
        LOCAL.prepare(self.tls, self.public)
        ready = self.directory / "ready.json"
        app = GenerationApp(TokenRegistry([]), GenerationService(EvidenceRegistry(), UnavailableProvider(), b"k" * 32))
        addresses = []
        configurations = []

        class Socket:
            def bind(self, address): addresses.append(address)
            def listen(self, value): pass
            def setblocking(self, value): pass
            def getsockname(self): return ("127.0.0.1", 8443)
            def close(self): pass

        class Server:
            def __init__(self, config):
                configurations.append(config)
                self.started = False
                self.should_exit = False

            async def serve(self, sockets):
                self.started = True
                await asyncio.sleep(0.03)
                document = json.loads(ready.read_text())
                assert document["scope"] == "debug_loopback_real_configured_provider"
                assert document["serverOrigin"] == "https://localhost:8443"
                assert stat.S_IMODE(ready.stat().st_mode) == 0o600

        output = io.StringIO()
        # A real loop is constructed with normal sockets; only the application bind is mocked.
        loop = asyncio.new_event_loop()
        try:
            with patch.multiple(LOCAL, TLS=self.tls, PUBLIC_CA=self.public, READY=ready), \
                    patch.object(LOCAL, "configured_app", return_value=app), \
                    patch.object(LOCAL.socket, "socket", return_value=Socket()), \
                    patch.object(LOCAL.uvicorn, "Server", Server), contextlib.redirect_stdout(output):
                loop.run_until_complete(LOCAL.serve(SimpleNamespace(port=8443, config=None, env_file=None)))
        finally:
            loop.close()
        self.assertEqual(addresses, [("127.0.0.1", 8443)])
        self.assertFalse(ready.exists())
        self.assertFalse(configurations[0].access_log)
        self.assertFalse(configurations[0].proxy_headers)
        self.assertEqual(json.loads(output.getvalue())["outcome"], "READY")

    def test_cli_does_not_print_underlying_exception(self):
        output = io.StringIO()
        original_umask = os.umask(0o077)
        try:
            with patch("sys.argv", ["dev-local-https.py", "prepare"]), \
                    patch.object(LOCAL, "prepare", side_effect=ValueError("PRIVATE_KEY_CANARY")), \
                    contextlib.redirect_stdout(output):
                self.assertEqual(LOCAL.main(), 1)
        finally:
            os.umask(original_umask)
        self.assertNotIn("PRIVATE_KEY_CANARY", output.getvalue())
        self.assertEqual(json.loads(output.getvalue())["code"], "LOCAL_HTTPS_CONFIGURATION_OR_START_FAILED")


if __name__ == "__main__":
    unittest.main()
