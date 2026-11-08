#!/usr/bin/env python3
"""Inspect the official boundary contract or perform one memory-only API check."""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from petasos_server.local_env import load_environment
from petasos_server.region_boundaries import BoundaryApiError, LAYER, VWorldBoundaryClient


class CliError(ValueError):
    pass


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        raise CliError("INVALID_ARGUMENTS")


def parser():
    result = PrivateParser(description=__doc__)
    commands = result.add_subparsers(dest="command", required=True)
    commands.add_parser("describe", help="No network or credentials needed")
    check = commands.add_parser("check", help="One exact legal si/gun/gu lookup; no geometry saved")
    check.add_argument("--code", required=True, help="Five-digit legal si/gun/gu code")
    check.add_argument("--env-file", type=Path, default=ROOT / ".env")
    return result


def run(args):
    if args.command == "describe":
        return {
            "mode": "contract_only_no_network", "layer": LAYER,
            "source": "https://www.vworld.kr/dev/v4dv_2ddataguide2_s002.do?svcIde=adsigg",
            "method": "GET", "endpoint": "https://api.vworld.kr/req/data",
            "fixedParameters": {"service": "data", "version": "2.0", "request": "GetFeature",
                                "format": "json", "geometry": True, "attribute": True,
                                "crs": "EPSG:4326", "size": 2, "page": 1},
            "regionFilter": "sig_cd:=:<five-digit-code>",
            "credentialFields": ["PETASOS_VWORLD_API_KEY"], "optionalFields": ["PETASOS_VWORLD_DOMAIN"],
            "geometryStorage": "disabled", "productEnabled": False,
            "offlineSource": "https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=MK&dsId=30252",
            "offlineFile": "N3A_G0100000.zip", "offlineSourceCrs": "EPSG:5179",
            "offlineLicense": "https://creativecommons.org/licenses/by/2.0/kr/",
            "offlineRequiresApiKey": False,
        }
    try:
        environment = load_environment(args.env_file)
    except (ValueError, OSError):
        raise CliError("LOCAL_ENV_INVALID") from None
    key, domain = (environment.get(name) for name in ("PETASOS_VWORLD_API_KEY", "PETASOS_VWORLD_DOMAIN"))
    if not key:
        raise CliError("MISSING_VWORLD_CONFIGURATION")
    return VWorldBoundaryClient(key, domain).lookup(args.code).safe_metadata()


def main(argv=None):
    try:
        result = run(parser().parse_args(argv))
    except (CliError, BoundaryApiError) as error:
        print(json.dumps({"status": "BLOCKED", "errorCode": str(error)}, ensure_ascii=False))
        return 2
    except Exception:
        print(json.dumps({"status": "BLOCKED", "errorCode": "BOUNDARY_CHECK_FAILED"}))
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
