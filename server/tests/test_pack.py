"""Content pipeline: prompt files, pack format (formatVersion 3), visibility rules, route geometry, endpoint."""
import asyncio
import copy
import hashlib
import json
from pathlib import Path
import unittest

from petasos_server import prompts
from petasos_server.asgi import GenerationApp
from petasos_server.content_bundle import ContentBundleError, validate_bundle
from petasos_server.pipeline.geo import Route
from petasos_server.pipeline.intro import quote_intro, validate_intro
from petasos_server.evidence import EvidenceContext
from petasos_server.contracts import Failure
from petasos_server.pipeline.model import License, Place, SourceDocument
from petasos_server.pipeline.pack import describe_candidates
from petasos_server.pipeline.scoring import Candidate, NoInterestScorer, RuleScorer
from petasos_server.pipeline.visibility import visibility

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "demo" / "packs" / "seoul-daejeon.json"
STORIES = ROOT / "app" / "src" / "main" / "assets" / "content" / "p0-stories-v2.json"
# sha256 of the single SYSTEM_PROMPT constant before it moved to server/prompts/ (petasos-v0.8-5).
PRE_SPLIT_PROMPT_SHA256 = "35ee56465a2db909c7f3a2725542150b4f1b9ce72e3e24a3099e5832baa753ab"
LICENSE = License("KOGL-1", "https://www.kogl.or.kr/info/license.do", True, "test")


def place(name, category="culture", source="tourapi", **attributes):
    return Place(f"{source}:1", name, 37.5, 127.0, None, category, source, LICENSE, "https://example.org/x", 0, attributes)


class PromptFileTests(unittest.TestCase):
    def test_split_prompt_is_byte_identical_to_the_previous_constant(self):
        version, text = prompts.system_prompt()
        self.assertEqual(version, "petasos-v0.8-5")
        self.assertEqual(hashlib.sha256(text.encode()).hexdigest(), PRE_SPLIT_PROMPT_SHA256)

    def test_every_prompt_file_has_a_version(self):
        for name in ("rules", "story_evidence", "guide_persona", "story", "answer", "summary_resume", "landmark_intro"):
            version, body = prompts.load(name)
            self.assertTrue(version and body.strip(), name)
        self.assertEqual(prompts.landmark_intro_prompt()[0], "landmark-intro-v2")


@unittest.skipUnless(PACK.exists(), "build the demo pack first")
class PackFormatTests(unittest.TestCase):
    def setUp(self):
        self.pack = json.loads(PACK.read_text())

    def test_demo_pack_validates_and_carries_stories_unchanged(self):
        validate_bundle(self.pack)
        base = json.loads(STORIES.read_text())
        for name in ("stories", "versions", "regionBindings"):
            self.assertEqual(self.pack[name], base[name], name)
        self.assertEqual(self.pack["formatVersion"], 3)
        self.assertEqual(self.pack["scope"]["type"], "route")
        self.assertTrue(self.pack["landmarks"])
        self.assertTrue(all("interestScores" not in story for story in self.pack["stories"]))

    def test_interest_scores_are_optional_and_bounded(self):
        pack = copy.deepcopy(self.pack)
        pack["stories"][0]["interestScores"] = {"history": 0.8, "food": 0}
        validate_bundle(pack)
        pack["stories"][0]["interestScores"] = {"history": 1.5}
        with self.assertRaisesRegex(ContentBundleError, "INVALID_INTEREST_SCORES"):
            validate_bundle(pack)

    def test_landmark_must_reuse_its_place_coordinates_and_facts(self):
        pack = copy.deepcopy(self.pack)
        pack["landmarks"][0]["latitude"] = "37.0000000"
        with self.assertRaisesRegex(ContentBundleError, "INVALID_LANDMARK"):
            validate_bundle(pack)
        pack = copy.deepcopy(self.pack)
        pack["landmarks"][0]["factRevisionIds"] = ["not-a-fact"]
        with self.assertRaisesRegex(ContentBundleError, "MISSING_REFERENCE"):
            validate_bundle(pack)
        pack = copy.deepcopy(self.pack)
        pack["landmarks"][0]["visibility"] = "medium"
        with self.assertRaisesRegex(ContentBundleError, "INVALID_LANDMARK"):
            validate_bundle(pack)

    def test_every_landmark_cites_only_facts_of_its_own_source(self):
        facts = {f["factRevisionId"]: f for f in self.pack["facts"]}
        places = {p["placeId"]: p for p in self.pack["places"]}
        for landmark in self.pack["landmarks"]:
            self.assertLessEqual(len(landmark["intro"]), 130)
            sources = {s for fid in landmark["factRevisionIds"] for s in facts[fid]["sourceRevisionIds"]}
            self.assertEqual(len(sources), 1, landmark["name"])
            self.assertIn(landmark["placeId"], places)


class VisibilityAndScoringTests(unittest.TestCase):
    def test_visibility_rules(self):
        self.assertEqual(visibility(place("반포대교 달빛무지개분수")), "large")
        self.assertEqual(visibility(place("한빛탑")), "large")
        self.assertEqual(visibility(place("청계산", "nature")), "large")
        self.assertEqual(visibility(place("서울 해병대사령부 초대교회", "heritage", "heritage"), "등록문화유산", "종교시설"), "small")
        self.assertEqual(visibility(place("숭례문", "heritage")), "small")
        self.assertIsNone(visibility(place("백자 항아리", "heritage", "heritage"), "유물"))
        self.assertIsNone(visibility(place("백범 김구 인장", "heritage", "heritage"), "등록문화유산", "동산"))
        self.assertIsNone(visibility(place("남대문 갈치조림골목")))
        self.assertIsNone(visibility(place("역삼도서관", lclsSystm="VE090300")))

    def test_rule_scorer_prefers_grade_and_closeness(self):
        scorer = RuleScorer()
        near = Candidate(place("A", "heritage"), "small", 20, 0, 1, None, heritage_grade="국보")
        far = Candidate(place("B", "heritage"), "small", 140, 0, 1, None, heritage_grade="국보")
        local = Candidate(place("C", "heritage"), "small", 20, 0, 1, None, heritage_grade="시도기념물")
        self.assertGreater(scorer.score(near), scorer.score(far))
        self.assertGreater(scorer.score(near), scorer.score(local))
        self.assertEqual(NoInterestScorer().interest_scores({}), {})


class ModifiableTextOnlyTests(unittest.TestCase):
    """CONTENT_PIPELINE.md 6장: 변경금지(KOGL-3)·유형 미확인 원문은 랜드마크 intro로 쓰지 않는다."""

    class Adapter:
        def __init__(self, license):
            self.license = license

        def document(self, place):
            return SourceDocument("tourapi:1", "p", "https://example.org", place.name,
                                  "이곳은 1993년에 세운 전망대로 지금도 많은 사람이 찾는다.", self.license, 0)

    class Regions:
        def containing_code(self, lon, lat):
            return "30200", "유성구"

    def describe(self, license, curated=None):
        raw = [(place("한빛탑"), (50.0, 0.0, 1))]
        return describe_candidates(raw, adapters={"tourapi": self.Adapter(license)}, regions=self.Regions(),
                                   curated=curated, log=lambda *_: None)

    def test_kogl3_and_unknown_types_are_dropped_unless_a_curated_source_replaces_them(self):
        kogl3 = License("KOGL-3", "https://www.kogl.or.kr/info/license.do", False, "t", allows_modification=False)
        self.assertEqual(self.describe(kogl3), [])
        self.assertEqual(len(self.describe(LICENSE)), 1)
        curated = {"tourapi:1": {"document": SourceDocument("x:1", "공식", "https://example.org/x", "한빛탑",
                                                            "한빛탑은 대전 유성구에 있는 전망대이다.", LICENSE, 0), "coordinates": None}}
        kept = self.describe(kogl3, curated)
        self.assertEqual(len(kept), 1)
        self.assertEqual(kept[0].document.source_id, "x:1")


class GeometryTests(unittest.TestCase):
    def test_side_and_distance(self):
        route = Route([(127.0, 37.0), (127.0, 37.01)])  # heading north
        east = route.nearest(127.001, 37.005)
        west = route.nearest(126.999, 37.005)
        self.assertEqual(east[2], 1)
        self.assertEqual(west[2], -1)
        self.assertAlmostEqual(east[0], 88.8, delta=1.0)
        self.assertIsNone(route.nearest(127.1, 37.005, 2000))


class IntroTests(unittest.TestCase):
    def test_intro_must_cite_supplied_facts_and_never_instruct(self):
        evidence = EvidenceContext({}, {"f1": {"factRevisionId": "f1"}})
        self.assertEqual(validate_intro({"intro": "1993년에 세운 전망대예요.", "factRevisionIds": ["f1"]}, evidence)[1], ["f1"])
        for bad in ({"intro": "오른쪽을 보세요.", "factRevisionIds": ["f1"]},
                    {"intro": "전망대예요.", "factRevisionIds": ["f2"]},
                    {"intro": "가" * 131, "factRevisionIds": ["f1"]}):
            with self.assertRaises(Failure):
                validate_intro(bad, evidence)

    def test_quote_skips_sentences_that_lean_on_earlier_text(self):
        facts = [{"factRevisionId": "a", "statement": "다만, 이 시기 이름은 달랐다."},
                 {"factRevisionId": "b", "statement": "이 집은 1926년에 지은 것이다."},
                 {"factRevisionId": "c", "statement": "1926년에 지은 전통 가옥이다."}]
        self.assertEqual(quote_intro(facts)[1], ["c"])


class RegionPackEndpointTests(unittest.TestCase):
    def call(self, app, path):
        sent = []

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message):
            sent.append(message)
        scope = {"type": "http", "scheme": "https", "path": path, "method": "GET",
                 "headers": [(b"authorization", b"Bearer token")]}
        asyncio.run(app(scope, receive, send))
        return sent[0]["status"], json.loads(sent[1]["body"] or b"null")

    def test_without_pack_service_reports_unavailable(self):
        class Tokens:
            def authenticate(self, value):
                return "device"
        status, body = self.call(GenerationApp(Tokens(), None), "/v1/packs/region/11140")
        self.assertEqual((status, body["code"]), (503, "PACKS_UNAVAILABLE"))

    def test_with_pack_service_returns_the_built_pack(self):
        class Tokens:
            def authenticate(self, value):
                return "device"

        class Packs:
            async def region(self, code):
                if code == "99999":
                    raise KeyError(code)
                return {"formatVersion": 3, "packId": f"region-{code}"}
        app = GenerationApp(Tokens(), None, pack_service=Packs())
        self.assertEqual(self.call(app, "/v1/packs/region/11140"), (200, {"formatVersion": 3, "packId": "region-11140"}))
        self.assertEqual(self.call(app, "/v1/packs/region/99999")[0], 404)


if __name__ == "__main__":
    unittest.main()
