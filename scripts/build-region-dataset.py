#!/usr/bin/env python3
"""Compile one hash-reviewed NGII SHP archive to bounded Android region assets.

Requires scripts/requirements-regions.txt in a separate environment. No network,
source repair, simplification, policy approval, or user location is involved.
"""
import argparse
from copy import deepcopy
import hashlib
import json
import logging
import math
from pathlib import Path
import re
import shutil
import struct
import sys
import tempfile
import zipfile

import numpy as np
import pyproj
import shapefile
import shapely
from shapely.geometry import shape
from shapely.ops import transform as geometry_transform

ROOT = Path(__file__).resolve().parents[1]
ASSET_PREFIX = "regions/ngii-2026-09-21-v1"
MAGIC = b"PTRG0001"
MAX_POINTS = 3_000_000
MAX_RINGS = 10_000
MAX_RING_POINTS = 500_000
MEMBERS = {"N3A_G0100000" + suffix for suffix in (".shp", ".shx", ".dbf", ".prj", ".shp.xml")}
INTERPRETATION_ERROR_CODES = {
    "polygon_only_holes": "SHAPEFILE_ONLY_HOLES",
    "polygon_orphaned_holes": "SHAPEFILE_ORPHANED_HOLES",
}


class DatasetError(ValueError):
    pass


def need(condition, code):
    if not condition:
        raise DatasetError(code)


def digest_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def mapping(record):
    """No county prefix heuristics or deduplication of legitimate city/gu overlaps."""
    legal = record["BJCD"]
    need(isinstance(legal, str) and re.fullmatch(r"[0-9]{5}00000", legal) is not None, "UNREVIEWED_LEGAL_CODE")
    classifications = {"HJD005": "G0018113", "HJD006": "G0018114", "HJD007": "G0018115"}
    need(record["DIVI"] in classifications and classifications[record["DIVI"]] == record["SCLS"], "UNREVIEWED_REGION_CLASS")
    name = record["NAME"]
    need(isinstance(name, str) and 0 < len(name.strip()) < 100 and not any(ord(c) < 32 for c in name), "INVALID_REGION_NAME")
    return legal[:5], name


def validate_archive(path, expected_sha):
    need(digest_file(path) == expected_sha, "SOURCE_HASH_MISMATCH")
    archive = zipfile.ZipFile(path)
    try:
        entries = archive.infolist()
        need(len(entries) == len(MEMBERS) and {i.filename for i in entries} == MEMBERS, "UNEXPECTED_ARCHIVE_MEMBERS")
        need(sum(i.file_size for i in entries) <= 200 * 1024 * 1024, "ARCHIVE_SIZE_LIMIT")
        need(all(not i.is_dir() and i.file_size > 0 and not i.flag_bits & 1 for i in entries), "UNSUPPORTED_ARCHIVE")
        need(all((i.external_attr >> 16) & 0o170000 != 0o120000 for i in entries), "ARCHIVE_SYMLINK")
        return archive
    except Exception:
        archive.close()
        raise


def write_geometry(path, points, parts):
    """Binary doubles preserve transformed vertices, ordering and every closed ring."""
    need(len(points) <= MAX_POINTS and 0 < len(parts) <= MAX_RINGS, "GEOMETRY_LIMIT")
    need(np.isfinite(points).all() and len(points.shape) == 2 and points.shape[1] == 2, "INVALID_COORDINATES")
    need(((points[:, 0] >= 120) & (points[:, 0] <= 135) & (points[:, 1] >= 30) & (points[:, 1] <= 45)).all(), "UNSUPPORTED_COORDINATES")
    need(parts[0] == 0 and all(a < b for a, b in zip(parts, parts[1:])), "INVALID_PARTS")
    bounds = [float(points[:, 0].min()), float(points[:, 1].min()), float(points[:, 0].max()), float(points[:, 1].max())]
    with Path(path).open("xb") as output:
        output.write(MAGIC + struct.pack(">ii", len(parts), len(points)))
        for start, end in zip(parts, [*parts[1:], len(points)]):
            ring = points[start:end]
            need(4 <= len(ring) <= MAX_RING_POINTS and np.array_equal(ring[0], ring[-1]), "INVALID_RING")
            rb = [float(ring[:, 0].min()), float(ring[:, 1].min()), float(ring[:, 0].max()), float(ring[:, 1].max())]
            output.write(struct.pack(">4di", *rb, len(ring)))
            output.write(ring.astype(">f8", copy=False).tobytes(order="C"))
    expected_size = 16 + 36 * len(parts) + 16 * len(points)
    need(Path(path).stat().st_size == expected_size <= 64 * 1024 * 1024, "GEOMETRY_SIZE_MISMATCH")
    return bounds, expected_size, digest_file(path)


def compile_dataset(source_zip, description, review_path, output_directory, audit_path):
    review = json.loads(Path(review_path).read_text())
    need(review["kind"] == "petasos-ngii-boundary-source-review" and review["formatVersion"] == 1, "INVALID_REVIEW")
    need(digest_file(description) == review["descriptionSha256"], "DESCRIPTION_HASH_MISMATCH")
    need(review["source"]["contentHash"] == review["zipSha256"], "SOURCE_HASH_MISMATCH")
    output = Path(output_directory)
    need(not output.exists(), "OUTPUT_ALREADY_EXISTS")
    output.parent.mkdir(parents=True, exist_ok=True)
    pyproj.network.set_network_enabled(False)
    audit = {"zipSha256": review["zipSha256"], "descriptionSha256": review["descriptionSha256"],
             "sourceEffectiveDate": None, "simplified": False, "repaired": False, "regions": [],
             "libraries": {"pyproj": pyproj.__version__, "proj": pyproj.proj_version_str, "shapely": shapely.__version__,
                           "geos": shapely.geos_version_string, "pyshp": shapefile.__version__, "numpy": np.__version__}}
    with tempfile.TemporaryDirectory(prefix="petasos-region-", dir=ROOT / ".local/regions") as work:
        work = Path(work)
        with validate_archive(source_zip, review["zipSha256"]) as archive:
            for name in sorted(MEMBERS):
                with archive.open(name) as src, (work / name).open("xb") as dst:
                    shutil.copyfileobj(src, dst, length=1024 * 1024)
        crs = pyproj.CRS.from_wkt((work / "N3A_G0100000.prj").read_text())
        need(crs.to_epsg() == 5179, "UNREVIEWED_SOURCE_CRS")
        xml = (work / "N3A_G0100000.shp.xml").read_text()
        need('code="5179"' in xml and 'EPSG' in xml, "SOURCE_CRS_DISAGREEMENT")
        transformer = pyproj.Transformer.from_crs(crs, "EPSG:4326", always_xy=True, allow_ballpark=False, only_best=True)
        need(math.isfinite(transformer.accuracy) and transformer.accuracy >= 0, "UNKNOWN_TRANSFORM_ACCURACY")
        stage = work / "assets"
        stage.mkdir()
        regions, seen = [], set()
        with shapefile.Reader(str(work / "N3A_G0100000.shp"), encoding="cp949", encodingErrors="strict") as reader:
            need(reader.shapeType == shapefile.POLYGON and reader.numRecords == review["expectedRecords"], "SOURCE_SCHEMA_CHANGED")
            need([f[0] for f in reader.fields[1:]] == ["UFID", "BJCD", "NAME", "DIVI", "SCLS", "FMTA"], "SOURCE_SCHEMA_CHANGED")
            for item in reader.iterShapeRecords():
                attrs = item.record.as_dict()
                code, name = mapping(attrs)
                need(code not in seen, "DUPLICATE_REGION_CODE")
                seen.add(code)
                points = np.asarray(item.shape.points, dtype=np.float64)
                lon, lat = transformer.transform(points[:, 0], points[:, 1], errcheck=True)
                transformed = np.column_stack((lon, lat))
                # Pinned PyShp 2.3.1 records convention repairs in Shape._errors: it can
                # turn orphaned/all-hole rings into valid exterior GeoJSON. GEOS validity
                # alone would certify that repaired interpretation. Quarantine every such
                # source and report only fixed codes, never PyShp warning text/coordinates.
                verbose = shapefile.VERBOSE
                try:
                    shapefile.VERBOSE = False
                    source_interface = item.shape.__geo_interface__
                finally:
                    shapefile.VERBOSE = verbose
                interpretation_errors = sorted({
                    INTERPRETATION_ERROR_CODES.get(key, "UNREVIEWED_SHAPEFILE_INTERPRETATION_ERROR")
                    for key, value in item.shape._errors.items() if value
                })
                geometry = shape(source_interface)
                del source_interface
                original_valid = bool(not interpretation_errors and geometry.is_valid and not geometry.is_empty and geometry.geom_type in ("Polygon", "MultiPolygon")
                                      and shapely.get_num_coordinates(geometry) == len(points))
                projected = geometry_transform(transformer.transform, geometry)
                projected_valid = bool(projected.is_valid and not projected.is_empty)
                status = "ready" if original_valid and projected_valid else "quarantined"
                filename = code + ".pgeo"
                bounds, byte_length, digest = write_geometry(stage / filename, transformed, list(item.shape.parts))
                row = {"regionId": "kr-sigungu-" + code,
                       "regionRevisionId": "kr-sigungu-" + code + "-" + review["datasetVersion"],
                       "code": code, "name": name, "geometryAsset": ASSET_PREFIX + "/" + filename,
                       "geometrySha256": digest, "geometryBytes": byte_length,
                       "ringCount": len(item.shape.parts), "pointCount": len(points),
                       "bounds": [format(value, ".17g") for value in bounds],
                       "status": status, "reasonCode": None if status == "ready" else "INVALID_TOPOLOGY"}
                regions.append(row)
                representative = projected.representative_point()
                audit["regions"].append({"code": code, "name": name, "classification": attrs["DIVI"],
                                         "originalValid": original_valid, "transformedValid": projected_valid,
                                         "interpretationErrors": interpretation_errors,
                                         "status": status, "pointCount": len(points), "ringCount": len(item.shape.parts),
                                         "representativePoint": [representative.x, representative.y]})
                # Progress contains public aggregate metadata only; no key or source geometry dump.
                if len(regions) % 25 == 0:
                    print(json.dumps({"processedRegions": len(regions)}, ensure_ascii=False), flush=True)
                del geometry, projected, points, transformed, lon, lat, item
        need(len(regions) == review["expectedRecords"], "SOURCE_RECORD_COUNT_MISMATCH")
        manifest = {"formatVersion": 1, "datasetVersion": review["datasetVersion"], "source": deepcopy(review["source"]),
                    "coordinateReferenceSystem": "EPSG:4326", "sourceCoordinateReferenceSystem": "EPSG:5179",
                    "transform": {"libraryVersion": "pyproj " + pyproj.__version__ + "/PROJ " + pyproj.proj_version_str,
                                  "operation": transformer.description, "expectedAccuracyMeters": format(transformer.accuracy, ".17g")},
                    "policyStatus": "pending", "regions": sorted(regions, key=lambda r: r["code"])}
        data = canonical(manifest)
        need(len(data) <= 256 * 1024, "MANIFEST_SIZE_LIMIT")
        (stage / "manifest.json").write_bytes(data)
        audit.update(manifestSha256=hashlib.sha256(data).hexdigest(), manifestBytes=len(data),
                     recordCount=len(regions), readyCount=sum(r["status"] == "ready" for r in regions),
                     quarantinedCount=sum(r["status"] == "quarantined" for r in regions),
                     totalGeometryBytes=sum(r["geometryBytes"] for r in regions),
                     totalPoints=sum(r["pointCount"] for r in regions), transform=manifest["transform"])
        # Rename on the same filesystem publishes only a complete prepared dataset.
        stage.rename(output)
    Path(audit_path).parent.mkdir(parents=True, exist_ok=True)
    Path(audit_path).write_bytes(canonical(audit) + b"\n")
    return {k: audit[k] for k in ("manifestSha256", "recordCount", "readyCount", "quarantinedCount", "totalGeometryBytes", "totalPoints")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zip", type=Path, required=True)
    parser.add_argument("--description", type=Path, required=True)
    parser.add_argument("--review", type=Path, default=ROOT / "docs/reference/regions/ngii-source-review.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--audit", type=Path, required=True)
    args = parser.parse_args()
    # PyShp may emit geometry diagnostics with large coordinates. Keep them out of stdout;
    # invalid geometry is quarantined through the explicit validity results instead.
    logging.getLogger("shapefile").setLevel(logging.CRITICAL)
    try:
        result = compile_dataset(args.zip, args.description, args.review, args.output, args.audit)
    except Exception as error:
        code = str(error) if isinstance(error, DatasetError) else "DATASET_COMPILATION_FAILED"
        print(json.dumps({"status": "FAILED", "errorCode": code}), file=sys.stderr)
        return 2
    print(json.dumps({"status": "PASS", **result}, ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
