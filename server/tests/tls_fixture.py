"""Loopback HTTPS integration fixture. Every generated response is synthetic test data.

Creates a one-day disposable CA/server certificate and a random bearer token. The ready file
contains only paths/port; the token and TLS key are mode 0600, never stdout or arguments.
This entry point is intentionally outside the production package.
"""
import argparse
import asyncio
import hashlib
import json
import logging
import os
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
from pathlib import Path

import uvicorn

from petasos_server.asgi import GenerationApp
from petasos_server.auth import DeviceToken, TokenRegistry
from petasos_server.evidence import EvidenceRegistry
from petasos_server.service import GenerationService
from tests.fixtures import SyntheticProvider, evidence_document


def private_write(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write(value)
    os.chmod(path, 0o600)


async def serve(args, directory):
    key, cert, token_file = [directory / name for name in ("server.key", "server.pem", "device.token")]
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
        "-out", str(cert), "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
        "-addext", "basicConstraints=critical,CA:TRUE"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.chmod(key, 0o600)
    token = secrets.token_urlsafe(32)
    private_write(token_file, token)
    registry = TokenRegistry([DeviceToken("test-device", hashlib.sha256(token.encode()).hexdigest(), time.time() + 3600)])
    app = GenerationApp(registry, GenerationService(EvidenceRegistry(evidence_document()), SyntheticProvider(), secrets.token_bytes(32)))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert, key)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("127.0.0.1", args.port))
    sock.listen(128)
    sock.setblocking(False)
    port = sock.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, access_log=False, log_config=None,
        proxy_headers=False, server_header=False, ssl_context_factory=lambda config, default_factory: context, limit_concurrency=128))
    task = asyncio.create_task(server.serve(sockets=[sock]))
    try:
        while not server.started:
            if task.done():
                await task
                raise RuntimeError("FIXTURE_START_FAILED")
            await asyncio.sleep(0.01)
        private_write(Path(args.ready_file), json.dumps({"port": port, "tokenFile": str(token_file), "caFile": str(cert),
                                                       "scope": "synthetic-provider-real-https"}))
        await task
    finally:
        server.should_exit = True
        if not task.done():
            await task
        sock.close()
        Path(args.ready_file).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description="Synthetic Petasos HTTPS fixture; loopback only")
    parser.add_argument("--ready-file", required=True)
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    for name in ("httpx", "httpcore", "uvicorn.access", "uvicorn.error"):
        logging.getLogger(name).disabled = True
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix="petasos-tls-") as path:
        try:
            asyncio.run(serve(args, Path(path)))
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
