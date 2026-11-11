"""Candidate scoring behind interfaces, so the rule version can be swapped for an embedding version later.

Scorer        : which landmark candidates are worth announcing (used now, rules-v0).
InterestScorer: CONTENT_PIPELINE.md 5-1 -- per-story relevance to the 12 interests. Only the seat exists
                before 1차; packs carry no interestScores yet.
"""
from dataclasses import dataclass, field
import re
from typing import Protocol

from .visibility import RADIUS_M


@dataclass
class Candidate:
    place: object            # model.Place
    visibility: str          # small | large
    route_distance_m: float  # lateral distance from the route line
    along_m: float           # distance along the route to the closest point
    side: int                # +1 right / -1 left of travel
    region_code: str | None
    document: object = None  # model.SourceDocument
    facts: list = field(default_factory=list)
    heritage_grade: str | None = None
    coordinate_document: object = None  # curated places only: a separate source row for the place's point coordinates


class Scorer(Protocol):
    version: str

    def score(self, candidate: Candidate) -> float: ...


class RuleScorer:
    """rules-v0: classification + heritage grade + description length + closeness to the road."""
    version = "rules-v0"
    GRADE = {"국보": 3.0, "사적": 2.6, "명승": 2.6, "보물": 2.2, "천연기념물": 2.0, "국가민속문화유산": 2.0,
             "국가등록유산": 1.8, "시도유형문화유산": 1.4, "시도기념물": 1.4, "시도민속문화유산": 1.3,
             "시도등록유산": 1.2, "시도자연유산": 1.3, "시도자연유산자료": 1.0, "문화유산자료": 1.0}
    CATEGORY = {"heritage": 1.6, "culture": 1.3, "nature": 1.4, "building": 1.2, "leisure": 1.0, "other": 0.5}

    def score(self, candidate):
        base = self.GRADE.get(candidate.heritage_grade or "", self.CATEGORY.get(candidate.place.category, 0.5))
        is_heritage = 0.6 if candidate.place.category == "heritage" or candidate.heritage_grade else 0.0
        description = min(1.0, len(candidate.document.text) / 600.0) if candidate.document else 0.0
        closeness = max(0.0, 1.0 - candidate.route_distance_m / RADIUS_M[candidate.visibility])
        landmark_size = 0.4 if candidate.visibility == "large" else 0.0
        return round(base + is_heritage + description + closeness + landmark_size, 4)


class InterestScorer(Protocol):
    """5-1 자리: story -> {interest: 0..1}. v0 rules / v1 embeddings plug in here (1차 후)."""
    version: str

    def interest_scores(self, story: dict) -> dict: ...


class NoInterestScorer:
    """Before 1차: packs leave interestScores out entirely (the app reads packs with or without it)."""
    version = "none"

    def interest_scores(self, story):
        return {}


# ----------------------------------------------------------------------------------------------- story seeds

class StorySeedScorer:
    """story-seed-rules-v0 (CONTENT_PIPELINE.md 5-1 생성 풀): which fact bundle (seed) is worth a story.

    + people, name origins, records, everyday life        - lists of architectural dimensions, promotional wording
    - heavy subjects (death, execution, torture, war damage) -- strongly, even when the user likes history
    Religious facilities are tagged; select_seed avoids two in a row (and slightly prefers other subjects).
    """
    version = "story-seed-rules-v0"
    RULES = (
        ("사람", 1.0, ("선생", "신부", "대군", "열사", "법사", "대종사", "스님", "주교", "본명", "아호", "라는 사람", "유림", "의 형", "후손", "실향민")),
        ("이름 유래", 1.0, ("이름", "뜻", "유래", "불린다", "불렸다", "불리고", "따왔", "에서 온", "옛 이름", "명칭")),
        ("기록", 0.6, ("기록", "실록", "연혁", "서한", "문헌")),
        ("생활", 0.6, ("장에서", "시장", "거래", "생산", "만든", "과자", "음식", "놀이", "기예", "교우촌", "마을", "재배")),
    )
    # Promotional / visitor-information wording describes a service, not a story.
    PROMOTIONAL = ("제공한다", "할 수 있도록", "마련되어", "만나볼 수", "즐길 수", "체험할 수", "이용할 수", "맞이하고")
    PROMOTIONAL_PENALTY = 1.0
    DIMENSIONS = ("기단", "면마다", "모서리", "높이", "길이", "총면적", "면적", "㎡", "m²", "평)", "층 높이", "규모")
    HEAVY = ("죽음", "사망", "순교", "처형", "시신", "장례", "고문", "학살", "전쟁", "임진왜란", "왜란", "피해", "소실", "박해", "순국", "형벌")
    # Regexes: "안성지역" is not a 성지.
    RELIGIOUS = (r"사찰", r"절 ", r"(?<!안)성지", r"성당", r"교회", r"불교", r"천주교", r"불상", r"불두", r"와불", r"대웅전", r"대적광전", r"관음", r"법당", r"교구")
    RELIGIOUS_PENALTY, REPEAT_RELIGIOUS_PENALTY, HEAVY_PENALTY, DIMENSION_PENALTY = 0.5, 2.0, 3.0, 1.0

    def score(self, statements):
        """statements: the seed's fact sentences -> (score, reasons, flags)."""
        text = " ".join(statements)
        score, reasons = 0.0, []
        for label, points, words in self.RULES:
            hits = [w for w in words if w in text]
            if hits:
                score += points
                reasons.append(f"{label} +{points:g}({hits[0].strip()})")
        dimensions = [w for w in self.DIMENSIONS if w in text]
        if len(dimensions) >= 2:
            score -= self.DIMENSION_PENALTY
            reasons.append(f"치수 나열 -{self.DIMENSION_PENALTY:g}({', '.join(dimensions[:2])})")
        promotional = [w for w in self.PROMOTIONAL if w in text]
        if promotional:
            score -= self.PROMOTIONAL_PENALTY
            reasons.append(f"홍보·안내 문구 -{self.PROMOTIONAL_PENALTY:g}({promotional[0]})")
        heavy = [w for w in self.HEAVY if w in text]
        if heavy:
            score -= self.HEAVY_PENALTY
            reasons.append(f"무거운 소재 -{self.HEAVY_PENALTY:g}({heavy[0]})")
        religious = [m.group(0).strip() for m in (re.search(w, text) for w in self.RELIGIOUS) if m]
        if religious:
            score -= self.RELIGIOUS_PENALTY
            reasons.append(f"종교 시설 -{self.RELIGIOUS_PENALTY:g}({religious[0]})")
        richness = min(0.5, 0.15 * len(statements))
        score += richness
        reasons.append(f"사실 {len(statements)}개 +{richness:g}")
        return round(score, 2), reasons, {"heavy": bool(heavy), "religious": bool(religious)}

    def select(self, seeds, *, previous_religious=False, used=()):
        """seeds: [{"id", "title", "statements", ...}] -> seeds ranked, each with score/reasons/flags (best first)."""
        ranked = []
        for seed in seeds:
            if seed["id"] in used:
                continue
            score, reasons, flags = self.score(seed["statements"])
            if flags["religious"] and previous_religious:
                score -= self.REPEAT_RELIGIOUS_PENALTY
                reasons.append(f"직전도 종교 시설 -{self.REPEAT_RELIGIOUS_PENALTY:g}")
            ranked.append({**seed, "score": round(score, 2), "reasons": reasons, **flags})
        ranked.sort(key=lambda s: (-s["score"], s["id"]))
        for rank, seed in enumerate(ranked, 1):
            seed["rank"], seed["candidates"] = rank, len(ranked)
        return ranked
