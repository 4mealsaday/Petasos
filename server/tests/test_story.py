"""StoryWriter (CONTENT_PIPELINE.md 9장): prompt sets, checks, retry, API path, bundle format, story_lab approve."""
import asyncio
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from petasos_server import prompts
from petasos_server.content_bundle import validate_bundle
from petasos_server.contracts import Failure, model_schema
from petasos_server.evidence import EvidenceRegistry
from petasos_server.pipeline.model import License, Place, SourceDocument
from petasos_server.pipeline.pack import _facts_for, region_story_rows
from petasos_server.pipeline.scoring import Candidate, RuleScorer
from petasos_server.pipeline.story import (LEGACY_VERSION, StoryDraft, StoryRejected, StoryRequest, StoryWriter,
                                           _korean_number, check_story, evidence_from_facts, lab_schema, numbers_in)
from petasos_server.provider import ProviderResult
from petasos_server.service import GenerationService

from .fixtures import SyntheticProvider, evidence_document, request

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / "demo" / "packs" / "seoul-daejeon.json"
APP_PACK = ROOT / "app" / "src" / "main" / "assets" / "content" / "packs" / "seoul-daejeon.json"
# BundledStoryCatalog.SHA256: the demo pack's 10 stories stay as reviewed until a story is approved on purpose.
GUIDE = prompts.story_prompt()[0]
APP_PACK_SHA256 = "e0ebcf20a19cfc61d72af538da814f28b15aef456c8fa4a5688d7c54807ffca1"

FACTS = (
    {"factRevisionId": "f1-r1", "factId": "f1", "kind": "verified_fact", "sourceRevisionIds": ["s1"],
     "statement": "대전엑스포과학공원은 1993년 대전세계박람회를 기념해 조성되었다."},
    {"factRevisionId": "f2-r1", "factId": "f2", "kind": "verified_fact", "sourceRevisionIds": ["s1"],
     "statement": "한빛탑은 엑스포과학공원 안의 전망대이다."},
)
GOOD = {"title": "박람회가 남긴 공원", "coreQuestion": "박람회 뒤에 무엇이 남았을까요?", "estimatedDurationMs": 30000,
        "opening": "place", "ending": "question",
        "paragraphs": [
            {"text": "대전 유성구를 지나고 있어요. 이 동네에 엑스포과학공원이 있어요. 천구백구십삼년 대전세계박람회를 기념해 만든 공원이에요.",
             "kind": "fact", "factRevisionIds": ["f1-r1"], "sourceRevisionIds": ["s1"]},
            {"text": "공원 안에는 한빛탑이라는 전망대가 서 있어요. 박람회의 기억이 높은 탑 하나로 남은 거죠. 다음에 이 길을 지날 때 그 탑이 떠오르지 않을까요?",
             "kind": "fact", "factRevisionIds": ["f2-r1"], "sourceRevisionIds": ["s1"]}]}


class ScriptedProvider:
    """Returns the scripted results in order and records how it was called."""

    def __init__(self, *results):
        self.results, self.calls = list(results), []

    async def generate(self, task, payload, evidence, schema, **kwargs):
        self.calls.append({"task": task, "payload": copy.deepcopy(payload), "kwargs": kwargs})
        return ProviderResult(copy.deepcopy(self.results.pop(0)), "test-model", "test-provider")

    async def close(self):
        pass


def bad(**changes):
    value = copy.deepcopy(GOOD)
    value["paragraphs"][0].update(changes)
    return value


class PromptSetTests(unittest.TestCase):
    def test_every_prompt_file_has_a_version_and_the_bump_rule_on_its_first_line(self):
        names = ("rules", "story_evidence", "guide_persona", "story", "answer", "summary_resume", "landmark_intro",
                 *prompts.example_names())
        self.assertEqual(len(prompts.example_names()), 3)
        for name in names:
            first = (prompts.DIRECTORY / f"{name}.md").read_text(encoding="utf-8").split("\n", 1)[0]
            self.assertIn("version: ", first, name)
            self.assertIn("version을 올린다", first, name)

    def test_story_guide_prompt_carries_rules_persona_composition_and_examples(self):
        version, text = prompts.story_prompt()
        self.assertTrue(version.startswith("story-guide-"))
        self.assertNotEqual(version, LEGACY_VERSION)
        for part in ("untrusted DATA", "## 말투", "## 이야기 구성", "문화역 서울 284", "천안삼거리", "## 근거"):
            self.assertIn(part, text)
        # The safety rules apply to stories too, but the conversation-only task rules do not.
        self.assertNotIn("For answer", text)


class CheckTests(unittest.TestCase):
    def setUp(self):
        self.evidence = evidence_from_facts(FACTS)
        self.schema = lab_schema()

    def reasons(self, value, **kwargs):
        return check_story(value, self.evidence, schema=self.schema, **kwargs)

    def test_good_story_passes_every_check(self):
        self.assertEqual(self.reasons(GOOD, target_seconds=35), [])

    def test_korean_numbers_are_read_for_the_evidence_check(self):
        self.assertEqual((_korean_number("천구백구십삼"), _korean_number("육백구십구"), _korean_number("십이")), (1993, 699, 12))
        self.assertEqual(numbers_in("천구백구십삼년에 높이 8m, 1,258석"), {"1993", "8", "1258"})

    def test_rejections_name_their_reason(self):
        cases = {
            "근거 밖 숫자": bad(text="대전 유성구예요. 천구백구십사년에 만든 공원이 여기에 있어요."),
            "느낌표": bad(text="대전 유성구를 지나요! 엑스포과학공원이 있어요."),
            "'~라고 할 수 있습니다'": bad(text="엑스포과학공원은 박람회의 유산이라고 할 수 있습니다."),
            "현재형": bad(text="엑스포과학공원은 오늘도 많은 사람이 찾는 공원이에요."),
            "보라는 지시": bad(text="오른쪽 창밖으로 엑스포과학공원을 보세요."),
            "근거 연결 오류": bad(factRevisionIds=["f2-r1"]),
            "문단 1 길이": bad(text="짧아요."),
        }
        for label, value in cases.items():
            self.assertTrue(any(label in reason for reason in self.reasons(value)), label)

    def test_imagining_is_not_a_look_instruction(self):
        self.assertEqual(self.reasons(bad(text="대전 유성구예요. 박람회가 열리던 풍경을 떠올려 보세요. 천구백구십삼년의 일이에요.")), [])

    def test_total_length_repetition_and_existing_story_overlap(self):
        self.assertTrue(any("전체 길이" in r for r in self.reasons(GOOD, target_seconds=10)))
        repeated = copy.deepcopy(GOOD)
        repeated["paragraphs"][1]["text"] = "이 동네에 엑스포과학공원이 있어요. " + repeated["paragraphs"][1]["text"]
        self.assertTrue(any("반복" in r for r in self.reasons(repeated)))
        self.assertTrue(any("기존 이야기" in r for r in self.reasons(GOOD, existing=({"title": "S10", "factIds": ["f1", "f2"]},))))


class WriterTests(unittest.TestCase):
    def request(self, version=GUIDE):
        return StoryRequest("30200", "대전 유성구", FACTS, 35, prompt_version=version)

    def test_retry_once_with_the_reasons_then_accept(self):
        provider = ScriptedProvider(bad(text="대전 유성구를 지나요! 공원이 있어요."), GOOD)
        writer = StoryWriter(provider)
        writer.guide_typed = False   # untyped paragraphs (v2 style); typed output is covered below
        draft = asyncio.run(writer.write(self.request()))
        self.assertEqual((draft.title, draft.prompt_version, draft.review_status), (GOOD["title"], GUIDE, "draft"))
        self.assertEqual(len(draft.attempts), 1)
        self.assertIn("느낌표", provider.calls[1]["payload"]["retryReason"])
        self.assertEqual(provider.calls[0]["kwargs"]["system_prompt"], prompts.story_prompt()[1])
        self.assertEqual(provider.calls[0]["payload"]["targetChars"], {"min": 122, "max": 227})

    def test_two_failures_reject_and_the_legacy_prompt_uses_the_provider_default(self):
        provider = ScriptedProvider(bad(text="짧아요."), bad(text="짧아요."))
        with self.assertRaises(StoryRejected) as raised:
            asyncio.run(StoryWriter(provider).write(self.request(LEGACY_VERSION)))
        self.assertEqual(len(raised.exception.attempts), 2)
        self.assertEqual(provider.calls[0]["kwargs"], {})

    def test_unknown_prompt_version_is_refused(self):
        with self.assertRaises(ValueError):
            StoryWriter(prompt_version="story-guide-v0")

    def test_bundle_version_is_valid_in_the_demo_pack(self):
        pack = json.loads(PACK.read_text())
        version = pack["versions"][9]
        fact = version["paragraphs"][0]["factRevisionIds"][0]
        draft = StoryDraft("t", "q", [{"text": GOOD["paragraphs"][0]["text"], "kind": "fact", "factRevisionIds": [fact],
                                       "sourceRevisionIds": []}], "story-guide-v1")
        pack["versions"][9] = draft.bundle_version(version["storyId"], version["version"] + 1, 1_800_000_000_000)
        validate_bundle(pack, now_epoch_ms=1_800_000_000_001)


class GroundingTests(unittest.TestCase):
    def test_claim_criteria_and_location_check(self):
        from petasos_server.pipeline.grounding import is_claim, location_reasons
        self.assertFalse(is_claim("다음에 이 길을 지날 때 그 탑이 떠오르지 않을까요?"))
        self.assertFalse(is_claim("박람회가 열리던 풍경을 떠올려 보세요."))
        self.assertFalse(is_claim("대전 유성구를 지나고 있어요."))
        self.assertTrue(is_claim("1993년의 풍경을 떠올려 보세요."))
        self.assertTrue(is_claim("이름 하나에 그 시대의 바람이 담겨 있는 셈이죠."))
        self.assertEqual(location_reasons([{"text": "대전 유성구를 지나고 있어요."}], "대전 유성구"), [])
        self.assertTrue(location_reasons([{"text": "용인 해곡동을 지나고 있어요."}], "용인"))
        self.assertTrue(location_reasons([{"text": "서울역 근처, 서울 중구를 지나고 있어요."}], "서울 중구"))
        self.assertEqual(location_reasons([{"text": "이 지역에 있는 와우정사는 사찰이에요."}], "용인"), [])

    def test_judge_rejects_insufficient_sentences_then_retries_and_skips_non_claims(self):
        judged = {"verdicts": [{"id": "p1s2", "verdict": "supported", "reason": ""},
                               {"id": "p1s3", "verdict": "insufficient", "reason": "조성 이유는 근거에 없음"},
                               {"id": "p2s1", "verdict": "supported", "reason": ""},
                               {"id": "p2s2", "verdict": "supported", "reason": ""}]}
        ok = {"verdicts": [{"id": i, "verdict": "supported", "reason": ""} for i in ("p1s2", "p1s3", "p2s1", "p2s2")]}
        provider = ScriptedProvider(GOOD, judged, GOOD, ok)
        writer = StoryWriter(provider, judge=True)
        writer.guide_typed = False
        draft = asyncio.run(writer.write(StoryRequest("30200", "대전 유성구", FACTS, 35, prompt_version=GUIDE)))
        tasks = [c["task"] for c in provider.calls]
        self.assertEqual(tasks, ["story", "grounding", "story", "grounding"])
        self.assertIn("근거 불충분", draft.attempts[0]["reasons"][0])
        self.assertIn("근거 불충분", provider.calls[2]["payload"]["retryReason"])
        # The current-location line and the closing question are never sent to the judge.
        sent = [item["text"] for item in provider.calls[1]["payload"]["sentences"]]
        self.assertFalse(any("지나고 있" in t or t.endswith("?") for t in sent))
        verdicts = {row["verdict"] for row in draft.judgments}
        self.assertEqual(verdicts, {"supported", "not_claim"})

    def test_location_and_repeated_combination_are_rejected_before_judging(self):
        value = bad(text="대전 도룡동을 지나고 있어요. 이 동네에 엑스포과학공원이 있어요. 천구백구십삼년 대전세계박람회를 기념해 만든 공원이에요.")
        reasons = check_story(value, evidence_from_facts(FACTS), schema=lab_schema(), region_name="대전 유성구",
                              existing=({"title": "앞 이야기", "factIds": [], "opening": "place", "ending": "question"},))
        self.assertTrue(any("현재 위치" in r for r in reasons))
        self.assertTrue(any("시작·끝 조합" in r for r in reasons))

    def test_intro_writer_uses_the_same_judge_and_falls_back_to_a_quote(self):
        from petasos_server.pipeline.intro import IntroWriter
        intro = {"intro": "천구백구십삼년 박람회 이후 가장 사랑받는 명소예요.", "factRevisionIds": ["f1-r1"]}
        verdict = {"verdicts": [{"id": "p1s1", "verdict": "insufficient", "reason": "평가는 근거에 없음"}]}
        provider = ScriptedProvider(intro, verdict, intro, verdict)
        facts = [{"factRevisionId": "f1-r1", "factId": "f1", "statement": FACTS[0]["statement"], "sourceRevisionId": "s1"}]
        written = asyncio.run(IntroWriter(provider, judge=True).write("엑스포과학공원", "culture", facts, True))
        self.assertEqual(written[2], "quote")
        self.assertEqual([c["task"] for c in provider.calls], ["landmark_intro", "grounding"] * 2)


class TypedOutputAndSeedTests(unittest.TestCase):
    TYPED = {"title": "박람회가 남긴 공원", "coreQuestion": "박람회 뒤에 무엇이 남았을까요?", "estimatedDurationMs": 30000,
             "opening": "scene", "ending": "afterglow", "paragraphs": [
                 {"kind": "fact", "factRevisionIds": ["f1-r1"], "sourceRevisionIds": ["s1"], "sentences": [
                     {"text": "큰 행사가 끝난 자리는 어떻게 될까요?", "type": "bridge"},
                     {"text": "대전엑스포과학공원은 1993년 대전세계박람회를 기념해 만든 공원이에요.", "type": "fact"}]},
                 {"kind": "fact", "factRevisionIds": ["f2-r1"], "sourceRevisionIds": ["s1"], "sentences": [
                     {"text": "공원 안에는 한빛탑이라는 전망대가 있어요.", "type": "fact"},
                     {"text": "그 탑 아래를 걷던 사람들의 발걸음이 그려지네요.", "type": "bridge"}]}]}

    def test_typed_schema_and_structure(self):
        from petasos_server.pipeline.story import normalize, structure_reasons
        from jsonschema import Draft202012Validator
        self.assertTrue(Draft202012Validator(lab_schema(typed=True)).is_valid(self.TYPED))
        value = normalize(copy.deepcopy(self.TYPED))
        self.assertTrue(value["paragraphs"][0]["text"].startswith("큰 행사가"))
        self.assertEqual(structure_reasons(value, "대전 유성구"), [])
        self.assertEqual(check_story(value, evidence_from_facts(FACTS), target_seconds=None, region_name="대전 유성구"), [])
        ends_with_fact = copy.deepcopy(self.TYPED)
        ends_with_fact["paragraphs"][1]["sentences"].pop()
        self.assertTrue(any("끝 문장" in r for r in structure_reasons(ends_with_fact)))

    def test_bridges_with_numbers_or_places_are_judged_as_facts(self):
        from petasos_server.pipeline.grounding import effective_type
        self.assertEqual(effective_type("딱 맞는 걸 보면 '안성맞춤이네' 하고 말할 때가 있죠.", "bridge", "안성"), "bridge")
        self.assertEqual(effective_type("길이 나뉘는 곳에 서면 방향을 고르게 되죠.", "bridge", "천안"), "bridge")
        self.assertEqual(effective_type("여행길에 기념품을 고를 때, 그 지역 이름이 붙은 걸 찾게 되죠.", "bridge", "천안", {"지역", "광덕산"}), "bridge")
        self.assertEqual(effective_type("이름 끝에 붙은 284, 무슨 숫자일까요?", "bridge", "서울 중구"), "bridge→fact")
        self.assertEqual(effective_type("한빛탑 아래를 걸어 볼까요?", "bridge", "대전 유성구", {"한빛탑"}), "bridge→fact")
        self.assertEqual(effective_type("큰 행사가 끝난 자리는 어떻게 될까요?", "bridge", "대전 유성구", {"한빛탑"}), "bridge")
        self.assertEqual(effective_type("대전 유성구로 들어가 볼까요?", "bridge", "대전 유성구"), "bridge")

    def test_endings_rotate_evenly_and_never_repeat_the_previous_one(self):
        from petasos_server.pipeline.story import next_ending
        used, previous = [], None
        for _ in range(6):
            ending = next_ending(previous, used)
            self.assertNotEqual(ending, previous)
            used.append(ending)
            previous = ending
        self.assertEqual(sorted(set(used)), ["afterglow", "question", "scene"])
        self.assertEqual({used.count(e) for e in set(used)}, {2})

    def test_server_requests_carry_no_temperature_unless_configured(self):
        import httpx
        from petasos_server.provider import OpenRouterConfig, OpenRouterProvider
        bodies = []

        def handler(request):
            bodies.append(json.loads(request.content))
            return httpx.Response(500)

        async def call(config, **kwargs):
            provider = OpenRouterProvider(config, transport=httpx.MockTransport(handler))
            try:
                await provider.generate("story", {}, evidence_from_facts(FACTS), model_schema("story"), **kwargs)
            except Failure:
                pass
            await provider.close()
        base = dict(api_key="k", model="m", provider_slug="p", accepted_provider_names=("P",), privacy_reviewed_until=1,
                    account_content_logging_disabled=True)
        asyncio.run(call(OpenRouterConfig(**base)))
        asyncio.run(call(OpenRouterConfig(**base, temperature=0.4)))
        asyncio.run(call(OpenRouterConfig(**base, temperature=0.4), temperature=0))
        self.assertNotIn("temperature", bodies[0])
        self.assertEqual((bodies[1]["temperature"], bodies[2]["temperature"]), (0.4, 0.0))

    def test_story_seed_scorer(self):
        from petasos_server.pipeline.scoring import StorySeedScorer
        scorer = StorySeedScorer()
        person, _, _ = scorer.score(["포은아트홀의 이름은 포은 정몽주 선생의 아호에서 따왔다."])
        dimensions, _, _ = scorer.score(["아래층 기단의 4면에는 각 면마다 7개씩의 안상이 조각되어 있다.", "위층 기단의 4면에는 모서리마다 기둥 모양을 새겨 두었다."])
        heavy, reasons, flags = scorer.score(["26세의 나이에 순교한 신부는 처형당한 지 40일 뒤에 안장되었다."])
        self.assertGreater(person, dimensions)
        self.assertGreater(dimensions, heavy)
        self.assertTrue(flags["heavy"] and any("무거운" in r for r in reasons))
        self.assertFalse(scorer.score(["안성 장의 거래는 경기도 안성지역에 관한 역사 소재이다."])[2]["religious"])
        seeds = [{"id": "temple", "title": "사찰", "statements": ["자광사는 1969년 탄허 대종사가 창건한 조계종 사찰이다."]},
                 {"id": "park", "title": "공원", "statements": ["한빛탑은 엑스포과학공원 안의 전망대이다."]}]
        self.assertEqual(scorer.select(seeds)[0]["id"], "temple")
        self.assertEqual(scorer.select(seeds, previous_religious=True)[0]["id"], "park")


class ApiPathTests(unittest.IsolatedAsyncioTestCase):
    async def test_story_api_retries_once_then_rejects_with_the_unchanged_error(self):
        provider = SyntheticProvider({"paragraphs": [{"text": "짧", "kind": "fact", "sourceRevisionIds": ["test-source-r1"],
                                                      "factRevisionIds": ["test-fact-r1"]}], "estimatedDurationMs": 2000})
        service = GenerationService(EvidenceRegistry(evidence_document()), provider, b"k" * 32)
        status, body = await service.generate("test-device", request("story"))
        self.assertEqual((status, body["code"], provider.calls), (422, "INVALID_MODEL_OUTPUT", 2))
        await service.close()

    async def test_story_api_keeps_the_request_and_the_reported_prompt_version(self):
        provider = ScriptedProvider({"paragraphs": [{"text": "합성 사실 항목입니다.", "kind": "fact",
                                                     "sourceRevisionIds": ["test-source-r1"], "factRevisionIds": ["test-fact-r1"]}],
                                     "estimatedDurationMs": 2000})
        service = GenerationService(EvidenceRegistry(evidence_document()), provider, b"k" * 32)
        value = request("story")
        status, body = await service.generate("test-device", value)
        self.assertEqual(status, 200)
        self.assertEqual(body["metadata"]["promptVersion"], LEGACY_VERSION)
        self.assertEqual(provider.calls[0]["payload"], value["payload"])   # the app payload goes to the model unchanged
        self.assertEqual(provider.calls[0]["kwargs"], {})
        self.assertNotIn("title", model_schema("story")["properties"])
        await service.close()


class RegionPackStoryTests(unittest.TestCase):
    def test_region_stories_use_generation_allowed_text_and_skip_rejections(self):
        allowed = License("KOGL-1", "https://www.kogl.or.kr/info/license.do", True, "test")
        forbidden = License("KOGL-3", "https://www.kogl.or.kr/info/license.do", False, "test", allows_modification=False)
        candidates = []
        for index, license in enumerate((allowed, forbidden, allowed)):
            place = Place(f"tourapi:{index}", f"장소{index}", 36.37, 127.38, "30200", "heritage", "tourapi", license,
                          "https://example.org/x", 0, {})
            document = SourceDocument(f"tourapi:{index}", "test", "https://example.org/x", place.name,
                                      "대전엑스포과학공원은 1993년 대전세계박람회를 기념해 조성되었다. 한빛탑은 엑스포과학공원 안의 전망대이다.",
                                      license, 0)
            candidates.append(Candidate(place, "large", 0.0, 0.0, 1, "30200", document, _facts_for(document, place)))

        class Writer:
            provider, calls = None, []

            async def write(self, story_request):
                Writer.calls.append(story_request)
                if len(Writer.calls) == 1:
                    raise StoryRejected([{"reasons": ["test"]}], "story-guide-v1")
                rows = story_request.facts
                return StoryDraft("제목", "질문", [{"text": GOOD["paragraphs"][0]["text"], "kind": "fact",
                                                  "factRevisionIds": [rows[0]["factRevisionId"]],
                                                  "sourceRevisionIds": rows[0]["sourceRevisionIds"]}], "story-guide-v1")

        sources, facts, stories, versions, drafts = region_story_rows(
            candidates, RuleScorer(), story_writer=Writer(), count=3, region_code="30200", verified_at=1, log=lambda *_: None)
        self.assertEqual([r.facts[0]["sourceRevisionIds"] for r in Writer.calls][0], Writer.calls[0].facts[0]["sourceRevisionIds"])
        self.assertEqual(len(Writer.calls), 2)                  # the KOGL-3 place is never sent to the model
        self.assertEqual((len(stories), len(versions), len(drafts)), (1, 1, 1))
        self.assertEqual(stories[0]["title"], "제목")


class DemoPackTests(unittest.TestCase):
    def test_demo_pack_stories_and_hash_are_unchanged(self):
        self.assertEqual(hashlib.sha256(APP_PACK.read_bytes()).hexdigest(), APP_PACK_SHA256)
        self.assertEqual(PACK.read_bytes(), APP_PACK.read_bytes())

    def test_approve_replaces_one_story_version_and_bumps_the_pack(self):
        spec = importlib.util.spec_from_file_location("story_lab", ROOT / "server" / "tools" / "story_lab.py")
        lab = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(lab)
        region = json.loads((ROOT / "server" / "eval" / "regions" / "30200.json").read_text())
        hanbit = next(f for f in region["facts"] if f["factRevisionId"] not in {x["factRevisionId"] for x in json.loads(PACK.read_text())["facts"]})
        draft = StoryDraft("t", "q", [{"text": GOOD["paragraphs"][0]["text"], "kind": "fact",
                                       "factRevisionIds": [hanbit["factRevisionId"]], "sourceRevisionIds": hanbit["sourceRevisionIds"]}],
                           "story-guide-v1")
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            (directory / "packs").mkdir()
            (directory / "packs" / "seoul-daejeon.json").write_bytes(PACK.read_bytes())
            out = directory / "out" / "story-guide-v1"
            out.mkdir(parents=True)
            (out / "30200.json").write_text(json.dumps({"stories": [{"status": "ok", **draft.to_dict()}]}, ensure_ascii=False))
            stories = directory / "stories.json"
            stories.write_bytes((ROOT / "app" / "src" / "main" / "assets" / "content" / "p0-stories-v2.json").read_bytes())
            lab.PACKS, lab.OUT = directory / "packs", directory / "out"
            lab.main(["approve", "--pack", "seoul-daejeon", "--story", "S10", "--from", "story-guide-v1", "--stories", str(stories)])
            before, after = json.loads(PACK.read_text()), json.loads((directory / "packs" / "seoul-daejeon.json").read_text())
            self.assertEqual(after["version"], before["version"] + 1)
            changed = [i for i, (a, b) in enumerate(zip(before["versions"], after["versions"])) if a != b]
            self.assertEqual(changed, [9])
            self.assertEqual(after["versions"][9]["version"], before["versions"][9]["version"] + 1)
            self.assertEqual(after["stories"], before["stories"])
            self.assertIn(hanbit["factRevisionId"], {f["factRevisionId"] for f in after["facts"]})
            base = json.loads(stories.read_text())
            self.assertEqual(base["versions"][9], after["versions"][9])
            self.assertEqual(json.loads((out / "30200.json").read_text())["stories"][0]["reviewStatus"], "approved")
        # The real demo pack was not touched.
        self.assertEqual(hashlib.sha256(APP_PACK.read_bytes()).hexdigest(), APP_PACK_SHA256)


if __name__ == "__main__":
    unittest.main()
