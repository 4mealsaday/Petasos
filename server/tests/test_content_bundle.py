from contextlib import redirect_stdout, redirect_stderr
from copy import deepcopy
import hashlib
import importlib.util
from io import StringIO
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from petasos_server.content_bundle import (
    ContentBundleError, build_evidence_registry, build_reviewed_bundle, canonical_bytes,
    source_content_hash, validate_bundle, version_content_hash,
)
from petasos_server.evidence import EvidenceRegistry


NOW = 1_794_000_000_000


def review_fixture():
    return {
        "formatVersion": 1, "kind": "petasos-content-source-review", "reviewMetadata": {"mediaApproved": False, "privateNote": "SYNTHETIC_REVIEW_NOTE"},
        "sources": [{"stableKey": "test-source-001", "provider": "synthetic", "sourceUrl": "https://example.test/source/001",
                     "title": "합성 테스트 출처", "fetchedAt": NOW - 2000, "sourceUpdatedAt": None, "sourceUpdatedRaw": "2026-11-07",
                     "verifiedAt": NOW - 1000, "license": {"type": "synthetic-test-only", "url": "https://example.test/license", "scope": "text", "reviewBasis": "합성 검토 근거"},
                     "allowedUses": {"allowGeneration": True, "allowDisplay": True, "allowDistribution": True, "reviewBasis": "합성 검토 본문에 한해 허용"},
                     "attribution": "합성 테스트 출처", "reviewStatus": "verified", "normalizedText": "검토된 사실 하나. 검토된 전승 하나."}],
        "facts": [{"stableKey": "test-fact", "version": 1, "statement": "검토된 사실 하나.", "kind": "verified_fact", "verifiedAt": NOW - 1000, "sourceKeys": ["test-source-001"]},
                  {"stableKey": "test-tradition", "version": 1, "statement": "검토된 전승 하나.", "kind": "tradition", "verifiedAt": NOW - 1000, "sourceKeys": ["test-source-001"]}],
        "places": [{"stableKey": "test-place", "name": "합성 장소", "aliases": [], "latitude": None, "longitude": None,
                    "coordinateSystem": None, "coordinateSourceKey": None, "coordinateReviewBasis": None,
                    "externalIds": [{"provider": "synthetic", "externalId": "000123", "verifiedAt": NOW - 1000}]}],
        "stories": [{"stableKey": "test-story", "topicKey": "test-topic", "title": "합성 이야기", "coreQuestion": "어떤 이야기인가요?", "interestTags": ["합성"], "placeKey": "test-place"}],
        "versions": [{"storyKey": "test-story", "version": 1, "createdAt": NOW - 500, "verifiedAt": NOW - 500, "validUntil": None,
                      "estimatedDurationMs": 7000, "status": "published", "paragraphs": [
                          {"stableKey": "opening", "ordinal": 0, "required": True, "text": "검토된 사실 하나. 검토된 전승 하나.", "audioRef": None,
                           "estimatedDurationMs": 7000, "factKeys": ["test-fact", "test-tradition"]}]}],
    }


def regional_review_fixture():
    review = review_fixture()
    review["formatVersion"] = 2
    review["regionBindings"] = [{
        "storyKey": "test-story", "regionId": "kr-sigungu-12345", "regionRevisionId": "synthetic-region-r1",
        "regionCode": "12345", "datasetVersion": "synthetic-regions-v1", "regionName": "합성 지역",
        "regionSourceRevisionId": "synthetic-geometry-r1", "associationFactKey": "test-fact",
        "verifiedAt": NOW - 500, "reviewBasis": "합성 자료에서 명시적인 지역 소속 사실을 확인함",
    }]
    return review


class ContentBundleTests(unittest.TestCase):
    def assert_review_error(self, review, code):
        with self.assertRaises(ContentBundleError) as raised:
            build_reviewed_bundle(review, NOW)
        self.assertEqual(raised.exception.code, code)

    def test_deterministic_compilation_preserves_timestamps_unknown_coordinates_and_ids(self):
        review = review_fixture()
        original = deepcopy(review)
        first = build_reviewed_bundle(review, NOW)
        second = build_reviewed_bundle(review, NOW + 1000)
        self.assertEqual(canonical_bytes(first), canonical_bytes(second))
        self.assertEqual(review, original)
        self.assertEqual(first["sources"][0]["fetchedAt"], NOW - 2000)
        self.assertIsNone(first["sources"][0]["sourceUpdatedAt"])
        self.assertEqual(first["sources"][0]["sourceUpdatedRaw"], "2026-11-07")
        self.assertIsNone(first["places"][0]["latitude"])
        self.assertIsNone(first["places"][0]["longitude"])
        self.assertEqual(first["places"][0]["externalIds"][0]["externalId"], "000123")
        self.assertNotIn("SYNTHETIC_REVIEW_NOTE", canonical_bytes(first).decode())
        self.assertNotIn("region", canonical_bytes(first).decode())
        validate_bundle(first, NOW)

    def test_hashes_are_exact_utf8_and_all_paragraph_fields_participate(self):
        bundle = build_reviewed_bundle(review_fixture(), NOW)
        source = bundle["sources"][0]
        self.assertEqual(source["contentHash"], hashlib.sha256(source["normalizedText"].encode()).hexdigest())
        version = bundle["versions"][0]
        normalized = [{**paragraph, "factRevisionIds": sorted(paragraph["factRevisionIds"])} for paragraph in version["paragraphs"]]
        expected = hashlib.sha256(json.dumps({"storyId": version["storyId"], "version": version["version"], "paragraphs": normalized},
                                             ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        self.assertEqual(version["contentHash"], expected)
        for field, value in (("paragraphId", "different-id"), ("ordinal", 1), ("required", False), ("text", "changed"),
                             ("audioRef", "media"), ("estimatedDurationMs", 7001), ("factRevisionIds", [])):
            changed = deepcopy(version["paragraphs"])
            changed[0][field] = value
            self.assertNotEqual(version_content_hash(version["storyId"], version["version"], changed), expected)

    def test_hash_canonicalizes_only_fact_reference_order_without_mutation(self):
        bundle = build_reviewed_bundle(review_fixture(), NOW)
        version = bundle["versions"][0]
        reversed_references = deepcopy(version["paragraphs"])
        reversed_references[0]["factRevisionIds"].reverse()
        original = deepcopy(reversed_references)
        self.assertEqual(version_content_hash(version["storyId"], version["version"], reversed_references), version["contentHash"])
        self.assertEqual(reversed_references, original)
        version["paragraphs"] = reversed_references
        validate_bundle(bundle, NOW)
        # Paragraph order remains significant even when the fact set is unchanged.
        another = deepcopy(reversed_references[0]); another["paragraphId"] = "another-paragraph"
        self.assertNotEqual(version_content_hash(version["storyId"], 1, [reversed_references[0], another]),
                            version_content_hash(version["storyId"], 1, [another, reversed_references[0]]))

    def test_changed_source_makes_new_revision_but_same_source_identity(self):
        review = review_fixture()
        first = build_reviewed_bundle(review, NOW)
        review["sources"][0]["normalizedText"] += " 추가 검토된 내용."
        second = build_reviewed_bundle(review, NOW)
        self.assertEqual(first["sources"][0]["sourceId"], second["sources"][0]["sourceId"])
        self.assertNotEqual(first["sources"][0]["sourceRevisionId"], second["sources"][0]["sourceRevisionId"])
        self.assertEqual(first["facts"][0]["factId"], second["facts"][0]["factId"])
        self.assertEqual(second["facts"][0]["sourceRevisionIds"], [second["sources"][0]["sourceRevisionId"]])

    def test_changed_published_text_does_not_silently_increment_identity(self):
        review = review_fixture()
        first = build_reviewed_bundle(review, NOW)
        review["versions"][0]["paragraphs"][0]["text"] += " 수정."
        second = build_reviewed_bundle(review, NOW)
        self.assertEqual(first["versions"][0]["storyVersionId"], second["versions"][0]["storyVersionId"])
        self.assertNotEqual(first["versions"][0]["contentHash"], second["versions"][0]["contentHash"])
        # The installer sees an immutable ID conflict; only an explicit version increment creates a new version.
        review["versions"][0]["version"] = 2
        third = build_reviewed_bundle(review, NOW)
        self.assertNotEqual(second["versions"][0]["storyVersionId"], third["versions"][0]["storyVersionId"])
        self.assertEqual(second["stories"][0]["storyId"], third["stories"][0]["storyId"])

    def test_pending_source_unpublished_version_and_missing_license_are_rejected(self):
        for state in ("pending", "draft", "rejected"):
            review = review_fixture(); review["sources"][0]["reviewStatus"] = state
            self.assert_review_error(review, "SOURCE_NOT_REVIEWED")
        review = review_fixture(); review["versions"][0]["status"] = "draft"
        self.assert_review_error(review, "UNREVIEWED_VERSION")
        review = review_fixture(); del review["sources"][0]["license"]
        self.assert_review_error(review, "INVALID_REVIEW")
        review = review_fixture(); review["sources"][0]["license"]["reviewBasis"] = ""
        self.assert_review_error(review, "INVALID_TEXT")

    def test_permissions_are_independent_explicit_booleans_with_no_media_promotion(self):
        for key in ("allowDisplay", "allowDistribution"):
            review = review_fixture(); review["sources"][0]["allowedUses"][key] = False
            self.assert_review_error(review, "USE_NOT_ALLOWED")
        review = review_fixture(); review["sources"][0]["allowedUses"]["allowGeneration"] = False
        bundle = build_reviewed_bundle(review, NOW)
        self.assertEqual(build_evidence_registry(bundle, ["test-device"], NOW), {"sources": [], "facts": []})
        review["sources"][0]["allowedUses"]["allowGeneration"] = "true"
        self.assert_review_error(review, "INVALID_BUNDLE")
        review = review_fixture(); review["sources"][0]["license"]["scope"] = "text-and-media"
        self.assert_review_error(review, "UNSUPPORTED_ASSET_SCOPE")
        review = review_fixture(); review["versions"][0]["paragraphs"][0]["audioRef"] = "file.mp3"
        self.assert_review_error(review, "UNVERIFIED_MEDIA")

    def test_unverified_mixed_missing_and_invalid_coordinates_rejected(self):
        review = review_fixture(); review["places"][0].update(latitude="34.0", longitude="125.0")
        self.assert_review_error(review, "UNREVIEWED_COORDINATES")
        review["places"][0].update(coordinateSystem="WGS84", coordinateSourceKey="test-source-001", coordinateReviewBasis="합성 좌표 검토")
        bundle = build_reviewed_bundle(review, NOW)
        self.assertEqual(bundle["places"][0]["latitude"], "34.0")
        for field, value in (("latitude", None), ("longitude", "181"), ("latitude", "NaN"), ("longitude", "1e2")):
            invalid = deepcopy(review); invalid["places"][0][field] = value
            self.assert_review_error(invalid, "INVALID_COORDINATES")
        review = review_fixture(); review["places"][0]["coordinateReviewBasis"] = "미검토"
        self.assert_review_error(review, "INVALID_COORDINATES")

    def test_source_and_fact_references_must_exist_and_be_nonempty(self):
        for keys in ([], ["missing"], ["test-source-001", "test-source-001"]):
            review = review_fixture(); review["facts"][0]["sourceKeys"] = keys
            self.assert_review_error(review, "MISSING_REFERENCE")
        for keys in ([], ["missing"], ["test-fact", "test-fact"]):
            review = review_fixture(); review["versions"][0]["paragraphs"][0]["factKeys"] = keys
            self.assert_review_error(review, "MISSING_REFERENCE")
        review = review_fixture(); review["stories"][0]["placeKey"] = "missing"
        self.assert_review_error(review, "MISSING_REFERENCE")

    def test_required_paragraph_ordinal_duration_and_expiry_are_enforced(self):
        for field, value, code in (("required", False, "MISSING_REQUIRED_PARAGRAPH"),
                                   ("ordinal", 1, "INVALID_ORDINAL"), ("estimatedDurationMs", 6000, "INVALID_DURATION")):
            review = review_fixture(); review["versions"][0]["paragraphs"][0][field] = value
            self.assert_review_error(review, code)
        review = review_fixture(); review["versions"][0]["validUntil"] = NOW
        self.assert_review_error(review, "EXPIRED_VERSION")
        review = review_fixture(); review["versions"][0]["createdAt"] = True
        self.assert_review_error(review, "INVALID_BUNDLE")

    def test_one_fact_revision_may_back_several_paragraphs_and_across_versions(self):
        review = review_fixture()
        extra = deepcopy(review["versions"][0]["paragraphs"][0]); extra.update(stableKey="repeat", ordinal=1)
        review["versions"][0]["paragraphs"].append(extra)
        review["versions"][0]["estimatedDurationMs"] *= 2
        self.assertEqual(len(build_reviewed_bundle(review, NOW)["versions"][0]["paragraphs"]), 2)
        review = review_fixture()
        second_version = deepcopy(review["versions"][0]); second_version["version"] = 2
        review["versions"].append(second_version)
        self.assertEqual(len(build_reviewed_bundle(review, NOW)["versions"]), 2)

    def test_duplicate_external_identifiers_and_review_keys_rejected(self):
        review = review_fixture(); review["facts"].append(deepcopy(review["facts"][0]))
        self.assert_review_error(review, "DUPLICATE_REVIEW_KEY")
        review = review_fixture(); other = deepcopy(review["places"][0]); other["stableKey"] = "other-place"
        review["places"].append(other)
        self.assert_review_error(review, "DUPLICATE_EXTERNAL_ID")

    def test_bundle_tampering_and_extra_fields_cannot_pass_cross_validation(self):
        bundle = build_reviewed_bundle(review_fixture(), NOW)
        for kind, mutate, code in (("source", lambda b: b["sources"][0].update(normalizedText="변조"), "SOURCE_HASH_MISMATCH"),
                                  ("story", lambda b: b["versions"][0]["paragraphs"][0].update(text="변조"), "STORY_HASH_MISMATCH"),
                                  ("region", lambda b: b.update(regions=[]), "INVALID_BUNDLE")):
            changed = deepcopy(bundle); mutate(changed)
            with self.subTest(kind=kind), self.assertRaisesRegex(ContentBundleError, "^" + code + "$"):
                validate_bundle(changed, NOW)

    def test_generation_export_uses_explicit_devices_and_keeps_traditions_distinct(self):
        bundle = build_reviewed_bundle(review_fixture(), NOW)
        evidence = build_evidence_registry(bundle, ["device-one", "device-two"], NOW)
        self.assertEqual({fact["kind"] for fact in evidence["facts"]}, {"fact", "tradition"})
        self.assertEqual(evidence["sources"][0]["allowedDeviceIds"], ["device-one", "device-two"])
        self.assertTrue(evidence["sources"][0]["reviewed"])
        EvidenceRegistry(evidence)
        for devices in ([], ["x", "x"], ["*"], "device"):
            with self.assertRaisesRegex(ContentBundleError, "^INVALID_DEVICE_IDS$"):
                build_evidence_registry(bundle, devices, NOW)

    def test_generation_export_excludes_facts_if_any_required_source_disallows_generation(self):
        review = review_fixture(); other = deepcopy(review["sources"][0]); other["stableKey"] = "restricted"
        other["allowedUses"]["allowGeneration"] = False
        review["sources"].append(other)
        review["facts"][0]["sourceKeys"].append("restricted")
        exported = build_evidence_registry(build_reviewed_bundle(review, NOW), ["device"], NOW)
        self.assertEqual(len(exported["sources"]), 1)
        self.assertEqual([fact["kind"] for fact in exported["facts"]], ["tradition"])

    def test_utf16_limits_credentials_in_source_urls_and_safe_errors(self):
        review = review_fixture(); review["sources"][0]["normalizedText"] = "😀" * 8001
        self.assert_review_error(review, "INVALID_TEXT")
        review = review_fixture(); review["sources"][0]["sourceUrl"] += "?serviceKey=SYNTHETIC_PRIVATE_KEY"
        self.assert_review_error(review, "INVALID_SOURCE_URL")
        with self.assertRaises(ContentBundleError) as raised:
            source_content_hash("\ud800")
        self.assertNotIn("SYNTHETIC", str(raised.exception))
        self.assertEqual(str(ContentBundleError("SYNTHETIC_PRIVATE_TEXT")), "INVALID_BUNDLE")


class RegionalContentBundleTests(unittest.TestCase):
    def assert_bundle_error(self, bundle, code):
        with self.assertRaisesRegex(ContentBundleError, "^" + code + "$"):
            validate_bundle(bundle, NOW)

    def assert_review_error(self, review, code):
        with self.assertRaisesRegex(ContentBundleError, "^" + code + "$"):
            build_reviewed_bundle(review, NOW)

    def test_existing_v1_bytes_ids_and_hashes_are_unchanged(self):
        bundle = build_reviewed_bundle(review_fixture(), NOW)
        # Captured before format v2 was added; includes every UUID and timestamp.
        self.assertEqual(hashlib.sha256(canonical_bytes(bundle)).hexdigest(),
                         "8503b910cff3938c137cad58cfada5ee6765864f4a68c31b6ccd311b86caef64")
        self.assertNotIn("regionBindings", bundle)

    def test_v2_compilation_resolves_explicit_keys_without_mutating_review(self):
        review = regional_review_fixture()
        original = deepcopy(review)
        first = build_reviewed_bundle(review, NOW)
        second = build_reviewed_bundle(review, NOW + 1000)
        self.assertEqual(review, original)
        self.assertEqual(canonical_bytes(first), canonical_bytes(second))
        binding = first["regionBindings"][0]
        self.assertEqual(binding["storyId"], first["stories"][0]["storyId"])
        self.assertEqual(binding["associationFactRevisionId"], first["facts"][0]["factRevisionId"])
        self.assertNotIn("storyKey", binding)
        self.assertNotIn("associationFactKey", binding)
        self.assertEqual(binding["regionSourceRevisionId"], "synthetic-geometry-r1")
        self.assertIsNone(first["places"][0]["latitude"])
        self.assertNotIn("regionId", first["stories"][0])
        validate_bundle(first, NOW)

    def test_v2_without_bindings_does_not_infer_a_region(self):
        review = regional_review_fixture()
        review["regionBindings"] = []
        new = build_reviewed_bundle(review, NOW)
        old = build_reviewed_bundle(review_fixture(), NOW)
        self.assertEqual(new.pop("regionBindings"), [])
        new["formatVersion"] = 1
        self.assertEqual(new, old)

    def test_versioned_root_shapes_reject_missing_or_injected_bindings(self):
        v1 = build_reviewed_bundle(review_fixture(), NOW)
        v1["regionBindings"] = []
        self.assert_bundle_error(v1, "INVALID_BUNDLE")
        v2 = build_reviewed_bundle(regional_review_fixture(), NOW)
        del v2["regionBindings"]
        self.assert_bundle_error(v2, "INVALID_BUNDLE")
        for version in (0, 3, True, "2", None):
            with self.subTest(version=version):
                invalid = build_reviewed_bundle(review_fixture(), NOW)
                invalid["formatVersion"] = version
                self.assert_bundle_error(invalid, "UNSUPPORTED_BUNDLE_VERSION")
                review = review_fixture(); review["formatVersion"] = version
                self.assert_review_error(review, "INVALID_REVIEW")

    def test_review_binding_shape_and_missing_stable_keys_are_rejected(self):
        for key in ("storyKey", "associationFactKey"):
            with self.subTest(key=key):
                review = regional_review_fixture(); review["regionBindings"][0][key] = "missing"
                self.assert_review_error(review, "INVALID_REGION_BINDING")
                review = regional_review_fixture(); del review["regionBindings"][0][key]
                self.assert_review_error(review, "INVALID_REVIEW")
        review = regional_review_fixture(); review["regionBindings"][0]["regionPolicy"] = {"approved": True}
        self.assert_review_error(review, "INVALID_REVIEW")

    def test_binding_shape_cannot_smuggle_coordinates_or_approval(self):
        for field, value in (("latitude", "35.0"), ("verified", True), ("regionPolicy", {}), ("regionName", None)):
            with self.subTest(field=field):
                bundle = build_reviewed_bundle(regional_review_fixture(), NOW)
                bundle["regionBindings"][0][field] = value
                self.assert_bundle_error(bundle, "INVALID_BUNDLE")
        bundle = build_reviewed_bundle(regional_review_fixture(), NOW)
        del bundle["regionBindings"][0]["reviewBasis"]
        self.assert_bundle_error(bundle, "INVALID_BUNDLE")

    def test_binding_story_is_unique_and_cardinality_bounded(self):
        bundle = build_reviewed_bundle(regional_review_fixture(), NOW)
        bundle["regionBindings"] *= 2
        self.assert_bundle_error(bundle, "INVALID_REGION_BINDING")
        bundle["regionBindings"] *= 17
        self.assert_bundle_error(bundle, "INVALID_REGION_BINDING")
        review = regional_review_fixture(); review["regionBindings"] *= 2
        self.assert_review_error(review, "INVALID_REGION_BINDING")

    def test_all_binding_ids_use_the_existing_bounded_id_contract(self):
        for field in ("storyId", "regionId", "regionRevisionId", "regionSourceRevisionId", "associationFactRevisionId"):
            for value in ("", " ", "한글", "x" * 129):
                with self.subTest(field=field, value=value):
                    bundle = build_reviewed_bundle(regional_review_fixture(), NOW)
                    bundle["regionBindings"][0][field] = value
                    self.assert_bundle_error(bundle, "INVALID_ID")

    def test_only_exact_ascii_five_digit_region_codes_are_accepted(self):
        for code in ("1234", "123456", "１２３４５", "١٢٣٤٥", "12345\n", 12345, None):
            with self.subTest(code=code):
                bundle = build_reviewed_bundle(regional_review_fixture(), NOW)
                bundle["regionBindings"][0]["regionCode"] = code
                self.assert_bundle_error(bundle, "INVALID_REGION_BINDING" if isinstance(code, str) else "INVALID_BUNDLE")

    def test_binding_requires_an_existing_story_and_association_fact(self):
        for field in ("storyId", "associationFactRevisionId"):
            with self.subTest(field=field):
                bundle = build_reviewed_bundle(regional_review_fixture(), NOW)
                bundle["regionBindings"][0][field] = "missing"
                self.assert_bundle_error(bundle, "INVALID_REGION_BINDING")

    def test_tradition_cannot_establish_administrative_association(self):
        review = regional_review_fixture(); review["regionBindings"][0]["associationFactKey"] = "test-tradition"
        self.assert_review_error(review, "INVALID_REGION_BINDING")

    def test_binding_review_time_must_follow_fact_and_not_be_in_the_future(self):
        for verified_at in (NOW - 1001, NOW + 1):
            with self.subTest(verified_at=verified_at):
                review = regional_review_fixture(); review["regionBindings"][0]["verifiedAt"] = verified_at
                self.assert_review_error(review, "INVALID_REGION_BINDING")
        for verified_at, code in ((-1, "INVALID_TIMESTAMP"), (True, "INVALID_BUNDLE"), (1.0, "INVALID_BUNDLE")):
            review = regional_review_fixture(); review["regionBindings"][0]["verifiedAt"] = verified_at
            self.assert_review_error(review, code)
        for verified_at in (NOW - 1000, NOW):
            review = regional_review_fixture(); review["regionBindings"][0]["verifiedAt"] = verified_at
            validate_bundle(build_reviewed_bundle(review, NOW), NOW)

    def test_binding_text_is_nonblank_and_utf16_bounded(self):
        for field, limit in (("datasetVersion", 128), ("regionName", 500), ("reviewBasis", 4000)):
            for text in (" ", "x" * (limit + 1), "😀" * (limit // 2 + 1)):
                with self.subTest(field=field, text_length=len(text)):
                    review = regional_review_fixture(); review["regionBindings"][0][field] = text
                    self.assert_review_error(review, "INVALID_TEXT")
            review = regional_review_fixture(); review["regionBindings"][0][field] = "x" * limit
            build_reviewed_bundle(review, NOW)

    def test_external_geometry_reference_is_not_imported_as_text_or_granted_generation(self):
        bundle = build_reviewed_bundle(regional_review_fixture(), NOW)
        geometry_id = bundle["regionBindings"][0]["regionSourceRevisionId"]
        self.assertNotIn(geometry_id, {source["sourceRevisionId"] for source in bundle["sources"]})
        exported = build_evidence_registry(bundle, ["device"], NOW)
        self.assertEqual(set(exported), {"sources", "facts"})
        self.assertNotIn(geometry_id, {source["sourceRevisionId"] for source in exported["sources"]})
        EvidenceRegistry(exported)

    def test_association_fact_can_be_unspoken_but_stays_reviewed_and_exportable(self):
        review = regional_review_fixture()
        review["versions"][0]["paragraphs"][0]["factKeys"] = ["test-tradition"]
        bundle = build_reviewed_bundle(review, NOW)
        association = bundle["regionBindings"][0]["associationFactRevisionId"]
        self.assertNotIn(association, bundle["versions"][0]["paragraphs"][0]["factRevisionIds"])
        exported = build_evidence_registry(bundle, ["device"], NOW)
        self.assertIn(association, {fact["factRevisionId"] for fact in exported["facts"]})

    def test_binding_never_promotes_source_permissions(self):
        review = regional_review_fixture(); review["sources"][0]["allowedUses"]["allowGeneration"] = False
        bundle = build_reviewed_bundle(review, NOW)
        self.assertEqual(build_evidence_registry(bundle, ["device"], NOW), {"sources": [], "facts": []})
        for permission in ("allowDisplay", "allowDistribution"):
            review = regional_review_fixture(); review["sources"][0]["allowedUses"][permission] = False
            self.assert_review_error(review, "USE_NOT_ALLOWED")
        review = regional_review_fixture(); review["sources"][0]["reviewStatus"] = "draft"
        self.assert_review_error(review, "SOURCE_NOT_REVIEWED")


class ContentBundleCliTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(__file__).resolve().parents[2] / "scripts/build-content-bundle.py"
        spec = importlib.util.spec_from_file_location("content_bundle_cli_test", path)
        cls.cli = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.cli)

    def test_v2_cli_compiles_and_exports_without_exposing_or_promoting_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); private = root / ".local"; content = private / "content"
            content.mkdir(parents=True, mode=0o700); private.chmod(0o700)
            review = content / "review.json"; review.write_bytes(canonical_bytes(regional_review_fixture())); review.chmod(0o600)
            bundle_path = content / "bundles/v2.json"
            evidence_path = content / "evidence/v2.json"
            with patch.object(self.cli, "ROOT", root), patch.object(self.cli, "PRIVATE_ROOT", private), patch.object(self.cli, "CONTENT_ROOT", content):
                stdout = StringIO()
                with redirect_stdout(stdout):
                    status = self.cli.main(["--review", str(review), "--output", str(bundle_path)])
                self.assertEqual(status, 0)
                result = json.loads(stdout.getvalue())
                self.assertEqual(result["formatVersion"], 2)
                bundle = json.loads(bundle_path.read_bytes())
                self.assertEqual(len(bundle["regionBindings"]), 1)
                self.assertNotIn("합성", stdout.getvalue())
                self.assertNotIn("regionBindings", stdout.getvalue())
                stdout = StringIO()
                with redirect_stdout(stdout):
                    status = self.cli.main(["--evidence-from-bundle", str(bundle_path), "--allow-device-id", "device",
                                            "--output", str(evidence_path)])
                self.assertEqual(status, 0)
                exported = json.loads(evidence_path.read_bytes())
                self.assertEqual(set(exported), {"sources", "facts"})
                self.assertEqual(exported["sources"][0]["allowedDeviceIds"], ["device"])
                self.assertNotIn("synthetic-geometry-r1", evidence_path.read_text())
                self.assertEqual(bundle_path.stat().st_mode & 0o777, 0o600)
                self.assertEqual(evidence_path.stat().st_mode & 0o777, 0o600)
                EvidenceRegistry(exported)

    def test_private_output_is_exclusive_and_stdout_contains_only_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); private = root / ".local"; content = private / "content"
            content.mkdir(parents=True, mode=0o700); private.chmod(0o700)
            review = content / "review.json"; review.write_bytes(canonical_bytes(review_fixture())); review.chmod(0o600)
            output = content / "bundles/result.json"
            with patch.object(self.cli, "ROOT", root), patch.object(self.cli, "PRIVATE_ROOT", private), patch.object(self.cli, "CONTENT_ROOT", content):
                stdout = StringIO()
                with redirect_stdout(stdout): status = self.cli.main(["--review", str(review), "--output", str(output)])
                self.assertEqual(status, 0)
                result = json.loads(stdout.getvalue())
                self.assertEqual(result["sha256"], hashlib.sha256(output.read_bytes()).hexdigest())
                self.assertEqual(output.stat().st_mode & 0o777, 0o600)
                self.assertNotIn("합성", stdout.getvalue())
                self.assertNotIn("SYNTHETIC", stdout.getvalue())
                original = output.read_bytes()
                with redirect_stdout(StringIO()): status = self.cli.main(["--review", str(review), "--output", str(output)])
                self.assertEqual(status, 1)
                self.assertEqual(output.read_bytes(), original)

    def test_nonprivate_paths_and_argument_errors_fail_without_echo(self):
        out, err = StringIO(), StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            status = self.cli.main(["--unknown", "SYNTHETIC_PRIVATE_KEY"])
        self.assertEqual(status, 1)
        self.assertNotIn("SYNTHETIC", out.getvalue() + err.getvalue())
        with patch.object(self.cli, "_read_review", side_effect=AssertionError("must not read")) as read:
            with redirect_stdout(StringIO()): status = self.cli.main(["--review", "/tmp/input.json", "--output", "/tmp/output.json"])
            self.assertEqual(status, 1)
            read.assert_not_called()

    def test_evidence_export_validates_bundle_and_scopes_registered_device(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); private = root / ".local"; content = private / "content"
            content.mkdir(parents=True, mode=0o700); private.chmod(0o700)
            bundle = content / "bundle.json"
            document = build_reviewed_bundle(review_fixture(), NOW)
            bundle.write_bytes(canonical_bytes(document)); bundle.chmod(0o600)
            output = content / "evidence/result.json"
            with patch.object(self.cli, "ROOT", root), patch.object(self.cli, "PRIVATE_ROOT", private), patch.object(self.cli, "CONTENT_ROOT", content):
                stdout = StringIO()
                with redirect_stdout(stdout):
                    status = self.cli.main(["--evidence-from-bundle", str(bundle), "--allow-device-id", "test-device", "--output", str(output)])
                self.assertEqual(status, 0)
                self.assertEqual(json.loads(stdout.getvalue())["status"], "EXPORTED")
                self.assertNotIn("검토", stdout.getvalue())
                self.assertNotIn("test-device", stdout.getvalue())
                self.assertEqual(output.stat().st_mode & 0o777, 0o600)
                registry = EvidenceRegistry(json.loads(output.read_text()))
                request = {"task": "answer", "payload": {"sourceRevisionIds": [document["sources"][0]["sourceRevisionId"]]}}
                self.assertEqual(len(registry.resolve("test-device", request).facts), 2)
                from petasos_server.contracts import Failure
                with self.assertRaises(Failure): registry.resolve("another-device", request)
                original = output.read_bytes()
                with redirect_stdout(StringIO()):
                    status = self.cli.main(["--evidence-from-bundle", str(bundle), "--allow-device-id", "test-device", "--output", str(output)])
                self.assertEqual(status, 1)
                self.assertEqual(output.read_bytes(), original)

    def test_evidence_export_requires_explicit_device_and_never_approves_tampered_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); private = root / ".local"; content = private / "content"
            content.mkdir(parents=True, mode=0o700); private.chmod(0o700)
            bundle = content / "bundle.json"; output = content / "evidence.json"
            document = build_reviewed_bundle(review_fixture(), NOW)
            document["sources"][0]["normalizedText"] = "SYNTHETIC_UNREVIEWED_TEXT"
            bundle.write_bytes(canonical_bytes(document)); bundle.chmod(0o600)
            with patch.object(self.cli, "ROOT", root), patch.object(self.cli, "PRIVATE_ROOT", private), patch.object(self.cli, "CONTENT_ROOT", content):
                for devices in ([], ["--allow-device-id", "test-device"]):
                    stdout = StringIO()
                    with redirect_stdout(stdout):
                        status = self.cli.main(["--evidence-from-bundle", str(bundle), "--output", str(output), *devices])
                    self.assertEqual(status, 1)
                    self.assertFalse(output.exists())
                    self.assertNotIn("SYNTHETIC_UNREVIEWED_TEXT", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
