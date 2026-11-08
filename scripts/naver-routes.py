#!/usr/bin/env python3
"""Inspect Directions 5 or check one auxiliary route without saving its geometry."""
import argparse
import json
import os
from pathlib import Path
import stat
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from petasos_server.local_env import load_environment
from petasos_server.naver_routes import (
    NaverDirectionsClient, NaverRouteError, NaverRouteRequest, decode_naver_request,
)

MAX_INPUT_BYTES = 16 * 1024


class CliError(ValueError):
    """Only fixed local codes, never a path, credential, URL query or provider message."""


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        raise CliError("INVALID_ARGUMENTS")


def parser():
    result = PrivateParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("describe", help="Offline request/response contract; no credentials")
    check = commands.add_parser("check", help="One request from a private local JSON file")
    check.add_argument("--request", type=Path, required=True)
    check.add_argument("--env-file", type=Path, default=ROOT / ".env")
    example = commands.add_parser("check-official-example", help="One request using the public official example coordinates")
    example.add_argument("--env-file", type=Path, default=ROOT / ".env")
    return result


def read_request(path):
    try:
        before = path.lstat()
        if not stat.S_ISREG(before.st_mode):
            raise CliError("REQUEST_FILE_NOT_PRIVATE")
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
        with os.fdopen(fd, "rb") as stream:
            current = os.fstat(stream.fileno())
            if (not stat.S_ISREG(current.st_mode) or
                    (before.st_dev, before.st_ino) != (current.st_dev, current.st_ino) or
                    os.name == "posix" and (current.st_uid != os.geteuid() or current.st_mode & 0o077)):
                raise CliError("REQUEST_FILE_NOT_PRIVATE")
            raw = stream.read(MAX_INPUT_BYTES + 1)
            after = os.fstat(stream.fileno())
            if (current.st_size, current.st_mtime_ns, current.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
                raise CliError("REQUEST_FILE_CHANGED")
        if len(raw) > MAX_INPUT_BYTES:
            raise CliError("REQUEST_TOO_LARGE")
        return decode_naver_request(raw)
    except (CliError, NaverRouteError):
        raise
    except (OSError, ValueError, TypeError):
        raise CliError("REQUEST_FILE_UNREADABLE") from None


def run(args):
    if args.command == "describe":
        return {
            "mode": "contract_only_no_network", "provider": "naver_maps", "sourceMode": "AUXILIARY",
            "source": "https://api.ncloud-docs.com/docs/application-maps-directions5",
            "endpoint": "https://maps.apigw.ntruss.com/map-direction/v1/driving", "method": "GET",
            "credentialFields": ["PETASOS_NAVER_MAPS_CLIENT_ID", "PETASOS_NAVER_MAPS_CLIENT_SECRET"],
            "requestFile": {"origin": "[longitude,latitude]", "destination": "[longitude,latitude]",
                            "waypoints": "optional; up to five [longitude,latitude] pairs", "option": "optional; one routing option"},
            "coordinates": "EPSG:4326", "geometryField": "path", "timingFields": ["guide.pointIndex", "guide.duration"],
            "durationUnit": "milliseconds", "storedGeometry": False, "productEnabled": False,
            "activePleosRouteVerified": False, "collectionEvidence": False,
        }
    # Validate explicit input before reading credentials or initializing networking.
    request = (read_request(args.request) if args.command == "check" else
               NaverRouteRequest(origin=(127.1058342, 37.359708), destination=(129.075986, 35.179470)))
    try:
        environment = load_environment(args.env_file)
    except (OSError, ValueError):
        raise CliError("LOCAL_ENV_INVALID") from None
    key_id = environment.get("PETASOS_NAVER_MAPS_CLIENT_ID")
    key = environment.get("PETASOS_NAVER_MAPS_CLIENT_SECRET")
    if not key_id or not key:
        raise CliError("MISSING_NAVER_MAPS_CONFIGURATION")
    return NaverDirectionsClient(key_id, key).lookup(request).safe_metadata()


def main(argv=None):
    try:
        result = run(parser().parse_args(argv))
    except CliError as error:
        print(json.dumps({"status": "BLOCKED", "errorCode": str(error)}))
        return 2
    except NaverRouteError as error:
        print(json.dumps({"status": "BLOCKED", "errorCode": error.code}))
        return 2
    except Exception:
        print(json.dumps({"status": "BLOCKED", "errorCode": "NAVER_ROUTE_CHECK_FAILED"}))
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
