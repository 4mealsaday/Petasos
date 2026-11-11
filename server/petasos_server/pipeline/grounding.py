"""Grounding stage shared by StoryWriter and IntroWriter (CONTENT_PIPELINE.md 9장 검증 보강).

1. Split a paragraph into sentences.
2. Decide which sentences are factual claims. NOT judged (explicit criteria):
   - a question: the sentence ends with "?" (e.g. "다음에 이 길을 지날 때 … 보이지 않을까요?");
   - a reflection/imagination line without numbers: contains 떠올려·떠올리·떠오르·궁금·생각해 보·그려 보·상상해 and no digit
     (e.g. "그 길을 한번 떠올려 보세요.");
   - a current-location line: contains 지나고 있·들어서고 있·지나는 중 (checked separately by location_reasons).
   Every other sentence is a claim, including evaluations ("유명한", "의미 있는") and links between facts.
3. A model judges each claim against the facts its paragraph cites: supported / insufficient / contradicted.
   insufficient and contradicted become rejection reasons (the writer retries once with them, then rejects).
"""
import re

from ..evidence import EvidenceContext
from ..prompts import load

SUPPORTED, INSUFFICIENT, CONTRADICTED, NOT_CLAIM = "supported", "insufficient", "contradicted", "not_claim"
VERDICT_LABELS = {SUPPORTED: "지지", INSUFFICIENT: "불충분", CONTRADICTED: "모순", NOT_CLAIM: "판정 제외"}
_SENTENCE = re.compile(r"[^.?!。]+[.?!。]?")
_REFLECTION = re.compile(r"(떠올려|떠올리|떠오르|궁금|생각해 ?보|그려 ?보|상상해)")
LOCATION_VERB = re.compile(r"(지나고 있|들어서고 있|지나는 중)")
# Place-like words: administrative units and landmarks. Region names are allowed; everything else is not where the car is.
_PLACE = re.compile(r"([가-힣]{1,12}?(?:동|면|읍|리|역|산|공원|탑|궁|사|성지|성|마을|거리|시장|구|군|시))(?=[을를은는이가에의도와과,\s]|$|쪽|근처|앞)")
# Ordinary words that end like a place name.
_COMMON = {"우리", "도시", "마을", "거리", "사이", "근처", "이곳", "요즘", "다시", "역시", "혹시", "당시", "동시"}
SCHEMA = {"type": "object", "additionalProperties": False, "required": ["verdicts"],
          "properties": {"verdicts": {"type": "array", "maxItems": 40, "items": {
              "type": "object", "additionalProperties": False, "required": ["id", "verdict", "reason"],
              "properties": {"id": {"type": "string"}, "verdict": {"enum": [SUPPORTED, INSUFFICIENT, CONTRADICTED]},
                             "reason": {"type": "string", "maxLength": 200}}}}}}


def sentences(text):
    return [s.strip() for s in _SENTENCE.findall(text) if s.strip()]


def is_claim(sentence):
    if sentence.endswith("?") or LOCATION_VERB.search(sentence):
        return False
    return not (_REFLECTION.search(sentence) and not re.search(r"\d", sentence))


def region_aliases(region_name):
    parts = [p for p in re.split(r"\s+", region_name or "") if p]
    aliases = set(parts)
    for part in parts:
        for suffix in ("시", "구", "군"):
            if part.endswith(suffix) and len(part) > 2:
                aliases.add(part[:-1])
            aliases.add(part + suffix)
    return aliases


def location_reasons(paragraphs, region_name):
    """First paragraph: a "지나고 있" sentence may name only the region (payload.region.name)."""
    if not region_name or not paragraphs:
        return []
    allowed = region_aliases(region_name)
    reasons = []
    for sentence in sentences(paragraphs[0]["text"]):
        if not LOCATION_VERB.search(sentence):
            continue
        invented = [w for w in _PLACE.findall(sentence) if w not in allowed and w not in _COMMON]
        if invented:
            reasons.append(f"현재 위치 지어내기: '{', '.join(dict.fromkeys(invented))}' (지금 지나는 곳은 '{region_name}'만 말한다)")
    return reasons


class GroundingJudge:
    """Asks the model whether each claim sentence is supported by the facts its paragraph cites."""

    def __init__(self, provider):
        self.provider = provider
        self.version, self.prompt = load("grounding_judge")

    async def judge(self, paragraphs, evidence, region_name=None):
        """paragraphs: [{text, factRevisionIds}] -> [{paragraph, sentence, verdict, reason}] in reading order."""
        rows, items = [], []
        for p_index, paragraph in enumerate(paragraphs, 1):
            for s_index, sentence in enumerate(sentences(paragraph["text"]), 1):
                row = {"paragraph": p_index, "sentence": sentence, "verdict": NOT_CLAIM, "reason": ""}
                rows.append(row)
                if is_claim(sentence):
                    items.append((f"p{p_index}s{s_index}", row, sorted(paragraph["factRevisionIds"])))
        if not items:
            return rows
        cited = {f for _, _, ids in items for f in ids}
        facts = {fid: row for fid, row in evidence.facts.items() if fid in cited}
        sources = {sid: evidence.sources[sid] for f in facts.values() for sid in f["sourceRevisionIds"] if sid in evidence.sources}
        payload = {"sentences": [{"id": key, "text": row["sentence"], "factRevisionIds": ids} for key, row, ids in items]}
        if region_name:
            # Facts are collected per region (eval set / region pack): "이 지역에 있는 ○○" is given, not invented.
            payload["region"] = {"name": region_name}
        generated = await self.provider.generate("grounding", payload, EvidenceContext(sources, facts), SCHEMA,
                                                 system_prompt=self.prompt)
        verdicts = {v["id"]: v for v in generated.result.get("verdicts", [])}
        for key, row, _ in items:
            verdict = verdicts.get(key)
            # A sentence the judge skipped is not proven: treat it as insufficient.
            row["verdict"] = verdict["verdict"] if verdict else INSUFFICIENT
            row["reason"] = (verdict or {}).get("reason", "") if verdict else "판정 누락"
        return rows


def judgment_reasons(rows):
    return [f"문단 {r['paragraph']} 근거 {VERDICT_LABELS[r['verdict']]}: '{r['sentence'][:40]}'" + (f" — {r['reason']}" if r["reason"] else "")
            for r in rows if r["verdict"] in (INSUFFICIENT, CONTRADICTED)]
