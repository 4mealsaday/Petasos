#!/usr/bin/env python3
"""Explicit live read-only contract probe: at most 47 one-page GET requests.

Only fixed public landmark searches and a returned public content ID are used.
No source records, messages, credentials, or authenticated URLs are printed.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from petasos_server.local_env import EnvFileError, load_environment
from petasos_server.tourism_api import TourismApiError, TourismClient
from petasos_server.tourism_contract import operations


class _ArgumentsError(ValueError):
    pass


class _PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        raise _ArgumentsError()


def probe(environment, client_factory=TourismClient):
    clients = {}
    for service, name in (("odii", "PETASOS_ODII_SERVICE_KEY"), ("tourapi", "PETASOS_TOURAPI_SERVICE_KEY")):
        key = environment.get(name) or environment.get("PETASOS_DATA_GO_KR_SERVICE_KEY")
        if key:
            clients[service] = client_factory(service, key)
    # Get a real tourist-attraction ID, never invent an ID for detail probes.
    content_id = None
    exhausted = set()
    setup = {"status": "MISSING_KEY"}
    if "tourapi" in clients:
        try:
            result = clients["tourapi"].request("areaBasedList2", {"numOfRows": 1, "pageNo": 1, "contentTypeId": "12"})
            if result.items:
                value = result.items[0].get("contentid")
                if isinstance(value, str) and value.isascii() and value.isdigit():
                    content_id = value
            setup = {"status": "PASS" if content_id else "NO_DETAIL_SEED", **result.safe_metadata()}
        except TourismApiError as error:
            setup = {"status": error.code, "resultCode": error.result_code, "httpStatus": error.http_status}
            if error.result_code in {"22", "23", "0022", "0023"} or error.http_status == 429:
                exhausted.add("tourapi")
    rows = []
    for definition in operations():
        params = {"numOfRows": 1, "pageNo": 1}
        names = {p["name"] for p in definition.parameters}
        if "mapX" in names:
            params.update(mapX="126.977", mapY="37.579", radius="2000")
        if "keyword" in names:
            params["keyword"] = "경복궁"
        if "eventStartDate" in names:
            params["eventStartDate"] = datetime.now(timezone.utc).strftime("%Y%m%d")
        if definition.name == "searchStay2":
            params["lDongRegnCd"] = "11"
        if definition.name in {"areaBasedList2", "areaBasedSyncList2", "locationBasedList2"}:
            params["contentTypeId"] = "12"
        requires_seed = any(p["name"] == "contentId" and p.get("required") for p in definition.parameters)
        if "contentId" in names and content_id:
            params["contentId"] = content_id
        if definition.name in {"detailIntro2", "detailInfo2"}:
            params["contentTypeId"] = "12"
        for response_format in ("json", "xml"):
            row = {"service": definition.service, "operation": definition.name,
                   "requestedFormat": response_format, "deprecated": definition.deprecated}
            if definition.service not in clients:
                row["status"] = "MISSING_KEY"
            elif definition.service in exhausted:
                row["status"] = "SKIPPED_QUOTA"
            elif requires_seed and not content_id:
                row["status"] = "SKIPPED_NO_DETAIL_SEED"
            else:
                try:
                    result = clients[definition.service].request(definition.name, params, response_format=response_format)
                    row.update(result.safe_metadata())
                    row["status"] = "PASS" if result.items else "EMPTY"
                    if result.schema_issues:
                        row["status"] = "SCHEMA_DRIFT"
                    if result.response_format != response_format:
                        row["status"] = "FORMAT_DRIFT"
                except TourismApiError as error:
                    row.update(status=error.code, resultCode=error.result_code, httpStatus=error.http_status)
                    if error.result_code in {"22", "23", "0022", "0023"} or error.http_status == 429:
                        exhausted.add(definition.service)
            rows.append(row)
    return {"recordedAtUTC": datetime.now(timezone.utc).isoformat(), "maximumRequests": 47,
            "requestedRowsPerCall": 1, "detailSeed": setup, "results": rows,
            "counts": dict(Counter(row["status"] for row in rows))}


def main(argv=None):
    parser = _PrivateParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="perform the bounded live calls")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--report", type=Path, help="write sanitized metadata; fail if path already exists")
    try:
        args = parser.parse_args(argv)
    except _ArgumentsError:
        print('{"status":"INVALID_ARGUMENTS"}')
        return 2
    if not args.check:
        print(json.dumps({"status": "NOT_RUN", "operationCount": len(operations()), "maximumRequests": 47}))
        return 0
    try:
        if args.report and (args.report.exists() or args.report.is_symlink()):
            raise FileExistsError()
        report = probe(load_environment(args.env_file))
        if args.report:
            # Exclusive creation: never overwrite credentials or previous evidence.
            with args.report.open("x", encoding="utf-8") as output:
                json.dump(report, output, ensure_ascii=False, indent=2)
                output.write("\n")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return int(any(row["status"] not in {"PASS", "EMPTY"} for row in report["results"]))
    except (EnvFileError, TourismApiError, OSError):
        print('{"status":"LOCAL_CONFIGURATION_OR_OUTPUT_ERROR"}')
        return 2
    except Exception:
        # Unexpected errors can include URLs; never emit their str/traceback.
        print('{"status":"REQUEST_FAILED"}')
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
