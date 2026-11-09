#!/usr/bin/env python3
"""GPS replay for the Seoul Station -> Daejeon Expo demo route.

Drives `adb emu geo fix` along demo/route.json's path at a variable cadence: slow inside the
five story-bearing region groups (so the app has time to region-confirm and finish at least
one story), fast everywhere else. Region containment uses the exact same even-odd point-in-
polygon rule as the shipped PackedRegionBoundaryLookup, read directly from the pruned .pgeo
assets -- not a separate/divergent geometry implementation.

Usage:
    python3 scripts/demo_route.py [--serial emulator-5554] [--start-index N] [--pause] [--speed X] [--video]

--video: recording preset (~4-5 min total). Pair it with the app's DEMO video mode so each region
plays one representative story (S01/S03/S05/S08/S10) + one 15 s music bridge:
    adb shell am broadcast --user 10 -f 0x20 -p com.petasos.app -a com.petasos.app.debug.SET_VIDEO_MODE --ez enabled true
Each story region is held ~45 s, pass-through stretches ~8 s, and points are thinned so every
geo fix step is >= 1 s (one `adb emu geo fix` alone costs ~0.15-0.2 s).
`kill -USR1 <pid>` toggles pause/resume as well as Enter (useful when stdin is not a terminal).
"""
import argparse
import signal
import struct
import sys
import threading
import time
from pathlib import Path
from subprocess import run

ROOT = Path(__file__).resolve().parents[1]
ROUTE_FILE = ROOT / "demo" / "route.json"
MANIFEST_FILE = ROOT / "app/src/main/assets/regions/ngii-2026-09-21-v1/manifest.json"
ASSETS_DIR = ROOT / "app/src/main/assets"

MAGIC = 0x5054524730303031  # ASCII "PTRG0001"

# Story-bearing region groups (DEMO_PLAN 3단계 [2]), cross-checked against
# app/src/main/assets/content/p0-stories-v2.json's regionBindings rather than taken on faith:
#   11140 (중구, Seoul)            -> S01/S02
#   41460/41461/41463/41465 (용인시 and its non-autonomous districts) -> S03/S04
#   41550 (안성시)                  -> S05
#   44130/44131/44133 (천안시 and its non-autonomous districts)       -> S06/S07 (44133 added for
#       pacing consistency with the app's own hierarchy: it shares 44130 as parent, same as 44131)
#   30200/30230 (유성구/대덕구, the Daejeon districts this route actually enters)
# Note: the bundled content's 7th binding is on 30110 (동구) -- verified by exact point-in-polygon
# test that this route's real 1957-point polyline has ZERO points inside 30110's geometry. That
# story is unreachable by GPS replay on this real route; it's a content/route mismatch (see the
# DEMO-only app-side fallback in RoomAutomaticStoryStore.demoFallbackCandidate), not something this
# script can route around, since demo/route.json is the real fetched Directions path.
STORY_GROUPS = {
    "seoul": {"11140"},
    "yongin": {"41460", "41461", "41463", "41465"},
    "anseong": {"41550"},
    "cheonan": {"44130", "44131", "44133"},
    "daejeon": {"30200", "30230"},
}
STORY_CODES = {code for group in STORY_GROUPS.values() for code in group}

STORY_REGION_TARGET_SECONDS = 65.0   # ~60-70s dwell inside a story-bearing run
PASSTHROUGH_TARGET_SECONDS = 12.5    # ~10-15s to cross a non-story run
MIN_INTERVAL_S = 0.15
MAX_INTERVAL_S = 6.0


def read_pgeo_rings(path):
    data = path.read_bytes()
    magic, ring_count, point_count = struct.unpack_from(">qii", data, 0)
    if magic != MAGIC:
        raise ValueError(f"bad .pgeo magic in {path}")
    offset = 16
    rings = []
    for _ in range(ring_count):
        _rmin_lon, _rmin_lat, _rmax_lon, _rmax_lat, ring_points = struct.unpack_from(">ddddi", data, offset)
        offset += 36
        points = []
        for _ in range(ring_points):
            lon, lat = struct.unpack_from(">dd", data, offset)
            offset += 16
            points.append((lon, lat))
        rings.append(points)
    return rings


def point_in_rings(lon, lat, rings):
    inside = False
    for ring in rings:
        n = len(ring)
        for i in range(1, n):
            prev_lon, prev_lat = ring[i - 1]
            next_lon, next_lat = ring[i]
            if (prev_lat > lat) != (next_lat > lat):
                crossing = prev_lon + (lat - prev_lat) * (next_lon - prev_lon) / (next_lat - prev_lat)
                if lon < crossing:
                    inside = not inside
    return inside


class RegionIndex:
    def __init__(self, manifest):
        self.entries = []
        for region in manifest["regions"]:
            bounds = [float(v) for v in region["bounds"]]
            geometry_path = ASSETS_DIR / region["geometryAsset"]
            self.entries.append({
                "code": region["code"],
                "name": region["name"],
                "bounds": bounds,  # [min_lon, min_lat, max_lon, max_lat]
                "rings": read_pgeo_rings(geometry_path),
            })

    def containing_code(self, lon, lat):
        for entry in self.entries:
            min_lon, min_lat, max_lon, max_lat = entry["bounds"]
            if not (min_lon <= lon <= max_lon and min_lat <= lat <= max_lat):
                continue
            if point_in_rings(lon, lat, entry["rings"]):
                return entry["code"], entry["name"]
        return None, None


def category_for(code):
    if code is None:
        return "passthrough", None
    for group, codes in STORY_GROUPS.items():
        if code in codes:
            return "story", group
    return "passthrough", None


def build_runs(points, index):
    """Groups consecutive points by (category, group) so each contiguous stretch gets one pacing target."""
    annotated = []
    for lon, lat in points:
        code, name = index.containing_code(lon, lat)
        category, group = category_for(code)
        annotated.append((lon, lat, code, name, category, group))

    runs = []
    current = None
    for item in annotated:
        category, group = item[4], item[5]
        key = (category, group)
        if current is None or current["key"] != key:
            current = {"key": key, "items": []}
            runs.append(current)
        current["items"].append(item)
    return runs


VIDEO_STORY_TARGET_SECONDS = 45.0
VIDEO_PASSTHROUGH_TARGET_SECONDS = 8.0
VIDEO_MIN_STEP_S = 1.0


def video_targets(runs):
    """Per-run seconds: each story group gets 45 s in total, split over its fragments by point count."""
    group_points = {}
    for run in runs:
        if run["key"][0] == "story":
            group_points[run["key"][1]] = group_points.get(run["key"][1], 0) + len(run["items"])
    for run in runs:
        category, group = run["key"]
        if category == "story":
            run["target"] = VIDEO_STORY_TARGET_SECONDS * len(run["items"]) / group_points[group]
        else:
            run["target"] = min(VIDEO_PASSTHROUGH_TARGET_SECONDS, max(1.0, len(run["items"]) * VIDEO_MIN_STEP_S))
    return runs


def thin_run(run, video):
    """Video preset: keep at most target/VIDEO_MIN_STEP_S evenly spread points (first and last kept)."""
    if not video:
        return run
    target = run["target"]
    keep = max(2, int(target / VIDEO_MIN_STEP_S) + 1)
    items = run["items"]
    if len(items) <= keep:
        return run
    step = (len(items) - 1) / (keep - 1)
    picked = [items[round(i * step)] for i in range(keep)]
    return {"key": run["key"], "items": picked, "target": target}


def run_interval(run, speed, video=False):
    category, group = run["key"]
    if video:
        steps = max(1, len(run["items"]) - 1)
        return run["target"] / steps / speed
    else:
        target = STORY_REGION_TARGET_SECONDS if category == "story" else PASSTHROUGH_TARGET_SECONDS
    steps = max(1, len(run["items"]) - 1)
    interval = target / steps
    interval = min(MAX_INTERVAL_S, max(MIN_INTERVAL_S, interval))
    return interval / speed


def describe_run(run):
    category, group = run["key"]
    codes = sorted({item[2] for item in run["items"] if item[2]})
    label = group if group else (",".join(codes) if codes else "no-region")
    return f"{category}:{label}"


class PauseController:
    def __init__(self):
        self.paused = threading.Event()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._listen, daemon=True)

    def start(self):
        self._thread.start()

    def toggle(self, *_):
        if self.paused.is_set():
            self.paused.clear()
            print("[resumed]", flush=True)
        else:
            self.paused.set()
            print("[paused]", flush=True)

    def stop(self):
        self._stop.set()

    def _listen(self):
        while not self._stop.is_set():
            try:
                line = sys.stdin.readline()
            except Exception:
                return
            if not line:
                return
            if self.paused.is_set():
                self.paused.clear()
                print("[resumed]")
            else:
                self.paused.set()
                print("[paused -- press Enter again to resume]")

    def wait_while_paused(self):
        while self.paused.is_set() and not self._stop.is_set():
            time.sleep(0.1)


def geo_fix(serial, lon, lat):
    run(["adb", "-s", serial, "emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}"],
        check=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--start-index", type=int, default=0)
    parser.add_argument("--pause", action="store_true", help="Enter toggles pause/resume mid-run")
    parser.add_argument("--speed", type=float, default=1.0, help="Overall speed multiplier (default 1.0)")
    parser.add_argument("--video", action="store_true", help="Recording preset: ~45 s per story region, ~4-5 min total")
    args = parser.parse_args()

    if args.speed <= 0:
        parser.error("--speed must be positive")

    import json
    route = json.loads(ROUTE_FILE.read_text())
    manifest = json.loads(MANIFEST_FILE.read_text())
    points = route["path"][args.start_index:]
    if not points:
        parser.error(f"--start-index {args.start_index} is past the end of the route ({len(route['path'])} points)")

    print(f"Loading {len(manifest['regions'])} region boundaries...")
    index = RegionIndex(manifest)

    print("Classifying route points into region runs...")
    runs = build_runs(points, index)
    if args.video:
        runs = [thin_run(r, True) for r in video_targets(runs)]

    total_seconds = sum(run_interval(run, args.speed, args.video) * max(1, len(run["items"]) - 1) for run in runs)
    story_runs = [r for r in runs if r["key"][0] == "story"]
    found_groups = {r["key"][1] for r in story_runs}
    missing_groups = set(STORY_GROUPS) - found_groups

    print(f"Route: {len(points)} points from index {args.start_index}, {len(runs)} pacing runs.")
    print(f"Story groups touched by this route: {sorted(found_groups)}")
    if missing_groups:
        print(f"WARNING: these story groups have NO route points inside them: {sorted(missing_groups)}")
    print(f"Estimated total replay time: {total_seconds / 60:.1f} minutes ({total_seconds:.0f}s) at speed={args.speed}x")
    if args.pause:
        print("Press Enter at any time to pause/resume.")
    print()

    pause_controller = PauseController()
    if args.pause:
        pause_controller.start()
    signal.signal(signal.SIGUSR1, pause_controller.toggle)

    emitted = 0
    try:
        for run_item in runs:
            interval = run_interval(run_item, args.speed, args.video)
            label = describe_run(run_item)
            print(f"-- entering run [{label}] ({len(run_item['items'])} points, ~{interval:.2f}s/point)")
            for lon, lat, code, name, category, group in run_item["items"]:
                pause_controller.wait_while_paused()
                started = time.monotonic()
                geo_fix(args.serial, lon, lat)
                emitted += 1
                if code:
                    print(f"  [{emitted}] ({lon:.5f},{lat:.5f}) in {name} ({code})")
                time.sleep(max(0.0, interval - (time.monotonic() - started)))
    except KeyboardInterrupt:
        print("\nInterrupted.")
    finally:
        if args.pause:
            pause_controller.stop()

    print(f"\nDone. Emitted {emitted} positions.")


if __name__ == "__main__":
    main()
