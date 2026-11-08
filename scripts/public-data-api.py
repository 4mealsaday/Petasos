#!/usr/bin/env python3
"""Inspect official tourism contracts or make one bounded, explicitly requested GET."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from petasos_server.local_env import load_environment
from petasos_server.public_data import PublicDataError, write_private_candidates
from petasos_server.tourism_api import TourismApiError, TourismClient
from petasos_server.tourism_contract import operation, operations

PRIVATE_ROOT = ROOT / ".local"
OUTPUT_ROOT = PRIVATE_ROOT / "content" / "api"
KEY_NAMES = {"odii": "PETASOS_ODII_SERVICE_KEY", "tourapi": "PETASOS_TOURAPI_SERVICE_KEY"}
MAX_PARAMETER_BYTES = 64 * 1024


class CliFailure(ValueError):
    """Only an internally selected error code is printable."""


class PrivateArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default message repeats bad arguments, which may contain a key.
        raise CliFailure("INVALID_ARGUMENTS")


def make_parser():
    parser = PrivateArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    listing = commands.add_parser("list", help="List all official operations; no credentials or network")
    listing.add_argument("--service", choices=tuple(KEY_NAMES))
    description = commands.add_parser("describe", help="Show exact official inputs and output item schema; no network")
    calling = commands.add_parser("call", help="Make exactly one GET; never retry or paginate automatically")
    for child in (description, calling):
        child.add_argument("--service", choices=tuple(KEY_NAMES), required=True)
        child.add_argument("--operation", required=True)
    calling.add_argument("--params", default="{}", help="JSON object of official query inputs; never include serviceKey")
    calling.add_argument("--format", choices=("json", "xml"), default="json")
    calling.add_argument("--env-file", type=Path, default=ROOT / ".env")
    calling.add_argument("--output", type=Path, help="Optional new .json file under .local/content/api; raw records are private")
    return parser


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CliFailure("INVALID_PARAMETERS")
        result[key] = value
    return result


def _reject_constant(value):
    raise CliFailure("INVALID_PARAMETERS")


def parse_parameters(text):
    if len(text.encode("utf-8")) > MAX_PARAMETER_BYTES:
        raise CliFailure("INVALID_PARAMETERS")
    try:
        parameters = json.loads(text, object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except (ValueError, TypeError, RecursionError):
        raise CliFailure("INVALID_PARAMETERS") from None
    if not isinstance(parameters, dict) or any(key.casefold() == "servicekey" for key in parameters):
        raise CliFailure("INVALID_PARAMETERS")
    return parameters


def output_path(value):
    if value is None:
        return None
    path = value if value.is_absolute() else ROOT / value
    try:
        relative = path.relative_to(OUTPUT_ROOT)
    except ValueError:
        raise CliFailure("OUTPUT_DIRECTORY_NOT_PRIVATE") from None
    if not relative.parts or ".." in relative.parts or not re.fullmatch(r"[a-zA-Z0-9_-]+\.json", path.name):
        raise CliFailure("OUTPUT_DIRECTORY_NOT_PRIVATE")
    return path


def catalog_entry(item):
    return {
        "service": item.service, "operation": item.name, "method": "GET", "path": item.path,
        "description": item.description, "deprecated": item.deprecated,
        "requiredParameters": [parameter["name"] for parameter in item.parameters if parameter.get("required")],
    }


def _result_code(value):
    return value if isinstance(value, str) and re.fullmatch(r"(?:[0-9]{2}|[0-9]{4})", value) else None


def _http_status(value):
    return value if isinstance(value, int) and not isinstance(value, bool) and 100 <= value <= 599 else None


def run(args):
    if args.command == "list":
        return {"mode": "catalog_only_no_network", "operations": [catalog_entry(item) for item in operations(args.service)]}
    try:
        selected = operation(args.service, args.operation)
    except (KeyError, ValueError):
        raise CliFailure("INVALID_OPERATION") from None
    if args.command == "describe":
        return {
            "mode": "catalog_only_no_network", **catalog_entry(selected),
            "parameters": list(selected.parameters), "itemSchema": selected.item_schema,
        }

    parameters = parse_parameters(args.params)
    destination = output_path(args.output)
    try:
        environment = load_environment(args.env_file)
    except (OSError, ValueError):
        raise CliFailure("LOCAL_ENV_INVALID") from None
    key = environment.get(KEY_NAMES[selected.service]) or environment.get("PETASOS_DATA_GO_KR_SERVICE_KEY")
    if not key:
        raise CliFailure("MISSING_SERVICE_KEY")
    response = TourismClient(selected.service, key).request(selected.name, parameters, response_format=args.format)
    # Deliberately construct stdout from known metadata, never from a response body,
    # resultMsg, provider field name, request URL, exception, or user-supplied path.
    status = "PASS" if response.items else "NO_DATA"
    if response.schema_issues:
        status = "SCHEMA_DRIFT"
    if response.response_format != args.format:
        status = "FORMAT_DRIFT"
    result = {
        "mode": "single_live_get", "service": selected.service, "operation": selected.name,
        "status": status, "httpStatus": 200,
        "resultCode": _result_code(response.result_code), "responseFormat": response.response_format,
        "requestedFormat": args.format,
        "itemCount": len(response.items), "numOfRows": response.num_of_rows,
        "pageNo": response.page_no, "totalCount": response.total_count,
        "schemaIssueCount": len(response.schema_issues), "extraFieldCount": response.extra_field_count,
        "outputSaved": False,
    }
    if destination is not None:
        document = {
            "formatVersion": 1, "kind": "petasos-public-api-response-for-review",
            "service": selected.service, "operation": selected.name,
            "fetchedAtUTC": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "responseFormat": response.response_format,
            "requestedFormat": args.format, "validationStatus": status,
            "response": {
                "header": {"resultCode": response.result_code, "resultMsg": response.result_message},
                "body": {"items": {"item": list(response.items)}, "numOfRows": response.num_of_rows,
                         "pageNo": response.page_no, "totalCount": response.total_count},
            },
            "schemaIssues": list(response.schema_issues), "extraFieldCount": response.extra_field_count,
            "review_status": "pending", "allowGeneration": False,
            "allowDisplay": False, "allowDistribution": False,
        }
        write_private_candidates(destination.parent, destination.name, document, private_root=PRIVATE_ROOT)
        result["outputSaved"] = True
    return result


def main(argv=None):
    try:
        result = run(make_parser().parse_args(argv))
        exit_code = int(result.get("status") in {"SCHEMA_DRIFT", "FORMAT_DRIFT"})
    except CliFailure as error:
        result, exit_code = {"status": str(error)}, 2
    except TourismApiError as error:
        result, exit_code = {"status": error.code}, 1
        if _result_code(error.result_code) is not None:
            result["resultCode"] = error.result_code
        if _http_status(error.http_status) is not None:
            result["httpStatus"] = error.http_status
    except PublicDataError as error:
        result, exit_code = {"status": error.code}, 1
    except Exception:
        # Even an unexpected transport/storage exception must not reveal URL keys.
        result, exit_code = {"status": "REQUEST_FAILED"}, 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
