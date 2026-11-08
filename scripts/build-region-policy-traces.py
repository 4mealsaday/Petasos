#!/usr/bin/env python3
"""Public NGII-only synthetic crossing fixtures. Never GPS/device/API/production policy input."""
import argparse
from collections import OrderedDict
import hashlib
import json
import math
import re
from pathlib import Path
import struct
import time

import numpy as np
import pyproj
import shapely
from shapely.geometry import Point, Polygon

MANIFEST = "regions/ngii-2026-09-21-v1/manifest.json"
MANIFEST_SHA = "3e2117159aa65c6c8f69826f534a8065d1247497039827d1b638f77d580f1bf9"
HIERARCHY = "regions/reviewed-hierarchy-v1.json"
HIERARCHY_SHA = "cf428d289dd504b3b8d1d481ea4e80f789a1db7e531ebd06ba749422a3971e5b"
CODES = ("11140", "41461", "41463", "41465", "41550", "44131", "44133", "30110", "30200")
RATES = (1, 2, 5)
ACCURACIES = (3, 10, 30)
KINDS = ("moving_dwell", "short_pass", "outside_boundary_bounce")
MAX_COORDINATES = 8192
MAX_LOADED_POINTS = 750_000
MAX_SINGLE_POINTS = 500_000
MAX_RESIDENT_POINTS = 100_000
MAX_RESIDENT_REGIONS = 16
MAX_EDGES = 4096
SCOPE = "PUBLIC_NGII_SYNTHETIC_CROSSINGS_NOT_ROADS_NOT_OBSERVED_LOCATIONS_NOT_APPROVED_POLICIES"


def need(value, code):
    if not value:
        raise ValueError(code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode() + b"\n"


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, "DUPLICATE_KEY")
            result[key] = value
        return result
    return json.loads(data.decode("utf-8"), object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("NONFINITE_JSON")))


def pin(path, expected, maximum):
    need(path.is_file() and not path.is_symlink(), "INVALID_SOURCE_FILE")
    need(path.stat().st_size <= maximum, "SOURCE_LIMIT")
    data = path.read_bytes()
    need(digest(data) == expected, "SOURCE_PIN_MISMATCH")
    return data


class Dataset:
    def __init__(self, assets, budget_seconds=120):
        self.assets = assets.resolve(strict=True)
        self.deadline = time.monotonic() + budget_seconds
        self.manifest = strict_json(pin(self.assets / MANIFEST, MANIFEST_SHA, 1_048_576))
        self.hierarchy = strict_json(pin(self.assets / HIERARCHY, HIERARCHY_SHA, 65_536))
        need(self.hierarchy["manifestSha256"] == MANIFEST_SHA and
             self.hierarchy["datasetVersion"] == self.manifest["datasetVersion"], "HIERARCHY_PIN_MISMATCH")
        self.entries = {entry["code"]: entry for entry in self.manifest["regions"]}
        need(len(self.entries) == len(self.manifest["regions"]) == 264, "REGION_IDENTITIES")
        self.by_revision = {entry["regionRevisionId"]: entry for entry in self.entries.values()}
        self.parents = {}
        for relation in self.hierarchy["relations"]:
            child, parent = relation["child"], relation["parent"]
            for node in (child, parent):
                entry = self.by_revision.get(node["regionRevisionId"])
                need(entry is not None and all(entry[key] == node[key] for key in ("code", "name", "regionId")), "HIERARCHY_IDENTITY")
            need(child["regionRevisionId"] not in self.parents, "HIERARCHY_DUPLICATE")
            self.parents[child["regionRevisionId"]] = parent["regionRevisionId"]
        self.parent_nodes = set(self.parents.values())
        for revision in self.parents:
            self.chain(revision)
        self.cache = OrderedDict()
        self.resident_points = 0
        self.peak_resident_points = 0
        self.loaded_points = 0
        self.loaded_regions = []
        self.to_meters = pyproj.Transformer.from_crs("EPSG:4326", "EPSG:5179", always_xy=True)
        self.to_wgs84 = pyproj.Transformer.from_crs("EPSG:5179", "EPSG:4326", always_xy=True)
        self.geod = pyproj.Geod(ellps="WGS84")

    def budget(self):
        need(time.monotonic() <= self.deadline, "GENERATOR_TIME_LIMIT")

    def chain(self, revision):
        result = []
        while revision is not None:
            need(revision not in result, "HIERARCHY_CYCLE")
            result.append(revision)
            revision = self.parents.get(revision)
        return result

    @staticmethod
    def in_bounds(entry, longitude, latitude):
        bounds = entry["bounds"]
        return float(bounds[0]) - 1e-10 <= longitude <= float(bounds[2]) + 1e-10 and \
            float(bounds[1]) - 1e-10 <= latitude <= float(bounds[3]) + 1e-10

    def geometry(self, entry):
        self.budget()
        code = entry["code"]
        if code in self.cache:
            self.cache.move_to_end(code)
            return self.cache[code]
        need(entry["status"] == "ready" and entry["pointCount"] <= min(MAX_SINGLE_POINTS, MAX_RESIDENT_POINTS), "GEOMETRY_LIMIT")
        need(self.loaded_points + entry["pointCount"] <= MAX_LOADED_POINTS, "GEOMETRY_TOTAL_LIMIT")
        while self.cache and (len(self.cache) >= MAX_RESIDENT_REGIONS or
                              self.resident_points + entry["pointCount"] > MAX_RESIDENT_POINTS):
            evicted, _ = self.cache.popitem(last=False)
            self.resident_points -= self.entries[evicted]["pointCount"]
        path = (self.assets / entry["geometryAsset"]).resolve(strict=True)
        need(path.is_relative_to(self.assets), "GEOMETRY_PATH")
        raw = pin(path, entry["geometrySha256"], 16 + 36 * entry["ringCount"] + 16 * entry["pointCount"])
        magic, ring_count, point_count = struct.unpack_from(">8sII", raw)
        need(magic == b"PTRG0001" and ring_count == entry["ringCount"] and point_count == entry["pointCount"], "GEOMETRY_HEADER")
        offset, count = 16, 0
        rings = []
        geometry = None
        for _ in range(ring_count):
            bounds = struct.unpack_from(">dddd", raw, offset)
            length = struct.unpack_from(">I", raw, offset + 32)[0]
            offset += 36
            need(4 <= length <= point_count and offset + 16 * length <= len(raw), "GEOMETRY_RING_SIZE")
            ring = np.frombuffer(raw, dtype=">f8", count=length * 2, offset=offset).reshape((length, 2))
            offset += 16 * length
            count += length
            need(np.isfinite(ring).all() and np.array_equal(ring[0], ring[-1]), "GEOMETRY_RING")
            need(120 <= ring[:, 0].min() <= ring[:, 0].max() <= 135 and
                 30 <= ring[:, 1].min() <= ring[:, 1].max() <= 45, "GEOMETRY_COORDINATES")
            need(tuple(map(float, (ring[:, 0].min(), ring[:, 1].min(), ring[:, 0].max(), ring[:, 1].max()))) == bounds,
                 "GEOMETRY_BOUNDS")
            polygon = Polygon(ring)
            need(polygon.is_valid and polygon.area > 0, "GEOMETRY_TOPOLOGY")
            # Independent GEOS even-odd reconstruction; no Kotlin ray casting/clearance reuse.
            geometry = polygon if geometry is None else geometry.symmetric_difference(polygon)
            rings.append(ring)
        need(offset == len(raw) and count == point_count and geometry.is_valid, "GEOMETRY_TRAILING_OR_COUNT")
        self.loaded_points += point_count
        self.loaded_regions.append(code)
        self.resident_points += point_count
        self.peak_resident_points = max(self.peak_resident_points, self.resident_points)
        self.cache[code] = (geometry, tuple(rings), raw)
        return self.cache[code]

    def oracle(self, longitude, latitude):
        self.budget()
        point = Point(longitude, latitude)
        matches = []
        clearances = []
        for entry in self.entries.values():
            if not self.in_bounds(entry, longitude, latitude):
                continue
            if entry["status"] != "ready":
                return None, 0, "quarantined"
            geometry = self.geometry(entry)[0]
            # Degrees ×70 km is a deliberately loose lower distance in the Korean envelope.
            # It is an independent guard for oracle comparisons, not the policy distance engine.
            clearance_mm = int(geometry.boundary.distance(point) * 70_000_000)
            if clearance_mm <= 5:
                return None, 0, "boundary"
            if geometry.contains(point):
                matches.append(entry["regionRevisionId"])
                clearances.append(clearance_mm)
        leaves = [revision for revision in matches if revision not in self.parent_nodes]
        if len(leaves) != 1 or set(matches) != set(self.chain(leaves[0])):
            return None, 0, "outside" if not matches else "ambiguous"
        return self.by_revision[leaves[0]]["code"], min(clearances), "leaf"

    def cross_section(self, code):
        entry = self.entries[code]
        geometry, rings, _ = self.geometry(entry)
        examined = 0
        for ring_index, ring in enumerate(rings):
            for edge_index in range(len(ring) - 1):
                examined += 1
                if examined > MAX_EDGES:
                    raise ValueError("CROSS_SECTION_SEARCH_LIMIT")
                self.budget()
                ax, ay = self.to_meters.transform(*ring[edge_index])
                bx, by = self.to_meters.transform(*ring[edge_index + 1])
                length = math.hypot(bx - ax, by - ay)
                if length < 40:
                    continue
                center = ((ax + bx) / 2, (ay + by) / 2)
                normal = (-(by - ay) / length, (bx - ax) / length)
                for sign in (1, -1):
                    inward = (normal[0] * sign, normal[1] * sign)
                    def at(distance, tangent=0):
                        return self.to_wgs84.transform(center[0] + inward[0] * distance - inward[1] * tangent,
                                                      center[1] + inward[1] * distance + inward[0] * tangent)
                    inside = self.oracle(*at(80))
                    outside = self.oracle(*at(-80))
                    if inside[0] != code or inside[1] < 45_000 or outside[0] in (None, code) or outside[1] < 45_000:
                        continue
                    neighbor = outside[0]
                    good = True
                    for distance in (-80, -40, -10, -2, 2, 10, 40, 80):
                        for tangent in (-10, 0, 10):
                            expected = code if distance > 0 else neighbor
                            if self.oracle(*at(distance, tangent))[0] != expected:
                                good = False
                                break
                        if not good:
                            break
                    if good:
                        return {"code": code, "regionRevisionId": entry["regionRevisionId"],
                                "geometrySha256": entry["geometrySha256"], "neighborCode": neighbor,
                                "neighborRegionRevisionId": self.entries[neighbor]["regionRevisionId"],
                                "ringIndex": ring_index, "edgeIndex": edge_index, "inwardSign": sign,
                                "examinedEdges": examined}, center, inward
        raise ValueError("NO_UNAMBIGUOUS_CROSS_SECTION")


def true_offset(kind, milliseconds):
    seconds = milliseconds / 1000
    if kind == "moving_dwell":
        return -80 + 20 * seconds if seconds < 8 else 80 if seconds <= 14 else 80 - 20 * (seconds - 14)
    if kind == "short_pass":
        return -80 + 27.5 * seconds if seconds <= 4 else 30 - 27.5 * (seconds - 4)
    return -1.0 if (milliseconds // 400) % 2 == 0 else -2.0


def build(assets):
    dataset = Dataset(assets)
    coordinates, oracle_rows, indexes = [], [], {}
    def coordinate(longitude, latitude):
        # String decimal coordinates keep the existing strict integer JSON parser usable.
        key = (f"{longitude:.9f}", f"{latitude:.9f}")
        if key not in indexes:
            need(len(coordinates) < MAX_COORDINATES, "COORDINATE_LIMIT")
            indexes[key] = len(coordinates)
            coordinates.append(list(key))
            leaf, clearance, classification = dataset.oracle(*map(float, key))
            need(classification in ("leaf", "boundary"), "TRACE_UNKNOWN_CONTAINMENT")
            oracle_rows.append([leaf, clearance])
        return indexes[key]
    regions = []
    error_pattern = ((0.0, 0.0), (0.75, 0.0), (-0.5, 0.5), (0.0, -0.75))
    for code in CODES:
        section, center, normal = dataset.cross_section(code)
        traces = []
        for kind in KINDS:
            end_ms = {"moving_dwell": 22_000, "short_pass": 8_000, "outside_boundary_bounce": 6_000}[kind]
            for hz in RATES:
                for accuracy in ACCURACIES:
                    samples = []
                    for sample_index, milliseconds in enumerate(range(0, end_ms + 1, 1000 // hz)):
                        offset = true_offset(kind, milliseconds)
                        x = center[0] + normal[0] * offset
                        y = center[1] + normal[1] * offset
                        true_point = dataset.to_wgs84.transform(x, y)
                        # Sample-index phase avoids frequency alias: every rate visits all four errors.
                        error_n, error_t = error_pattern[sample_index % len(error_pattern)]
                        measured_point = dataset.to_wgs84.transform(x + accuracy * (normal[0] * error_n - normal[1] * error_t),
                                                                 y + accuracy * (normal[1] * error_n + normal[0] * error_t))
                        true_index = coordinate(*true_point)
                        measured_index = coordinate(*measured_point)
                        rounded_true = tuple(map(float, coordinates[true_index]))
                        rounded_measured = tuple(map(float, coordinates[measured_index]))
                        error = dataset.geod.inv(*rounded_true, *rounded_measured)[2]
                        need(math.isfinite(error) and error <= accuracy, "SYNTHETIC_ERROR_OUTSIDE_RADIUS")
                        expected = code if offset > 0 else section["neighborCode"] if offset < 0 else None
                        need(oracle_rows[true_index][0] == expected, "TRUE_PATH_MEMBERSHIP_CHANGED")
                        samples.append([milliseconds, true_index, measured_index])
                    traces.append({"kind": kind, "rateHz": hz, "accuracyMeters": accuracy, "samples": samples})
        section["traces"] = traces
        regions.append(section)
    document = {"formatVersion": 1, "scope": SCOPE, "generatorVersion": "ngii-cross-section-v1",
                "generatorSha256": digest(Path(__file__).read_bytes()), "manifestSha256": MANIFEST_SHA,
                "hierarchySha256": HIERARCHY_SHA, "datasetVersion": dataset.manifest["datasetVersion"],
                "transformAccuracyMeters": dataset.manifest["transform"]["expectedAccuracyMeters"],
                "libraries": {"shapely": shapely.__version__, "geos": shapely.geos_version_string,
                              "pyproj": pyproj.__version__, "proj": pyproj.proj_version_str},
                "meterCrs": "EPSG:5179", "coordinateCrs": "EPSG:4326", "seed": "ordered-edges-and-time-pattern-v1",
                "ratesHz": list(RATES), "accuracyMeters": list(ACCURACIES), "kinds": list(KINDS),
                "limits": {"maxCoordinates": MAX_COORDINATES, "maxLoadedPoints": MAX_LOADED_POINTS,
                           "maxSinglePoints": MAX_SINGLE_POINTS, "maxResidentPoints": MAX_RESIDENT_POINTS,
                           "maxResidentRegions": MAX_RESIDENT_REGIONS, "maxEdgesPerRegion": MAX_EDGES},
                "syntheticModel": {"normalErrorPermille": [0, 750, -500, 0], "tangentErrorPermille": [0, 0, 500, -750],
                                   "errorPeriodStepSamples": 1, "fullCrossingSpeedMillimetersPerSecond": 20_000,
                                   "fullDwellMs": 6000, "shortPassSpeedMillimetersPerSecond": 27_500,
                                   "coordinatesDecimalPlaces": 9, "oracleClearanceLowerMetersPerDegree": 70_000,
                                   "oracleBoundaryToleranceMm": 5},
                "loadedGeometryCodes": sorted(set(dataset.loaded_regions)), "loadedGeometryPoints": dataset.loaded_points,
                "peakResidentGeometryPoints": dataset.peak_resident_points,
                "approvedPolicyCount": 0, "networkUsed": False, "deviceLocationUsed": False, "naverResponseStored": False,
                "coordinates": coordinates, "oracle": oracle_rows, "regions": regions}
    validate(document)
    return document


def validate(document):
    keys = {"formatVersion", "scope", "generatorVersion", "generatorSha256", "manifestSha256", "hierarchySha256",
            "datasetVersion", "transformAccuracyMeters", "libraries", "meterCrs", "coordinateCrs", "seed", "ratesHz",
            "accuracyMeters", "kinds", "limits", "syntheticModel", "loadedGeometryCodes", "loadedGeometryPoints", "peakResidentGeometryPoints",
            "approvedPolicyCount", "networkUsed", "deviceLocationUsed", "naverResponseStored", "coordinates", "oracle", "regions"}
    need(type(document) is dict and set(document) == keys, "FIXTURE_KEYS")
    need(type(document["formatVersion"]) is int and document["formatVersion"] == 1 and document["scope"] == SCOPE and document["generatorVersion"] == "ngii-cross-section-v1",
         "FIXTURE_KIND")
    need(document["manifestSha256"] == MANIFEST_SHA and document["hierarchySha256"] == HIERARCHY_SHA, "FIXTURE_PINS")
    need(type(document["approvedPolicyCount"]) is int and document["approvedPolicyCount"] == 0 and all(document[key] is False for key in
         ("networkUsed", "deviceLocationUsed", "naverResponseStored")), "FIXTURE_AUTHORITY")
    need(document["ratesHz"] == list(RATES) and all(type(x) is int for x in document["ratesHz"]) and
         document["accuracyMeters"] == list(ACCURACIES) and all(type(x) is int for x in document["accuracyMeters"]) and
         document["kinds"] == list(KINDS), "FIXTURE_MATRIX")
    need(re.fullmatch(r"[0-9a-f]{64}", document["generatorSha256"]) is not None and
         document["datasetVersion"] == "ngii-d7a3751bd89f-wgs84-stream-v1" and
         document["transformAccuracyMeters"] == "1" and document["meterCrs"] == "EPSG:5179" and
         document["coordinateCrs"] == "EPSG:4326" and document["seed"] == "ordered-edges-and-time-pattern-v1", "FIXTURE_METADATA")
    need(type(document["libraries"]) is dict and set(document["libraries"]) == {"shapely", "geos", "pyproj", "proj"} and
         all(type(x) is str and 0 < len(x) <= 100 and not any(ord(c) < 32 for c in x)
             for x in document["libraries"].values()), "FIXTURE_LIBRARIES")
    need(type(document["limits"]) is dict and all(type(x) is int for x in document["limits"].values()) and
         document["limits"] == {"maxCoordinates": MAX_COORDINATES, "maxLoadedPoints": MAX_LOADED_POINTS,
         "maxSinglePoints": MAX_SINGLE_POINTS, "maxResidentPoints": MAX_RESIDENT_POINTS,
         "maxResidentRegions": MAX_RESIDENT_REGIONS, "maxEdgesPerRegion": MAX_EDGES}, "FIXTURE_LIMITS")
    need(type(document["syntheticModel"]) is dict and
         all(type(x) is int or type(x) is list and all(type(v) is int for v in x) for x in document["syntheticModel"].values()) and
         document["syntheticModel"] == {"normalErrorPermille": [0, 750, -500, 0], "tangentErrorPermille": [0, 0, 500, -750],
         "errorPeriodStepSamples": 1, "fullCrossingSpeedMillimetersPerSecond": 20000, "fullDwellMs": 6000,
         "shortPassSpeedMillimetersPerSecond": 27500, "coordinatesDecimalPlaces": 9,
         "oracleClearanceLowerMetersPerDegree": 70000, "oracleBoundaryToleranceMm": 5}, "FIXTURE_MODEL")
    loaded = document["loadedGeometryCodes"]
    need(type(loaded) is list and len(loaded) <= 264 and all(type(x) is str and re.fullmatch(r"[0-9]{5}", x) for x in loaded)
         and loaded == sorted(set(loaded)) and set(CODES) <= set(loaded), "FIXTURE_LOADED_REGIONS")
    need(type(document["loadedGeometryPoints"]) is int and 0 < document["loadedGeometryPoints"] <= MAX_LOADED_POINTS and
         type(document["peakResidentGeometryPoints"]) is int and
         0 < document["peakResidentGeometryPoints"] <= MAX_RESIDENT_POINTS, "FIXTURE_LOADED_POINTS")
    coordinates, oracle = document["coordinates"], document["oracle"]
    need(type(coordinates) is list and 1 <= len(coordinates) <= MAX_COORDINATES and type(oracle) is list and
         len(oracle) == len(coordinates), "FIXTURE_COORDINATES")
    for pair, truth in zip(coordinates, oracle):
        need(type(pair) is list and len(pair) == 2 and all(type(x) is str and re.fullmatch(r"[0-9]{2,3}\.[0-9]{9}", x) for x in pair), "FIXTURE_COORDINATE_TYPE")
        lon, lat = map(float, pair)
        need(math.isfinite(lon) and math.isfinite(lat) and 120 <= lon <= 135 and 30 <= lat <= 45, "FIXTURE_COORDINATE_RANGE")
        need(type(truth) is list and len(truth) == 2 and (truth[0] is None or type(truth[0]) is str and truth[0].isascii() and
             truth[0].isdigit() and len(truth[0]) == 5) and type(truth[1]) is int and truth[1] >= 0, "FIXTURE_ORACLE")
    need(type(document["regions"]) is list and [r["code"] for r in document["regions"]] == list(CODES), "FIXTURE_REGIONS")
    for region in document["regions"]:
        need(set(region) == {"code", "regionRevisionId", "geometrySha256", "neighborCode", "neighborRegionRevisionId",
                             "ringIndex", "edgeIndex", "inwardSign", "examinedEdges", "traces"}, "FIXTURE_REGION_KEYS")
        need(region["neighborCode"] != region["code"] and region["inwardSign"] in (-1, 1), "FIXTURE_NEIGHBOR")
        need(region["neighborCode"] in loaded and all(type(region[x]) is str and re.fullmatch(r"[A-Za-z0-9_.:-]{1,200}", region[x])
             for x in ("regionRevisionId", "neighborRegionRevisionId")) and
             re.fullmatch(r"[0-9a-f]{64}", region["geometrySha256"]) is not None, "FIXTURE_REGION_IDENTITY")
        need(all(type(region[x]) is int and 0 <= region[x] < MAX_EDGES for x in ("ringIndex", "edgeIndex")) and
             type(region["inwardSign"]) is int and type(region["examinedEdges"]) is int and
             1 <= region["examinedEdges"] <= MAX_EDGES, "FIXTURE_SECTION")
        traces = region["traces"]
        need([(t["kind"], t["rateHz"], t["accuracyMeters"]) for t in traces] ==
             [(kind, rate, accuracy) for kind in KINDS for rate in RATES for accuracy in ACCURACIES], "FIXTURE_TRACE_MATRIX")
        for trace in traces:
            need(set(trace) == {"kind", "rateHz", "accuracyMeters", "samples"}, "FIXTURE_TRACE_KEYS")
            need(type(trace["rateHz"]) is int and type(trace["accuracyMeters"]) is int, "FIXTURE_TRACE_TYPES")
            end = {"moving_dwell": 22000, "short_pass": 8000, "outside_boundary_bounce": 6000}[trace["kind"]]
            expected_times = list(range(0, end + 1, 1000 // trace["rateHz"]))
            need([row[0] for row in trace["samples"]] == expected_times, "FIXTURE_TIMES")
            for row in trace["samples"]:
                need(type(row) is list and len(row) == 3 and all(type(x) is int for x in row) and
                     all(0 <= index < len(coordinates) for index in row[1:]), "FIXTURE_SAMPLE")
            if trace["kind"] == "outside_boundary_bounce":
                need(all(oracle[row[1]][0] == region["neighborCode"] for row in trace["samples"]),
                     "FIXTURE_BOUNCE_TRUE_ENTERED")
                observed_codes = {oracle[row[2]][0] for row in trace["samples"]}
                need(region["code"] in observed_codes and region["neighborCode"] in observed_codes,
                     "FIXTURE_BOUNCE_SAMPLING_ALIAS")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    need(not args.output.exists(), "OUTPUT_ALREADY_EXISTS")
    result = build(args.assets)
    data = json_bytes(result)
    need(len(data) <= 2_097_152, "FIXTURE_BYTES_LIMIT")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("xb") as stream:
        stream.write(data)
    print(json.dumps({"scope": SCOPE, "sha256": digest(data), "bytes": len(data), "regions": len(result["regions"]),
                      "coordinates": len(result["coordinates"]), "loadedGeometryPoints": result["loadedGeometryPoints"]}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, struct.error) as error:
        # No coordinates, raw metadata, file contents or external exception text in diagnostics.
        raise SystemExit("PUBLIC_REGION_TRACE_GENERATION_FAILED") from None
