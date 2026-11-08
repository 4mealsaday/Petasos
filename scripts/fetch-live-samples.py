#!/usr/bin/env python3
"""Fetch at most one record per approved API into private, unreviewed candidate files."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from petasos_server.local_env import load_environment
from petasos_server.public_data import PublicDataError, collect_candidates, write_private_candidates


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--service", choices=("all", "odii", "tourapi"), default="all")
    args = parser.parse_args()
    try:
        environment = load_environment(args.env_file)
    except (OSError, ValueError):
        print(json.dumps({"status": "LOCAL_ENV_INVALID"}))
        return 2
    now = datetime.now(timezone.utc)
    services = ("odii", "tourapi") if args.service == "all" else (args.service,)
    try:
        document, results = collect_candidates(environment, now.isoformat(timespec="seconds").replace("+00:00", "Z"), services)
        output = {"results": results}
        if document["records"]:
            path, digest = write_private_candidates(ROOT / ".local" / "content" / "incoming",
                "public-data-" + now.strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:12] + ".json", document,
                private_root=ROOT / ".local")
            output.update(path=str(path), sha256=digest, recordCount=len(document["records"]))
        print(json.dumps(output, ensure_ascii=False, indent=2))
        return 0 if all(result["status"] in {"PENDING_REVIEW", "INCOMPLETE_PENDING_REVIEW"} for result in results) else 1
    except PublicDataError as error:
        print(json.dumps({"status": error.code}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
