#!/usr/bin/env python3
"""Private loopback TLS for the debug app and the explicitly configured real provider.

prepare creates a local CA and localhost leaf, copying ONLY the public CA to debug
resources. serve is a foreground process; Ctrl-C/SIGTERM stops it. No public bind,
CA-store installation, credential output, provider activation or fallback occurs.
"""
import argparse
import asyncio
import hashlib
import json
import logging
import os
from pathlib import Path
import socket
import ssl
import stat
import subprocess
import tempfile

import uvicorn

from petasos_server.__main__ import build_app
from petasos_server.local_env import load_environment
from petasos_server.provider import OpenRouterProvider

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / ".local/server"
TLS = PRIVATE / "dev-tls"
PUBLIC_CA = ROOT / "app/src/debug/res/raw/dev_local_generation_ca.pem"
READY = PRIVATE / "dev-https-ready.json"
TLS_FILES = ("ca.key", "ca.pem", "server.key", "server.pem")


def private_directory(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError("INVALID_PRIVATE_DIRECTORY")


def private_file(path, maximum=8 * 1024 * 1024):
    info = path.lstat()
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600 or not 0 < info.st_size <= maximum):
        raise ValueError("INVALID_PRIVATE_FILE")


def atomic_write(path, data, mode=0o600):
    # Refuse an existing symlink rather than silently redirecting a local setup file.
    if path.is_symlink():
        raise ValueError("INVALID_OUTPUT_PATH")
    descriptor, temporary = tempfile.mkstemp(prefix=".petasos-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def openssl(*arguments):
    subprocess.run(["openssl", *map(str, arguments)], check=True,
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def validate_tls(directory):
    for name in TLS_FILES:
        private_file(directory / name, 32768)
    openssl("verify", "-CAfile", directory / "ca.pem", "-verify_hostname", "localhost", directory / "server.pem")
    openssl("x509", "-in", directory / "ca.pem", "-checkend", "0", "-noout")
    openssl("x509", "-in", directory / "server.pem", "-checkend", "0", "-noout")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(directory / "server.pem", directory / "server.key")
    return context


def prepare(directory=TLS, public_ca=PUBLIC_CA):
    private_directory(directory.parent)
    private_directory(directory)
    existing = [(directory / name).exists() for name in TLS_FILES]
    if any(existing) and not all(existing):
        raise ValueError("INCOMPLETE_TLS_FILES")
    if not any(existing):
        with tempfile.TemporaryDirectory(prefix=".prepare-", dir=directory) as temporary:
            work = Path(temporary)
            openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", work / "ca.key",
                    "-out", work / "ca.pem", "-days", "30", "-subj", "/CN=Petasos local debug CA",
                    "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
                    "-addext", "keyUsage=critical,keyCertSign,cRLSign")
            openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-keyout", work / "server.key",
                    "-out", work / "server.csr", "-subj", "/CN=localhost")
            (work / "leaf.cnf").write_text("subjectAltName=DNS:localhost\n"
                "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                "extendedKeyUsage=serverAuth\n")
            openssl("x509", "-req", "-in", work / "server.csr", "-CA", work / "ca.pem",
                    "-CAkey", work / "ca.key", "-CAcreateserial", "-out", work / "server.pem",
                    "-days", "7", "-extfile", work / "leaf.cnf")
            for name in TLS_FILES:
                atomic_write(directory / name, (work / name).read_bytes())
    validate_tls(directory)
    public_ca.parent.mkdir(parents=True, exist_ok=True)
    certificate = (directory / "ca.pem").read_bytes()
    if b"PRIVATE KEY" in certificate or certificate.count(b"BEGIN CERTIFICATE") != 1:
        raise ValueError("INVALID_PUBLIC_CA")
    atomic_write(public_ca, certificate, mode=0o644)
    return {"outcome": "PREPARED", "scope": "debug_localhost_only", "caSha256": hashlib.sha256(certificate).hexdigest(),
            "publicCaFile": str(public_ca), "rebuildDebugApkRequired": True}


def configured_app(config, env_file):
    private_file(config)
    document = json.loads(config.read_text())
    for field in ("tokensFile", "fingerprintKeyFile", "evidenceFile"):
        if document.get(field):
            private_file(Path(document[field]))
    app = build_app(config, load_environment(env_file))
    if not isinstance(app.service.provider, OpenRouterProvider):
        raise ValueError("REAL_PROVIDER_NOT_CONFIGURED")
    app.service.provider.config.validate()
    return app


async def serve(args):
    private_directory(TLS.parent)
    private_directory(TLS)
    context = validate_tls(TLS)
    if PUBLIC_CA.is_symlink() or PUBLIC_CA.read_bytes() != (TLS / "ca.pem").read_bytes():
        raise ValueError("DEBUG_CA_OUT_OF_SYNC")
    app = configured_app(args.config, args.env_file)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    task = None
    try:
        sock.bind(("127.0.0.1", args.port))
        sock.listen(128)
        sock.setblocking(False)
        port = sock.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, access_log=False, log_config=None,
            proxy_headers=False, server_header=False, ssl_context_factory=lambda config, default_factory: context,
            limit_concurrency=128, timeout_keep_alive=5))
        task = asyncio.create_task(server.serve(sockets=[sock]))
        while not server.started:
            if task.done():
                await task
                raise RuntimeError("SERVER_START_FAILED")
            await asyncio.sleep(0.01)
        # Metadata only. A ready server does not imply a successful model request.
        atomic_write(READY, (json.dumps({"port": port, "serverOrigin": f"https://localhost:{port}",
            "pid": os.getpid(), "scope": "debug_loopback_real_configured_provider",
            "caFile": str(PUBLIC_CA), "tokenFile": str(PRIVATE / "device-provisioning.token")}) + "\n").encode())
        print(json.dumps({"outcome": "READY", "readyFile": str(READY), "port": port}), flush=True)
        await task
    finally:
        if task is not None:
            server.should_exit = True
            if not task.done():
                await task
            READY.unlink(missing_ok=True)
        sock.close()
        await app.service.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare", help="Create/reuse local certificates and copy only the public CA to debug resources")
    start = commands.add_parser("serve", help="Start foreground loopback HTTPS; stop with Ctrl-C or SIGTERM")
    start.add_argument("--config", type=Path, default=PRIVATE / "config.json")
    start.add_argument("--env-file", type=Path, default=ROOT / ".env")
    start.add_argument("--port", type=int, default=8443)
    args = parser.parse_args()
    if args.command == "serve" and args.port not in range(1, 65536):
        parser.error("--port must be between 1 and 65535")
    os.umask(0o077)
    for name in ("httpx", "httpcore", "uvicorn.access", "uvicorn.error"):
        logging.getLogger(name).disabled = True
    try:
        if args.command == "prepare":
            print(json.dumps(prepare()))
        else:
            asyncio.run(serve(args))
    except KeyboardInterrupt:
        pass
    except Exception:
        # Dependency exceptions can contain URLs or config; expose only a fixed code.
        print(json.dumps({"outcome": "BLOCKED", "code": "LOCAL_HTTPS_CONFIGURATION_OR_START_FAILED"}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
