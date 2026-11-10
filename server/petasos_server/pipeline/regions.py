"""The app's own si/gun/gu boundaries (the pruned NGII .pgeo assets), read with the same even-odd rule as
PackedRegionBoundaryLookup. Shared by build_pack and scripts/demo_route.py so both agree with the app."""
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ASSETS_DIR = ROOT / "app/src/main/assets"
MANIFEST_FILE = ASSETS_DIR / "regions/ngii-2026-09-21-v1/manifest.json"
MAGIC = 0x5054524730303031  # ASCII "PTRG0001"


def read_pgeo_rings(path):
    data = Path(path).read_bytes()
    magic, ring_count, _point_count = struct.unpack_from(">qii", data, 0)
    if magic != MAGIC:
        raise ValueError(f"bad .pgeo magic in {path}")
    offset = 16
    rings = []
    for _ in range(ring_count):
        _a, _b, _c, _d, ring_points = struct.unpack_from(">ddddi", data, offset)
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
        for i in range(1, len(ring)):
            prev_lon, prev_lat = ring[i - 1]
            next_lon, next_lat = ring[i]
            if (prev_lat > lat) != (next_lat > lat):
                crossing = prev_lon + (lat - prev_lat) * (next_lon - prev_lon) / (next_lat - prev_lat)
                if lon < crossing:
                    inside = not inside
    return inside


class RegionIndex:
    def __init__(self, manifest_file=MANIFEST_FILE):
        manifest = json.loads(Path(manifest_file).read_text())
        self.entries = []
        for region in manifest["regions"]:
            self.entries.append({
                "code": region["code"], "name": region["name"],
                "bounds": [float(v) for v in region["bounds"]],  # [min_lon, min_lat, max_lon, max_lat]
                "rings": read_pgeo_rings(ASSETS_DIR / region["geometryAsset"]),
            })

    def containing(self, lon, lat):
        """All containing regions, most specific (autonomous gu inside a si) last."""
        found = []
        for entry in self.entries:
            min_lon, min_lat, max_lon, max_lat = entry["bounds"]
            if min_lon <= lon <= max_lon and min_lat <= lat <= max_lat and point_in_rings(lon, lat, entry["rings"]):
                found.append(entry)
        return sorted(found, key=lambda e: e["code"].endswith("0") is False)

    def containing_code(self, lon, lat):
        """The most specific containing code and name (gu over its parent si), or (None, None)."""
        found = self.containing(lon, lat)
        return (found[-1]["code"], found[-1]["name"]) if found else (None, None)
