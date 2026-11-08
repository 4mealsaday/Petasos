"""Lightweight offline compiler checks; run with .local/region-venv/bin/python.

All shapefiles here are small synthetic fixtures, never the downloaded national dataset.
The normal host environment can discover these tests without installing GIS dependencies.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("build_region_dataset", ROOT / "scripts/build-region-dataset.py")
compiler = importlib.util.module_from_spec(spec)
try:
    spec.loader.exec_module(compiler)
    HAS_GIS = True
except ModuleNotFoundError:
    HAS_GIS = False


@unittest.skipUnless(HAS_GIS, "Run with the isolated scripts/requirements-regions.txt environment")
class RegionDatasetCompilerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="petasos-region-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / ".local/regions").mkdir(parents=True)

    def archive(self, entries=None):
        path = self.root / "input.zip"
        entries = entries or [(name, b"synthetic") for name in sorted(compiler.MEMBERS)]
        with warnings.catch_warnings(), zipfile.ZipFile(path, "w") as archive:
            warnings.filterwarnings("ignore", message="Duplicate name:", category=UserWarning)
            for name, value in entries:
                archive.writestr(name, value)
        return path

    def assert_error(self, code, callback):
        with self.assertRaises(compiler.DatasetError) as failure:
            callback()
        self.assertEqual(str(failure.exception), code)

    def test_archive_hash_is_required_before_opening_archive(self):
        path = self.archive()
        with patch.object(compiler.zipfile, "ZipFile", side_effect=AssertionError("Must check digest first")):
            self.assert_error("SOURCE_HASH_MISMATCH", lambda: compiler.validate_archive(path, "0" * 64))

    def test_archive_rejects_path_traversal_extra_duplicate_and_missing_members(self):
        good = [(name, b"synthetic") for name in sorted(compiler.MEMBERS)]
        variants = [
            [("../" + good[0][0], good[0][1]), *good[1:]],
            [*good, ("extra.txt", b"ignored")],
            [good[0], good[0], *good[2:]],
            good[1:],
        ]
        for entries in variants:
            with self.subTest(entries=[str(name) for name, _ in entries]):
                path = self.archive(entries)
                self.assert_error("UNEXPECTED_ARCHIVE_MEMBERS",
                                  lambda: compiler.validate_archive(path, compiler.digest_file(path)))

    def test_archive_rejects_symlink_and_empty_required_member(self):
        good = [(name, b"synthetic") for name in sorted(compiler.MEMBERS)]
        symlink = zipfile.ZipInfo(good[0][0])
        symlink.create_system = 3
        symlink.external_attr = (stat.S_IFLNK | 0o777) << 16
        path = self.archive([(symlink, b"../../elsewhere"), *good[1:]])
        self.assert_error("ARCHIVE_SYMLINK", lambda: compiler.validate_archive(path, compiler.digest_file(path)))
        path = self.archive([(good[0][0], b""), *good[1:]])
        self.assert_error("UNSUPPORTED_ARCHIVE", lambda: compiler.validate_archive(path, compiler.digest_file(path)))

    def test_archive_accepts_only_exact_reviewed_member_set(self):
        path = self.archive()
        with compiler.validate_archive(path, compiler.digest_file(path)) as archive:
            self.assertEqual(set(archive.namelist()), compiler.MEMBERS)

    def test_city_gu_and_independent_county_codes_are_preserved(self):
        fixtures = [
            ("4111000000", "수원시", "HJD005", "G0018113", "41110"),
            ("4111100000", "장안구", "HJD007", "G0018115", "41111"),
            ("4374000000", "영동군", "HJD006", "G0018114", "43740"),
            ("4374500000", "증평군", "HJD006", "G0018114", "43745"),
        ]
        for code, name, division, classification, expected in fixtures:
            self.assertEqual(compiler.mapping({"BJCD": code, "NAME": name, "DIVI": division, "SCLS": classification}),
                             (expected, name))

    def test_mapping_rejects_non_city_level_or_mismatched_classification(self):
        valid = {"BJCD": "4691000000", "NAME": "신안군", "DIVI": "HJD006", "SCLS": "G0018114"}
        for value in (4691000000, "46910", "4691010100", "469100000 ", "４６９１００００００"):
            self.assert_error("UNREVIEWED_LEGAL_CODE", lambda: compiler.mapping({**valid, "BJCD": value}))
        for values in ({"DIVI": "HJD000"}, {"DIVI": "HJD005"}, {"SCLS": "G0018115"}):
            self.assert_error("UNREVIEWED_REGION_CLASS", lambda: compiler.mapping({**valid, **values}))

    def test_binary_preserves_vertex_order_duplicates_and_hole_ring_order(self):
        outer = [(127.0, 37.0), (127.0, 37.0), (127.04, 37.0), (127.04, 37.04), (127.0, 37.04), (127.0, 37.0)]
        hole = [(127.01, 37.01), (127.01, 37.03), (127.03, 37.03), (127.03, 37.01), (127.01, 37.01)]
        points = compiler.np.asarray([*outer, *hole], dtype=compiler.np.float64)
        path = self.root / "ring.pgeo"
        bounds, size, digest = compiler.write_geometry(path, points, [0, len(outer)])
        rings, actual_points = self.decode(path)
        self.assertEqual(rings, [outer, hole])
        self.assertEqual(actual_points, len(points))
        self.assertEqual(bounds, [127.0, 37.0, 127.04, 37.04])
        self.assertEqual(size, 16 + 36 * 2 + 16 * len(points))
        self.assertEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_geometry_rejects_open_rings_nan_outside_envelope_and_bad_parts(self):
        valid = compiler.np.asarray([(127.0, 37.0), (127.04, 37.0), (127.04, 37.04), (127.0, 37.04), (127.0, 37.0)])
        invalid = []
        open_ring = valid.copy(); open_ring[-1, 0] = 127.001
        invalid.append((open_ring, [0], "INVALID_RING"))
        nan = valid.copy(); nan[1, 0] = float("nan")
        invalid.append((nan, [0], "INVALID_COORDINATES"))
        outside = valid.copy(); outside[1, 0] = 119.0
        invalid.append((outside, [0], "UNSUPPORTED_COORDINATES"))
        invalid.extend([(valid, [1], "INVALID_PARTS"), (valid, [0, 0], "INVALID_PARTS")])
        for index, (points, parts, code) in enumerate(invalid):
            self.assert_error(code, lambda: compiler.write_geometry(self.root / f"invalid-{index}.pgeo", points, parts))

    def test_binary_writer_never_overwrites_existing_file(self):
        path = self.root / "unchanged.pgeo"
        path.write_bytes(b"existing")
        points = compiler.np.asarray([(127.0, 37.0), (127.04, 37.0), (127.04, 37.04), (127.0, 37.0)])
        with self.assertRaises(FileExistsError):
            compiler.write_geometry(path, points, [0])
        self.assertEqual(path.read_bytes(), b"existing")

    def test_projection_uses_longitude_first_at_epsg5179_origin(self):
        source, description, review = self.dataset_fixture([("1111000000", self.valid_ring())])
        output = self.root / "output"
        with patch.object(compiler, "ROOT", self.root):
            compiler.compile_dataset(source, description, review, output, self.root / "audit.json")
        manifest = json.loads((output / "manifest.json").read_text())
        self.assertEqual(manifest["sourceCoordinateReferenceSystem"], "EPSG:5179")
        self.assertEqual(manifest["coordinateReferenceSystem"], "EPSG:4326")
        rings, count = self.decode(output / "11110.pgeo")
        self.assertEqual(count, len(self.valid_ring()))
        self.assertAlmostEqual(rings[0][0][0], 127.5, places=8)
        self.assertAlmostEqual(rings[0][0][1], 38.0, places=8)
        self.assertEqual(rings[0][0], rings[0][-1])
        self.assertGreaterEqual(float(manifest["transform"]["expectedAccuracyMeters"]), 0)

    def test_invalid_topology_is_quarantined_without_repair_or_drop(self):
        bowtie = [(1000000, 2000000), (1001000, 2001000), (1001000, 2000000), (1000000, 2001000), (1000000, 2000000)]
        source, description, review = self.dataset_fixture([
            ("1111000000", self.valid_ring()), ("1114000000", bowtie),
        ])
        output = self.root / "output"
        with patch.object(compiler, "ROOT", self.root):
            result = compiler.compile_dataset(source, description, review, output, self.root / "audit.json")
        self.assertEqual((result["recordCount"], result["readyCount"], result["quarantinedCount"]), (2, 1, 1))
        manifest = json.loads((output / "manifest.json").read_text())
        self.assertEqual([r["status"] for r in manifest["regions"]], ["ready", "quarantined"])
        self.assertEqual(manifest["regions"][1]["reasonCode"], "INVALID_TOPOLOGY")
        self.assertEqual(self.decode(output / "11140.pgeo")[1], len(bowtie))
        audit = json.loads((self.root / "audit.json").read_text())
        self.assertFalse(audit["simplified"])
        self.assertFalse(audit["repaired"])
        self.assertIsNone(audit["sourceEffectiveDate"])
        self.assertEqual(manifest["policyStatus"], "pending")

    def test_shapefile_only_holes_convention_repair_is_quarantined(self):
        # PyShp can reinterpret all CCW hole rings as exteriors and return valid GeoJSON.
        # Valid GEOS output alone must not certify that silently repaired source interpretation.
        source, description, review = self.dataset_fixture([("1111000000", list(reversed(self.valid_ring())))])
        output = self.root / "output"
        with patch.object(compiler, "ROOT", self.root):
            result = compiler.compile_dataset(source, description, review, output, self.root / "audit.json")
        self.assertEqual((result["readyCount"], result["quarantinedCount"]), (0, 1))
        manifest = json.loads((output / "manifest.json").read_text())
        self.assertEqual(manifest["regions"][0]["status"], "quarantined")
        self.assertEqual(self.decode(output / "11110.pgeo")[1], len(self.valid_ring()))
        audit = json.loads((self.root / "audit.json").read_text())
        self.assertEqual(audit["regions"][0]["interpretationErrors"], ["SHAPEFILE_ONLY_HOLES"])
        self.assertFalse(audit["regions"][0]["originalValid"])

    def test_shapefile_orphan_hole_convention_repair_is_quarantined(self):
        exterior = self.valid_ring()
        # A separate CCW ring is an orphaned hole; PyShp turns it into another exterior.
        orphan = [(x + 3000, y) for x, y in reversed(exterior)]
        second_exterior = [(x + 6000, y) for x, y in exterior]
        source, description, review = self.dataset_fixture([("1111000000", [exterior, second_exterior, orphan])])
        output = self.root / "output"
        with patch.object(compiler, "ROOT", self.root):
            result = compiler.compile_dataset(source, description, review, output, self.root / "audit.json")
        self.assertEqual((result["readyCount"], result["quarantinedCount"]), (0, 1))
        audit = json.loads((self.root / "audit.json").read_text())
        self.assertEqual(audit["regions"][0]["interpretationErrors"], ["SHAPEFILE_ORPHANED_HOLES"])
        self.assertFalse(audit["regions"][0]["originalValid"])
        self.assertEqual(self.decode(output / "11110.pgeo")[1], len(exterior) + len(second_exterior) + len(orphan))

    def test_source_or_description_hash_change_publishes_no_output(self):
        source, description, review_path = self.dataset_fixture([("1111000000", self.valid_ring())])
        review = json.loads(review_path.read_text())
        description.write_bytes(b"changed description")
        output = self.root / "output"
        with patch.object(compiler, "ROOT", self.root):
            self.assert_error("DESCRIPTION_HASH_MISMATCH", lambda: compiler.compile_dataset(
                source, description, review_path, output, self.root / "audit.json"))
        self.assertFalse(output.exists())
        description.write_bytes(b"synthetic schema document")
        source.write_bytes(source.read_bytes() + b"altered zip")
        with patch.object(compiler, "ROOT", self.root):
            self.assert_error("SOURCE_HASH_MISMATCH", lambda: compiler.compile_dataset(
                source, description, review_path, output, self.root / "audit.json"))
        self.assertFalse(output.exists())
        self.assertEqual(review["descriptionSha256"], compiler.digest_file(description))

    def test_duplicate_region_code_aborts_without_publishing_partial_dataset(self):
        source, description, review = self.dataset_fixture([
            ("1111000000", self.valid_ring()), ("1111000000", self.valid_ring()),
        ])
        output = self.root / "output"
        with patch.object(compiler, "ROOT", self.root):
            self.assert_error("DUPLICATE_REGION_CODE", lambda: compiler.compile_dataset(
                source, description, review, output, self.root / "audit.json"))
        self.assertFalse(output.exists())
        self.assertEqual(list((self.root / ".local/regions").iterdir()), [])

    @staticmethod
    def valid_ring():
        # SHP exterior winding is clockwise; EPSG:5179 false origin is an independent axis fixture.
        return [(1000000, 2000000), (1000000, 2001000), (1001000, 2001000), (1001000, 2000000), (1000000, 2000000)]

    def dataset_fixture(self, records):
        directory = self.root / "source"
        directory.mkdir()
        stem = directory / "N3A_G0100000"
        with compiler.shapefile.Writer(str(stem), shapeType=compiler.shapefile.POLYGON, encoding="cp949") as writer:
            for name, length in [("UFID", 34), ("BJCD", 10), ("NAME", 100), ("DIVI", 6), ("SCLS", 8), ("FMTA", 9)]:
                writer.field(name, "C", size=length)
            for index, (code, ring) in enumerate(records):
                rings = ring if isinstance(ring[0][0], (list, tuple)) else [ring]
                writer.poly(rings)
                writer.record("synthetic-" + str(index), code, "시험구", "HJD007", "G0018115", "R24020001")
        stem.with_suffix(".prj").write_text(compiler.pyproj.CRS.from_epsg(5179).to_wkt(version="WKT1_ESRI"))
        (directory / "N3A_G0100000.shp.xml").write_text('<metadata><refSysID><identCode code="5179"/><idCodeSpace>EPSG</idCodeSpace></refSysID></metadata>')
        source = self.root / "dataset.zip"
        with zipfile.ZipFile(source, "w") as archive:
            for path in sorted(directory.iterdir()):
                archive.write(path, path.name)
        description = self.root / "schema.pdf"
        description.write_bytes(b"synthetic schema document")
        review = {"kind": "petasos-ngii-boundary-source-review", "formatVersion": 1,
                  "descriptionSha256": compiler.digest_file(description), "zipSha256": compiler.digest_file(source),
                  "expectedRecords": len(records), "datasetVersion": "synthetic-v1", "source": {"contentHash": compiler.digest_file(source)}}
        review_path = self.root / "review.json"
        review_path.write_text(json.dumps(review))
        return source, description, review_path

    def decode(self, path):
        data = path.read_bytes()
        self.assertEqual(data[:8], b"PTRG0001")
        ring_count, total_count = struct.unpack_from(">ii", data, 8)
        offset = 16
        rings = []
        for _ in range(ring_count):
            bounds = struct.unpack_from(">4d", data, offset)
            count, = struct.unpack_from(">i", data, offset + 32)
            offset += 36
            ring = [struct.unpack_from(">2d", data, offset + index * 16) for index in range(count)]
            offset += count * 16
            self.assertEqual(bounds, (min(x for x, _ in ring), min(y for _, y in ring),
                                      max(x for x, _ in ring), max(y for _, y in ring)))
            rings.append(ring)
        self.assertEqual(offset, len(data))
        self.assertEqual(sum(map(len, rings)), total_count)
        return rings, total_count


if __name__ == "__main__":
    unittest.main()
