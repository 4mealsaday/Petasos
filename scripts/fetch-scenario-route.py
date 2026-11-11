#!/usr/bin/env python3
"""Fetch a demo scenario's road route once and pin it as demo/scenarios/<id>/route.json.

    PYTHONPATH=server .local/server-venv/bin/python scripts/fetch-scenario-route.py --scenario seoul-chuncheon

Reads origin/destination from scenario.json and calls NAVER Directions 5 through the server's own client
(server/petasos_server/naver_routes.py, PETASOS_NAVER_MAPS_CLIENT_ID/SECRET in .env). The file has the same shape as
the seoul-daejeon route.json. An existing route.json is never overwritten (the pinned route is the demo).
"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from petasos_server.local_env import load_environment  # noqa: E402
from petasos_server.naver_routes import NaverDirectionsClient, NaverRouteRequest  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    args = parser.parse_args()
    directory = ROOT / "demo" / "scenarios" / args.scenario
    scenario = json.loads((directory / "scenario.json").read_text())
    out = directory / scenario["route"]
    if out.exists():
        raise SystemExit(f"{out.relative_to(ROOT)} exists; the pinned route is not refetched.")
    environment = load_environment(args.env_file)
    origin = (scenario["origin"]["longitude"], scenario["origin"]["latitude"])
    destination = (scenario["destination"]["longitude"], scenario["destination"]["latitude"])
    request = NaverRouteRequest(origin, destination)
    route = NaverDirectionsClient(environment["PETASOS_NAVER_MAPS_CLIENT_ID"],
                                  environment["PETASOS_NAVER_MAPS_CLIENT_SECRET"]).lookup(request)
    value = {"origin": list(origin), "destination": list(destination),
             "providerOrigin": list(route.provider_origin), "providerDestination": list(route.provider_destination),
             "path": [list(point) for point in route.path], "totalDistanceMeters": route.total_distance_meters,
             "totalDurationMs": route.total_duration_ms,
             "timingSegments": [{"startPointIndex": s.start_point_index, "endPointIndex": s.end_point_index,
                                 "durationMs": s.duration_ms, "distanceMeters": s.distance_meters} for s in route.timing_segments]}
    out.write_text(json.dumps(value) + "\n")
    print(f"{out.relative_to(ROOT)}: {len(value['path'])} points, {value['totalDistanceMeters'] / 1000:.1f} km, "
          f"{value['totalDurationMs'] / 60000:.0f} min")


if __name__ == "__main__":
    main()
