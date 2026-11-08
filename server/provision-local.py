#!/usr/bin/env python3
"""Create operator-owned local settings. Does not activate any paid provider or start a listener."""
import argparse
import hashlib
import json
import os
import re
import secrets
import time
from pathlib import Path


def write_private(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as stream:
        stream.write(value if isinstance(value, bytes) else value.encode())


def main():
    parser = argparse.ArgumentParser(description="Create private local Petasos configuration without printing credentials")
    parser.add_argument("--directory", default=".local/server")
    parser.add_argument("--device-id", default="local-development-device")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", args.device_id):
        parser.exit(2, "Invalid device ID.\n")
    directory = Path(args.directory).resolve()
    if directory.exists():
        parser.exit(2, "Destination already exists; no credentials were overwritten.\n")
    os.umask(0o077)
    directory.mkdir(parents=True, mode=0o700)
    token = secrets.token_urlsafe(32)
    write_private(directory / "device-provisioning.token", token)
    write_private(directory / "fingerprint.key", secrets.token_bytes(32))
    write_private(directory / "tokens.json", json.dumps([{"deviceId": args.device_id,
        "tokenHash": hashlib.sha256(token.encode()).hexdigest(), "expiresAt": int(time.time() + 86400), "revoked": False}], indent=2))
    write_private(directory / "evidence.json", json.dumps({"sources": [], "facts": []}, indent=2))
    write_private(directory / "config.json", json.dumps({"tokensFile": str(directory / "tokens.json"),
        "fingerprintKeyFile": str(directory / "fingerprint.key"), "evidenceFile": str(directory / "evidence.json"),
        "provider": None}, indent=2))
    print("Private local configuration created; provider remains unavailable. Credential values were not printed.")


if __name__ == "__main__":
    main()
