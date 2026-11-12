#!/usr/bin/env python3
"""GPS replay for the Seoul Station -> Daejeon Expo demo route.

Drives `adb emu geo fix` along a demo scenario's route.json path (demo/scenarios/<id>/) at a variable cadence: slow inside the
five story-bearing region groups (so the app has time to region-confirm and finish at least
one story), fast everywhere else. Region containment uses the exact same even-odd point-in-
polygon rule as the shipped PackedRegionBoundaryLookup, read directly from the pruned .pgeo
assets -- not a separate/divergent geometry implementation.

Usage:
    python3 scripts/demo_route.py [--scenario seoul-daejeon] [--serial emulator-5554] [--start-index N] [--pause] [--speed X] [--video]

--gpx FILE: write the --video pacing as a timestamped GPX track (no adb needed) and exit. Load it in the
emulator: Extended controls -> Location -> Routes -> Load GPX/KML -> Play route (demo/scenarios/seoul-daejeon/route.gpx is committed).

--video: recording preset (~7 min total). Pair it with the app's DEMO video mode so each region
plays one representative story (S01/S03/S05/S08/S10) + one 15 s music bridge:
    adb shell am broadcast --user 10 -f 0x20 -p com.petasos.app -a com.petasos.app.debug.SET_VIDEO_MODE --ez enabled true
Each story region is held ~45 s and pass-through stretches are sent as fast as the cadence floor allows.
Window landmarks listed in scenario.json video.landmarks get a real driving pace instead (landmarkPassSpeedMps,
from landmarkSlowBeforeM before their closest point to landmarkSlowAfterM after, fixes every ~20 m): the app
announces a landmark 8-12 s before passing it and ignores speeds no car reaches, so the fast-forward
stretches stay silent and those landmarks are announced in their real direction.

Every preset sends every point of the scenario's route.json, densified by linear interpolation so no two consecutive
fixes are more than --max-spacing metres apart (the app records each fix, so the saved line follows the road).
Time is compressed by shortening the send interval, never by skipping points. Fixes go through one
persistent emulator console connection (an `adb emu geo fix` process costs ~0.15-0.2 s per fix, too slow
for dense points); `adb emu` is the fallback when the console cannot be opened.
`kill -USR1 <pid>` toggles pause/resume as well as Enter (useful when stdin is not a terminal).
"""
import argparse
import json
import math
import signal
import socket
import struct
import sys
import threading
import time
from pathlib import Path
from subprocess import run

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS_DIR = ROOT / "demo" / "scenarios"
PACKS_DIR = ROOT / "demo" / "packs"
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
# script can route around, since the scenario's route.json is the real fetched Directions path.
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


def category_for(code, groups=None):
    if code is None:
        return "passthrough", None
    for group, codes in (groups or STORY_GROUPS).items():
        if code in codes:
            return "story", group
    return "passthrough", None


def build_runs(points, index, slow=None, groups=None):
    """Groups consecutive points by (category, group) so each contiguous stretch gets one pacing target.

    slow[i] is the seconds to wait before point i inside a landmark slow zone (None elsewhere).
    groups overrides STORY_GROUPS so a scenario can pace regions that carry no story (a music or
    conversation beat still needs dwell time)."""
    annotated = []
    for i, (lon, lat) in enumerate(points):
        code, name = index.containing_code(lon, lat)
        category, group = category_for(code, groups)
        annotated.append((lon, lat, code, name, category, group, slow[i] if slow else None))

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
# Cadence floor for any preset. The app stores ~20 points/s at most (each point re-checks region
# confirmation over its recent points); 20 Hz overran that, the app fell minutes behind and its 10 s
# freshness checks cut the recording. ~8 Hz keeps it at about 40% of that.
SEND_MIN_INTERVAL_S = 0.12
# The original points are the road's own vertices; inserts only split long straight stretches.
MAX_SPACING_M = 100.0


def distance_m(a, b):
    lat1, lat2 = math.radians(a[1]), math.radians(b[1])
    dlat, dlon = lat2 - lat1, math.radians(b[0] - a[0])
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * 6371000.0 * math.asin(math.sqrt(h))


def densify(points, max_spacing=MAX_SPACING_M):
    """Every original point is kept; straight segments longer than max_spacing get evenly spaced inserts."""
    out = [tuple(points[0])]
    for a, b in zip(points, points[1:]):
        pieces = max(1, math.ceil(distance_m(a, b) / max_spacing))
        for k in range(1, pieces + 1):
            t = k / pieces
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    return out


SLOW_SPACING_M = 20.0


def landmark_slow_zones(points, scenario, packs_dir, log=print):
    """-> (points, slow): points re-densified to ~20 m inside each video landmark's zone, and per-point waits."""
    video = scenario.get("video", {})
    names = video.get("landmarks") or []
    if not names:
        return points, None
    pack = json.loads((packs_dir / f"{scenario['packId']}.json").read_text())
    by_name = {landmark["name"]: landmark for landmark in pack["landmarks"]}
    speed = float(video.get("landmarkPassSpeedMps", 28))
    before, after = float(video.get("landmarkSlowBeforeM", 700)), float(video.get("landmarkSlowAfterM", 150))
    along = [0.0]
    for a, b in zip(points, points[1:]):
        along.append(along[-1] + distance_m(a, b))
    zones = []
    for name in names:
        landmark = by_name.get(name)
        if landmark is None:
            log(f"WARNING: video landmark '{name}' is not in pack {scenario['packId']}; skipped")
            continue
        target = (float(landmark["longitude"]), float(landmark["latitude"]))
        nearest = min(range(len(points)), key=lambda i: distance_m(points[i], target))
        zones.append((along[nearest] - before, along[nearest] + after))
        log(f"  slow zone: {name} at {along[nearest] / 1000:.1f} km ({speed:.0f} m/s)")
    out, slow = [points[0]], [None]
    for i in range(len(points) - 1):
        a, b = points[i], points[i + 1]
        inside = any(start <= along[i + 1] and along[i] <= end for start, end in zones)
        pieces = max(1, math.ceil(distance_m(a, b) / SLOW_SPACING_M)) if inside else 1
        for k in range(1, pieces + 1):
            t = k / pieces
            point = (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
            slow.append(distance_m(out[-1], point) / speed if inside else None)
            out.append(point)
    return out, slow


def video_targets(runs, per_group=None):
    """Per-run seconds: each story group gets its budget, split over its fragments by point count.

    A group needs room for one music track to finish plus the whole story, and a region with a
    window announcement needs that on top, so the budget is per group (scenario.json
    `videoStorySecondsPerGroup`) rather than one number for all."""
    per_group = per_group or {}
    group_points = {}
    for run in runs:
        if run["key"][0] == "story":
            group_points[run["key"][1]] = group_points.get(run["key"][1], 0) + len(run["items"])
    for run in runs:
        category, group = run["key"]
        if category == "story":
            budget = float(per_group.get(group, VIDEO_STORY_TARGET_SECONDS))
            run["target"] = budget * len(run["items"]) / group_points[group]
        else:
            run["target"] = VIDEO_PASSTHROUGH_TARGET_SECONDS * min(1.0, len(run["items"]) / 200)
    return runs


def run_interval(run, speed, video=False):
    """Seconds between fixes. Never thins: a run that cannot fit its target simply takes longer."""
    category, group = run["key"]
    steps = max(1, sum(1 for item in run["items"] if item[6] is None) - 1)
    if video:
        interval = run["target"] / steps
    else:
        target = STORY_REGION_TARGET_SECONDS if category == "story" else PASSTHROUGH_TARGET_SECONDS
        interval = min(MAX_INTERVAL_S, target / steps)
    return max(SEND_MIN_INTERVAL_S, interval / speed)


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


def write_gpx(path, runs, speed):
    """Same pacing as the live --video replay: each point is stamped with the cumulative replay time."""
    from datetime import datetime, timedelta, timezone
    clock = datetime(2026, 1, 1, 9, 0, 0, tzinfo=timezone.utc)
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<gpx version="1.1" creator="petasos demo_route.py" xmlns="http://www.topografix.com/GPX/1/1">',
             '  <trk><name>Seoul Station to Daejeon Expo (demo pacing)</name><trkseg>']
    count = 0
    for run_item in runs:
        interval = run_interval(run_item, speed, True)
        for lon, lat, *_rest, slow in run_item["items"]:
            if count:
                clock += timedelta(seconds=slow / speed if slow is not None else interval)
            lines.append(f'    <trkpt lat="{lat:.7f}" lon="{lon:.7f}"><ele>0</ele><time>{clock.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3]}Z</time></trkpt>')
            count += 1
    lines += ['  </trkseg></trk>', '</gpx>', '']
    path.write_text("\n".join(lines))
    total = (clock - datetime(2026, 1, 1, 9, 0, 0, tzinfo=timezone.utc)).total_seconds()
    print(f"Wrote {count} points, {total / 60:.1f} minutes, to {path}")


class GeoSender:
    """One authenticated emulator console socket; falls back to `adb emu` per fix if it cannot connect."""

    def __init__(self, serial):
        self.serial = serial
        self.sock = None
        self.buffer = b""
        if serial.startswith("emulator-"):
            try:
                self.sock = socket.create_connection(("127.0.0.1", int(serial.split("-", 1)[1])), timeout=5)
                self._read_ok()
                token_file = Path.home() / ".emulator_console_auth_token"
                if token_file.exists():
                    self._command(f"auth {token_file.read_text().strip()}")
            except (OSError, ValueError, RuntimeError) as error:
                print(f"Emulator console unavailable ({error}); falling back to adb emu (slow).")
                self.close()

    def _read_ok(self):
        while True:
            for line in self.buffer.split(b"\n")[:-1]:
                if line.startswith(b"KO"):
                    raise RuntimeError(line.decode(errors="replace").strip())
            if b"\nOK" in b"\n" + self.buffer or self.buffer.endswith(b"OK\r\n"):
                index = self.buffer.rfind(b"OK")
                self.buffer = self.buffer[index + 2:].lstrip(b"\r\n")
                return
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("console closed")
            self.buffer += chunk

    def _command(self, text):
        self.sock.sendall(text.encode() + b"\n")
        self._read_ok()

    def fix(self, lon, lat):
        if self.sock is not None:
            self._command(f"geo fix {lon:.7f} {lat:.7f}")
            return
        run(["adb", "-s", self.serial, "emu", "geo", "fix", f"{lon:.7f}", f"{lat:.7f}"],
            check=True, capture_output=True)

    def close(self):
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scenario", default="seoul-daejeon", help="demo/scenarios/<id>/ (scenario.json, route.json)")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--start-index", type=int, default=0)
    parser.add_argument("--pause", action="store_true", help="Enter toggles pause/resume mid-run")
    parser.add_argument("--speed", type=float, default=1.0, help="Overall speed multiplier (default 1.0)")
    parser.add_argument("--video", action="store_true", help="Recording preset: ~45 s per story region, every point sent")
    parser.add_argument("--max-spacing", type=float, default=MAX_SPACING_M,
                        help=f"Interpolate so consecutive fixes are at most this many metres apart (default {MAX_SPACING_M:.0f})")
    parser.add_argument("--gpx", type=Path, help="Write the video-paced route as a timestamped GPX file and exit")
    args = parser.parse_args()
    if args.gpx:
        args.video = True

    if args.speed <= 0:
        parser.error("--speed must be positive")

    scenario_dir = SCENARIOS_DIR / args.scenario
    scenario = json.loads((scenario_dir / "scenario.json").read_text())
    route = json.loads((scenario_dir / scenario["route"]).read_text())
    manifest = json.loads(MANIFEST_FILE.read_text())
    original = route["path"][args.start_index:]
    if not original:
        parser.error(f"--start-index {args.start_index} is past the end of the route ({len(route['path'])} points)")
    points = densify(original, args.max_spacing)

    print(f"Loading {len(manifest['regions'])} region boundaries...")
    index = RegionIndex(manifest)

    slow = None
    if args.video:
        points, slow = landmark_slow_zones(points, scenario, PACKS_DIR)
    print("Classifying route points into region runs...")
    story_groups = {name: set(codes) for name, codes in scenario["storyGroups"].items()} \
        if "storyGroups" in scenario else None
    # A region must hold one music track plus the story, or the story never gets a slot:
    # music is offered in DEMO_MUSIC_DURATION_MS chunks and the story only starts at a boundary.
    global VIDEO_STORY_TARGET_SECONDS
    VIDEO_STORY_TARGET_SECONDS = float(scenario.get("videoStorySeconds", VIDEO_STORY_TARGET_SECONDS))
    runs = build_runs(points, index, slow, story_groups)
    if args.video:
        runs = video_targets(runs, scenario.get("videoStorySecondsPerGroup"))

    if args.gpx:
        write_gpx(args.gpx, runs, args.speed)
        return

    total_seconds = sum(run_interval(run, args.speed, args.video) * max(1, sum(1 for item in run["items"] if item[6] is None) - 1)
                        + sum(item[6] for item in run["items"] if item[6] is not None) / args.speed for run in runs)
    story_runs = [r for r in runs if r["key"][0] == "story"]
    found_groups = {r["key"][1] for r in story_runs}
    missing_groups = set(STORY_GROUPS) - found_groups

    print(f"Route: {len(original)} original points from index {args.start_index}, densified to {len(points)} "
          f"(<= {args.max_spacing:.0f} m apart, none skipped), {len(runs)} pacing runs.")
    print(f"Story groups touched by this route: {sorted(found_groups)}")
    if missing_groups:
        print(f"WARNING: these story groups have NO route points inside them: {sorted(missing_groups)}")
    print(f"Estimated total replay time: {total_seconds / 60:.1f} minutes ({total_seconds:.0f}s) at speed={args.speed}x")
    if args.pause:
        print("Press Enter at any time to pause/resume.")
    print()

    sender = GeoSender(args.serial)
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
            for lon, lat, code, name, category, group, slow_wait in run_item["items"]:
                pause_controller.wait_while_paused()
                started = time.monotonic()
                sender.fix(lon, lat)
                emitted += 1
                if code and emitted % 20 == 0:
                    print(f"  [{emitted}] ({lon:.5f},{lat:.5f}) in {name} ({code})")
                wait = max(SEND_MIN_INTERVAL_S, slow_wait / args.speed) if slow_wait is not None else interval
                time.sleep(max(0.0, wait - (time.monotonic() - started)))
    except KeyboardInterrupt:
        print("\nInterrupted.")
    finally:
        sender.close()
        if args.pause:
            pause_controller.stop()

    print(f"\nDone. Emitted {emitted} positions.")


if __name__ == "__main__":
    main()
