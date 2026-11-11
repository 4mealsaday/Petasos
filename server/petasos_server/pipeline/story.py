"""StoryWriter (CONTENT_PIPELINE.md 9장): one module that every story path calls -- the demo pack lab
(server/tools/story_lab.py), region packs (build_region_pack) and the app's story generation API.

Same shape as IntroWriter: assemble the prompt -> call the model -> check -> on failure retry once with the reasons
-> reject. Checks: schema, fact/source links (the server's own evidence.validate_semantics), facts outside the evidence
(numbers), banned expressions, paragraph and total length, repeated content, and overlap with existing stories.

Prompt versions: LEGACY_VERSION is the conversation system prompt (petasos-v0.8-5, what the server used for stories so
far); the story guide version is whatever server/prompts/story.md declares now. Older guide versions are compared
through their saved lab output (server/eval/out/<version>/), not regenerated.
"""
from dataclasses import dataclass, field
import copy
import re
from uuid import NAMESPACE_URL, uuid5

from jsonschema import Draft202012Validator

from ..content_bundle import version_content_hash
from ..contracts import Failure, model_schema
from ..evidence import EvidenceContext, validate_semantics
from ..prompts import story_output_typed, story_prompt, system_prompt
from .grounding import GroundingJudge, effective_type, fact_names, judgment_reasons, location_reasons

LEGACY_VERSION = system_prompt()[0]
CHARS_PER_SECOND = 5.0          # the demo pack's reviewed stories: about 165 characters per 34 seconds
LENGTH_TOLERANCE = 0.3          # total length window: target ± 30%
MIN_PARAGRAPH_CHARS = 10
MAX_PARAGRAPH_CHARS = 240       # the v0.8 wire limit
MAX_PARAGRAPHS = 4
REPEAT_SIMILARITY = 0.7         # character-bigram Jaccard between two sentences
_NAMESPACE = uuid5(NAMESPACE_URL, "urn:petasos:story-writer:v1")

# (pattern, reason). Shared by every prompt version so a comparison measures the prompt, not the checker.
BANNED = (
    (re.compile(r"!"), "느낌표"),
    (re.compile(r"(우와|와아|와,|정말 |무려|놀랍게도|놀라운|엄청난|대단한)"), "감탄·과장 표현"),
    (re.compile(r"(이?라고 할 수 있|라 할 수 있)"), "'~라고 할 수 있습니다'"),
    (re.compile(r"(오늘|매일|운영 ?중|가동 ?중|지금도 (운영|열|진행|공연)|지금 (운영|열리|진행 중|공연))"), "때에 따라 달라지는 현재형(지금·오늘·운영 중)"),
    (re.compile(r"((?<!려 )(?<!려)보세요|바라보세요|살펴보세요|확인해 ?보세요|방문해 ?보세요|들러 ?보세요)"), "보라는 지시"),
)
_DIGITS = re.compile(r"\d[\d,]*(?:\.\d+)?")
_KOREAN_NUMBER = re.compile(r"([일이삼사오육칠팔구십백천만]{2,})(?=\s?(년|월|일|미터|m|개|명|석|층|킬로|퍼센트|대|번|호|세기|미터의))")
_KOREAN_DIGIT = {"일": 1, "이": 2, "삼": 3, "사": 4, "오": 5, "육": 6, "칠": 7, "팔": 8, "구": 9}
_KOREAN_UNIT = {"십": 10, "백": 100, "천": 1000}
_SENTENCE = re.compile(r"[^.?!。]+[.?!。]?")
OPENINGS = ("place", "person", "scene", "question")   # v2: 장소 이름 / 사람 이야기 / 장면 / 물음
ENDINGS = ("question", "afterglow", "fact")              # v2: 가벼운 물음 / 한 줄 여운 / 사실로 마무리
TYPED_OPENINGS = ("experience", "scene", "question")  # v3 bridge openings: 듣는 사람의 경험·말 / 장면 / 물음
TYPED_ENDINGS = ("question", "afterglow", "scene")     # v3 bridge endings: 가벼운 물음 / 한 줄 여운 / 장면 떠올리기


@dataclass(frozen=True)
class StoryRequest:
    """facts: rows {factRevisionId, factId, kind ('fact'|'tradition'|'verified_fact'), statement, sourceRevisionIds}."""
    region_code: str | None
    region_name: str | None
    facts: tuple
    target_seconds: int | None = 35
    interest_focus: tuple = ()
    existing: tuple = ()            # ({"title": str, "factIds": [stable factId, ...]}, ...)
    prompt_version: str | None = None
    ending: str | None = None       # an assigned ending (typed output); see next_ending


@dataclass
class StoryDraft:
    title: str
    core_question: str
    paragraphs: list                # [{text, kind, factRevisionIds, sourceRevisionIds}]
    prompt_version: str
    review_status: str = "draft"
    attempts: list = field(default_factory=list)   # [{"reasons": [...]}] for every rejected attempt before success
    model_id: str | None = None
    opening: str | None = None      # place | person | scene | question (story guide v2+)
    ending: str | None = None       # question | afterglow | fact
    judgments: list = field(default_factory=list)  # grounding rows of the accepted output

    @property
    def chars(self):
        return sum(len(p["text"]) for p in self.paragraphs)

    def to_dict(self):
        return {"title": self.title, "coreQuestion": self.core_question, "paragraphs": copy.deepcopy(self.paragraphs),
                "chars": self.chars, "promptVersion": self.prompt_version, "reviewStatus": self.review_status,
                "rejectedAttempts": copy.deepcopy(self.attempts), "modelId": self.model_id,
                "opening": self.opening, "ending": self.ending, "judgments": copy.deepcopy(self.judgments)}

    @classmethod
    def from_dict(cls, value):
        return cls(value["title"], value["coreQuestion"], copy.deepcopy(value["paragraphs"]), value["promptVersion"],
                   value.get("reviewStatus", "draft"), copy.deepcopy(value.get("rejectedAttempts", [])), value.get("modelId"),
                   value.get("opening"), value.get("ending"), copy.deepcopy(value.get("judgments", [])))

    def bundle_version(self, story_id, version, now_ms):
        """The story in the reviewed bundle format (versions[] row), ready for a pack."""
        version_id = str(uuid5(_NAMESPACE, f"story-version\x1f{story_id}\x1f{version}"))
        paragraphs = []
        for index, paragraph in enumerate(self.paragraphs):
            paragraphs.append({
                "audioRef": None, "estimatedDurationMs": estimated_ms(paragraph["text"]),
                "factRevisionIds": sorted(paragraph["factRevisionIds"]), "ordinal": index,
                "paragraphId": str(uuid5(_NAMESPACE, f"paragraph\x1f{version_id}\x1f{index}")),
                "required": True, "text": paragraph["text"]})
        return {"contentHash": version_content_hash(story_id, version, paragraphs), "createdAt": now_ms,
                "estimatedDurationMs": sum(p["estimatedDurationMs"] for p in paragraphs), "paragraphs": paragraphs,
                "status": "published", "storyId": story_id, "storyVersionId": version_id, "validUntil": None,
                "verifiedAt": now_ms, "version": version}


class StoryRejected(Exception):
    def __init__(self, attempts, prompt_version):
        super().__init__("STORY_REJECTED")
        self.attempts, self.prompt_version = attempts, prompt_version


def estimated_ms(text):
    return max(1000, int(round(len(text) / CHARS_PER_SECOND, 0)) * 1000)


def target_window(seconds):
    if not seconds:
        return None
    centre = seconds * CHARS_PER_SECOND
    return {"min": int(centre * (1 - LENGTH_TOLERANCE)), "max": int(centre * (1 + LENGTH_TOLERANCE))}


def _korean_number(word):
    """천구백구십삼 -> 1993. Returns None for words that are not a plain Sino-Korean number."""
    total, current, digit = 0, 0, None
    for char in word:
        if char in _KOREAN_DIGIT:
            if digit is not None:
                return None
            digit = _KOREAN_DIGIT[char]
        elif char in _KOREAN_UNIT:
            current += (digit or 1) * _KOREAN_UNIT[char]
            digit = None
        elif char == "만":
            total = (total + current + (digit or 0) or 1) * 10000
            current, digit = 0, None
        else:
            return None
    return total + current + (digit or 0)


def numbers_in(text):
    found = {value.replace(",", "") for value in _DIGITS.findall(text)}
    for word, _unit in _KOREAN_NUMBER.findall(text):
        value = _korean_number(word)
        if value is not None and value >= 10:
            found.add(str(value))
    return found


def _bigrams(sentence):
    compact = re.sub(r"\s+", "", sentence)
    return {compact[i:i + 2] for i in range(len(compact) - 1)}


def _repeated(paragraphs):
    sentences = [s.strip() for p in paragraphs for s in _SENTENCE.findall(p["text"]) if len(s.strip()) >= 12]
    grams = [_bigrams(s) for s in sentences]
    for i in range(len(grams)):
        for j in range(i + 1, len(grams)):
            union = grams[i] | grams[j]
            if union and len(grams[i] & grams[j]) / len(union) >= REPEAT_SIMILARITY:
                return sentences[j]
    return None


def next_ending(previous=None, used=()):
    """Rotate the typed endings evenly: the least used one that differs from the previous story's ending."""
    counts = {e: list(used).count(e) for e in TYPED_ENDINGS}
    choices = [e for e in TYPED_ENDINGS if e != previous] or list(TYPED_ENDINGS)
    return min(choices, key=lambda e: (counts[e], TYPED_ENDINGS.index(e)))


def normalize(result):
    """Typed output: paragraph text = its sentences joined, so every later check and the bundle see plain text."""
    for paragraph in result.get("paragraphs", []):
        if "sentences" in paragraph:
            paragraph["text"] = " ".join(s["text"].strip() for s in paragraph["sentences"])
    return result


def structure_reasons(result, region_name=None, names=None):
    """Typed output: the story opens and closes with a bridge that claims nothing new."""
    units = [s for p in result["paragraphs"] for s in p.get("sentences", [])]
    if not units:
        return []
    reasons = []
    for label, unit in (("시작", units[0]), ("끝", units[-1])):
        kind = effective_type(unit["text"], unit["type"], region_name, names)
        if kind != "bridge":
            reasons.append(f"{label} 문장이 연결 문장이 아님({kind}): '{unit['text'][:30]}'")
    if not any(s["type"] == "fact" for s in units):
        reasons.append("사실 문장 없음")
    return reasons


def check_story(result, evidence, *, schema=None, target_seconds=None, existing=(), region_name=None):
    """-> list of human-readable reasons (empty = accepted). Never raises for model output."""
    if schema is not None and not Draft202012Validator(schema).is_valid(result):
        return ["형식(스키마) 불일치"]
    normalize(result)
    reasons = structure_reasons(result, region_name, fact_names(evidence))
    try:
        validate_semantics({"task": "story", "payload": {}}, result, evidence)
    except Failure:
        reasons.append("근거 연결 오류(없는 factId·출처 불일치·같은 사실 반복 인용·kind 불일치)")
    paragraphs = result["paragraphs"]
    if len(paragraphs) > MAX_PARAGRAPHS:
        reasons.append(f"문단 수 {len(paragraphs)} > {MAX_PARAGRAPHS}")
    for index, paragraph in enumerate(paragraphs, 1):
        text = paragraph["text"]
        if not MIN_PARAGRAPH_CHARS <= len(text) <= MAX_PARAGRAPH_CHARS:
            reasons.append(f"문단 {index} 길이 {len(text)}자(허용 {MIN_PARAGRAPH_CHARS}~{MAX_PARAGRAPH_CHARS})")
        for pattern, label in BANNED:
            match = pattern.search(text)
            if match:
                reasons.append(f"문단 {index} 금지 표현: {label} ('{match.group(0).strip()}')")
        cited = " ".join(evidence.facts[f]["text"] for f in paragraph["factRevisionIds"] if f in evidence.facts)
        allowed = numbers_in(cited)
        outside = sorted(n for n in numbers_in(text) if n not in allowed)
        if outside:
            reasons.append(f"문단 {index} 근거 밖 숫자: {', '.join(outside)}")
    window = target_window(target_seconds)
    total = sum(len(p["text"]) for p in paragraphs)
    if window and not window["min"] <= total <= window["max"]:
        reasons.append(f"전체 길이 {total}자(목표 {window['min']}~{window['max']}자)")
    repeated = _repeated(paragraphs)
    if repeated:
        reasons.append(f"같은 내용 반복: '{repeated[:30]}'")
    reasons += location_reasons(paragraphs, region_name)
    previous = existing[-1] if existing else None
    if previous and result.get("opening") and (previous.get("opening"), previous.get("ending")) == (result.get("opening"), result.get("ending")):
        reasons.append(f"직전 이야기와 같은 시작·끝 조합({result['opening']}/{result['ending']})")
    used = {evidence.facts[f]["factId"] for p in paragraphs for f in p["factRevisionIds"] if f in evidence.facts}
    for story in existing:
        if used and used == set(story.get("factIds", ())):
            reasons.append(f"기존 이야기 '{story.get('title', '')}'와 같은 사실 묶음")
    return reasons


def evidence_from_facts(facts):
    """Facts only (source text is not sent): the model may use nothing beyond the supplied statements."""
    sources, rows = {}, {}
    for fact in facts:
        for source_id in fact["sourceRevisionIds"]:
            sources[source_id] = {"sourceRevisionId": source_id, "text": ""}
        kind = "tradition" if fact["kind"] == "tradition" else "fact"
        rows[fact["factRevisionId"]] = {"factRevisionId": fact["factRevisionId"], "factId": fact["factId"], "kind": kind,
                                        "text": fact["statement"], "sourceRevisionIds": sorted(fact["sourceRevisionIds"])}
    return EvidenceContext(sources, rows)


def lab_schema(typed=False):
    """The server's story model schema plus a title and core question for the pack row. typed (story guide v3+):
    each paragraph is a list of sentences marked fact/bridge instead of one text."""
    schema = model_schema("story")
    if typed:
        paragraph = schema["properties"]["paragraphs"]["items"]
        del paragraph["properties"]["text"]
        paragraph["properties"]["sentences"] = {"type": "array", "minItems": 1, "maxItems": 6, "items": {
            "type": "object", "additionalProperties": False, "required": ["text", "type"],
            "properties": {"text": {"type": "string", "minLength": 1, "maxLength": 160}, "type": {"enum": ["fact", "bridge"]}}}}
        paragraph["required"] = ["sentences" if key == "text" else key for key in paragraph["required"]]
    schema["properties"]["title"] = {"type": "string", "minLength": 1, "maxLength": 60}
    schema["properties"]["coreQuestion"] = {"type": "string", "minLength": 1, "maxLength": 160}
    schema["properties"]["opening"] = {"enum": list(TYPED_OPENINGS if typed else OPENINGS)}
    schema["properties"]["ending"] = {"enum": list(TYPED_ENDINGS if typed else ENDINGS)}
    schema["required"] = [*schema["required"], "title", "coreQuestion", "opening", "ending"]
    return schema


class StoryWriter:
    def __init__(self, provider=None, prompt_version=None, judge=None):
        """judge: a GroundingJudge, or True for one on the same provider. Off by default: the app's story API keeps one
        model call per request (no timing change); the lab and pack builds turn it on."""
        self.provider = provider
        self.judge = GroundingJudge(provider) if judge is True else judge
        self.guide_version, self.guide_prompt = story_prompt()
        self.guide_typed = story_output_typed()
        self.prompt_version = prompt_version or LEGACY_VERSION
        self._prompt(self.prompt_version)

    def _prompt(self, version):
        """-> system prompt text, or None for the legacy conversation prompt (the provider's default)."""
        if version == LEGACY_VERSION:
            return None
        if version == self.guide_version:
            return self.guide_prompt
        raise ValueError(f"UNKNOWN_PROMPT_VERSION: {version} (available: {LEGACY_VERSION}, {self.guide_version})")

    async def _attempts(self, provider, payload, evidence, schema, version, target_seconds, existing, region_name=None,
                        typed=False):
        prompt = self._prompt(version)
        attempts = []
        for _ in range(2):
            body = payload if not attempts else {**payload, "retryReason": "; ".join(attempts[-1]["reasons"])}
            # The legacy prompt is the provider's default: keep the exact call the server always made.
            if prompt is None:
                generated = await provider.generate("story", body, evidence, schema)
            else:
                generated = await provider.generate("story", body, evidence, schema, system_prompt=prompt)
            result = copy.deepcopy(generated.result)
            if typed and Draft202012Validator(schema).is_valid(result):
                # Derived data: a paragraph's sources are exactly its facts' sources (story_evidence.md). The lab fills
                # them in instead of failing on bookkeeping; the app API still requires the model's own list.
                for paragraph in result["paragraphs"]:
                    paragraph["sourceRevisionIds"] = sorted({sid for f in paragraph["factRevisionIds"] if f in evidence.facts
                                                             for sid in evidence.facts[f]["sourceRevisionIds"]})
            reasons = check_story(result, evidence, schema=schema, target_seconds=target_seconds, existing=existing,
                                  region_name=region_name)
            if payload.get("ending") and isinstance(result, dict) and result.get("ending") != payload["ending"]:
                reasons.append(f"정해진 끝맺음({payload['ending']})과 다름({result.get('ending')})")
            judgments = []
            if not reasons and self.judge is not None:
                judgments = await self.judge.judge(result["paragraphs"], evidence, region_name)
                reasons = judgment_reasons(judgments)
            if not reasons:
                result["_judgments"] = judgments
                return generated, result, attempts
            attempts.append({"reasons": reasons, "output": result, "judgments": judgments})
        raise StoryRejected(attempts, version)

    async def write(self, request: StoryRequest) -> StoryDraft:
        """Lab / pack path. Raises StoryRejected with every attempt's reasons."""
        if self.provider is None:
            raise Failure("UPSTREAM_UNAVAILABLE", 503, True)
        version = request.prompt_version or self.prompt_version
        evidence = evidence_from_facts(request.facts)
        payload = {"region": {"code": request.region_code, "name": request.region_name},
                   "targetChars": target_window(request.target_seconds),
                   "interestFocus": list(request.interest_focus), "existingStories": list(request.existing)}
        if request.ending:
            payload["ending"] = request.ending
        if request.existing and request.existing[-1].get("opening"):
            payload["avoid"] = {"opening": request.existing[-1]["opening"], "ending": request.existing[-1].get("ending")}
        typed = version == self.guide_version and self.guide_typed
        generated, result, attempts = await self._attempts(self.provider, payload, evidence, lab_schema(typed), version,
                                                           request.target_seconds, request.existing, request.region_name, typed)
        return StoryDraft(result["title"], result["coreQuestion"], result["paragraphs"], version,
                          attempts=[{"reasons": a["reasons"], "judgments": a["judgments"]} for a in attempts],
                          model_id=generated.model_id, opening=result["opening"], ending=result["ending"],
                          judgments=result.get("_judgments", []))

    async def generate_api(self, payload, evidence, provider):
        """The app's story API (task=story): same request, same v0.8 result schema, plus the writer's checks and one
        retry. The app payload carries no target length, so the total-length check does not apply."""
        try:
            generated, result, _attempts = await self._attempts(provider, payload, evidence, model_schema("story"),
                                                                self.prompt_version, None, ())
        except StoryRejected:
            raise Failure("INVALID_MODEL_OUTPUT", 422) from None
        result.pop("_judgments", None)
        return type(generated)(result, generated.model_id, generated.provider, generated.input_tokens, generated.output_tokens)
