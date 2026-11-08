from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
import hashlib
import importlib.util
from io import StringIO
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("merge_content_evidence", ROOT / "scripts/merge-content-evidence.py")
merge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(merge)
from petasos_server.content_bundle import build_reviewed_bundle, canonical_bytes
from petasos_server.evidence import EvidenceRegistry

NOW = 1_700_000_000_000


def source(key, grants=None):
    return {"sourceRevisionId": key, "text": "PRIVATE_SYNTHETIC_SOURCE_TEXT_" + key,
            "allowedDeviceIds": ["synthetic-device"] if grants is None else grants, "reviewed": True}


def fact(key, sources):
    return {"factRevisionId": key, "factId": key + "-stable", "kind": "fact",
            "text": "PRIVATE_SYNTHETIC_FACT_TEXT_" + key, "sourceRevisionIds": sources}


def base_registry():
    return {"sources": [source("old-b", ["device-z", "device-a"]), source("old-a", [])],
            "facts": [fact("old-fact", ["old-b", "old-a"])]}


def incoming_registry():
    return {"sources": [source("new-source")], "facts": [fact("new-fact", ["new-source"])]}


def bundle_fixture(generation=True):
    return build_reviewed_bundle({
        "formatVersion": 2, "kind": "petasos-content-source-review", "reviewMetadata": {},
        "sources": [{"stableKey": "synthetic-source", "provider": "synthetic",
                     "sourceUrl": "https://example.test/source", "title": "Synthetic source", "fetchedAt": NOW - 20,
                     "sourceUpdatedAt": None, "sourceUpdatedRaw": None, "verifiedAt": NOW - 10,
                     "license": {"type": "synthetic-test-only", "url": "https://example.test/rights", "scope": "text", "reviewBasis": "test only"},
                     "allowedUses": {"allowGeneration": generation, "allowDisplay": True, "allowDistribution": True, "reviewBasis": "test only"},
                     "attribution": "synthetic", "reviewStatus": "verified", "normalizedText": "PRIVATE_SYNTHETIC_BUNDLE_TEXT"}],
        "facts": [{"stableKey": "synthetic-fact", "version": 1, "statement": "PRIVATE_SYNTHETIC_FACT",
                   "kind": "verified_fact", "verifiedAt": NOW - 10, "sourceKeys": ["synthetic-source"]}],
        "places": [],
        "stories": [{"stableKey": "synthetic-story", "topicKey": "synthetic-topic", "title": "Synthetic story",
                     "coreQuestion": "Synthetic question?", "interestTags": [], "placeKey": None}],
        "versions": [{"storyKey": "synthetic-story", "version": 1, "createdAt": NOW, "verifiedAt": NOW,
                      "validUntil": None, "estimatedDurationMs": 1000, "status": "published",
                      "paragraphs": [{"stableKey": "synthetic-paragraph", "ordinal": 0, "required": True,
                                      "text": "PRIVATE_SYNTHETIC_PARAGRAPH", "audioRef": None,
                                      "estimatedDurationMs": 1000, "factKeys": ["synthetic-fact"]}]}],
        "regionBindings": [{"storyKey": "synthetic-story", "regionId": "synthetic-region", "regionRevisionId": "synthetic-region-r1",
                            "regionCode": "12345", "datasetVersion": "synthetic-dataset", "regionName": "Synthetic region",
                            "regionSourceRevisionId": "synthetic-geometry", "associationFactKey": "synthetic-fact",
                            "verifiedAt": NOW, "reviewBasis": "synthetic association"}],
    }, NOW)


class MergeEvidenceTests(unittest.TestCase):
    def assert_error(self, code, base, incoming):
        with self.assertRaisesRegex(merge.CliError, "^" + code + "$"):
            merge.merge_evidence(base, incoming)

    def test_append_preserves_every_old_row_order_and_revoked_grant_without_mutation(self):
        base, incoming = base_registry(), incoming_registry()
        original_base, original_incoming = deepcopy(base), deepcopy(incoming)
        result = merge.merge_evidence(base, incoming)
        self.assertEqual(result["sources"], base["sources"] + incoming["sources"])
        self.assertEqual(result["facts"], base["facts"] + incoming["facts"])
        self.assertEqual(result["sources"][1]["allowedDeviceIds"], [])
        self.assertEqual(base, original_base)
        self.assertEqual(incoming, original_incoming)
        result["sources"][0]["text"] = "changed result only"
        self.assertEqual(base, original_base)

    def test_exact_reimport_is_idempotent_including_normalized_reference_and_grant_order(self):
        base = base_registry()
        incoming = deepcopy(base)
        incoming["sources"][0]["allowedDeviceIds"].reverse()
        incoming["facts"][0]["sourceRevisionIds"].reverse()
        self.assertEqual(merge.merge_evidence(base, incoming), base)
        first = merge.merge_evidence(base, incoming_registry())
        self.assertEqual(merge.merge_evidence(first, incoming_registry()), first)

    def test_same_source_revision_cannot_change_text(self):
        base = incoming_registry()
        incoming = deepcopy(base)
        incoming["sources"][0]["text"] += " changed"
        self.assert_error("EVIDENCE_REVISION_CONFLICT", base, incoming)

    def test_same_fact_revision_cannot_change_stable_fact_kind_text_or_sources(self):
        base = base_registry()
        for field, value in (("factId", "different-stable"), ("kind", "tradition"),
                             ("text", "changed text"), ("sourceRevisionIds", ["old-b"])):
            with self.subTest(field=field):
                incoming = deepcopy(base)
                incoming["facts"][0][field] = value
                self.assert_error("EVIDENCE_REVISION_CONFLICT", base, incoming)

    def test_import_cannot_expand_reduce_or_restore_existing_grants(self):
        for old, new in ((["synthetic-device"], ["synthetic-device", "another-device"]),
                         (["synthetic-device", "another-device"], ["synthetic-device"]),
                         ([], ["synthetic-device"]), (["synthetic-device"], [])):
            with self.subTest(old_count=len(old), new_count=len(new)):
                base, incoming = incoming_registry(), incoming_registry()
                base["sources"][0]["allowedDeviceIds"] = old
                incoming["sources"][0]["allowedDeviceIds"] = new
                self.assert_error("EVIDENCE_GRANT_CONFLICT", base, incoming)

    def test_each_document_must_be_valid_before_merge(self):
        invalid = []
        value = incoming_registry(); value["unknown"] = True; invalid.append(value)
        value = incoming_registry(); value["sources"] *= 2; invalid.append(value)
        value = incoming_registry(); value["facts"][0]["sourceRevisionIds"] = ["missing"]; invalid.append(value)
        value = incoming_registry(); value["sources"][0]["reviewed"] = False; invalid.append(value)
        for value in invalid:
            self.assert_error("INVALID_EVIDENCE_REGISTRY", value, incoming_registry())
            self.assert_error("INVALID_EVIDENCE_REGISTRY", base_registry(), value)

    def test_size_limit_includes_written_newline(self):
        base = base_registry()
        size = len(canonical_bytes(base))
        with patch.object(merge, "MAX_EVIDENCE_BYTES", size):
            self.assert_error("EVIDENCE_FILE_TOO_LARGE", base, {"sources": [], "facts": []})
        with patch.object(merge, "MAX_EVIDENCE_BYTES", size + 1):
            self.assertEqual(merge.merge_evidence(base, {"sources": [], "facts": []}), base)


class MergeEvidenceCliTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.private = self.root / ".local"
        self.content = self.private / "content"
        for path in (self.private, self.private / "server", self.content, self.content / "bundles"):
            path.mkdir(mode=0o700)
        self.base = self.private / "server/base.json"
        self.bundle = self.content / "bundles/reviewed.json"
        self.output = self.content / "evidence/merged.json"
        self.write(self.base, base_registry())
        self.write(self.bundle, bundle_fixture())
        for name, value in (("ROOT", self.root), ("PRIVATE_ROOT", self.private), ("CONTENT_ROOT", self.content)):
            patcher = patch.object(merge, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def write(self, path, value):
        path.write_bytes(canonical_bytes(value) + b"\n")
        path.chmod(0o600)

    def run_cli(self, extra=None):
        args = ["--base", str(self.base), "--bundle", str(self.bundle),
                "--allow-device-id", "PRIVATE_SYNTHETIC_DEVICE_ID", "--output", str(self.output)]
        stdout, stderr = StringIO(), StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = merge.main(args + (extra or []))
        self.assertEqual(stderr.getvalue(), "")
        self.assertNotIn("PRIVATE_SYNTHETIC", stdout.getvalue())
        self.assertNotIn(str(self.root), stdout.getvalue())
        return code, json.loads(stdout.getvalue())

    def test_writes_new_private_file_preserving_original_bytes_and_rows(self):
        old_base, old_bundle = self.base.read_bytes(), self.bundle.read_bytes()
        code, report = self.run_cli()
        self.assertEqual(code, 0)
        self.assertEqual(report["counts"], {"sources": 3, "facts": 2})
        self.assertEqual(report["preserved"], {"sources": 2, "facts": 1})
        self.assertEqual(report["added"], {"sources": 1, "facts": 1})
        self.assertEqual(report["networkRequests"], 0)
        self.assertEqual(report["sha256"], hashlib.sha256(self.output.read_bytes()).hexdigest())
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.output.parent.stat().st_mode), 0o700)
        self.assertEqual(self.base.read_bytes(), old_base)
        self.assertEqual(self.bundle.read_bytes(), old_bundle)
        result = json.loads(self.output.read_bytes())
        self.assertEqual(result["sources"][:2], base_registry()["sources"])
        self.assertEqual(result["facts"][:1], base_registry()["facts"])
        EvidenceRegistry(result)

    def test_existing_output_is_never_overwritten(self):
        self.output.parent.mkdir(mode=0o700)
        self.output.write_bytes(b"existing output")
        code, report = self.run_cli()
        self.assertEqual((code, report["status"]), (1, "OUTPUT_ALREADY_EXISTS"))
        self.assertEqual(self.output.read_bytes(), b"existing output")

    def test_generation_disallowed_bundle_does_not_activate_sources(self):
        self.write(self.bundle, bundle_fixture(generation=False))
        code, report = self.run_cli()
        self.assertEqual(code, 0)
        self.assertEqual(report["added"], {"sources": 0, "facts": 0})
        self.assertEqual(json.loads(self.output.read_bytes()), base_registry())

    def test_conflict_never_creates_output_and_does_not_disclose_payload(self):
        incoming = merge.build_evidence_registry(bundle_fixture(), ["PRIVATE_SYNTHETIC_DEVICE_ID"])
        incoming["sources"][0]["allowedDeviceIds"] = []
        self.write(self.base, incoming)
        code, report = self.run_cli()
        self.assertEqual((code, report["status"]), (1, "EVIDENCE_GRANT_CONFLICT"))
        self.assertFalse(self.output.exists())

    def test_unknown_argument_has_no_argument_or_payload_echo(self):
        code, report = self.run_cli(["--PRIVATE_SYNTHETIC_ARGUMENT"])
        self.assertEqual((code, report["status"]), (1, "INVALID_ARGUMENTS"))
        self.assertFalse(self.output.exists())

    def test_unreviewed_or_unknown_bundle_schema_is_rejected(self):
        for mutate in (lambda value: value.update(formatVersion=True),
                       lambda value: value.update(unexpected="PRIVATE_SYNTHETIC_VALUE"),
                       lambda value: value["sources"][0].update(reviewStatus="pending")):
            with self.subTest(mutate=mutate):
                bundle = bundle_fixture()
                mutate(bundle)
                self.write(self.bundle, bundle)
                code, report = self.run_cli()
                self.assertEqual(code, 1)
                self.assertFalse(report["outputSaved"])
                self.assertFalse(self.output.exists())

    def test_duplicate_json_keys_are_rejected(self):
        self.base.write_bytes(b'{"sources":[],"sources":[],"facts":[]}')
        code, report = self.run_cli()
        self.assertEqual((code, report["status"]), (1, "INVALID_INPUT_JSON"))

    def test_nonprivate_file_and_oversized_input_are_rejected(self):
        self.base.chmod(0o644)
        code, report = self.run_cli()
        self.assertEqual((code, report["status"]), (1, "INPUT_FILE_NOT_PRIVATE"))
        self.base.chmod(0o600)
        with patch.object(merge, "MAX_BUNDLE_BYTES", 8):
            code, report = self.run_cli()
        self.assertEqual((code, report["status"]), (1, "INPUT_FILE_TOO_LARGE"))

    def test_symlink_input_and_traversal_output_are_rejected(self):
        real = self.base.with_name("actual.json")
        self.base.rename(real)
        self.base.symlink_to(real)
        code, report = self.run_cli()
        self.assertEqual((code, report["status"]), (1, "INPUT_OR_OUTPUT_PATH_NOT_PRIVATE"))
        self.base.unlink()
        real.rename(self.base)
        self.output = self.content / "../outside.json"
        code, report = self.run_cli()
        self.assertEqual((code, report["status"]), (1, "INPUT_OR_OUTPUT_PATH_NOT_PRIVATE"))
        self.assertFalse((self.private / "outside.json").exists())

    def test_symlink_output_parent_is_rejected_without_writing(self):
        (self.private / "elsewhere").mkdir(mode=0o700)
        self.output.parent.symlink_to(self.private / "elsewhere", target_is_directory=True)
        code, report = self.run_cli()
        self.assertEqual((code, report["status"]), (1, "INPUT_OR_OUTPUT_PATH_NOT_PRIVATE"))
        self.assertFalse((self.private / "elsewhere/merged.json").exists())


if __name__ == "__main__":
    unittest.main()
