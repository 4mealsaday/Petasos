"""Candidate scoring behind interfaces, so the rule version can be swapped for an embedding version later.

Scorer        : which landmark candidates are worth announcing (used now, rules-v0).
InterestScorer: CONTENT_PIPELINE.md 5-1 -- per-story relevance to the 12 interests. Only the seat exists
                before 1차; packs carry no interestScores yet.
"""
from dataclasses import dataclass, field
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
