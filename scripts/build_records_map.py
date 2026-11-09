#!/usr/bin/env python3
"""Build display-only silhouettes from pinned public NGII geometry, never collection geometry.

No user history or coordinates are read. Re-running writes one deterministic small UI asset.
"""
from pathlib import Path
import hashlib
import json
import math
import struct

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets"
MANIFEST = ASSETS / "regions/ngii-2026-09-21-v1/manifest.json"
OUTPUT = ASSETS / "records-map/regions-display-v1.json"
TOLERANCE = 0.002
MIN_RING_AREA = 0.00005


def simplify(points):
    keep = {0, len(points) - 1}
    stack = [(0, len(points) - 1)]
    while stack:
        start, end = stack.pop()
        ax, ay = points[start]
        bx, by = points[end]
        dx, dy = bx - ax, by - ay
        denominator = dx * dx + dy * dy
        furthest, index = 0.0, None
        for cursor in range(start + 1, end):
            px, py = points[cursor]
            t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / denominator)) if denominator else 0
            distance = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if distance > furthest:
                furthest, index = distance, cursor
        if index is not None and furthest > TOLERANCE ** 2:
            keep.add(index)
            stack.extend(((start, index), (index, end)))
    return [points[i] for i in sorted(keep)]


def ring_area(points):
    ox, oy = points[0]
    return abs(sum((a[0] - ox) * (b[1] - oy) - (b[0] - ox) * (a[1] - oy)
                   for a, b in zip(points, points[1:]))) / 2


def decode(entry):
    raw = (ASSETS / entry["geometryAsset"]).read_bytes()
    assert len(raw) == entry["geometryBytes"]
    assert hashlib.sha256(raw).hexdigest() == entry["geometrySha256"]
    magic, rings, count = struct.unpack_from(">8sii", raw)
    assert magic == b"PTRG0001" and rings == entry["ringCount"] and count == entry["pointCount"]
    position, total, decoded = 16, 0, []
    for _ in range(rings):
        *bounds, n = struct.unpack_from(">4di", raw, position)
        position += 36
        points = list(struct.iter_unpack(">dd", raw[position:position + n * 16]))
        position += n * 16
        total += n
        assert points[0] == points[-1] and all(math.isfinite(v) for p in points for v in p)
        decoded.append(points)
    assert position == len(raw) and total == count
    largest = max(range(len(decoded)), key=lambda i: ring_area(decoded[i]))
    result = []
    for i, points in enumerate(decoded):
        if i != largest and ring_area(points) < MIN_RING_AREA:
            continue
        # A closed ring is split at its furthest vertex before Douglas–Peucker simplification.
        start = points[0]
        split = max(range(len(points) - 1), key=lambda j: (points[j][0] - start[0]) ** 2 + (points[j][1] - start[1]) ** 2)
        simple = simplify(points[:split + 1])[:-1] + simplify(points[split:])
        if len(simple) < 4:
            simple = points
        result.append([[round(x, 6), round(y, 6)] for x, y in simple])
    assert result
    return result


def main():
    raw = MANIFEST.read_bytes()
    manifest = json.loads(raw)
    entries = manifest["regions"]
    regions = [{"regionId": e["regionId"], "name": e["name"], "code": e["code"], "rings": decode(e)}
               for e in entries if e["status"] == "ready"]
    data = {
        "formatVersion": 1, "displayOnly": True, "manifestSha256": hashlib.sha256(raw).hexdigest(),
        "attribution": "국토지리정보원·브이월드, CC BY 2.0 KR. 화면 표시용 경계 단순화·일부 소도서 생략.",
        "sourceUrl": manifest["source"]["sourceUrl"], "licenseUrl": manifest["source"]["licenseUrl"],
        "simplificationDegrees": TOLERANCE, "minimumRingAreaDegrees": MIN_RING_AREA,
        "excludedQuarantinedCodes": [e["code"] for e in entries if e["status"] != "ready"],
        "regions": regions,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"Display-only regions: {len(regions)}; bytes: {OUTPUT.stat().st_size}")


if __name__ == "__main__":
    main()
